const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {hash, linkProductionEvidence, archiveSavedPlayer} = require('./production-evidence.cjs');
const {ITEMS, RESOURCES} = require('./logistics-cycle.cjs');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const player = 'SupplyCook', playerId = offlinePlayerId(player);

function fixture(t, earned = true) {
  const root = path.resolve(__dirname, '../../../target'); fs.mkdirSync(root, {recursive: true});
  const stage = fs.mkdtempSync(path.join(root, 'production-evidence-test-')); fs.mkdirSync(path.join(stage, 'folia-proof'));
  t.after(() => { assert.ok(stage.startsWith(root + path.sep)); fs.rmSync(stage, {recursive: true}); });
  const plugins = [{name: 'StarCore-1.0.0.jar', sha256: 'core'}, {name: 'StarSupply-0.1.0.jar', sha256: 'supply'}];
  const plan = {counts: Object.fromEntries(RESOURCES.map(resource => [resource, 3])), productionEvidence: []};
  const saves = [], runs = [], inventory = earned ? {} : Object.fromEntries(Object.values(ITEMS).map(item => [item, 6]));
  const list = () => Object.entries(inventory).filter(([, count]) => count).map(([name, count]) => ({name, count}));
  const save = index => {
    const bytes = Buffer.from(`fixture-only snapshot ${index}`), archive = `folia-proof/save-${index}.dat`;
    fs.writeFileSync(path.join(stage, archive), bytes);
    const result = {archive, sha256: hash(bytes), playerId, gameMode: 'survival', inventory: Object.fromEntries(list().map(row => [row.name, row.count]))};
    saves.push(result); return result;
  };
  let before = save(0);
  for (const [index, phase] of ['mining', 'surface', 'farming', 'honey', 'scaffolding', 'fireworks'].entries()) {
    const actions = [{event: 'started', phase, player, inventory: list()}];
    const acquire = (item, amount, source = 'harvest', inputs = {}) => {
      const initial = inventory[item] ?? 0;
      for (const [input, count] of Object.entries(inputs)) inventory[input] -= count;
      inventory[item] = initial + amount;
      actions.push({event: 'production_acquired', phase, item, before: initial, after: inventory[item], count: amount,
        source, inputs, inventory: list()});
    };
    if (earned) {
      if (phase === 'mining') {
        acquire('raw_iron', 6); acquire('iron_ingot', 6, 'smelt', {raw_iron: 6}); acquire('cobblestone', 6);
        acquire('sand', 6); acquire('glass', 6, 'smelt', {sand: 6}); acquire('glass_bottle', 6, 'craft', {glass: 6});
      }
      if (phase === 'farming') { acquire('wheat', 18); acquire('bread', 6, 'craft', {wheat: 18}); }
      if (phase === 'honey') acquire('honey_bottle', 6, 'honey', {glass_bottle: 6});
      if (phase === 'scaffolding') { acquire('bamboo', 6); acquire('string', 1, 'natural-chest'); acquire('scaffolding', 6, 'craft', {bamboo: 6, string: 1}); }
      if (phase === 'fireworks') {
        acquire('sugar_cane', 3); acquire('paper', 3, 'craft', {sugar_cane: 3}); acquire('gunpowder', 1, 'natural-chest');
        acquire('firework_rocket', 3, 'craft', {paper: 1, gunpowder: 1});
      }
    }
    const after = save(index + 1);
    actions.push({event: 'phase_complete', phase, after: list()});
    const log = `folia-proof/${phase}-client.log`, logBytes = Buffer.from(actions.map(row => JSON.stringify(row)).join('\n') + '\n');
    fs.writeFileSync(path.join(stage, log), logBytes);
    const relative = `folia-proof/${phase}-result.json`;
    const run = {event: 'survival_progression_run', productionProtocolVersion: 1, phase, player, plugins, worldIdentity: {level: 'world', uidSha256: 'uid'},
      normalStopObserved: true, gamePortClosed: true, rconPortClosed: true, serverExit: {code: 0}, clientExit: {code: 0},
      runtimeHealth: {coreReady: true, supplyReady: true, serverReady: true, rconReady: true, failure: null},
      savedBefore: before, savedCheckpoint: after, pass: true, clientLog: log, clientLogSha256: hash(logBytes),
      clientSources: ['six-resources.cjs', 'run.cjs', 'production-evidence.cjs'].map(file => ({file, sha256: hash(fs.readFileSync(path.join(__dirname, file)))}))};
    fs.writeFileSync(path.join(stage, relative), JSON.stringify(run));
    plan.productionEvidence.push(relative); runs.push(run); before = after;
  }
  return {stage, plan, plugins, saves, runs, before, verify: () => linkProductionEvidence(stage, plan, before, player, plugins),
    update(index) { fs.writeFileSync(path.join(stage, plan.productionEvidence[index]), JSON.stringify(runs[index])); }};
}

test('six production results link archived NBT, action logs, recipe inputs and the final logistics checkpoint', t => {
  const f = fixture(t), result = f.verify();
  assert.equal(result.productionEvidenceLinked, true);
  assert.equal(result.survivalProductionVerified, true);
  assert.deepEqual(result.unprovenQuantities, {});
  assert.equal(result.entries.length, 6);
});

test('six saved inventory phase passes cannot certify unproven initial stock', t => {
  const f = fixture(t, false), result = f.verify();
  assert.equal(result.productionEvidenceLinked, true);
  assert.equal(result.survivalProductionVerified, false);
  assert.deepEqual(result.unprovenQuantities, f.plan.counts);
});

test('production evidence refuses missing phases, chain gaps, other identities and changed artifacts', t => {
  const f = fixture(t);
  const original = structuredClone(f.runs[2]);
  for (const change of [run => { run.pass = false; }, run => { run.savedBefore = f.saves[0]; },
    run => { run.worldIdentity = {level: 'other'}; }, run => { run.player = 'Other'; },
    run => { run.plugins[0].sha256 = 'other'; }, run => { run.clientLogSha256 = 'changed'; },
    run => { run.savedCheckpoint.sha256 = 'changed'; }, run => { run.runtimeHealth.coreReady = false; }]) {
    f.runs[2] = structuredClone(original); change(f.runs[2]); f.update(2);
    assert.throws(f.verify);
  }
  f.runs[2] = original; f.update(2);
  assert.throws(() => linkProductionEvidence(f.stage, f.plan, {...f.before, sha256: 'different'}, player, f.plugins), /final production/);
  f.plan.productionEvidence.push(f.plan.productionEvidence[0]);
  assert.throws(f.verify, /distinct/);
});

test('crafting with initial or already-spent inputs cannot create traced production', t => {
  const f = fixture(t), run = f.runs[2];
  const file = path.join(f.stage, run.clientLog), actions = fs.readFileSync(file, 'utf8').trim().split('\n').map(JSON.parse);
  const filtered = actions.filter(row => row.item !== 'wheat');
  const bytes = Buffer.from(filtered.map(row => JSON.stringify(row)).join('\n') + '\n');
  fs.writeFileSync(file, bytes); run.clientLogSha256 = hash(bytes); f.update(2);
  const result = f.verify();
  assert.equal(result.survivalProductionVerified, false);
  assert.equal(result.unprovenQuantities.FOOD, 3);
});

test('player archive is copied without changing the save and cannot mask a changed source', t => {
  const f = fixture(t), relative = 'player.dat', bytes = Buffer.from('player-save-fixture');
  fs.writeFileSync(path.join(f.stage, relative), bytes);
  const save = {path: relative, sha256: hash(bytes), playerId, gameMode: 'survival'};
  const archived = archiveSavedPlayer(f.stage, save, 'before');
  assert.deepEqual(fs.readFileSync(path.join(f.stage, archived.archive)), bytes);
  assert.deepEqual(archiveSavedPlayer(f.stage, save, 'before'), archived);
  fs.writeFileSync(path.join(f.stage, relative), 'changed');
  assert.throws(() => archiveSavedPlayer(f.stage, save, 'after'), /changed while archiving/);
});
