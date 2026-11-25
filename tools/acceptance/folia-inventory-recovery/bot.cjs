const fs = require('node:fs');
const path = require('node:path');
const mineflayerPath = process.env.STARSUPPLY_MINEFLAYER_MODULE
  || 'C:/Users/Administrator/Documents/starsupply-deps/minecraft-client-1.21.11/node_modules/mineflayer';
const mineflayer = require(mineflayerPath);
const stage = path.resolve(process.env.STARSUPPLY_FOLIA_STAGE || path.dirname(__dirname));
const username = process.argv[2];
if (!username || !/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Pass the existing acceptance player name.');
const properties = Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
  .split(/\r?\n/).filter(line => !line.startsWith('#') && line.includes('=')).map(line => {
    const separator = line.indexOf('=');
    return [line.slice(0, separator), line.slice(separator + 1)];
  }));
const root = path.join(stage, 'folia-proof');
const actionFile = path.join(root, `actions-${username}.json`);
const cursorFile = path.join(root, `cursor-${username}.json`);
const logFile = path.join(root, `bot-${username}.jsonl`);
let cursor = '';
try { cursor = JSON.parse(fs.readFileSync(cursorFile, 'utf8')).id; } catch {}
let running = false;
const session = Date.now().toString(36);

function log(type, data = {}) {
  const line = JSON.stringify({at: new Date().toISOString(), session, username, type, ...data});
  console.log(line);
  fs.appendFileSync(logFile, line + '\n');
}

const bot = mineflayer.createBot({host: '127.0.0.1', port: +properties['server-port'], username,
  auth: 'offline', version: '1.21.11', hideErrors: false, checkTimeoutInterval: 90000});
bot.on('spawn', () => log('spawn', {uuid: bot.player?.uuid ?? null, position: bot.entity?.position ?? null,
  quickBarSlot: bot.quickBarSlot, heldItem: bot.heldItem && ({name: bot.heldItem.name,
    count: bot.heldItem.count, components: bot.heldItem.components, nbt: bot.heldItem.nbt})}));
bot.on('messagestr', message => log('chat', {message}));
bot.on('error', error => log('error', {error: error.stack || String(error)}));
bot.on('kicked', reason => log('kicked', {reason}));
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

const timer = setInterval(async () => {
  if (running || !bot.entity) return;
  let requested;
  try { requested = JSON.parse(fs.readFileSync(actionFile, 'utf8')); } catch { return; }
  if (!requested.id || requested.id === cursor || !Array.isArray(requested.actions)) return;
  running = true;
  cursor = requested.id;
  fs.writeFileSync(cursorFile, JSON.stringify({id: cursor, session, at: new Date().toISOString()}));
  try {
    for (const action of requested.actions) {
      if (action.chat) {
        log('command', {command: action.chat, actionId: cursor});
        bot.chat(action.chat);
      }
      if (action.inventory) {
        log('inventory', {items: bot.inventory.items().map(item => ({slot: item.slot, name: item.name,
          count: item.count, components: item.components, nbt: item.nbt}))});
      }
      if (action.quit) {
        if (!bot._client.ended) bot.end(String(action.quit));
        break;
      }
      await delay(Math.min(60000, Math.max(50, action.wait || 750)));
    }
    log('actions-complete', {actionId: cursor});
  } catch (error) {
    log('actions-failed', {actionId: cursor, error: error.stack || String(error)});
  } finally {
    running = false;
  }
}, 250);

const lifetime = setTimeout(() => {
  if (!bot._client.ended) bot.end('isolated acceptance time bound');
}, 15 * 60 * 1000);
bot.on('end', reason => {
  clearInterval(timer);
  clearTimeout(lifetime);
  log('end', {reason});
});
