const {confirmServerBlock} = require('./server-blocks.cjs');

async function placeBlockConfirmed(bot, referenceBlock, face, blockName, label, log = () => {}, timeoutMs = 15000) {
  const target = referenceBlock.position.offset(face.x, face.y, face.z);
  if (bot.blockAt(target)?.name === blockName) throw new Error(`Placement target already contains ${blockName}`);
  const confirmation = confirmServerBlock(bot, target, block => block.name === blockName);
  let timer, clientError;
  try {
    const placed = Promise.resolve().then(() => bot.placeBlock(referenceBlock, face)).catch(error => {
      // A late server packet can arrive after Mineflayer's own placement listener
      // times out. Other failures (disconnect, missing held item) remain failures.
      if (!/Server refused to place .*: the block is still air|Server did not answer the placement at |Event blockUpdate:.*did not fire|No block has been placed/.test(error.message)) throw error;
      clientError = error.message;
    }).then(() => confirmation.promise);
    await Promise.race([placed, new Promise((resolve, reject) => {
      timer = setTimeout(() => reject(new Error(`No server-confirmed ${label} at ${target}`)), timeoutMs);
    })]);
    const block = bot.blockAt(target);
    if (block?.name !== blockName) throw new Error(`Server placement no longer present: ${blockName} at ${target}`);
    log('server_block_placed', {block: blockName, target, support: referenceBlock.position,
      clientError, playerPosition: bot.entity.position.clone()});
    return block;
  } finally {
    clearTimeout(timer);
    confirmation.dispose();
  }
}

module.exports = {placeBlockConfirmed};
