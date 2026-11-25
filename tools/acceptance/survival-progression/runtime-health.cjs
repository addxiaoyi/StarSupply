const {StringDecoder} = require('node:string_decoder');

// Keep failures after log rotation/tail truncation and decode each pipe independently.
function runtimeHealth(rconPort) {
  const streams = new Map();
  const state = {coreReady: false, supplyReady: false, serverReady: false, rconReady: false};
  let failure;
  let stopping = false;

  function line(text) {
    if (!failure && (/OutOfMemoryError|insufficient memory|Native memory allocation.*failed/i.test(text)
        || /(?:ERROR|SEVERE)\]/.test(text)
        || /Error occurred while enabling|Could not load (?:plugin|')|Error loading plugin/.test(text)
        || (!stopping && /Disabling (?:StarCore|StarSupply)\b/.test(text)))) {
      failure = new Error(`Folia runtime failed: ${text.trim()}`);
    }
    if (/\[StarCore\] STARCORE \S+ enabled\./.test(text)) state.coreReady = true;
    // Windows JVM console encoding can differ from the UTF-8 file appender.
    if (/\[StarSupply\] StarSupply .*supply\.db/.test(text)) state.supplyReady = true;
    if (text.includes('Done (')) state.serverReady = true;
    if (text.includes(`RCON running on 127.0.0.1:${rconPort}`)) state.rconReady = true;
  }

  return {
    push(chunk, channel = 'stdout') {
      if (!streams.has(channel)) streams.set(channel, {decoder: new StringDecoder('utf8'), pending: ''});
      const stream = streams.get(channel);
      stream.pending += stream.decoder.write(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
      const lines = stream.pending.split(/\r?\n/);
      stream.pending = lines.pop();
      for (const text of lines) line(text);
    },
    finish() {
      for (const stream of streams.values()) {
        line(stream.pending + stream.decoder.end());
        stream.pending = '';
      }
    },
    beginShutdown() { stopping = true; },
    get failure() { return failure; },
    get ready() { return !failure && Object.values(state).every(Boolean); },
    snapshot() { return {...state, failure: failure?.message ?? null}; }
  };
}

module.exports = {runtimeHealth};
