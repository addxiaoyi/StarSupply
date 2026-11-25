const {spawn} = require('node:child_process');
const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const {runtimeHealth} = require('./runtime-health.cjs');
const {preflightMemory} = require('./memory-preflight.cjs');
const {createHash} = require('node:crypto');
const {readSavedPickaxe, readSavedResourcePhase, readSavedInventory} = require('./survival-checkpoint.cjs');
const {stopViaRcon} = require('./rcon-stop.cjs');

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
  const [stageArg, mineflayerArg, pathfinderArg, javaArg, username = 'SupplyCook', clientScriptArg, clientTimeoutArg, clientArgumentsArg] = process.argv.slice(2);
  if (!stageArg || !mineflayerArg || !pathfinderArg || !javaArg) {
    throw new Error('Usage: run.cjs <isolatedFoliaStage> <mineflayerModule> <pathfinderModule> <javaExecutable> [player] [clientScript] [timeoutMs] [clientArgumentsJson]');
  }
  if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Invalid offline player name');

  const stage = path.resolve(stageArg);
  const properties = readProperties(stage);
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

  const javaPath = path.resolve(javaArg);
  if (!fs.existsSync(javaPath)) throw new Error(`Java executable does not exist: ${javaPath}`);
  const clientTimeoutMs = clientTimeoutArg === undefined ? 600000 : Number(clientTimeoutArg);
  if (!Number.isSafeInteger(clientTimeoutMs) || clientTimeoutMs < 1000) {
    throw new Error('Client timeout must be an integer of at least 1000 milliseconds');
  }
  const serverHeap = process.env.STARSUPPLY_ACCEPTANCE_HEAP || '1024M';
  if (!/^\d+[kmg]$/i.test(serverHeap)) {
    throw new Error('STARSUPPLY_ACCEPTANCE_HEAP must be a JVM memory size such as 512M');
  }
  const memory = preflightMemory(serverHeap);
  const plugins = fs.readdirSync(path.join(stage, 'plugins')).filter(name => name.endsWith('.jar'))
    .sort().map(name => ({name, sha256: createHash('sha256')
      .update(fs.readFileSync(path.join(stage, 'plugins', name))).digest('hex').toUpperCase()}));
  const startupTimeoutMs = process.env.STARSUPPLY_ACCEPTANCE_STARTUP_TIMEOUT === undefined
    ? 360000
    : Number(process.env.STARSUPPLY_ACCEPTANCE_STARTUP_TIMEOUT);
  if (!Number.isSafeInteger(startupTimeoutMs) || startupTimeoutMs < 1000) {
    throw new Error('STARSUPPLY_ACCEPTANCE_STARTUP_TIMEOUT must be an integer of at least 1000 milliseconds');
  }
  const clientArguments = clientArgumentsArg === undefined ? [] : JSON.parse(clientArgumentsArg);
  if (!Array.isArray(clientArguments) || clientArguments.some(argument => typeof argument !== 'string')) {
    throw new Error('Client arguments must be a JSON array of strings');
  }

  const proofDirectory = path.join(stage, 'folia-proof');
  fs.mkdirSync(proofDirectory, {recursive: true});
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-');
  const serverLogPath = path.join(proofDirectory, `survival-progression-${timestamp}-server.log`);
  const serverErrorPath = path.join(proofDirectory, `survival-progression-${timestamp}-server-error.log`);
  const clientLogPath = path.join(proofDirectory, `survival-progression-${timestamp}-client.log`);
  const serverLog = fs.createWriteStream(serverLogPath, {flags: 'wx'});
  const serverErrorLog = fs.createWriteStream(serverErrorPath, {flags: 'wx'});
  const clientLog = fs.createWriteStream(clientLogPath, {flags: 'wx'});

  let server;
  let client;
  let serverFailure;
  let serverExit;
  let clientExit;
  let serverTail = '';
  let serverClosed;
  let clientClosed;
  let runFailure;
  let stopping = false;
  let shutdownMethod;
  let clientSources;
  const health = runtimeHealth(rconPort);
  const observe = (chunk, channel) => {
    health.push(chunk, channel);
    if (health.failure && client && clientExit === undefined) client.kill();
  };

  try {
    server = spawn(javaPath, [
      '-Xms64M', `-Xmx${serverHeap}`, '-Xss512k', '-XX:ActiveProcessorCount=1',
      '-XX:CICompilerCount=2', '-XX:TieredStopAtLevel=1', '-XX:+UseSerialGC',
      '-Dfile.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
      '-jar', 'folia.jar', 'nogui'
    ], {cwd: stage, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe']});

    server.stdout.pipe(serverLog);
    server.stderr.pipe(serverErrorLog);
    server.stdout.on('data', chunk => {
      serverTail = (serverTail + chunk.toString('utf8')).slice(-30000);
      observe(chunk, 'stdout');
    });
    server.stderr.on('data', chunk => observe(chunk, 'stderr'));
    server.on('error', error => { serverFailure = error; });
    server.stdin.on('error', error => { serverFailure = error; });
    serverClosed = new Promise(resolve => server.once('close', (code, signal) => {
      serverExit = {code, signal};
      if (!stopping && client && clientExit === undefined) {
        runFailure = runFailure ?? health.failure ?? new Error('Folia exited during gameplay');
        client.kill();
      }
      resolve(serverExit);
    }));

    await until(() => health.ready, 'Folia and required plugins to finish enabling', startupTimeoutMs,
    () => health.failure ?? serverFailure
      ?? (serverExit ? new Error(`Folia exited during startup: ${JSON.stringify(serverExit)}`) : null));
    if (await portClosed(gamePort)) throw new Error(`Folia game listener is unavailable on ${gamePort}`);

    const clientPath = path.resolve(clientScriptArg || path.join(__dirname, 'iron-pickaxe.cjs'));
    clientSources = [clientPath, ...fs.readdirSync(__dirname)
      .filter(name => name.endsWith('.cjs') && !name.endsWith('.test.cjs'))
      .map(name => path.join(__dirname, name))]
      .filter((file, index, files) => files.indexOf(file) === index)
      .map(file => ({file: path.relative(__dirname, file).replace(/\\/g, '/'),
        sha256: createHash('sha256').update(fs.readFileSync(file)).digest('hex').toUpperCase()}));
    client = spawn(process.execPath, [
      '--max-old-space-size=256',
      clientPath,
      stage,
      path.resolve(mineflayerArg),
      path.resolve(pathfinderArg),
      username,
      ...clientArguments
    ], {cwd: stage, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']});
    client.stdout.pipe(clientLog);
    client.stderr.pipe(clientLog);
    client.on('error', error => { runFailure = error; });
    clientClosed = new Promise(resolve => client.once('close', (code, signal) => {
      clientExit = {code, signal};
      resolve(clientExit);
    }));
    await within(clientClosed, 'Mineflayer survival progression', clientTimeoutMs);
    if (health.failure) throw health.failure;
  } catch (error) {
    runFailure = runFailure ?? error;
  } finally {
    if (client && clientExit === undefined) {
      client.kill();
      await within(clientClosed, 'Mineflayer process to exit', 10000).catch(() => {});
    }
    stopping = true;
    health.beginShutdown();
    if (server && serverExit === undefined) {
      // A failed plugin may be reported before the listener and worlds finish loading.
      // Waiting here is bounded and never starts the gameplay client after a health failure.
      await until(() => health.snapshot().rconReady || serverExit,
        'RCON availability for graceful shutdown', 60000).catch(() => {});
      if (serverExit === undefined) {
        try {
          await stopViaRcon(rconPort, properties['rcon.password']);
          shutdownMethod = 'rcon';
        } catch {
          shutdownMethod = 'console-fallback';
          if (!server.stdin.destroyed) server.stdin.write('stop\n');
        }
      }
      await within(serverClosed, 'Folia graceful shutdown', 180000)
        .catch(error => { runFailure = runFailure ?? error; });
    }
    health.finish();
    runFailure = runFailure ?? health.failure;
    serverLog.end();
    serverErrorLog.end();
    clientLog.end();
  }

  const normalStopObserved = serverTail.includes('Stopping server')
    && serverTail.includes('Saved all player data')
    && serverExit?.code === 0;
  const [gamePortClosed, rconPortClosed] = await Promise.all([
    portClosed(gamePort),
    portClosed(rconPort)
  ]);
  let savedPlayer;
  let savedCheckpoint;
  if (normalStopObserved && gamePortClosed && rconPortClosed && (!clientScriptArg
      || path.resolve(clientScriptArg) === path.join(__dirname, 'six-resources.cjs'))) {
    try {
      savedCheckpoint = await readSavedInventory(stage, properties['level-name'], username, mineflayerArg);
    } catch (error) { runFailure = runFailure ?? error; }
  }
  if (clientExit?.code === 0 && normalStopObserved && gamePortClosed && rconPortClosed) {
    try {
      if (!clientScriptArg) {
        savedPlayer = await readSavedPickaxe(stage, properties['level-name'], username, mineflayerArg);
      } else if (path.resolve(clientScriptArg) === path.join(__dirname, 'six-resources.cjs')) {
        savedPlayer = await readSavedResourcePhase(stage, properties['level-name'], username, mineflayerArg,
          clientArguments[0] ?? 'mining');
      }
    } catch (error) {
      runFailure = runFailure ?? error;
    }
  }
  const summary = {
    event: 'survival_progression_run',
    server: 'Folia 1.21.11-14-529aabc',
    serverHeap,
    clientNodeHeapMiB: 256,
    memory,
    plugins,
    clientSources,
    savedPlayer,
    savedCheckpoint,
    startupTimeoutMs,
    runtimeHealth: health.snapshot(),
    failure: runFailure?.message ?? null,
    pass: !runFailure && clientExit?.code === 0 && normalStopObserved && gamePortClosed && rconPortClosed,
    player: username,
    clientExit,
    serverExit,
    shutdownMethod,
    normalStopObserved,
    gamePortClosed,
    rconPortClosed,
    serverLog: path.relative(stage, serverLogPath).replace(/\\/g, '/'),
    serverErrorLog: path.relative(stage, serverErrorPath).replace(/\\/g, '/'),
    clientLog: path.relative(stage, clientLogPath).replace(/\\/g, '/')
  };
  fs.writeFileSync(path.join(proofDirectory, `survival-progression-${timestamp}-result.json`),
    JSON.stringify(summary, null, 2) + '\n');
  console.log(JSON.stringify(summary));

  if (runFailure) throw runFailure;
  if (clientExit?.code !== 0) throw new Error('The survival progression client did not pass');
  if (!normalStopObserved || !gamePortClosed || !rconPortClosed) {
    throw new Error('Folia did not confirm a normal shutdown and closed listeners');
  }
}

main().catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
