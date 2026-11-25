CREATE TABLE native_supply_credit (
  id TEXT PRIMARY KEY,
  army TEXT NOT NULL, nation TEXT NOT NULL, slot INTEGER NOT NULL,
  points INTEGER NOT NULL CHECK(points BETWEEN 1 AND 10),
  state TEXT NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','APPLIED','REVIEW')),
  attempts INTEGER NOT NULL DEFAULT 0,
  next_attempt INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  UNIQUE(army,slot)
);
CREATE INDEX native_supply_pending ON native_supply_credit(state,next_attempt);
PRAGMA user_version=9;
