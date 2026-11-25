const {spawn} = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const net = require('node:net');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

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

function inventory(bot) {
  return bot.inventory.items().map(item => ({name: item.name, count: item.count}));
}

function countItems(bot, names) {
  return bot.inventory.items().filter(item => names(item.name)).reduce((sum, item) => sum + item.count, 0);
}

async function walkToVisibleBlock(bot, goals, candidates, log) {
  for (const block of candidates) {
    try {
      if (!bot.canDigBlock(block) || !bot.canSeeBlock(block)) {
        const goal = new goals.GoalLookAtBlock(block.position, bot.world, {
          reach: 3.5,
          entityHeight: bot.entity.eyeHeight
        });
        await within(bot.pathfinder.goto(goal), `walk to ${block.name}`, 60000);
      }
      const current = bot.blockAt(block.position);
      if (current?.name === block.name && bot.canDigBlock(current) && bot.canSeeBlock(current)) {
        return current;
      }
    } catch (error) {
      log('approach_failed', {
        block: block.name,
        target: block.position,
        player: bot.entity.position,
        reason: error.message
      });
    } finally {
      bot.pathfinder.setGoal(null);
    }
  }
  throw new Error('Could not reach a natural log with a clear line of sight');
}

function portClosed(port) {
  return new Promise(resolve => {
    const socket = net.createConnection({host: '127.0.0.1', port});
    const timer = setTimeout(() => {
      socket.destroy();
      resolve(false);
    }, 1000);
    socket.once('connect', () => {
      clearTimeout(timer);
      socket.destroy();
      resolve(false);
    });
    socket.once('error', error => {
      clearTimeout(timer);
      resolve(error.code === 'ECONNREFUSED');
    });
  });
}

async function main() {
  const [stageArg, mineflayerArg, pathfinderArg, javaArg, username = 'SupplySurvivor'] = process.argv.slice(2);
  if (!stageArg || !mineflayerArg || !pathfinderArg || !javaArg) {
    throw new Error('Usage: run.cjs <isolatedStage> <mineflayerModule> <pathfinderModule> <javaExecutable> [player]');
  }
  if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Invalid offline player name');

  const stage = path.resolve(stageArg);
  const proof = path.join(stage, 'acceptance-proof');
  const properties = Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
    .split(/\r?\n/)
    .filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => {
      const split = line.indexOf('=');
      return [line.slice(0, split), line.slice(split + 1)];
    }));
  if (properties['online-mode'] !== 'false' || properties['server-ip'] !== '127.0.0.1') {
    throw new Error('The acceptance stage must use offline mode and bind to 127.0.0.1');
  }
  if (properties.gamemode !== 'survival') throw new Error('The acceptance stage must use survival mode');
  const gamePort = Number(properties['server-port']);
  const rconPort = Number(properties['rcon.port']);
  for (const port of [gamePort, rconPort]) {
    if (!Number.isInteger(port) || port < 1 || port > 65535 || !await portClosed(port)) {
      throw new Error(`Acceptance listener is unavailable: ${port}`);
    }
  }

  const mineflayer = require(path.resolve(mineflayerArg));
  const pathfinderModule = require(path.resolve(pathfinderArg));
  const logPath = path.join(proof, 'survival-acquisition.jsonl');
  const log = (event, data = {}) => {
    const row = {at: new Date().toISOString(), event, ...data};
    fs.mkdirSync(proof, {recursive: true});
    fs.appendFileSync(logPath, JSON.stringify(row) + '\n');
    console.log(JSON.stringify(row));
    return row;
  };

  const java = spawn(path.resolve(javaArg), [
    '-Xms512M', '-Xmx2G', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC',
    '-Dfile.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
    '-jar', 'paper-1.21.11-132.jar', '--nogui'
  ], {cwd: stage, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']});
  let serverOutput = '';
  let serverError = '';
  const observedServerMessages = [];
  java.stdout.setEncoding('utf8').on('data', chunk => { serverOutput += chunk; });
  java.stderr.setEncoding('utf8').on('data', chunk => { serverError += chunk; });
  let serverFailure;
  java.on('error', error => { serverFailure = error; });
  java.stdin.on('error', error => { serverFailure = error; });
  let serverExit;
  const serverClosed = new Promise(resolve => java.once('close', (code, signal) => {
    serverExit = {code, signal};
    resolve(serverExit);
  }));

  // Read the real block from Paper. Mineflayer predicts air when its dig timer ends.
  async function serverHasBlock(position, name) {
    const marker = `survival-block-${crypto.randomUUID()}`;
    const query = `block ${position.x} ${position.y} ${position.z}`;
    java.stdin.write(`minecraft:execute at ${username} if ${query} minecraft:${name} run minecraft:say ${marker} match\n`);
    java.stdin.write(`minecraft:execute at ${username} unless ${query} minecraft:${name} run minecraft:say ${marker} other\n`);
    const answer = await until(() => {
      if (serverOutput.includes(`${marker} match`)
          || observedServerMessages.some(message => message.includes(`${marker} match`))) return true;
      if (serverOutput.includes(`${marker} other`)
          || observedServerMessages.some(message => message.includes(`${marker} other`))) return false;
    }, `Paper block query for ${name} at ${position}`, 5000, () => serverFailure);
    return answer;
  }

  async function waitForServerBlock(position, name, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    let present = await serverHasBlock(position, name);
    while (Date.now() < deadline && !present) {
      await delay(300);
      present = await serverHasBlock(position, name);
    }
    return present;
  }

  let bot;
  let clientFailure;
  try {
    await until(() => serverOutput.includes(`RCON running on 127.0.0.1:${rconPort}`)
      && serverOutput.includes('Done ('), 'Paper startup', 180000,
    () => serverFailure ?? (serverExit ? new Error(`Paper exited during startup: ${JSON.stringify(serverExit)}`) : null));
    log('paper_ready', {
      processId: java.pid,
      gamePort,
      server: 'Paper 1.21.11-132',
      starSupplySha256: crypto.createHash('sha256').update(fs.readFileSync(path.join(stage, 'plugins', 'StarSupply-0.1.0.jar'))).digest('hex').toUpperCase()
    });
    bot = mineflayer.createBot({
      host: '127.0.0.1', port: gamePort, username,
      auth: 'offline', version: '1.21.11', viewDistance: 'short',
      checkTimeoutInterval: 90000
    });
    bot.loadPlugin(pathfinderModule.pathfinder);
    bot.on('error', error => { clientFailure = error; });
    bot.on('kicked', reason => { clientFailure = new Error(`Kicked: ${JSON.stringify(reason)}`); });
    bot.on('messagestr', message => {
      observedServerMessages.push(message);
      log('server_message', {message});
    });
    bot.on('itemDrop', entity => log('item_drop_observed', {
      entityId: entity.id,
      name: entity.name,
      position: entity.position,
      metadata: entity.metadata
    }));
    bot.on('playerCollect', (collector, collected) => log('item_pickup_observed', {
      collectorId: collector.id,
      itemEntityId: collected.id,
      item: collected.getDroppedItem()?.name ?? null,
      itemPosition: collected.position
    }));
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Timed out waiting for player spawn')), 60000);
      bot.once('spawn', () => { clearTimeout(timer); resolve(); });
      bot.once('error', error => { clearTimeout(timer); reject(error); });
    });
    if (typeof bot.digTime !== 'function') throw new Error('Mineflayer digging support is unavailable after spawn');
    const predictedDigTime = bot.digTime.bind(bot);
    bot.digTime = block => predictedDigTime(block) + 750;
    const movements = new pathfinderModule.Movements(bot);
    movements.canDig = true;
    movements.canPlaceBlocks = false;
    movements.allow1by1towers = false;
    movements.allowParkour = true;
    bot.pathfinder.setMovements(movements);
    bot.pathfinder.searchRadius = -1;
    bot.pathfinder.thinkTimeout = 60000;
    await within(bot.waitForChunksToLoad(), 'initial terrain', 15000);
    if (bot.game.gameMode !== 'survival') throw new Error(`Player game mode is ${bot.game.gameMode}`);
    log('player_spawned', {
      username,
      uuid: bot.player.uuid,
      position: bot.entity.position,
      spawnPoint: bot.spawnPoint,
      health: bot.health,
      food: bot.food,
      inventory: inventory(bot)
    });
    log('server_dig_timing_margin_configured', {extraMilliseconds: 750});
    const emptyHotbarSlot = bot.inventory.slots.slice(36, 45).findIndex(item => !item);
    if (emptyHotbarSlot < 0) throw new Error('No empty hotbar slot available for bare-hand harvesting');
    await bot.setQuickBarSlot(emptyHotbarSlot);
    await bot.waitForTicks(2);
    const logsBeforeDropRecovery = countItems(bot, name => name === 'birch_log');
    let harvested = logsBeforeDropRecovery > 0;
    if (harvested) {
      log('survival_log_acquisition_resumed', {
        birchLogCount: logsBeforeDropRecovery,
        source: 'persisted player inventory from an earlier survival session',
        position: bot.entity.position,
        inventory: inventory(bot)
      });
    }
    const priorLogDrop = harvested ? null : Object.values(bot.entities).find(entity => entity.name === 'item'
      && entity.getDroppedItem()?.name === 'birch_log'
      && entity.position.distanceTo(bot.entity.position) <= 6);
    if (priorLogDrop) {
      const dropPosition = priorLogDrop.position.floored();
      log('prior_session_log_drop_found', {
        position: priorLogDrop.position,
        count: priorLogDrop.getDroppedItem()?.count ?? 1,
        playerPosition: bot.entity.position
      });
      const dropPath = bot.pathfinder.goto(new pathfinderModule.goals.GoalBlock(
        dropPosition.x, dropPosition.y, dropPosition.z));
      dropPath.catch(error => log('prior_log_drop_route_ended', {reason: error.message}));
      const recoveredCount = await until(() => {
        const count = countItems(bot, name => name === 'birch_log');
        if (count > logsBeforeDropRecovery) return count;
      }, 'collect the prior-session birch log', 15000, () => clientFailure);
      bot.pathfinder.setGoal(null);
      log('tree_log_harvested', {
        block: 'birch_log',
        source: 'collected the server-dropped log from the previous normal survival session',
        dropPosition,
        positionAfterWalking: bot.entity.position,
        inventory: inventory(bot),
        health: bot.health,
        food: bot.food,
        logCountBefore: logsBeforeDropRecovery,
        logCountAfter: recoveredCount
      });
      harvested = true;
    }
    const protectionRadius = Number(properties['spawn-protection'] ?? 16);
    const spawnClearance = protectionRadius + 2;
    const logBlocks = bot.findBlocks({
      matching: block => block.name.endsWith('_log'),
      maxDistance: 80,
      count: 1000
    }).map(position => bot.blockAt(position)).filter(block => block
      && Math.max(Math.abs(block.position.x - bot.spawnPoint.x),
        Math.abs(block.position.z - bot.spawnPoint.z)) > spawnClearance
      && Math.abs(block.position.y - bot.entity.position.y) <= 8);
    const treeBases = new Map();
    for (const block of logBlocks) {
      const key = `${block.position.x},${block.position.z}`;
      const current = treeBases.get(key);
      if (!current || block.position.y < current.position.y) treeBases.set(key, block);
    }
    const candidates = [...treeBases.values()]
      .sort((first, second) => first.position.distanceTo(bot.entity.position)
        - second.position.distanceTo(bot.entity.position))
      .slice(0, 8);
    log('tree_candidates', {
      spawnClearance,
      trees: candidates.map(block => ({name: block.name, position: block.position}))
    });
    for (const candidate of candidates) {
      if (harvested) break;
      let originalBlock;
      try {
        originalBlock = await walkToVisibleBlock(bot, pathfinderModule.goals, [candidate], log);
      } catch (error) {
        log('tree_candidate_unreachable', {
          position: candidate.position,
          reason: error.message
        });
        continue;
      }

      const target = originalBlock.position;
      const logName = originalBlock.name;
      const countLogs = () => countItems(bot, name => name === logName);
      const beforeLogs = countLogs();
      log('tree_reached', {
        target: {name: logName, position: target},
        position: bot.entity.position,
        distance: bot.entity.position.offset(0, bot.entity.eyeHeight, 0)
          .distanceTo(target.offset(0.5, 0.5, 0.5)),
        canDig: bot.canDigBlock(originalBlock),
        canSee: bot.canSeeBlock(originalBlock),
        heldItem: bot.heldItem?.name ?? null,
        predictedDigTimeMs: bot.digTime(originalBlock),
        health: bot.health,
        food: bot.food
      });

      let serverReportsAir = false;
      try {
        if (!await serverHasBlock(target, logName)) {
          log('tree_candidate_block_changed', {position: target, expectedBlock: logName});
          continue;
        }
        await within(bot.dig(originalBlock, true, 'raycast'), `dig ${logName}`, 10000);
        serverReportsAir = !await waitForServerBlock(target, logName, 8000);
        log('tree_server_state_after_dig', {position: target, serverReportsAir});
        if (!serverReportsAir) {
          log('tree_candidate_break_denied', {position: target, block: logName});
          continue;
        }

        const pickup = await until(() => {
          if (countLogs() > beforeLogs) return {collected: true};
          const drop = Object.values(bot.entities).find(entity => entity.name === 'item'
            && entity.getDroppedItem()?.name === logName
            && entity.position.distanceTo(target.offset(0.5, 0.5, 0.5)) <= 6);
          if (drop) return {drop};
        }, 'log drop or inventory pickup', 5000, () => clientFailure);
        if (pickup.drop) {
          const dropPosition = pickup.drop.position.floored();
          const dropPath = bot.pathfinder.goto(new pathfinderModule.goals.GoalBlock(
            dropPosition.x, dropPosition.y, dropPosition.z));
          dropPath.catch(error => log('log_drop_route_ended', {position: dropPosition, reason: error.message}));
          await until(() => {
            const count = countLogs();
            if (count > beforeLogs) return count;
          }, 'collect the dropped log', 15000, () => clientFailure);
          bot.pathfinder.setGoal(null);
        }
        const afterLogs = await until(() => {
          const count = countLogs();
          if (count > beforeLogs) return count;
        }, 'picked-up tree log', 5000, () => clientFailure);
        if (bot.health <= 0) throw new Error('Player died during the harvest');
        log('tree_log_harvested', {
          block: logName,
          source: 'normal survival block break',
          position: target,
          positionAfterWalking: bot.entity.position,
          inventory: inventory(bot),
          health: bot.health,
          food: bot.food,
          logCountBefore: beforeLogs,
          logCountAfter: afterLogs
        });
        harvested = true;
        break;
      } catch (error) {
        if (serverReportsAir) throw error;
        log('tree_candidate_attempt_failed', {position: target, block: logName, reason: error.message});
      }
    }
    if (!harvested) throw new Error('No reachable natural log could be harvested in an unprotected area');
  } finally {
    if (bot) {
      if (bot.pathfinder) bot.pathfinder.setGoal(null);
      if (bot && !bot._client.ended) bot.end('survival acquisition acceptance complete');
    }
    if (!serverExit && java.pid) {
      java.stdin.write('stop\n');
      await within(serverClosed, 'Paper to stop after the stop command', 120000);
    }
    const portsClosed = await Promise.all([
      portClosed(gamePort),
      portClosed(rconPort)
    ]);
    const shutdown = {
      paperExit: serverExit,
      normalStopObserved: serverOutput.includes('Stopping server')
        && serverOutput.includes('All chunks are saved')
        && serverExit?.code === 0,
      gamePortClosed: portsClosed[0],
      rconPortClosed: portsClosed[1],
      stderrBytes: Buffer.byteLength(serverError)
    };
    log('paper_shutdown', shutdown);
    if (!shutdown.normalStopObserved || !shutdown.gamePortClosed || !shutdown.rconPortClosed) {
      throw new Error('Paper did not confirm a normal shutdown and closed listeners');
    }
  }
}

main().catch(error => {
  console.error(error.stack || String(error));
  process.exit(1);
});
