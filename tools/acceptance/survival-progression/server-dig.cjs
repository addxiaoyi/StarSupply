const {confirmServerBlock} = require('./server-blocks.cjs');

// Minecraft 1.21.11: client prediction is not evidence of a mined block.
function installServerDigConfirmation(bot, log = () => {}, timeoutMs = 25000) {
  const original = bot.dig;
  bot.dig = async (block, forceLook, face) => {
    const position = {...block.position};
    const confirmation = confirmServerBlock(bot, position,
      state => ['air', 'cave_air', 'void_air', 'water'].includes(state.name));
    let timeout;
    const deadline = new Promise(resolve => {
      timeout = setTimeout(() => resolve({ok: false}), timeoutMs);
    });
    try {
      const completed = Promise.resolve().then(() => original.call(bot, block, forceLook, face))
        .then(() => confirmation.promise).then(() => ({ok: true}));
      const result = await Promise.race([completed, deadline]);
      if (!result.ok) {
        bot.stopDigging?.();
        throw new Error(`No server-confirmed removal of ${block.name} at ${position.x},${position.y},${position.z}`);
      }
      log('server_block_removed', {block: block.name, position});
    } finally {
      clearTimeout(timeout);
      confirmation.dispose();
    }
  };
  return () => { bot.dig = original; };
}

module.exports = {installServerDigConfirmation};
