const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {test} = require('node:test');
const {installServerDigConfirmation} = require('./server-dig.cjs');
const block = {name: 'stone', stateId: 1, position: {x: -4, y: 62, z: 56}};
function fixture(dig) {
  const bot = {_client: new EventEmitter(), registry: {blocksByStateId: [{name: 'air'}, {name: 'stone'}, {name: 'stone'}]},
    dig, stopped: false, stopDigging() { this.stopped = true; }};
  installServerDigConfirmation(bot, () => {}, 25);
  return bot;
}
test('predicted air without an authoritative packet never passes digging acceptance', async () => {
  const bot = fixture(async function () { this._client.emit('blockUpdate', block, {name: 'air'}); });
  await assert.rejects(bot.dig(block), /No server-confirmed removal/);
  assert.equal(bot.stopped, true);
  assert.equal(bot._client.listenerCount('block_change'), 0);
  assert.equal(bot._client.listenerCount('multi_block_change'), 0);
});
test('a matching server air packet before the client dig resolves is retained', async () => {
  const bot = fixture(async function () { this._client.emit('block_change', {location: block.position, type: 0}); });
  await bot.dig(block);
  assert.equal(bot.stopped, false);
  assert.equal(bot._client.listenerCount('block_change'), 0);
});
test('another location and a different state of the original material do not prove removal', async () => {
  const bot = fixture(async function () {
    this._client.emit('block_change', {location: {...block.position, x: 0}, type: 0});
    this._client.emit('block_change', {location: block.position, type: 2});
    this._client.emit('block_change', {location: block.position, type: 999999});
  });
  await assert.rejects(bot.dig(block), /No server-confirmed removal/);
});
test('batched changes decode negative chunk coordinates and local XYZ correctly', async () => {
  const bot = fixture(async function () {
    this._client.emit('multi_block_change', {chunkCoordinates: {x: -1, y: 3, z: 3}, records: [12 * 256 + 8 * 16 + 14]});
  });
  await bot.dig(block);
});
test('native digging failure preserves its error and removes both packet listeners', async () => {
  const failure = new Error('tool broke');
  const bot = fixture(async () => { throw failure; });
  await assert.rejects(bot.dig(block), error => error === failure);
  assert.equal(bot._client.listenerCount('block_change'), 0);
  assert.equal(bot._client.listenerCount('multi_block_change'), 0);
});
test('a stuck native dig is cancelled at the deadline even if an air packet arrived', async () => {
  const bot = fixture(function () {
    this._client.emit('block_change', {location: block.position, type: 0});
    return new Promise(() => {});
  });
  await assert.rejects(bot.dig(block), /No server-confirmed removal/);
  assert.equal(bot.stopped, true);
});
