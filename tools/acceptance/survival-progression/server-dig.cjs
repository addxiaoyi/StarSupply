// Minecraft 1.21.11: client prediction is not evidence of a mined block.
function installServerDigConfirmation(bot, log = () => {}, timeoutMs = 25000) {
  const original = bot.dig;
  bot.dig = async (block, forceLook, face) => {
    const position = {...block.position};
    let finish;
    const confirmation = new Promise(resolve => { finish = resolve; });
    const observe = (x, y, z, stateId) => {
      if (x !== position.x || y !== position.y || z !== position.z) return;
      const name = bot.registry.blocksByStateId[stateId]?.name;
      if (['air', 'cave_air', 'void_air', 'water'].includes(name)) finish({ok: true});
    };
    const single = packet => observe(packet.location.x, packet.location.y, packet.location.z, packet.type);
    const multiple = packet => {
      if (!packet.chunkCoordinates) return;
      for (const record of packet.records) {
        // Values fit safely in JS integers for this protocol; use arithmetic for
        // the state ID rather than a signed 32-bit shift.
        const packed = typeof record === 'bigint' ? Number(record) : record;
        if (!Number.isSafeInteger(packed) || packed < 0) continue;
        observe(packet.chunkCoordinates.x * 16 + (Math.floor(packed / 256) % 16),
          packet.chunkCoordinates.y * 16 + (packed % 16),
          packet.chunkCoordinates.z * 16 + (Math.floor(packed / 16) % 16), Math.floor(packed / 4096));
      }
    };
    bot._client.on('block_change', single);
    bot._client.on('multi_block_change', multiple);
    let timeout;
    const deadline = new Promise(resolve => {
      timeout = setTimeout(() => resolve({ok: false}), timeoutMs);
    });
    try {
      const completed = Promise.resolve().then(() => original.call(bot, block, forceLook, face))
        .then(() => confirmation);
      const result = await Promise.race([completed, deadline]);
      if (!result.ok) {
        bot.stopDigging?.();
        throw new Error(`No server-confirmed removal of ${block.name} at ${position.x},${position.y},${position.z}`);
      }
      log('server_block_removed', {block: block.name, position});
    } finally {
      clearTimeout(timeout);
      bot._client.off('block_change', single);
      bot._client.off('multi_block_change', multiple);
    }
  };
  return () => { bot.dig = original; };
}

module.exports = {installServerDigConfirmation};
