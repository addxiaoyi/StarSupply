const requireThat = (value, message) => { if (!value) throw new Error(message); };

function counts(list) {
  const result = {};
  for (const row of list ?? []) result[row.name] = (result[row.name] ?? 0) + row.count;
  return result;
}

function checkedInventory(inventory) {
  requireThat(inventory && typeof inventory === 'object' && !Array.isArray(inventory), 'Inventory counts are required');
  for (const [item, count] of Object.entries(inventory)) requireThat(/^[a-z0-9_]+$/.test(item)
    && Number.isSafeInteger(count) && count >= 0, 'Invalid production inventory count');
  return {...inventory};
}

// Quantities are fungible, but unexplained losses always spend traced units first.
// Retained old stacks can never absorb a loss on behalf of the proof.
class ProductionLedger {
  constructor(inventory, traced = {}) {
    this.inventory = checkedInventory(inventory);
    this.traced = checkedInventory(traced);
    for (const [item, count] of Object.entries(this.traced)) requireThat(count <= (this.inventory[item] ?? 0),
      'Traced quantity exceeds physical inventory');
  }

  observe(inventory) {
    inventory = checkedInventory(inventory);
    for (const item of Object.keys(this.traced)) {
      const lost = Math.max(0, (this.inventory[item] ?? 0) - (inventory[item] ?? 0));
      this.traced[item] = Math.min(inventory[item] ?? 0, Math.max(0, this.traced[item] - lost));
    }
    this.inventory = inventory;
    return {...this.traced};
  }

  acquire(action) {
    const before = checkedInventory(action.beforeInventory), after = counts(action.inventory);
    this.observe(before);
    const {item, count, source, inputs = {}} = action;
    requireThat(Number.isSafeInteger(count) && count > 0 && action.before === (before[item] ?? 0)
      && action.after === (after[item] ?? 0) && action.after - action.before === count,
    'Invalid production quantity evidence');
    const direct = ['harvest', 'natural-chest'].includes(source);
    requireThat(direct || ['craft', 'smelt', 'honey'].includes(source), 'Unknown production source');
    requireThat(direct ? Object.keys(inputs).length === 0 : Object.keys(inputs).length > 0,
      'Production source and recipe inputs disagree');
    requireThat(source !== 'honey' || inputs.glass_bottle === count, 'Honey provenance needs the consumed bottles');
    for (const [input, amount] of Object.entries(inputs)) requireThat(input !== item
      && Number.isSafeInteger(amount) && amount > 0 && (before[input] ?? 0) - (after[input] ?? 0) >= amount,
    'Recipe inputs were not consumed from the observed inventory');
    const earned = direct || Object.entries(inputs).every(([input, amount]) => (this.traced[input] ?? 0) >= amount);
    for (const [input, amount] of Object.entries(inputs)) {
      this.traced[input] = Math.max(0, (this.traced[input] ?? 0) - amount);
      this.inventory[input] -= amount;
    }
    this.inventory[item] = (this.inventory[item] ?? 0) + count;
    if (earned) this.traced[item] = (this.traced[item] ?? 0) + count;
    this.observe(after);
    return earned;
  }

  available(item) { return this.traced[item] ?? 0; }
  target(item, required) {
    requireThat(Number.isSafeInteger(required) && required >= 0, 'Invalid traced target');
    return (this.inventory[item] ?? 0) + Math.max(0, required - this.available(item));
  }
}

module.exports = {counts, ProductionLedger};
