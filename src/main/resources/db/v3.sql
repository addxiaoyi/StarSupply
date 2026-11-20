ALTER TABLE item_transfer ADD COLUMN exporter_nation TEXT;
ALTER TABLE item_transfer ADD COLUMN trade_agreement TEXT;
ALTER TABLE item_transfer ADD COLUMN agreement_limit INTEGER NOT NULL DEFAULT 0 CHECK(agreement_limit >= 0);
ALTER TABLE item_transfer ADD COLUMN trade_units INTEGER NOT NULL DEFAULT 0 CHECK(trade_units >= 0);
CREATE INDEX item_transfer_agreement ON item_transfer(trade_agreement,state) WHERE trade_agreement IS NOT NULL;
PRAGMA user_version=3;
