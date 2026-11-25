// Drives normal player commands; requires the existing isolated foliaproof fixture and bot.cjs.
// No server start, shutdown, database writes, private fields or fabricated receipts.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const crypto = require('node:crypto');
const [stageArg, python, mode = 'run', requestArg] = process.argv.slice(2);
if (!stageArg || !python) throw new Error('run.cjs <isolatedStage> <pythonExe> [run|retry <requestUUID>]');
const stage = path.resolve(stageArg);
const proof = path.join(stage, 'folia-proof');
const output = path.join(stage, 'native-supply-proof');
fs.mkdirSync(output, {recursive: true});
const player = 'SupplyBuyer';
const run = `native-${Date.now()}`;
const logPath = path.join(output, `${run}.jsonl`);
const evidenceFile = path.join(stage, 'plugins/SupplyNativeProof/evidence.jsonl');
const delay = ms => new Promise(resolve => setTimeout(resolve, ms));
function rows(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, 'utf8').trim().split(/\r?\n/).filter(Boolean).map(s => JSON.parse(s));
}
function log(type, data = {}) {
  const row = {at: new Date().toISOString(), run, type, ...data};
  fs.appendFileSync(logPath, JSON.stringify(row) + '\n');
  console.log(JSON.stringify(row));
  return row;
}
function rcon(...commands) {
  const result = cp.execFileSync(process.execPath, [path.join(proof, 'rcon.cjs'), ...commands],
    {cwd: stage, env: {...process.env, STARSUPPLY_FOLIA_STAGE: stage}, encoding: 'utf8', windowsHide: true});
  log('rcon', {commands, result});
}
function action(suffix, actions) {
  const id = `${run}-${suffix}`;
  fs.writeFileSync(path.join(proof, `actions-${player}.json`), JSON.stringify({id, actions}));
  return id;
}
async function until(get, label, timeout = 40000, interval = 200) {
  const end = Date.now() + timeout;
  while (Date.now() < end) {
    const value = get(); if (value) return value;
    await delay(interval);
  }
  throw new Error(`Timed out: ${label}`);
}
function playerRows() { return rows(path.join(proof, `bot-${player}.jsonl`)); }
async function command(suffix, text, pattern) {
  const start = Date.now();
  const id = action(suffix, [{chat: text, wait: 500}]);
  await until(() => playerRows().find(r => r.type === 'actions-complete' && r.actionId === id), text);
  if (!pattern) return;
  const answer = await until(() => playerRows().find(r => r.type === 'chat'
    && Date.parse(r.at) >= start && pattern.test(r.message)), `response ${text}`);
  log('player-response', {command: text, response: answer.message});
  return answer.message;
}
function inspect(army, persistSnapshot = true) {
  const data = JSON.parse(cp.execFileSync(python, [path.join(__dirname, 'inspect.py'), stage, army],
    {encoding: 'utf8', windowsHide: true}));
  if (persistSnapshot) {
    fs.writeFileSync(path.join(output, `${run}-snapshot.json`), JSON.stringify(data, null, 2));
  }
  return data;
}
async function retry(request) {
  const start = Date.now();
  rcon(`supplynativeproof retry ${request}`);
  const result = await until(() => rows(evidenceFile).find(r => r.type === 'retry-passed'
    && r.receipt.request === request && Date.parse(r.at) >= start), `receipt retry ${request}`);
  log('retry-confirmed', result);
}
async function main() {
  log('deployed-jars', {jars: Object.fromEntries(fs.readdirSync(path.join(stage, 'plugins'))
    .filter(s => s.endsWith('.jar')).map(s => [s, crypto.createHash('sha256')
      .update(fs.readFileSync(path.join(stage, 'plugins', s))).digest('hex').toUpperCase()]))});
  if (mode === 'retry') {
    if (!/^[0-9a-f-]{36}$/i.test(requestArg || '')) throw new Error('Pass the existing consumption request UUID');
    await retry(requestArg);
    return;
  }
  if (mode !== 'run') throw new Error('Unknown mode');
  let start = Date.now();
  rcon('foliaproof state Alpha Gamma', 'foliaproof balances Alpha SupplyBuyer',
    'foliaproof army-create Alpha starsupply-stage1-world -21.5 86 68.5 10');
  const created = await until(() => rows(path.join(stage, 'plugins/FoliaSupplyProof/evidence.jsonl'))
    .find(r => r.type === 'army-created' && Date.parse(r.at) >= start), 'authority test army creation');
  const army = created.army;
  log('test-army', {army, created});
  rcon(`supplynativeproof prepare ${army} 15 70`, `supplynativeproof watch ${army} 600`,
    'foliaproof inventory SupplyBuyer native-before');
  await until(() => rows(evidenceFile).find(r => r.type === 'prepared' && r.after.army === army), 'low supply preparation');
  await command('rebind', `/supply rebind-front morale-front ${army}`, /morale-front/);
  await command('survival', `/gamemode survival ${player}`);
  const resources = [
    ['food', 'bread'], ['medical', 'honey_bottle'], ['equipment', 'iron_ingot'],
    ['materials', 'cobblestone'], ['engineering', 'scaffolding'], ['strategic', 'firework_rocket']
  ];
  const orders = [];
  for (const [resource, item] of resources) {
    const purchaseMessage = await command(`buy-${resource}`, `/supply buy alpha-depot ${resource} 8 40`,
      /采购单\s+[0-9a-f-]{36}/i);
    const purchase = purchaseMessage.match(/[0-9a-f-]{36}/i)[0];
    await command(`grant-${resource}`, `/give ${player} minecraft:${item} 8`);
    log('administrative-preparation', {resource, item, count: 8, gameplayProductionProof: false});
    await command(`sell-${resource}`, `/supply sell ${purchase}`);
    const paid = await until(() => rows(path.join(stage, 'plugins/FoliaSupplyProof/evidence.jsonl'))
      .find(r => r.type === 'settled' && r.kind === 'PURCHASE' && r.order === purchase), `purchase ${resource}`);
    if (paid.outcome !== 'PAID' || paid.count !== 1) throw new Error('Purchase was not uniquely paid');
    const shipmentMessage = await command(`ship-${resource}`, `/supply ship ${purchase} morale-front 15`,
      /运输单\s+[0-9a-f-]{36}/i);
    const shipment = shipmentMessage.match(/[0-9a-f-]{36}/i)[0];
    await command(`accept-${resource}`, `/supply accept ${shipment}`, /已领取/);
    await delay(1500);
    await command(`deliver-${resource}`, `/supply deliver ${shipment}`);
    const delivered = await until(() => rows(path.join(stage, 'plugins/FoliaSupplyProof/evidence.jsonl'))
      .find(r => r.type === 'settled' && r.kind === 'TRANSPORT' && r.order === shipment), `delivery ${resource}`);
    if (delivered.outcome !== 'PAID' || delivered.count !== 1) throw new Error('Transport was not uniquely paid');
    orders.push({resource, purchase, shipment, purchaseSettlement: paid.id, transportSettlement: delivered.id});
    log('physical-delivery', orders.at(-1));
  }
  const credit = await until(() => inspect(army, false).native_supply_credit.find(r => r.state === 'APPLIED'),
    'native supply durable outbox confirmation', 60000, 1000);
  log('credit-applied', {credit});
  await retry(credit.id);
  // A real one-minute maintenance tick must restore saved morale after physical native supply.
  const samples = () => rows(evidenceFile).filter(r => r.type === 'state' && r.label === 'watch'
    && r.army === army && r.nativeSupply >= 20);
  const recovered = await until(() => {
    const values = samples();
    const before = values.find(row => row.baseMorale < 100);
    return before && values.find(row => Date.parse(row.at) > Date.parse(before.at)
      && row.baseMorale > before.baseMorale) && {before, after: values.find(row =>
        Date.parse(row.at) > Date.parse(before.at) && row.baseMorale > before.baseMorale)};
  }, 'base morale maintenance recovery after native supply reaches 20', 150000);
  log('morale-recovered', recovered);
  const full = await until(() => rows(evidenceFile).find(r => r.type === 'state' && r.army === army
    && /coverage=1\.0(?:,|\])/.test(r.starSupply)), 'full six-resource coverage', 60000);
  log('full-coverage', full);
  const snapshot = inspect(army);
  const summary = {run, army, orders, credit, recovered, fullCoverage: full, snapshot,
    preparedByAdminFixture: true, chefProductionProvenByThisRun: false};
  const summaryPath = path.join(output, 'latest-run.json');
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2));
  rcon(`supplynativeproof state ${army}`, 'foliaproof balances Alpha SupplyBuyer', 'foliaproof inventory SupplyBuyer native-after');
  log('integration-passed-awaiting-normal-restart', {summaryPath, request: credit.id});
}
main().catch(error => { log('failed', {error: error.stack || String(error)}); process.exitCode = 1; });
