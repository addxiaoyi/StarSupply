const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const {test} = require('node:test');
const {placeBlockConfirmed} = require('./server-place.cjs');

const position = (x, y, z) => ({x, y, z, offset(dx, dy, dz) { return position(x + dx, y + dy, z + dz); },
  clone() { return position(x, y, z); }, toString() { return `${x},${y},${z}`; }});
const reference = {position: position(-4, 61, 56)};
const face = {x: 0, y: 1, z: 0};
const target = reference.position.offset(0, 1, 0);
function fixture(action) {
  const bot = {_client: new EventEmitter(), entity: {position: target}, current: 'air',
    registry: {blocksByStateId: [{name: 'air'}, {name: 'furnace'}, {name: 'stone'}]},
    blockAt() { return {name: this.current, position: target}; }, placeBlock: action};
  bot.confirm = () => {
    bot.current = 'furnace';
    bot._client.emit('block_change', {location: target, type: 1});
  };
  bot.place = () => placeBlockConfirmed(bot, reference, face, 'furnace', 'place furnace', () => {}, 30);
  return bot;
}
function cleaned(bot) {
  assert.equal(bot._client.listenerCount('block_change'), 0);
  assert.equal(bot._client.listenerCount('multi_block_change'), 0);
}

test('local placement prediction is insufficient even when the cached block looks correct', async () => {
  const bot = fixture(async function () { this.current = 'furnace'; });
  await assert.rejects(bot.place(), /No server-confirmed/);
  cleaned(bot);
});
test('server placement arriving before the native call resolves is retained', async () => {
  const bot = fixture(async function () { this.confirm(); });
  assert.equal((await bot.place()).name, 'furnace');
  cleaned(bot);
});
test('late real placement recovers only a known client placement timeout', async () => {
  const bot = fixture(async function () {
    setTimeout(() => this.confirm(), 1);
    throw new Error('Event blockUpdate:-4,62,56 did not fire');
  });
  await bot.place();
  cleaned(bot);
});
test('wrong positions, unknown states and another material cannot confirm placement', async () => {
  const bot = fixture(async function () {
    this.current = 'furnace';
    this._client.emit('block_change', {location: {...target, x: 3}, type: 1});
    this._client.emit('block_change', {location: target, type: 2});
    this._client.emit('block_change', {location: target, type: 999999});
  });
  await assert.rejects(bot.place(), /No server-confirmed/);
  cleaned(bot);
});
test('batched placement supports negative chunks and bigint records', async () => {
  const bot = fixture(async function () {
    this.current = 'furnace';
    this._client.emit('multi_block_change', {chunkCoordinates: {x: -1, y: 3, z: 3},
      records: [-1, Number.MAX_SAFE_INTEGER + 1, BigInt(4096 + 12 * 256 + 8 * 16 + 14)]});
  });
  await bot.place();
  cleaned(bot);
});
test('non-placement failures propagate even if a matching packet arrived', async () => {
  const error = new Error('connection closed');
  const bot = fixture(async function () { this.confirm(); throw error; });
  await assert.rejects(bot.place(), failure => failure === error);
  cleaned(bot);
});
test('a hung native placement has a deadline and releases observers', async () => {
  const bot = fixture(function () { this.confirm(); return new Promise(() => {}); });
  await assert.rejects(bot.place(), /No server-confirmed/);
  cleaned(bot);
});
test('pre-existing or subsequently removed blocks do not prove retained placement', async () => {
  const existing = fixture(async () => { throw new Error('must not place'); });
  existing.current = 'furnace';
  await assert.rejects(existing.place(), /already contains/);
  cleaned(existing);
  const removed = fixture(async function () { this.confirm(); this.current = 'air'; });
  await assert.rejects(removed.place(), /no longer present/);
  cleaned(removed);
});
