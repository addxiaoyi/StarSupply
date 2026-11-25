const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');

const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
const header = Buffer.from('4d546864000000060000000101e0', 'hex');

function midiFile(track) {
  const trackHeader = Buffer.alloc(8);
  trackHeader.write('MTrk', 0, 'ascii');
  trackHeader.writeUInt32BE(track.length, 4);
  return Buffer.concat([header, trackHeader, track]);
}

function validateStage(stage) {
  if (!fs.existsSync(path.join(stage, 'folia.jar'))) throw new Error(`Missing Folia server JAR: ${stage}`);
  const pluginJar = path.join(stage, 'plugins', 'KaleidoscopeMusic-0.1.0-dev.jar');
  if (!fs.existsSync(pluginJar)) throw new Error(`Missing Music plugin: ${pluginJar}`);
  const properties = readProperties(stage);
  if (properties['online-mode'] !== 'false' || properties['server-ip'] !== '127.0.0.1') {
    throw new Error('The acceptance stage must use offline mode and bind to 127.0.0.1');
  }
  for (const property of ['server-port', 'rcon.port']) {
    const port = Number(properties[property]);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      throw new Error(`Invalid ${property} in the acceptance stage`);
    }
  }
  return properties;
}

function writeFixtures(stage) {
  const songs = path.join(stage, 'plugins', 'KaleidoscopeMusicPlugin', 'songs');
  fs.mkdirSync(songs, {recursive: true});

  const playback = midiFile(Buffer.from([
    0x00, 0xff, 0x51, 0x03, 0x07, 0xa1, 0x20,
    0x00, 0x90, 0x3c, 0x64,
    0x78, 0x80, 0x3c, 0x40,
    0x00, 0x90, 0x40, 0x64,
    0x78, 0x80, 0x40, 0x40,
    0x00, 0x90, 0x43, 0x64,
    0x78, 0x80, 0x43, 0x40,
    0x00, 0xff, 0x2f, 0x00
  ]));
  const delayed = midiFile(Buffer.from([
    0x00, 0xff, 0x51, 0x03, 0x07, 0xa1, 0x20,
    0x00, 0x90, 0x3c, 0x64,
    0xa5, 0x40, 0x80, 0x3c, 0x40,
    0x00, 0x90, 0x40, 0x64,
    0x00, 0xff, 0x2f, 0x00
  ]));
  const files = [
    ['music-acceptance.mid', playback],
    ['music-stop-acceptance.mid', delayed]
  ];

  for (const [name, contents] of files) {
    fs.writeFileSync(path.join(songs, name), contents);
  }
  return files.map(([name, contents]) => ({
    name,
    bytes: contents.length,
    sha256: crypto.createHash('sha256').update(contents).digest('hex').toUpperCase()
  }));
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

async function until(read, label, timeoutMs = 15000, failure = () => null) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const error = failure();
    if (error) throw error;
    const result = read();
    if (result) return result;
    await delay(50);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

function itemId(item) {
  const customData = item.components?.find(component => component.type === 'custom_data')?.data;
  return customData?.value?.['craftengine:id']?.value || null;
}

async function main() {
  const [stageArg, mineflayerArg, username = 'MusicProof'] = process.argv.slice(2);
  if (!stageArg || !mineflayerArg) {
    throw new Error('Usage: run.cjs <isolatedStage> <mineflayerModulePath> [playerName]');
  }
  if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Invalid offline player name');

  const stage = path.resolve(stageArg);
  const properties = validateStage(stage);
  const fixtures = writeFixtures(stage);
  const pluginJar = path.join(stage, 'plugins', 'KaleidoscopeMusic-0.1.0-dev.jar');
  const proof = path.join(stage, 'folia-proof');
  const rcon = path.join(proof, 'rcon.cjs');
  if (!fs.existsSync(rcon)) throw new Error(`Missing isolated RCON helper: ${rcon}`);

  const mineflayer = require(path.resolve(mineflayerArg));
  const runId = `music-${Date.now()}`;
  const logPath = path.join(proof, `${runId}.jsonl`);
  const summaryPath = path.join(proof, 'music-acceptance-latest.json');
  const log = (type, data = {}) => {
    const row = {at: new Date().toISOString(), runId, username, type, ...data};
    fs.appendFileSync(logPath, JSON.stringify(row) + '\n');
    console.log(JSON.stringify(row));
    return row;
  };
  const rconResult = command => cp.execFileSync(process.execPath, [rcon, command], {
    cwd: stage,
    env: {...process.env, STARSUPPLY_FOLIA_STAGE: stage},
    encoding: 'utf8',
    windowsHide: true
  });
  const opOutput = rconResult(`op ${username}`);
  log('operator-granted', {player: username, output: opOutput.trim()});

  const sounds = [];
  const windows = [];
  let connectionFailure;
  const bot = mineflayer.createBot({
    host: '127.0.0.1',
    port: Number(properties['server-port']),
    username,
    auth: 'offline',
    version: '1.21.11',
    hideErrors: false,
    checkTimeoutInterval: 90000
  });
  bot.on('spawn', () => log('spawn', {uuid: bot.player.uuid, position: bot.entity.position}));
  bot.on('messagestr', message => log('chat', {message}));
  bot.on('windowOpen', window => {
    const entry = {title: window.title, type: window.type, slots: window.slots.length};
    windows.push(entry);
    log('window-open', entry);
  });
  bot.on('soundEffectHeard', (name, position, volume, pitch) => {
    if (!name.toLowerCase().includes('note_block.')) return;
    const entry = {at: Date.now(), name, position, volume, pitch};
    sounds.push(entry);
    log('note-sound', entry);
  });
  bot.on('error', error => {
    connectionFailure = error;
    log('client-error', {message: error.stack || String(error)});
  });
  bot.on('kicked', reason => {
    connectionFailure = new Error(`Kicked: ${JSON.stringify(reason)}`);
    log('kicked', {reason});
  });
  bot.on('end', reason => log('disconnect', {reason}));

  try {
    await until(() => bot.entity, 'player spawn', 45000, () => connectionFailure);
    bot.chat('/kmusic kit');
    const instrument = await until(() => bot.inventory.items()
      .find(item => itemId(item) === 'musicplus:handheld_instrument'), 'Music instrument kit');
    await bot.equip(instrument, 'hand');
    log('instrument-equipped', {name: instrument.name, customId: itemId(instrument)});

    const windowCount = windows.length;
    bot.activateItem();
    const menu = await until(() => windows.length > windowCount ? windows.at(-1) : null,
      'Music keyboard menu');
    if (!String(menu.type).includes('generic_9x3')) {
      throw new Error(`Unexpected keyboard window type: ${menu.type}`);
    }
    const beforeKey = sounds.length;
    await bot.clickWindow(0, 0, 0);
    await until(() => sounds.length > beforeKey, 'keyboard note sound');

    if (bot.currentWindow) bot.closeWindow(bot.currentWindow);
    const beforePlayback = sounds.length;
    bot.chat('/kmusic play music-acceptance.mid');
    await until(() => sounds.length >= beforePlayback + 3, 'three MIDI note sounds', 10000,
      () => connectionFailure);

    const beforeStop = sounds.length;
    bot.chat('/kmusic play music-stop-acceptance.mid');
    await until(() => sounds.length > beforeStop, 'first delayed MIDI note', 5000,
      () => connectionFailure);
    bot.chat('/kmusic stop');
    await delay(5600);
    const stoppedPlaybackSounds = sounds.length - beforeStop;
    if (stoppedPlaybackSounds !== 1) {
      throw new Error(`Expected stop to leave one MIDI sound, observed ${stoppedPlaybackSounds}`);
    }

    const summary = {
      at: new Date().toISOString(),
      runId,
      username,
      stage,
      musicPluginSha256: crypto.createHash('sha256').update(fs.readFileSync(pluginJar)).digest('hex').toUpperCase(),
      fixtures,
      keyboardWindow: menu,
      keyboardNote: sounds[beforeKey],
      midiPlayback: sounds.slice(beforePlayback, beforePlayback + 3),
      stopPlaybackSoundCount: stoppedPlaybackSounds,
      accepted: true
    };
    fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2));
    log('accepted', {summaryPath});
    return summary;
  } finally {
    if (!bot._client.ended) bot.end('Music acceptance complete');
  }
}

const [firstArg, secondArg] = process.argv.slice(2);
if (firstArg === '--prepare') {
  if (!secondArg) throw new Error('Usage: run.cjs --prepare <isolatedStage>');
  const stage = path.resolve(secondArg);
  validateStage(stage);
  const fixtureFiles = writeFixtures(stage);
  console.log(JSON.stringify({prepared: fixtureFiles}, null, 2));
} else {
  main().catch(error => {
    console.error(error.stack || String(error));
    process.exitCode = 1;
  });
}
