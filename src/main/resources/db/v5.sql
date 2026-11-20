ALTER TABLE shipment ADD COLUMN risk_roll REAL NOT NULL DEFAULT 1 CHECK(risk_roll >= 0 AND risk_roll <= 1);
ALTER TABLE item_transfer ADD COLUMN convoy_roll REAL NOT NULL DEFAULT 1 CHECK(convoy_roll >= 0 AND convoy_roll <= 1);
PRAGMA user_version=5;
