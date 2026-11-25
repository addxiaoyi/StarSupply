const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

async function until(read, label, timeoutMs, fail = () => null) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const error = fail();
    if (error) throw error;
    const value = read();
    if (value) return value;
    await delay(50);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function within(promise, label, timeoutMs) {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((resolve, reject) => {
        timer = setTimeout(() => reject(new Error(`Timed out waiting for ${label}`)), timeoutMs);
      })
    ]);
  } finally {
    clearTimeout(timer);
  }
}

async function waitForNearbyChunkColumns(bot, Vec3, radius = 2, timeoutMs = 60000) {
  const position = bot.entity.position;
  const center = new Vec3(Math.floor(position.x / 16) * 16, 0, Math.floor(position.z / 16) * 16);
  const columns = new Map();
  for (let offsetX = -radius; offsetX <= radius; offsetX++) {
    for (let offsetZ = -radius; offsetZ <= radius; offsetZ++) {
      const column = center.offset(offsetX * 16, 0, offsetZ * 16);
      columns.set(column.toString(), column);
    }
  }
  if ([...columns.values()].every(column => bot.world.getColumnAt(column))) return;

  await new Promise((resolve, reject) => {
    const pending = new Set(columns.keys());
    const timeout = setTimeout(() => {
      bot.world.off('chunkColumnLoad', onLoad);
      reject(new Error(`Timed out waiting for ${pending.size} nearby chunk columns after ${timeoutMs}ms`));
    }, timeoutMs);
    function onLoad(column) {
      pending.delete(column.toString());
      if (pending.size === 0) {
        clearTimeout(timeout);
        bot.world.off('chunkColumnLoad', onLoad);
        resolve();
      }
    }
    bot.world.on('chunkColumnLoad', onLoad);
    for (const [key, column] of columns) {
      if (bot.world.getColumnAt(column)) pending.delete(key);
    }
    if (pending.size === 0) {
      clearTimeout(timeout);
      bot.world.off('chunkColumnLoad', onLoad);
      resolve();
    }
  });
}

async function placeBlockEventually(bot, referenceBlock, face, blockName, label, log) {
  const target = referenceBlock.position.offset(face.x, face.y, face.z);
  const waitForPlacement = () => until(() => {
    const block = bot.blockAt(target);
    if (block?.name === blockName) return block;
    if (block && block.name !== 'air') {
      throw new Error(`Unexpected ${block.name} at ${target} while waiting for ${blockName}`);
    }
  }, `server-confirmed ${label}`, 5000);
  try {
    await within(bot.placeBlock(referenceBlock, face), label, 10000);
  } catch (error) {
    if (!/Server refused to place .*: the block is still air|Server did not answer the placement at |Event blockUpdate:.*did not fire|No block has been placed/.test(error.message)) {
      throw error;
    }
    try {
      const placed = await waitForPlacement();
      log('placement_confirmed_after_client_place_error', {
        block: blockName,
        target,
        support: referenceBlock.position,
        clientError: error.message,
        playerPosition: bot.entity.position.clone()
      });
      return placed;
    } catch {
      throw error;
    }
  }
  return waitForPlacement();
}

function listenerOpen(host, port) {
  return new Promise(resolve => {
    const socket = net.createConnection({host, port});
    socket.setTimeout(1000);
    socket.once('connect', () => {
      socket.destroy();
      resolve(true);
    });
    socket.once('timeout', () => {
      socket.destroy();
      resolve(false);
    });
    socket.once('error', () => resolve(false));
  });
}

function inventory(bot) {
  return bot.inventory.items().map(item => ({
    name: item.name,
    count: item.count,
    slot: item.slot
  }));
}

function countItems(bot, name) {
  return bot.inventory.items().filter(item => item.name === name)
    .reduce((total, item) => total + item.count, 0);
}

async function craftIronPickaxeConfirmed(bot, workbench, log, mineflayerModule) {
  const Item = require(path.join(path.dirname(path.resolve(mineflayerModule)), 'prismarine-item'))(bot.registry);
  const window = await within(bot.openBlock(workbench), 'open the survival crafting table', 10000);
  let outputConfirmed = false;
  let inventoryConfirmed = false;
  const onSlot = packet => {
    const item = Item.fromNotch(packet.item);
    if (item?.name !== 'iron_pickaxe') return;
    if (packet.windowId === window.id && packet.slot === 0) outputConfirmed = true;
    if ((packet.windowId === window.id && packet.slot >= window.inventoryStart)
        || (packet.windowId === 0 && packet.slot >= bot.inventory.inventoryStart)) inventoryConfirmed = true;
  };
  const onItems = packet => {
    if (packet.windowId === window.id && Item.fromNotch(packet.items[0])?.name === 'iron_pickaxe') outputConfirmed = true;
    const start = packet.windowId === window.id ? window.inventoryStart
      : packet.windowId === 0 ? bot.inventory.inventoryStart : null;
    if (start !== null && packet.items.slice(start).some(item => Item.fromNotch(item)?.name === 'iron_pickaxe')) {
      inventoryConfirmed = true;
    }
  };
  bot._client.on('set_slot', onSlot);
  bot._client.on('window_items', onItems);
  try {
    if (!window.type.includes('crafting')) throw new Error(`Unexpected crafting window: ${window.type}`);
    for (const [name, slots] of [['iron_ingot', [1, 2, 3]], ['stick', [5, 8]]]) {
      const item = window.slots.slice(window.inventoryStart, window.inventoryEnd)
        .find(item => item?.name === name && item.count >= slots.length);
      if (!item) throw new Error(`Missing ${name} for the server-confirmed pickaxe recipe`);
      const source = item.slot;
      await within(bot.clickWindow(source, 0, 0), `pick up ${name}`, 5000);
      await delay(200);
      for (const slot of slots) {
        await within(bot.clickWindow(slot, 1, 0), `place ${name} in recipe slot ${slot}`, 5000);
        await delay(200);
      }
      if (window.selectedItem) {
        await within(bot.clickWindow(source, 0, 0), `return unused ${name}`, 5000);
        await delay(200);
      }
    }
    await until(() => outputConfirmed && window.slots[0]?.name === 'iron_pickaxe',
      'server-reported iron pickaxe recipe output', 10000);
    log('iron_pickaxe_recipe_confirmed', {windowId: window.id, workbench: workbench.position});
    await within(bot.clickWindow(0, 0, 0), 'take the server-reported iron pickaxe', 5000);
    await delay(200);
    const destination = window.slots.findIndex((item, slot) => slot >= window.inventoryStart
      && slot < window.inventoryEnd && !item);
    if (destination < 0) throw new Error('No inventory slot is available for the crafted pickaxe');
    await within(bot.clickWindow(destination, 0, 0), 'put the crafted pickaxe in player inventory', 5000);
    await until(() => inventoryConfirmed, 'server-confirmed pickaxe in a player inventory slot', 10000);
    log('iron_pickaxe_inventory_confirmed', {windowId: window.id, slot: destination});
  } finally {
    bot._client.off('set_slot', onSlot);
    bot._client.off('window_items', onItems);
    if (bot.currentWindow === window) bot.closeWindow(window);
  }
  await delay(1000);
  if (countItems(bot, 'iron_pickaxe') < 1) throw new Error('Server removed the pickaxe after closing the crafting table');
}

async function craftStonePickaxe(
  bot,
  pathfinderModule,
  Vec3,
  workbenchPosition,
  surfaceRoutePosition,
  returnToMiningPosition,
  log
) {
  if (countItems(bot, 'stone_pickaxe') > 0) {
    const nearbyWorkbench = bot.findBlocks({
      matching: block => block.name === 'crafting_table',
      maxDistance: 32,
      count: 20
    }).sort((first, second) => first.distanceTo(bot.entity.position)
      - second.distanceTo(bot.entity.position))[0];
    return nearbyWorkbench ?? workbenchPosition;
  }

  const existingWorkbench = bot.blockAt(workbenchPosition);
  if (bot.entity.position.y < surfaceRoutePosition.y - 8
      || !existingWorkbench || !bot.canSeeBlock(existingWorkbench)) {
    const hasNearbyBirchLogs = bot.findBlocks({
      matching: block => block.name === 'birch_log',
      maxDistance: 16,
      count: 100
    }).length >= 2;
    if (findTreeSite(bot) || countItems(bot, 'birch_sapling') > 0 || hasNearbyBirchLogs) {
      return craftStonePickaxeLocally(bot, pathfinderModule, Vec3, log);
    }
    log('local_tree_site_unavailable', {
      playerPosition: bot.entity.position,
      fallback: 'existing_surface_crafting_table'
    });
  }

  if (countItems(bot, 'stick') < 2) {
    const stickRecipe = bot.recipesFor(bot.registry.itemsByName.stick.id, null, 1)[0];
    if (!stickRecipe) throw new Error('No personal-inventory recipe is available for pickaxe sticks');
    await within(bot.craft(stickRecipe, 1), 'craft stone-pickaxe sticks', 10000);
  }

  const workbench = bot.blockAt(workbenchPosition);
  if (workbench?.name !== 'crafting_table') {
    throw new Error(`Previously used crafting table not found at ${workbenchPosition}`);
  }

  const previousSearchRadius = bot.pathfinder.searchRadius;
  const previousThinkTimeout = bot.pathfinder.thinkTimeout;
  bot.pathfinder.searchRadius = -1;
  bot.pathfinder.thinkTimeout = 60000;
  try {
    log('stone_pickaxe_workbench_route_started', {
      playerPosition: bot.entity.position,
      workbench: workbenchPosition,
      surfaceRoutePosition,
      returnToMiningPosition
    });

    if (!bot.canSeeBlock(workbench)) {
      await within(
        bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
          Math.floor(surfaceRoutePosition.x),
          Math.floor(surfaceRoutePosition.y),
          Math.floor(surfaceRoutePosition.z),
          3)),
        'reach the existing surface passage',
        90000);
      bot.pathfinder.setGoal(null);
      log('stone_pickaxe_surface_passage_reached', {
        playerPosition: bot.entity.position,
        workbench: workbench.position
      });

      await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
        Math.floor(workbenchPosition.x),
        Math.floor(workbenchPosition.y),
        Math.floor(workbenchPosition.z),
        7)), 'reach the area around the existing crafting table', 90000);
      bot.pathfinder.setGoal(null);
      log('stone_pickaxe_workbench_approach_reached', {
        playerPosition: bot.entity.position,
        workbench: workbench.position
      });

      if (!bot.canSeeBlock(workbench)) {
        const goal = new pathfinderModule.goals.GoalLookAtBlock(workbenchPosition, bot.world, {
          reach: 3.5,
          entityHeight: bot.entity.eyeHeight
        });
        await within(bot.pathfinder.goto(goal), 'reach the existing crafting table for a stone pickaxe', 90000);
        bot.pathfinder.setGoal(null);
      }
    }

    const reachableWorkbench = bot.blockAt(workbenchPosition);
    if (!reachableWorkbench || !bot.canSeeBlock(reachableWorkbench)) {
      throw new Error('Could not reach the existing crafting table to make a stone pickaxe');
    }

    const stonePickaxe = bot.registry.itemsByName.stone_pickaxe;
    const recipe = bot.recipesFor(stonePickaxe.id, null, 1, reachableWorkbench)[0];
    if (!recipe) throw new Error('No survival recipe is available for a stone pickaxe');
    const inventoryBeforeCraft = inventory(bot);
    await within(bot.craft(recipe, 1, reachableWorkbench), 'craft a stone pickaxe', 15000);
    if (countItems(bot, 'stone_pickaxe') < 1) throw new Error('Stone pickaxe was not added to the inventory');
    log('stone_pickaxe_crafted', {
      workbench: workbenchPosition,
      recipeIngredients: ['cobblestone x3', 'stick x2'],
      inventoryBeforeCraft,
      inventoryAfterCraft: inventory(bot),
      playerPosition: bot.entity.position
    });

    await within(
      bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
        Math.floor(returnToMiningPosition.x),
        Math.floor(returnToMiningPosition.y),
        Math.floor(returnToMiningPosition.z),
        1)),
      'return to the mining area after crafting a stone pickaxe',
      90000);
    return workbenchPosition;
  } finally {
    bot.pathfinder.setGoal(null);
    bot.pathfinder.searchRadius = previousSearchRadius;
    bot.pathfinder.thinkTimeout = previousThinkTimeout;
  }
}

function findTreeSite(bot) {
  const soilPositions = bot.findBlocks({
    matching: block => ['dirt', 'grass_block', 'podzol', 'coarse_dirt'].includes(block.name),
    maxDistance: 24,
    count: 300
  });
  const candidates = soilPositions
    .map(position => bot.blockAt(position))
    .filter(block => block && bot.blockAt(block.position.offset(0, 1, 0))?.name === 'air')
    .sort((first, second) => first.position.distanceTo(bot.entity.position)
      - second.position.distanceTo(bot.entity.position));

  for (const soil of candidates) {
    let clear = true;
    for (let height = 1; height <= 6 && clear; height++) {
      const radius = height <= 2 ? 0 : 2;
      for (let offsetX = -radius; offsetX <= radius && clear; offsetX++) {
        for (let offsetZ = -radius; offsetZ <= radius; offsetZ++) {
          const block = bot.blockAt(soil.position.offset(offsetX, height, offsetZ));
          if (!block || block.boundingBox !== 'empty') {
            clear = false;
            break;
          }
        }
      }
    }
    if (clear) {
      return {
        soilPosition: soil.position.clone(),
        saplingPosition: soil.position.offset(0, 1, 0)
      };
    }
  }
  return null;
}

function placementIntersectsPlayer(bot, position) {
  const playerPosition = bot.entity.position;
  const playerHalfWidth = 0.3;
  const playerHeight = bot.entity.height ?? 1.8;
  return playerPosition.x + playerHalfWidth > position.x
    && playerPosition.x - playerHalfWidth < position.x + 1
    && playerPosition.y + playerHeight > position.y
    && playerPosition.y < position.y + 1
    && playerPosition.z + playerHalfWidth > position.z
    && playerPosition.z - playerHalfWidth < position.z + 1;
}

function placementFaceVisible(bot, block, face) {
  const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0);
  const point = block.position.offset(0.5 + face.x * 0.499,
    0.5 + face.y * 0.499, 0.5 + face.z * 0.499);
  const direction = point.minus(eye);
  const distance = direction.norm();
  if (distance > 4.5 || distance === 0) return false;
  const hit = bot.world.raycast(eye, direction.normalize(), distance + 0.01);
  return Boolean(hit && hit.position.equals(block.position));
}

function findPlacementSupport(
  bot,
  origin,
  minimumDistance = 0,
  excludedPositions = [],
  supportY = null,
  maxDistance = 5
) {
  return bot.findBlocks({
    matching: block => block.boundingBox === 'block',
    maxDistance,
    count: 300
  }).map(position => bot.blockAt(position))
    .filter(block => block
      && block.position.distanceTo(origin) >= minimumDistance
      && (supportY === null || block.position.y === supportY)
      && !excludedPositions.some(position => block.position.equals(position))
      && bot.blockAt(block.position.offset(0, 1, 0))?.name === 'air'
      && !placementIntersectsPlayer(bot, block.position.offset(0, 1, 0))
      && placementFaceVisible(bot, block, new (origin.constructor)(0, 1, 0)))
    .sort((first, second) => first.position.distanceTo(bot.entity.position)
      - second.position.distanceTo(bot.entity.position))[0] ?? null;
}

function findAdjacentPlacement(bot, origin, excludedPositions = [], maxDistance = 5) {
  const faces = [
    new (origin.constructor)(0, 1, 0),
    new (origin.constructor)(0, 0, -1),
    new (origin.constructor)(0, 0, 1),
    new (origin.constructor)(-1, 0, 0),
    new (origin.constructor)(1, 0, 0)
  ];
  const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0);
  const feetY = Math.floor(bot.entity.position.y);
  return bot.findBlocks({
    matching: block => block.boundingBox === 'block',
    maxDistance,
    count: 300
  }).map(position => bot.blockAt(position))
    .filter(block => block
      && !excludedPositions.some(position => block.position.equals(position)))
    .flatMap(block => faces.map(face => ({
      block,
      face,
      target: block.position.offset(face.x, face.y, face.z)
    })))
    .filter(candidate => {
      const targetBlock = bot.blockAt(candidate.target);
      const reach = eye.distanceTo(candidate.target.offset(0.5, 0.5, 0.5));
      return targetBlock?.name === 'air'
        && candidate.target.y <= feetY + 1
        && !placementIntersectsPlayer(bot, candidate.target)
        && reach <= 4.5
        && placementFaceVisible(bot, candidate.block, candidate.face);
    })
    .sort((first, second) => eye.distanceTo(first.target.offset(0.5, 0.5, 0.5))
      - eye.distanceTo(second.target.offset(0.5, 0.5, 0.5)))[0] ?? null;
}

async function openFurnaceAlcove(bot, Vec3, log) {
  const feet = bot.entity.position.floored();
  const safeStone = new Set(['stone', 'andesite', 'diorite', 'granite', 'deepslate', 'tuff', 'cobblestone']);
  const falling = new Set(['sand', 'red_sand', 'gravel', 'suspicious_sand', 'suspicious_gravel']);
  for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
    const target = feet.offset(dx, 0, dz);
    const support = bot.blockAt(target.offset(0, -1, 0));
    const cells = [target.offset(0, 1, 0), target];
    if (placementIntersectsPlayer(bot, target) || support?.boundingBox !== 'block'
        || cells.some(position => {
          const block = bot.blockAt(position);
          return !block || (block.name !== 'air' && !safeStone.has(block.name));
        }) || falling.has(bot.blockAt(target.offset(0, 2, 0))?.name)) continue;
    const fluidNearby = cells.some(position => [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, 0, 1], [0, 0, -1]]
      .some(([x, y, z]) => ['water', 'lava'].includes(bot.blockAt(position.offset(x, y, z))?.name)));
    if (fluidNearby) continue;
    const pickaxe = bot.inventory.items().find(item => item.name === 'stone_pickaxe');
    if (!pickaxe) throw new Error('A stone pickaxe is required to open a furnace alcove');
    await within(bot.equip(pickaxe, 'hand'), 'equip the stone pickaxe for a furnace alcove', 5000);
    log('furnace_alcove_started', {target, support: support.position, playerPosition: bot.entity.position.clone()});
    for (const position of cells) {
      const block = bot.blockAt(position);
      if (block?.name === 'air') continue;
      if (!block || !bot.canDigBlock(block) || !hasDirectRay(bot, block)) break;
      let serverConfirmed = false;
      const onChange = packet => {
        if (packet.location.x === position.x && packet.location.y === position.y
            && packet.location.z === position.z && packet.type === 0) serverConfirmed = true;
      };
      bot._client.on('block_change', onChange);
      try {
        await within(bot.dig(block, true, 'raycast'), `mine the furnace alcove at ${position}`, 10000);
        await until(() => serverConfirmed && bot.blockAt(position)?.name === 'air',
          `server-confirmed furnace alcove at ${position}`, 5000);
      } finally {
        bot._client.off('block_change', onChange);
      }
      log('furnace_alcove_block_mined', {block: block.name, position, serverConfirmed});
    }
    const placement = findAdjacentPlacement(bot, feet, [], 5);
    if (placement) {
      log('furnace_alcove_ready', {target: placement.target, support: placement.block.position, face: placement.face});
      return placement;
    }
  }
  return null;
}

async function moveToFurnaceWorksite(bot, pathfinderModule, log) {
  const feetY = Math.floor(bot.entity.position.y);
  const standingPositions = bot.findBlocks({
    matching: block => block.boundingBox === 'block',
    maxDistance: 12,
    count: 500
  }).map(position => bot.blockAt(position))
    .filter(block => block && block.position.y === feetY - 1
      && bot.blockAt(block.position.offset(0, 1, 0))?.boundingBox === 'empty'
      && bot.blockAt(block.position.offset(0, 2, 0))?.boundingBox === 'empty'
      && bot.canSeeBlock(block))
    .map(block => block.position.offset(0, 1, 0))
    .filter(position => Math.hypot(
      position.x + 0.5 - bot.entity.position.x,
      position.z + 0.5 - bot.entity.position.z) >= 0.8)
    .sort((first, second) => first.distanceTo(bot.entity.position)
      - second.distanceTo(bot.entity.position));
  if (standingPositions.length === 0) return false;

  const movements = bot.pathfinder.movements;
  const previousCanDig = movements.canDig;
  movements.canDig = false;
  try {
    for (const position of standingPositions.slice(0, 16)) {
      log('furnace_worksite_reposition_started', {
        target: position,
        playerPosition: bot.entity.position.clone()
      });
      try {
        await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
          position.x, position.y, position.z, 0)),
        `reach a clear furnace placement tile at ${position}`, 12000);
        bot.pathfinder.setGoal(null);
        if (bot.entity.onGround
            && bot.blockAt(position.offset(0, -1, 0))?.boundingBox === 'block'
            && bot.blockAt(position)?.boundingBox === 'empty'
            && bot.blockAt(position.offset(0, 1, 0))?.boundingBox === 'empty') {
          log('furnace_worksite_repositioned', {
            target: position,
            playerPosition: bot.entity.position.clone()
          });
          return true;
        }
      } catch (error) {
        bot.pathfinder.setGoal(null);
        log('furnace_worksite_reposition_failed', {
          target: position,
          playerPosition: bot.entity.position.clone(),
          reason: error.message
        });
      }
    }
    return false;
  } finally {
    movements.canDig = previousCanDig;
    bot.pathfinder.setGoal(null);
  }
}

async function returnToFurnaceSurfaceSite(bot, pathfinderModule, Vec3, log) {
  const target = new Vec3(-8, 75, 58);
  const movements = bot.pathfinder.movements;
  const previousCanDig = movements.canDig;
  movements.canDig = false;
  try {
    log('furnace_surface_route_started', {
      target,
      playerPosition: bot.entity.position.clone(),
      terrainModification: false
    });
    await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
      target.x, target.y, target.z, 3)),
    'return to the previously verified surface worksite without digging', 60000);
    bot.pathfinder.setGoal(null);
    log('furnace_surface_route_reached', {
      target,
      playerPosition: bot.entity.position.clone(),
      health: bot.health,
      food: bot.food
    });
    return true;
  } catch (error) {
    bot.pathfinder.setGoal(null);
    log('furnace_surface_route_failed', {
      target,
      playerPosition: bot.entity.position.clone(),
      reason: error.message
    });
    return false;
  } finally {
    movements.canDig = previousCanDig;
    bot.pathfinder.setGoal(null);
  }
}

async function craftStonePickaxeLocally(bot, pathfinderModule, Vec3, log) {
  bot.pathfinder.searchRadius = -1;
  bot.pathfinder.thinkTimeout = 60000;
  for (let slot = 1; slot <= 4; slot++) {
    const item = bot.inventory.slots[slot];
    if (!item) continue;
    const emptySlot = bot.inventory.slots.findIndex((entry, index) => index >= 9 && index < 36 && !entry);
    if (emptySlot < 0) throw new Error('No inventory slot is available to clear the personal crafting grid');
    await within(bot.clickWindow(slot, 0, 0), `pick up personal crafting-grid item from slot ${slot}`, 5000);
    await delay(100);
    await within(bot.clickWindow(emptySlot, 0, 0), `return crafting-grid item to inventory slot ${emptySlot}`, 5000);
    await delay(100);
  }
  const plantedSaplingPosition = bot.findBlocks({
    matching: block => block.name === 'birch_sapling',
    maxDistance: 24,
    count: 20
  }).sort((first, second) => first.distanceTo(bot.entity.position)
    - second.distanceTo(bot.entity.position))[0] ?? null;
  const existingBirchLogs = bot.findBlocks({
    matching: block => block.name === 'birch_log',
    maxDistance: 16,
    count: 100
  });
  const canUseExistingLogs = existingBirchLogs.length >= 2;
  const canGrowTree = Boolean(plantedSaplingPosition) || countItems(bot, 'birch_sapling') > 0;
  const availableBirchPlanks = countItems(bot, 'birch_planks') + countItems(bot, 'birch_log') * 4;
  const hasEnoughWood = availableBirchPlanks >= 6;
  const growTree = !hasEnoughWood && canGrowTree && !canUseExistingLogs;
  const treeSite = growTree
    ? (plantedSaplingPosition
        ? {
            soilPosition: plantedSaplingPosition.offset(0, -1, 0),
            saplingPosition: plantedSaplingPosition
          }
        : findTreeSite(bot))
    : null;
  const support = !growTree && (canUseExistingLogs || hasEnoughWood)
    ? findPlacementSupport(bot, bot.entity.position, 0)
    : null;
  let site = treeSite ?? (support ? {
    soilPosition: support.position.clone(),
    saplingPosition: support.position.offset(0, 1, 0)
  } : null);
  if (!site) throw new Error('No survival crafting surface or tree-growing space is available near the player');
  if (!growTree && !canUseExistingLogs && !hasEnoughWood) {
    throw new Error('A sapling with bone meal or two nearby birch logs is required');
  }
  if (growTree && countItems(bot, 'bone_meal') < 1) {
    throw new Error('Bone meal is required to grow the birch tree');
  }
  if (growTree && countItems(bot, 'torch') < 1 && countItems(bot, 'coal') < 1) {
    throw new Error('Coal is required to craft a torch for the tree-growing space');
  }

  if (growTree && Math.abs(bot.entity.position.y - site.soilPosition.y) > 2) {
    const exitStair = bot.findBlocks({
      matching: block => block.name === 'cobblestone',
      maxDistance: 24,
      count: 300
    }).map(position => bot.blockAt(position))
      .filter(block => block && block.position.y >= Math.floor(bot.entity.position.y)
        && block.position.y < 82
        && bot.blockAt(block.position.offset(0, 1, 0))?.boundingBox === 'empty')
      .sort((first, second) => second.position.y - first.position.y
        || first.position.distanceTo(bot.entity.position) - second.position.distanceTo(bot.entity.position))[0];
    const exitTarget = exitStair
      ? exitStair.position.offset(0, 1, 0)
      : new Vec3(-8, 82, 56);
    const caveExitGoal = new pathfinderModule.goals.GoalBlock(
      Math.floor(exitTarget.x), Math.floor(exitTarget.y), Math.floor(exitTarget.z));
    log('stone_pickaxe_local_tree_exit_route_started', {
      target: exitTarget,
      playerPosition: bot.entity.position.clone()
    });
    await within(bot.pathfinder.goto(caveExitGoal), 'return to the top of the survival staircase', 90000);
    bot.pathfinder.setGoal(null);
    if (Math.floor(bot.entity.position.y) !== Math.floor(exitTarget.y)) {
      throw new Error(`Reached the staircase at y=${bot.entity.position.y}, expected y=${exitTarget.y}`);
    }
    log('stone_pickaxe_local_tree_exit_reached', {playerPosition: bot.entity.position.clone()});
  }

  if (growTree) {
    const soilGoal = new pathfinderModule.goals.GoalLookAtBlock(site.soilPosition, bot.world, {
      reach: 3.5,
      entityHeight: bot.entity.eyeHeight
    });
    log('stone_pickaxe_local_tree_route_started', {
      site: site.soilPosition,
      playerPosition: bot.entity.position.clone()
    });
    await within(bot.pathfinder.goto(soilGoal), 'reach the nearby birch growing space', 45000);
    bot.pathfinder.setGoal(null);
    log('stone_pickaxe_local_tree_site_reached', {
      site: site.soilPosition,
      playerPosition: bot.entity.position.clone()
    });
  } else {
    log('stone_pickaxe_existing_logs_route_ready', {
      workbenchSupport: site.soilPosition,
      playerPosition: bot.entity.position.clone()
    });
  }
  if (growTree) {
    const soil = bot.blockAt(site.soilPosition);
    const saplingAlreadyPlanted = bot.blockAt(site.saplingPosition)?.name === 'birch_sapling';
    if (!soil || (!saplingAlreadyPlanted && bot.blockAt(site.saplingPosition)?.name !== 'air')) {
      throw new Error(`The birch growing space changed before planting at ${site.saplingPosition}`);
    }

    if (countItems(bot, 'torch') < 1) {
      const torchRecipe = bot.recipesFor(bot.registry.itemsByName.torch.id, null, 1)[0];
      if (!torchRecipe) throw new Error('No personal-inventory recipe is available for torches');
      await within(bot.craft(torchRecipe, 1), 'craft torches for tree growth', 10000);
    }

    const existingTorches = bot.findBlocks({
      matching: block => block.name === 'torch',
      maxDistance: 10,
      count: 20
    }).sort((first, second) => first.distanceTo(site.saplingPosition)
      - second.distanceTo(site.saplingPosition));
    const torchSupport = existingTorches.length > 0
      ? null
      : findPlacementSupport(bot, site.saplingPosition, 1);
    if (existingTorches.length === 0 && !torchSupport) {
      throw new Error('No nearby floor space is available for a torch');
    }
    if (!saplingAlreadyPlanted) {
      const saplingItem = bot.inventory.items().find(item => item.name === 'birch_sapling');
      await within(bot.equip(saplingItem, 'hand'), 'equip the existing birch sapling', 5000);
      await placeBlockEventually(bot, soil, new Vec3(0, 1, 0), 'birch_sapling',
        'plant a birch sapling in the growing space', log);
      await until(() => bot.blockAt(site.saplingPosition)?.name === 'birch_sapling',
        'planted birch sapling', 5000);
    }

    let torchPosition = existingTorches[0] ?? null;
    if (torchSupport) {
      const torchItem = bot.inventory.items().find(item => item.name === 'torch');
      await within(bot.equip(torchItem, 'hand'), 'equip a torch', 5000);
      await placeBlockEventually(bot, torchSupport, new Vec3(0, 1, 0), 'torch',
        'light the tree-growing space', log);
      torchPosition = torchSupport.position.offset(0, 1, 0);
    }
    log('stone_pickaxe_local_tree_ready', {
      soil: site.soilPosition,
      sapling: site.saplingPosition,
      reusedSapling: saplingAlreadyPlanted,
      torch: torchPosition,
      playerPosition: bot.entity.position
    });

    const boneMeal = bot.inventory.items().find(item => item.name === 'bone_meal');
    await within(bot.equip(boneMeal, 'hand'), 'equip bone meal', 5000);
    for (let attempt = 0; attempt < 16; attempt++) {
      const plantedTreeLogs = bot.findBlocks({
        matching: block => block.name === 'birch_log',
        maxDistance: 8,
        count: 20
      }).filter(position => position.distanceTo(site.saplingPosition) <= 4);
      if (plantedTreeLogs.length >= 2) break;
      const sapling = bot.blockAt(site.saplingPosition);
      if (sapling?.name !== 'birch_sapling') {
        throw new Error('The birch sapling disappeared before producing a tree');
      }
      await within(bot.activateBlock(sapling), `apply bone meal to the birch sapling ${attempt + 1}`, 5000);
      await delay(250);
      log('stone_pickaxe_local_tree_growth_attempt', {
        attempt: attempt + 1,
        plantedTreeLogs: plantedTreeLogs.length,
        boneMealRemaining: countItems(bot, 'bone_meal'),
        sapling: site.saplingPosition
      });
    }
    const grownBirchLogs = bot.findBlocks({
      matching: block => block.name === 'birch_log',
      maxDistance: 8,
      count: 20
    }).filter(position => position.distanceTo(site.saplingPosition) <= 4);
    if (grownBirchLogs.length < 2) {
      throw new Error(`The birch sapling produced ${grownBirchLogs.length} reachable trunk logs`);
    }
  }

  let collectedLogs = 0;
  const maximumLogY = Math.floor(bot.entity.position.y) + 3;
  for (let attempt = 0; attempt < 8; attempt++) {
    const availableBirchPlanks = countItems(bot, 'birch_planks') + countItems(bot, 'birch_log') * 4;
    if (availableBirchPlanks >= 6) break;
    const logs = bot.findBlocks({
      matching: block => block.name === 'birch_log',
      maxDistance: 12,
      count: 40
    }).map(position => bot.blockAt(position))
      .filter(block => block && block.diggable && block.position.y <= maximumLogY)
      .sort((first, second) => first.position.distanceTo(bot.entity.position)
        - second.position.distanceTo(bot.entity.position));
    let logBlock = null;
    for (const candidate of logs.slice(0, 3)) {
      if (hasDirectRay(bot, candidate)) {
        logBlock = candidate;
        break;
      }
      try {
        log('birch_log_approach_started', {
          position: candidate.position,
          playerPosition: bot.entity.position.clone()
        });
        await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
          candidate.position.x,
          candidate.position.y,
          candidate.position.z,
          2)), `approach birch log at ${candidate.position}`, 20000);
        bot.pathfinder.setGoal(null);
        let approachedBlock = bot.blockAt(candidate.position);
        if (approachedBlock?.name === 'birch_log' && bot.canDigBlock(approachedBlock)) {
          if (!hasDirectRay(bot, approachedBlock)) {
            const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0);
            const center = approachedBlock.position.offset(0.5, 0.5, 0.5);
            const direction = center.minus(eye);
            const distance = direction.norm();
            const hit = bot.world.raycast(eye, direction.normalize(), Math.min(distance + 0.1, 4.5));
            const obstruction = hit ? bot.blockAt(hit.position) : null;
            if (obstruction?.name === 'birch_log' && bot.canDigBlock(obstruction)) {
              approachedBlock = obstruction;
            } else {
              await exposeBlockToRay(bot, approachedBlock, ['birch_log'], log, 'birch_log');
              approachedBlock = bot.blockAt(candidate.position);
            }
          }
          if (approachedBlock?.name === 'birch_log' && hasDirectRay(bot, approachedBlock)) {
            logBlock = approachedBlock;
            log('birch_log_approach_reached', {
              position: approachedBlock.position.clone(),
              playerPosition: bot.entity.position.clone()
            });
            break;
          }
          log('birch_log_approach_incomplete', {
            position: candidate.position,
            block: approachedBlock?.name ?? null,
            canDig: Boolean(approachedBlock && bot.canDigBlock(approachedBlock)),
            hasDirectRay: Boolean(approachedBlock && hasDirectRay(bot, approachedBlock)),
            playerPosition: bot.entity.position.clone()
          });
        }
      } catch (error) {
        bot.pathfinder.setGoal(null);
        log('birch_log_approach_failed', {
          position: candidate.position,
          playerPosition: bot.entity.position.clone(),
          reason: error.message
        });
      }
    }
    if (!logBlock) break;

    const position = logBlock.position.clone();
    const logsBefore = countItems(bot, 'birch_log');
    const birchDropsBefore = new Map(Object.values(bot.entities)
      .filter(entity => entity.name === 'item' && entity.getDroppedItem()?.name === 'birch_log')
      .map(entity => [entity.id, entity.getDroppedItem().count ?? 1]));
    await within(bot.unequip('hand'), 'clear the hand before harvesting birch logs', 5000);
    await within(bot.dig(logBlock, true, 'raycast'), `harvest birch log at ${position}`, 15000);
    const logResult = await until(() => {
      const total = countItems(bot, 'birch_log');
      if (total > logsBefore) return {total};
      const drop = Object.values(bot.entities).find(entity => {
        if (entity.name !== 'item') return false;
        const droppedItem = entity.getDroppedItem();
        const previousCount = birchDropsBefore.get(entity.id);
        return droppedItem?.name === 'birch_log'
          && entity.position.distanceTo(position.offset(0.5, 0.5, 0.5)) <= 2.5
          && (previousCount === undefined || droppedItem.count > previousCount);
      });
      if (drop) return {drop};
    }, `find the harvested birch log drop at ${position}`, 7000);
    const needsPickupRoute = Boolean(logResult.drop && countItems(bot, 'birch_log') <= logsBefore);
    if (logResult.drop) {
      log('birch_log_drop_observed', {
        harvestedPosition: position,
        dropEntityId: logResult.drop.id,
        dropPosition: logResult.drop.position.clone(),
        playerPosition: bot.entity.position.clone()
      });
    }
    if (needsPickupRoute) {
      log('birch_log_pickup_route_started', {
        dropPosition: logResult.drop.position.clone(),
        playerPosition: bot.entity.position.clone()
      });
      bot.pathfinder.setGoal(new pathfinderModule.goals.GoalFollow(logResult.drop, 1), true);
    }
    let confirmedLogs;
    if (needsPickupRoute) {
      try {
        confirmedLogs = await until(() => {
          const total = countItems(bot, 'birch_log');
          if (total > logsBefore) return total;
        }, `confirm birch log in inventory at ${position}`, 8000);
      } catch (error) {
        bot.pathfinder.setGoal(null);
        const dropPosition = logResult.drop.position.floored();
        const pickupFeetY = Math.floor(bot.entity.position.y);
        log('birch_log_pickup_fallback_started', {
          dropPosition,
          pickupFeetY,
          playerPosition: bot.entity.position.clone(),
          reason: error.message
        });
        const pickupGoal = new pathfinderModule.goals.GoalNear(
          dropPosition.x, pickupFeetY, dropPosition.z, 1);
        await within(bot.pathfinder.goto(pickupGoal), 'approach the dropped birch log on foot', 20000);
        bot.pathfinder.setGoal(null);
        confirmedLogs = await until(() => {
          const total = countItems(bot, 'birch_log');
          if (total > logsBefore) return total;
        }, `confirm birch log pickup at ${position}`, 10000);
      }
    } else {
      confirmedLogs = await until(() => {
        const total = countItems(bot, 'birch_log');
        if (total > logsBefore) return total;
      }, `confirm birch log in inventory at ${position}`, 5000);
    }
    collectedLogs += confirmedLogs - logsBefore;
  }
  const availableBirchPlanksAfterHarvest = countItems(bot, 'birch_planks') + countItems(bot, 'birch_log') * 4;
  if (availableBirchPlanksAfterHarvest < 6) {
    throw new Error(`Collected ${availableBirchPlanksAfterHarvest} of 6 birch planks needed for the crafting table and furnace fuel`);
  }
  log('birch_logs_collected_for_tools', {
    collectedLogs,
    inventory: inventory(bot),
    playerPosition: bot.entity.position.clone()
  });

  if (countItems(bot, 'birch_log') > 0) {
    const plankRecipe = bot.recipesFor(bot.registry.itemsByName.birch_planks.id, null, 1)[0];
    if (!plankRecipe) throw new Error('No survival recipe is available for birch planks');
    while (countItems(bot, 'birch_log') > 0) {
      await within(bot.craft(plankRecipe, 1), 'craft birch planks from harvested logs', 10000);
    }
  }
  log('birch_planks_crafted_for_tools', {
    inventory: inventory(bot),
    playerPosition: bot.entity.position.clone()
  });
  const provenSurfaceSupport = bot.blockAt(new Vec3(-7, 79, 59));
  if (provenSurfaceSupport?.boundingBox === 'block') {
    site = {
      soilPosition: provenSurfaceSupport.position.clone(),
      saplingPosition: provenSurfaceSupport.position.offset(0, 1, 0)
    };
    log('local_crafting_table_support_selected', {
      support: site.soilPosition,
      tablePosition: site.saplingPosition,
      reason: 'previously verified survival build location'
    });
  }
  const craftingTableItem = bot.registry.itemsByName.crafting_table;
  const tableRecipe = bot.recipesFor(craftingTableItem.id, null, 1)[0];
  if (!tableRecipe) throw new Error('No personal-inventory recipe is available for a crafting table');
  const tablePlanks = bot.inventory.items().find(item => item.name === 'birch_planks');
  if (!tablePlanks || tablePlanks.count < 4) {
    throw new Error('Four birch planks are required to craft a survival crafting table');
  }
  const craftingTablesBefore = countItems(bot, 'crafting_table');
  await within(bot.clickWindow(tablePlanks.slot, 0, 0), 'pick up birch planks for the crafting table', 5000);
  await delay(150);
  for (const slot of [1, 2, 3, 4]) {
    await within(bot.clickWindow(slot, 1, 0), `place birch plank in personal crafting slot ${slot}`, 5000);
    await delay(150);
    log('local_crafting_table_grid_slot_filled', {
      slot,
      grid: bot.inventory.slots.slice(0, 5).map(item => item?.name ?? null),
      selectedItem: bot.inventory.selectedItem?.name ?? null,
      selectedCount: bot.inventory.selectedItem?.count ?? 0
    });
  }
  if (bot.inventory.selectedItem) {
    await within(bot.clickWindow(tablePlanks.slot, 0, 0), 'return unused birch planks to inventory', 5000);
  }
  await until(() => bot.inventory.slots[0]?.name === 'crafting_table',
    'crafting table result in personal crafting grid', 5000);
  await within(bot.putAway(0), 'take the survival crafting table result', 10000);
  log('local_crafting_table_output_taken', {
    selectedItem: bot.inventory.selectedItem?.name ?? null,
    inventory: inventory(bot)
  });
  await until(() => countItems(bot, 'crafting_table') > craftingTablesBefore,
    'crafted survival crafting table in inventory', 5000);
  log('local_crafting_table_crafted', {inventory: inventory(bot)});

  const worksiteGoal = new pathfinderModule.goals.GoalNear(
    site.soilPosition.x, site.soilPosition.y + 1, site.soilPosition.z, 2);
  if (bot.entity.position.distanceTo(site.soilPosition.offset(0, 1, 0)) > 3.5) {
    await within(bot.pathfinder.goto(worksiteGoal), 'reach the survival crafting-table worksite', 45000);
    bot.pathfinder.setGoal(null);
  }

  const tablePositionBlock = bot.blockAt(site.saplingPosition);
  if (tablePositionBlock?.name !== 'air') {
    if (!tablePositionBlock || !bot.canDigBlock(tablePositionBlock)) {
      throw new Error(`Cannot clear the crafting-table position at ${site.saplingPosition}`);
    }
    await within(bot.unequip('hand'), 'clear the hand before making room for a crafting table', 5000);
    await within(bot.dig(tablePositionBlock, true, 'raycast'),
      `clear the crafting-table position at ${site.saplingPosition}`, 15000);
    await until(() => bot.blockAt(site.saplingPosition)?.name === 'air',
      'clear the crafting-table position', 5000);
  }
  const plantingSoil = bot.blockAt(site.soilPosition);
  if (!plantingSoil || plantingSoil.boundingBox !== 'block') {
    throw new Error(`The crafting-table support disappeared at ${site.soilPosition}`);
  }
  const tableItem = bot.inventory.items().find(item => item.name === 'crafting_table');
  await within(bot.equip(tableItem, 'hand'), 'equip the crafted crafting table', 5000);
  await placeBlockEventually(bot, plantingSoil, new Vec3(0, 1, 0), 'crafting_table',
    'place the local crafting table', log);
  await until(() => bot.blockAt(site.saplingPosition)?.name === 'crafting_table',
    'placed local crafting table', 5000);

  const workbench = bot.blockAt(site.saplingPosition);
  const stonePickaxe = bot.registry.itemsByName.stone_pickaxe;
  const recipe = bot.recipesFor(stonePickaxe.id, null, 1, workbench)[0];
  if (!recipe) throw new Error('No survival recipe is available for a stone pickaxe');
  const inventoryBeforeCraft = inventory(bot);
  await within(bot.craft(recipe, 1, workbench), 'craft a stone pickaxe at the local table', 15000);
  await until(() => countItems(bot, 'stone_pickaxe') > 0,
    'stone pickaxe in player inventory', 5000);
  log('stone_pickaxe_crafted', {
    workbench: site.saplingPosition,
    route: growTree
      ? "grew a birch tree using the player's sapling and bone meal"
      : 'harvested nearby naturally grown birch logs',
    recipeIngredients: ['cobblestone x3', 'stick x2'],
    inventoryBeforeCraft,
    inventoryAfterCraft: inventory(bot),
    playerPosition: bot.entity.position
  });
  return site.saplingPosition;
}

function hasDirectRay(bot, block, maxReach = 4.5) {
  const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0);
  const center = block.position.offset(0.5, 0.5, 0.5);
  const direction = center.minus(eye);
  const distance = direction.norm();
  if (distance > maxReach) return false;
  const hit = bot.world.raycast(eye, direction.clone().normalize(), Math.min(distance + 0.1, maxReach));
  return Boolean(hit && hit.position.x === block.position.x
    && hit.position.y === block.position.y && hit.position.z === block.position.z);
}

async function exposeBlockToRay(bot, target, expectedNames, log, eventPrefix, options = {}) {
  const maxReach = 4.5;
  for (let attempt = 0; attempt < (options.maxAttempts ?? 3); attempt++) {
    target = bot.blockAt(target.position);
    if (!target || !expectedNames.includes(target.name)) return false;

    const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0);
    const center = target.position.offset(0.5, 0.5, 0.5);
    const direction = center.minus(eye);
    const distance = direction.norm();
    if (distance > maxReach) {
      log(`${eventPrefix}_exposure_stalled`, {
        target: target.position,
        reason: 'target-out-of-reach',
        distance,
        maxReach
      });
      return false;
    }

    const hit = bot.world.raycast(eye, direction.clone().normalize(), Math.min(distance + 0.1, maxReach));
    if (!hit) {
      log(`${eventPrefix}_exposure_stalled`, {target: target.position, reason: 'no-ray-hit', distance});
      return false;
    }
    if (hit.position.x === target.position.x
        && hit.position.y === target.position.y
        && hit.position.z === target.position.z) return true;

    const obstruction = bot.blockAt(hit.position);
    if (obstruction && expectedNames.includes(obstruction.name)) {
      return false;
    }
    const obstructionDistance = obstruction
      ? eye.distanceTo(obstruction.position.offset(0.5, 0.5, 0.5))
      : Infinity;
    if (!obstruction || obstructionDistance > maxReach || !bot.canDigBlock(obstruction)) {
      log(`${eventPrefix}_exposure_stalled`, {
        target: target.position,
        reason: 'obstruction-out-of-reach-or-not-diggable',
        obstruction: obstruction?.name ?? null,
        position: hit.position,
        distance: obstructionDistance,
        canDig: Boolean(obstruction && bot.canDigBlock(obstruction)),
        maxReach
      });
      return false;
    }

    const position = obstruction.position.clone();
    log(`${eventPrefix}_obstruction_mining_started`, {
      block: obstruction.name,
      position,
      target: target.position,
      player: bot.entity.position,
      heldItem: bot.heldItem?.name ?? null
    });
    if (options.centerRay && !hasDirectRay(bot, obstruction)) return false;
    await within(bot.dig(obstruction, true, options.centerRay ? 'auto' : 'raycast'), `clear ${obstruction.name} at ${position}`, 10000);
    await until(() => bot.blockAt(position)?.name !== obstruction.name, `clear obstruction at ${position}`, 5000);
    log(`${eventPrefix}_obstruction_cleared`, {block: obstruction.name, position, target: target.position});
  }
  target = bot.blockAt(target.position);
  if (!target || !expectedNames.includes(target.name)) return false;
  return hasDirectRay(bot, target, maxReach);
}

async function exposeOre(bot, target, log) {
  return exposeBlockToRay(bot, target, ['iron_ore', 'deepslate_iron_ore'], log, 'iron_ore');
}

async function descendAboveIronOre(bot, target, log, options = {}) {
  const targetFeetY = target.position.y + 1;
  for (let step = 0; step < 16 && Math.floor(bot.entity.position.y) > targetFeetY; step++) {
    const feet = bot.entity.position.floored();
    const supportPosition = feet.offset(0, -1, 0);
    const support = bot.blockAt(supportPosition);
    const landingPosition = supportPosition.offset(0, -1, 0);
    const landing = bot.blockAt(landingPosition);
    if (!support || support.boundingBox !== 'block'
        || ['sand', 'red_sand', 'gravel', 'suspicious_sand', 'suspicious_gravel'].includes(support.name)) {
      log('iron_ore_descent_stopped', {
        reason: 'unsafe-or-missing-current-support',
        support: support?.name ?? null,
        supportPosition,
        playerPosition: bot.entity.position.clone()
      });
      return false;
    }
    if (!landing || landing.boundingBox !== 'block' || landing.name === 'water' || landing.name === 'lava') {
      log('iron_ore_descent_stopped', {
        reason: 'unsafe-or-missing-landing-support',
        landing: landing?.name ?? null,
        landingPosition,
        playerPosition: bot.entity.position.clone()
      });
      return false;
    }
    if (!bot.canDigBlock(support)) return false;

    const nextFeetY = feet.y - 1;
    log('iron_ore_descent_step_started', {
      step,
      support: support.name,
      supportPosition,
      landing: landing.name,
      landingPosition,
      playerPosition: bot.entity.position.clone()
    });
    if (options.centerRay && !hasDirectRay(bot, support)) return false;
    await within(bot.dig(support, true, options.centerRay ? 'auto' : 'raycast'), `dig one supported step down at ${supportPosition}`, 15000);
    await until(() => Math.floor(bot.entity.position.y) <= nextFeetY && bot.entity.onGround,
      `land on the supported descent step at ${landingPosition}`, 5000);
    log('iron_ore_descent_step_reached', {
      step,
      playerPosition: bot.entity.position.clone(),
      health: bot.health,
      food: bot.food
    });
  }
  return Math.floor(bot.entity.position.y) <= targetFeetY + 1;
}

async function tunnelToOre(bot, target, pathfinderModule, log,
  expectedNames = ['iron_ore', 'deepslate_iron_ore'], options = {}) {
  const start = bot.entity.position.clone();
  const initialFeet = bot.entity.position.floored();
  const horizontalDistance = Math.hypot(
    target.position.x + 0.5 - start.x,
    target.position.z + 0.5 - start.z
  );
  if (horizontalDistance > (options.maxHorizontal ?? 8) || target.position.y - initialFeet.y < (options.minVertical ?? 0)
      || target.position.y - initialFeet.y > 3) return false;

  let clearedBlocks = 0;
  log('iron_ore_tunnel_started', {
    target: target.position,
    player: start,
    maxSteps: options.maxSteps ?? 6
  });

  for (let step = 0; step < (options.maxSteps ?? 6); step++) {
    target = bot.blockAt(target.position);
    if (!target || !expectedNames.includes(target.name)) return false;
    const eye = bot.entity.position.offset(0, bot.entity.eyeHeight, 0);
    const targetCenter = target.position.offset(0.5, 0.5, 0.5);
    if (eye.distanceTo(targetCenter) <= 4.25 && (!options.requireRay || hasDirectRay(bot, target))) {
      log('iron_ore_tunnel_reached', {
        target: target.position,
        player: bot.entity.position,
        steps: step,
        clearedBlocks
      });
      return true;
    }

    const feet = bot.entity.position.floored();
    const deltaX = target.position.x + 0.5 - bot.entity.position.x;
    const deltaZ = target.position.z + 0.5 - bot.entity.position.z;
    const moveX = Math.abs(deltaX) > Math.abs(deltaZ) ? Math.sign(deltaX) : 0;
    const moveZ = moveX === 0 ? Math.sign(deltaZ) : 0;
    const nextY = target.position.y - feet.y >= 2 ? feet.y + 1 : feet.y;
    const next = feet.offset(moveX, nextY - feet.y, moveZ);
    const support = bot.blockAt(next.offset(0, -1, 0));
    if (!support || support.boundingBox !== 'block') {
      log('iron_ore_tunnel_stalled', {
        target: target.position,
        next,
        reason: 'unsupported-step',
        support: support?.name ?? null
      });
      return false;
    }

    for (const height of options.headFirst ? [1, 0] : [0, 1]) {
      const position = next.offset(0, height, 0);
      const block = bot.blockAt(position);
      if (!block || block.name === 'air' || block.boundingBox !== 'block') continue;
      if (block.position.equals(target.position) || !bot.canDigBlock(block) || !hasDirectRay(bot, block)) {
        log('iron_ore_tunnel_stalled', {
          target: target.position,
          next,
          obstruction: block.name,
          position,
          reason: 'step-not-safely-diggable'
        });
        return false;
      }
      await within(bot.dig(block, true, options.centerRay ? 'auto' : 'raycast'), `open supported step at ${position}`, 10000);
      await until(() => bot.blockAt(position)?.name === 'air', `open tunnel at ${position}`, 5000);
      clearedBlocks++;
    }

    bot.pathfinder.setGoal(null);
    await within(bot.lookAt(next.offset(0.5, bot.entity.position.y + 1.2 - next.y, 0.5)),
      'face the cleared tunnel step', 5000);
    bot.setControlState('forward', true);
    if (next.y > feet.y) bot.setControlState('jump', true);
    try {
      await until(() => {
        const position = bot.entity.position;
        const horizontalDistance = Math.hypot(position.x - next.x - 0.5, position.z - next.z - 0.5);
        if (horizontalDistance <= 0.35 && Math.abs(position.y - next.y) <= 0.25) return position;
      }, `walk through supported tunnel step at ${next}`, 3500);
    } finally {
      bot.setControlState('forward', false);
      bot.setControlState('jump', false);
    }
    await delay(200);
    log('iron_ore_tunnel_step', {
      target: target.position,
      position: next,
      player: bot.entity.position,
      clearedBlocks
    });
  }

  return false;
}

function readProperties(stage) {
  return Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
    .split(/\r?\n/)
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => {
      const split = line.indexOf('=');
      return [line.slice(0, split), line.slice(split + 1)];
    }));
}

async function main() {
  const [stageArg, mineflayerArg, pathfinderArg, username = 'SupplyCook'] = process.argv.slice(2);
  if (!stageArg || !mineflayerArg || !pathfinderArg) {
    throw new Error('Usage: iron-pickaxe.cjs <isolatedFoliaStage> <mineflayerModule> <pathfinderModule> [player]');
  }
  if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Invalid offline player name');

  const stage = path.resolve(stageArg);
  const properties = readProperties(stage);
  const host = properties['server-ip'];
  const port = Number(properties['server-port']);
  if (properties['online-mode'] !== 'false' || host !== '127.0.0.1') {
    throw new Error('The acceptance stage must use offline mode and bind to loopback');
  }
  if (properties.gamemode !== 'survival') throw new Error('The acceptance stage must use survival mode');
  if (!await listenerOpen(host, port)) throw new Error(`The isolated Folia game listener is unavailable on ${port}`);

  const mineflayer = require(path.resolve(mineflayerArg));
  const pathfinderModule = require(path.resolve(pathfinderArg));
  const {Vec3} = require(path.join(path.dirname(path.resolve(mineflayerArg)), 'vec3'));
  const proofDirectory = path.join(stage, 'folia-proof');
  const proofPath = path.join(proofDirectory, 'survival-iron-pickaxe-20261122.jsonl');
  const pluginPath = path.join(stage, 'plugins', 'StarSupply-0.1.0.jar');
  const pluginHash = fs.existsSync(pluginPath)
    ? require('node:crypto').createHash('sha256').update(fs.readFileSync(pluginPath)).digest('hex').toUpperCase()
    : null;
  fs.mkdirSync(proofDirectory, {recursive: true});
  const log = (event, data = {}) => {
    const row = {at: new Date().toISOString(), event, ...data};
    fs.appendFileSync(proofPath, JSON.stringify(row) + '\n');
    console.log(JSON.stringify(row));
    return row;
  };

  const bot = mineflayer.createBot({
    host,
    port,
    username,
    auth: 'offline',
    version: '1.21.11',
    viewDistance: 'short',
    checkTimeoutInterval: 60000
  });
  bot.loadPlugin(pathfinderModule.pathfinder);
  bot.on('path_update', update => {
    if (update.status !== 'success' && update.status !== 'partial') {
      log('path_update_failed', {
        status: update.status,
        nodes: update.path?.length ?? 0,
        visitedNodes: update.visitedNodes ?? null,
        elapsedMs: update.time ?? null
      });
    }
  });

  let clientFailure;
  let furnace;
  bot.on('error', error => { clientFailure = error; });
  bot.on('kicked', reason => { clientFailure = new Error(`Kicked: ${JSON.stringify(reason)}`); });
  bot.on('messagestr', message => log('server_message', {message}));

  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timed out waiting for player spawn')), 45000);
      bot.once('spawn', () => { clearTimeout(timer); resolve(); });
      bot.once('error', error => { clearTimeout(timer); reject(error); });
    });
    await within(waitForNearbyChunkColumns(bot, Vec3), 'initial terrain', 65000);
    // Mineflayer 4.37.1 does not send Minecraft 1.21.11's terrain-loaded acknowledgement.
    bot._client.write('player_loaded', {});
    await within(bot.waitForTicks(5), 'server to process the terrain-loaded acknowledgement', 5000);
    log('terrain_loaded_acknowledged');
    if (bot.game.gameMode !== 'survival') throw new Error(`Player game mode is ${bot.game.gameMode}`);

    bot.pathfinder.thinkTimeout = 15000;
    bot.pathfinder.searchRadius = 32;
    const movements = new pathfinderModule.Movements(bot);
    movements.canDig = true;
    movements.allowParkour = false;
    movements.allow1by1towers = false;
    bot.pathfinder.setMovements(movements);
    const before = inventory(bot);
    let startingPosition = bot.entity.position.clone();
    log('survival_session_started', {
      server: 'Folia 1.21.11-14',
      player: username,
      uuid: bot.player.uuid,
      pluginSha256: pluginHash,
      gameMode: bot.game.gameMode,
      position: startingPosition,
      health: bot.health,
      food: bot.food,
      inventory: before,
      nearbyIronOre: bot.findBlocks({
        matching: block => block.name === 'iron_ore' || block.name === 'deepslate_iron_ore',
        maxDistance: 32,
        count: 30
      })
    });

    const originalIron = countItems(bot, 'iron_ingot');
    const originalRawIron = countItems(bot, 'raw_iron');
    const requiredIronUnits = Math.max(0, 3 - originalIron);
    const requiredOre = Math.max(0, requiredIronUnits - originalRawIron);
    const existingWorkbenchPosition = new Vec3(-20, 80, 71);
    if (requiredIronUnits === 0) {
      const workbench = bot.findBlock({matching: bot.registry.blocksByName.crafting_table.id, maxDistance: 8});
      if (!workbench) throw new Error('The saved smelting checkpoint has no nearby crafting table');
      await craftIronPickaxeConfirmed(bot, workbench, log, mineflayerArg);
      log('survival_session_passed', {checkpoint: 'saved_smelted_iron',
        ironPickaxeCount: countItems(bot, 'iron_pickaxe'), finalInventory: inventory(bot)});
      return;
    }
    let workbenchPosition = await craftStonePickaxe(
      bot,
      pathfinderModule,
      Vec3,
      existingWorkbenchPosition,
      new Vec3(-8, 75, 58),
      new Vec3(-10.68, 61, 74.45),
      log);
    const miningLanding = new Vec3(-11, 61, 73);
    if (bot.entity.position.distanceTo(miningLanding) > 8) {
      log('survival_mining_route_started', {
        target: miningLanding,
        playerPosition: bot.entity.position.clone()
      });
      try {
        await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
          miningLanding.x, miningLanding.y, miningLanding.z, 3)),
        'return to the established iron-mining landing', 90000);
        log('survival_mining_landing_reached', {playerPosition: bot.entity.position.clone()});
      } catch (error) {
        log('survival_mining_route_failed', {
          target: miningLanding,
          playerPosition: bot.entity.position.clone(),
          reason: error.message
        });
      } finally {
        bot.pathfinder.setGoal(null);
      }
    }
    startingPosition = bot.entity.position.clone();
    const birchPlanksRequiredForFuel = Math.ceil(requiredIronUnits / 1.5);
    if (countItems(bot, 'coal') < 1
        && countItems(bot, 'birch_planks') < birchPlanksRequiredForFuel) {
      throw new Error(`Need ${birchPlanksRequiredForFuel} birch planks to fuel smelting the mined iron`);
    }

    const stonePickaxe = bot.inventory.items().find(item => item.name === 'stone_pickaxe');
    await within(bot.equip(stonePickaxe, 'hand'), 'equip the existing stone pickaxe', 5000);
    const candidatePositions = bot.findBlocks({
      matching: block => block.name === 'iron_ore' || block.name === 'deepslate_iron_ore',
      maxDistance: 16,
      count: 40
    });
    const candidates = candidatePositions.map(position => bot.blockAt(position))
      .filter(block => block && (block.name === 'iron_ore' || block.name === 'deepslate_iron_ore'))
      .sort((first, second) => first.position.distanceTo(startingPosition)
        - second.position.distanceTo(startingPosition));

    let mined = 0;
    for (const candidate of candidates) {
      if (mined >= requiredOre) break;
      let target = bot.blockAt(candidate.position);
      if (!target || (target.name !== 'iron_ore' && target.name !== 'deepslate_iron_ore')) continue;
      try {
        const horizontalDistance = Math.hypot(
          target.position.x + 0.5 - bot.entity.position.x,
          target.position.z + 0.5 - bot.entity.position.z);
        if (bot.entity.position.y - target.position.y > 3 && horizontalDistance <= 2) {
          const reachedOreLevel = await descendAboveIronOre(bot, target, log);
          if (!reachedOreLevel) {
            log('iron_ore_unreachable', {
              position: target.position,
              player: bot.entity.position,
              reason: 'supported-descent-could-not-reach-ore-level'
            });
            continue;
          }
          target = bot.blockAt(candidate.position);
        }
        if (!bot.canDigBlock(target) || !hasDirectRay(bot, target)) {
          await tunnelToOre(bot, target, pathfinderModule, log);
          target = bot.blockAt(candidate.position);
        }
        let targetExposed = Boolean(target && bot.canDigBlock(target) && hasDirectRay(bot, target));
        if (target && !targetExposed && bot.canDigBlock(target)) {
          targetExposed = await exposeOre(bot, target, log);
        }
        if (!bot.canDigBlock(target) || !hasDirectRay(bot, target)) {
          try {
            const goal = new pathfinderModule.goals.GoalLookAtBlock(target.position, bot.world, {
              reach: 3.0,
              entityHeight: bot.entity.eyeHeight
            });
            await within(bot.pathfinder.goto(goal), `approach iron ore at ${target.position}`, 18000);
          } catch (error) {
            if (!targetExposed) throw error;
          }
        }
        target = bot.blockAt(candidate.position);
        targetExposed = Boolean(targetExposed || (target && bot.canDigBlock(target) && hasDirectRay(bot, target)));
        if (target && !targetExposed) targetExposed = await exposeOre(bot, target, log);
        target = bot.blockAt(candidate.position);
        if (!target || (!targetExposed && (!bot.canDigBlock(target) || !hasDirectRay(bot, target)))) {
          log('iron_ore_unreachable', {position: candidate.position, player: bot.entity.position});
          continue;
        }

        const rawBefore = countItems(bot, 'raw_iron');
        log('iron_ore_mining_started', {
          block: target.name,
          position: target.position,
          player: bot.entity.position,
          heldItem: bot.heldItem?.name ?? null,
          rawIronBefore: rawBefore,
          predictedDigTimeMs: bot.digTime(target)
        });
        await within(bot.dig(target, true, 'raycast'), `mine iron ore at ${target.position}`, 15000);
        const rawAfter = await until(() => {
          const total = countItems(bot, 'raw_iron');
          if (total > rawBefore) return total;
          const drop = Object.values(bot.entities).find(entity => entity.name === 'item'
            && entity.getDroppedItem()?.name === 'raw_iron'
            && entity.position.distanceTo(target.position.offset(0.5, 0.5, 0.5)) <= 6);
          if (drop) return {drop};
        }, 'mined raw iron', 15000, () => clientFailure);
        if (typeof rawAfter === 'object' && rawAfter.drop) {
          const dropPosition = rawAfter.drop.position.floored();
          await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalBlock(
            dropPosition.x, dropPosition.y, dropPosition.z)), 'collect raw iron', 12000);
        }
        const confirmedRawIron = await until(() => {
          const total = countItems(bot, 'raw_iron');
          if (total > rawBefore) return total;
        }, 'raw iron inventory update', 10000, () => clientFailure);
        mined += confirmedRawIron - rawBefore;
        log('iron_ore_mined', {
          block: target.name,
          position: target.position,
          rawIronBefore: rawBefore,
          rawIronAfter: confirmedRawIron,
          inventory: inventory(bot),
          health: bot.health,
          food: bot.food
        });
      } catch (error) {
        bot.pathfinder.setGoal(null);
        log('iron_ore_attempt_failed', {
          position: candidate.position,
          player: bot.entity.position,
          reason: error.message
        });
      } finally {
        bot.pathfinder.setGoal(null);
      }
    }

    if (countItems(bot, 'raw_iron') < originalRawIron + requiredOre) {
      throw new Error(`Mined ${mined} of ${requiredOre} required iron ore`);
    }
    const rawForSmelting = Math.min(countItems(bot, 'raw_iron'), requiredIronUnits);
    const coalBefore = countItems(bot, 'coal');
    if (bot.entity.position.distanceTo(workbenchPosition.offset(0.5, 0.5, 0.5)) > 5) {
      const localWorkbenches = bot.findBlocks({
        matching: block => block.name === 'crafting_table',
        maxDistance: 8,
        count: 10
      }).sort((first, second) => first.distanceTo(bot.entity.position)
        - second.distanceTo(bot.entity.position));
      if (localWorkbenches.length > 0) {
        workbenchPosition = localWorkbenches[0];
      } else {
        const tableItem = bot.inventory.items().find(item => item.name === 'crafting_table');
        const support = findPlacementSupport(bot, bot.entity.position);
        if (!tableItem || !support) {
          throw new Error('A local crafting table and supported placement surface are required for the furnace');
        }
        await within(bot.equip(tableItem, 'hand'), 'equip a second crafting table for the mining area', 5000);
        const tablePosition = support.position.offset(0, 1, 0);
        await placeBlockEventually(bot, support, new Vec3(0, 1, 0), 'crafting_table',
          'place a crafting table beside the iron-mining area', log);
        await until(() => bot.blockAt(tablePosition)?.name === 'crafting_table',
          'local mining-area crafting table', 5000);
        workbenchPosition = tablePosition;
        log('mining_area_crafting_table_placed', {
          support: support.position,
          workbench: workbenchPosition,
          playerPosition: bot.entity.position.clone()
        });
      }
    }
    let furnacePosition = new Vec3(-11, 61, 73);
    let furnaceBlock = bot.blockAt(furnacePosition);
    if (furnaceBlock?.name !== 'furnace'
        || bot.entity.position.distanceTo(furnacePosition.offset(0.5, 0.5, 0.5)) > 8) {
      const furnaceItem = bot.registry.itemsByName.furnace;
      let craftedFurnace = bot.inventory.items().find(item => item.name === 'furnace');
      const craftedThisSession = !craftedFurnace;
      if (!craftedFurnace) {
        const furnaceRecipe = bot.recipesFor(furnaceItem.id, null, 1, bot.blockAt(workbenchPosition))[0];
        if (!furnaceRecipe) throw new Error('No survival recipe is available for a furnace');
        await within(bot.craft(furnaceRecipe, 1, bot.blockAt(workbenchPosition)), 'craft a local furnace', 10000);
        craftedFurnace = await until(
          () => bot.inventory.items().find(item => item.name === 'furnace'),
          'crafted furnace in player inventory',
          5000,
          () => clientFailure);
      }
      let localPlacement = findAdjacentPlacement(bot, bot.entity.position.floored(), [workbenchPosition], 5);
      if (!localPlacement) localPlacement = await openFurnaceAlcove(bot, Vec3, log);
      let support = localPlacement ? null : findPlacementSupport(
        bot,
        bot.entity.position.floored(),
        0,
        [workbenchPosition],
        Math.floor(bot.entity.position.y) - 1,
        12
      );
      if (!support && !localPlacement) {
        const movedToClearTile = await moveToFurnaceWorksite(bot, pathfinderModule, log);
        if (movedToClearTile) {
          support = findPlacementSupport(
            bot,
            bot.entity.position.floored(),
            0,
            [workbenchPosition],
            Math.floor(bot.entity.position.y) - 1,
            12
          );
        }
      }
      if (!support && !localPlacement) {
        const returnedToSurface = await returnToFurnaceSurfaceSite(bot, pathfinderModule, Vec3, log);
        if (returnedToSurface) {
          support = findPlacementSupport(
            bot,
            bot.entity.position.floored(),
            0,
            [workbenchPosition],
            Math.floor(bot.entity.position.y) - 1,
            12
          );
        }
      }
      let referenceBlock = support;
      let placementFace = new Vec3(0, 1, 0);
      let placementTarget;
      if (!referenceBlock) {
        const adjacentPlacement = localPlacement ?? findAdjacentPlacement(
          bot,
          bot.entity.position.floored(),
          [workbenchPosition],
          12
        );
        if (!adjacentPlacement) {
          throw new Error('No nearby supported, collision-free position is available for a local furnace');
        }
        referenceBlock = adjacentPlacement.block;
        placementFace = adjacentPlacement.face;
        placementTarget = adjacentPlacement.target;
      } else {
        placementTarget = referenceBlock.position.offset(placementFace.x, placementFace.y, placementFace.z);
      }
      await within(bot.equip(craftedFurnace, 'hand'), 'equip the crafted furnace', 5000);
      log('local_furnace_support_selected', {
        support: referenceBlock.position,
        face: placementFace,
        target: placementTarget,
        supportType: referenceBlock.name,
        playerPosition: bot.entity.position.clone()
      });
      await placeBlockEventually(bot, referenceBlock, placementFace, 'furnace',
        'place the local furnace', log);
      furnacePosition = referenceBlock.position.offset(placementFace.x, placementFace.y, placementFace.z);
      furnaceBlock = await until(() => {
        const block = bot.blockAt(furnacePosition);
        if (block?.name === 'furnace') return block;
      }, 'placed local furnace', 5000);
      log('local_furnace_placed', {
        furnace: furnacePosition,
        craftedThisSession,
        ingredients: craftedThisSession ? ['cobblestone x8'] : [],
        inventory: inventory(bot)
      });
    }
    furnace = await within(bot.openFurnace(furnaceBlock), 'open the existing furnace', 10000);
    if (furnace.inputItem() || furnace.outputItem()) {
      throw new Error('The existing furnace contains items from another workflow');
    }
    const fuelName = countItems(bot, 'coal') > 0 ? 'coal' : 'birch_planks';
    const fuelCount = fuelName === 'coal' ? 1 : Math.ceil(rawForSmelting / 1.5);
    const fuelItem = bot.registry.itemsByName[fuelName];
    if (countItems(bot, fuelName) < fuelCount) {
      throw new Error(`Not enough ${fuelName} is available for smelting ${rawForSmelting} raw iron`);
    }
    log('smelting_started', {
      furnace: furnacePosition,
      rawIron: rawForSmelting,
      fuel: {name: fuelName, count: fuelCount},
      playerPosition: bot.entity.position,
      inventory: inventory(bot)
    });
    await within(furnace.putInput(bot.registry.itemsByName.raw_iron.id, null, rawForSmelting),
      'insert the mined raw iron', 10000);
    await within(furnace.putFuel(fuelItem.id, null, fuelCount),
      `insert ${fuelName} as furnace fuel`, 10000);
    await until(() => {
      const output = furnace.outputItem();
      if (output?.name === 'iron_ingot' && output.count >= rawForSmelting) return output;
    }, 'iron ingots in the furnace', rawForSmelting * 18000, () => clientFailure);
    const furnaceOutput = furnace.outputItem();
    log('smelting_output_ready', {
      output: {name: furnaceOutput.name, count: furnaceOutput.count},
      furnaceInput: furnace.inputItem(),
      furnaceFuel: furnace.fuelItem(),
      progress: furnace.progress
    });
    await within(furnace.takeOutput(), 'take the smelted iron', 10000);
    await within(furnace.close(), 'close the furnace', 5000);
    furnace = null;

    const ironAfterSmelting = countItems(bot, 'iron_ingot');
    if (ironAfterSmelting < 3) throw new Error('Smelted iron did not reach the pickaxe recipe count');
    let workbench = bot.blockAt(workbenchPosition);
    if (workbench?.name !== 'crafting_table') {
      throw new Error(`Previously used crafting table not found at ${workbenchPosition}`);
    }
    if (!bot.canSeeBlock(workbench)) {
      const goal = new pathfinderModule.goals.GoalLookAtBlock(workbenchPosition, bot.world, {
        reach: 3.5,
        entityHeight: bot.entity.eyeHeight
      });
      await within(bot.pathfinder.goto(goal), 'return to the existing crafting table', 30000);
      bot.pathfinder.setGoal(null);
      workbench = bot.blockAt(workbenchPosition);
    }
    if (!workbench || !bot.canDigBlock(workbench) || !bot.canSeeBlock(workbench)) {
      throw new Error('Could not reach the existing crafting table through the survival route');
    }
    if (countItems(bot, 'stick') < 2) {
      const stickRecipe = bot.recipesFor(bot.registry.itemsByName.stick.id, null, 1, workbench)[0];
      if (!stickRecipe) throw new Error('No survival recipe is available for the missing pickaxe sticks');
      await within(bot.craft(stickRecipe, 1, workbench), 'craft pickaxe sticks', 10000);
      await until(() => countItems(bot, 'stick') >= 2,
        'crafted pickaxe sticks in player inventory', 5000, () => clientFailure);
      log('pickaxe_sticks_crafted', {
        workbench: workbenchPosition,
        inventory: inventory(bot)
      });
    }
    const ironPickaxe = bot.registry.itemsByName.iron_pickaxe;
    const recipe = bot.recipesFor(ironPickaxe.id, null, 1, workbench)[0];
    if (!recipe) throw new Error('No survival recipe is available for an iron pickaxe');
    const inventoryBeforeCraft = inventory(bot);
    await within(craftIronPickaxeConfirmed(bot, workbench, log, mineflayerArg), 'craft an iron pickaxe with server confirmation', 30000);
    await until(() => countItems(bot, 'iron_pickaxe') > 0,
      'iron pickaxe in player inventory', 5000);

    log('iron_pickaxe_crafted', {
      workbench: workbenchPosition,
      recipeIngredients: ['iron_ingot x3', 'stick x2'],
      inventoryBeforeCraft,
      inventoryAfterCraft: inventory(bot),
      playerPosition: bot.entity.position,
      health: bot.health,
      food: bot.food,
      ironOreMined: mined,
      ironSmelted: rawForSmelting,
      coalBefore,
      coalAfter: countItems(bot, 'coal')
    });
    log('survival_session_passed', {
      ironPickaxeCount: countItems(bot, 'iron_pickaxe'),
      originalIron,
      finalInventory: inventory(bot),
      startPosition: startingPosition,
      endPosition: bot.entity.position
    });
  } catch (error) {
    log('survival_session_failed', {reason: error.message});
    throw error;
  } finally {
    if (bot.pathfinder) bot.pathfinder.setGoal(null);
    if (furnace) {
      try {
        await furnace.close();
      } catch (_) {}
    }
    if (!bot._client.ended) bot.end('survival iron pickaxe acceptance complete');
  }
}

module.exports = {waitForNearbyChunkColumns, placeBlockEventually, findPlacementSupport,
  findAdjacentPlacement, exposeBlockToRay, hasDirectRay, tunnelToOre, descendAboveIronOre,
  returnToFurnaceSurfaceSite, openFurnaceAlcove};

if (require.main === module) {
  main().catch(error => {
    console.error(error.stack || String(error));
    process.exitCode = 1;
  });
}
