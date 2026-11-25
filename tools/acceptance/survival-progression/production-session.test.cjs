const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {ProductionLedger} = require('./production-ledger.cjs');
const {hash, readProductionEvidence} = require('./production-evidence.cjs');
const {RESOURCES, runCycle, journalPath} = require('./logistics-cycle.cjs');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const {sessionPath, prepareProduction, beginProduction, finishProduction, verifyTracedPhase, productionForLogistics} = require('./production-session.cjs');

function fixture(t) {
  const root = path.resolve(__dirname, '../../../target'); fs.mkdirSync(root, {recursive: true});
  const stage = fs.mkdtempSync(path.join(root, 'production-session-test-'));
  fs.mkdirSync(path.join(stage, 'folia-proof'));
  t.after(() => { assert.ok(stage.startsWith(root + path.sep)); fs.rmSync(stage, {recursive: true}); });
  const player = 'SupplyCook', playerId = offlinePlayerId(player);
  const plan = {runId: 'traced', traceProduction: true, depot: 'depot', front: 'front',
    army: '00000000-0000-0000-0000-000000000001', cycles: 3, intervalMillis: 30000,
    price: '5.00', reward: '1.00', counts: Object.fromEntries(RESOURCES.map(resource => [resource, 3]))};
  const plugins = [{name: 'StarCore-1.0.0.jar', sha256: 'core'}, {name: 'StarSupply-0.1.0.jar', sha256: 'supply'}];
  const worldIdentity = {level: 'world', uidSha256: 'uid'};
  let inventory = {raw_iron: 6, cobblestone: 146, bread: 3, campfire: 3, stone_sword: 1, wooden_hoe: 1}, index = 0;
  const list = () => Object.entries(inventory).filter(([, count]) => count > 0).map(([name, count]) => ({name, count}));
  const save = () => {
    inventory = Object.fromEntries(list().map(row => [row.name, row.count]));
    // Hash fixtures exercise journal/evidence boundaries, not Minecraft NBT decoding.
    const bytes = Buffer.from(JSON.stringify({fixture: index, inventory})), archive = `folia-proof/save-${index}.dat`;
    fs.writeFileSync(path.join(stage, archive), bytes);
    return {archive, sha256: hash(bytes), gameMode: 'survival', playerId, inventory: {...inventory}};
  };
  let savedBefore = save();
  const args = () => ({stage, plan, player, plugins, worldIdentity, savedBefore});
  const begin = phase => {
    const prepared = prepareProduction(args()); index++;
    const result = `folia-proof/survival-progression-attempt${index}-result.json`;
    const context = beginProduction(stage, plan, prepared, phase, result, path.join(stage, `context-${index}.json`));
    const ledger = new ProductionLedger(inventory, context.traced), actions = [{event: 'started', player, phase, inventory: list()}];
    const add = (item, amount, source = 'harvest', inputs = {}) => {
      const beforeInventory = {...inventory}, before = inventory[item] ?? 0;
      for (const [name, count] of Object.entries(inputs)) inventory[name] -= count;
      inventory[item] = before + amount;
      const action = {event: 'production_acquired', phase, item, before, after: inventory[item], count: amount,
        source, inputs, beforeInventory, inventory: list()};
      ledger.acquire(action); actions.push(action);
    };
    const write = ({pass = true, commit = true} = {}) => {
      const checkpoint = save(), clientLog = `folia-proof/client-${index}.log`;
      if (pass) actions.push({event: 'phase_complete', phase, after: list()});
      const bytes = Buffer.from(actions.map(row => JSON.stringify(row)).join('\n') + '\n');
      fs.writeFileSync(path.join(stage, clientLog), bytes);
      const run = {event: 'survival_progression_run', productionProtocolVersion: 2, productionSession: plan.runId,
        phase, player, plugins, worldIdentity, pass, normalStopObserved: true, gamePortClosed: true, rconPortClosed: true,
        serverExit: {code: 0}, clientExit: {code: pass ? 0 : 1}, runtimeHealth: {coreReady: true, supplyReady: true, serverReady: true, rconReady: true},
        savedBefore, savedCheckpoint: checkpoint, clientLog, clientLogSha256: hash(bytes),
        clientSources: ['six-resources.cjs', 'run.cjs', 'production-evidence.cjs', 'production-ledger.cjs'].map(file => ({file, sha256: hash(fs.readFileSync(path.join(__dirname, file)))}))};
      fs.writeFileSync(path.join(stage, result), JSON.stringify(run));
      savedBefore = checkpoint;
      const evidence = commit ? finishProduction(stage, plan, prepared)
        : readProductionEvidence(stage, [...prepared.journal.results.map(row => row.path), result], checkpoint, player, plugins, {strict: true});
      return {run, evidence, result};
    };
    return {prepared, context, ledger, add, write, result};
  };
  return {stage, plan, plugins, player, worldIdentity, args, begin, save, change: value => { inventory = value; savedBefore = save(); }};
}

test('six traced phases preserve old inventory, persist source balances and gate the logistics handoff', t => {
  const f = fixture(t);
  const mining = f.begin('mining');
  mining.add('raw_iron', 3); mining.add('iron_ingot', 3, 'smelt', {raw_iron: 3}); mining.add('cobblestone', 3);
  mining.add('sand', 3); mining.add('glass', 3, 'smelt', {sand: 3}); mining.add('glass_bottle', 3, 'craft', {glass: 3});
  const mined = mining.write(); verifyTracedPhase('mining', f.plan, mined.evidence, mined.run.savedCheckpoint.inventory);
  assert.throws(() => productionForLogistics(f.stage, f.plan, f.player, f.plugins, f.worldIdentity, f.args().savedBefore), /production run result paths/);
  f.begin('surface').write();
  const farm = f.begin('farming'); farm.add('wheat', 9); farm.add('bread', 3, 'craft', {wheat: 9}); farm.add('sugar_cane', 3); farm.write();
  const honey = f.begin('honey'); honey.add('honey_bottle', 3, 'honey', {glass_bottle: 3}); honey.write();
  const scaffold = f.begin('scaffolding'); scaffold.add('bamboo', 6); scaffold.add('string', 1);
  scaffold.add('scaffolding', 6, 'craft', {bamboo: 6, string: 1}); scaffold.write();
  const fire = f.begin('fireworks'); fire.add('paper', 3, 'craft', {sugar_cane: 3}); fire.add('gunpowder', 1, 'natural-chest');
  fire.add('firework_rocket', 3, 'craft', {paper: 1, gunpowder: 1}); fire.write({commit: false});
  const proof = productionForLogistics(f.stage, f.plan, f.player, f.plugins, f.worldIdentity, f.args().savedBefore);
  assert.equal(proof.survivalProductionVerified, true);
  assert.deepEqual(proof.unprovenQuantities, {});
  assert.equal(f.args().savedBefore.inventory.cobblestone, 149);
  assert.equal(f.args().savedBefore.inventory.raw_iron, 6);
  assert.equal(f.args().savedBefore.inventory.bread, 6);
});

test('a failed but saved attempt retains only recorded progress and can finish the same phase', t => {
  const f = fixture(t), partial = f.begin('farming');
  partial.add('wheat', 3); partial.add('bread', 1, 'craft', {wheat: 3});
  const result = partial.write({pass: false});
  assert.throws(() => verifyTracedPhase('farming', f.plan, result.evidence, result.run.savedCheckpoint.inventory), /requires 3 bread/);
  const retry = f.begin('farming');
  assert.equal(retry.context.traced.bread, 1);
  retry.add('wheat', 6); retry.add('bread', 2, 'craft', {wheat: 6}); retry.add('sugar_cane', 3);
  const done = retry.write(); verifyTracedPhase('farming', f.plan, done.evidence, done.run.savedCheckpoint.inventory);
  assert.deepEqual(done.evidence.phases, ['farming']);
});

test('a committed result is recovered after interruption before journal advancement without repeating acquisition', t => {
  const f = fixture(t), attempt = f.begin('mining');
  attempt.add('raw_iron', 3); attempt.write({pass: false, commit: false});
  const recovered = prepareProduction(f.args());
  assert.equal(recovered.evidence.balance.raw_iron, 3);
  assert.equal(recovered.journal.results.length, 1);
  assert.equal(recovered.journal.pending, null);
  assert.equal(prepareProduction(f.args()).journal.results.length, 1);
});

test('an unstarted attempt can retry only when its archived player checkpoint is unchanged', t => {
  const f = fixture(t); f.begin('mining');
  assert.equal(prepareProduction(f.args()).journal.pending, null);
  f.begin('mining'); f.change({...f.args().savedBefore.inventory, raw_iron: 9});
  assert.throws(() => prepareProduction(f.args()), /Unrecorded production changed/);
});

test('a startup-only failure with unchanged saved data is archived without adding production credit', t => {
  const f = fixture(t), attempt = f.begin('mining'), {run, result} = attempt.write({pass: false, commit: false});
  run.clientExit = undefined; run.savedCheckpoint = run.savedBefore;
  fs.writeFileSync(path.join(f.stage, run.clientLog), ''); run.clientLogSha256 = hash(Buffer.alloc(0));
  fs.writeFileSync(path.join(f.stage, result), JSON.stringify(run));
  const recovered = prepareProduction({...f.args(), savedBefore: run.savedBefore});
  assert.equal(recovered.journal.results.length, 0);
  assert.equal(recovered.journal.noGameplay.length, 1);
  assert.deepEqual(recovered.evidence.balance, {});
});

test('legacy production records cannot be promoted into a traced session', t => {
  const f = fixture(t), attempt = f.begin('mining'), {run, result} = attempt.write({pass: false, commit: false});
  run.productionProtocolVersion = 1;
  fs.writeFileSync(path.join(f.stage, result), JSON.stringify(run));
  assert.throws(() => prepareProduction(f.args()), /protocol 2/);
});

test('changed plans, plugin hashes, checkpoints or result files cannot adopt old production credit', t => {
  const f = fixture(t), attempt = f.begin('mining'); attempt.add('raw_iron', 3); const result = attempt.write({pass: false});
  assert.throws(() => prepareProduction({...f.args(), plan: {...f.plan, price: '6.00'}}), /session plan/);
  assert.throws(() => prepareProduction({...f.args(), plugins: []}), /session plan/);
  assert.throws(() => prepareProduction({...f.args(), savedBefore: {...f.args().savedBefore, sha256: 'changed'}}), /checkpoint changed/);
  fs.appendFileSync(path.join(f.stage, result.result), ' ');
  assert.throws(() => prepareProduction(f.args()), /result changed/);
  assert.ok(fs.existsSync(sessionPath(f.stage, f.plan)));
});

test('a physically full phase cannot pass the traced production gate with historical ingredients', t => {
  const f = fixture(t), attempt = f.begin('farming');
  attempt.add('sugar_cane', 3); const result = attempt.write();
  assert.equal(result.run.savedCheckpoint.inventory.bread, 3);
  assert.throws(() => verifyTracedPhase('farming', f.plan, result.evidence, result.run.savedCheckpoint.inventory), /existing untraced stock/);
});

test('direct gameplay clients cannot create traced logistics orders before the runner verifies production', async t => {
  const f = fixture(t);
  let calls = 0;
  const options = {stage: f.stage, plan: f.plan, player: f.player, inventory: () => f.args().savedBefore.inventory,
    chat: () => { calls++; }};
  await assert.rejects(runCycle(options), /through the runner/);
  assert.equal(fs.existsSync(journalPath(f.stage, f.plan)), false);
  fs.writeFileSync(journalPath(f.stage, f.plan), JSON.stringify({productionEvidence: {survivalProductionVerified: false}}));
  await assert.rejects(runCycle(options), /no verified production/);
  assert.equal(calls, 0);
});
