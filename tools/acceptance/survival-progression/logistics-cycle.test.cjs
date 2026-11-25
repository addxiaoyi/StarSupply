const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID, createHash} = require('node:crypto');
const {DatabaseSync} = require('node:sqlite');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const {ITEMS, RESOURCES, validatePlan, money, nativeReceiptId, readSnapshot, assertStart, verifyCycle, runCycle, verifyStoppedCycle} = require('./logistics-cycle.cjs');
const army = '11111111-1111-4111-8111-111111111111', nation = '22222222-2222-4222-8222-222222222222';
const player = 'SupplyCook', playerId = offlinePlayerId(player);
const plan = () => ({runId: 'survival-fixture', depot: 'depot', front: 'front', army, cycles: 3,
  intervalMillis: 30000, price: '5.00', reward: '1.00', counts: Object.fromEntries(RESOURCES.map(r => [r, 6]))});
const inventory = () => Object.fromEntries(Object.values(ITEMS).map(item => [item, 8]));

function fixture(t) {
  const targetRoot = path.resolve(__dirname, '../../../target');
  fs.mkdirSync(targetRoot, {recursive: true});
  const stage = fs.mkdtempSync(path.join(targetRoot, 'logistics-test-'));
  fs.mkdirSync(path.join(stage, 'plugins', 'StarSupply'), {recursive: true});
  fs.mkdirSync(path.join(stage, 'folia-proof'));
  const database = path.join(stage, 'plugins', 'StarSupply', 'supply.db');
  const db = new DatabaseSync(database);
  t.after(() => {
    db.close();
    assert.ok(path.resolve(stage).startsWith(targetRoot + path.sep));
    fs.rmSync(stage, {recursive: true});
  });
  for (let version = 1; version <= 11; version++) db.exec(fs.readFileSync(path.resolve(__dirname,
    `../../../src/main/resources/db/v${version}.sql`), 'utf8'));
  const insert = (table, row) => db.prepare(`INSERT INTO ${table} (${Object.keys(row).join(',')}) VALUES (${Object.keys(row).map(() => '?').join(',')})`)
    .run(...Object.values(row));
  const config = plan();
  insert('warehouse', {id: 'depot', nation, kind: 'DEPOT', world: 'world', x: 0, y: 65, z: 0, army: null});
  insert('warehouse', {id: 'front', nation, kind: 'FRONT', world: 'world', x: 10, y: 65, z: 0, army});
  const baseline = readSnapshot(stage, config), orders = [];
  function addOrder(resource) {
    const p = randomUUID(), s = randomUUID(), transfer = randomUUID(), payment = randomUUID();
    insert('purchase', {id: p, nation, depot: 'depot', resource, item_count: 6, price: '5.00', state: 'BOUGHT', seller: playerId,
      settlement: payment, created: 1000});
    insert('shipment', {id: s, purchase: p, nation, origin: 'depot', target: 'front', reward: '1.00', state: 'DELIVERED', carrier: playerId, created: 1000});
    insert('item_transfer', {id: transfer, purchase: p, player: playerId, resource, state: 'STORED', before_hash: 'before', after_hash: 'after', created: 1000});
    insert('item_lot', {transfer, ordinal: 0, item_id: `minecraft:${ITEMS[resource]}`, quality: 'BASE', quality_ratio: 1,
      resource, original_count: 6, remaining: 6, units_per_item: 1, payload: Buffer.from('test fixture only'), warehouse: 'front', shipment: null});
    insert('settlement', {id: payment, order_id: p, nation, player: playerId, kind: 'PURCHASE', amount: '5.00', state: 'PAID', updated: 1000});
    insert('settlement', {id: randomUUID(), order_id: s, nation, player: playerId, kind: 'TRANSPORT', amount: '1.00', state: 'PAID', updated: 1000});
    const order = {resource, purchase: p, shipment: s}; orders.push(order); return order;
  }
  function addCycle(slot, units = 1) {
    for (const resource of RESOURCES) {
      insert('consumption', {army, slot, interval_ms: 30000, consumed_at: slot * 30000, resource, demand: 1, units});
      if (units) db.prepare('UPDATE item_lot SET remaining=remaining-? WHERE resource=?').run(units, resource);
    }
    insert('native_supply_credit', {id: nativeReceiptId(army, slot, 30000), army, nation, slot, interval_ms: 30000, points: 10, state: 'APPLIED'});
  }
  return {stage, database, db, config, baseline, orders, insert, addOrder, addCycle,
    populate() { RESOURCES.forEach(addOrder); [10, 11, 12].forEach(slot => addCycle(slot)); },
    journal() { return {plan: config, player, nation, baseline, orders, initialInventory: inventory(), state: 'awaiting-normal-stop'}; },
    snapshot() { return readSnapshot(stage, config); }};
}

test('plan validation rejects weak cycle requirements and command/path injection', () => {
  for (const changed of [{cycles: 1}, {runId: '../escape'}, {depot: 'depot\n/give'}, {army: 'bad'}, {price: 'NaN'},
    {reward: '-1'}, {intervalMillis: 0}, {counts: {FOOD: 6}}]) assert.throws(() => validatePlan({...plan(), ...changed}));
  assert.equal(money('5.0'), money('5.00'));
  assert.equal(money('0.01'), 1n);
});

test('read-only snapshots use real v11 migrations and do not modify the database', t => {
  const f = fixture(t); f.populate();
  const hash = () => createHash('sha256').update(fs.readFileSync(f.database)).digest('hex');
  const before = hash(), snapshot = f.snapshot();
  assert.equal(snapshot.lots.length, 6);
  assert.equal(snapshot.lots[0].payload, undefined);
  assert.equal(hash(), before);
  assert.deepEqual(verifyCycle(f.config, f.journal(), snapshot).fullSlots, [10, 11, 12]);
});

test('starting gates reject missing survival items and prior stock before any commands', t => {
  const f = fixture(t);
  assert.throws(() => assertStart(f.config, f.baseline, {...inventory(), honey_bottle: 2}), /Produce 6 honey_bottle/);
  assert.equal(assertStart(f.config, f.baseline, inventory()).front.army, army);
  f.addOrder('FOOD');
  assert.throws(() => assertStart(f.config, f.snapshot(), inventory()), /empty dedicated warehouses/);
});

test('partial or nonconsecutive consumption cannot pass sustained coverage', t => {
  const f = fixture(t); RESOURCES.forEach(f.addOrder); [10, 12, 14].forEach(slot => f.addCycle(slot));
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Awaiting 3 consecutive/);
  f.db.exec('UPDATE consumption SET units=0 WHERE resource=\'MEDICAL\'; UPDATE item_lot SET remaining=6 WHERE resource=\'MEDICAL\'');
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Awaiting 3 consecutive/);
});

test('historical cycles and consumption under another interval cannot be reused', t => {
  const f = fixture(t); f.populate();
  const journal = f.journal(); journal.baseline.consumption = f.snapshot().consumption;
  assert.throws(() => verifyCycle(f.config, journal, f.snapshot()), /not attributable/);
  journal.baseline.consumption = [];
  f.db.exec('UPDATE consumption SET interval_ms=60000');
  assert.throws(() => verifyCycle(f.config, journal, f.snapshot()), /interval or resource mismatch/);
});

test('duplicate payments and incorrect player credits fail the proof', t => {
  const f = fixture(t); f.populate();
  const purchase = f.snapshot().purchases[0];
  f.insert('settlement', {id: randomUUID(), order_id: purchase.id, nation, player: playerId,
    kind: 'PURCHASE', amount: '5.00', state: 'PAID', updated: 1000});
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Settlement/);
  f.db.prepare('DELETE FROM settlement WHERE order_id=? AND id<>?').run(purchase.id, purchase.settlement);
  f.db.prepare('UPDATE settlement SET player=? WHERE id=?').run(army, purchase.settlement);
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Settlement/);
});

test('physical losses, foreign lots and wrong item mappings fail attribution', t => {
  const f = fixture(t); f.populate();
  f.db.exec('UPDATE item_lot SET remaining=remaining-1 WHERE resource=\'FOOD\'');
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /not attributable/);
  f.db.exec('UPDATE item_lot SET remaining=remaining+1 WHERE resource=\'FOOD\'');
  f.addOrder('FOOD');
  const journal = f.journal(); journal.orders = journal.orders.slice(0, 6);
  assert.throws(() => verifyCycle(f.config, journal, f.snapshot()), /Unrelated stock/);
  f.db.exec('UPDATE item_lot SET item_id=\'minecraft:stone\' WHERE resource=\'FOOD\'');
  assert.throws(() => verifyCycle(f.config, journal, f.snapshot()), /lot identity/);
});

test('applied credits must match the full consumption cycles and army', t => {
  const f = fixture(t); f.populate();
  f.db.exec('UPDATE native_supply_credit SET state=\'PENDING\' WHERE slot=11');
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Awaiting matching native/);
  f.db.exec('UPDATE native_supply_credit SET state=\'APPLIED\',points=9 WHERE slot=11');
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Awaiting matching native/);
  f.db.prepare('UPDATE native_supply_credit SET points=10,id=? WHERE slot=11').run(randomUUID());
  assert.throws(() => verifyCycle(f.config, f.journal(), f.snapshot()), /Awaiting matching native/);
});

test('stopped verification requires the exact saved inventory debit and player identity', t => {
  const f = fixture(t); f.populate();
  const file = path.join(f.stage, 'folia-proof', `survival-logistics-${f.config.runId}.json`);
  fs.writeFileSync(file, JSON.stringify(f.journal()));
  const before = {gameMode: 'survival', playerId, inventory: inventory()};
  const after = {...before, inventory: Object.fromEntries(Object.keys(before.inventory).map(item => [item, 2]))};
  assert.throws(() => verifyStoppedCycle(f.stage, f.config, before, {...after, playerId: army}), /identity/);
  assert.throws(() => verifyStoppedCycle(f.stage, f.config, before, {...after, inventory: inventory()}), /inventory debit/);
  const result = verifyStoppedCycle(f.stage, f.config, before, after);
  assert.equal(result.savedInventoryVerified, true);
  assert.equal(result.coreReceiptReplayVerified, false);
  assert.equal(result.acquisitionProvenanceRequiresPhaseEvidence, true);
  assert.equal(JSON.parse(fs.readFileSync(file)).state, 'stopped-and-verified');
});

test('a lost creation response preserves intent and prevents automatic duplicate orders', async t => {
  const f = fixture(t), commands = [];
  const args = {stage: f.stage, plan: f.config, player, inventory, world: 'world', travel: async () => {},
    chat: text => commands.push(text), until: async () => { throw new Error('response lost'); }, log: () => {}};
  await assert.rejects(runCycle(args), /response lost/);
  assert.equal(commands.length, 1);
  assert.match(commands[0], /^\/supply buy /);
  const file = path.join(f.stage, 'folia-proof', `survival-logistics-${f.config.runId}.json`);
  const journal = JSON.parse(fs.readFileSync(file));
  assert.equal(journal.actions[0].command, commands[0]);
  assert.equal(journal.state, 'failed-requires-inspection');
  await assert.rejects(runCycle(args), /already has a journal/);
  assert.equal(commands.length, 1);
});

test('normal command workflow uses existing inventory and waits for real persisted states', async t => {
  const f = fixture(t), commands = [], moves = [];
  let cyclesWritten = false;
  await runCycle({stage: f.stage, plan: f.config, player, inventory, world: 'world',
    travel: async point => { moves.push(point.id); }, log: () => {},
    chat: text => {
      commands.push(text); const [, verb, ...args] = text.split(' ');
      if (verb === 'buy') {
        const order = f.addOrder(args[1].toUpperCase());
        f.db.prepare('UPDATE purchase SET state=\'OPEN\',seller=NULL WHERE id=?').run(order.purchase);
        f.db.prepare('UPDATE shipment SET state=\'CANCELLED\' WHERE id=?').run(order.shipment);
      } else if (verb === 'sell') {
        f.db.prepare('UPDATE purchase SET state=\'BOUGHT\',seller=? WHERE id=?').run(playerId, args[0]);
      } else if (verb === 'ship') {
        f.db.prepare('UPDATE shipment SET state=\'OPEN\',carrier=NULL WHERE purchase=?').run(args[0]);
      } else if (verb === 'accept') {
        f.db.prepare('UPDATE shipment SET state=\'IN_TRANSIT\',carrier=?,ready=?,expires=? WHERE id=?')
          .run(playerId, Date.now() - 1, Date.now() + 60000, args[0]);
      } else if (verb === 'deliver') f.db.prepare('UPDATE shipment SET state=\'DELIVERED\' WHERE id=?').run(args[0]);
      else assert.fail(`Unexpected command ${text}`);
    },
    until: async (read, label) => {
      if (label === 'sustained physical consumption' && !cyclesWritten) {
        [10, 11, 12].forEach(slot => f.addCycle(slot)); cyclesWritten = true;
      }
      const result = read(); assert.ok(result, `Fixture did not reach ${label}`); return result;
    }});
  assert.deepEqual(moves, ['depot', 'front']);
  assert.equal(commands.length, 30);
  assert.ok(commands.every(text => /^\/supply (buy|sell|ship|accept|deliver) /.test(text)));
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.stage, 'folia-proof', `survival-logistics-${f.config.runId}.json`))).state,
    'awaiting-normal-stop');
});
