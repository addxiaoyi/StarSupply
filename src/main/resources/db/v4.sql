ALTER TABLE shipment ADD COLUMN route_risk REAL NOT NULL DEFAULT 0 CHECK(route_risk >= 0 AND route_risk <= 1);
CREATE TABLE shipment_escort (
  shipment TEXT NOT NULL REFERENCES shipment(id),
  player TEXT NOT NULL,
  joined INTEGER NOT NULL,
  PRIMARY KEY(shipment,player)
);
CREATE INDEX shipment_escort_player ON shipment_escort(player,joined);
PRAGMA user_version=4;
