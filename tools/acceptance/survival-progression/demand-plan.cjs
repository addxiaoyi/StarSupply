const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const {isDeepStrictEqual} = require('node:util');
const {RESOURCES, validatePlan} = require('./logistics-cycle.cjs');
const {hash} = require('./production-evidence.cjs');
const {writeJson} = require('./durable-json.cjs');
const requireThat = (value, message) => { if (!value) throw new Error(message); };
const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;

function validateDemand(plan, row, now = Date.now()) {
  requireThat(row?.type === 'demand' && row.protocolVersion === 1 && UUID.test(row.bootId) && UUID.test(row.correlation),
    'A correlated current demand probe is required');
  requireThat(row.army === plan.army && row.front === plan.front && UUID.test(row.nation)
    && row.intervalMillis === plan.intervalMillis && row.atWar && row.access === 'CONNECTED', 'Army demand identity, interval or access mismatch');
  requireThat(Number.isSafeInteger(row.soldiers) && row.soldiers > 0 && Number.isSafeInteger(row.capturedAt)
    && now - row.capturedAt >= 0 && now - row.capturedAt <= 30000, 'Army demand is stale');
  requireThat(row.slot === Math.floor(row.capturedAt / row.intervalMillis), 'Army status is from an earlier interval');
  for (const resource of RESOURCES) requireThat(Number.isSafeInteger(row.currentDemand?.[resource])
    && row.currentDemand[resource] > 0 && Number.isSafeInteger(row.maximumDemand?.[resource])
    && row.maximumDemand[resource] >= row.currentDemand[resource] && row.maximumDemand[resource] <= 100000,
    `Invalid ${resource} demand bound`);
  requireThat(Number.isSafeInteger(row.maxPurchaseItems) && row.maxPurchaseItems > 0, 'Missing configured purchase limit');
  return row;
}

function budgetCounts(plan, maximumDemand, planning) {
  const {deliveryWindowMillis = 120000, reserveCycles = 1} = planning ?? {};
  requireThat(Number.isSafeInteger(deliveryWindowMillis) && deliveryWindowMillis >= 0 && deliveryWindowMillis <= 3600000,
    'Delivery budget must be 0..3600000 milliseconds');
  requireThat(Number.isSafeInteger(reserveCycles) && reserveCycles >= 1 && reserveCycles <= 12, 'Reserve at least one extra consumption cycle');
  const budgetedCycles = plan.cycles + Math.ceil(deliveryWindowMillis / plan.intervalMillis) + reserveCycles;
  const counts = Object.fromEntries(RESOURCES.map(resource => [resource, maximumDemand[resource] * budgetedCycles]));
  return {counts, deliveryWindowMillis, reserveCycles, budgetedCycles};
}

function createDemandPlan(seed, row, {plugins, worldIdentity}, now = Date.now()) {
  validatePlan(seed); validateDemand(seed, row, now);
  const budget = budgetCounts(seed, row.maximumDemand, seed.planning);
  requireThat(RESOURCES.every(resource => budget.counts[resource] <= Math.min(256, row.maxPurchaseItems)),
    'Demand exceeds a single acceptance purchase; use a smaller test army or delivery budget');
  const requiredSlots = RESOURCES.reduce((n, resource) => n + Math.ceil(budget.counts[resource] / (resource === 'MEDICAL' ? 16 : 64)), 0);
  requireThat(requiredSlots <= 28, 'Delivery exceeds available backpack space after reserving eight tool/ingredient slots');
  const base = {...seed}; delete base.demandBudget;
  return validatePlan({...base, counts: budget.counts, demandBudget: {
    version: 1, ...budget, maximumDemand: row.maximumDemand, soldiers: row.soldiers, nation: row.nation,
    capturedAt: row.capturedAt, probe: row, plugins, worldIdentity,
    scope: 'Current troop count without efficiency discounts; configured maximum winter and siege demand plus delivery/reserve cycles.'
  }});
}

function verifyDemandBudget(plan, {plugins, worldIdentity} = {}) {
  if (!plan.demandBudget) return;
  const budget = plan.demandBudget;
  validateDemand(plan, budget.probe, budget.capturedAt);
  requireThat(budget.version === 1 && budget.soldiers === budget.probe.soldiers && budget.nation === budget.probe.nation
    && budget.capturedAt === budget.probe.capturedAt
    && isDeepStrictEqual(budget.maximumDemand, budget.probe.maximumDemand), 'Demand budget identity changed');
  const calculated = budgetCounts(plan, budget.maximumDemand, budget);
  requireThat(isDeepStrictEqual(calculated.counts, plan.counts) && isDeepStrictEqual(budget.counts, plan.counts)
    && calculated.budgetedCycles === budget.budgetedCycles, 'Delivery counts do not match the captured demand budget');
  if (plugins) requireThat(isDeepStrictEqual(plugins, budget.plugins), 'Plugins changed since demand planning');
  if (worldIdentity) requireThat(isDeepStrictEqual(worldIdentity, budget.worldIdentity), 'World changed since demand planning');
}

function assertDemandFits(plan, row, now = Date.now()) {
  verifyDemandBudget(plan); validateDemand(plan, row, now);
  const budget = plan.demandBudget;
  requireThat(budget && row.nation === budget.nation && row.soldiers <= budget.soldiers
    && RESOURCES.every(resource => row.maximumDemand[resource] <= budget.maximumDemand[resource])
    && RESOURCES.every(resource => plan.counts[resource] <= row.maxPurchaseItems),
    'Army demand increased; replan and replenish before creating logistics orders');
}

async function captureDemand({stage, plan, command, until}) {
  const correlation = randomUUID();
  await command(`supplynativeproof demand ${plan.army} ${correlation}`);
  const file = path.join(stage, 'plugins', 'SupplyNativeProof', 'evidence.jsonl');
  const row = await until(() => {
    const text = fs.readFileSync(file, 'utf8');
    return text.slice(0, text.lastIndexOf('\n') + 1).split(/\r?\n/).filter(Boolean)
      .map(line => JSON.parse(line)).find(value => value.correlation === correlation);
  }, 'fresh army demand', 30000);
  return validateDemand(plan, row);
}

function saveDemandPlan(stage, plan) {
  const relative = `folia-proof/logistics-plan-${plan.runId}.json`;
  writeJson(path.join(stage, relative), plan, {exclusive: true});
  return {path: relative, sha256: hash(fs.readFileSync(path.join(stage, relative))), counts: plan.counts,
    budgetedCycles: plan.demandBudget.budgetedCycles};
}

module.exports = {validateDemand, budgetCounts, createDemandPlan, verifyDemandBudget, assertDemandFits, captureDemand, saveDemandPlan};
