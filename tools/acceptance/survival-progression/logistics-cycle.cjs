const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const {isDeepStrictEqual} = require('node:util');
const {offlinePlayerId} = require('./survival-checkpoint.cjs');
const {writeJson} = require('./durable-json.cjs');

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
  requireThat(plan.nativeReplay === undefined || typeof plan.nativeReplay === 'boolean', 'nativeReplay must be boolean');
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
    const warehouses = rows('SELECT * FROM warehouse WHERE id IN (?,?) ORDER BY id', plan.depot, plan.front);
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
  if (plan.demandBudget) requireThat(consumed.every(row => row.demand <= plan.demandBudget.maximumDemand[row.resource]),
    'Observed consumption exceeded the planned demand bound');
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

const saveJournal = (file, value, exclusive = false) => writeJson(file, value, {exclusive});
const actionFor = (journal, verb, resource) => journal.actions.find(row => row.key === `${verb}:${resource}`);

function checkPurchase(plan, journal, resource, row) {
  requireThat(row && row.depot === plan.depot && row.resource === resource
    && row.item_count === plan.counts[resource] && row.nation === journal.nation
    && money(row.price) === money(plan.price)
    && (row.seller === null || row.seller === offlinePlayerId(journal.player)), 'Purchase identity changed');
  requireThat(!['REVIEW', 'CANCELLED'].includes(row.state), 'Purchase requires reconciliation');
  requireThat(['OPEN', 'RESERVED', 'SETTLING', 'BOUGHT'].includes(row.state)
    && (row.state !== 'BOUGHT' || row.seller === offlinePlayerId(journal.player)), 'Invalid purchase state or seller');
  return row;
}

function checkShipment(plan, journal, order, row) {
  requireThat(row && row.purchase === order.purchase && row.origin === plan.depot
    && row.target === plan.front && row.nation === journal.nation && money(row.reward) === money(plan.reward)
    && (row.carrier === null || row.carrier === offlinePlayerId(journal.player)), 'Shipment identity changed');
  requireThat(!['REVIEW', 'CANCELLED', 'FAILED'].includes(row.state), 'Shipment requires reconciliation');
  requireThat(['OPEN', 'IN_TRANSIT', 'SETTLING', 'DELIVERED'].includes(row.state)
    && (row.state === 'OPEN' || row.carrier === offlinePlayerId(journal.player)), 'Invalid shipment state or carrier');
  if (row.state === 'IN_TRANSIT') requireThat(row.expires > Date.now(), 'Shipment failed or expired during travel');
  return row;
}

// Recover a creation whose response was lost using the saved pre-command IDs.
// Missing/ambiguous creations are never dispatched a second time.
function reconcileOrders(plan, journal, snapshot) {
  for (const resource of RESOURCES) {
    let order = journal.orders.find(row => row.resource === resource);
    const buy = actionFor(journal, 'buy', resource);
    if (!order && buy) {
      requireThat(Array.isArray(buy.beforeIds), 'Creation intent lacks its baseline; inspect the legacy run');
      const found = snapshot.purchases.filter(row => !buy.beforeIds.includes(row.id)
        && !journal.orders.some(other => other.purchase === row.id));
      requireThat(found.length <= 1, 'Concurrent purchase creation requires inspection');
      if (found.length) {
        const purchase = checkPurchase(plan, journal, resource, found[0]);
        order = {resource, purchase: purchase.id}; journal.orders.push(order);
      }
    }
    if (!order) continue;
    requireThat(buy, 'Purchase has no persisted creation intent');
    checkPurchase(plan, journal, resource, snapshot.purchases.find(row => row.id === order.purchase));
    const ship = actionFor(journal, 'ship', resource);
    if (!order.shipment && ship) {
      requireThat(Array.isArray(ship.beforeIds), 'Shipment intent lacks its baseline');
      const found = snapshot.shipments.filter(row => !ship.beforeIds.includes(row.id)
        && row.purchase === order.purchase);
      requireThat(found.length <= 1, 'Concurrent shipment creation requires inspection');
      if (found.length) order.shipment = checkShipment(plan, journal, order, found[0]).id;
    }
    if (order.shipment) {
      requireThat(ship, 'Shipment has no persisted creation intent');
      checkShipment(plan, journal, order, snapshot.shipments.find(row => row.id === order.shipment));
    }
  }
  for (const [table, key] of [['purchases', 'purchase'], ['shipments', 'shipment']]) {
    const known = new Set([...journal.baseline[table].map(row => row.id), ...journal.orders.map(row => row[key])]);
    requireThat(snapshot[table].every(row => known.has(row.id)), 'Unrelated orders entered the dedicated warehouses');
  }
  requireThat(journal.orders.length <= 6 && new Set(journal.orders.map(row => row.resource)).size === journal.orders.length,
    'Journal has duplicate resource orders');
}

function prepareCycle({stage, plan, player, inventory, savedBefore, plugins}) {
  validatePlan(plan);
  if (plan.demandBudget) require('./demand-plan.cjs').verifyDemandBudget(plan, {plugins});
  const file = journalPath(stage, plan), snapshot = readSnapshot(stage, plan), existed = fs.existsSync(file);
  let journal;
  if (!existed) {
    const {depot} = assertStart(plan, snapshot, inventory);
    journal = {version: 2, plan, player, nation: depot.nation, initialInventory: inventory, baseline: snapshot,
      savedBefore, plugins, startedAt: new Date().toISOString(), state: 'running', orders: [], actions: []};
  } else {
    journal = JSON.parse(fs.readFileSync(file, 'utf8'));
    requireThat(journal.version === 2, 'Legacy logistics journal requires manual inspection; do not delete it');
    requireThat(isDeepStrictEqual(journal.plan, plan) && journal.player === player, 'Logistics plan or player changed');
    requireThat(journal.state !== 'stopped-and-verified', 'Logistics run is already verified; use its restart proof');
    requireThat(!plugins || isDeepStrictEqual(plugins, journal.plugins), 'Deployed plugins changed during logistics');
    requireThat(isDeepStrictEqual(snapshot.warehouses, journal.baseline.warehouses), 'Warehouse binding or position changed');
    reconcileOrders(plan, journal, snapshot);
    for (const resource of RESOURCES) {
      const item = ITEMS[resource], initial = journal.initialInventory[item], current = inventory[item] ?? 0;
      const order = journal.orders.find(row => row.resource === resource);
      const purchase = snapshot.purchases.find(row => row.id === order?.purchase);
      const sold = actionFor(journal, 'sell', resource);
      if (purchase?.state === 'BOUGHT') {
        requireThat(sold && current === initial - plan.counts[resource], 'Saved inventory does not match completed purchases');
      } else {
        requireThat(current === initial || (sold && current === initial - plan.counts[resource]),
          'Inventory changed outside the recorded logistics actions');
      }
    }
    if (savedBefore) requireThat(journal.savedBefore, 'The original saved checkpoint is missing');
  }
  if (savedBefore) {
    requireThat(savedBefore.gameMode === 'survival' && savedBefore.playerId === offlinePlayerId(player), 'Saved player identity or game mode changed');
    journal.attemptBefore = savedBefore;
  }
  fs.mkdirSync(path.dirname(file), {recursive: true});
  saveJournal(file, journal, !existed);
  return journal;
}

async function runCycle({stage, plan, player, inventory, world, travel, chat, until, log}) {
  const file = journalPath(stage, plan);
  const journal = prepareCycle({stage, plan, player, inventory: inventory()});
  const depot = journal.baseline.warehouses.find(row => row.id === plan.depot);
  const front = journal.baseline.warehouses.find(row => row.id === plan.front);
  requireThat(world === depot.world, 'Player is in a different world');
  let atDepot = false;
  const snapshot = () => {
    const state = readSnapshot(stage, plan);
    requireThat(isDeepStrictEqual(state.warehouses, journal.baseline.warehouses), 'Warehouse binding or position changed');
    reconcileOrders(plan, journal, state);
    return state;
  };
  const dispatch = async (verb, resource, command, observe, label, timeout = 60000) => {
    let action = actionFor(journal, verb, resource);
    if (!action) {
      if (verb !== 'deliver' && !atDepot) { await travel(depot); atDepot = true; }
      const state = snapshot();
      const order = journal.orders.find(row => row.resource === resource);
      if (verb === 'sell') {
        const row = state.purchases.find(row => row.id === order.purchase);
        requireThat(row.state === 'OPEN' && row.seller === null
          && !state.transfers.some(transfer => transfer.purchase === row.id), 'Sale progressed without a recorded command');
      }
      if (verb === 'accept') {
        const row = state.shipments.find(row => row.id === order.shipment);
        requireThat(row.state === 'OPEN' && row.carrier === null, 'Acceptance progressed without a recorded command');
      }
      action = {key: `${verb}:${resource}`, command, at: new Date().toISOString(), state: 'dispatched'};
      if (verb === 'buy') action.beforeIds = state.purchases.map(row => row.id);
      if (verb === 'ship') action.beforeIds = state.shipments.map(row => row.id);
      journal.actions.push(action);
      saveJournal(file, journal);
      chat(command);
    } else requireThat(action.command === command, 'Recorded command differs from the logistics plan');
    const result = await until(() => observe(snapshot()), label, timeout);
    action.state = 'observed';
    saveJournal(file, journal);
    return result;
  };
  try {
    for (const resource of RESOURCES) {
      await dispatch('buy', resource, `/supply buy ${plan.depot} ${resource.toLowerCase()} ${plan.counts[resource]} ${plan.price}`,
        () => journal.orders.find(row => row.resource === resource), `new ${resource} purchase`);
      const order = journal.orders.find(row => row.resource === resource);
      await dispatch('sell', resource, `/supply sell ${order.purchase}`, state =>
        checkPurchase(plan, journal, resource, state.purchases.find(row => row.id === order.purchase)).state === 'BOUGHT',
      `paid ${resource} purchase`);
      await dispatch('ship', resource, `/supply ship ${order.purchase} ${plan.front} ${plan.reward}`,
        () => order.shipment, `${resource} shipment`);
      await dispatch('accept', resource, `/supply accept ${order.shipment}`, state => {
        const row = checkShipment(plan, journal, order, state.shipments.find(row => row.id === order.shipment));
        return ['IN_TRANSIT', 'SETTLING', 'DELIVERED'].includes(row.state) && row.carrier === offlinePlayerId(player);
      }, `accepted ${resource} shipment`);
    }
    const needsDelivery = journal.orders.some(order => !actionFor(journal, 'deliver', order.resource));
    if (needsDelivery) await travel(front);
    for (const order of journal.orders) {
      if (!actionFor(journal, 'deliver', order.resource)) await until(() => {
        const row = checkShipment(plan, journal, order, snapshot().shipments.find(row => row.id === order.shipment));
        requireThat(row.state === 'IN_TRANSIT', 'Delivery progressed without a recorded command');
        return row.ready <= Date.now();
      }, 'real transport travel time', 120000);
      await dispatch('deliver', order.resource, `/supply deliver ${order.shipment}`, state =>
        checkShipment(plan, journal, order, state.shipments.find(row => row.id === order.shipment)).state === 'DELIVERED',
      `paid ${order.resource} delivery`);
    }
    const result = await until(() => {
      try { return verifyCycle(plan, journal, snapshot()); }
      catch (error) { if (/^Awaiting /.test(error.message)) return false; throw error; }
    }, 'sustained physical consumption', (plan.cycles + 2) * plan.intervalMillis + 60000);
    journal.state = 'awaiting-normal-stop'; journal.result = result; delete journal.failure; saveJournal(file, journal);
    log('survival_logistics_client_complete', {journal: path.basename(file), result});
  } catch (error) {
    journal.state = 'failed-requires-inspection'; journal.failure = error.message;
    try { saveJournal(file, journal); } catch (persistError) { error.message += `; journal update failed: ${persistError.message}`; }
    throw error;
  }
}

function verifyStoppedCycle(stage, plan, before, after) {
  const file = journalPath(stage, plan), journal = JSON.parse(fs.readFileSync(file, 'utf8'));
  requireThat(isDeepStrictEqual(journal.plan, plan), 'Logistics plan changed after execution');
  requireThat(journal.state === 'awaiting-normal-stop', 'Client did not finish the logistics run');
  if (journal.attemptBefore) requireThat(isDeepStrictEqual(before, journal.attemptBefore), 'Attempt checkpoint changed');
  before = journal.savedBefore ?? before;
  requireThat(before.gameMode === 'survival' && after.gameMode === 'survival'
    && before.playerId === offlinePlayerId(journal.player) && after.playerId === before.playerId, 'Saved player identity or game mode changed');
  for (const resource of RESOURCES) {
    const item = ITEMS[resource];
    requireThat(before.inventory[item] === journal.initialInventory[item], 'Starting inventory differs from the saved survival checkpoint');
    requireThat((after.inventory[item] ?? 0) === before.inventory[item] - plan.counts[resource], 'Saved inventory debit does not match the orders');
  }
  const stoppedSnapshot = readSnapshot(stage, plan), result = verifyCycle(plan, journal, stoppedSnapshot);
  if (plan.nativeReplay) requireThat(journal.nativeBeforeRestart?.length === plan.cycles
    && result.credits.every(id => journal.nativeBeforeRestart.some(row => row.receipt.request === id)), 'Native probes did not complete before stopping');
  journal.state = 'stopped-and-verified'; journal.savedBefore = before; journal.savedAfter = after; journal.result = result;
  journal.stoppedSnapshot = stoppedSnapshot;
  saveJournal(file, journal);
  return {...result, journal: path.basename(file), savedInventoryVerified: true,
    acquisitionProvenanceRequiresPhaseEvidence: !journal.productionEvidence?.survivalProductionVerified,
    productionEvidenceLinked: !!journal.productionEvidence?.productionEvidenceLinked,
    survivalProductionVerified: journal.productionEvidence?.survivalProductionVerified === true,
    unprovenQuantities: journal.productionEvidence?.unprovenQuantities, processRestartReplayVerified: false};
}

module.exports = {ITEMS, RESOURCES, money, nativeReceiptId, validatePlan, journalPath, readSnapshot,
  assertStart, verifyCycle, prepareCycle, runCycle, verifyStoppedCycle};
