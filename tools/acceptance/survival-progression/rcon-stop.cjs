const net = require('node:net');

// Console command sources on Folia may have no world after a plugin startup failure.
// RCON supplies a world and can still request a normal save-and-stop in that case.
function stopViaRcon(port, password, timeoutMs = 10000) {
  if (!Number.isInteger(port) || port < 1 || port > 65535 || typeof password !== 'string' || !password
      || !Number.isSafeInteger(timeoutMs) || timeoutMs < 1) {
    return Promise.reject(new Error('Invalid RCON shutdown configuration'));
  }
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({host: '127.0.0.1', port});
    let buffer = Buffer.alloc(0);
    let authenticated = false;
    let complete = false;
    const finish = error => {
      if (complete) return;
      complete = true;
      clearTimeout(timer);
      socket.destroy();
      if (error) reject(error); else resolve();
    };
    const timer = setTimeout(() => finish(new Error('RCON shutdown timed out')), timeoutMs);
    function send(id, type, text) {
      const body = Buffer.from(text, 'utf8');
      const frame = Buffer.alloc(body.length + 14);
      frame.writeInt32LE(body.length + 10);
      frame.writeInt32LE(id, 4);
      frame.writeInt32LE(type, 8);
      body.copy(frame, 12);
      socket.write(frame);
    }
    socket.on('connect', () => send(1, 3, password));
    socket.on('error', error => finish(new Error(`RCON shutdown connection failed: ${error.code || 'unknown'}`)));
    socket.on('close', () => finish(new Error('RCON closed before acknowledging shutdown')));
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 4 && !complete) {
        const length = buffer.readInt32LE();
        if (length < 10 || length > 2 * 1024 * 1024) {
          finish(new Error('Invalid RCON shutdown response size')); return;
        }
        if (buffer.length < length + 4) return;
        const id = buffer.readInt32LE(4), type = buffer.readInt32LE(8);
        const reply = buffer.subarray(12, length + 2).toString('utf8');
        buffer = buffer.subarray(length + 4);
        if (id === -1) { finish(new Error('RCON shutdown authentication failed')); return; }
        if (!authenticated && id === 1 && type === 2) {
          authenticated = true;
          send(2, 2, 'stop');
        } else if (authenticated && id === 2 && type === 0) {
          if (/Stopping (?:the )?server/i.test(reply)) finish();
          else finish(new Error('RCON did not acknowledge the stop command'));
        }
      }
    });
  });
}

module.exports = {stopViaRcon};
