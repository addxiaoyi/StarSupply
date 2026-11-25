const fs = require('node:fs');
const path = require('node:path');
const {createHash} = require('node:crypto');
const helpers = require('./iron-pickaxe.cjs');
const {installServerDigConfirmation} = require('./server-dig.cjs');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));

async function main() {
  const [stage, mineflayerModule, pathfinderModule, username = 'SupplyCook', phase = 'mining'] = process.argv.slice(2);
  const mineflayer = require(mineflayerModule), pf = require(pathfinderModule);
  const Vec3 = require(path.join(path.dirname(mineflayerModule), 'vec3')).Vec3;
  const properties = Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
    .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  if (properties['server-ip'] !== '127.0.0.1' || properties['online-mode'] !== 'false') throw new Error('Isolated loopback stage required');
  const proof = path.join(stage, 'folia-proof', `six-resource-${phase}-${Date.now()}.jsonl`);
  const log = (event, data = {}) => {
    const row = {event, at: new Date().toISOString(), phase, ...data};
    fs.appendFileSync(proof, JSON.stringify(row) + '\n'); console.log(JSON.stringify(row));
  };
  const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(properties['server-port']), username,
    auth: 'offline', version: '1.21.11', viewDistance: 'short', checkTimeoutInterval: 90000});
  bot.loadPlugin(pf.pathfinder);
  let failure;
  bot.on('error', error => { failure = error; });
  bot.on('kicked', reason => { failure = new Error(`Kicked: ${JSON.stringify(reason)}`); });
  bot.on('death', () => { failure = new Error('Survival player died; saved progress requires recovery'); bot.pathfinder.setGoal(null); });
  bot.on('messagestr', message => log('message', {message}));
  const count = name => bot.inventory.items().filter(item => item.name === name).reduce((n, item) => n + item.count, 0);
  const inventory = () => bot.inventory.items().map(item => ({slot: item.slot, name: item.name, count: item.count}));
  async function until(read, label, timeout = 10000) {
    const deadline = Date.now() + timeout;
    while (Date.now() < deadline) {
      if (failure) throw failure;
      const value = read(); if (value) return value;
      await delay(100);
    }
    throw new Error(`Timed out waiting for ${label}`);
  }
  async function within(action, label, timeout = 20000) {
    let timer;
    try { return await Promise.race([action, new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`Timed out: ${label}`)), timeout);
    })]); } finally { clearTimeout(timer); }
  }
  async function move(position, radius = 2, timeout = 60000) {
    log('walk_started', {target: position, from: bot.entity.position.clone()});
    try { await within(bot.pathfinder.goto(new pf.goals.GoalNear(position.x, position.y, position.z, radius)), 'survival walking', timeout); }
    finally { bot.pathfinder.setGoal(null); bot.clearControlStates(); }
    log('walk_finished', {position: bot.entity.position.clone(), health: bot.health, food: bot.food});
  }
  async function travel(position, radius = 3) {
    for (let step = 0; step < 160; step++) {
      const from = bot.entity.position.clone();
      if (from.distanceTo(position) <= radius + 1) return;
      const horizontal = Math.hypot(position.x - from.x, position.z - from.z);
      if (horizontal < 10) { await move(position, radius, 20000); return; }
      const nextX = Math.floor(from.x + (position.x - from.x) * 7 / horizontal);
      const nextZ = Math.floor(from.z + (position.z - from.z) * 7 / horizontal);
      const candidates = [];
      for (let x = nextX - 2; x <= nextX + 2; x++) for (let z = nextZ - 2; z <= nextZ + 2; z++) {
        for (let y = Math.floor(from.y) + 7; y >= Math.floor(from.y) - 6; y--) {
          const feet = new Vec3(x, y, z), support = bot.blockAt(feet.offset(0, -1, 0));
          const foot = bot.blockAt(feet), head = bot.blockAt(feet.offset(0, 1, 0));
          if (support?.boundingBox === 'block' && foot?.boundingBox === 'empty' && head?.boundingBox === 'empty'
              && ![foot.name, head.name, support.name].some(name => /water|lava|campfire|cactus|magma/.test(name))) {
            candidates.push(feet); break;
          }
        }
      }
      candidates.sort((a, b) => a.distanceTo(position) + Math.abs(a.y - from.y) * 0.3
        - b.distanceTo(position) - Math.abs(b.y - from.y) * 0.3);
      let reached = false;
      for (const candidate of candidates.slice(0, 4)) {
        try { await move(candidate, 1, 15000); reached = true; break; }
        catch (error) { log('waypoint_failed', {candidate, reason: error.message}); }
      }
      if (!reached || bot.entity.position.distanceTo(from) < 1) throw new Error(`No safe progress toward ${position}`);
    }
    throw new Error(`Survival journey exceeded its bounded waypoint count: ${position}`);
  }
  async function ascendToSurface(y = 75) {
    while (Math.floor(bot.entity.position.y) < y) {
      const feet = bot.entity.position.floored();
      for (const height of [2, 3]) {
        const ceiling = bot.blockAt(feet.offset(0, height, 0));
        if (!ceiling || ['water', 'lava'].includes(ceiling.name)) throw new Error('Unsafe ceiling on survival ascent');
        if (ceiling.boundingBox !== 'empty') {
          await equip('iron_pickaxe');
          if (!helpers.hasDirectRay(bot, ceiling)) throw new Error('No clear ceiling ray on survival ascent');
          await within(bot.dig(ceiling, true, 'auto'), 'open ascent headroom', 12000);
          await delay(200);
        }
      }
      const support = bot.blockAt(feet.offset(0, -1, 0));
      if (support?.boundingBox !== 'block') throw new Error('No solid support for survival ascent');
      const blockName = count('dirt') ? 'dirt' : 'cobblestone';
      await equip(blockName);
      bot.setControlState('jump', true);
      try {
        await until(() => bot.entity.position.y >= feet.y + 1.05, 'jump above pillar placement', 3000);
        await helpers.placeBlockEventually(bot, support, new Vec3(0, 1, 0), blockName, 'survival ascent pillar', log);
      } finally { bot.setControlState('jump', false); }
      await until(() => bot.entity.onGround && bot.entity.position.y >= feet.y + 1, 'land on placed survival pillar', 5000);
      log('ascent_step', {position: bot.entity.position.clone(), blockName});
    }
  }
  async function descendTo(y) {
    for (let step = 0; step < 160 && bot.entity.position.y > y + 0.5; step++) {
      const feet = bot.entity.position.floored(), support = bot.blockAt(feet.offset(0, -1, 0));
      if (!support || support.boundingBox !== 'block' || /sand|gravel/.test(support.name)) throw new Error('Unsafe descending support');
      let landing;
      for (let depth = 2; depth <= 4; depth++) {
        const candidate = bot.blockAt(feet.offset(0, -depth, 0));
        if (!candidate || /lava|water|campfire|magma/.test(candidate.name)) throw new Error('Unsafe descending landing');
        if (candidate.boundingBox === 'block') { landing = candidate; break; }
      }
      if (!landing || landing.position.y + 1 < y - 1) throw new Error('No short supported drop toward the resource level');
      await equip('iron_pickaxe');
      if (!helpers.hasDirectRay(bot, support)) throw new Error('No clear descent digging ray');
      await bot.dig(support, true, 'auto');
      await until(() => bot.entity.onGround && bot.entity.position.y < feet.y - 0.5, 'land after survival descent', 7000);
      log('descent_step', {position: bot.entity.position.clone(), health: bot.health});
    }
  }
  async function equip(name) {
    const item = bot.inventory.items().find(item => item.name === name);
    if (!item) throw new Error(`Missing tool ${name}`);
    await within(bot.equip(item, 'hand'), `equip ${name}`, 5000);
  }
  // Mineflayer predicts air when its dig timer ends. Every explicit tunnel/harvest
  // operation must also receive an authoritative block packet from the server.
  async function clearWalk(target, stop = () => false) {
    bot.pathfinder.setGoal(null); bot.clearControlStates();
    for (let step = 0; step < 24 && !stop(); step++) {
      const from = bot.entity.position.clone(), feet = from.floored();
      if (Math.hypot(target.x - from.x, target.z - from.z) < 0.65 && Math.abs(target.y - from.y) < 1.6) return;
      const dx = target.x - from.x, dz = target.z - from.z;
      const mx = Math.abs(dx) > Math.abs(dz) ? Math.sign(dx) : 0;
      const mz = mx ? 0 : Math.sign(dz);
      if (!mx && !mz) return;
      let y = feet.y + (target.y >= feet.y + 1 ? 1 : target.y < feet.y - 0.5 ? -1 : 0);
      let next = new Vec3(feet.x + mx, y, feet.z + mz);
      let support = bot.blockAt(next.offset(0, -1, 0));
      if (!support || support.boundingBox !== 'block' || /lava|water|sand|gravel/.test(support.name)) {
        throw new Error(`Unsupported collection step at ${next}: ${support?.name}`);
      }
      for (let height = Math.max(feet.y + 1, next.y + 1); height >= next.y; height--) {
        const position = new Vec3(next.x, height, next.z);
        let block = bot.blockAt(position);
        if (!block || /lava|water/.test(block.name)) throw new Error('Unsafe collection corridor');
        if (block.boundingBox === 'empty') continue;
        if (!helpers.hasDirectRay(bot, block)) {
          await helpers.exposeBlockToRay(bot, block, [block.name], log, 'collection', {maxAttempts: 5, centerRay: true});
          block = bot.blockAt(position);
        }
        if (!block || !helpers.hasDirectRay(bot, block)) throw new Error(`No collection corridor digging ray at ${position}`);
        await equip('iron_pickaxe');
        await bot.dig(block, true, 'auto');
      }
      await bot.lookAt(next.offset(0.5, from.y + 1.2 - next.y, 0.5), true);
      bot.setControlState('forward', true);
      if (next.y > feet.y) bot.setControlState('jump', true);
      try {
        await until(() => stop() || (Math.hypot(bot.entity.position.x - next.x - 0.5,
          bot.entity.position.z - next.z - 0.5) < 0.3 && Math.abs(bot.entity.position.y - next.y) < 0.3),
        'walk into the server-cleared corridor', 4500);
      } finally { bot.clearControlStates(); }
      await delay(200);
      log('collection_step', {position: bot.entity.position.clone(), target});
    }
  }
  async function approach(block) {
    if (bot.canDigBlock(block) && helpers.hasDirectRay(bot, block)) return;
    const horizontal = Math.hypot(block.position.x + 0.5 - bot.entity.position.x,
      block.position.z + 0.5 - bot.entity.position.z);
    if (block.position.y < Math.floor(bot.entity.position.y) - 2 && horizontal <= 12) {
      await helpers.descendAboveIronOre(bot, block, log, {centerRay: true});
    }
    await helpers.tunnelToOre(bot, block, pf,
      (event, data) => log(event.replace('iron_ore_tunnel', 'resource_tunnel'), {block: block.name, ...data}),
      [block.name], {maxHorizontal: 12, minVertical: -2, maxSteps: 16, headFirst: true, requireRay: true, centerRay: true});
    block = bot.blockAt(block.position);
    if (block && bot.canDigBlock(block) && !helpers.hasDirectRay(bot, block)) {
      await helpers.exposeBlockToRay(bot, block, [block.name], log, 'resource', {maxAttempts: 8, centerRay: true});
      block = bot.blockAt(block.position);
    }
    if (block && bot.canDigBlock(block) && helpers.hasDirectRay(bot, block)) return;
    try {
      await within(bot.pathfinder.goto(new pf.goals.GoalLookAtBlock(block.position, bot.world,
        {reach: 3.8, entityHeight: bot.entity.eyeHeight})), `approach ${block.name}`, 8000);
    } finally { bot.pathfinder.setGoal(null); }
  }
  async function collect(dropName, before, target) {
    await delay(300);
    if (count(dropName) > before) return;
    const drop = await until(() => Object.values(bot.entities).find(entity => entity.name === 'item'
      && entity.getDroppedItem()?.name === dropName && entity.position.distanceTo(target) < 8), `observe ${dropName} drop`, 7000);
    log('drop_observed', {dropName, position: drop.position.clone(), player: bot.entity.position.clone()});
    try { await clearWalk(drop.position, () => count(dropName) > before); }
    catch (error) { log('collection_corridor_failed', {reason: error.message}); }
    if (count(dropName) > before) return;
    bot.pathfinder.setGoal(new pf.goals.GoalFollow(drop, 0.7), true);
    try { await until(() => count(dropName) > before, `collect ${dropName}`, 15000); }
    finally { bot.pathfinder.setGoal(null); bot.clearControlStates(); }
  }
  async function gather(blockNames, dropName, desired, tool, maxDistance = 24) {
    let failures = 0;
    while (count(dropName) < desired) {
      const existing = Object.values(bot.entities).find(entity => entity.name === 'item'
        && entity.getDroppedItem()?.name === dropName && entity.position.distanceTo(bot.entity.position) < 12);
      if (existing) {
        try { await collect(dropName, count(dropName), existing.position); }
        catch (error) { log('existing_drop_unreachable', {dropName, reason: error.message}); }
        if (count(dropName) >= desired) return;
      }
      const candidates = bot.findBlocks({matching: block => blockNames.includes(block.name), maxDistance, count: 80})
        .sort((a, b) => a.distanceTo(bot.entity.position) - b.distanceTo(bot.entity.position));
      let gathered = false;
      for (const position of candidates) {
        if (failures > 12) throw new Error(`Too many failed ${dropName} mining attempts`);
        try {
          let block = bot.blockAt(position);
          if (tool) await equip(tool);
          if (bot.canDigBlock(block) && !helpers.hasDirectRay(bot, block)) {
            await helpers.exposeBlockToRay(bot, block, blockNames, log, dropName, {maxAttempts: 8, centerRay: true});
          }
          block = bot.blockAt(position);
          if (!block || !blockNames.includes(block.name)) continue;
          await approach(block);
          block = bot.blockAt(position);
          if (tool) await equip(tool);
          const before = count(dropName);
          if (!helpers.hasDirectRay(bot, block)) throw new Error(`No clear survival digging ray to ${block.name}`);
          log('harvest_started', {block: block.name, position, before, held: bot.heldItem?.name, player: bot.entity.position.clone()});
          await within(bot.dig(block, true, 'auto'), `harvest ${block.name}`, 20000);
          await until(() => !blockNames.includes(bot.blockAt(position)?.name), 'server block removal');
          await collect(dropName, before, position.offset(0.5, 0.5, 0.5));
          log('harvest_collected', {drop: dropName, before, after: count(dropName), position, inventory: inventory()});
          gathered = true; break;
        } catch (error) {
          bot.pathfinder.setGoal(null); bot.clearControlStates(); failures++;
          log('harvest_attempt_failed', {drop: dropName, position, player: bot.entity.position.clone(),
            nodeHeapMiB: Math.round(process.memoryUsage().heapUsed / 1048576), reason: error.message});
          if (failure) throw failure;
        }
      }
      if (!gathered) throw new Error(`No reachable ${dropName} source within ${maxDistance} blocks`);
    }
  }
  async function workbench() {
    let block = bot.findBlock({matching: block => block.name === 'crafting_table', maxDistance: 12});
    if (!block) {
      if (!count('crafting_table') && count('birch_planks') >= 4) {
        const recipe = bot.recipesFor(bot.registry.itemsByName.crafting_table.id, null, 1, null)[0];
        if (!recipe) throw new Error('No personal-grid workbench recipe');
        await bot.craft(recipe, 1, null);
        await until(() => count('crafting_table') > 0, 'craft travel workbench');
      }
      const support = helpers.findPlacementSupport(bot, bot.entity.position.floored());
      if (!support) throw new Error('No supported workbench placement');
      await equip('crafting_table');
      await helpers.placeBlockEventually(bot, support, new Vec3(0, 1, 0), 'crafting_table', 'place survival workbench', log);
      block = bot.blockAt(support.position.offset(0, 1, 0));
    }
    await approach(block); return block;
  }
  async function craft(output, ingredients, repetitions = 1) {
    const Item = require(path.join(path.dirname(mineflayerModule), 'prismarine-item'))(bot.registry);
    const table = await workbench();
    for (let repetition = 0; repetition < repetitions; repetition++) {
      const before = count(output), window = await bot.openBlock(table);
      let confirmedOutput = false, confirmedInventory = false;
      const onSlot = packet => {
        const item = Item.fromNotch(packet.item);
        if (item?.name !== output) return;
        if (packet.windowId === window.id && packet.slot === 0) confirmedOutput = true;
        if (packet.windowId === window.id && packet.slot >= window.inventoryStart) confirmedInventory = true;
      };
      const onItems = packet => {
        if (packet.windowId !== window.id) return;
        if (Item.fromNotch(packet.items[0])?.name === output) confirmedOutput = true;
        if (packet.items.slice(window.inventoryStart).some(item => Item.fromNotch(item)?.name === output)) confirmedInventory = true;
      };
      bot._client.on('set_slot', onSlot); bot._client.on('window_items', onItems);
      try {
        for (const [name, slots] of ingredients) {
          for (const slot of slots) {
            const item = window.slots.slice(window.inventoryStart, window.inventoryEnd).find(item => item?.name === name);
            if (!item) throw new Error(`Missing ${name} for ${output}`);
            const source = item.slot;
            await bot.clickWindow(source, 0, 0); await delay(150);
            await bot.clickWindow(slot, 1, 0); await delay(150);
            if (window.selectedItem) { await bot.clickWindow(source, 0, 0); await delay(150); }
          }
        }
        await until(() => confirmedOutput && window.slots[0]?.name === output, `server ${output} recipe`);
        confirmedInventory = false;
        await bot.clickWindow(0, 0, 0); await delay(150);
        const destination = window.slots.findIndex((item, slot) => slot >= window.inventoryStart && slot < window.inventoryEnd && !item);
        if (destination < 0) throw new Error('No room for crafted output');
        await bot.clickWindow(destination, 0, 0);
        await until(() => confirmedInventory, `server ${output} inventory`);
      } finally {
        bot._client.off('set_slot', onSlot); bot._client.off('window_items', onItems);
        if (bot.currentWindow === window) bot.closeWindow(window);
      }
      await delay(500);
      if (count(output) <= before) throw new Error(`Crafted ${output} was not retained`);
      log('crafted', {output, ingredients, before, after: count(output), inventory: inventory()});
    }
  }
  async function furnace() {
    let block = bot.findBlock({matching: block => block.name === 'furnace'
      && Math.abs(block.position.y - bot.entity.position.y) <= 3, maxDistance: 12});
    if (!block) {
      if (!count('furnace')) await craft('furnace', [['cobblestone', [1, 2, 3, 4, 6, 7, 8, 9]]]);
      const placement = helpers.findAdjacentPlacement(bot, bot.entity.position.floored())
        ?? await helpers.openFurnaceAlcove(bot, Vec3, log);
      if (!placement) throw new Error('No supported furnace placement');
      await equip('furnace');
      await helpers.placeBlockEventually(bot, placement.block, placement.face, 'furnace', 'place survival furnace', log);
      block = bot.blockAt(placement.target);
    }
    await approach(block); return block;
  }
  async function smelt(input, output, desired) {
    if (count(output) >= desired) return;
    const block = await furnace(), window = await bot.openFurnace(block);
    try {
      const needed = desired - count(output);
      if (window.outputItem()) {
        if (window.outputItem().name !== output) throw new Error(`Furnace has different output: ${window.outputItem().name}`);
        await window.takeOutput();
      }
      const remaining = desired - count(output);
      if (remaining <= 0) return;
      if (!window.fuelItem() && !window.fuel) {
        await window.putFuel(bot.registry.itemsByName.coal.id, null, Math.ceil(remaining / 8));
      }
      if (!window.inputItem()) await window.putInput(bot.registry.itemsByName[input].id, null, remaining);
      log('smelting_started', {input, output, needed, position: block.position});
      await until(() => window.outputItem()?.name === output && window.outputItem().count >= remaining,
        `smelt ${remaining} ${output}`, remaining * 12000 + 30000);
      await window.takeOutput();
      log('smelting_collected', {output, count: count(output), inventory: inventory()});
    } finally { window.close(); }
    await delay(500);
    if (count(output) < desired) throw new Error(`Smelting output did not persist: ${output}`);
  }

  async function farmBread() {
    if (count('bread') >= 6 && count('sugar_cane') >= 3) return;
    await travel(new Vec3(-60, 65, -1), 4);
    await gather(['sugar_cane'], 'sugar_cane', 3, null, 16);
    const farm = bot.findBlocks({matching: block => block.name === 'wheat', maxDistance: 12, count: 8});
    const soils = bot.findBlocks({matching: block => ['dirt', 'grass_block', 'farmland'].includes(block.name), maxDistance: 12, count: 200});
    for (const position of soils) {
      if (farm.length >= 8) break;
      const above = bot.blockAt(position.offset(0, 1, 0));
      if (!above || above.name !== 'air') continue;
      let hydrated = false;
      for (let x = -4; x <= 4; x++) for (let z = -4; z <= 4; z++) {
        if (bot.blockAt(position.offset(x, 0, z))?.name === 'water') hydrated = true;
      }
      if (!hydrated) continue;
      const soil = bot.blockAt(position);
      await approach(soil);
      if (soil.name !== 'farmland') {
        await equip('wooden_hoe'); await bot.activateBlock(soil);
        await until(() => bot.blockAt(position)?.name === 'farmland', 'server tilled farmland');
      }
      if (!count('wheat_seeds')) break;
      await equip('wheat_seeds');
      await helpers.placeBlockEventually(bot, bot.blockAt(position), new Vec3(0, 1, 0), 'wheat', 'plant survival wheat', log);
      farm.push(position.offset(0, 1, 0));
    }
    if (!farm.length) throw new Error('No reachable hydrated farmland for wheat');
    const deadline = Date.now() + 1800000;
    while (count('wheat') + count('bread') * 3 < 18) {
      if (Date.now() >= deadline) throw new Error('Wheat is still growing; saved crops can resume next run');
      for (const position of farm) {
        let crop = bot.blockAt(position);
        if (crop?.name !== 'wheat') continue;
        await approach(crop);
        while (Number(crop.getProperties().age) < 7 && count('bone_meal')) {
          const before = count('bone_meal');
          await equip('bone_meal'); await bot.activateBlock(crop);
          await until(() => count('bone_meal') < before, 'server bone meal use');
          crop = bot.blockAt(position);
          log('crop_growing', {position, age: crop.getProperties().age, boneMeal: count('bone_meal')});
        }
        if (Number(crop.getProperties().age) !== 7) continue;
        const before = count('wheat');
        await bot.dig(crop, true, 'auto'); await collect('wheat', before, position.offset(0.5, 0.5, 0.5));
        log('wheat_harvested', {position, count: count('wheat')});
        if (count('wheat_seeds')) {
          await equip('wheat_seeds');
          await helpers.placeBlockEventually(bot, bot.blockAt(position.offset(0, -1, 0)), new Vec3(0, 1, 0), 'wheat', 'replant survival wheat', log);
        }
      }
      if (count('wheat') + count('bread') * 3 < 18) await delay(10000);
    }
    while (count('bread') < 6) await craft('bread', [['wheat', [4, 5, 6]]]);
  }

  async function harvestHoney() {
    for (const coordinates of [[-6, 86, 91], [-87, 73, 117], [60, 89, 140]]) {
      if (count('honey_bottle') >= 3) return;
      const position = new Vec3(...coordinates);
      await travel(position, 3);
      const nest = bot.blockAt(position);
      if (!nest || !['bee_nest', 'beehive'].includes(nest.name) || Number(nest.getProperties().honey_level) !== 5) {
        log('honey_source_unready', {position, block: nest?.name, properties: nest?.getProperties()}); continue;
      }
      await approach(nest);
      let smoke = false;
      for (let below = 1; below <= 5; below++) {
        const target = position.offset(0, -below, 0), block = bot.blockAt(target);
        if (block?.name === 'campfire' && block.getProperties().lit) { smoke = true; break; }
        if (!block || block.boundingBox !== 'empty') break;
        const support = bot.blockAt(target.offset(0, -1, 0));
        if (support?.boundingBox !== 'block') continue;
        await equip('campfire');
        await helpers.placeBlockEventually(bot, support, new Vec3(0, 1, 0), 'campfire', 'smoke natural bee nest', log);
        smoke = true; break;
      }
      if (!smoke) throw new Error(`No safe campfire smoke path under bee nest at ${position}`);
      await equip('glass_bottle');
      const before = count('honey_bottle'); await bot.activateBlock(nest);
      await until(() => count('honey_bottle') > before && Number(bot.blockAt(position).getProperties().honey_level) === 0,
        'server honey bottle and emptied nest');
      log('honey_harvested', {position, before, after: count('honey_bottle')});
    }
    if (count('honey_bottle') < 3) throw new Error('Natural honey sources need more time before another harvest');
  }
  async function lootGunpowder() {
    if (count('gunpowder')) return;
    // Naturally generated dungeon chests located by the read-only terrain survey.
    // Their random loot remains unopened until a normal player interaction.
    for (const coordinates of [[-29, 40, 104], [-28, 40, 108], [-41, 32, 110], [-44, 32, 109]]) {
      const position = new Vec3(...coordinates);
      if (bot.entity.position.y < 65) await ascendToSurface();
      await travel(new Vec3(position.x + 3, 80, position.z), 4);
      await descendTo(position.y + 1);
      let chest = bot.blockAt(position);
      if (chest?.name !== 'chest') throw new Error(`Surveyed natural chest is unavailable at ${position}`);
      await approach(chest);
      chest = bot.blockAt(position);
      const window = await bot.openChest(chest);
      try {
        const loot = window.containerItems().map(item => ({name: item.name, count: item.count}));
        log('natural_chest_opened', {position, loot});
        for (const name of ['gunpowder', 'string', 'wheat', 'bread']) {
          const item = window.containerItems().find(item => item.name === name);
          if (!item) continue;
          const before = count(name);
          await window.withdraw(item.type, null, item.count);
          await until(() => count(name) > before, `collect natural ${name} loot`);
          log('natural_loot_collected', {position, name, before, after: count(name)});
        }
      } finally { window.close(); }
      if (count('gunpowder')) return;
    }
    throw new Error('Visited natural chests did not contain gunpowder; another survival source is required');
  }

  try {
    await until(() => bot.entity && bot.inventory, 'player spawn', 60000);
    await helpers.waitForNearbyChunkColumns(bot, Vec3);
    bot._client.write('player_loaded', {}); await delay(1000);
    if (bot.game.gameMode !== 'survival') throw new Error('Survival mode required');
    // Mineflayer injects its plugins asynchronously during login. Install after
    // spawn so its digging plugin cannot replace the confirmation wrapper.
    installServerDigConfirmation(bot, log);
    const movements = new pf.Movements(bot);
    movements.canDig = true; movements.allowParkour = false; movements.allow1by1towers = false;
    bot.pathfinder.setMovements(movements); bot.pathfinder.thinkTimeout = 2500; bot.pathfinder.searchRadius = 16;
    bot.on('path_update', update => log('path_update', {status: update.status, nodes: update.visitedNodes,
      pathLength: update.path?.length, nodeHeapMiB: Math.round(process.memoryUsage().heapUsed / 1048576)}));
    const before = inventory();
    log('started', {player: username, position: bot.entity.position.clone(), inventory: before,
      pluginSha256: createHash('sha256').update(fs.readFileSync(path.join(stage, 'plugins/StarSupply-0.1.0.jar'))).digest('hex').toUpperCase()});
    if (phase === 'mining') {
      if (count('iron_ingot') < 6) {
        await gather(['coal_ore', 'deepslate_coal_ore'], 'coal', 6, 'iron_pickaxe');
        await gather(['iron_ore', 'deepslate_iron_ore'], 'raw_iron', 6 - count('iron_ingot'), 'iron_pickaxe');
        await smelt('raw_iron', 'iron_ingot', 6);
      }
      if (count('glass_bottle') < 3) {
        await ascendToSurface();
        await travel(new Vec3(-60, 65, -1), 4);
        await gather(['sand'], 'sand', Math.max(0, 3 - count('glass')), null, 24);
        await smelt('sand', 'glass', 3);
        await craft('glass_bottle', [['glass', [1, 3, 5]]]);
      }
    } else if (phase === 'surface') {
      if (!await helpers.returnToFurnaceSurfaceSite(bot, pf, Vec3, log)) await ascendToSurface();
      const remainingCampfires = Math.max(0, 3 - count('campfire'));
      await gather(['birch_log'], 'birch_log', 6 + remainingCampfires * 3, null, 24);
      while (count('birch_planks') < 24) await craft('birch_planks', [['birch_log', [1]]]);
      while (count('stick') < remainingCampfires * 3 + (count('stone_sword') ? 0 : 1)) await craft('stick', [['birch_planks', [1, 4]]]);
      if (!count('stone_sword')) await craft('stone_sword', [['cobblestone', [2, 5]], ['stick', [8]]]);
      while (count('campfire') < 3) await craft('campfire', [['stick', [2, 4, 6]], ['coal', [5]], ['birch_log', [7, 8, 9]]]);
    } else if (phase === 'farming') {
      await farmBread();
    } else if (phase === 'honey') {
      await harvestHoney();
    } else if (phase === 'scaffolding') {
      if (count('scaffolding') < 6) {
        if (count('bamboo') < 6) {
          await travel(new Vec3(114, 96, -144), 3);
          await gather(['bamboo'], 'bamboo', 6, 'stone_sword', 20);
        }
        if (!count('string')) {
          await travel(new Vec3(-73, 75, 41), 4);
          const target = bot.blockAt(new Vec3(-73, 24, 41));
          if (!target || target.name !== 'cobweb') throw new Error('Surveyed mineshaft cobweb is unavailable');
          await descendTo(26);
          await gather(['cobweb'], 'string', 1, 'stone_sword', 24);
        }
        await craft('scaffolding', [['bamboo', [1, 3, 4, 6, 7, 9]], ['string', [2]]]);
      }
    } else if (phase === 'fireworks') {
      if (count('firework_rocket') < 3) {
        if (count('paper') < 1) await craft('paper', [['sugar_cane', [4, 5, 6]]]);
        await lootGunpowder();
        await craft('firework_rocket', [['paper', [1]], ['gunpowder', [2]]]);
      }
    } else {
      throw new Error(`Unimplemented survival phase: ${phase}`);
    }
    log('phase_complete', {before, after: inventory(), position: bot.entity.position.clone(), health: bot.health, food: bot.food});
  } catch (error) { log('failed', {reason: error.message, inventory: bot.inventory ? inventory() : []}); throw error; }
  finally {
    bot.pathfinder.setGoal(null); bot.clearControlStates();
    if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
    if (!bot._client.ended) bot.quit(`six resource ${phase} checkpoint`);
  }
}
main().catch(error => { console.error(error.stack || String(error)); process.exitCode = 1; });
