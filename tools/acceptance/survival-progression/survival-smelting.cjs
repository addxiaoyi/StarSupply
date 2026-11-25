const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

function inventoryCount(window, name) {
  return window.slots.slice(window.inventoryStart, window.inventoryEnd)
    .reduce((total, item) => total + (item?.name === name ? item.count : 0), 0);
}

function smeltingState(window, input, output, desired) {
  if (!Number.isSafeInteger(desired) || desired < 1) throw new Error('Invalid smelting target');
  const pending = window.inputItem(), ready = window.outputItem();
  if (pending && pending.name !== input) throw new Error(`Furnace has different input: ${pending.name}`);
  if (ready && ready.name !== output) throw new Error(`Furnace has different output: ${ready.name}`);
  const owned = inventoryCount(window, output);
  const missingInput = Math.max(0, desired - owned - (pending?.count ?? 0) - (ready?.count ?? 0));
  return {owned, missingInput, acquireInput: Math.max(0, missingInput - inventoryCount(window, input))};
}

// Resumes partial furnace contents. A burning fuel item may run out before the
// batch finishes; collect outputs as they arrive and replenish only empty fuel.
async function smeltUntil(bot, window, input, output, desired, options = {}) {
  const {log = () => {}, check = () => {}, timeoutMs = desired * 12000 + 30000, pollMs = 100} = options;
  let timer;
  const expires = Date.now() + timeoutMs;
  const bounded = async action => {
    try {
      return await Promise.race([Promise.resolve().then(action), new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out smelting ${desired} ${output}`)),
          Math.max(0, expires - Date.now()));
      })]);
    } finally { clearTimeout(timer); }
  };
  while (true) {
    check();
    if (bot.currentWindow !== window) throw new Error('Furnace window closed during smelting');
    if (Date.now() >= expires) throw new Error(`Timed out smelting ${desired} ${output}`);
    const state = smeltingState(window, input, output, desired);
    if (state.owned >= desired) return {output, count: state.owned};
    if (window.outputItem()) {
      await bounded(() => window.takeOutput());
      log('smelting_output_taken', {output, count: inventoryCount(window, output)});
      await delay(pollMs);
      continue;
    }
    if (state.acquireInput) throw new Error(`Need ${state.acquireInput} more ${input} to finish ${output}`);
    if (state.missingInput) {
      const count = Math.min(state.missingInput, 64 - (window.inputItem()?.count ?? 0));
      if (count > 0) {
        await bounded(() => window.putInput(bot.registry.itemsByName[input].id, null, count));
        log('smelting_input_added', {input, count});
      }
    }
    if (window.inputItem() && !window.fuelItem() && !(window.fuelSeconds > 0 || window.fuel > 0)) {
      const fuel = ['coal', 'charcoal'].find(name => inventoryCount(window, name) > 0);
      if (!fuel) throw new Error('Furnace needs coal or charcoal; saved inputs can resume after refuelling');
      await bounded(() => window.putFuel(bot.registry.itemsByName[fuel].id, null, 1));
      log('smelting_fuel_added', {fuel, count: 1});
    }
    await delay(pollMs);
  }
}

async function drainSavedFurnace(bot, window, input, output, options = {}) {
  smeltingState(window, input, output, Math.max(1, inventoryCount(window, output)));
  const retained = (window.inputItem()?.count ?? 0) + (window.outputItem()?.count ?? 0);
  if (retained) await smeltUntil(bot, window, input, output, inventoryCount(window, output) + retained, options);
  if (window.inputItem() || window.outputItem()) throw new Error('Saved furnace contents were not fully recovered');
  return retained;
}

module.exports = {inventoryCount, smeltingState, smeltUntil, drainSavedFurnace};
