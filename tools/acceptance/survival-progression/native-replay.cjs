const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {isDeepStrictEqual} = require('node:util');
const {journalPath, readSnapshot, verifyCycle} = require('./logistics-cycle.cjs');
const {writeJson} = require('./durable-json.cjs');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const requireThat = (condition, message) => { if (!condition) throw new Error(message); };
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

function decimalKey(value) {
  requireThat(typeof value === 'string' && /^-?(0|[1-9]\d*)(\.\d+)?$/.test(value), 'Exact decimal treasury evidence required');
  const negative = value.startsWith('-');
  const [integer, fraction = ''] = value.replace(/^-/, '').split('.');
  const trimmed = fraction.replace(/0+$/, '');
  return `${negative && (BigInt(integer) !== 0n || trimmed) ? '-' : ''}${integer}:${trimmed}`;
}

function validateRetry(row, credit, previous) {
  const receipt = row.receipt;
  requireThat(row.type === 'retry-passed' && UUID.test(row.bootId) && UUID.test(row.correlation)
    && receipt?.request === credit.id && receipt.army === credit.army && receipt.nation === credit.nation
    && receipt.requestedPoints === credit.points && Number.isInteger(receipt.appliedPoints)
    && receipt.appliedPoints >= 0 && receipt.appliedPoints <= credit.points, 'Core retry receipt does not match the physical credit');
  requireThat(row.conflictRejected === true && Number.isInteger(row.nativeSupplyBefore)
    && row.nativeSupplyBefore >= 0 && row.nativeSupplyBefore <= 100 && row.nativeSupplyBefore === row.nativeSupplyAfter
    && decimalKey(row.treasuryBefore) === decimalKey(row.treasuryAfter), 'Core retry changed supply or treasury');
  if (previous) {
    requireThat(previous.bootId !== row.bootId, 'Core evidence is from the same plugin boot');
    requireThat(isDeepStrictEqual(previous.receipt, receipt), 'Core receipt changed across restart');
  }
  return row;
}

function validateAccounts(row, journal, previous) {
  requireThat(row.type === 'accounts' && UUID.test(row.bootId) && UUID.test(row.correlation)
    && row.player === offlinePlayerId(journal.player) && row.nation === journal.nation, 'Account evidence identity mismatch');
  decimalKey(row.playerBalance); decimalKey(row.treasuryBalance);
  if (previous) requireThat(decimalKey(row.playerBalance) === decimalKey(previous.playerBalance), 'Player balance changed during receipt replay');
  return row;
}

function assertHistoricalRows(before, after, journal) {
  const purchaseIds = new Set(journal.orders.map(row => row.purchase));
  const shipmentIds = new Set(journal.orders.map(row => row.shipment));
  const consumptionKey = row => `${row.army}:${row.slot}:${row.interval_ms}:${row.resource}`;
  const originalConsumption = new Set(before.consumption.map(consumptionKey));
  const baselineConsumption = new Set(journal.baseline.consumption.map(consumptionKey));
  const originalCredits = new Set(before.credits.map(row => row.id));
  const baselineCredits = new Set(journal.baseline.credits.map(row => row.id));
  const selected = {
    purchases: row => purchaseIds.has(row.id), shipments: row => shipmentIds.has(row.id),
    transfers: row => purchaseIds.has(row.purchase),
    settlements: row => purchaseIds.has(row.order_id) || shipmentIds.has(row.order_id),
    consumption: row => originalConsumption.has(consumptionKey(row)) && !baselineConsumption.has(consumptionKey(row)),
    credits: row => originalCredits.has(row.id) && !baselineCredits.has(row.id)
  };
  const sorted = rows => rows.map(row => JSON.stringify(row)).sort();
  for (const [table, include] of Object.entries(selected)) requireThat(
    isDeepStrictEqual(sorted(before[table].filter(include)), sorted(after[table].filter(include))),
    `Historical ${table} changed across restart`);
}

function prepareReplay(stage, plan, plugins, savedBefore) {
  requireThat(plan.nativeReplay === true, 'Enable nativeReplay in the original logistics plan');
  const journal = JSON.parse(fs.readFileSync(journalPath(stage, plan), 'utf8'));
  requireThat(isDeepStrictEqual(journal.plan, plan) && isDeepStrictEqual(journal.plugins, plugins),
    'Replay plan or deployed plugins changed');
  requireThat(journal.state === 'stopped-and-verified' && journal.nativeBeforeRestart?.length === plan.cycles
    && journal.accountsBeforeRestart && journal.stoppedSnapshot, 'Finish the logistics run, native probes and normal stop first');
  requireThat(savedBefore.gameMode === 'survival' && savedBefore.playerId === journal.savedAfter.playerId
    && savedBefore.sha256 === journal.savedAfter.sha256, 'Saved player changed before restart');
  return journal;
}

async function probeCredits({stage, plan, command, until, restarting = false}) {
  const file = journalPath(stage, plan), journal = JSON.parse(fs.readFileSync(file, 'utf8'));
  const snapshot = readSnapshot(stage, plan), result = verifyCycle(plan, journal, snapshot);
  if (restarting) assertHistoricalRows(journal.stoppedSnapshot, snapshot, journal);
  const evidence = path.join(stage, 'plugins', 'SupplyNativeProof', 'evidence.jsonl');
  const rows = () => {
    const contents = fs.readFileSync(evidence, 'utf8');
    return contents.slice(0, contents.lastIndexOf('\n') + 1).split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
  };
  const proofs = [];
  const accounts = async previous => {
    const correlation = randomUUID();
    await command(`supplynativeproof accounts ${journal.nation} ${offlinePlayerId(journal.player)} ${correlation}`);
    const row = await until(() => rows().find(row => row.correlation === correlation), 'Core player balance', 30000);
    return validateAccounts(row, journal, previous);
  };
  const beforeAccounts = await accounts(restarting ? journal.accountsBeforeRestart : null);
  if (restarting) requireThat(beforeAccounts.bootId !== journal.accountsBeforeRestart.bootId, 'Account evidence is from the same plugin boot');
  for (const request of result.credits) {
    const credit = snapshot.credits.find(row => row.id === request), correlation = randomUUID();
    const previous = restarting ? journal.nativeBeforeRestart.find(row => row.receipt.request === request) : null;
    if (restarting) requireThat(previous, 'Original Core receipt evidence is missing');
    // Correlation IDs exclude old evidence, including earlier attempts in the same boot.
    await command(`supplynativeproof retry ${request} ${correlation}`);
    const proof = await until(() => rows().find(row => row.correlation === correlation), `Core retry ${request}`, 30000);
    proofs.push(validateRetry(proof, credit, previous));
  }
  const afterAccounts = await accounts(beforeAccounts);
  requireThat(afterAccounts.bootId === beforeAccounts.bootId && proofs.every(row => row.bootId === afterAccounts.bootId),
    'Core probe boot changed during the receipt sequence');
  const after = readSnapshot(stage, plan);
  verifyCycle(plan, journal, after);
  assertHistoricalRows(snapshot, after, journal);
  journal[restarting ? 'nativeAfterRestart' : 'nativeBeforeRestart'] = proofs;
  journal[restarting ? 'accountsAfterRestart' : 'accountsBeforeRestart'] = afterAccounts;
  writeJson(file, journal);
  return proofs;
}

function verifyReplayStopped(stage, plan, before, after) {
  const file = journalPath(stage, plan), journal = JSON.parse(fs.readFileSync(file, 'utf8'));
  requireThat(before.sha256 === journal.savedAfter.sha256 && after.sha256 === before.sha256
    && after.playerId === before.playerId && after.gameMode === 'survival', 'Player save changed during receipt replay');
  requireThat(journal.nativeAfterRestart?.length === plan.cycles, 'Restart Core probes are incomplete');
  const snapshot = readSnapshot(stage, plan);
  verifyCycle(plan, journal, snapshot);
  assertHistoricalRows(journal.stoppedSnapshot, snapshot, journal);
  for (const row of journal.nativeAfterRestart) validateRetry(row,
    snapshot.credits.find(credit => credit.id === row.receipt.request),
    journal.nativeBeforeRestart.find(old => old.receipt.request === row.receipt.request));
  validateAccounts(journal.accountsAfterRestart, journal, journal.accountsBeforeRestart);
  requireThat(journal.accountsAfterRestart.bootId !== journal.accountsBeforeRestart.bootId, 'Account evidence is from the same plugin boot');
  journal.restartVerification = {coreReceiptReplayVerified: true, processRestartReplayVerified: true,
    savedInventoryUnchanged: true, originalSettlementsUnchanged: true, playerBalanceUnchanged: true,
    productionEvidenceLinked: !!journal.productionEvidence?.productionEvidenceLinked,
    survivalProductionVerified: journal.productionEvidence?.survivalProductionVerified === true,
    requests: journal.nativeAfterRestart.map(row => row.receipt.request)};
  writeJson(file, journal);
  return journal.restartVerification;
}

module.exports = {validateRetry, validateAccounts, assertHistoricalRows, prepareReplay, probeCredits, verifyReplayStopped};
