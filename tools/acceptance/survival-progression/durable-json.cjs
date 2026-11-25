const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');

// Commit a complete, flushed sibling file before replacing the previous snapshot.
// A process interruption before rename leaves the previous journal intact. Windows
// does not provide directory fsync through Node; this is not a power-loss guarantee.
function writeJson(file, value, {exclusive = false, io = fs} = {}) {
  const bytes = JSON.stringify(value, null, 2) + '\n';
  const temporary = `${file}.${randomUUID()}.tmp`;
  let fd;
  try {
    fd = io.openSync(temporary, 'wx');
    io.writeFileSync(fd, bytes);
    io.fsyncSync(fd);
    io.closeSync(fd); fd = undefined;
    if (exclusive) io.linkSync(temporary, file);
    else io.renameSync(temporary, file);
    if (process.platform !== 'win32') {
      const directory = io.openSync(path.dirname(file), 'r');
      try { io.fsyncSync(directory); } finally { io.closeSync(directory); }
    }
  } finally {
    if (fd !== undefined) io.closeSync(fd);
    try { io.unlinkSync(temporary); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
}

module.exports = {writeJson};
