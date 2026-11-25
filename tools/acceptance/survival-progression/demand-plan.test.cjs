const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {RESOURCES} = require('./logistics-cycle.cjs');
const {validateDemand, createDemandPlan, verifyDemandBudget, assertDemandFits, captureDemand, saveDemandPlan} = require('./demand-plan.cjs');

function fixture() {
  const now = 1800000000001, army = randomUUID(), nation = randomUUID();
  const seed = {runId: 'demand-test', depot: 'depot', front: 'front', army, cycles: 3, intervalMillis: 30000,
    price: '5.00', reward: '1.00', counts: Object.fromEntries(RESOURCES.map(resource => [resource, 6])),
    planning: {deliveryWindowMillis: 90000, reserveCycles: 1}};
  const probe = {type: 'demand', protocolVersion: 1, army, nation, front: 'front', soldiers: 20,
    atWar: true, access: 'CONNECTED', intervalMillis: 30000, slot: Math.floor(now / 30000), capturedAt: now,
    bootId: randomUUID(), correlation: randomUUID(), maxPurchaseItems: 256,
    currentDemand: {FOOD: 2, MEDICAL: 1, EQUIPMENT: 1, MATERIALS: 1, ENGINEERING: 1, STRATEGIC: 1},
    maximumDemand: {FOOD: 4, MEDICAL: 2, EQUIPMENT: 1, MATERIALS: 2, ENGINEERING: 2, STRATEGIC: 1}};
  const context = {plugins: [{name: 'StarSupply-0.1.0.jar', sha256: 'fixture'}], worldIdentity: {level: 'world', uidSha256: 'fixture'}};
  return {now, seed, probe, context, plan: () => createDemandPlan(seed, probe, context, now)};
}

test('season/siege demand and partial delivery cycles determine the complete production batch', () => {
  const f = fixture(), plan = f.plan();
  assert.deepEqual(plan.counts, {FOOD: 28, MEDICAL: 14, EQUIPMENT: 7, MATERIALS: 14, ENGINEERING: 14, STRATEGIC: 7});
  assert.equal(plan.demandBudget.budgetedCycles, 7);
  verifyDemandBudget(plan, f.context);
  assertDemandFits(plan, f.probe, f.now);
  f.seed.planning.deliveryWindowMillis += 1;
  assert.equal(f.plan().counts.FOOD, 32);
});

test('stale or incomplete demand, disconnected fronts and wrong armies cannot generate a plan', () => {
  const f = fixture();
  for (const changes of [{army: randomUUID()}, {nation: 'not-a-uuid'}, {atWar: false}, {access: 'CONTROL_LOST'},
    {capturedAt: f.now - 30001}, {capturedAt: f.now + 1}, {slot: f.probe.slot - 1}, {intervalMillis: 60000},
    {currentDemand: {FOOD: 2}}, {maximumDemand: {...f.probe.maximumDemand, FOOD: 1}}, {bootId: 'legacy'}]) {
    assert.throws(() => createDemandPlan(f.seed, {...f.probe, ...changes}, f.context, f.now));
  }
});

test('purchase and backpack limits fail before any gameplay instead of silently clipping demand', () => {
  const f = fixture();
  assert.throws(() => createDemandPlan(f.seed, {...f.probe, maxPurchaseItems: 20}, f.context, f.now), /purchase/);
  f.seed.planning.deliveryWindowMillis = 120000;
  const full = {...f.probe, maximumDemand: Object.fromEntries(RESOURCES.map(resource => [resource, 32]))};
  assert.throws(() => createDemandPlan(f.seed, full, f.context, f.now), /backpack/);
  assert.throws(() => createDemandPlan(f.seed, {...full, maximumDemand: {...full.maximumDemand, FOOD: 33}}, f.context, f.now), /purchase/);
  for (const planning of [{reserveCycles: 0}, {reserveCycles: 1.5}, {deliveryWindowMillis: -1}, {deliveryWindowMillis: 3600001}]) {
    assert.throws(() => createDemandPlan({...f.seed, planning}, f.probe, f.context, f.now));
  }
});

test('changed troop counts, configuration bounds, binary identity or edited quantities require replanning', () => {
  const f = fixture(), plan = f.plan();
  assert.throws(() => assertDemandFits(plan, {...f.probe, soldiers: 21}, f.now), /replan/);
  assert.throws(() => assertDemandFits(plan, {...f.probe, maximumDemand: {...f.probe.maximumDemand, FOOD: 5}}, f.now), /replan/);
  assert.throws(() => assertDemandFits(plan, {...f.probe, nation: randomUUID()}, f.now), /replan/);
  assertDemandFits(plan, {...f.probe, soldiers: 10}, f.now);
  assert.throws(() => verifyDemandBudget(plan, {...f.context, plugins: []}), /Plugins changed/);
  assert.throws(() => verifyDemandBudget(plan, {...f.context, worldIdentity: {level: 'other'}}), /World changed/);
  assert.throws(() => verifyDemandBudget({...plan, counts: {...plan.counts, FOOD: 6}}), /counts do not match/);
});

test('probe capture requires a new correlation and output plans cannot overwrite an existing run', async t => {
  const f = fixture(), root = path.resolve(__dirname, '../../../target'); fs.mkdirSync(root, {recursive: true});
  const stage = fs.mkdtempSync(path.join(root, 'demand-plan-test-'));
  t.after(() => { assert.ok(stage.startsWith(root + path.sep)); fs.rmSync(stage, {recursive: true}); });
  fs.mkdirSync(path.join(stage, 'plugins', 'SupplyNativeProof'), {recursive: true});
  fs.mkdirSync(path.join(stage, 'folia-proof'));
  const file = path.join(stage, 'plugins', 'SupplyNativeProof', 'evidence.jsonl');
  fs.writeFileSync(file, JSON.stringify(f.probe) + '\n');
  const row = await captureDemand({stage, plan: f.seed, command: async command => {
    const [, verb, army, correlation] = command.split(' ');
    assert.equal(verb, 'demand'); assert.equal(army, f.seed.army); assert.notEqual(correlation, f.probe.correlation);
    const now = Date.now();
    fs.appendFileSync(file, JSON.stringify({...f.probe, correlation, capturedAt: now, slot: Math.floor(now / 30000)}) + '\n');
  }, until: async read => read()});
  validateDemand(f.seed, row);
  const result = saveDemandPlan(stage, f.plan());
  assert.match(result.sha256, /^[A-F0-9]{64}$/);
  assert.deepEqual(JSON.parse(fs.readFileSync(path.join(stage, result.path))).counts, f.plan().counts);
  assert.throws(() => saveDemandPlan(stage, f.plan()), {code: 'EEXIST'});
});
