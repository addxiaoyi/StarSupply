const DEFAULT_COUNTS = Object.freeze({FOOD: 6, MEDICAL: 3, EQUIPMENT: 6, MATERIALS: 6, ENGINEERING: 6, STRATEGIC: 3});
const PHASES = Object.freeze(['mining', 'surface', 'farming', 'honey', 'scaffolding', 'fireworks']);

function productionCounts(plan) {
  const counts = plan == null ? DEFAULT_COUNTS : plan.counts;
  for (const resource of Object.keys(DEFAULT_COUNTS)) {
    if (!Number.isSafeInteger(counts?.[resource]) || counts[resource] < 1 || counts[resource] > 256) {
      throw new Error(`Invalid ${resource} production target`);
    }
  }
  return {...counts};
}

function productionNeeds(plan, inventory) {
  const counts = productionCounts(plan);
  const have = name => {
    const value = inventory[name] ?? 0;
    if (!Number.isSafeInteger(value) || value < 0) throw new Error(`Invalid saved ${name} count`);
    return value;
  };
  const missing = (name, target) => Math.max(0, target - have(name));
  const rocketBatches = Math.ceil(missing('firework_rocket', counts.STRATEGIC) / 3);
  const paperBatches = Math.ceil(missing('paper', rocketBatches) / 3);
  const scaffoldBatches = Math.ceil(missing('scaffolding', counts.ENGINEERING) / 6);
  const bottles = missing('honey_bottle', counts.MEDICAL);
  return {counts, bread: missing('bread', counts.FOOD), wheat: missing('wheat', missing('bread', counts.FOOD) * 3),
    bottles, bottleBatches: Math.ceil(missing('glass_bottle', bottles) / 3),
    rocketBatches, paperBatches, sugarCane: paperBatches * 3, gunpowder: rocketBatches,
    scaffoldBatches, bamboo: scaffoldBatches * 6, string: scaffoldBatches};
}

function phaseRequirements(phase, plan, inventory) {
  const needs = productionNeeds(plan, inventory), c = needs.counts;
  const requirements = {
    mining: {iron_ingot: c.EQUIPMENT, glass_bottle: needs.bottles, cobblestone: c.MATERIALS},
    surface: {campfire: 3, stone_sword: 1, wooden_hoe: 1},
    farming: {bread: c.FOOD, sugar_cane: needs.sugarCane},
    honey: {honey_bottle: c.MEDICAL},
    scaffolding: {scaffolding: c.ENGINEERING},
    fireworks: {firework_rocket: c.STRATEGIC, cobblestone: c.MATERIALS}
  };
  if (!Object.hasOwn(requirements, phase)) throw new Error(`Unknown saved survival phase: ${phase}`);
  return requirements[phase];
}

// A completed batch must actually change authoritative inventory; a predicted
// recipe or empty natural source cannot silently advance the production target.
async function craftToTarget({output, target, count, craft, ingredients}) {
  if (!Number.isSafeInteger(target) || target < 0 || target > 1024) throw new Error('Invalid craft target');
  while (count(output) < target) {
    const before = count(output);
    await craft(output, ingredients);
    if (count(output) <= before) throw new Error(`Crafting ${output} made no inventory progress`);
  }
}

async function harvestToTarget({target, count, round, wait, now = Date.now, timeoutMs = 1800000}) {
  if (!Number.isSafeInteger(target) || target < 1 || target > 256) throw new Error('Invalid honey target');
  const deadline = now() + timeoutMs;
  while (count() < target) {
    if (now() >= deadline) throw new Error('Natural honey is still growing; resume the saved partial harvest later');
    await round();
    if (count() < target) await wait(Math.min(10000, Math.max(0, deadline - now())));
  }
}

module.exports = {DEFAULT_COUNTS, PHASES, productionCounts, productionNeeds, phaseRequirements, craftToTarget, harvestToTarget};
