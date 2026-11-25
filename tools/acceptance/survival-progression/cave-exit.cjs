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

async function until(read, label, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = read();
    if (value) return value;
    await delay(50);
  }
  throw new Error(`Timed out waiting for ${label}`);
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

function countItems(bot, name) {
  return bot.inventory.items().filter(item => item.name === name)
    .reduce((total, item) => total + item.count, 0);
}

function sendPlayerInput(bot) {
  if (!bot.supportFeature('newPlayerInputPacket')) return;
  bot._client.write('player_input', {
    inputs: {
      forward: bot.getControlState('forward'),
      backward: bot.getControlState('back'),
      left: bot.getControlState('left'),
      right: bot.getControlState('right'),
      jump: bot.getControlState('jump'),
      shift: bot.getControlState('sneak'),
      sprint: bot.getControlState('sprint')
    }
  });
}

function setControlState(bot, control, state) {
  bot.setControlState(control, state);
  sendPlayerInput(bot);
}

async function main() {
  const [stageArg, mineflayerArg, pathfinderArg, username = 'SupplyCook', maxStepsArg] = process.argv.slice(2);
  if (!stageArg || !mineflayerArg || !pathfinderArg) {
    throw new Error('Usage: cave-exit.cjs <isolatedFoliaStage> <mineflayerModule> <pathfinderModule> [player]');
  }

  const stage = path.resolve(stageArg);
  const properties = readProperties(stage);
  const mineflayer = require(path.resolve(mineflayerArg));
  const pathfinderModule = require(path.resolve(pathfinderArg));
  const proofPath = path.join(stage, 'folia-proof', `survival-cave-exit-${Date.now()}.jsonl`);
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
  bot.on('physicsTick', () => sendPlayerInput(bot));

  let failure;
  bot.on('error', error => { failure = error; });
  bot.on('kicked', reason => { failure = new Error(`Kicked: ${reason}`); });

  try {
    await within(new Promise((resolve, reject) => {
      bot.once('spawn', resolve);
      bot.once('error', reject);
    }), 'player spawn', 45000);
    await within(bot.waitForChunksToLoad(), 'initial terrain', 15000);
    await delay(1200);
    if (bot.game.gameMode !== 'survival') throw new Error(`Player game mode is ${bot.game.gameMode}`);
    if (bot.health < 12 || bot.food < 8) throw new Error('Player health or food is too low for the cave exit');
    if (!bot.entity.onGround) throw new Error('Player is not standing on a stable block');

    const cobblestone = bot.registry.itemsByName.cobblestone;
    const startPosition = bot.entity.position.clone();
    const feet = startPosition.floored();
    const targetY = 82;
    const requiredLevels = targetY - feet.y;
    if (requiredLevels < 1 || requiredLevels > 60) throw new Error(`Unexpected cave exit height: ${requiredLevels}`);
    const stepCount = maxStepsArg === undefined ? requiredLevels : Number(maxStepsArg);
    if (!Number.isSafeInteger(stepCount) || stepCount < 1 || stepCount > requiredLevels) {
      throw new Error(`Step count must be between 1 and ${requiredLevels}`);
    }
    if (countItems(bot, 'cobblestone') < requiredLevels * 2) {
      throw new Error(`The planned staircase needs up to ${requiredLevels * 2} cobblestone blocks`);
    }
    const directions = [
      {x: 1, z: 0},
      {x: 0, z: -1},
      {x: -1, z: 0},
      {x: 0, z: 1}
    ];
    const Vec3 = bot.entity.position.constructor;
    const movements = new pathfinderModule.Movements(bot);
    movements.canDig = false;
    movements.canPlaceBlocks = false;
    movements.allowParkour = false;
    movements.allow1by1towers = false;
    bot.pathfinder.setMovements(movements);
    bot.pathfinder.thinkTimeout = 15000;
    bot.pathfinder.searchRadius = 16;

    log('survival_cave_exit_started', {
      player: username,
      gameMode: bot.game.gameMode,
      startPosition,
      targetY,
      requiredLevels,
      stepCount,
      cobblestoneCount: countItems(bot, 'cobblestone'),
      health: bot.health,
      food: bot.food,
      method: 'placed spiral stairs and hand-mined obstructing stone'
    });

    await within(bot.unequip('hand'), 'clear the hand before clearing headroom', 5000);
    for (let step = 0; step < stepCount; step++) {
      if (failure) throw failure;
      if (bot.health < 8 || bot.food < 4) throw new Error('Stopping the cave exit because health or food became unsafe');

      const current = bot.entity.position.floored();
      const direction = directions[step % directions.length];
      const nextX = current.x + direction.x;
      const nextZ = current.z + direction.z;
      const currentSupportPosition = current.offset(0, -1, 0);
      const currentSupport = bot.blockAt(currentSupportPosition);
      if (!currentSupport || currentSupport.boundingBox !== 'block') {
        throw new Error(`The current stair has no solid support at ${currentSupportPosition}`);
      }

      for (const height of [2, 3]) {
        const overheadPosition = current.offset(0, height, 0);
        const overheadBlock = bot.blockAt(overheadPosition);
        if (!overheadBlock || overheadBlock.name === 'water' || overheadBlock.name === 'lava') {
          throw new Error(`The current stair headroom is unavailable at ${overheadPosition}`);
        }
        if (overheadBlock.boundingBox === 'empty') continue;
        if (['sand', 'red_sand', 'gravel', 'suspicious_sand', 'suspicious_gravel'].includes(overheadBlock.name)) {
          throw new Error(`Refusing to hand-mine unstable ${overheadBlock.name} at ${overheadPosition}`);
        }
        if (!bot.canDigBlock(overheadBlock)) {
          throw new Error(`The current stair headroom is blocked by an unbreakable ${overheadBlock.name} at ${overheadPosition}`);
        }
        await within(bot.unequip('hand'), 'clear the hand before mining overhead stone', 5000);
        log('survival_stair_overhead_mining_started', {
          step,
          block: overheadBlock.name,
          position: overheadPosition,
          playerPosition: bot.entity.position.clone()
        });
        await within(bot.dig(overheadBlock, true, 'raycast'),
          `hand-mine overhead ${overheadBlock.name} at ${overheadPosition}`, 30000);
        await until(() => bot.blockAt(overheadPosition)?.boundingBox === 'empty',
          `clear current stair headroom at ${overheadPosition}`, 5000);
        log('survival_stair_overhead_cleared', {step, block: overheadBlock.name, position: overheadPosition});
      }

      const basePosition = current.offset(direction.x, -1, direction.z);
      let baseBlock = bot.blockAt(basePosition);
      if (!baseBlock || baseBlock.name === 'water' || baseBlock.name === 'lava') {
        throw new Error(`Cannot build the next stair support at ${basePosition}`);
      }
      if (baseBlock.boundingBox !== 'block') {
        const countBefore = countItems(bot, 'cobblestone');
        const item = bot.inventory.items().find(entry => entry.name === 'cobblestone');
        if (!item) throw new Error('Cobblestone ran out while building the cave exit');
        await within(bot.equip(item, 'hand'), 'equip a stair support block', 5000);
        await within(bot.placeBlock(currentSupport, new Vec3(direction.x, 0, direction.z)),
          `place the stair support at ${basePosition}`, 10000);
        baseBlock = await until(() => {
          const block = bot.blockAt(basePosition);
          if (block?.boundingBox === 'block') return block;
        }, `stair support at ${basePosition}`, 5000);
        await until(() => countItems(bot, 'cobblestone') === countBefore - 1,
          'confirm one cobblestone consumed by stair support placement', 5000);
      }

      const stairPosition = current.offset(direction.x, 0, direction.z);
      let stairBlock = bot.blockAt(stairPosition);
      if (!stairBlock || stairBlock.name === 'water' || stairBlock.name === 'lava') {
        throw new Error(`Cannot build the next stair at ${stairPosition}`);
      }
      if (stairBlock.boundingBox !== 'block') {
        const countBefore = countItems(bot, 'cobblestone');
        const item = bot.inventory.items().find(entry => entry.name === 'cobblestone');
        if (!item) throw new Error('Cobblestone ran out while building the cave exit');
        await within(bot.equip(item, 'hand'), 'equip a stair block', 5000);
        await within(bot.placeBlock(baseBlock, new Vec3(0, 1, 0)),
          `place the stair at ${stairPosition}`, 10000);
        stairBlock = await until(() => {
          const block = bot.blockAt(stairPosition);
          if (block?.boundingBox === 'block') return block;
        }, `stair block at ${stairPosition}`, 5000);
        await until(() => countItems(bot, 'cobblestone') === countBefore - 1,
          'confirm one cobblestone consumed by stair placement', 5000);
      }

      const destination = stairPosition.offset(0, 1, 0);
      for (let height = 0; height < 3; height++) {
        const position = destination.offset(0, height, 0);
        const block = bot.blockAt(position);
        if (!block || block.name === 'water' || block.name === 'lava') {
          throw new Error(`The next stair passage is unavailable at ${position}`);
        }
        if (block.boundingBox === 'empty') continue;
        if (['sand', 'red_sand', 'gravel', 'suspicious_sand', 'suspicious_gravel'].includes(block.name)) {
          throw new Error(`Refusing to hand-mine unstable ${block.name} at ${position}`);
        }
        if (!bot.canDigBlock(block)) throw new Error(`The passage is blocked by an unbreakable ${block.name} at ${position}`);
        await within(bot.unequip('hand'), 'clear the hand before mining stone', 5000);
        log('survival_stair_obstruction_mining_started', {
          step,
          block: block.name,
          position,
          playerPosition: bot.entity.position.clone(),
          cobblestoneCount: countItems(bot, 'cobblestone')
        });
        await within(bot.dig(block, true, 'raycast'), `hand-mine ${block.name} at ${position}`, 30000);
        await until(() => bot.blockAt(position)?.boundingBox === 'empty', `clear passage at ${position}`, 5000);
        log('survival_stair_obstruction_cleared', {step, block: block.name, position});
      }

      const targetFeet = destination;
      const currentCenter = current.offset(0.5, 0, 0.5);
      const approachPosition = currentCenter.offset(direction.x * 0.15, 0, direction.z * 0.15);
      const currentPosition = bot.entity.position;
      if (Math.hypot(currentPosition.x - approachPosition.x, currentPosition.z - approachPosition.z) > 0.06) {
        await within(bot.lookAt(approachPosition.offset(
          0,
          currentPosition.y + bot.entity.eyeHeight - approachPosition.y,
          0)), 'face the next stair approach point', 5000);
        setControlState(bot, 'sneak', true);
        setControlState(bot, 'forward', true);
        try {
          await until(() => {
            const position = bot.entity.position;
            if (Math.hypot(position.x - approachPosition.x, position.z - approachPosition.z) <= 0.04) return true;
          }, `approach the next stair from ${current}`, 5000);
        } finally {
          setControlState(bot, 'forward', false);
          setControlState(bot, 'sneak', false);
        }
      }
      await within(bot.waitForTicks(4), 'horizontal movement to settle before a stair jump', 2000);
      const settledPosition = bot.entity.position;
      if (Math.hypot(settledPosition.x - approachPosition.x, settledPosition.z - approachPosition.z) > 0.15) {
        throw new Error(`The player did not settle at the approach point for stair ${targetFeet}`);
      }

      const targetCenter = targetFeet.offset(0.5, 0, 0.5);
      log('survival_stair_step_started', {
        step,
        from: bot.entity.position.clone(),
        approachPosition,
        target: targetFeet,
        targetCenter,
        stair: stairPosition,
        remainingCobblestone: countItems(bot, 'cobblestone')
      });
      const lookStart = bot.entity.position.clone();
      const expectedYaw = Math.atan2(
        -(targetCenter.x - lookStart.x),
        -(targetCenter.z - lookStart.z)
      );
      await within(bot.look(expectedYaw, 0), `face survival stair to y=${targetFeet.y}`, 5000);
      await until(() => Math.abs(Math.atan2(
        Math.sin(bot.entity.yaw - expectedYaw),
        Math.cos(bot.entity.yaw - expectedYaw)
      )) <= 0.08, `align to survival stair to y=${targetFeet.y}`, 5000);
      await within(bot.waitForTicks(2), 'stair direction to settle before jumping', 1500);
      const jumpPosition = bot.entity.position;
      if (Math.hypot(jumpPosition.x - approachPosition.x, jumpPosition.z - approachPosition.z) > 0.15) {
        throw new Error(`The player drifted from the approach point for stair ${targetFeet}`);
      }
      log('survival_stair_jump_started', {
        step,
        position: bot.entity.position.clone(),
        velocity: bot.entity.velocity,
        yaw: bot.entity.yaw,
        expectedYaw,
        pitch: bot.entity.pitch,
        onGround: bot.entity.onGround,
        direction
      });
      const jumpStartY = bot.entity.position.y;
      setControlState(bot, 'jump', false);
      setControlState(bot, 'jump', true);
      try {
        const jumpCell = bot.entity.position.floored();
        const jumpPosition = bot.entity.position;
        const minX = Math.floor(jumpPosition.x - 0.3);
        const maxX = Math.floor(jumpPosition.x + 0.3 - 1e-6);
        const minZ = Math.floor(jumpPosition.z - 0.3);
        const maxZ = Math.floor(jumpPosition.z + 0.3 - 1e-6);
        const nearbyHeadroomBlocks = [];
        for (let y = jumpCell.y + 1; y <= jumpCell.y + 3; y++) {
          for (let x = minX; x <= maxX; x++) {
            for (let z = minZ; z <= maxZ; z++) {
              const position = new Vec3(x, y, z);
              const block = bot.blockAt(position);
              if (block && block.boundingBox !== 'empty') {
                nearbyHeadroomBlocks.push({position, name: block.name});
              }
            }
          }
        }
        log('survival_stair_jump_input_queued', {
          step,
          physicsEnabled: bot.physicsEnabled,
          jumpQueued: bot.jumpQueued,
          jumpTicks: bot.jumpTicks,
          controlState: {
            forward: bot.getControlState('forward'),
            jump: bot.getControlState('jump'),
            sneak: bot.getControlState('sneak')
          },
          onGround: bot.entity.onGround,
          support: bot.blockAt(jumpCell.offset(0, -1, 0))?.name,
          feetSpace: bot.blockAt(jumpCell)?.name,
          headSpace: bot.blockAt(jumpCell.offset(0, 1, 0))?.name,
          upperHeadSpace: bot.blockAt(jumpCell.offset(0, 2, 0))?.name,
          nearbyHeadroomBlocks,
          targetStair: bot.blockAt(stairPosition)?.name,
          targetHeadSpace: bot.blockAt(targetFeet)?.name,
          targetUpperHeadSpace: bot.blockAt(targetFeet.offset(0, 1, 0))?.name
        });
        await until(() => bot.entity.position.y >= targetFeet.y + 0.05,
          `rise above survival stair y=${targetFeet.y} before moving`, 2500);
        log('survival_stair_jump_physics_sample', {
          step,
          physicsEnabled: bot.physicsEnabled,
          jumpQueued: bot.jumpQueued,
          jumpTicks: bot.jumpTicks,
          position: bot.entity.position.clone(),
          velocity: bot.entity.velocity,
          onGround: bot.entity.onGround
        });
        if (bot.entity.position.y <= jumpStartY + 0.1) {
          throw new Error(`Jump did not lift the player toward stair level ${targetFeet.y}`);
        }
        log('survival_stair_jump_liftoff_confirmed', {
          step,
          jumpStartY,
          position: bot.entity.position.clone(),
          velocity: bot.entity.velocity
        });
        setControlState(bot, 'jump', false);
        setControlState(bot, 'forward', true);
        const deadline = Date.now() + 8000;
        let nextSampleAt = Date.now();
        while (Date.now() < deadline) {
          if (failure) throw failure;
          const position = bot.entity.position;
          const horizontalDistance = Math.hypot(
            position.x - targetCenter.x,
            position.z - targetCenter.z
          );
          if (horizontalDistance <= 0.3 && Math.abs(position.y - targetFeet.y) <= 0.25) break;
          if (Date.now() >= nextSampleAt) {
            log('survival_stair_jump_sample', {
              step,
              position: position.clone(),
              velocity: bot.entity.velocity,
              onGround: bot.entity.onGround,
              horizontalDistance,
              yaw: bot.entity.yaw,
              pitch: bot.entity.pitch
            });
            nextSampleAt = Date.now() + 500;
          }
          await delay(50);
        }
        const finalPosition = bot.entity.position;
        if (Math.hypot(finalPosition.x - targetCenter.x, finalPosition.z - targetCenter.z) > 0.3
            || Math.abs(finalPosition.y - targetFeet.y) > 0.25) {
          throw new Error(`Timed out waiting for climb survival stair to y=${targetFeet.y}`);
        }
      } finally {
        setControlState(bot, 'forward', false);
        setControlState(bot, 'jump', false);
      }
      await until(() => bot.entity.onGround && Math.floor(bot.entity.position.y) === targetFeet.y,
        `land safely on survival stair y=${targetFeet.y}`, 6000);
      await within(bot.waitForTicks(10), 'stair landing to settle', 2500);
      if (Math.floor(bot.entity.position.y) !== targetFeet.y) {
        throw new Error(`The player did not reach stair level ${targetFeet.y}`);
      }
      const landingSupport = bot.blockAt(bot.entity.position.floored().offset(0, -1, 0));
      if (!landingSupport || landingSupport.boundingBox !== 'block') {
        throw new Error(`The player did not settle above a solid stair at y=${targetFeet.y}`);
      }
      log('survival_stair_step_reached', {
        step,
        position: bot.entity.position.clone(),
        onGround: bot.entity.onGround,
        landingSupport: landingSupport.name,
        velocity: bot.entity.velocity,
        health: bot.health,
        food: bot.food,
        cobblestoneCount: countItems(bot, 'cobblestone')
      });
    }

    log(bot.entity.position.y >= targetY
      ? 'survival_cave_exit_reached_surface_level'
      : 'survival_stair_sequence_test_completed', {
        startPosition,
        endPosition: bot.entity.position.clone(),
        completedSteps: stepCount,
        health: bot.health,
        food: bot.food,
        cobblestoneCount: countItems(bot, 'cobblestone')
      });
  } catch (error) {
    log('survival_cave_exit_failed', {
      reason: error.message,
      position: bot.entity?.position?.clone() ?? null,
      health: bot.health,
      food: bot.food,
      cobblestoneCount: countItems(bot, 'cobblestone')
    });
    throw error;
  } finally {
    if (bot.pathfinder) bot.pathfinder.setGoal(null);
    if (!bot._client.ended) bot.end('survival cave exit acceptance step complete');
  }
}

main().catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
