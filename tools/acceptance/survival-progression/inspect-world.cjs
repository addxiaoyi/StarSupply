// Read-only navigation planning from a stopped acceptance world. This does not prove acquisition.
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');

async function main() {
  const [world, mineflayerModule, xArg = '-7', yArg = '64', zArg = '53'] = process.argv.slice(2);
  if (!world || !mineflayerModule) throw new Error('Usage: inspect-world.cjs <world> <mineflayerModule> [x y z]');
  const origin = [xArg, yArg, zArg].map(Number);
  if (!origin.every(Number.isFinite)) throw new Error('Invalid survey origin');
  const nbt = require(path.join(path.dirname(path.resolve(mineflayerModule)), 'prismarine-nbt'));
  const wanted = new Set(['bee_nest', 'beehive', 'bamboo', 'sugar_cane', 'cobweb', 'wheat',
    'iron_ore', 'deepslate_iron_ore', 'coal_ore', 'sand', 'birch_log', 'oak_log', 'melon']);
  const results = Object.fromEntries([...wanted].map(name => [name, {count: 0, nearest: []}]));
  const lootContainers = [];
  let chunks = 0;
  for (const name of fs.readdirSync(path.join(world, 'region')).filter(name => /^r\.-?\d+\.-?\d+\.mca$/.test(name))) {
    const file = fs.readFileSync(path.join(world, 'region', name));
    for (let slot = 0; slot < 1024; slot++) {
      const offset = file.readUIntBE(slot * 4, 3) * 4096;
      if (!offset) continue;
      const length = file.readUInt32BE(offset);
      const compression = file[offset + 4];
      if (compression & 0x80) throw new Error(`External chunk storage requires explicit inspection: ${name}:${slot}`);
      const compressed = file.subarray(offset + 5, offset + 4 + length);
      const raw = compression === 2 ? zlib.inflateSync(compressed)
        : compression === 1 ? zlib.gunzipSync(compressed) : compression === 3 ? compressed : null;
      if (!raw) throw new Error(`Unsupported region compression ${compression}`);
      const chunk = nbt.simplify((await nbt.parse(raw)).parsed);
      chunks++;
      for (const entity of chunk.block_entities ?? []) {
        if (!['minecraft:chest', 'minecraft:barrel'].includes(entity.id)) continue;
        const position = [entity.x, entity.y, entity.z];
        const items = (entity.Items ?? []).map(item => ({id: item.id, count: item.count ?? item.Count}));
        if (!entity.LootTable && !items.some(item => /gunpowder|string/.test(item.id))) continue;
        lootContainers.push({position, distance: Math.hypot(...position.map((coordinate, axis) => coordinate - origin[axis])),
          lootTable: entity.LootTable ?? null, items});
      }
      for (const section of chunk.sections ?? []) {
        const states = section.block_states;
        const palette = states?.palette ?? [];
        const targets = new Map(palette.map((entry, index) => [index, entry])
          .filter(([index, entry]) => wanted.has(entry.Name.replace('minecraft:', ''))));
        if (!targets.size) continue;
        const bits = Math.max(4, Math.ceil(Math.log2(palette.length)));
        const perLong = Math.floor(64 / bits), mask = (1n << BigInt(bits)) - 1n;
        const packed = (states.data ?? []).map(value => typeof value === 'bigint' ? BigInt.asUintN(64, value)
          : (BigInt(value[0] >>> 0) << 32n) | BigInt(value[1] >>> 0));
        for (let block = 0; block < 4096; block++) {
          const index = palette.length === 1 ? 0
            : Number((packed[Math.floor(block / perLong)] >> BigInt((block % perLong) * bits)) & mask);
          const entry = targets.get(index);
          if (!entry) continue;
          const position = [chunk.xPos * 16 + (block & 15), section.Y * 16 + (block >> 8),
            chunk.zPos * 16 + ((block >> 4) & 15)];
          const distance = Math.hypot(...position.map((coordinate, axis) => coordinate - origin[axis]));
          const result = results[entry.Name.replace('minecraft:', '')];
          result.count++;
          if (result.nearest.length < 12 || distance < result.nearest.at(-1).distance) {
            result.nearest.push({position, distance, properties: entry.Properties ?? {}});
            result.nearest.sort((a, b) => a.distance - b.distance);
            result.nearest.length = Math.min(result.nearest.length, 12);
          }
        }
      }
    }
  }
  lootContainers.sort((a, b) => a.distance - b.distance);
  console.log(JSON.stringify({purpose: 'read-only terrain survey, not gameplay production evidence', origin, chunks, results,
    lootContainers: lootContainers.slice(0, 24)}, null, 2));
}
main().catch(error => { console.error(error.stack); process.exitCode = 1; });
