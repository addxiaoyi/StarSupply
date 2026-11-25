DROP INDEX native_supply_pending;
ALTER TABLE native_supply_credit RENAME TO native_supply_credit_v9;
CREATE TABLE native_supply_credit (
  id TEXT PRIMARY KEY,
  army TEXT NOT NULL,
  nation TEXT NOT NULL,
  slot INTEGER NOT NULL,
  interval_ms INTEGER NOT NULL DEFAULT 0 CHECK(interval_ms BETWEEN 0 AND 86400000),
  points INTEGER NOT NULL CHECK(points BETWEEN 1 AND 10),
  state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPLIED','REVIEW')),
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  UNIQUE(army,slot,interval_ms)
);
INSERT INTO native_supply_credit(id,army,nation,slot,interval_ms,points,state,attempts,next_attempt,last_error)
SELECT id,army,nation,slot,0,points,state,attempts,next_attempt,last_error FROM native_supply_credit_v9;
DROP TABLE native_supply_credit_v9;
CREATE INDEX native_supply_pending ON native_supply_credit(state,next_attempt);
PRAGMA user_version=10;
