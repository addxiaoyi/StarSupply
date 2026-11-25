const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const {spawn} = require('node:child_process');

const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const appendedText = (file, byteOffset) => fs.readFileSync(file).subarray(byteOffset).toString('utf8');

function raceDelay(promise, milliseconds) {
  let timer;
  const timeout = new Promise(resolve => { timer = setTimeout(resolve, milliseconds); });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
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

async function run() {
  const [stageArgument, javaArgument, nodeArgument, pythonArgument, username = 'SupplyBuyer'] = process.argv.slice(2);
  if (!stageArgument || !javaArgument || !nodeArgument || !pythonArgument) {
    throw new Error('Usage: run.cjs <isolatedFoliaStage> <javaExecutable> <nodeExecutable> <pythonExecutable> [player]');
  }
  const stage = path.resolve(stageArgument);
  const java = path.resolve(javaArgument);
  const node = path.resolve(nodeArgument);
  const python = path.resolve(pythonArgument);
  const properties = readProperties(stage);
  if (properties['online-mode'] !== 'false' || properties['server-ip'] !== '127.0.0.1') {
    throw new Error('The acceptance stage must be a loopback-bound offline test server');
  }
  if (!/^[A-Za-z0-9_]{1,16}$/.test(username)) throw new Error('Invalid acceptance player name');
  if (username !== 'SupplyBuyer') throw new Error('This acceptance stage is bound to the persistent SupplyBuyer profile');

  const gamePort = Number(properties['server-port']);
  const rconPort = Number(properties['rcon.port']);
  for (const port of [gamePort, rconPort]) {
    if (!Number.isInteger(port) || !await portClosed(port)) {
      throw new Error(`Acceptance listener must be closed before startup: ${port}`);
    }
  }

  const proof = path.join(stage, 'folia-proof');
  const playerId = '5ab1d958-3192-31da-88f6-5860ab3c3a3d';
  const purchaseId = '443e79ab-0960-4d51-b5f7-273a70f4616b';
  const database = path.join(stage, 'plugins', 'StarSupply', 'supply.db');
  const inspector = path.join(__dirname, 'inspect.py');
  const botScript = path.join(__dirname, 'bot.cjs');
  const botLog = path.join(proof, `bot-${username}.jsonl`);
  const actionFile = path.join(proof, `actions-${username}.json`);
  const cursorFile = path.join(proof, `cursor-${username}.json`);
  for (const required of [java, node, python, inspector, botScript, database]) {
    if (!fs.existsSync(required)) throw new Error(`Missing inventory-retirement input: ${required}`);
  }

  const runId = `folia-inventory-recovery-${Date.now()}`;
  const serverLogPath = path.join(proof, `${runId}-server.log`);
  const serverErrorPath = path.join(proof, `${runId}-server-error.log`);
  const resultPath = path.join(proof, `${runId}.json`);
  const serverLog = fs.createWriteStream(serverLogPath, {flags: 'wx'});
  const serverErrorLog = fs.createWriteStream(serverErrorPath, {flags: 'wx'});
  const botLogStart = fs.existsSync(botLog) ? fs.statSync(botLog).size : 0;
  const javaArgs = [
    '-Xms256M', '-Xmx1024M', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC',
    '-Dfile.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
    '-jar', 'folia.jar', 'nogui'
  ];
  let server;
  let serverTail = '';
  let serverFailure;
  let serverExit;
  let serverClosed;
  let clients = [];
  let runFailure;

  async function connectClient(actions) {
    const session = `inventory-${runId}-${clients.length + 1}`;
    const actionId = `${session}-actions`;
    fs.writeFileSync(actionFile, JSON.stringify({id: actionId, actions}));
    const client = spawn(node, [botScript, username], {
      cwd: stage,
      env: {...process.env, STARSUPPLY_FOLIA_STAGE: stage},
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let output = '';
    client.stdout.on('data', chunk => { output += chunk.toString('utf8'); });
    client.stderr.on('data', chunk => { output += chunk.toString('utf8'); });
    const closed = new Promise(resolve => client.once('close', (code, signal) => resolve({code, signal})));
    clients.push({client, closed, actionId, readOutput: () => output});
    return {client, closed, actionId, readOutput: () => output};
  }

  async function playerSnapshot() {
    const logStart = fs.existsSync(botLog) ? fs.statSync(botLog).size : 0;
    const client = await connectClient([
      {wait: 6000},
      {inventory: true},
      {wait: 300},
      {quit: 'inventory recovery check complete'}
    ]);
    await until(() => {
      if (!fs.existsSync(botLog)) return null;
      const added = appendedText(botLog, logStart);
      return added.includes(`"actionId":"${client.actionId}"`)
        && added.includes('"type":"inventory"')
        && added.includes('"type":"actions-complete"');
    }, 'player reconnect snapshot', 45000);
    const added = appendedText(botLog, logStart);
    const rows = added.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
    return {
      inventory: rows.find(row => row.type === 'inventory')?.items ?? [],
      chats: rows.filter(row => row.type === 'chat').map(row => row.message),
      clientExit: await client.closed
    };
  }

  async function inspectDatabase() {
    const child = spawn(python, [inspector, database, purchaseId, playerId], {
      cwd: stage,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    let output = '';
    let error = '';
    child.stdout.on('data', chunk => { output += chunk.toString('utf8'); });
    child.stderr.on('data', chunk => { error += chunk.toString('utf8'); });
    return new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('close', code => {
        if (code !== 0) {
          reject(new Error(`SQLite inspection failed: ${error}`));
          return;
        }
        try {
          resolve(JSON.parse(output));
        } catch (failure) {
          reject(new Error(`SQLite inspection returned invalid JSON: ${failure.message}`));
        }
      });
    });
  }

  try {
    server = spawn(java, javaArgs, {
      cwd: stage,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe']
    });
    server.stdout.pipe(serverLog);
    server.stderr.pipe(serverErrorLog);
    server.stdout.on('data', chunk => {
      serverTail = (serverTail + chunk.toString('utf8')).slice(-50000);
    });
    server.on('error', error => { serverFailure = error; });
    server.stdin.on('error', error => { serverFailure = error; });
    serverClosed = new Promise(resolve => server.once('close', (code, signal) => {
      serverExit = {code, signal};
      resolve(serverExit);
    }));
    await until(() => serverTail.includes(`RCON running on 127.0.0.1:${rconPort}`)
      && serverTail.includes('Done (')
      && serverTail.includes('FOLIA_PROOF {"type":"enabled"'), 'Folia startup', 180000, () => {
      if (serverFailure) return serverFailure;
      if (serverTail.includes("Could not load plugin 'StarSupply")) {
        return new Error('Folia rejected the StarSupply test plugin descriptor');
      }
      return serverExit ? new Error(`Folia exited: ${JSON.stringify(serverExit)}`) : null;
    });

    const initialPlayer = await playerSnapshot();
    const initialIron = initialPlayer.inventory
      .filter(item => item.name === 'iron_ingot')
      .reduce((sum, item) => sum + item.count, 0);
    if (initialIron < 1) throw new Error(`Acceptance player has ${initialIron} iron ingots`);

    const before = await inspectDatabase();
    if (before.purchase?.state !== 'OPEN' || before.purchase.item_count !== 1) {
      throw new Error(`Known open purchase is unavailable: ${JSON.stringify(before)}`);
    }
    if (before.transfers.some(item => ['PREPARED', 'RETURNING', 'REVIEW'].includes(item.state))) {
      throw new Error(`Known purchase has an unresolved transfer before the test: ${JSON.stringify(before.transfers)}`);
    }

    let afterDisconnect = before;
    let saleAttempts = [];
    for (const disconnectDelayMs of [50, 65, 75, 85, 90, 100, 160, 250, 350]) {
      const baseline = fs.existsSync(botLog) ? fs.statSync(botLog).size : 0;
      const saleClient = await connectClient([
        {chat: `/supply sell ${purchaseId}`, wait: disconnectDelayMs},
        {quit: `disconnect during inventory sale after ${disconnectDelayMs}ms`}
      ]);
      const saleDisconnect = await until(() => {
        if (!fs.existsSync(botLog)) return null;
        const added = appendedText(botLog, baseline);
        if (added.includes('"type":"error"') || added.includes('"type":"actions-failed"')
            || added.includes('Unknown or incomplete command')) {
          return {failed: added};
        }
        return added.includes(`"actionId":"${saleClient.actionId}"`)
          && added.includes('"type":"end"')
          && added.includes(`disconnect during inventory sale after ${disconnectDelayMs}ms`);
      }, `player disconnect after sale command at ${disconnectDelayMs}ms`, 60000);
      if (saleDisconnect.failed) throw new Error(`Player sale interaction failed: ${saleDisconnect.failed}`);
      await saleClient.closed;
      await delay(500);
      afterDisconnect = await inspectDatabase();
      const previousTransferIds = new Set(before.transfers.map(item => item.id));
      const newTransfers = afterDisconnect.transfers.filter(item => !previousTransferIds.has(item.id));
      saleAttempts.push({disconnectDelayMs, transfers: newTransfers});
      if (newTransfers.length > 0 || afterDisconnect.purchase?.state !== 'OPEN') break;
    }

    const reconnected = await playerSnapshot();
    const afterReconnect = await inspectDatabase();
    const ironAfter = reconnected.inventory
      .filter(item => item.name === 'iron_ingot')
      .reduce((sum, item) => sum + item.count, 0);
    const previousTransferIds = new Set(before.transfers.map(item => item.id));
    const attemptedTransfers = afterDisconnect.transfers.filter(item => !previousTransferIds.has(item.id));
    const interruptedTransfers = attemptedTransfers.filter(item => item.state === 'PREPARED');
    const previousSettlementIds = new Set(before.settlements.map(item => item.id));
    const newSettlements = afterReconnect.settlements.filter(item => !previousSettlementIds.has(item.id));
    const recoveredTransferIds = new Set(afterReconnect.transfers.map(item => item.id));
    const completedTransfers = afterReconnect.transfers.filter(item => !previousTransferIds.has(item.id));
    const returnedWithoutDebit = interruptedTransfers.length > 0
      && interruptedTransfers.every(item => !recoveredTransferIds.has(item.id))
      && afterReconnect.purchase?.state === 'OPEN'
      && afterReconnect.settlements.length === before.settlements.length
      && ironAfter === initialIron;
    const committedExactlyOnce = completedTransfers.some(item => item.state === 'STORED')
      && newSettlements.length === 1
      && newSettlements[0].kind === 'PURCHASE'
      && newSettlements[0].state === 'PAID'
      && afterReconnect.purchase?.state === 'BOUGHT'
      && ironAfter === initialIron - before.purchase.item_count;
    const result = {
      runId,
      purchaseId,
      player: username,
      initialIron,
      ironAfter,
      before,
      afterDisconnect,
      afterReconnect,
      saleAttempts,
      attemptedTransfers,
      interruptedTransfers,
      newSettlements,
      reconnectChats: reconnected.chats,
      reconnectedClientExit: reconnected.clientExit,
      outcome: returnedWithoutDebit ? 'PREPARED_ABORTED_WITHOUT_DEBIT'
        : committedExactlyOnce ? 'COMMITTED_EXACTLY_ONCE'
          : 'REVIEW_OR_UNEXPECTED_STATE',
      pass: returnedWithoutDebit || committedExactlyOnce
    };
    fs.writeFileSync(resultPath, JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
    if (!result.pass) throw new Error('Folia inventory recovery did not converge');
  } catch (error) {
    runFailure = error;
  } finally {
    for (const current of clients) {
      if (current.client.exitCode === null && current.client.signalCode === null) current.client.kill();
      await raceDelay(current.closed, 5000);
    }
    if (server && serverExit === undefined) {
      server.stdin.write('stop\n');
      await raceDelay(serverClosed, 180000).catch(error => {
        runFailure = runFailure ?? error;
      });
    }
    serverLog.end();
    serverErrorLog.end();
  }

  const normalShutdown = serverTail.includes('Stopping server')
    && serverTail.includes('Saved all player data')
    && serverExit?.code === 0;
  const listenersClosed = await portClosed(gamePort) && await portClosed(rconPort);
  if (runFailure) throw runFailure;
  if (!normalShutdown || !listenersClosed) {
    throw new Error('Folia did not shut down normally after inventory recovery');
  }
}

run().catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
