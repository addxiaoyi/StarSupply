const {test} = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const {stopViaRcon} = require('./rcon-stop.cjs');

function packet(id, type, text) {
  const body = Buffer.from(text), frame = Buffer.alloc(body.length + 14);
  frame.writeInt32LE(body.length + 10); frame.writeInt32LE(id, 4); frame.writeInt32LE(type, 8);
  body.copy(frame, 12); return frame;
}
async function serverFor(t, handler) {
  const server = net.createServer(socket => {
    let buffer = Buffer.alloc(0);
    socket.on('data', chunk => {
      buffer = Buffer.concat([buffer, chunk]);
      while (buffer.length >= 4 && buffer.length >= buffer.readInt32LE() + 4) {
        const length = buffer.readInt32LE();
        const frame = {id: buffer.readInt32LE(4), type: buffer.readInt32LE(8), body: buffer.subarray(12, length + 2).toString()};
        buffer = buffer.subarray(length + 4); handler(socket, frame);
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  return server.address().port;
}
test('authenticates before stop and accepts fragmented/coalesced protocol replies', async t => {
  const observed = [];
  const port = await serverFor(t, (socket, frame) => {
    observed.push(frame);
    if (frame.type === 3) {
      const reply = Buffer.concat([packet(1, 0, ''), packet(1, 2, '')]);
      socket.write(reply.subarray(0, 5));
      setImmediate(() => socket.write(reply.subarray(5)));
    } else socket.write(packet(2, 0, 'Stopping the server'));
  });
  await stopViaRcon(port, 'fixture-credential');
  assert.deepEqual(observed, [{id: 1, type: 3, body: 'fixture-credential'}, {id: 2, type: 2, body: 'stop'}]);
});
test('authentication rejection does not send a command or reveal credentials', async t => {
  let count = 0;
  const port = await serverFor(t, socket => { count++; socket.write(packet(-1, 2, '')); });
  await assert.rejects(stopViaRcon(port, 'private-fixture-value'), {message: 'RCON shutdown authentication failed'});
  assert.equal(count, 1);
});
test('connection loss is not a shutdown acknowledgement', async t => {
  const port = await serverFor(t, socket => socket.end());
  await assert.rejects(stopViaRcon(port, 'fixture'), /closed before acknowledging/);
});
test('malformed response and command refusal fail closed', async t => {
  const invalidPort = await serverFor(t, socket => socket.write(Buffer.from([0xff, 0xff, 0xff, 0xff])));
  await assert.rejects(stopViaRcon(invalidPort, 'fixture'), /Invalid RCON/);
  const refusedPort = await serverFor(t, (socket, frame) => {
    socket.write(frame.type === 3 ? packet(1, 2, '') : packet(2, 0, 'Unknown command'));
  });
  await assert.rejects(stopViaRcon(refusedPort, 'fixture'), /did not acknowledge/);
});
test('an unresponsive connection times out and invalid configuration never connects', async t => {
  const port = await serverFor(t, () => {});
  await assert.rejects(stopViaRcon(port, 'fixture', 100), /timed out/);
  await assert.rejects(stopViaRcon(port, undefined), /Invalid RCON shutdown configuration/);
});
