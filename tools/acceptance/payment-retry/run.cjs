const fs = require('node:fs');
const path = require('node:path');
const {execFileSync} = require('node:child_process');
const mineflayerPath = process.env.STARSUPPLY_MINEFLAYER_MODULE
  || 'C:/Users/Administrator/Documents/starsupply-deps/minecraft-client-1.21.11/node_modules/mineflayer';
const mineflayer = require(mineflayerPath);

const [stageArgument, pythonArgument, orderAmount = '5.00', player = 'SupplyBuyer'] = process.argv.slice(2);
if (!stageArgument || !pythonArgument) {
  throw new Error('Usage: run.cjs <isolatedPaperStage> <pythonExecutable> [orderAmount] [player]');
}
if (!/^[0-9]{1,9}\.[0-9]{2}$/.test(orderAmount)) throw new Error('Pass an exact positive amount such as 5.00');
if (player !== 'SupplyBuyer') throw new Error('This fixture uses the saved SupplyBuyer profile');

const stage = path.resolve(stageArgument);
const python = path.resolve(pythonArgument);
const properties = Object.fromEntries(fs.readFileSync(path.join(stage, 'server.properties'), 'utf8')
  .split(/\r?\n/).filter(line => line && !line.startsWith('#') && line.includes('=')).map(line => {
    const separator = line.indexOf('=');
    return [line.slice(0, separator), line.slice(separator + 1)];
  }));
if (properties['online-mode'] !== 'false' || properties['server-ip'] !== '127.0.0.1') {
  throw new Error('The isolated Paper server must use offline mode and bind to loopback');
}

const proof = path.join(stage, 'folia-proof');
const database = path.join(stage, 'plugins', 'StarSupply', 'supply.db');
const inspector = path.join(__dirname, 'inspect.py');
const rconScript = path.join(proof, 'rcon.cjs');
const faultEvidence = path.join(stage, 'plugins', 'StarSupplyFaultBootstrap', 'fault-evidence.jsonl');
const runId = `paper-spending-retry-${Date.now()}`;
const playerUuid = '5ab1d958-3192-31da-88f6-5860ab3c3a3d';
const buyerNation = 'b361b25b-b7a4-460d-be8d-8237afdf0920';
const botLog = path.join(proof, `bot-${player}.jsonl`);
const actionFile = path.join(proof, `actions-${player}.json`);
const cursorFile = path.join(proof, `cursor-${player}.json`);
const outputDirectory = path.join(proof, 'payment-retry');
const outputFile = path.join(outputDirectory, `${runId}.json`);
const delay = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
for (const required of [python, inspector, rconScript, faultEvidence, database, botLog, actionFile, cursorFile]) {
  if (!fs.existsSync(required)) throw new Error(`Missing payment-retry input: ${required}`);
}

function rcon(...commands) {
  return execFileSync(process.execPath, [rconScript, ...commands], {
    cwd: stage,
    env: {...process.env, STARSUPPLY_FOLIA_STAGE: stage},
    encoding: 'utf8',
    windowsHide: true,
    timeout: 30000
  });
}

function inspect(purchaseId) {
  return JSON.parse(execFileSync(python, [inspector, database, purchaseId], {
    cwd: stage,
    encoding: 'utf8',
    windowsHide: true
  }));
}

function evidenceRows(byteOffset) {
  const content = fs.readFileSync(faultEvidence).subarray(byteOffset).toString('utf8');
  return content.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line));
}

function balances() {
  const output = rcon(`supplyfault balances ${buyerNation} ${playerUuid}`);
  const match = output.match(/treasury=([0-9]+(?:\.[0-9]{2})?).*playerBalance=([0-9]+(?:\.[0-9]{2})?)/);
  if (!match) throw new Error(`Could not read public balances: ${output}`);
  return {treasury: match[1], player: match[2], output};
}

function integerCents(amount) {
  const [units, fraction] = amount.split('.');
  return Number(units) * 100 + Number(fraction);
}

function formatCents(cents) {
  const whole = Math.floor(cents / 100);
  const fraction = String(cents % 100).padStart(2, '0');
  return `${whole}.${fraction}`;
}

const paymentKey = settlementId => `starsupply:payment:${settlementId}`;

async function waitFor(read, label, timeoutMs = 30000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = read();
    if (value) return value;
    await delay(100);
  }
  throw new Error(`Timed out waiting for ${label}`);
}

async function main() {
  fs.mkdirSync(outputDirectory, {recursive: true});
  const evidenceStart = fs.statSync(faultEvidence).size;
  const logStart = fs.statSync(botLog).size;
  const beforeBalance = balances();
  const buyer = mineflayer.createBot({
    host: '127.0.0.1',
    port: Number(properties['server-port']),
    username: player,
    auth: 'offline',
    version: '1.21.11',
    hideErrors: false,
    checkTimeoutInterval: 90000
  });
  let clientExit;
  const clientClosed = new Promise(resolve => buyer.once('end', reason => {
    clientExit = {reason};
    resolve(clientExit);
  }));
  const messages = [];
  buyer.on('messagestr', message => messages.push({at: new Date().toISOString(), message}));
  const spawned = new Promise((resolve, reject) => {
    buyer.once('spawn', resolve);
    buyer.once('error', reject);
    setTimeout(() => reject(new Error('Timed out waiting for SupplyBuyer to join')), 45000).unref();
  });
  let failure;
  try {
    await spawned;
    const inventoryBefore = buyer.inventory.items().filter(item => item.name === 'iron_ingot')
      .reduce((sum, item) => sum + item.count, 0);
    rcon(`gamemode survival ${player}`, `give ${player} minecraft:iron_ingot 1`);
    await waitFor(() => buyer.inventory.items().filter(item => item.name === 'iron_ingot')
      .reduce((sum, item) => sum + item.count, 0) === inventoryBefore + 1, 'one test iron ingot');

    const purchaseCommandAt = messages.length;
    buyer.chat(`/supply buy alpha-depot equipment 1 ${orderAmount}`);
    const purchaseMessage = await waitFor(() => messages.slice(purchaseCommandAt)
      .find(row => /采购单\s+[0-9a-f-]{36}/i.test(row.message)), 'new public purchase command', 45000);
    const purchaseId = purchaseMessage.message.match(/[0-9a-f-]{36}/i)[0];
    const beforePurchase = inspect(purchaseId);
    if (beforePurchase.purchase?.state !== 'OPEN' || beforePurchase.purchase.item_count !== 1) {
      throw new Error(`New purchase is not open: ${JSON.stringify(beforePurchase)}`);
    }
    const warehouse = beforePurchase.warehouse;
    const position = buyer.entity.position;
    const distance = Math.hypot(position.x - warehouse.x, position.y - warehouse.y, position.z - warehouse.z);
    if (distance > 8) throw new Error(`Buyer is ${distance.toFixed(1)} blocks from alpha-depot`);

    const armedOutput = rcon(`supplyfault spend-retry ${beforePurchase.purchase.nation} ${orderAmount}`);
    if (!armedOutput.includes('SUPPLYFAULT ARMED ONCE')) throw new Error(`Spending fault did not arm: ${armedOutput}`);
    const saleCommandAt = messages.length;
    buyer.chat(`/supply sell ${purchaseId}`);
    const transferState = await waitFor(() => {
      const state = inspect(purchaseId);
      const transfer = state.transfers.find(row => row.player === playerUuid);
      const settlement = state.settlements.find(row => row.kind === 'PURCHASE');
      return transfer && settlement && {state, transfer, settlement};
    }, 'physical inventory handoff and pending settlement', 45000);
    if (transferState.settlement.state !== 'PENDING') {
      throw new Error(`The initial settlement was not left pending: ${JSON.stringify(transferState)}`);
    }

    const injected = await waitFor(() => evidenceRows(evidenceStart)
      .find(row => row.event === 'spending_result_injected'
        && row.transaction_id === paymentKey(transferState.settlement.id)), 'synthetic StarCore response');
    if (injected.delegate_called !== 'false'
        || injected.returned_status !== 'INSUFFICIENT_FUNDS'
        || integerCents(injected.returned_balance_after) < integerCents(orderAmount)) {
      throw new Error(`Injected result did not meet the retry precondition: ${JSON.stringify(injected)}`);
    }

    const pendingSnapshot = inspect(purchaseId);
    const pendingBalances = balances();
    const pendingReceipt = rcon(`supplyfault receipt ${paymentKey(transferState.settlement.id)}`);
    if (pendingBalances.treasury !== beforeBalance.treasury
        || pendingBalances.player !== beforeBalance.player
        || !pendingReceipt.includes('SUPPLYFAULT RECEIPT ABSENT')) {
      throw new Error('Balances or spending receipt changed before the authorized retry');
    }

    const completeSnapshot = await waitFor(() => {
      const state = inspect(purchaseId);
      return state.settlements.some(row => row.id === transferState.settlement.id && row.state === 'PAID')
        ? state : null;
    }, 'the original settlement retry to commit', 30000);
    const retryEvidence = await waitFor(() => evidenceRows(evidenceStart)
      .find(row => row.event === 'spending_retry_delegate_result'
        && row.transaction_id === paymentKey(transferState.settlement.id)), 'real StarCore retry result');
    const finalBalances = balances();
    const expectedTreasury = formatCents(integerCents(beforeBalance.treasury) - integerCents(orderAmount));
    const expectedPlayer = formatCents(integerCents(beforeBalance.player) + integerCents(orderAmount));
    const finalReceipt = rcon(`supplyfault receipt ${paymentKey(transferState.settlement.id)}`);
    const inventoryAfter = buyer.inventory.items().filter(item => item.name === 'iron_ingot')
      .reduce((sum, item) => sum + item.count, 0);
    const settlementCount = completeSnapshot.settlements.filter(row => row.kind === 'PURCHASE').length;
    const passed = retryEvidence.delegate_called === 'true'
      && retryEvidence.delegate_status === 'APPLIED'
      && finalReceipt.includes(`SUPPLYFAULT RECEIPT ${paymentKey(transferState.settlement.id)}`)
      && finalBalances.treasury === expectedTreasury
      && finalBalances.player === expectedPlayer
      && settlementCount === 1
      && completeSnapshot.purchase?.state === 'BOUGHT'
      && completeSnapshot.transfers.some(row => row.id === transferState.settlement.id && row.state === 'STORED')
      && inventoryAfter === inventoryBefore;
    const result = {
      runId,
      purchaseId,
      player,
      nation: beforePurchase.purchase.nation,
      amount: orderAmount,
      beforeBalance,
      injected,
      pendingSnapshot,
      pendingBalances,
      pendingReceipt,
      retryEvidence,
      completeSnapshot,
      finalBalances,
      expectedTreasury,
      expectedPlayer,
      finalReceipt,
      ironIngotsBeforeGrant: inventoryBefore,
      ironIngotsAfterSettlement: inventoryAfter,
      saleMessages: messages.slice(saleCommandAt),
      clientExit,
      passed
    };
    fs.writeFileSync(outputFile, JSON.stringify(result, null, 2) + '\n');
    console.log(JSON.stringify(result));
    if (!passed) throw new Error('Paper StarCore insufficient-funds retry acceptance failed');
  } catch (error) {
    failure = error;
  } finally {
    buyer.quit('Paper spending retry acceptance complete');
    await Promise.race([clientClosed, delay(5000)]);
  }
  if (failure) throw failure;
}

main().catch(error => {
  console.error(error.stack || String(error));
  process.exitCode = 1;
});
