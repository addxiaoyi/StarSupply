const assert = require('node:assert/strict');
const {test} = require('node:test');
const {runtimeHealth} = require('./runtime-health.cjs');

const readyLog = '[Server thread/INFO]: [StarCore] STARCORE 1.0.0 enabled.\n'
  + '[Server thread/INFO]: [StarSupply] StarSupply 已启用，数据库 supply.db\n'
  + '[Server thread/INFO]: RCON running on 127.0.0.1:25739\n'
  + '[Server thread/INFO]: Done (12.0s)! For help, type "help"\n';

test('a listening server with failed plugins never permits the gameplay client', () => {
  const health = runtimeHealth(25739);
  health.push('[11:48:30] [Server thread/ERROR]: Error occurred while enabling StarCore v1.0.0\n');
  health.push('java.lang.OutOfMemoryError: Java heap space\n');
  health.push(readyLog);
  assert.equal(health.ready, false);
  assert.match(health.failure.message, /enabling StarCore/);
});

test('startup waits for both dependencies to finish enabling, beyond open listeners', () => {
  const health = runtimeHealth(25739);
  health.push(readyLog.split('\n').slice(2).join('\n'));
  assert.equal(health.ready, false);
  health.push(readyLog);
  assert.equal(health.ready, true);
});

test('UTF-8 split at every byte still recognizes the Chinese enable message', () => {
  const health = runtimeHealth(25739);
  for (const byte of Buffer.from(readyLog)) health.push(Buffer.from([byte]));
  assert.equal(health.ready, true);
});

test('Windows console replacement characters cannot hide the ASCII startup milestone', () => {
  const health = runtimeHealth(25739);
  health.push(readyLog.replace('已启用，数据库', '\uFFFD\uFFFD\uFFFD'));
  assert.equal(health.ready, true);
});

test('stderr OOM persists beyond a long subsequent stdout stream', () => {
  const health = runtimeHealth(25739);
  health.push('java.lang.OutOfMem', 'stderr');
  health.push(readyLog);
  health.push('oryError: Java heap space\n', 'stderr');
  health.push('ordinary log line\n'.repeat(10000));
  assert.equal(health.ready, false);
  assert.match(health.failure.message, /OutOfMemoryError/);
});

test('a dependency disabling after readiness invalidates the run', () => {
  const health = runtimeHealth(25739);
  health.push(readyLog);
  assert.equal(health.ready, true);
  health.push('[Server thread/INFO]: [StarCore] Disabling StarCore v1.0.0\n');
  assert.equal(health.ready, false);
});

test('an unterminated JVM allocation error is preserved on process exit', () => {
  const health = runtimeHealth(25739);
  health.push('Native memory allocation (malloc) failed to allocate 1024 bytes', 'stderr');
  health.finish();
  assert.match(health.failure.message, /Native memory allocation/);
});

test('normal shutdown permits dependency disable messages but still rejects OOM', () => {
  const health = runtimeHealth(25739);
  health.push(readyLog);
  health.beginShutdown();
  health.push('[Server thread/INFO]: [StarSupply] Disabling StarSupply v0.1.0\n');
  assert.equal(health.failure, undefined);
  health.push('java.lang.OutOfMemoryError: Java heap space\n');
  assert.match(health.failure.message, /OutOfMemoryError/);
});
