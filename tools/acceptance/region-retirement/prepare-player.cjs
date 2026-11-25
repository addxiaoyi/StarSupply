const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const assert = require('node:assert/strict');
const net = require('node:net');
const {createHash} = require('node:crypto');
const {offlinePlayerId} = require('../survival-progression/survival-checkpoint.cjs');

async function main() {
  const [stage, mineflayerModule, username = 'SupplyBuyer'] = process.argv.slice(2);
  assert.match(username, /^[A-Za-z0-9_]{1,16}$/);
  assert.ok(fs.existsSync(path.join(stage, 'folia-proof', 'allow-region-retirement-fixture')));
  const properties = Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
    .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
    .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
  assert.equal(properties['online-mode'], 'false');
  assert.equal(properties['server-ip'], '127.0.0.1');
  for (const port of [Number(properties['server-port']), Number(properties['rcon.port'])]) {
    assert.ok(Number.isInteger(port) && port > 0 && port <= 65535);
    await new Promise((resolve, reject) => {
      const socket = net.createConnection({host: '127.0.0.1', port});
      socket.setTimeout(1000);
      socket.once('connect', () => { socket.destroy(); reject(new Error('Acceptance stage must be stopped before preparing player data')); });
      socket.once('timeout', () => { socket.destroy(); reject(new Error('Cannot verify stopped acceptance stage')); });
      socket.once('error', error => error.code === 'ECONNREFUSED' ? resolve() : reject(error));
    });
  }
  const nbt = require(path.join(path.dirname(path.resolve(mineflayerModule)), 'prismarine-nbt'));
  const file = path.join(stage, properties['level-name'], 'playerdata', `${offlinePlayerId(username)}.dat`);
  const before = fs.readFileSync(file);
  const parsed = (await nbt.parse(before)).parsed;
  const inventoryBefore = JSON.stringify(parsed.value.Inventory);
  const receiptBefore = JSON.stringify(parsed.value.BukkitValues);
  const removed = [];
  const recipeBook = parsed.value.recipeBook?.value;
  for (const key of ['recipes', 'toBeDisplayed']) {
    const list = recipeBook?.[key]?.value?.value;
    if (!Array.isArray(list)) continue;
    recipeBook[key].value.value = list.filter(recipe => {
      if (recipe.startsWith('minecraft:')) return true;
      removed.push({list: key, recipe});
      return false;
    });
  }
  const after = zlib.gzipSync(nbt.writeUncompressed(parsed));
  const checked = (await nbt.parse(after)).parsed;
  assert.equal(JSON.stringify(checked.value.Inventory), inventoryBefore);
  assert.equal(JSON.stringify(checked.value.BukkitValues), receiptBefore);
  const backup = path.join(stage, 'folia-proof', `player-before-recipe-filter-${Date.now()}.dat`);
  fs.writeFileSync(backup, before, {flag: 'wx'});
  fs.writeFileSync(file, after);
  const sha = bytes => createHash('sha256').update(bytes).digest('hex').toUpperCase();
  const evidence = {player: username, removed, inventoryUnchanged: true, receiptsUnchanged: true,
    beforeSha256: sha(before), afterSha256: sha(after), backup};
  fs.writeFileSync(path.join(stage, 'folia-proof', 'player-recipe-preparation.json'), JSON.stringify(evidence, null, 2) + '\n');
  console.log(JSON.stringify(evidence));
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
