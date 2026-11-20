ALTER TABLE item_transfer ADD COLUMN tax_type TEXT;
ALTER TABLE item_transfer ADD COLUMN tax_amount TEXT NOT NULL DEFAULT '0.00';
CREATE TABLE settlement_tax (
  settlement TEXT PRIMARY KEY REFERENCES settlement(id),
  state TEXT NOT NULL CHECK(state IN ('QUOTED','FUNDING','FUNDED','REVIEW')),
  updated INTEGER NOT NULL
);
PRAGMA user_version=6;
