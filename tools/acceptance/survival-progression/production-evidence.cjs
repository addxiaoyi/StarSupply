const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {isDeepStrictEqual} = require('node:util');
const {ITEMS, RESOURCES} = require('./logistics-cycle.cjs');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const {ProductionLedger} = require('./production-ledger.cjs');
const requireThat = (value, message) => { if (!value) throw new Error(message); };
const PHASES = ['mining', 'surface', 'farming', 'honey', 'scaffolding', 'fireworks'];
const hash = bytes => createHash('sha256').update(bytes).digest('hex').toUpperCase();

function evidenceFile(stage, relative) {
  requireThat(typeof relative === 'string' && relative.startsWith('folia-proof/')
    && !relative.split(/[\\/]/).includes('..'), 'Evidence must be under the isolated folia-proof directory');
  const file = fs.realpathSync(path.resolve(stage, relative));
  const root = fs.realpathSync(path.join(stage, 'folia-proof'));
  requireThat(file.startsWith(root + path.sep), 'Evidence path leaves the isolated proof directory');
  return file;
}

function archivedSave(stage, save) {
  requireThat(save?.archive && save.gameMode === 'survival', 'A saved survival NBT archive is required');
  requireThat(hash(fs.readFileSync(evidenceFile(stage, save.archive))) === save.sha256, 'Saved NBT archive hash mismatch');
}

function archiveSavedPlayer(stage, save, prefix) {
  const bytes = fs.readFileSync(path.join(stage, save.path));
  requireThat(hash(bytes) === save.sha256, 'Player save changed while archiving');
  const archive = `folia-proof/${prefix}-${save.sha256}.dat`;
  try { fs.writeFileSync(path.join(stage, archive), bytes, {flag: 'wx'}); }
  catch (error) { if (error.code !== 'EEXIST' || hash(fs.readFileSync(path.join(stage, archive))) !== save.sha256) throw error; }
  return {...save, archive};
}

// This links captured production actions and NBT checkpoints. It deliberately
// does not infer survival acquisition from a pre-existing stack or crafted item.
function readProductionEvidence(stage, results, savedBefore, player, plugins, {complete = false, strict = false} = {}) {
  requireThat(Array.isArray(results) && results.length >= (complete ? 6 : 0) && results.length <= 100
    && new Set(results).size === results.length, 'Provide ordered, distinct production run result paths');
  const phases = new Set(), entries = [];
  let previous, worldIdentity;
  const corePlugins = list => list.filter(row => /^(StarCore|StarSupply)-/.test(row.name));
  const inventoryCounts = list => {
    const counts = {};
    for (const row of list ?? []) counts[row.name] = (counts[row.name] ?? 0) + row.count;
    return counts;
  };
  const balance = {};
  let ledger;
  let protocol;
  for (const relative of results) {
    const bytes = fs.readFileSync(evidenceFile(stage, relative)), run = JSON.parse(bytes);
    requireThat(run.event === 'survival_progression_run' && [1, 2].includes(run.productionProtocolVersion) && PHASES.includes(run.phase)
      && run.player === player && run.normalStopObserved && run.gamePortClosed && run.rconPortClosed
      && run.serverExit?.code === 0 && !run.runtimeHealth?.failure
      && ['coreReady', 'supplyReady', 'serverReady', 'rconReady'].every(key => run.runtimeHealth?.[key]),
    'Production run lacks a normal saved survival checkpoint');
    requireThat(!strict || run.productionProtocolVersion === 2, 'Traced production requires protocol 2 observations');
    requireThat(!protocol || protocol === run.productionProtocolVersion, 'Production evidence protocols cannot be mixed');
    protocol = run.productionProtocolVersion;
    requireThat(run.worldIdentity && (!worldIdentity || isDeepStrictEqual(run.worldIdentity, worldIdentity)), 'Production world identity changed');
    worldIdentity = run.worldIdentity;
    requireThat(isDeepStrictEqual(corePlugins(run.plugins), corePlugins(plugins)), 'Production plugin binaries differ from logistics');
    const sourceFiles = ['six-resources.cjs', 'run.cjs', 'production-evidence.cjs', ...(protocol === 2 ? ['production-ledger.cjs'] : [])];
    requireThat(sourceFiles.every(file => run.clientSources?.some(row =>
      row.file === file && /^[A-F0-9]{64}$/.test(row.sha256))), 'Production source hashes are incomplete');
    archivedSave(stage, run.savedBefore); archivedSave(stage, run.savedCheckpoint);
    requireThat(run.savedBefore.playerId === offlinePlayerId(player) && run.savedCheckpoint.playerId === offlinePlayerId(player),
      'Production saved player identity changed');
    if (previous) requireThat(previous.sha256 === run.savedBefore.sha256, 'Production NBT checkpoint chain has a gap');
    if (!ledger) ledger = new ProductionLedger(run.savedBefore.inventory);
    const logBytes = fs.readFileSync(evidenceFile(stage, run.clientLog));
    requireThat(hash(logBytes) === run.clientLogSha256, 'Production action log hash mismatch');
    const actions = logBytes.toString('utf8').split(/\r?\n/).filter(line => line.startsWith('{')).map(line => JSON.parse(line));
    const start = actions.filter(row => row.event === 'started'), end = actions.filter(row => row.event === 'phase_complete');
    requireThat(start.length === 1 && start[0].player === player && start[0].phase === run.phase
      && isDeepStrictEqual(inventoryCounts(start[0].inventory), run.savedBefore.inventory), 'Production action log identity mismatch');
    if (run.pass) {
      requireThat(run.clientExit?.code === 0 && end.length === 1 && end[0].phase === run.phase, 'Production completion event missing');
      requireThat(isDeepStrictEqual(inventoryCounts(end[0].after), run.savedCheckpoint.inventory), 'Production final inventory differs from NBT');
      phases.add(run.phase);
    }
    for (const action of actions) {
      if (run.productionProtocolVersion === 2 && action.event === 'production_observed') {
        requireThat(action.phase === run.phase, 'Production observation phase changed');
        ledger.observe(action.inventory); continue;
      }
      if (action.event !== 'production_acquired') continue;
      if (run.productionProtocolVersion === 2) {
        requireThat(action.phase === run.phase, 'Production action phase changed');
        ledger.acquire(action); continue;
      }
      requireThat(action.phase === run.phase && Number.isSafeInteger(action.count) && action.count > 0
        && Number.isSafeInteger(action.before) && action.after - action.before === action.count,
        'Invalid production quantity evidence');
      // Only direct collection and the actual recipe input counts logged by this
      // runner may earn provenance. Unknown initial inventory starts at zero.
      const inputs = action.inputs ?? {};
      const direct = ['harvest', 'honey', 'natural-chest'].includes(action.source);
      requireThat(direct || ['craft', 'smelt'].includes(action.source), 'Unknown production source');
      requireThat(action.source !== 'honey' || inputs.glass_bottle === action.count, 'Honey provenance needs the consumed bottles');
      let traced = direct;
      if (!direct || Object.keys(inputs).length) {
        requireThat(Object.keys(inputs).length > 0 && Object.values(inputs).every(value => Number.isSafeInteger(value) && value > 0),
          'Recipe provenance needs positive input counts');
        traced = Object.entries(inputs).every(([item, count]) => (balance[item] ?? 0) >= count);
      }
      for (const [item, count] of Object.entries(inputs)) balance[item] = Math.max(0, (balance[item] ?? 0) - count);
      if (traced) balance[action.item] = (balance[action.item] ?? 0) + action.count;
      requireThat(action.inventory && inventoryCounts(action.inventory)[action.item] === action.after, 'Production inventory snapshot is missing');
      const current = inventoryCounts(action.inventory);
      for (const item of Object.keys(balance)) balance[item] = Math.min(balance[item], current[item] ?? 0);
    }
    // Conservative cap removes consumed/lost items. Never turn retained but
    // untraced starting stock into newly acquired evidence.
    for (const item of Object.keys(balance)) balance[item] = Math.min(balance[item], run.savedCheckpoint.inventory[item] ?? 0);
    if (run.productionProtocolVersion === 2) ledger.observe(run.savedCheckpoint.inventory);
    else ledger = new ProductionLedger(run.savedCheckpoint.inventory, balance);
    previous = run.savedCheckpoint;
    entries.push({result: relative, sha256: hash(bytes), phase: run.phase, pass: run.pass,
      clientLog: run.clientLog, clientLogSha256: run.clientLogSha256, sources: run.clientSources});
  }
  if (complete) requireThat(PHASES.every(phase => phases.has(phase)), 'All six production phases need a completed saved run');
  requireThat(!previous || previous.sha256 === savedBefore.sha256 && previous.playerId === savedBefore.playerId,
    'Logistics does not start at the final production checkpoint');
  return {worldIdentity, entries, phases: [...phases], balance: ledger?.traced ?? {}};
}

function linkProductionEvidence(stage, plan, savedBefore, player, plugins) {
  const {worldIdentity, entries, balance} = readProductionEvidence(stage, plan.productionEvidence, savedBefore, player, plugins,
    {complete: true, strict: plan.traceProduction === true});
  const production = {};
  for (const resource of RESOURCES) production[resource] = Math.min(balance[ITEMS[resource]] ?? 0, savedBefore.inventory[ITEMS[resource]] ?? 0);
  const missing = Object.fromEntries(RESOURCES.filter(resource => production[resource] < plan.counts[resource])
    .map(resource => [resource, plan.counts[resource] - production[resource]]));
  return {productionEvidenceLinked: true, survivalProductionVerified: Object.keys(missing).length === 0,
    worldIdentity, entries, tracedInventory: production, unprovenQuantities: missing,
    scope: 'Captured gameplay acquisition/recipe records chained to archived survival NBT; initial inventory is not assumed produced.'};
}

module.exports = {hash, evidenceFile, archiveSavedPlayer, readProductionEvidence, linkProductionEvidence};
