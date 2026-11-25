const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');
const {spawn, execFile} = require('node:child_process');
const {promisify} = require('node:util');

const execFileAsync = promisify(execFile);
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));

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

async function until(read, label, timeoutMs) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = read();
    if (value) return value;
    await delay(100);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function run() {
  const [
    stageArgument,
    javaArgument,
    pythonArgument,
    createCountArgument = '24',
    lockHoldMsArgument = '1',
    lockPauseMsArgument = '2'
  ] = process.argv.slice(2);
  if (!stageArgument || !javaArgument || !pythonArgument) {
    throw new Error('Usage: run.cjs <isolatedFoliaStage> <javaExecutable> <pythonExecutable> [createCount]');
  }
  const stage = path.resolve(stageArgument);
  const java = path.resolve(javaArgument);
  const python = path.resolve(pythonArgument);
  const createCount = Number(createCountArgument);
  const lockHoldMs = Number(lockHoldMsArgument);
  const lockPauseMs = Number(lockPauseMsArgument);
  if (!Number.isInteger(createCount) || createCount < 1 || createCount > 64) {
    throw new Error('createCount must be between 1 and 64');
  }
  if (!Number.isInteger(lockHoldMs) || lockHoldMs < 1 || lockHoldMs > 20
      || !Number.isInteger(lockPauseMs) || lockPauseMs < 1 || lockPauseMs > 20) {
    throw new Error('SQLite lock timing must stay between 1 and 20 milliseconds');
  }

  const properties = readProperties(stage);
  if (properties['online-mode'] !== 'false' || properties['server-ip'] !== '127.0.0.1') {
    throw new Error('The acceptance stage must be a loopback-bound offline test server');
  }
  const gamePort = Number(properties['server-port']);
  const rconPort = Number(properties['rcon.port']);
  for (const port of [gamePort, rconPort]) {
    if (!Number.isInteger(port) || !await portClosed(port)) {
      throw new Error(`Acceptance listener must be closed before startup: ${port}`);
    }
  }

  const proofDirectory = path.join(stage, 'folia-proof');
  const writerScript = path.join(__dirname, 'write-lock.py');
  const database = path.join(stage, 'plugins', 'StarCore', 'starcore-retry-test.db');
  const evidenceFile = path.join(stage, 'plugins', 'FoliaSupplyProof', 'evidence.jsonl');
  const rconScript = path.join(proofDirectory, 'rcon.cjs');
  for (const required of [java, python, writerScript, database, evidenceFile, rconScript]) {
    if (!fs.existsSync(required)) throw new Error(`Missing retry-acceptance input: ${required}`);
  }

  const runId = `sqlite-retry-${Date.now()}`;
  const stopFile = path.join(proofDirectory, `${runId}.stop`);
  const outputFile = path.join(proofDirectory, `${runId}.json`);
  const serverLogPath = path.join(proofDirectory, `${runId}-server.log`);
  const serverErrorPath = path.join(proofDirectory, `${runId}-server-error.log`);
  const serverLog = fs.createWriteStream(serverLogPath, {flags: 'wx'});
  const serverErrorLog = fs.createWriteStream(serverErrorPath, {flags: 'wx'});
  const evidenceStart = fs.readFileSync(evidenceFile, 'utf8').length;
  const javaArguments = [
    '-Xms256M', '-Xmx1024M', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC',
    '-Dfile.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
    '-jar', 'folia.jar', 'nogui'
  ];

  let server;
  let writer;
  let serverTail = '';
  let writerOutput = '';
  let writerError = '';
  let serverExit;
  let serverFailure;
  let runFailure;
  let serverClosed;
  let writerClosed;

  try {
    server = spawn(java, javaArguments, {
      cwd: stage,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe']
    });
    server.stdout.pipe(serverLog);
    server.stderr.pipe(serverErrorLog);
    server.stdout.on('data', chunk => {
      serverTail = (serverTail + chunk.toString('utf8')).slice(-40000);
    });
    server.on('error', error => { serverFailure = error; });
    server.stdin.on('error', error => { serverFailure = error; });
    serverClosed = new Promise(resolve => server.once('close', (code, signal) => {
      serverExit = {code, signal};
      resolve(serverExit);
    }));

    await until(() => serverTail.includes(`RCON running on 127.0.0.1:${rconPort}`)
      && serverTail.includes('Done ('), 'Folia startup', 180000);

    writer = spawn(python, [writerScript, database, stopFile, String(lockHoldMs), String(lockPauseMs)], {
      cwd: stage,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    writer.stdout.on('data', chunk => { writerOutput += chunk.toString('utf8'); });
    writer.stderr.on('data', chunk => { writerError += chunk.toString('utf8'); });
    writerClosed = new Promise(resolve => writer.once('close', (code, signal) => resolve({code, signal})));
    await until(() => writerOutput.includes('"event": "writer_ready"'), 'SQLite writer setup', 15000);

    const attempts = Array.from({length: createCount}, (_, index) => {
      const coordinate = 8200 + index * 16;
      return {
        coordinate,
        operation: `army-create Gamma starsupply-stage1-world ${coordinate} 100 ${coordinate} 10`,
        command: `foliaproof army-create Gamma starsupply-stage1-world ${coordinate} 100 ${coordinate} 10`
      };
    });
    const operationResults = [];
    for (const attempt of attempts) {
      const retryStart = serverTail.split(/\r?\n/).filter(line => line.includes('SQLITE_BUSY_RETRY')).length;
      await execFileAsync(process.execPath, [rconScript, attempt.command], {
        cwd: stage,
        env: {...process.env, STARSUPPLY_FOLIA_STAGE: stage},
        windowsHide: true,
        timeout: 30000
      });
      const result = await until(() => {
        const addedEvidence = fs.readFileSync(evidenceFile, 'utf8').slice(evidenceStart);
        const rows = addedEvidence.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
        return rows.find(row =>
          (row.type === 'army-created' && Math.abs(row.location.x - attempt.coordinate) < 1)
          || (row.type === 'failed' && row.operation === attempt.operation));
      }, `public result for ${attempt.operation}`, 30000);
      const retryLines = serverTail.split(/\r?\n/).filter(line => line.includes('SQLITE_BUSY_RETRY'));
      const commandRetryLines = retryLines.slice(retryStart);
      operationResults.push({...result, retryLines: commandRetryLines});
      if (result.type === 'army-created'
          && commandRetryLines.some(line => line.includes('attempt=1') && line.includes('retryAllowed=true'))) {
        break;
      }
    }

    const createdArmies = operationResults.filter(row => row.type === 'army-created');
    const retryLines = serverTail.split(/\r?\n/).filter(line => line.includes('SQLITE_BUSY_RETRY'));
    const result = {
      runId,
      database: path.relative(stage, database).replace(/\\/g, '/'),
      requestedArmies: operationResults.length,
      maximumAttempts: createCount,
      lockHoldMs,
      lockPauseMs,
      createdArmies: createdArmies.length,
      retryLines,
      operationResults: operationResults.map(row => ({
        type: row.type,
        at: row.at,
        operation: row.operation,
        army: row.army,
        retryLines: row.retryLines
      })),
      publicEvidence: createdArmies.map(row => ({
        army: row.army,
        nation: row.nation,
        treasuryBefore: row.treasuryBefore,
        treasuryAfter: row.treasuryAfter,
        location: row.location
      })),
      passed: operationResults.some(row => row.type === 'army-created'
        && row.retryLines.some(line => line.includes('attempt=1') && line.includes('retryAllowed=true')))
    };
    fs.writeFileSync(outputFile, JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
    if (!result.passed || writerError) {
      throw new Error('SQLite busy retry runtime acceptance did not pass');
    }
  } catch (error) {
    runFailure = error;
  } finally {
    if (writer) {
      fs.writeFileSync(stopFile, 'stop\n');
      if (writerClosed) {
        await Promise.race([writerClosed, delay(10000)]).catch(error => {
          runFailure = runFailure ?? error;
        });
      }
    }
    if (server && serverExit === undefined) {
      server.stdin.write('stop\n');
      await Promise.race([serverClosed, delay(180000)]).catch(error => {
        runFailure = runFailure ?? error;
      });
    }
    fs.rmSync(stopFile, {force: true});
    serverLog.end();
    serverErrorLog.end();
  }
  if (runFailure) throw runFailure;
  if (serverFailure) throw serverFailure;
  if (serverExit?.code !== 0) throw new Error(`Folia shutdown was not clean: ${JSON.stringify(serverExit)}`);
  if (!await portClosed(gamePort) || !await portClosed(rconPort)) {
    throw new Error('Folia acceptance listeners remain open after shutdown');
  }
}

run().catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
