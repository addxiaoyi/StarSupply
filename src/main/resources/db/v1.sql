CREATE TABLE warehouse (
  id TEXT PRIMARY KEY, nation TEXT NOT NULL, kind TEXT NOT NULL CHECK(kind IN ('DEPOT','FRONT')),
  world TEXT NOT NULL, x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL, army TEXT UNIQUE,
  CHECK(kind = 'FRONT' OR army IS NULL)
);
CREATE INDEX warehouse_nation ON warehouse(nation);
CREATE TABLE road (
  id TEXT PRIMARY KEY, nation TEXT NOT NULL, origin TEXT NOT NULL REFERENCES warehouse(id),
  target TEXT NOT NULL REFERENCES warehouse(id), efficiency REAL NOT NULL,
  blocked INTEGER NOT NULL DEFAULT 0, core_line TEXT, CHECK(origin <> target)
);
CREATE INDEX road_nation ON road(nation);
CREATE TABLE purchase (
  id TEXT PRIMARY KEY, nation TEXT NOT NULL, depot TEXT NOT NULL REFERENCES warehouse(id),
  resource TEXT NOT NULL, item_count INTEGER NOT NULL CHECK(item_count > 0), price TEXT NOT NULL,
  state TEXT NOT NULL, seller TEXT, settlement TEXT UNIQUE, created INTEGER NOT NULL
);
CREATE INDEX purchase_board ON purchase(state, created);
CREATE TABLE shipment (
  id TEXT PRIMARY KEY, purchase TEXT NOT NULL REFERENCES purchase(id), nation TEXT NOT NULL,
  origin TEXT NOT NULL REFERENCES warehouse(id), target TEXT NOT NULL REFERENCES warehouse(id),
  reward TEXT NOT NULL, state TEXT NOT NULL, carrier TEXT, ready INTEGER NOT NULL DEFAULT 0,
  expires INTEGER NOT NULL DEFAULT 0, path TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL
);
CREATE UNIQUE INDEX shipment_active ON shipment(purchase) WHERE state IN ('OPEN','IN_TRANSIT','SETTLING','REVIEW','DELIVERED');
CREATE INDEX shipment_board ON shipment(state, created);
CREATE INDEX shipment_expiry ON shipment(expires) WHERE state = 'IN_TRANSIT';
CREATE TABLE item_transfer (
  id TEXT PRIMARY KEY, purchase TEXT NOT NULL REFERENCES purchase(id), player TEXT NOT NULL,
  resource TEXT NOT NULL, state TEXT NOT NULL, before_hash TEXT NOT NULL, after_hash TEXT NOT NULL,
  created INTEGER NOT NULL, return_id TEXT UNIQUE, note TEXT NOT NULL DEFAULT ''
);
CREATE UNIQUE INDEX transfer_pending ON item_transfer(player) WHERE state IN ('PREPARED','RETURNING')
  OR (state = 'REVIEW' AND note <> 'payment');
CREATE INDEX transfer_player ON item_transfer(player, state, created);
CREATE TABLE item_lot (
  transfer TEXT NOT NULL REFERENCES item_transfer(id), ordinal INTEGER NOT NULL,
  item_id TEXT NOT NULL, quality TEXT NOT NULL, quality_ratio REAL NOT NULL,
  resource TEXT NOT NULL, original_count INTEGER NOT NULL CHECK(original_count > 0),
  remaining INTEGER NOT NULL CHECK(remaining >= 0 AND remaining <= original_count),
  units_per_item INTEGER NOT NULL CHECK(units_per_item > 0), payload BLOB NOT NULL,
  warehouse TEXT REFERENCES warehouse(id), shipment TEXT REFERENCES shipment(id),
  PRIMARY KEY(transfer, ordinal)
);
CREATE INDEX lot_warehouse ON item_lot(warehouse, resource, shipment) WHERE remaining > 0;
CREATE TABLE settlement (
  id TEXT PRIMARY KEY, order_id TEXT NOT NULL, nation TEXT NOT NULL, player TEXT NOT NULL,
  kind TEXT NOT NULL CHECK(kind IN ('PURCHASE','TRANSPORT')), amount TEXT NOT NULL,
  state TEXT NOT NULL CHECK(state IN ('PENDING','PAID','REJECTED','REVIEW')),
  updated INTEGER NOT NULL
);
CREATE INDEX settlement_pending ON settlement(state, updated);
CREATE TABLE consumption (
  army TEXT NOT NULL, slot INTEGER NOT NULL, resource TEXT NOT NULL, demand INTEGER NOT NULL,
  units INTEGER NOT NULL, PRIMARY KEY(army, slot, resource)
);
CREATE INDEX consumption_expiry ON consumption(slot);
