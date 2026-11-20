DROP INDEX transfer_pending;
CREATE UNIQUE INDEX transfer_pending ON item_transfer(player)
  WHERE state IN ('PREPARED','RETURNING');
PRAGMA user_version=2;
