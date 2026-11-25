// Observe protocol packets, never Mineflayer's locally predicted blockUpdate events.
function confirmServerBlock(bot, position, accepts) {
  let finish;
  const promise = new Promise(resolve => { finish = resolve; });
  const observe = (x, y, z, stateId) => {
    if (x !== position.x || y !== position.y || z !== position.z) return;
    const block = bot.registry.blocksByStateId[stateId];
    if (block && accepts(block)) finish({stateId, name: block.name});
  };
  const single = packet => observe(packet.location.x, packet.location.y, packet.location.z, packet.type);
  const multiple = packet => {
    if (!packet.chunkCoordinates) return;
    for (const record of packet.records) {
      const packed = typeof record === 'bigint' ? Number(record) : record;
      if (!Number.isSafeInteger(packed) || packed < 0) continue;
      observe(packet.chunkCoordinates.x * 16 + (Math.floor(packed / 256) % 16),
        packet.chunkCoordinates.y * 16 + (packed % 16),
        packet.chunkCoordinates.z * 16 + (Math.floor(packed / 16) % 16), Math.floor(packed / 4096));
    }
  };
  bot._client.on('block_change', single);
  bot._client.on('multi_block_change', multiple);
  return {promise, dispose() {
    bot._client.off('block_change', single);
    bot._client.off('multi_block_change', multiple);
  }};
}

module.exports = {confirmServerBlock};
