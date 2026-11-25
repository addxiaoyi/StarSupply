const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');

function offlinePlayerId(username) {
  const bytes = createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest();
  bytes[6] = (bytes[6] & 0x0f) | 0x30;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = bytes.toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

function confirmSavedPickaxe(player) {
  if (player.playerGameType !== 0) throw new Error('Saved acceptance player is not in survival mode');
  const pickaxes = (player.Inventory ?? []).filter(item => item.id === 'minecraft:iron_pickaxe')
    .reduce((count, item) => count + (item.count ?? item.Count ?? 0), 0);
  if (pickaxes < 1) throw new Error('Iron pickaxe is absent from the saved player inventory; client prediction is not completion');
  return {gameMode: 'survival', ironPickaxeCount: pickaxes, position: player.Pos};
}

async function readSavedPickaxe(stage, levelName, username, mineflayerModule) {
  return readSavedPlayer(stage, levelName, username, mineflayerModule, confirmSavedPickaxe);
}

function confirmSavedInventory(player, required) {
  if (player.playerGameType !== 0) throw new Error('Saved acceptance player is not in survival mode');
  const inventory = {};
  for (const item of player.Inventory ?? []) {
    const name = item.id.replace(/^minecraft:/, '');
    inventory[name] = (inventory[name] ?? 0) + (item.count ?? item.Count ?? 0);
  }
  for (const [name, minimum] of Object.entries(required)) {
    if ((inventory[name] ?? 0) < minimum) {
      throw new Error(`Saved inventory requires ${minimum} ${name}, found ${inventory[name] ?? 0}; client prediction is not completion`);
    }
  }
  return {gameMode: 'survival', inventory, position: player.Pos, health: player.Health, food: player.foodLevel};
}

function confirmSavedResourcePhase(player, phase, productionPlan) {
  if (productionPlan) {
    const {phaseRequirements} = require('./production-targets.cjs');
    const saved = confirmSavedInventory(player, {});
    return {phase, ...confirmSavedInventory(player, phaseRequirements(phase, productionPlan, saved.inventory))};
  }
  const requirements = {
    mining: {iron_ingot: 6, glass_bottle: 3},
    surface: {campfire: 3, stone_sword: 1},
    farming: {bread: 6, sugar_cane: 3},
    honey: {honey_bottle: 3},
    scaffolding: {scaffolding: 6},
    fireworks: {firework_rocket: 3}
  };
  if (!Object.hasOwn(requirements, phase)) throw new Error(`Unknown saved survival phase: ${phase}`);
  return {phase, ...confirmSavedInventory(player, requirements[phase])};
}

async function readSavedResourcePhase(stage, levelName, username, mineflayerModule, phase, productionPlan) {
  return readSavedPlayer(stage, levelName, username, mineflayerModule, player => confirmSavedResourcePhase(player, phase, productionPlan));
}

async function readSavedInventory(stage, levelName, username, mineflayerModule) {
  return readSavedPlayer(stage, levelName, username, mineflayerModule, player => confirmSavedInventory(player, {}));
}

async function readSavedPlayer(stage, levelName, username, mineflayerModule, confirm) {
  const nbt = require(path.join(path.dirname(path.resolve(mineflayerModule)), 'prismarine-nbt'));
  const playerId = offlinePlayerId(username);
  const file = path.join(stage, levelName, 'playerdata', `${playerId}.dat`);
  const bytes = fs.readFileSync(file);
  const data = await nbt.parse(bytes);
  return {...confirm(nbt.simplify(data.parsed)), playerId,
    path: path.relative(stage, file).replace(/\\/g, '/'),
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase()};
}

module.exports = {offlinePlayerId, confirmSavedPickaxe, readSavedPickaxe,
  confirmSavedInventory, confirmSavedResourcePhase, readSavedResourcePhase, readSavedInventory};
