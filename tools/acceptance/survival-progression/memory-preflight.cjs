const os = require('node:os');
const {execFileSync} = require('node:child_process');

const MIB = 1024 * 1024;

function memoryBudget(heap, availableBytes) {
  const match = /^(\d+)([kmg])$/i.exec(heap);
  const heapBytes = match ? Number(match[1]) * ({k: 1024, m: MIB, g: 1024 * MIB})[match[2].toLowerCase()] : NaN;
  if (!Number.isSafeInteger(heapBytes) || heapBytes <= 0) throw new Error('Invalid acceptance JVM heap size');
  if (!Number.isFinite(availableBytes) || availableBytes < 0) throw new Error('Host memory reading is unavailable');
  // Reserve space for JVM native allocations and the separate Mineflayer client.
  const requiredBytes = heapBytes + 512 * MIB;
  if (availableBytes < requiredBytes) {
    throw new Error(`Insufficient host memory for the acceptance server and client: `
      + `${Math.floor(availableBytes / MIB)} MiB available, ${Math.ceil(requiredBytes / MIB)} MiB required `
      + `with JVM heap ${heap}. Use a smaller isolated plugin set or free host memory before retrying.`);
  }
  return {heapBytes, availableBytes, requiredBytes};
}

function preflightMemory(heap) {
  let availableBytes = os.freemem();
  let source = 'free physical memory';
  if (process.platform === 'win32') {
    const commitKiB = Number(execFileSync('powershell.exe', [
      '-NoProfile', '-NonInteractive', '-Command',
      '(Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory'
    ], {encoding: 'utf8', windowsHide: true, timeout: 15000}).trim());
    if (!Number.isFinite(commitKiB) || commitKiB <= 0) throw new Error('Windows commit headroom is unavailable');
    availableBytes = Math.min(availableBytes, commitKiB * 1024);
    source = 'minimum of free physical memory and Windows commit headroom';
  }
  return {...memoryBudget(heap, availableBytes), source};
}

module.exports = {memoryBudget, preflightMemory};
