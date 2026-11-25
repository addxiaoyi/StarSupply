const fs = require('node:fs');
const path = require('node:path');

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

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

function readProperties(stage) {
  return Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
    .split(/\r?\n/)
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => {
      const split = line.indexOf('=');
      return [line.slice(0, split), line.slice(split + 1)];
    }));
}

function inventory(bot) {
  return bot.inventory.items().map(item => ({name: item.name, count: item.count}));
}

async function main() {
  const [stageArg, mineflayerArg, pathfinderArg, username = 'SupplyCook', mode = 'routes'] = process.argv.slice(2);
  if (!stageArg || !mineflayerArg || !pathfinderArg) {
    throw new Error('Usage: route-probe.cjs <isolatedFoliaStage> <mineflayerModule> <pathfinderModule> [player]');
  }

  const stage = path.resolve(stageArg);
  const properties = readProperties(stage);
  const mineflayer = require(path.resolve(mineflayerArg));
  const pathfinderModule = require(path.resolve(pathfinderArg));
  const proofPath = path.join(stage, 'folia-proof', `survival-route-probe-${Date.now()}.jsonl`);
  const log = (event, data = {}) => {
    const row = {at: new Date().toISOString(), event, ...data};
    fs.appendFileSync(proofPath, JSON.stringify(row) + '\n');
    console.log(JSON.stringify(row));
    return row;
  };
  const bot = mineflayer.createBot({
    host: properties['server-ip'],
    port: Number(properties['server-port']),
    username,
    version: '1.21.11'
  });
  bot.loadPlugin(pathfinderModule.pathfinder);

  let failure;
  bot.on('error', error => { failure = error; });
  bot.on('kicked', reason => { failure = new Error(`Kicked: ${reason}`); });
  bot.on('path_update', update => log('path_update', {
    status: update.status,
    nodes: update.path?.length ?? 0,
    movements: update.excessiveMove ?? null
  }));

  try {
    await within(new Promise((resolve, reject) => {
      bot.once('spawn', resolve);
      bot.once('error', reject);
    }), 'player spawn', 45000);
    await within(bot.waitForChunksToLoad(), 'initial terrain', 15000);

    const movements = new pathfinderModule.Movements(bot);
    movements.canDig = false;
    movements.canPlaceBlocks = false;
    movements.allowParkour = false;
    movements.allow1by1towers = false;
    bot.pathfinder.setMovements(movements);
    bot.pathfinder.thinkTimeout = 60000;
    bot.pathfinder.searchRadius = -1;

    const initial = {
      position: bot.entity.position.clone(),
      inventory: inventory(bot),
      health: bot.health,
      food: bot.food
    };
    log('read_only_route_probe_started', {
      player: username,
      position: initial.position,
      inventory: initial.inventory,
      miningLanding: {x: -11, y: 61, z: 73},
      surfacePassage: {x: -8, y: 75, z: 58},
      craftingTable: {x: -20, y: 80, z: 71},
      canDig: movements.canDig,
      canPlaceBlocks: movements.canPlaceBlocks
    });

    const goals = mode === 'scan-only' ? [] : mode === 'table-only' ? [
      {name: 'nearby_existing_crafting_table', position: {x: -7, y: 80, z: 59}, radius: 3}
    ] : [
      {name: 'mining_landing', position: {x: -11, y: 61, z: 73}, radius: 2},
      {name: 'surface_passage', position: {x: -8, y: 75, z: 58}, radius: 3},
      {name: 'crafting_table', position: {x: -20, y: 80, z: 71}, radius: 3}
    ];
    for (const target of goals) {
      const startPosition = bot.entity.position.clone();
      log('read_only_route_started', {goal: target.name, startPosition, target});
      try {
        await within(bot.pathfinder.goto(new pathfinderModule.goals.GoalNear(
          target.position.x,
          target.position.y,
          target.position.z,
          target.radius)), `route to ${target.name}`, 60000);
        log('read_only_route_reached', {
          goal: target.name,
          startPosition,
          endPosition: bot.entity.position.clone(),
          inventoryUnchanged: JSON.stringify(initial.inventory) === JSON.stringify(inventory(bot))
        });
      } catch (error) {
        bot.pathfinder.setGoal(null);
        log('read_only_route_failed', {
          goal: target.name,
          startPosition,
          endPosition: bot.entity.position.clone(),
          reason: error.message,
          inventoryUnchanged: JSON.stringify(initial.inventory) === JSON.stringify(inventory(bot))
        });
        break;
      }
      bot.pathfinder.setGoal(null);
      await delay(300);
    }

    const soilPositions = bot.findBlocks({
      matching: block => ['dirt', 'grass_block', 'podzol', 'coarse_dirt'].includes(block.name),
      maxDistance: 24,
      count: 300
    });
    const naturalTreeSites = soilPositions.map(position => {
      let clear = true;
      for (let height = 1; height <= 6 && clear; height++) {
        const radius = height <= 2 ? 0 : 2;
        for (let offsetX = -radius; offsetX <= radius && clear; offsetX++) {
          for (let offsetZ = -radius; offsetZ <= radius; offsetZ++) {
            const block = bot.blockAt(position.offset(offsetX, height, offsetZ));
            if (!block || block.boundingBox !== 'empty') {
              clear = false;
              break;
            }
          }
        }
      }
      return {position, clear, distance: position.distanceTo(bot.entity.position)};
    }).filter(site => site.clear).sort((first, second) => first.distance - second.distance);

    const floorPositions = bot.findBlocks({
      matching: block => block.boundingBox === 'block',
      maxDistance: 8,
      count: 300
    });
    const dirtPlots = floorPositions.map(position => {
      let clear = true;
      for (let height = 2; height <= 7 && clear; height++) {
        const radius = height <= 3 ? 0 : 2;
        for (let offsetX = -radius; offsetX <= radius && clear; offsetX++) {
          for (let offsetZ = -radius; offsetZ <= radius; offsetZ++) {
            const block = bot.blockAt(position.offset(offsetX, height, offsetZ));
            if (!block || block.boundingBox !== 'empty') {
              clear = false;
              break;
            }
          }
        }
      }
      return {
        support: position,
        soil: position.offset(0, 1, 0),
        clear,
        visible: bot.canSeeBlock(bot.blockAt(position)),
        distance: position.distanceTo(bot.entity.position)
      };
    }).filter(plot => plot.clear).sort((first, second) => first.distance - second.distance);

    const nearbyIronOre = bot.findBlocks({
      matching: block => block.name === 'iron_ore' || block.name === 'deepslate_iron_ore',
      maxDistance: 12,
      count: 30
    }).map(position => {
      const block = bot.blockAt(position);
      return {
        position,
        block: block?.name ?? null,
        visible: Boolean(block && bot.canSeeBlock(block)),
        distance: position.distanceTo(bot.entity.position)
      };
    }).sort((first, second) => first.distance - second.distance);
    const nearbyBirchLogs = bot.findBlocks({
      matching: block => block.name === 'birch_log',
      maxDistance: 24,
      count: 100
    }).map(position => {
      const block = bot.blockAt(position);
      return {
        position,
        visible: Boolean(block && bot.canSeeBlock(block)),
        distance: position.distanceTo(bot.entity.position)
      };
    }).sort((first, second) => first.distance - second.distance);
    const nearbyCraftingTables = bot.findBlocks({
      matching: block => block.name === 'crafting_table',
      maxDistance: 32,
      count: 30
    }).map(position => ({position, distance: position.distanceTo(bot.entity.position)}))
      .sort((first, second) => first.distance - second.distance);
    const nearbyPlacementSupports = floorPositions.map(position => bot.blockAt(position))
      .filter(block => block
        && bot.blockAt(block.position.offset(0, 1, 0))?.name === 'air'
        && bot.canSeeBlock(block))
      .map(block => ({
        support: block.position,
        placement: block.position.offset(0, 1, 0),
        intersectsPlayer: bot.entity.position.x + 0.3 > block.position.x
          && bot.entity.position.x - 0.3 < block.position.x + 1
          && bot.entity.position.y + (bot.entity.height ?? 1.8) > block.position.y + 1
          && bot.entity.position.y < block.position.y + 2
          && bot.entity.position.z + 0.3 > block.position.z
          && bot.entity.position.z - 0.3 < block.position.z + 1,
        distance: block.position.distanceTo(bot.entity.position)
      }))
      .sort((first, second) => first.distance - second.distance);
    const feet = bot.entity.position.floored();
    const verticalProfiles = [];
    for (let offsetX = -4; offsetX <= 4; offsetX++) {
      for (let offsetZ = -4; offsetZ <= 4; offsetZ++) {
        let solidBlocks = 0;
        let fluids = 0;
        let firstAirRun = 0;
        for (let y = feet.y + 1; y <= 84; y++) {
          const block = bot.blockAt(feet.offset(offsetX, y - feet.y, offsetZ));
          if (!block) continue;
          if (block.name === 'water' || block.name === 'lava') fluids++;
          if (block.boundingBox === 'block') solidBlocks++;
          if (y <= feet.y + 3 && block.boundingBox === 'empty') firstAirRun++;
        }
        verticalProfiles.push({
          x: feet.x + offsetX,
          z: feet.z + offsetZ,
          horizontalDistance: Math.hypot(offsetX, offsetZ),
          solidBlocks,
          fluids,
          headroomNearStart: firstAirRun
        });
      }
    }
    verticalProfiles.sort((first, second) => first.solidBlocks - second.solidBlocks
      || first.horizontalDistance - second.horizontalDistance);
    log('local_survival_sites', {
      naturalTreeSites: naturalTreeSites.slice(0, 10),
      possibleDirtPlots: dirtPlots.slice(0, 10),
      nearbyIronOre: nearbyIronOre.slice(0, 20),
      nearbyBirchLogs: nearbyBirchLogs.slice(0, 30),
      nearbyCraftingTables: nearbyCraftingTables.slice(0, 15),
      nearbyPlacementSupports: nearbyPlacementSupports.slice(0, 30),
      verticalProfiles: verticalProfiles.slice(0, 15),
      inventoryUnchanged: JSON.stringify(initial.inventory) === JSON.stringify(inventory(bot))
    });

    log('read_only_route_probe_completed', {
      initial,
      finalPosition: bot.entity.position.clone(),
      finalInventory: inventory(bot),
      inventoryUnchanged: JSON.stringify(initial.inventory) === JSON.stringify(inventory(bot)),
      failure: failure?.message ?? null
    });
    if (failure) throw failure;
  } finally {
    bot.pathfinder.setGoal(null);
    if (!bot._client.ended) bot.end('read-only route probe complete');
    await within(new Promise(resolve => {
      if (bot._client.ended) return resolve();
      bot.once('end', resolve);
      setTimeout(resolve, 5000);
    }), 'client disconnect', 6000).catch(() => {});
  }
}

main().catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
