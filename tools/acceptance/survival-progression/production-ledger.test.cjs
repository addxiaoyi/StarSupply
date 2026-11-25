const {test} = require('node:test');
const assert = require('node:assert/strict');
const {ProductionLedger} = require('./production-ledger.cjs');
const {productionNeeds, craftToTarget} = require('./production-targets.cjs');

function acquire(ledger, item, count, source = 'harvest', inputs = {}) {
  const beforeInventory = {...ledger.inventory}, after = {...beforeInventory};
  for (const [name, used] of Object.entries(inputs)) after[name] -= used;
  after[item] = (after[item] ?? 0) + count;
  return ledger.acquire({item, count, source, inputs, beforeInventory, before: beforeInventory[item] ?? 0,
    after: after[item], inventory: Object.entries(after).map(([name, count]) => ({name, count}))});
}

test('retained old stock never satisfies traced targets; fresh ingredients make a proven batch alongside it', () => {
  const ledger = new ProductionLedger({raw_iron: 6, iron_ingot: 6, cobblestone: 146});
  assert.equal(ledger.target('iron_ingot', 6), 12);
  assert.equal(ledger.target('cobblestone', 6), 152);
  acquire(ledger, 'raw_iron', 6);
  assert.equal(acquire(ledger, 'iron_ingot', 6, 'smelt', {raw_iron: 6}), true);
  acquire(ledger, 'cobblestone', 6);
  assert.equal(ledger.available('iron_ingot'), 6);
  assert.equal(ledger.available('cobblestone'), 6);
  assert.equal(ledger.inventory.raw_iron, 6);
  assert.equal(ledger.inventory.iron_ingot, 12);
  assert.equal(ledger.target('iron_ingot', 6), 12);
});

test('unexplained losses consume provenance before old stacks, including loss followed by unrecorded gains', () => {
  const ledger = new ProductionLedger({cobblestone: 100});
  acquire(ledger, 'cobblestone', 10);
  ledger.observe({cobblestone: 105});
  assert.equal(ledger.available('cobblestone'), 5);
  ledger.observe({cobblestone: 200});
  assert.equal(ledger.available('cobblestone'), 5);
  ledger.observe({cobblestone: 194});
  assert.equal(ledger.available('cobblestone'), 0);
});

test('old recipe inputs and unreceipted furnace outputs cannot create provenance', () => {
  const ledger = new ProductionLedger({raw_iron: 6});
  assert.equal(acquire(ledger, 'iron_ingot', 6, 'smelt', {raw_iron: 6}), false);
  ledger.observe({...ledger.inventory, iron_ingot: 12}); // Recover a saved furnace.
  assert.equal(ledger.available('iron_ingot'), 0);
  acquire(ledger, 'raw_iron', 6);
  acquire(ledger, 'iron_ingot', 6, 'smelt', {raw_iron: 6});
  assert.equal(ledger.available('iron_ingot'), 6);
  assert.equal(ledger.inventory.iron_ingot, 18);
  assert.throws(() => acquire(ledger, 'glass_bottle', 3, 'craft', {glass: 3}), /not consumed/);
});

test('saved partial outputs and recipe batching replenish only the remaining traced requirement', async () => {
  let ledger = new ProductionLedger({bread: 10, wheat: 3});
  acquire(ledger, 'wheat', 6);
  acquire(ledger, 'bread', 1, 'craft', {wheat: 3});
  ledger = new ProductionLedger(JSON.parse(JSON.stringify(ledger.inventory)), JSON.parse(JSON.stringify(ledger.traced)));
  const plan = {counts: {FOOD: 3, MEDICAL: 3, EQUIPMENT: 3, MATERIALS: 3, ENGINEERING: 3, STRATEGIC: 3}};
  assert.equal(productionNeeds(plan, ledger.traced).wheat, 3);
  acquire(ledger, 'wheat', 3);
  let calls = 0;
  const complete = () => craftToTarget({output: 'bread', target: 3, count: item => ledger.available(item),
    craft: async () => { calls++; acquire(ledger, 'bread', 1, 'craft', {wheat: 3}); }});
  await complete(); await complete();
  assert.equal(calls, 2);
  assert.equal(ledger.available('bread'), 3);
  assert.equal(ledger.inventory.bread, 13);
  assert.equal(ledger.inventory.wheat, 3);
});

test('honey needs traced bottles and each recipe consumes its inputs exactly once', () => {
  const ledger = new ProductionLedger({glass_bottle: 2});
  acquire(ledger, 'sand', 3); acquire(ledger, 'glass', 3, 'smelt', {sand: 3});
  acquire(ledger, 'glass_bottle', 3, 'craft', {glass: 3});
  acquire(ledger, 'honey_bottle', 3, 'honey', {glass_bottle: 3});
  assert.equal(ledger.available('honey_bottle'), 3);
  assert.equal(ledger.available('glass_bottle'), 0);
  assert.equal(acquire(ledger, 'honey_bottle', 1, 'honey', {glass_bottle: 1}), false);
  assert.equal(ledger.available('honey_bottle'), 3);
  assert.throws(() => acquire(ledger, 'honey_bottle', 1, 'honey'), /recipe inputs/);
});

