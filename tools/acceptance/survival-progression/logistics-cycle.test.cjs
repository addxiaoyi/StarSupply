const assert = require('node:assert/strict');
const {test} = require('node:test');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID, createHash} = require('node:crypto');
const {DatabaseSync} = require('node:sqlite');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const {ITEMS, RESOURCES, validatePlan, money, nativeReceiptId, journalPath, readSnapshot,
  assertStart, verifyCycle, prepareCycle, runCycle, verifyStoppedCycle} = require('./logistics-cycle.cjs');
const {prepareReplay, probeCredits, verifyReplayStopped} = require('./native-replay.cjs');
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
  await assert.rejects(runCycle(args), /response lost/);
  assert.equal(commands.length, 1);
});

function driver(f, afterCommand = () => {}) {
  const commands = [], moves = [], items = inventory();
  let cyclesWritten = false;
  const args = {stage: f.stage, plan: f.config, player, inventory: () => ({...items}), world: 'world',
    travel: async point => { moves.push(point.id); }, log: () => {},
    chat: text => {
      commands.push(text); const [, verb, ...args] = text.split(' ');
      if (verb === 'buy') {
        f.insert('purchase', {id: randomUUID(), nation, depot: 'depot', resource: args[1].toUpperCase(),
          item_count: 6, price: '5.00', state: 'OPEN', seller: null, settlement: null, created: Date.now()});
      } else if (verb === 'sell') {
        const purchase = f.db.prepare('SELECT * FROM purchase WHERE id=?').get(args[0]);
        assert.equal(purchase.state, 'OPEN');
        const transfer = randomUUID(), settlement = randomUUID(), resource = purchase.resource;
        items[ITEMS[resource]] -= 6;
        f.db.prepare('UPDATE purchase SET state=\'BOUGHT\',seller=?,settlement=? WHERE id=?').run(playerId, settlement, args[0]);
        f.insert('item_transfer', {id: transfer, purchase: purchase.id, player: playerId, resource, state: 'STORED',
          before_hash: 'before', after_hash: 'after', created: Date.now()});
        f.insert('item_lot', {transfer, ordinal: 0, item_id: `minecraft:${ITEMS[resource]}`, quality: 'BASE', quality_ratio: 1,
          resource, original_count: 6, remaining: 6, units_per_item: 1, payload: Buffer.from('test fixture only'),
          warehouse: 'depot', shipment: null});
        f.insert('settlement', {id: settlement, order_id: purchase.id, nation, player: playerId,
          kind: 'PURCHASE', amount: '5.00', state: 'PAID', updated: Date.now()});
      } else if (verb === 'ship') {
        const shipment = randomUUID();
        f.insert('shipment', {id: shipment, purchase: args[0], nation, origin: 'depot', target: 'front', reward: '1.00',
          state: 'OPEN', carrier: null, created: Date.now()});
        f.db.prepare('UPDATE item_lot SET shipment=? WHERE transfer IN (SELECT id FROM item_transfer WHERE purchase=?)')
          .run(shipment, args[0]);
      } else if (verb === 'accept') {
        f.db.prepare('UPDATE shipment SET state=\'IN_TRANSIT\',carrier=?,ready=?,expires=? WHERE id=?')
          .run(playerId, Date.now() - 1, Date.now() + 60000, args[0]);
      } else if (verb === 'deliver') {
        f.db.prepare('UPDATE shipment SET state=\'DELIVERED\' WHERE id=?').run(args[0]);
        f.db.prepare('UPDATE item_lot SET warehouse=\'front\',shipment=NULL WHERE shipment=?').run(args[0]);
        f.insert('settlement', {id: randomUUID(), order_id: args[0], nation, player: playerId,
          kind: 'TRANSPORT', amount: '1.00', state: 'PAID', updated: Date.now()});
      }
      else assert.fail(`Unexpected command ${text}`);
      afterCommand(verb, text);
    },
    until: async (read, label) => {
      if (label === 'sustained physical consumption' && !cyclesWritten) {
        [10, 11, 12].forEach(slot => f.addCycle(slot)); cyclesWritten = true;
      }
      const result = read(); assert.ok(result, `Fixture did not reach ${label}`); return result;
    }};
  return {args, commands, moves, items};
}

test('normal command workflow uses existing inventory and waits for real persisted states', async t => {
  const f = fixture(t), d = driver(f);
  await runCycle(d.args);
  assert.deepEqual(d.moves, ['depot', 'front']);
  assert.equal(d.commands.length, 30);
  assert.ok(d.commands.every(text => /^\/supply (buy|sell|ship|accept|deliver) /.test(text)));
  assert.equal(JSON.parse(fs.readFileSync(path.join(f.stage, 'folia-proof', `survival-logistics-${f.config.runId}.json`))).state,
    'awaiting-normal-stop');
});

for (const boundary of ['buy', 'sell', 'ship', 'accept', 'deliver']) {
  test(`resume after committed ${boundary} and lost response never repeats the economic action`, async t => {
    const f = fixture(t);
    let interrupted = false;
    const d = driver(f, verb => {
      if (!interrupted && verb === boundary) { interrupted = true; throw new Error('client interrupted'); }
    });
    const before = {gameMode: 'survival', playerId, inventory: inventory(), sha256: 'original-save'};
    prepareCycle({stage: f.stage, plan: f.config, player, inventory: before.inventory, savedBefore: before});
    await assert.rejects(runCycle(d.args), /client interrupted/);
    const resumedBefore = {...before, inventory: {...d.items}, sha256: 'resumed-save'};
    prepareCycle({stage: f.stage, plan: f.config, player, inventory: resumedBefore.inventory, savedBefore: resumedBefore});
    await runCycle(d.args);
    assert.equal(d.commands.length, 30);
    assert.equal(new Set(d.commands).size, 30);
    assert.equal(f.snapshot().settlements.length, 12);
    assert.deepEqual(d.items, Object.fromEntries(Object.keys(inventory()).map(key => [key, 2])));
    const after = {...before, inventory: {...d.items}, sha256: 'final-save'};
    assert.equal(verifyStoppedCycle(f.stage, f.config, resumedBefore, after).savedInventoryVerified, true);
    const journal = JSON.parse(fs.readFileSync(journalPath(f.stage, f.config)));
    assert.equal(journal.savedBefore.sha256, 'original-save');
    await assert.rejects(runCycle(d.args), /already verified/);
  });
}

test('resume after completed deliveries only waits for consumption and the normal save', async t => {
  const f = fixture(t), d = driver(f);
  await runCycle(d.args);
  const commands = d.commands.length, moves = d.moves.length;
  await runCycle(d.args);
  assert.equal(d.commands.length, commands);
  assert.equal(d.moves.length, moves);
});

test('resume rejects changed plan, identity, warehouse binding and unexplained inventory changes', async t => {
  const f = fixture(t), d = driver(f, () => { throw new Error('client interrupted'); });
  await assert.rejects(runCycle(d.args), /interrupted/);
  for (const altered of [{plan: {...f.config, reward: '2.00'}}, {player: 'SomeoneElse'},
    {inventory: () => ({...d.items, iron_ingot: 9})}]) {
    await assert.rejects(runCycle({...d.args, ...altered}), /changed/);
  }
  f.db.exec('UPDATE warehouse SET x=999 WHERE id=\'front\'');
  await assert.rejects(runCycle(d.args), /binding or position changed/);
  assert.equal(d.commands.length, 1);
});

test('ambiguous creation, manual review and expired shipments stop without more commands', async t => {
  const f = fixture(t), d = driver(f, verb => { if (verb === 'accept') throw new Error('client interrupted'); });
  await assert.rejects(runCycle(d.args), /interrupted/);
  const count = d.commands.length;
  f.db.exec('UPDATE purchase SET state=\'REVIEW\'');
  await assert.rejects(runCycle(d.args), /reconciliation/);
  f.db.exec('UPDATE purchase SET state=\'BOUGHT\'; UPDATE shipment SET expires=1');
  await assert.rejects(runCycle(d.args), /expired/);
  assert.equal(d.commands.length, count);
  const f2 = fixture(t), d2 = driver(f2, () => { throw new Error('client interrupted'); });
  await assert.rejects(runCycle(d2.args), /interrupted/);
  const existing = f2.snapshot().purchases[0];
  f2.insert('purchase', {...existing, id: randomUUID()});
  await assert.rejects(runCycle(d2.args), /Concurrent purchase/);
  assert.equal(d2.commands.length, 1);
});

test('legacy journals without recovery baselines remain untouched', async t => {
  const f = fixture(t), d = driver(f);
  const file = journalPath(f.stage, f.config), legacy = JSON.stringify({...f.journal(), version: 1});
  fs.writeFileSync(file, legacy);
  await assert.rejects(runCycle(d.args), /Legacy logistics journal/);
  assert.equal(fs.readFileSync(file, 'utf8'), legacy);
  assert.deepEqual(d.commands, []);
});

test('native probes bind all physical credits to new correlations and a different boot after a normal save', async t => {
  const f = fixture(t); f.config.nativeReplay = true;
  const d = driver(f), plugins = [{name: 'SupplyNativeProof-1.0.0.jar', sha256: 'fixture'}];
  const before = {gameMode: 'survival', playerId, inventory: inventory(), sha256: 'before'};
  prepareCycle({stage: f.stage, plan: f.config, player, inventory: before.inventory, savedBefore: before, plugins});
  await runCycle(d.args);
  const folder = path.join(f.stage, 'plugins', 'SupplyNativeProof'); fs.mkdirSync(folder);
  const evidence = path.join(folder, 'evidence.jsonl');
  fs.writeFileSync(evidence, '');
  let bootId = randomUUID();
  const commands = [];
  const args = {stage: f.stage, plan: f.config,
    command: async text => {
      commands.push(text);
      const [, verb, request, correlation, accountCorrelation] = text.split(' ');
      if (verb === 'accounts') {
        assert.equal(request, nation); assert.equal(correlation, playerId);
        fs.appendFileSync(evidence, JSON.stringify({type: 'accounts', bootId, nation, player: playerId,
          correlation: accountCorrelation, playerBalance: '40.00', treasuryBalance: '999999.00'}) + '\n');
        return;
      }
      assert.equal(verb, 'retry');
      fs.appendFileSync(evidence, JSON.stringify({type: 'retry-passed', bootId, correlation,
        receipt: {request, army, nation, requestedPoints: 10, appliedPoints: 10},
        nativeSupplyBefore: 60, nativeSupplyAfter: 60, treasuryBefore: '999999999999999.00',
        treasuryAfter: '999999999999999', conflictRejected: true}) + '\n');
    }, until: async read => { const value = read(); assert.ok(value); return value; }};
  const original = await probeCredits(args);
  const after = {...before, inventory: {...d.items}, sha256: 'final'};
  verifyStoppedCycle(f.stage, f.config, before, after);
  assert.throws(() => prepareReplay(f.stage, f.config, [], after), /plugins changed/);
  assert.throws(() => prepareReplay(f.stage, f.config, plugins, {...after, sha256: 'foreign-save'}), /player changed/);
  prepareReplay(f.stage, f.config, plugins, after);
  await assert.rejects(probeCredits({...args, restarting: true}), /same plugin boot/);
  bootId = randomUUID();
  const resumed = await probeCredits({...args, restarting: true});
  assert.deepEqual(resumed.map(row => row.receipt), original.map(row => row.receipt));
  assert.ok(resumed.every(row => original.every(previous => previous.correlation !== row.correlation)));
  const proof = verifyReplayStopped(f.stage, f.config, after, after);
  assert.equal(proof.processRestartReplayVerified, true);
  assert.equal(proof.coreReceiptReplayVerified, true);
  assert.equal(proof.playerBalanceUnchanged, true);
  assert.throws(() => verifyReplayStopped(f.stage, f.config, after, {...after, sha256: 'modified'}), /Player save changed/);
  assert.equal(new Set(commands).size, commands.length);
  const paid = f.snapshot().settlements[0];
  f.db.prepare('UPDATE settlement SET amount=\'10.00\' WHERE id=?').run(paid.id);
  assert.throws(() => verifyReplayStopped(f.stage, f.config, after, after), /Settlement/);
});

test('settling purchases are observed to completion without replaying their sale', async t => {
  const f = fixture(t);
  let once = false;
  const d = driver(f, verb => {
    if (verb === 'sell' && !once) {
      once = true; f.db.exec('UPDATE purchase SET state=\'SETTLING\''); throw new Error('settlement in progress');
    }
  });
  await assert.rejects(runCycle(d.args), /settlement in progress/);
  const poll = d.args.until;
  d.args.until = async (read, label) => {
    if (label === 'paid FOOD purchase') f.db.exec('UPDATE purchase SET state=\'BOUGHT\'');
    return poll(read, label);
  };
  await runCycle(d.args);
  assert.equal(d.commands.length, 30);
  assert.equal(f.snapshot().settlements.length, 12);
});
