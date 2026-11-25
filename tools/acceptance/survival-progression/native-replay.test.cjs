const {test} = require('node:test');
const assert = require('node:assert/strict');
const {randomUUID} = require('node:crypto');
const {validateRetry, validateAccounts, assertHistoricalRows} = require('./native-replay.cjs');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');

function fixture() {
  const credit = {id: randomUUID(), army: randomUUID(), nation: randomUUID(), points: 10};
  const row = {type: 'retry-passed', bootId: randomUUID(), correlation: randomUUID(),
    receipt: {request: credit.id, army: credit.army, nation: credit.nation, requestedPoints: 10, appliedPoints: 7},
    nativeSupplyBefore: 55, nativeSupplyAfter: 55, treasuryBefore: '12345678901234567890.00',
    treasuryAfter: '12345678901234567890', conflictRejected: true};
  return {credit, row};
}

test('native replay rejects changed receipt identity, balances, supply and conflicting request acceptance', () => {
  const {credit, row} = fixture();
  assert.equal(validateRetry(row, credit), row);
  for (const altered of [{bootId: undefined}, {correlation: undefined}, {nativeSupplyAfter: 56}, {conflictRejected: false},
    {treasuryAfter: '12345678901234567891'}, {treasuryBefore: 123},
    {receipt: {...row.receipt, requestedPoints: 9}}, {receipt: {...row.receipt, appliedPoints: 11}},
    {receipt: {...row.receipt, nation: randomUUID()}}]) assert.throws(() => validateRetry({...row, ...altered}, credit));
  assert.throws(() => validateRetry({...row, correlation: randomUUID()}, credit, row), /same plugin boot/);
  assert.throws(() => validateRetry({...row, bootId: randomUUID(), receipt: {...row.receipt, appliedPoints: 6}}, credit, row),
    /changed across restart/);
  assert.throws(() => validateRetry({...row, treasuryBefore: '-0.50', treasuryAfter: '0.50'}, credit), /changed supply or treasury/);
});

test('restart compares original financial and consumption rows while allowing new consumption intervals', () => {
  const before = {purchases: [{id: 'p', state: 'BOUGHT'}], shipments: [{id: 's', state: 'DELIVERED'}],
    transfers: [{purchase: 'p', state: 'STORED'}], settlements: [{order_id: 'p', amount: '5.00'}],
    consumption: [{army: 'a', slot: 10, interval_ms: 30, resource: 'FOOD', units: 1}], credits: [{id: 'c', state: 'APPLIED'}]};
  const journal = {orders: [{purchase: 'p', shipment: 's'}], baseline: {consumption: [], credits: []}};
  const after = structuredClone(before);
  after.consumption.push({...before.consumption[0], slot: 11});
  after.credits.push({id: 'c2', state: 'APPLIED'});
  assertHistoricalRows(before, after, journal);
  for (const table of Object.keys(before)) {
    const changed = structuredClone(after); changed[table].shift();
    assert.throws(() => assertHistoricalRows(before, changed, journal), /Historical/);
  }
  after.settlements.push({order_id: 'p', amount: '5.00'});
  assert.throws(() => assertHistoricalRows(before, after, journal), /settlements/);
});

test('player balances are read independently through Core and must remain unchanged across replay', () => {
  const journal = {player: 'SupplyCook', nation: randomUUID()};
  const row = {type: 'accounts', bootId: randomUUID(), correlation: randomUUID(), nation: journal.nation,
    player: offlinePlayerId(journal.player), playerBalance: '36.00', treasuryBalance: '900.00'};
  validateAccounts(row, journal);
  validateAccounts({...row, bootId: randomUUID(), playerBalance: '36'}, journal, row);
  assert.throws(() => validateAccounts({...row, playerBalance: '41.00'}, journal, row), /Player balance changed/);
  assert.throws(() => validateAccounts({...row, player: randomUUID()}, journal, row), /identity mismatch/);
});
