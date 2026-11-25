DROP INDEX consumption_expiry;
ALTER TABLE consumption RENAME TO consumption_v10;
CREATE TABLE consumption (
  army TEXT NOT NULL,
  slot INTEGER NOT NULL,
  interval_ms INTEGER NOT NULL CHECK(interval_ms BETWEEN 0 AND 86400000),
  consumed_at INTEGER NOT NULL,
  resource TEXT NOT NULL,
  demand INTEGER NOT NULL,
  units INTEGER NOT NULL,
  PRIMARY KEY(army,slot,interval_ms,resource)
);
INSERT INTO consumption(army,slot,interval_ms,consumed_at,resource,demand,units)
SELECT army,slot,0,unixepoch()*1000,resource,demand,units FROM consumption_v10;
DROP TABLE consumption_v10;
CREATE INDEX consumption_expiry ON consumption(consumed_at);
PRAGMA user_version=11;
