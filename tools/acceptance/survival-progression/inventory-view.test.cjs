const {test} = require('node:test');
const assert = require('node:assert/strict');
const {playerItems} = require('./inventory-view.cjs');

test('open containers expose current player slots without counting container contents or stale inventory', () => {
  const bot = {inventory: {inventoryStart: 9, items: () => [{slot: 9, name: 'raw_iron', count: 6}]},
    currentWindow: {inventoryStart: 3, inventoryEnd: 6, slots: [
      {slot: 0, name: 'raw_iron', count: 3}, null, {slot: 2, name: 'iron_ingot', count: 5},
      {slot: 3, name: 'raw_iron', count: 3}, {slot: 4, name: 'iron_ingot', count: 1}, null]}};
  assert.deepEqual(playerItems(bot), [{slot: 9, name: 'raw_iron', count: 3}, {slot: 10, name: 'iron_ingot', count: 1}]);
  assert.equal(bot.currentWindow.slots[3].slot, 3);
  bot.currentWindow = null;
  assert.deepEqual(playerItems(bot), [{slot: 9, name: 'raw_iron', count: 6}]);
});
