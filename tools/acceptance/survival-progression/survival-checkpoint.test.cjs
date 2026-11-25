const assert = require('node:assert/strict');
const {test} = require('node:test');
const {offlinePlayerId, confirmSavedPickaxe, confirmSavedResourcePhase} = require('./survival-checkpoint.cjs');

test('rejects a predicted successful craft when the save still contains its ingredients', () => {
  assert.throws(() => confirmSavedPickaxe({playerGameType: 0, Inventory: [
    {id: 'minecraft:iron_ingot', count: 3}, {id: 'minecraft:stick', count: 5}
  ]}), /absent from the saved player inventory/);
});

test('accepts a saved survival pickaxe and rejects a creative-mode save', () => {
  const player = {playerGameType: 0, Inventory: [{id: 'minecraft:iron_pickaxe', count: 1}], Pos: [1, 2, 3]};
  assert.equal(confirmSavedPickaxe(player).ironPickaxeCount, 1);
  assert.throws(() => confirmSavedPickaxe({...player, playerGameType: 1}), /not in survival mode/);
});

test('the deterministic offline identity selects the actual acceptance player save', () => {
  assert.equal(offlinePlayerId('SupplyCook'), '904364cc-bf39-35a0-b688-6cc8a6d846c8');
  assert.notEqual(offlinePlayerId('SupplyBuyer'), offlinePlayerId('SupplyCook'));
});

test('mining completion requires smelted ingots and bottles in the authoritative save', () => {
  const player = {playerGameType: 0, Inventory: [
    {id: 'minecraft:raw_iron', count: 6}, {id: 'minecraft:glass', count: 3}
  ]};
  assert.throws(() => confirmSavedResourcePhase(player, 'mining'), /requires 6 iron_ingot, found 0/);
  player.Inventory.push({id: 'minecraft:iron_ingot', count: 6});
  assert.throws(() => confirmSavedResourcePhase(player, 'mining'), /requires 3 glass_bottle, found 0/);
});

test('saved phase counts include split stacks and older NBT counts', () => {
  const result = confirmSavedResourcePhase({playerGameType: 0, Inventory: [
    {id: 'minecraft:iron_ingot', count: 4}, {id: 'minecraft:iron_ingot', Count: 2},
    {id: 'minecraft:glass_bottle', count: 3}
  ], Pos: [1, 2, 3]}, 'mining');
  assert.equal(result.inventory.iron_ingot, 6);
  assert.deepEqual(result.position, [1, 2, 3]);
});

test('resource phases reject creative-mode saves and unknown phases', () => {
  assert.throws(() => confirmSavedResourcePhase({playerGameType: 1}, 'mining'), /not in survival mode/);
  assert.throws(() => confirmSavedResourcePhase({playerGameType: 0}, 'typo'), /Unknown saved survival phase/);
});

test('farming completion requires both produced food and the harvested paper ingredient', () => {
  const player = {playerGameType: 0, Inventory: [{id: 'minecraft:bread', count: 6}]};
  assert.throws(() => confirmSavedResourcePhase(player, 'farming'), /requires 3 sugar_cane, found 0/);
  player.Inventory.push({id: 'minecraft:sugar_cane', count: 3});
  assert.equal(confirmSavedResourcePhase(player, 'farming').inventory.bread, 6);
});
