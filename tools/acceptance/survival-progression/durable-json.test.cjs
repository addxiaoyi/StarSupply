const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {writeJson} = require('./durable-json.cjs');

function fixture(t) {
  const root = path.resolve(__dirname, '../../../target');
  fs.mkdirSync(root, {recursive: true});
  const directory = fs.mkdtempSync(path.join(root, 'journal-test-'));
  t.after(() => { assert.ok(directory.startsWith(root + path.sep)); fs.rmSync(directory, {recursive: true}); });
  return path.join(directory, 'journal.json');
}

test('journal write, flush and rename failures preserve the last durable order IDs', t => {
  const file = fixture(t), previous = {orders: [{purchase: 'original-purchase'}]};
  writeJson(file, previous, {exclusive: true});
  for (const operation of ['writeFileSync', 'fsyncSync', 'renameSync']) {
    const io = {...fs, [operation]: (...args) => {
      if (operation === 'writeFileSync') fs.writeSync(args[0], '{"orders":');
      throw new Error(`injected ${operation} interruption`);
    }};
    assert.throws(() => writeJson(file, {orders: ['new']}, {io}), /injected/);
    assert.deepEqual(JSON.parse(fs.readFileSync(file)), previous);
    assert.deepEqual(fs.readdirSync(path.dirname(file)), ['journal.json']);
  }
  writeJson(file, {...previous, state: 'confirmed'});
  assert.equal(JSON.parse(fs.readFileSync(file)).state, 'confirmed');
});

test('exclusive journal creation cannot replace an existing run or publish partial JSON', t => {
  const file = fixture(t);
  writeJson(file, {run: 1}, {exclusive: true});
  assert.throws(() => writeJson(file, {run: 2}, {exclusive: true}), {code: 'EEXIST'});
  assert.deepEqual(JSON.parse(fs.readFileSync(file)), {run: 1});
  const invalid = {}; invalid.self = invalid;
  assert.throws(() => writeJson(file, invalid), /circular/i);
  assert.deepEqual(JSON.parse(fs.readFileSync(file)), {run: 1});
});
