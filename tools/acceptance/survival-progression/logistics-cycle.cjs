const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');

const ITEMS = Object.freeze({FOOD: 'bread', MEDICAL: 'honey_bottle', EQUIPMENT: 'iron_ingot',
  MATERIALS: 'cobblestone', ENGINEERING: 'scaffolding', STRATEGIC: 'firework_rocket'});
const RESOURCES = Object.keys(ITEMS);
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
const requireThat = (condition, message) => { if (!condition) throw new Error(message); };
const cycleKey = row => `${row.slot}:${row.interval_ms}`;
const consumptionKey = row => `${cycleKey(row)}:${row.resource}`;

function nativeReceiptId(army, slot, intervalMillis) {
  const bytes = createHash('md5').update(`starsupply:consumption:${army}:${slot}:${intervalMillis}`, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function money(value) {
  requireThat(typeof value === 'string' && /^(0|[1-9]\d{0,7})(\.\d{1,2})?$/.test(value), 'Invalid money amount');
  const [whole, fraction = ''] = value.split('.');
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'));
}

function validatePlan(plan) {
  requireThat(plan && typeof plan === 'object', 'Missing logistics plan');
  requireThat(/^[a-z0-9][a-z0-9_-]{0,31}$/.test(plan.runId ?? ''), 'Invalid logistics run ID');
  for (const name of ['depot', 'front']) requireThat(/^[a-z0-9][a-z0-9_-]{0,31}$/.test(plan[name] ?? ''), `Invalid ${name}`);
  requireThat(plan.depot !== plan.front && UUID.test(plan.army ?? ''), 'Distinct depot/front and army UUID required');
  requireThat(Number.isInteger(plan.cycles) && plan.cycles >= 3 && plan.cycles <= 12, 'Require 3 to 12 consecutive full cycles');
  requireThat(Number.isInteger(plan.intervalMillis) && plan.intervalMillis >= 5000 && plan.intervalMillis <= 86400000,
    'Invalid expected supply interval');
  requireThat(money(plan.price) > 0n && money(plan.reward) > 0n, 'Prices and freight must be positive');
  for (const resource of RESOURCES) requireThat(Number.isInteger(plan.counts?.[resource])
    && plan.counts[resource] >= plan.cycles && plan.counts[resource] <= 256, `Invalid ${resource} delivery count`);
  return plan;
}

function journalPath(stage, plan) {
  validatePlan(plan);
  return path.join(stage, 'folia-proof', `survival-logistics-${plan.runId}.json`);
}

function readSnapshot(stage, plan) {
  const {DatabaseSync} = require('node:sqlite');
  const database = new DatabaseSync(path.join(stage, 'plugins', 'StarSupply', 'supply.db'), {readOnly: true});
  try {
    database.exec('PRAGMA query_only=ON; PRAGMA busy_timeout=1000; BEGIN');
    requireThat(database.prepare('PRAGMA user_version').get().user_version === 11, 'Logistics verifier requires schema v11');
    const rows = (sql, ...args) => database.prepare(sql).all(...args).map(row => ({...row}));
    const warehouses = rows('SELECT * FROM warehouse WHERE id IN (?,?)', plan.depot, plan.front);
    const purchases = rows('SELECT * FROM purchase WHERE depot=?', plan.depot);
    const shipments = rows('SELECT * FROM shipment WHERE origin=? OR target=?', plan.depot, plan.front);
    const transfers = rows('SELECT t.* FROM item_transfer t JOIN purchase p ON p.id=t.purchase WHERE p.depot=?', plan.depot);
    const lots = rows('SELECT l.transfer,l.ordinal,l.item_id,l.quality,l.quality_ratio,l.resource,l.original_count,'
      + 'l.remaining,l.units_per_item,l.warehouse,l.shipment FROM item_lot l JOIN item_transfer t ON t.id=l.transfer '
      + 'JOIN purchase p ON p.id=t.purchase WHERE p.depot=? OR l.warehouse IN (?,?)', plan.depot, plan.depot, plan.front);
    const settlements = rows('SELECT s.* FROM settlement s WHERE order_id IN (SELECT id FROM purchase WHERE depot=?) '
      + 'OR order_id IN (SELECT id FROM shipment WHERE origin=? OR target=?)', plan.depot, plan.depot, plan.front);
    const consumption = rows('SELECT * FROM consumption WHERE army=?', plan.army);
    const credits = rows('SELECT * FROM native_supply_credit WHERE army=?', plan.army);
    database.exec('ROLLBACK');
    return {warehouses, purchases, shipments, transfers, lots, settlements, consumption, credits};
  } finally { database.close(); }
}

function assertStart(plan, snapshot, inventory) {
  validatePlan(plan);
  const depot = snapshot.warehouses.find(row => row.id === plan.depot);
  const front = snapshot.warehouses.find(row => row.id === plan.front);
  requireThat(depot?.kind === 'DEPOT' && front?.kind === 'FRONT' && front.army === plan.army,
    'Use an existing depot and the specified army front');
  requireThat(depot.nation === front.nation && depot.world === front.world, 'Depot and front must share nation and world');
  requireThat(snapshot.lots.every(row => row.remaining === 0), 'Use empty dedicated warehouses; prior stock prevents attribution');
  requireThat(snapshot.shipments.every(row => ['DELIVERED', 'CANCELLED', 'FAILED'].includes(row.state)),
    'Prior active shipments prevent isolated attribution');
  requireThat(snapshot.purchases.every(row => ['BOUGHT', 'CANCELLED'].includes(row.state)),
    'Prior open purchases must be resolved before the survival cycle');
  for (const resource of RESOURCES) requireThat((inventory[ITEMS[resource]] ?? 0) >= plan.counts[resource],
    `Produce ${plan.counts[resource]} ${ITEMS[resource]} before logistics; no items are granted`);
  return {depot, front};
}

function verifyCycle(plan, journal, snapshot) {
  validatePlan(plan);
  const player = offlinePlayerId(journal.player);
  const front = snapshot.warehouses.find(row => row.id === plan.front);
  requireThat(front?.army === plan.army && front.nation === journal.nation, 'Front binding changed');
  requireThat(journal.orders.length === RESOURCES.length
    && new Set(journal.orders.map(row => row.resource)).size === RESOURCES.length, 'Six distinct resource orders required');
  const spent = Object.fromEntries(RESOURCES.map(resource => [resource, 0]));
  const transfers = new Set();
  for (const order of journal.orders) {
    requireThat(RESOURCES.includes(order.resource), 'Unexpected resource order');
    const purchase = snapshot.purchases.find(row => row.id === order.purchase);
    const shipment = snapshot.shipments.find(row => row.id === order.shipment);
    requireThat(purchase?.state === 'BOUGHT' && purchase.seller === player && purchase.depot === plan.depot
      && purchase.resource === order.resource && purchase.item_count === plan.counts[order.resource]
      && purchase.nation === journal.nation && money(purchase.price) === money(plan.price), 'Purchase does not match survival delivery');
    requireThat(shipment?.state === 'DELIVERED' && shipment.purchase === purchase.id && shipment.carrier === player
      && shipment.origin === plan.depot && shipment.target === plan.front && shipment.nation === journal.nation
      && money(shipment.reward) === money(plan.reward), 'Shipment does not match survival delivery');
    for (const [id, kind, amount] of [[purchase.id, 'PURCHASE', plan.price], [shipment.id, 'TRANSPORT', plan.reward]]) {
      const payments = snapshot.settlements.filter(row => row.order_id === id);
      requireThat(payments.length === 1 && payments[0].kind === kind && payments[0].state === 'PAID'
        && payments[0].player === player && payments[0].nation === journal.nation && money(payments[0].amount) === money(amount),
      'Settlement is missing, duplicated or mismatched');
      if (kind === 'PURCHASE') requireThat(payments[0].id === purchase.settlement, 'Purchase receipt mismatch');
    }
    const transfer = snapshot.transfers.filter(row => row.purchase === purchase.id);
    requireThat(transfer.length === 1 && transfer[0].state === 'STORED' && transfer[0].player === player
      && transfer[0].resource === order.resource, 'Physical transfer is missing or ambiguous');
    transfers.add(transfer[0].id);
    const lots = snapshot.lots.filter(row => row.transfer === transfer[0].id);
    requireThat(lots.length > 0 && lots.every(row => row.resource === order.resource && row.units_per_item === 1
      && row.item_id === `minecraft:${ITEMS[order.resource]}` && row.warehouse === plan.front && row.shipment === null),
    'Unexpected physical lot identity, units or destination');
    requireThat(lots.reduce((n, row) => n + row.original_count, 0) === plan.counts[order.resource], 'Physical lot count mismatch');
    spent[order.resource] = lots.reduce((n, row) => n + row.original_count - row.remaining, 0);
  }
  const originalLots = new Set(journal.baseline.lots.map(row => `${row.transfer}:${row.ordinal}`));
  requireThat(snapshot.lots.every(row => transfers.has(row.transfer)
    || (originalLots.has(`${row.transfer}:${row.ordinal}`) && row.remaining === 0)), 'Unrelated stock entered the test warehouses');
  const old = new Set(journal.baseline.consumption.map(consumptionKey));
  const consumed = snapshot.consumption.filter(row => !old.has(consumptionKey(row)));
  requireThat(consumed.every(row => row.army === plan.army && RESOURCES.includes(row.resource)
    && row.interval_ms === plan.intervalMillis && Number.isInteger(row.units) && row.units >= 0
    && Number.isInteger(row.demand) && row.demand > 0 && row.units <= row.demand), 'Consumption interval or resource mismatch');
  for (const resource of RESOURCES) requireThat(consumed.filter(row => row.resource === resource)
    .reduce((n, row) => n + row.units, 0) === spent[resource], 'Consumption is not attributable to the delivered physical lots');
  const groups = new Map();
  for (const row of consumed) {
    if (!groups.has(cycleKey(row))) groups.set(cycleKey(row), []);
    groups.get(cycleKey(row)).push(row);
  }
  const full = [...groups.values()].filter(rows => rows.length === 6 && new Set(rows.map(row => row.resource)).size === 6
    && rows.every(row => row.units === row.demand)).map(rows => rows[0].slot).sort((a, b) => a - b);
  const consecutive = full.findIndex((slot, index) => full.slice(index, index + plan.cycles).length === plan.cycles
    && full.slice(index, index + plan.cycles).every((value, offset) => value === slot + offset));
  requireThat(consecutive >= 0, `Awaiting ${plan.cycles} consecutive full six-resource consumption cycles`);
  const slots = full.slice(consecutive, consecutive + plan.cycles);
  const credits = slots.map(slot => snapshot.credits.filter(row => row.slot === slot && row.interval_ms === plan.intervalMillis));
  requireThat(credits.every(rows => rows.length === 1 && rows[0].state === 'APPLIED' && rows[0].points === 10
    && rows[0].army === plan.army && rows[0].nation === journal.nation
    && rows[0].id === nativeReceiptId(plan.army, rows[0].slot, plan.intervalMillis)), 'Awaiting matching native supply outbox confirmations');
  return {fullSlots: slots, intervalMillis: plan.intervalMillis, spent, credits: credits.map(rows => rows[0].id),
    purchaseSettlements: 6, transportSettlements: 6, coreReceiptReplayVerified: false};
}

function saveJournal(file, value, exclusive = false) {
  const fd = fs.openSync(file, exclusive ? 'wx' : 'w');
  try { fs.writeFileSync(fd, JSON.stringify(value, null, 2) + '\n'); fs.fsyncSync(fd); }
  finally { fs.closeSync(fd); }
}

async function runCycle({stage, plan, player, inventory, world, travel, chat, until, log}) {
  validatePlan(plan);
  const file = journalPath(stage, plan);
  requireThat(!fs.existsSync(file), 'This logistics run already has a journal; inspect its order IDs before any retry');
  const baseline = readSnapshot(stage, plan), initialInventory = inventory();
  const {depot, front} = assertStart(plan, baseline, initialInventory);
  requireThat(world === depot.world, 'Player is in a different world');
  const journal = {version: 1, plan, player, nation: depot.nation, initialInventory, baseline,
    startedAt: new Date().toISOString(), state: 'running', orders: [], actions: []};
  saveJournal(file, journal, true);
  const dispatch = text => {
    requireThat(/^\/supply (buy|sell|ship|accept|deliver) /.test(text), 'Only normal logistics commands are allowed');
    journal.actions.push({command: text, at: new Date().toISOString(), state: 'dispatched'});
    saveJournal(file, journal);
    chat(text);
  };
  try {
    await travel(depot);
    for (const resource of RESOURCES) {
      const known = new Set(readSnapshot(stage, plan).purchases.map(row => row.id));
      dispatch(`/supply buy ${plan.depot} ${resource.toLowerCase()} ${plan.counts[resource]} ${plan.price}`);
      const purchase = await until(() => {
        const found = readSnapshot(stage, plan).purchases.filter(row => !known.has(row.id));
        requireThat(found.length <= 1, 'Concurrent purchase creation requires inspection');
        if (found[0]) requireThat(found[0].state === 'OPEN' && found[0].seller === null
          && found[0].resource === resource && found[0].item_count === plan.counts[resource]
          && found[0].nation === depot.nation && money(found[0].price) === money(plan.price), 'Unexpected newly created purchase');
        return found[0];
      }, `new ${resource} purchase`);
      const order = {resource, purchase: purchase.id}; journal.orders.push(order); saveJournal(file, journal);
      dispatch(`/supply sell ${purchase.id}`);
      await until(() => {
        const row = readSnapshot(stage, plan).purchases.find(row => row.id === purchase.id);
        requireThat(!['REVIEW', 'CANCELLED'].includes(row?.state), 'Purchase requires reconciliation');
        return row?.state === 'BOUGHT';
      }, `paid ${resource} purchase`, 60000);
      dispatch(`/supply ship ${purchase.id} ${plan.front} ${plan.reward}`);
      const shipment = await until(() => readSnapshot(stage, plan).shipments.find(row => row.purchase === purchase.id),
        `${resource} shipment`);
      requireThat(shipment.state === 'OPEN' && shipment.carrier === null && shipment.origin === plan.depot
        && shipment.target === plan.front && shipment.nation === depot.nation
        && money(shipment.reward) === money(plan.reward), 'Unexpected newly created shipment');
      order.shipment = shipment.id; saveJournal(file, journal);
      dispatch(`/supply accept ${shipment.id}`);
      await until(() => readSnapshot(stage, plan).shipments.find(row => row.id === shipment.id)?.state === 'IN_TRANSIT',
        `accepted ${resource} shipment`);
    }
    await travel(front);
    for (const order of journal.orders) {
      await until(() => {
        const row = readSnapshot(stage, plan).shipments.find(row => row.id === order.shipment);
        requireThat(row?.state === 'IN_TRANSIT' && row.expires > Date.now(), 'Shipment failed or expired during travel');
        return row.ready <= Date.now();
      }, 'real transport travel time', 120000);
      dispatch(`/supply deliver ${order.shipment}`);
      await until(() => readSnapshot(stage, plan).shipments.find(row => row.id === order.shipment)?.state === 'DELIVERED',
        `paid ${order.resource} delivery`, 60000);
    }
    const result = await until(() => {
      try { return verifyCycle(plan, journal, readSnapshot(stage, plan)); }
      catch (error) { if (/^Awaiting /.test(error.message)) return false; throw error; }
    }, 'sustained physical consumption', (plan.cycles + 2) * plan.intervalMillis + 60000);
    journal.state = 'awaiting-normal-stop'; journal.result = result; saveJournal(file, journal);
    log('survival_logistics_client_complete', {journal: path.basename(file), result});
  } catch (error) {
    journal.state = 'failed-requires-inspection'; journal.failure = error.message; saveJournal(file, journal);
    throw error;
  }
}

function verifyStoppedCycle(stage, plan, before, after) {
  const file = journalPath(stage, plan), journal = JSON.parse(fs.readFileSync(file, 'utf8'));
  requireThat(JSON.stringify(journal.plan) === JSON.stringify(plan), 'Logistics plan changed after execution');
  requireThat(journal.state === 'awaiting-normal-stop', 'Client did not finish the logistics run');
  requireThat(before.gameMode === 'survival' && after.gameMode === 'survival'
    && before.playerId === offlinePlayerId(journal.player) && after.playerId === before.playerId, 'Saved player identity or game mode changed');
  for (const resource of RESOURCES) {
    const item = ITEMS[resource];
    requireThat(before.inventory[item] === journal.initialInventory[item], 'Starting inventory differs from the saved survival checkpoint');
    requireThat((after.inventory[item] ?? 0) === before.inventory[item] - plan.counts[resource], 'Saved inventory debit does not match the orders');
  }
  const result = verifyCycle(plan, journal, readSnapshot(stage, plan));
  journal.state = 'stopped-and-verified'; journal.savedBefore = before; journal.savedAfter = after; journal.result = result;
  saveJournal(file, journal);
  return {...result, journal: path.basename(file), savedInventoryVerified: true,
    acquisitionProvenanceRequiresPhaseEvidence: true, survivalProductionVerified: false, processRestartReplayVerified: false};
}

module.exports = {ITEMS, RESOURCES, money, nativeReceiptId, validatePlan, readSnapshot, assertStart, verifyCycle, runCycle, verifyStoppedCycle};
