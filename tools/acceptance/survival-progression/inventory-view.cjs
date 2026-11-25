// Mineflayer copies container player slots back to bot.inventory only when the
// window closes. Never use that stale copy to count a withdrawal or recipe input.
function playerItems(bot) {
  const window = bot.currentWindow;
  if (!window) return bot.inventory.items();
  return window.slots.slice(window.inventoryStart, window.inventoryEnd).filter(Boolean)
    .map(item => ({...item, slot: item.slot - window.inventoryStart + bot.inventory.inventoryStart}));
}

module.exports = {playerItems};
