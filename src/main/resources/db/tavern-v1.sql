CREATE TABLE tavern_hub (
  id TEXT PRIMARY KEY, nation TEXT NOT NULL, world TEXT NOT NULL,
  x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
  registered_by TEXT NOT NULL, created INTEGER NOT NULL, verified INTEGER NOT NULL
);
CREATE INDEX tavern_hub_nation ON tavern_hub(nation);
CREATE TABLE scout_report (
  id TEXT PRIMARY KEY, nation TEXT NOT NULL, scout TEXT NOT NULL,
  road TEXT NOT NULL, road_nation TEXT NOT NULL, origin TEXT NOT NULL, target TEXT NOT NULL,
  distance REAL NOT NULL, efficiency REAL NOT NULL, blocked INTEGER NOT NULL, risk REAL NOT NULL,
  world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
  observed INTEGER NOT NULL, expires INTEGER NOT NULL,
  published_hub TEXT REFERENCES tavern_hub(id), published INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX scout_report_nation ON scout_report(nation,expires,observed);
CREATE INDEX scout_report_board ON scout_report(published_hub,nation,expires);
PRAGMA user_version=1;
