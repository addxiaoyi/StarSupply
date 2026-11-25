const fs = require('node:fs');
const path = require('node:path');
const {isDeepStrictEqual} = require('node:util');
const {validatePlan, ITEMS} = require('./logistics-cycle.cjs');
const {hash, evidenceFile, readProductionEvidence, linkProductionEvidence} = require('./production-evidence.cjs');
const {writeJson} = require('./durable-json.cjs');
const {phaseRequirements} = require('./production-targets.cjs');
const requireThat = (value, message) => { if (!value) throw new Error(message); };

function sessionPath(stage, plan) {
  validatePlan(plan);
  return path.join(stage, 'folia-proof', `survival-production-${plan.runId}.json`);
}

function readSession(stage, plan, player, plugins, worldIdentity) {
  const journal = JSON.parse(fs.readFileSync(sessionPath(stage, plan), 'utf8'));
  requireThat(journal.version === 1 && isDeepStrictEqual(journal.plan, plan) && journal.player === player
    && isDeepStrictEqual(journal.plugins, plugins) && isDeepStrictEqual(journal.worldIdentity, worldIdentity),
  'Production session plan, player, world or plugins changed');
  requireThat(Array.isArray(journal.results) && journal.results.length <= 100, 'Invalid production session results');
  for (const row of [...journal.results, ...(journal.noGameplay ?? [])]) requireThat(hash(fs.readFileSync(evidenceFile(stage, row.path))) === row.sha256,
    'Recorded production result changed');
  return journal;
}

function acceptPending(stage, journal) {
  const pending = journal.pending;
  const file = evidenceFile(stage, pending.result);
  const bytes = fs.readFileSync(file), run = JSON.parse(bytes);
  requireThat(run.productionSession === journal.plan.runId && run.phase === pending.phase
    && run.savedBefore?.sha256 === journal.checkpoint.sha256, 'Pending production result identity changed');
  if (!run.clientExit && run.savedCheckpoint?.sha256 === journal.checkpoint.sha256
      && run.normalStopObserved && run.serverExit?.code === 0 && run.gamePortClosed && run.rconPortClosed) {
    const log = fs.readFileSync(evidenceFile(stage, run.clientLog));
    requireThat(hash(log) === run.clientLogSha256 && !log.toString('utf8').trim(), 'Unexpected client activity before production');
    (journal.noGameplay ??= []).push({path: pending.result, sha256: hash(bytes)});
    journal.pending = null;
    return null;
  }
  const paths = [...journal.results.map(row => row.path), pending.result];
  const evidence = readProductionEvidence(stage, paths, run.savedCheckpoint, journal.player, journal.plugins, {strict: true});
  requireThat(isDeepStrictEqual(evidence.worldIdentity, journal.worldIdentity), 'Production result world changed');
  journal.results.push({path: pending.result, sha256: hash(bytes)});
  journal.checkpoint = run.savedCheckpoint;
  journal.pending = null;
  return evidence;
}

// A pending attempt is recorded before the server starts. A committed result can
// be adopted after a launcher interruption; changed saves without that result
// need inspection, never an invented provenance baseline.
function prepareProduction({stage, plan, player, plugins, worldIdentity, savedBefore}) {
  requireThat(plan.traceProduction === true && !plan.productionEvidence, 'Traced sessions manage their own production evidence');
  const file = sessionPath(stage, plan);
  if (!fs.existsSync(file)) writeJson(file, {version: 1, plan, player, plugins, worldIdentity,
    checkpoint: savedBefore, baseline: savedBefore, results: [], pending: null}, {exclusive: true});
  const journal = readSession(stage, plan, player, plugins, worldIdentity);
  if (journal.pending) {
    requireThat(/^folia-proof\/survival-progression-[\w-]+-result\.json$/.test(journal.pending.result), 'Invalid pending result path');
    if (fs.existsSync(path.join(stage, journal.pending.result))) acceptPending(stage, journal);
    else requireThat(savedBefore.sha256 === journal.checkpoint.sha256, 'Unrecorded production changed the player save; inspect before resuming');
    journal.pending = null;
    writeJson(file, journal);
  }
  requireThat(savedBefore.sha256 === journal.checkpoint.sha256 && savedBefore.playerId === journal.checkpoint.playerId,
    'Production checkpoint changed outside the recorded session');
  const evidence = readProductionEvidence(stage, journal.results.map(row => row.path), savedBefore, player, plugins, {strict: true});
  return {journal, evidence};
}

function beginProduction(stage, plan, prepared, phase, result, contextFile) {
  requireThat(!prepared.journal.pending && prepared.journal.results.length < 100, 'Production attempt is already pending or exhausted');
  const context = {plan, player: prepared.journal.player, savedBefore: prepared.journal.checkpoint,
    traced: prepared.evidence.balance};
  writeJson(contextFile, context, {exclusive: true});
  prepared.journal.pending = {phase, result};
  writeJson(sessionPath(stage, plan), prepared.journal);
  return context;
}

function finishProduction(stage, plan, prepared) {
  const evidence = acceptPending(stage, prepared.journal);
  writeJson(sessionPath(stage, plan), prepared.journal);
  return evidence;
}

function verifyTracedPhase(phase, plan, evidence, inventory) {
  const required = phaseRequirements(phase, plan, evidence.balance);
  for (const [item, count] of Object.entries(required)) requireThat(
    ((phase === 'surface' ? inventory : evidence.balance)[item] ?? 0) >= count,
    `Traced ${phase} requires ${count} ${item}; existing untraced stock does not complete production`);
  return Object.fromEntries(Object.values(ITEMS).map(item => [item, evidence.balance[item] ?? 0]));
}

function productionForLogistics(stage, plan, player, plugins, worldIdentity, savedBefore) {
  let journal = readSession(stage, plan, player, plugins, worldIdentity);
  if (journal.pending) journal = prepareProduction({stage, plan, player, plugins, worldIdentity, savedBefore}).journal;
  const evidence = linkProductionEvidence(stage, {...plan, productionEvidence: journal.results.map(row => row.path)},
    savedBefore, player, plugins);
  requireThat(evidence.survivalProductionVerified && isDeepStrictEqual(evidence.worldIdentity, worldIdentity),
    'Replenish traced production before logistics; delivered quantities need complete source evidence');
  return evidence;
}

module.exports = {sessionPath, prepareProduction, beginProduction, finishProduction, verifyTracedPhase, productionForLogistics};
