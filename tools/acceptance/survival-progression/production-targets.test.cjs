const {test} = require('node:test');
const assert = require('node:assert/strict');
const {productionCounts, productionNeeds, phaseRequirements, craftToTarget, harvestToTarget} = require('./production-targets.cjs');
const {confirmSavedResourcePhase} = require('./survival-checkpoint.cjs');
const plan = {counts: {FOOD: 28, MEDICAL: 14, EQUIPMENT: 7, MATERIALS: 14, ENGINEERING: 14, STRATEGIC: 7}};

test('ingredient deficits account for existing outputs and whole recipe batch sizes', () => {
  const needs = productionNeeds(plan, {bread: 10, wheat: 5, honey_bottle: 4, glass_bottle: 2,
    scaffolding: 3, bamboo: 2, string: 1, firework_rocket: 1, paper: 1});
  assert.equal(needs.wheat, 49);
  assert.equal(needs.bottles, 10);
  assert.equal(needs.bottleBatches, 3);
  assert.equal(needs.scaffoldBatches, 2);
  assert.equal(needs.bamboo, 12);
  assert.equal(needs.string, 2);
  assert.equal(needs.rocketBatches, 2);
  assert.equal(needs.paperBatches, 1);
  assert.equal(needs.sugarCane, 3);
  assert.equal(needs.gunpowder, 2);
  const ready = productionNeeds(plan, {bread: 28, honey_bottle: 14, scaffolding: 18, firework_rocket: 9});
  assert.equal(ready.wheat + ready.bottleBatches + ready.scaffoldBatches + ready.rocketBatches + ready.sugarCane, 0);
});

test('saved NBT gates use the actual plan rather than the historical fixed targets', () => {
  const player = {playerGameType: 0, Inventory: [{id: 'minecraft:bread', count: 6}, {id: 'minecraft:sugar_cane', count: 3}]};
  assert.throws(() => confirmSavedResourcePhase(player, 'farming', plan), /requires 28 bread/);
  player.Inventory[0].count = 28;
  assert.equal(confirmSavedResourcePhase(player, 'farming', plan).inventory.bread, 28);
  assert.equal(phaseRequirements('mining', plan, {honey_bottle: 10}).glass_bottle, 4);
  assert.equal(phaseRequirements('fireworks', plan, {}).cobblestone, 14);
  assert.throws(() => productionCounts({counts: {...plan.counts, FOOD: 257}}), /production target/);
  assert.throws(() => productionNeeds(plan, {bread: -1}), /Invalid saved/);
});

test('repeated recipes consume real ingredients and resume from partial finished outputs', async () => {
  const items = {scaffolding: 2, bamboo: 12, string: 2};
  let crafts = 0;
  const run = () => craftToTarget({output: 'scaffolding', target: 14, count: name => items[name] ?? 0,
    ingredients: [['bamboo', [1, 3, 4, 6, 7, 9]], ['string', [2]]], craft: async (output, ingredients) => {
      for (const [item, slots] of ingredients) { assert.ok(items[item] >= slots.length); items[item] -= slots.length; }
      items[output] += 6; crafts++;
    }});
  await run(); await run();
  assert.deepEqual(items, {scaffolding: 14, bamboo: 0, string: 0});
  assert.equal(crafts, 2);
  await assert.rejects(craftToTarget({output: 'bread', target: 1, count: () => 0, craft: async () => {}}), /no inventory progress/);
});

test('honey harvest repeats natural refills, preserves partial progress on timeout and stops at target', async () => {
  let honey = 2, time = 0, rounds = 0;
  await harvestToTarget({target: 7, count: () => honey, now: () => time, timeoutMs: 100000,
    round: async () => { rounds++; honey += Math.min(3, 7 - honey); }, wait: async ms => { time += ms; }});
  assert.equal(honey, 7); assert.equal(rounds, 2);
  await assert.rejects(harvestToTarget({target: 9, count: () => honey, now: () => time, timeoutMs: 10000,
    round: async () => {}, wait: async ms => { time += ms; }}), /resume the saved partial/);
  assert.equal(honey, 7);
  await harvestToTarget({target: 9, count: () => honey, now: () => time, round: async () => { honey++; }, wait: async () => {}});
  assert.equal(honey, 9);
});
