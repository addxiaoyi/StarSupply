const fs = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const [stage, mineflayerModule, unusedPathfinder, username = 'SupplyBuyer'] = process.argv.slice(2);
const mineflayer = require(mineflayerModule);
const Vec3 = require(path.join(path.dirname(mineflayerModule), 'vec3')).Vec3;
const properties = Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
  .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
  .map(line => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]));
assert.equal(properties['server-ip'], '127.0.0.1');
assert.equal(properties['online-mode'], 'false');
const evidence = path.join(stage, 'plugins', 'SupplyRetirementProof', 'evidence.jsonl');
const offset = fs.existsSync(evidence) ? fs.statSync(evidence).size : 0;
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
let active;
let clientFailure;
const log = (type, data = {}) => console.log(JSON.stringify({type, at: new Date().toISOString(), ...data}));
function rows() {
  if (!fs.existsSync(evidence)) return [];
  const text = fs.readFileSync(evidence).subarray(offset).toString('utf8');
  const complete = text.slice(0, text.lastIndexOf('\n') + 1);
  return complete.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
}
async function until(read, label, timeout = 60000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (clientFailure) throw clientFailure;
    const failed = rows().find(row => row.type === 'failed');
    if (failed) throw new Error(`Fixture failure: ${JSON.stringify(failed)}`);
    const value = read();
    if (value) return value;
    await delay(100);
  }
  throw new Error(`Timed out waiting for ${label}`);
}
async function connect() {
  const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(properties['server-port']),
    username, auth: 'offline', version: '1.21.11', viewDistance: 'short', checkTimeoutInterval: 60000});
  active = bot;
  bot.physicsEnabled = false;
  bot.on('error', error => { clientFailure = error; });
  bot.on('messagestr', message => log('chat', {message}));
  bot.on('kicked', reason => log('kicked', {reason}));
  bot.on('end', reason => { bot.proofEnded = true; log('end', {reason}); });
  await until(() => bot.entity && bot.inventory, 'spawn');
  await until(() => {
    const p = bot.entity.position;
    return bot.world.getColumnAt(new Vec3(Math.floor(p.x / 16) * 16, 0, Math.floor(p.z / 16) * 16));
  }, 'player chunk');
  bot._client.write('player_loaded', {});
  await delay(1500);
  assert.equal(bot.game.gameMode, 'survival');
  log('connected', {player: username, position: bot.entity.position});
  return bot;
}
async function snapshot(bot, mode) {
  const start = rows().length;
  for (let attempt = 0; attempt < 40; attempt++) {
    bot.chat('/retirementproof snapshot');
    await delay(1000);
    const result = rows().slice(start).find(row => row.type === 'snapshot' && row.mode === mode && row.pass);
    if (result) return result;
    await until(() => true, 'fixture health');
  }
  throw new Error(`Recovery did not pass: ${JSON.stringify(rows().filter(row => row.type === 'snapshot').at(-1))}`);
}
async function main() {
  const outcomes = [];
  let bot = await connect();
  try {
    for (const mode of ['untouched', 'receipt']) {
      bot.chat(`/retirementproof start ${mode}`);
      await until(() => bot.proofEnded, `${mode} forced retirement`);
      for (const type of ['prepared', 'scheduler-retired', 'callback-retired', 'drained', 'unloaded', 'region-absent']) {
        await until(() => rows().some(row => row.type === type && row.mode === mode), `${mode} ${type}`, 120000);
      }
      bot = await connect();
      const first = await snapshot(bot, mode);
      bot.chat('/supply recover');
      await delay(1500);
      const repeated = await snapshot(bot, mode);
      assert.deepEqual(repeated, {...first, at: repeated.at, thread: repeated.thread});
      outcomes.push({mode, recovered: first, repeatedRecovery: repeated});
    }
    bot.chat('/retirementproof finish');
    await until(() => rows().some(row => row.type === 'finished'), 'saved final player');
    log('region_retirement_passed', {outcomes, evidence});
  } finally {
    if (active && !active._client.ended) {
      active.quit('region retirement acceptance complete');
      await delay(1500);
    }
  }
}
main().catch(error => { console.error(error.stack || String(error)); if (active && !active._client.ended) active.end(); process.exitCode = 1; });
