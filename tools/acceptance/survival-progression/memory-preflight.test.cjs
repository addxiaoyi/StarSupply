const assert = require('node:assert/strict');
const {test} = require('node:test');
const {memoryBudget} = require('./memory-preflight.cjs');

const MIB = 1024 * 1024;

test('rejects the observed low-memory full-stack launch before starting a JVM', () => {
  assert.throws(() => memoryBudget('768M', 1060 * MIB), /1060 MiB available, 1280 MiB required/);
});

test('a smaller isolated profile retains a client and native-memory reserve', () => {
  const budget = memoryBudget('384M', 1500 * MIB);
  assert.equal(budget.requiredBytes, 896 * MIB);
  assert.equal(budget.heapBytes, 384 * MIB);
});

test('all supported JVM units produce the same memory requirement', () => {
  assert.equal(memoryBudget('1G', 2048 * MIB).requiredBytes,
    memoryBudget('1048576k', 2048 * MIB).requiredBytes);
});

test('invalid heap sizes or missing host measurements cannot bypass preflight', () => {
  for (const heap of ['0M', '-1G', 'NaN', '99999999999999999999G']) {
    assert.throws(() => memoryBudget(heap, 4096 * MIB), /Invalid acceptance JVM heap/);
  }
  for (const available of [NaN, Infinity, -1]) {
    assert.throws(() => memoryBudget('1G', available), /Host memory reading/);
  }
});
