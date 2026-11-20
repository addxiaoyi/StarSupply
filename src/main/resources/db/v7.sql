CREATE TABLE campaign_siege (
  front TEXT PRIMARY KEY REFERENCES warehouse(id) ON DELETE CASCADE,
  army TEXT NOT NULL, nation TEXT NOT NULL,
  since INTEGER NOT NULL CHECK(since>0),
  last_observed INTEGER NOT NULL CHECK(last_observed>=since)
);
PRAGMA user_version=7;
