const fs = require('node:fs');
const path = require('node:path');
const net = require('node:net');
const zlib = require('node:zlib');
const crypto = require('node:crypto');
const {spawn, execFileSync} = require('node:child_process');

const mineflayerPath = process.env.STARSUPPLY_MINEFLAYER_MODULE
  || 'C:/Users/Administrator/Documents/starsupply-deps/minecraft-client-1.21.11/node_modules/mineflayer';
const mineflayer = require(mineflayerPath);
const [stageArgument, javaArgument, nodeArgument = process.execPath, pythonArgument] = process.argv.slice(2);
if (!stageArgument || !javaArgument || !pythonArgument) {
  throw new Error('Usage: run.cjs <isolatedPaperStage> <javaExecutable> <nodeExecutable> <pythonExecutable>');
}

const stage = path.resolve(stageArgument);
const java = path.resolve(javaArgument);
const node = path.resolve(nodeArgument);
const python = path.resolve(pythonArgument);
const proof = path.join(stage, 'folia-proof');
const moduleDirectory = path.join(stage, 'plugins', 'StarSupply');
const database = path.join(moduleDirectory, 'supply.db');
const inspector = path.resolve(__dirname, '..', 'payment-retry', 'inspect.py');
const rconScript = path.join(proof, 'rcon.cjs');
const manifestPath = path.join(stage,
  'folia-proof', 'backup-spending-retry-20261122-222930', 'runtime-state-compressed', 'manifest.json');
const backup = path.join(stage, 'folia-proof', 'backup-spending-retry-20261122-222930');
const runId = `paper-trade-rejections-${Date.now()}`;
const outputDirectory = path.join(proof, 'trade-rejections');
fs.mkdirSync(outputDirectory, {recursive: true});
const outputFile = path.join(outputDirectory, `${runId}.json`);
const serverLogPath = path.join(stage, 'logs', `${runId}-stdout.log`);
const serverErrorPath = path.join(stage, 'logs', `${runId}-stderr.log`);
const log = fs.createWriteStream(serverLogPath, {flags: 'wx'});
const errorLog = fs.createWriteStream(serverErrorPath, {flags: 'wx'});
const tracePath = path.join(outputDirectory, `${runId}-trace.jsonl`);
const trace = fs.createWriteStream(tracePath, {flags: 'wx'});

const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const players = [];
const playerDataBackups = new Map();
const evidence = {runId, server: {}, identities: {}, scenarios: [], cleanup: {activeFixtureRemoved: false}};
let server;
let serverExited = false;
let serverExitCode;
let serverExitSignal;
let warehouseLocation;
let outputTail = '';
let serverErrorTail = '';
let runFailure;
let stageMutationStarted = false;

function sha256(bytes) {
  return crypto.createHash('sha256').update(bytes).digest('hex').toUpperCase();
}

function writeTrace(event, detail = {}) {
  trace.write(JSON.stringify({at: new Date().toISOString(), event, ...detail}) + '\n');
}

function readProperties() {
  return Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
    .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('='))
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

function rcon(...commands) {
  const output = execFileSync(node, [rconScript, ...commands], {
    cwd: stage,
    env: {...process.env, STARSUPPLY_FOLIA_STAGE: stage},
    encoding: 'utf8',
    windowsHide: true,
    timeout: 30000
  });
  writeTrace('rcon', {commands, output: output.trim()});
  return output;
}

function inspect(purchaseId) {
  return JSON.parse(execFileSync(python, [inspector, database, purchaseId], {
    cwd: stage,
    encoding: 'utf8',
    windowsHide: true,
    timeout: 10000
  }));
}

function depotLocation() {
  if (warehouseLocation) return warehouseLocation;
  const query = [
    'import json, sqlite3, sys',
    'from pathlib import Path',
    'database = Path(sys.argv[1])',
    "with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:",
    "    row = connection.execute('SELECT id,world,x,y,z FROM warehouse WHERE id=?', ('alpha-depot',)).fetchone()",
    '    assert row is not None, "alpha-depot is missing"',
    '    print(json.dumps(dict(zip(("id", "world", "x", "y", "z"), row))))'
  ].join('\n');
  warehouseLocation = JSON.parse(execFileSync(python, ['-c', query, database], {
    cwd: stage, encoding: 'utf8', windowsHide: true, timeout: 10000
  }));
  return warehouseLocation;
}

function fixture(command, pattern) {
  const output = rcon(`supplytradeproof ${command}`);
  const match = output.match(pattern);
  if (!match) throw new Error(`Trade fixture command failed: ${output}`);
  return {output, match};
}

function agreement(seconds, units = 1, price = '2.50') {
  const result = fixture(`create ${seconds} ${units} ${price}`,
    /STARTRADE AGREEMENT ([0-9a-f-]{36}) active=true/i);
  return {id: result.match[1], output: result.output, seconds, units, price};
}

function balances() {
  const output = rcon(`supplytradeproof balances ${evidence.identities.carrier.uuid}`);
  const match = output.match(/STARTRADE BALANCES alpha=([0-9]+(?:\.[0-9]+)?) beta=([0-9]+(?:\.[0-9]+)?) seller=([0-9]+(?:\.[0-9]+)?)/);
  if (!match) throw new Error(`Could not read public StarCore balances: ${output}`);
  return {alpha: match[1], beta: match[2], seller: match[3], output};
}

function cents(value) {
  const [whole, fraction = ''] = value.split('.');
  return Number(whole) * 100 + Number((fraction + '00').slice(0, 2));
}

async function waitFor(read, label, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = read();
    if (value) return value;
    if (serverExited) throw new Error(`Paper exited while waiting for ${label}: ${outputTail}`);
    await pause(150);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

function connect(username) {
  const bot = mineflayer.createBot({
    host: '127.0.0.1',
    port: Number(evidence.server.gamePort),
    username,
    auth: 'offline',
    version: '1.21.11',
    hideErrors: false,
    checkTimeoutInterval: 90000
  });
  bot.tradeMessages = [];
  bot.on('messagestr', message => {
    const row = {at: new Date().toISOString(), message};
    bot.tradeMessages.push(row);
    writeTrace('chat', {player: username, message});
  });
  bot.on('error', error => {
    bot.clientError = error.stack || String(error);
    writeTrace('client-error', {player: username, error: bot.clientError});
  });
  bot.on('end', reason => {
    bot.endReason = String(reason || '');
    writeTrace('client-end', {player: username, reason: bot.endReason});
  });
  players.push(bot);
  return waitForSpawn(bot, username);
}

function waitForSpawn(bot, username) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${username} did not join the Paper stage`)), 45000);
    bot.once('spawn', () => {
      clearTimeout(timer);
      resolve(bot);
    });
    bot.once('error', error => {
      clearTimeout(timer);
      reject(error);
    });
  });
}

function itemCount(bot, itemName) {
  return bot.inventory.items().filter(item => item.name === itemName)
    .reduce((sum, item) => sum + item.count, 0);
}

async function createOrder(buyer, amount = '2.50') {
  const depot = depotLocation();
  await ensureAtDepot(buyer, depot);
  const messageOffset = buyer.tradeMessages.length;
  buyer.chat(`/supply buy alpha-depot food 1 ${amount}`);
  const orderMessage = await waitFor(() => buyer.tradeMessages.slice(messageOffset)
    .find(row => /采购单\s+[0-9a-f-]{36}/i.test(row.message)), 'a new Alpha FOOD purchase', 20000);
  const purchaseId = orderMessage.message.match(/[0-9a-f-]{36}/i)[0];
  const snapshot = await waitFor(() => {
    const current = inspect(purchaseId);
    return current.purchase?.state === 'OPEN' ? current : null;
  }, `purchase ${purchaseId} in OPEN state`);
  if (snapshot.purchase.resource !== 'FOOD' || snapshot.purchase.item_count !== 1
      || snapshot.purchase.price !== amount || snapshot.purchase.depot !== 'alpha-depot'
      || snapshot.warehouse.world !== depot.world) {
    throw new Error(`Unexpected open test purchase ${purchaseId}: ${JSON.stringify(snapshot.purchase)}`);
  }
  return {id: purchaseId, snapshot};
}

function depotDistance(bot, depot) {
  if (!bot.entity?.position) return Infinity;
  const position = bot.entity.position;
  return Math.hypot(position.x - depot.x, position.y - depot.y, position.z - depot.z);
}

async function ensureAtDepot(bot, depot) {
  if (depotDistance(bot, depot) <= 8) return;
  const command = `minecraft:teleport ${bot.username} ${depot.x} ${depot.y} ${depot.z}`;
  rcon(command);
  await waitFor(() => depotDistance(bot, depot) <= 8, `${bot.username} at alpha-depot`);
}

async function grantBread(seller, depot) {
  const before = itemCount(seller, 'bread');
  rcon('gamemode survival SupplyBuyer', 'gamemode survival SupplyCarrier',
    `minecraft:teleport SupplyCarrier ${depot.x} ${depot.y} ${depot.z}`,
    'give SupplyCarrier minecraft:bread 1');
  await waitFor(() => itemCount(seller, 'bread') === before + 1 && depotDistance(seller, depot) <= 8,
    'the Beta seller at alpha-depot with one test bread');
  return {before, afterGrant: itemCount(seller, 'bread')};
}

async function attemptRejectedSale({name, seller, reason, order}) {
  const inventory = await grantBread(seller, order.snapshot.warehouse);
  const initialBalances = balances();
  const chatOffset = seller.tradeMessages.length;
  writeTrace('sale-submitted', {scenario: name, orderId: order.id, inventory,
    position: seller.entity?.position ?? null});
  seller.chat(`/supply sell ${order.id}`);
  const message = await waitFor(() => seller.tradeMessages.slice(chatOffset)
    .find(row => row.message.includes(reason)), `${name} rejection message`);
  await pause(600);
  const finalSnapshot = inspect(order.id);
  const finalBalances = balances();
  const finalBread = itemCount(seller, 'bread');
  const noTransaction = finalSnapshot.purchase?.state === 'OPEN'
    && finalSnapshot.transfers.length === 0
    && finalSnapshot.settlements.length === 0
    && finalSnapshot.lots.length === 0;
  const unchangedBalances = initialBalances.alpha === finalBalances.alpha
    && initialBalances.beta === finalBalances.beta
    && initialBalances.seller === finalBalances.seller;
  const passed = noTransaction && unchangedBalances && finalBread === inventory.afterGrant;
  const record = {
    name,
    result: passed ? 'passed' : 'failed',
    reason,
    orderId: order.id,
    purchaseState: finalSnapshot.purchase?.state,
    transfers: finalSnapshot.transfers,
    settlements: finalSnapshot.settlements,
    inventory,
    breadAfterAttempt: finalBread,
    balancesBefore: initialBalances,
    balancesAfter: finalBalances,
    serverReply: message.message
  };
  evidence.scenarios.push(record);
  if (!passed) throw new Error(`${name} changed transaction state, balances, or inventory: ${JSON.stringify(record)}`);
  return record;
}

async function quotaAcceptance({buyer, seller}) {
  const permit = agreement(300, 1, '2.50');
  const order = await createOrder(buyer);
  const inventory = await grantBread(seller, order.snapshot.warehouse);
  const balancesBefore = balances();
  const chatOffset = seller.tradeMessages.length;
  writeTrace('sale-submitted', {scenario: 'agreement-quota-accepted-once', orderId: order.id,
    inventory, position: seller.entity?.position ?? null});
  seller.chat(`/supply sell ${order.id}`);
  let latestSnapshot;
  let snapshot;
  try {
    snapshot = await waitFor(() => {
      latestSnapshot = inspect(order.id);
      if (latestSnapshot.purchase?.state !== 'BOUGHT') return null;
      return latestSnapshot.transfers.some(row => row.state === 'STORED')
        && latestSnapshot.settlements.some(row => row.state === 'PAID') ? latestSnapshot : null;
    }, 'the first trade under the one-unit agreement quota', 30000);
  } catch (error) {
    evidence.failedQuotaAcceptance = {
      order: latestSnapshot || inspect(order.id),
      balance: balances(),
      sellerMessages: seller.tradeMessages.slice(chatOffset),
      sellerInventory: seller.inventory.items().map(row => ({name: row.name, count: row.count}))
    };
    writeTrace('quota-trade-timeout', evidence.failedQuotaAcceptance);
    throw error;
  }
  const settlement = snapshot.settlements.find(row => row.kind === 'PURCHASE');
  if (!settlement) throw new Error(`No completed purchase settlement: ${JSON.stringify(snapshot)}`);
  await waitFor(() => seller.tradeMessages.slice(chatOffset)
    .some(row => row.message.includes('军需物资已托管')), 'the inventory custody reply');
  const receiptResult = fixture(`receipt ${settlement.id}`,
    /STARTRADE RECEIPT starsupply:payment:[0-9a-f-]{36} present=true amount=([0-9]+\.[0-9]{2}) refunded=false/i);
  const firstBalancesAfter = balances();
  const sellerBreadAfter = itemCount(seller, 'bread');
  const first = {
    name: 'agreement-quota-accepted-once',
    result: 'passed',
    agreementId: permit.id,
    orderId: order.id,
    settlementId: settlement.id,
    item: 'minecraft:bread',
    receipt: receiptResult.output,
    purchaseState: snapshot.purchase.state,
    transferState: snapshot.transfers.find(row => row.id === settlement.id)?.state,
    settlementState: settlement.state,
    receiptAmount: receiptResult.match[1],
    lotWarehouse: snapshot.lots[0]?.warehouse,
    inventory,
    breadAfterSettlement: sellerBreadAfter,
    balancesBefore: balancesBefore,
    balancesAfter: firstBalancesAfter,
    amount: '2.50'
  };
  if (first.transferState !== 'STORED' || first.settlementState !== 'PAID'
      || first.receiptAmount !== '2.50' || first.lotWarehouse !== 'alpha-depot'
      || sellerBreadAfter !== inventory.before
      || cents(balancesBefore.alpha) <= cents(firstBalancesAfter.alpha)
      || cents(balancesBefore.seller) >= cents(firstBalancesAfter.seller)) {
    first.result = 'failed';
    throw new Error(`First agreement trade did not complete exactly once: ${JSON.stringify(first)}`);
  }
  evidence.scenarios.push(first);

  const exhausted = await createOrder(buyer);
  const rejected = await attemptRejectedSale({
    name: 'agreement-quota-exhausted', buyer, seller, order: exhausted,
    reason: 'StarCore 出口协定剩余额度不足'
  });
  return {first, rejected};
}

async function runScenarios(buyer, seller) {
  const buyerIdentity = fixture('identity 5ab1d958-3192-31da-88f6-5860ab3c3a3d',
    /STARTRADE IDENTITY 5ab1d958-3192-31da-88f6-5860ab3c3a3d nation=([0-9a-f-]{36})/i);
  const sellerIdentity = fixture('identity b1ef34be-1610-379d-b9d3-97068ff1b518',
    /STARTRADE IDENTITY b1ef34be-1610-379d-b9d3-97068ff1b518 nation=([0-9a-f-]{36})/i);
  const alpha = fixture('identity 5ab1d958-3192-31da-88f6-5860ab3c3a3d',
    /STARTRADE IDENTITY 5ab1d958-3192-31da-88f6-5860ab3c3a3d nation=([0-9a-f-]{36})/i).match[1];
  if (!buyerIdentity.output.includes(alpha) || sellerIdentity.match[1] === alpha) {
    throw new Error('Acceptance buyer and seller are not on separate StarCore nations');
  }
  evidence.identities = {
    buyer: {name: 'SupplyBuyer', uuid: '5ab1d958-3192-31da-88f6-5860ab3c3a3d', nation: alpha},
    carrier: {name: 'SupplyCarrier', uuid: 'b1ef34be-1610-379d-b9d3-97068ff1b518', nation: sellerIdentity.match[1]}
  };
  const balancesBefore = balances();

  const quota = await quotaAcceptance({buyer, seller});
  const expiredQuotaAgreement = fixture(`cancel ${quota.first.agreementId}`,
    new RegExp(`STARTRADE CANCEL ${quota.first.agreementId} removed=true`));
  evidence.scenarios.push({name: 'quota-agreement-cleanup', agreementId: quota.first.agreementId,
    removedAfterQuotaTest: true, result: 'passed', response: expiredQuotaAgreement.output.trim()});

  const expiredAgreement = agreement(3, 2, '2.50');
  const expiredOrder = await createOrder(buyer);
  const expiredState = await waitFor(() => {
    const output = rcon(`supplytradeproof state ${expiredAgreement.id}`);
    const state = output.match(new RegExp(
      `STARTRADE STATE ${expiredAgreement.id} present=(true|false) active=(true|false)`));
    if (!state) throw new Error(`Could not read agreement expiry state: ${output}`);
    return state[1] === 'true' && state[2] === 'false' ? output : null;
  }, 'a public StarCore agreement to expire', 15000);
  evidence.scenarios.push({name: 'agreement-expiry-observed', agreementId: expiredAgreement.id,
    observedActive: false, durationSeconds: expiredAgreement.seconds, observation: expiredState.trim()});
  await attemptRejectedSale({name: 'agreement-expired', seller, reason: '没有有效的 StarCore 国家贸易协定',
    order: expiredOrder});

  const cancelledAgreement = agreement(300, 2, '2.50');
  const cancellation = fixture(`cancel ${cancelledAgreement.id}`,
    new RegExp(`STARTRADE CANCEL ${cancelledAgreement.id} removed=true`));
  const cancelledOrder = await createOrder(buyer);
  await attemptRejectedSale({name: 'agreement-cancelled', seller, reason: '没有有效的 StarCore 国家贸易协定',
    order: cancelledOrder});

  const mispricedAgreement = agreement(300, 2, '3.00');
  const mispricedOrder = await createOrder(buyer);
  await attemptRejectedSale({name: 'agreement-price-mismatch', seller,
    reason: '跨国采购总价须符合 StarCore 协定单价与实际物资单位数', order: mispricedOrder});

  const embargoAgreement = agreement(300, 2, '2.50');
  const embargoResult = fixture('embargo 300', /STARTRADE EMBARGO ([0-9a-f-]{36}) effective=true/i);
  const embargoOrder = await createOrder(buyer);
  await attemptRejectedSale({name: 'starcore-resource-embargo', seller,
    reason: '该战略物资正在受 StarCore 禁运', order: embargoOrder});
  fixture(`lift ${embargoResult.match[1]}`, new RegExp(`STARTRADE LIFT ${embargoResult.match[1]} removed=true`));

  const finalBalances = balances();
  evidence.balanceDelta = {
    beforeAcceptance: balancesBefore,
    afterAcceptedQuotaTrade: quota.first.balancesAfter,
    afterAllRejectedTrades: finalBalances,
    noAdditionalTradeEffects: finalBalances.alpha === quota.first.balancesAfter.alpha
      && finalBalances.beta === quota.first.balancesAfter.beta
      && finalBalances.seller === quota.first.balancesAfter.seller
  };
  if (!evidence.balanceDelta.noAdditionalTradeEffects) {
    throw new Error(`Rejected trades changed StarCore balances: ${JSON.stringify(evidence.balanceDelta)}`);
  }
  if (!cancellation.output.includes('removed=true') || !embargoAgreement.id) {
    throw new Error('The agreement cancellation or control agreement was not observed');
  }
}

function restoreBaseline() {
  const entries = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  for (const item of entries) {
    const destination = path.resolve(stage, item.path);
    if (!destination.startsWith(stage + path.sep)) throw new Error(`Unsafe restore path: ${item.path}`);
    const archive = path.resolve(stage, item.compressedPath);
    if (!archive.startsWith(stage + path.sep)) throw new Error(`Unsafe backup path: ${item.compressedPath}`);
    const compressed = fs.readFileSync(archive);
    const bytes = item.sourceBytes === 0 && compressed.length === 0
      ? Buffer.alloc(0) : zlib.gunzipSync(compressed);
    if (bytes.byteLength !== item.sourceBytes || sha256(bytes) !== item.sourceSha256) {
      throw new Error(`Compressed backup verification failed: ${item.path}`);
    }
    fs.mkdirSync(path.dirname(destination), {recursive: true});
    fs.writeFileSync(destination, bytes);
  }

  const controls = [
    'folia-proof/actions-SupplyBuyer.json', 'folia-proof/cursor-SupplyBuyer.json',
    'logs/latest.log', 'plugins/.paper-remapped/index.json',
    'plugins/.paper-remapped/StarCore-1.0.0.jar', 'plugins/.paper-remapped/StarSupply-0.1.0.jar'
  ];
  for (const relative of controls) {
    fs.copyFileSync(path.join(backup, relative), path.join(stage, relative));
  }
  for (const [relative, contents] of playerDataBackups) {
    fs.writeFileSync(path.join(stage, relative), contents);
  }

  const generated = [
    'plugins/SupplyTradeProof.jar', 'plugins/.paper-remapped/SupplyTradeProof.jar'
  ];
  for (const relative of generated) {
    const source = path.join(stage, relative);
    if (!fs.existsSync(source)) continue;
    const disabled = `${source}.disabled-${runId}`;
    fs.renameSync(source, disabled);
  }

  const restored = entries.every(item => {
    const contents = fs.readFileSync(path.join(stage, item.path));
    return contents.length === item.sourceBytes && sha256(contents) === item.sourceSha256;
  });
  const controlsRestored = controls.every(relative =>
    sha256(fs.readFileSync(path.join(stage, relative))) === sha256(fs.readFileSync(path.join(backup, relative))));
  const playerFilesRestored = [...playerDataBackups.entries()].every(([relative, contents]) =>
    sha256(fs.readFileSync(path.join(stage, relative))) === sha256(contents));
  if (!restored || !controlsRestored) throw new Error('Stage backup hash verification failed after restoration');
  if (!playerFilesRestored) throw new Error('Player data restoration hash verification failed');

  const integrityCode = [
    'import sqlite3, sys',
    'from pathlib import Path',
    'root = Path(sys.argv[1])',
    "paths = ['plugins/CraftEngine/data.db', 'plugins/StarCore/religion.db', 'plugins/StarCore/starcore.db', 'plugins/StarSupply/insurance.db', 'plugins/StarSupply/supply.db', 'plugins/StarSupply/tavern.db']",
    'for relative in paths:',
    "    with sqlite3.connect((root / relative).as_uri() + '?mode=ro&immutable=1', uri=True) as db:",
    "        result = db.execute('PRAGMA integrity_check').fetchone()[0]",
    "        assert result == 'ok', (relative, result)",
    "        print(relative, result)"
  ].join('\n');
  const integrity = execFileSync(python, ['-c', integrityCode, stage], {
    cwd: stage, encoding: 'utf8', windowsHide: true, timeout: 30000
  });
  return {manifestFiles: entries.length, controlFiles: controls.length,
    playerFiles: playerDataBackups.size, hashesMatch: true,
    sqliteIntegrity: integrity.trim().split(/\r?\n/),
    worldRegionFilesRestored: false,
    worldInteractionScope: 'Players were moved in the existing depot area. No blocks or containers were changed.'};
}

async function run() {
  try {
    const properties = readProperties();
    if (properties['online-mode'] !== 'false' || properties['server-ip'] !== '127.0.0.1') {
      throw new Error('The Paper acceptance stage must use offline mode and bind to loopback');
    }
    evidence.server = {
      gamePort: Number(properties['server-port']),
      rconPort: Number(properties['rcon.port']),
      java,
      plugin: 'Paper 1.21.11-132'
    };
    const required = [
      java, node, python, inspector, rconScript, database, manifestPath,
      path.join(stage, 'plugins', 'StarSupply-0.1.0.jar'),
      path.join(stage, 'plugins', 'StarCore-1.0.0.jar'),
      path.resolve(__dirname, 'build', 'SupplyTradeProof.jar')
    ];
    for (const executable of required) {
      if (!fs.existsSync(executable)) throw new Error(`Missing acceptance input: ${executable}`);
    }
    for (const port of [evidence.server.gamePort, evidence.server.rconPort]) {
      if (!await portClosed(port)) throw new Error(`Paper listener must be closed before the test: ${port}`);
    }
    const playerDataPaths = [
      'starsupply-stage1-world/playerdata/5ab1d958-3192-31da-88f6-5860ab3c3a3d.dat',
      'starsupply-stage1-world/playerdata/5ab1d958-3192-31da-88f6-5860ab3c3a3d.dat_old',
      'starsupply-stage1-world/playerdata/b1ef34be-1610-379d-b9d3-97068ff1b518.dat',
      'starsupply-stage1-world/playerdata/b1ef34be-1610-379d-b9d3-97068ff1b518.dat_old'
    ];
    for (const relative of playerDataPaths) {
      const file = path.join(stage, relative);
      if (fs.existsSync(file)) playerDataBackups.set(relative, fs.readFileSync(file));
    }
    evidence.cleanup.playerFilesBackedUp = [...playerDataBackups.entries()].map(([file, contents]) => ({
      file,
      sha256: sha256(contents)
    }));
    const stagedProofJar = path.join(stage, 'plugins', 'SupplyTradeProof.jar');
    if (fs.existsSync(stagedProofJar)) throw new Error('An active trade proof fixture already exists');
    stageMutationStarted = true;
    fs.copyFileSync(path.resolve(__dirname, 'build', 'SupplyTradeProof.jar'), stagedProofJar);

    writeTrace('paper-starting', {gamePort: evidence.server.gamePort, rconPort: evidence.server.rconPort});
    server = spawn(java, ['-Xms256M', '-Xmx768M', '-XX:ActiveProcessorCount=2', '-XX:+UseSerialGC',
      '-Dfile.encoding=UTF-8', '-Dterminal.jline=false', '-Dterminal.ansi=false',
      '-jar', 'paper-1.21.11-132.jar', 'nogui'], {
      cwd: stage,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });
    server.stdout.on('data', chunk => {
      const text = chunk.toString();
      outputTail = (outputTail + text).slice(-30000);
      log.write(chunk);
    });
    server.stderr.on('data', chunk => {
      const text = chunk.toString();
      serverErrorTail = (serverErrorTail + text).slice(-10000);
      errorLog.write(chunk);
    });
    server.on('exit', (code, signal) => {
      serverExited = true;
      serverExitCode = code;
      serverExitSignal = signal;
    });
    await waitFor(() => /Done \([\d.]+s\)! For help/i.test(outputTail), 'Paper full startup', 120000);
    evidence.server.started = true;
    writeTrace('paper-started');
    if (/\[SupplyTradeProof\].*ERROR/.test(outputTail) || /\[StarSupply\].*ERROR/.test(outputTail)) {
      throw new Error(`Paper startup reported a test plugin failure: ${outputTail}`);
    }

    const buyer = await connect('SupplyBuyer');
    const seller = await connect('SupplyCarrier');
    await waitFor(() => seller.entity?.position && buyer.entity?.position, 'both test players to load');
    await rcon('gamemode survival SupplyBuyer', 'gamemode survival SupplyCarrier');
    const depot = depotLocation();
    await Promise.all([ensureAtDepot(buyer, depot), ensureAtDepot(seller, depot)]);
  await runScenarios(buyer, seller);
    evidence.result = 'passed';
    evidence.full_goal_complete = false;
  } catch (error) {
    runFailure = error;
    evidence.result = 'failed';
    evidence.failure = error.stack || String(error);
    writeTrace('test-failed', {error: evidence.failure});
  } finally {
    for (const player of players) {
      try { if (player.player) player.quit(`Trade acceptance ${runId} complete`); } catch {}
    }
    if (server && !serverExited) {
      try {
        await rcon('stop');
        await waitFor(() => serverExited, 'Paper normal shutdown', 60000);
        evidence.cleanup.normalStop = serverExitCode === 0
          && outputTail.includes('Stopping server')
          && outputTail.includes('All dimensions are saved');
      } catch (stopFailure) {
        evidence.cleanup.normalStop = false;
        evidence.cleanup.stopFailure = stopFailure.stack || String(stopFailure);
        if (!serverExited) server.kill();
        await new Promise(resolve => {
          if (serverExited) return resolve();
          server.once('exit', resolve);
        });
      }
    }
    await pause(500);
    if (server) {
      evidence.server.exitCode = serverExitCode;
      evidence.server.exitSignal = serverExitSignal;
      evidence.server.outputSha256 = sha256(Buffer.from(outputTail));
      evidence.server.errorOutputTail = serverErrorTail;
    }
    if (stageMutationStarted) {
      try {
        evidence.cleanup.restoration = restoreBaseline();
        evidence.cleanup.activeFixtureRemoved = !fs.existsSync(path.join(stage, 'plugins', 'SupplyTradeProof.jar'))
          && !fs.existsSync(path.join(stage, 'plugins', '.paper-remapped', 'SupplyTradeProof.jar'));
        evidence.cleanup.listenersReleased = await portClosed(evidence.server.gamePort)
          && await portClosed(evidence.server.rconPort);
      } catch (restoreFailure) {
        evidence.cleanup.restorationFailure = restoreFailure.stack || String(restoreFailure);
        if (!runFailure) runFailure = restoreFailure;
        evidence.result = 'failed';
      }
    } else {
      evidence.cleanup.restorationNotRequired = true;
    }
    await Promise.all([
      new Promise(resolve => log.end(resolve)),
      new Promise(resolve => errorLog.end(resolve)),
      new Promise(resolve => trace.end(resolve))
    ]);
    if (fs.existsSync(serverLogPath)) evidence.server.stdoutLogSha256 = sha256(fs.readFileSync(serverLogPath));
    if (fs.existsSync(serverErrorPath)) evidence.server.stderrLogSha256 = sha256(fs.readFileSync(serverErrorPath));
    evidence.server.tracePath = path.relative(stage, tracePath).replaceAll(path.sep, '/');
    evidence.server.traceSha256 = sha256(fs.readFileSync(tracePath));
    fs.writeFileSync(outputFile, JSON.stringify(evidence, null, 2) + '\n');
  }
}

run().then(() => {
  console.log(JSON.stringify({outputFile, result: evidence.result, scenarios: evidence.scenarios, cleanup: evidence.cleanup}, null, 2));
  if (runFailure || evidence.result !== 'passed') {
    console.error(runFailure?.stack || evidence.failure);
    process.exitCode = 1;
  }
}).catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
