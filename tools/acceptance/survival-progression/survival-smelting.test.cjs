const assert = require('node:assert/strict');
const {test} = require('node:test');
const {inventoryCount, smeltingState, smeltUntil} = require('./survival-smelting.cjs');

function fixture({pending = 0, ready = 0, owned = 0, raw = 0, coal = 1, fuel = 0, target = 6} = {}) {
  const names = ['raw_iron', 'iron_ingot', 'coal', 'charcoal'];
  const ids = Object.fromEntries(names.map((name, id) => [name, {id}]));
  const item = (name, count) => count > 0 ? {name, count} : null;
  const slots = [item('raw_iron', pending), null, item('iron_ingot', ready),
    item('raw_iron', raw), item('iron_ingot', owned), item('coal', coal), null];
  const actions = [];
  const window = {slots, inventoryStart: 3, inventoryEnd: slots.length, fuel, fuelSeconds: fuel,
    inputItem: () => slots[0], fuelItem: () => slots[1], outputItem: () => slots[2],
    async takeOutput() {
      actions.push(['take', slots[2].count]);
      slots[4] = item('iron_ingot', (slots[4]?.count ?? 0) + slots[2].count); slots[2] = null;
    },
    async putInput(id, metadata, count) { transfer('input', id, count, 0); },
    async putFuel(id, metadata, count) { transfer('fuel', id, count, 1); }};
  function transfer(label, id, count, destination) {
    const source = slots.findIndex((entry, index) => index >= 3 && entry?.name === names[id]);
    assert.ok(source >= 3 && slots[source].count >= count, 'only transfer existing ingredients');
    actions.push([label, count]);
    slots[destination] = item(names[id], (slots[destination]?.count ?? 0) + count);
    slots[source] = item(names[id], slots[source].count - count);
  }
  const bot = {currentWindow: window, registry: {itemsByName: ids}};
  const cook = () => {
    if (!slots[0]) return;
    if (window.fuelSeconds <= 0 && slots[1]) {
      slots[1] = item(slots[1].name, slots[1].count - 1); window.fuelSeconds = 8;
    }
    if (window.fuelSeconds <= 0) return;
    slots[0] = item('raw_iron', slots[0].count - 1);
    slots[2] = item('iron_ingot', (slots[2]?.count ?? 0) + 1);
    window.fuelSeconds--; window.fuel = window.fuelSeconds / 8;
  };
  return {bot, window, actions, slots, cook,
    run: (options = {}) => smeltUntil(bot, window, 'raw_iron', 'iron_ingot', target,
      {pollMs: 1, timeoutMs: 100, check: cook, ...options})};
}

test('resumes stopped furnace input and output without demanding replacement ore', async () => {
  const f = fixture({pending: 3, ready: 2, owned: 1});
  assert.deepEqual(smeltingState(f.window, 'raw_iron', 'iron_ingot', 6), {owned: 1, missingInput: 0, acquireInput: 0});
  await f.run();
  assert.equal(inventoryCount(f.window, 'iron_ingot'), 6);
  assert.equal(f.actions.filter(a => a[0] === 'input').length, 0);
});
test('tops up a partial batch and replenishes fuel when the previous burn runs out', async () => {
  const f = fixture({pending: 1, raw: 4, owned: 1, fuel: 1});
  await f.run();
  assert.equal(inventoryCount(f.window, 'iron_ingot'), 6);
  assert.equal(f.actions.filter(a => a[0] === 'input').reduce((n, a) => n + a[1], 0), 4);
  assert.deepEqual(f.actions.filter(a => a[0] === 'fuel'), [['fuel', 1]]);
});
test('takes already finished outputs without requiring ore or fuel', async () => {
  const f = fixture({ready: 3, owned: 3, coal: 0});
  await f.run();
  assert.deepEqual(f.actions, [['take', 3]]);
  assert.equal(inventoryCount(f.window, 'iron_ingot'), 6);
});
test('does not overwrite unrelated furnace contents', async () => {
  for (const slot of [0, 2]) {
    const f = fixture({raw: 6}); f.slots[slot] = {name: 'copper_ingot', count: 2};
    await assert.rejects(f.run({check: () => {}}), /different (input|output)/);
    assert.deepEqual(f.actions, []);
    assert.equal(f.slots[slot].count, 2);
  }
});
test('reports only missing ingredients after accounting for saved pending and ready items', async () => {
  const f = fixture({pending: 1, ready: 1, owned: 1, raw: 2});
  assert.equal(smeltingState(f.window, 'raw_iron', 'iron_ingot', 6).acquireInput, 1);
  await assert.rejects(f.run({check: () => {}}), /Need 1 more raw_iron/);
  assert.equal(f.slots[0].count, 1);
  assert.equal(f.slots[3].count, 2);
});
test('fuel shortage fails clearly and leaves partial inputs for the next run', async () => {
  const f = fixture({pending: 5, owned: 1, coal: 0});
  await assert.rejects(f.run(), /needs coal or charcoal/);
  assert.equal(f.slots[0].count, 5);
  assert.equal(inventoryCount(f.window, 'iron_ingot'), 1);
});
test('supports charcoal and uses the active window inventory', async () => {
  const f = fixture({raw: 2, target: 2, coal: 0});
  f.slots[6] = {name: 'charcoal', count: 1};
  f.bot.inventory = {items() { throw new Error('closed player inventory must not be sampled'); }};
  await f.run();
  assert.equal(inventoryCount(f.window, 'iron_ingot'), 2);
  assert.equal(inventoryCount(f.window, 'charcoal'), 0);
});
test('closed windows and connection failures stop before transferring ingredients', async () => {
  const closed = fixture({raw: 6}); closed.bot.currentWindow = null;
  await assert.rejects(closed.run(), /window closed/);
  assert.deepEqual(closed.actions, []);
  const failed = fixture({raw: 6}), failure = new Error('disconnected');
  await assert.rejects(failed.run({check: () => { throw failure; }}), error => error === failure);
  assert.deepEqual(failed.actions, []);
});
test('stalled transfers and stopped cooking both have bounded deadlines', async () => {
  const transfer = fixture({raw: 6}); transfer.window.putInput = () => new Promise(() => {});
  await assert.rejects(transfer.run({timeoutMs: 20}), /Timed out smelting/);
  const stalled = fixture({pending: 6, fuel: 10});
  await assert.rejects(stalled.run({check: () => {}, timeoutMs: 20}), /Timed out smelting/);
});
