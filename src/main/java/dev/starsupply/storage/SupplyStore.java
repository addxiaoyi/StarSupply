package dev.starsupply.storage;

import static dev.starsupply.api.SupplyModels.*;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Durable inventory custody, order state, and settlement outbox for one server. */
public final class SupplyStore implements AutoCloseable {
    private static final int MAX_WAREHOUSES = 128;
    private static final int MAX_ROADS = 512;
    private static final int MAX_ORDERS = 128;
    private static final int BOARD_LIMIT = 125;
    private final Connection connection;

    public record Lot(String itemId, String quality, double qualityRatio, int count, int units, byte[] payload) {
        public Lot {
            Objects.requireNonNull(itemId);
            Objects.requireNonNull(quality);
            if (itemId.length() > 160 || quality.length() > 80 || !Double.isFinite(qualityRatio)
                    || qualityRatio < 0 || qualityRatio > 2 || count < 1 || count > 99
                    || units < count || units % count != 0 || units / count > 10_000
                    || payload.length < 1 || payload.length > 262_144) {
                throw new IllegalArgumentException("无效的物资批次");
            }
            payload = payload.clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    public record Transfer(UUID id, UUID purchaseId, UUID player, ResourceType resource, TransferState state,
                           String beforeHash, String afterHash, UUID returnId, String note,
                           UUID exporterNation, UUID tradeAgreement, long agreementLimit, long tradeUnits,
                           String taxType, BigDecimal taxAmount) {}
    public enum TaxState { QUOTED, FUNDING, FUNDED, REVIEW }
    public record Road(String id, UUID nationId, String origin, String target, double efficiency,
                       boolean blocked, UUID coreLine) {}
    public record Consumption(int demand, int units) {}
    public record MarketSignals(long stockItems, long demandItems) {}
    public record SiegeContinuity(long since, long lastObserved) {}

    public SupplyStore(Path path) throws SQLException, IOException {
        Objects.requireNonNull(path);
        // Paper, StarCore and optional plugins may load SQLite drivers in isolated class loaders.
        // Resolve and initialize our shaded driver through this plugin's class loader explicitly.
        Driver driver = new org.sqlite.JDBC();
        connection = driver.connect("jdbc:sqlite:" + path.toAbsolutePath(), new java.util.Properties());
        if (connection == null) throw new SQLException("StarSupply SQLite driver rejected its database URL");
        try {
            execute("PRAGMA foreign_keys=ON");
            execute("PRAGMA busy_timeout=5000");
            execute("PRAGMA journal_mode=WAL");
            execute("PRAGMA synchronous=FULL");
            int version = one("PRAGMA user_version", row -> row.getInt(1)).orElseThrow();
            if (version == 0) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v1.sql")) {
                    if (input == null) throw new IOException("Missing SQLite schema");
                    String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : schema.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        execute("PRAGMA user_version=1");
                        return null;
                    });
                }
                version = 1;
            }
            if (version == 1) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v2.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v2");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        return null;
                    });
                }
                version = 2;
            }
            if (version == 2) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v3.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v3");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        return null;
                    });
                }
                version = 3;
            }
            if (version == 3) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v4.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v4");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        return null;
                    });
                }
                version = 4;
            }
            if (version == 4) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v5.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v5");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        return null;
                    });
                }
                version = 5;
            }
            if (version == 5) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v6.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v6");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        return null;
                    });
                }
                version = 6;
            }
            if (version == 6) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v7.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v7");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) {
                            if (!ddl.isBlank()) execute(ddl);
                        }
                        return null;
                    });
                }
                version = 7;
            }
            if (version == 7) {
                try (InputStream input = SupplyStore.class.getResourceAsStream("/db/v8.sql")) {
                    if (input == null) throw new IOException("Missing SQLite migration v8");
                    String migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                    tx(() -> {
                        for (String ddl : migration.split(";")) if (!ddl.isBlank()) execute(ddl);
                        return null;
                    });
                }
                version = 8;
            }
            if (version != 8) {
                throw new SQLException("Unsupported StarSupply database schema " + version);
            }
        } catch (SQLException | IOException | RuntimeException ex) {
            connection.close();
            throw ex;
        }
    }

    public synchronized Warehouse addWarehouse(Warehouse warehouse) throws SQLException {
        return addWarehouse(warehouse, false);
    }

    /** Moves only an army's binding; old station stock and transport custody stay at that station. */
    public synchronized Warehouse addWarehouse(Warehouse warehouse, boolean replaceArmyBinding) throws SQLException {
        return tx(() -> {
            cap("SELECT COUNT(*) FROM warehouse WHERE nation=?", MAX_WAREHOUSES, warehouse.nationId());
            require(warehouse(warehouse.id()).isEmpty(), "仓库编号已存在");
            if (warehouse.armyId() != null) {
                if (replaceArmyBinding) {
                    require(one("SELECT 1 FROM warehouse WHERE army=? AND nation<>?", row -> true,
                            warehouse.armyId(), warehouse.nationId()).isEmpty(), "不能替换其他国家的军队绑定");
                    execute("UPDATE warehouse SET army=NULL WHERE army=? AND nation=?",
                            warehouse.armyId(), warehouse.nationId());
                }
                require(one("SELECT 1 FROM warehouse WHERE army=?", row -> true, warehouse.armyId()).isEmpty(),
                        "该军队已绑定前线站");
            }
            Position pos = warehouse.position();
            execute("INSERT INTO warehouse VALUES(?,?,?,?,?,?,?,?)", warehouse.id(), warehouse.nationId(),
                    warehouse.kind(), pos.world(), pos.x(), pos.y(), pos.z(), warehouse.armyId());
            return warehouse;
        });
    }

    public synchronized Optional<Warehouse> warehouse(String id) throws SQLException {
        return one("SELECT * FROM warehouse WHERE id=?", SupplyStore::warehouse, id);
    }

    public synchronized List<Warehouse> warehouses(UUID nation) throws SQLException {
        return rows("SELECT * FROM warehouse WHERE nation=? ORDER BY id", SupplyStore::warehouse, nation);
    }

    public synchronized List<Warehouse> fronts() throws SQLException {
        return rows("SELECT * FROM warehouse WHERE army IS NOT NULL ORDER BY id", SupplyStore::warehouse);
    }

    public synchronized void addRoad(Road road) throws SQLException {
        tx(() -> {
            cap("SELECT COUNT(*) FROM road WHERE nation=?", MAX_ROADS, road.nationId());
            require(one("SELECT 1 FROM road WHERE id=?", row -> true, road.id()).isEmpty(), "道路编号已存在");
            execute("INSERT INTO road VALUES(?,?,?,?,?,?,?)", road.id(), road.nationId(),
                    road.origin(), road.target(), road.efficiency(), road.blocked(), road.coreLine());
            return null;
        });
    }

    public synchronized Optional<Road> road(String id) throws SQLException {
        return one("SELECT * FROM road WHERE id=?", SupplyStore::road, id);
    }

    public synchronized List<Road> roads(UUID nation) throws SQLException {
        return rows("SELECT * FROM road WHERE nation=? ORDER BY id", SupplyStore::road, nation);
    }

    public synchronized void setBlocked(String id, boolean blocked) throws SQLException {
        require(execute("UPDATE road SET blocked=? WHERE id=?", blocked, id) == 1, "道路不存在");
    }

    public synchronized Purchase addPurchase(Purchase purchase) throws SQLException {
        return tx(() -> {
            cap("SELECT COUNT(*) FROM purchase WHERE nation=? AND state IN ('OPEN','RESERVED','SETTLING','REVIEW')",
                    MAX_ORDERS, purchase.nationId());
            execute("INSERT INTO purchase VALUES(?,?,?,?,?,?,?,?,?,?)", purchase.id(), purchase.nationId(),
                    purchase.warehouseId(), purchase.resource(), purchase.itemCount(), purchase.price(),
                    PurchaseState.OPEN, null, null, purchase.createdAt());
            return purchase;
        });
    }

    public synchronized Optional<Purchase> purchase(UUID id) throws SQLException {
        return one("SELECT * FROM purchase WHERE id=?", SupplyStore::purchase, id);
    }

    public synchronized List<Purchase> purchases() throws SQLException {
        return rows("SELECT * FROM purchase WHERE state='OPEN' ORDER BY created DESC,id LIMIT " + BOARD_LIMIT,
                SupplyStore::purchase);
    }

    public synchronized List<Purchase> nationPurchases(UUID nation) throws SQLException {
        return rows("SELECT * FROM purchase WHERE nation=? ORDER BY created DESC,id LIMIT " + BOARD_LIMIT,
                SupplyStore::purchase, nation);
    }

    public synchronized List<Purchase> playerPurchases(UUID player) throws SQLException {
        return rows("SELECT * FROM purchase WHERE seller=? ORDER BY created DESC,id LIMIT " + BOARD_LIMIT,
                SupplyStore::purchase, player);
    }

    public synchronized boolean cancelPurchase(UUID id) throws SQLException {
        return execute("UPDATE purchase SET state='CANCELLED' WHERE id=? AND state='OPEN'", id) == 1;
    }

    /** Reserve the only seller slot and snapshot the exact real item stacks before inventory changes. */
    public synchronized void prepareTransfer(UUID id, UUID purchaseId, UUID seller, String beforeHash,
                                             String afterHash, List<Lot> lots, long now) throws SQLException {
        prepareTransfer(id, purchaseId, seller, null, null, 0, 0, null, beforeHash, afterHash, lots, now);
    }

    public synchronized void prepareTransfer(UUID id, UUID purchaseId, UUID seller, UUID exporterNation,
                                             UUID agreementId, long agreementLimit, long tradeUnits,
                                             String beforeHash, String afterHash, List<Lot> lots, long now)
            throws SQLException {
        prepareTransfer(id, purchaseId, seller, exporterNation, agreementId, agreementLimit, tradeUnits,
                null, beforeHash, afterHash, lots, now);
    }

    public synchronized void prepareTransfer(UUID id, UUID purchaseId, UUID seller, UUID exporterNation,
                                             UUID agreementId, long agreementLimit, long tradeUnits,
                                             TaxAssessment tax, String beforeHash, String afterHash,
                                             List<Lot> lots, long now) throws SQLException {
        tx(() -> {
            Purchase purchase = purchase(purchaseId).orElseThrow(() -> error("采购单不存在"));
            require(purchase.state() == PurchaseState.OPEN, "采购单已被预订或关闭");
            require((agreementId == null && agreementLimit == 0 && tradeUnits == 0)
                    || (exporterNation != null && agreementId != null && agreementLimit > 0
                    && tradeUnits > 0 && tradeUnits <= agreementLimit), "国家贸易额度记录无效");
            require(pendingInventory(seller).isEmpty(), "请先恢复先前的库存操作");
            if (tax != null) {
                require(tax.settlementId().equals(id) && tax.nationId().equals(purchase.nationId())
                                && tax.payerId().equals(seller) && tax.grossAmount().compareTo(purchase.price()) == 0
                                && tax.taxAmount().signum() > 0 && tax.taxAmount().compareTo(purchase.price()) < 0,
                        "关税快照与采购订单不符，或税额不允许卖家净额为零");
            }
            require(lots.size() <= 36 && lots.stream().mapToInt(Lot::count).sum() == purchase.itemCount(),
                    "物品数量与采购单不符");
            require(validHash(beforeHash) && validHash(afterHash) && !beforeHash.equals(afterHash), "库存快照无效");
            require(execute("UPDATE purchase SET state='RESERVED',seller=?,settlement=? "
                    + "WHERE id=? AND state='OPEN'", seller, id, purchaseId) == 1, "采购单已被预订");
            execute("INSERT INTO item_transfer(id,purchase,player,resource,state,before_hash,after_hash,created,"
                            + "exporter_nation,trade_agreement,agreement_limit,trade_units,convoy_roll,tax_type,tax_amount) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", id, purchaseId, seller, purchase.resource(),
                    TransferState.PREPARED, beforeHash, afterHash, now, exporterNation, agreementId,
                    agreementLimit, tradeUnits, ThreadLocalRandom.current().nextDouble(),
                    tax == null ? null : tax.type(), tax == null ? BigDecimal.ZERO.setScale(2) : tax.taxAmount());
            if (agreementId != null) {
                long reserved = one("SELECT COALESCE(SUM(trade_units),0) FROM item_transfer "
                                + "WHERE trade_agreement=? AND id<>? AND state IN "
                                + "('PREPARED','TAKEN','STORED','REVIEW')", row -> row.getLong(1), agreementId, id)
                        .orElse(0L);
                require(tradeUnits <= agreementLimit - reserved, "StarCore 出口协定剩余额度不足");
            }
            int ordinal = 0;
            for (Lot lot : lots) {
                execute("INSERT INTO item_lot VALUES(?,?,?,?,?,?,?,?,?,?,NULL,NULL)", id, ordinal++, lot.itemId(),
                        lot.quality(), lot.qualityRatio(), purchase.resource(), lot.count(), lot.count(),
                        lot.units() / lot.count(), lot.payload());
            }
            return null;
        });
    }

    public synchronized Optional<Transfer> transfer(UUID id) throws SQLException {
        return one("SELECT * FROM item_transfer WHERE id=?", SupplyStore::transfer, id);
    }

    public synchronized Optional<Transfer> receiptTransfer(UUID receipt) throws SQLException {
        return one("SELECT * FROM item_transfer WHERE id=? OR return_id=?", SupplyStore::transfer, receipt, receipt);
    }

    public synchronized Optional<Transfer> pendingInventory(UUID player) throws SQLException {
        return one("SELECT * FROM item_transfer WHERE player=? AND (state IN ('PREPARED','RETURNING') "
                        + "OR (state='REVIEW' AND note<>'payment'))",
                SupplyStore::transfer, player);
    }

    public synchronized List<Transfer> recoverable(UUID player) throws SQLException {
        return rows("SELECT * FROM item_transfer WHERE player=? AND state IN ('REJECTED','REVIEW') "
                + "ORDER BY created LIMIT " + BOARD_LIMIT, SupplyStore::transfer, player);
    }

    public synchronized List<Lot> lots(UUID transfer) throws SQLException {
        return rows("SELECT * FROM item_lot WHERE transfer=? ORDER BY ordinal", row -> {
            int count = row.getInt("original_count");
            return new Lot(row.getString("item_id"), row.getString("quality"), row.getDouble("quality_ratio"),
                    count, count * row.getInt("units_per_item"), row.getBytes("payload"));
        }, transfer);
    }

    /** No asset was touched. The order can receive another seller. */
    public synchronized void abandonTransfer(UUID id) throws SQLException {
        tx(() -> {
            Transfer transfer = transfer(id).orElseThrow(() -> error("找不到库存操作"));
            if (transfer.state() != TransferState.PREPARED) return null;
            execute("DELETE FROM item_lot WHERE transfer=?", id);
            execute("DELETE FROM item_transfer WHERE id=?", id);
            reopenPurchase(transfer);
            return null;
        });
    }

    /** Called only after the entity-owned inventory receipt has been established. */
    public synchronized void confirmTaken(UUID id, long now) throws SQLException {
        tx(() -> {
            Transfer transfer = transfer(id).orElseThrow(() -> error("找不到库存操作"));
            if (transfer.state() != TransferState.PREPARED) return null;
            Purchase order = purchase(transfer.purchaseId()).orElseThrow();
            require(order.state() == PurchaseState.RESERVED && id.equals(order.settlementId()), "采购单库存收据不匹配");
            execute("UPDATE item_transfer SET state='TAKEN' WHERE id=?", id);
            execute("UPDATE purchase SET state='SETTLING' WHERE id=?", order.id());
            queue(new Settlement(id, order.id(), order.nationId(), transfer.player(), "PURCHASE", "PENDING",
                    order.price(), now));
            if (transfer.taxType() != null && transfer.taxAmount().signum() > 0) {
                execute("INSERT INTO settlement_tax(settlement,state,updated) VALUES(?,?,?)",
                        id, TaxState.QUOTED, now);
            }
            return null;
        });
    }

    public synchronized Optional<TaxAssessment> taxAssessment(UUID settlement) throws SQLException {
        return one("SELECT i.tax_type,i.tax_amount,p.nation,p.price,i.player FROM item_transfer i "
                        + "JOIN purchase p ON p.id=i.purchase WHERE i.id=? AND i.tax_type IS NOT NULL",
                row -> new TaxAssessment(settlement, row.getString("tax_type"), uuid(row, "nation"),
                        uuid(row, "player"), new BigDecimal(row.getString("price")),
                        new BigDecimal(row.getString("tax_amount"))), settlement);
    }

    public synchronized Optional<TaxState> taxState(UUID settlement) throws SQLException {
        return one("SELECT state FROM settlement_tax WHERE settlement=?", row -> TaxState.valueOf(row.getString(1)),
                settlement);
    }

    public synchronized boolean beginTaxFunding(UUID settlement, long now) throws SQLException {
        return execute("UPDATE settlement_tax SET state='FUNDING',updated=? WHERE settlement=? AND state='QUOTED'",
                now, settlement) == 1;
    }

    public synchronized void finishTaxFunding(UUID settlement, boolean funded, long now) throws SQLException {
        require(execute("UPDATE settlement_tax SET state=?,updated=? WHERE settlement=? AND state='FUNDING'",
                funded ? TaxState.FUNDED : TaxState.REVIEW, now, settlement) == 1,
                "关税结算状态已变化");
    }

    /** Only independent evidence from StarCore may resolve a failed external tax response. */
    public synchronized void reconcileTaxFunding(UUID settlement, boolean funded, long now) throws SQLException {
        require(execute("UPDATE settlement_tax SET state=?,updated=? WHERE settlement=? "
                        + "AND state IN ('QUOTED','FUNDING','REVIEW','FUNDED')",
                funded ? TaxState.FUNDED : TaxState.QUOTED, now, settlement) == 1,
                "关税不在人工核对状态");
    }

    public synchronized void inventoryReview(UUID id, String detail) throws SQLException {
        tx(() -> {
            Transfer transfer = transfer(id).orElseThrow(() -> error("找不到库存操作"));
            if (transfer.state() == TransferState.PREPARED) {
                execute("UPDATE purchase SET state='REVIEW' WHERE id=? AND settlement=?", transfer.purchaseId(), id);
            }
            if (transfer.state() == TransferState.PREPARED || transfer.state() == TransferState.RETURNING) {
                execute("UPDATE item_transfer SET state='REVIEW',note=? WHERE id=?", detail, id);
            }
            return null;
        });
    }

    /** Operator-confirmed evidence reconciles only the inventory leg, never a money review. */
    public synchronized void reconcileInventory(UUID receipt, boolean applied, long now) throws SQLException {
        tx(() -> {
            Transfer transfer = receiptTransfer(receipt).orElseThrow(() -> error("库存回执不存在"));
            require(transfer.state() == TransferState.REVIEW && !transfer.note().equals("payment"),
                    "库存回执不在人工核对状态");
            UUID id = transfer.id();
            require(pendingInventory(transfer.player()).filter(other -> !other.id().equals(transfer.id())).isEmpty(),
                    "玩家另有库存操作待恢复");
            if (receipt.equals(transfer.returnId())) {
                if (applied) {
                    execute("UPDATE item_lot SET remaining=0 WHERE transfer=?", id);
                    execute("UPDATE item_transfer SET state='RETURNED',note='' WHERE id=?", id);
                } else {
                    execute("UPDATE item_transfer SET state='REJECTED',return_id=NULL,note='' WHERE id=?", id);
                }
            } else {
                require(transfer.returnId() == null && receipt.equals(id), "请使用退款库存回执编号");
                Purchase order = purchase(transfer.purchaseId()).orElseThrow();
                require(order.state() == PurchaseState.REVIEW && id.equals(order.settlementId()), "采购单与回执不匹配");
                if (applied) {
                    execute("UPDATE item_transfer SET state='TAKEN',note='' WHERE id=?", id);
                    execute("UPDATE purchase SET state='SETTLING' WHERE id=?", order.id());
                    queue(new Settlement(id, order.id(), order.nationId(), transfer.player(), "PURCHASE",
                            "PENDING", order.price(), now));
                    if (transfer.taxType() != null && transfer.taxAmount().signum() > 0) {
                        execute("INSERT INTO settlement_tax(settlement,state,updated) VALUES(?,?,?)",
                                id, TaxState.QUOTED, now);
                    }
                } else {
                    // Retain the payload and UUID for audit. It can never be released twice.
                    execute("UPDATE item_lot SET remaining=0 WHERE transfer=?", id);
                    execute("UPDATE item_transfer SET state='RETURNED',note='no inventory debit' WHERE id=?", id);
                    execute("UPDATE purchase SET state='OPEN',seller=NULL,settlement=NULL WHERE id=?", order.id());
                }
            }
            return null;
        });
    }

    public synchronized void beginReturn(UUID id, UUID receipt, String beforeHash, String afterHash) throws SQLException {
        tx(() -> {
            Transfer transfer = transfer(id).orElseThrow(() -> error("找不到库存操作"));
            require(transfer.state() == TransferState.REJECTED, "物资暂不可退回");
            require(pendingInventory(transfer.player()).isEmpty(), "请先恢复先前的库存操作");
            require(validHash(beforeHash) && validHash(afterHash) && !beforeHash.equals(afterHash), "库存快照无效");
            execute("UPDATE item_transfer SET state='RETURNING',return_id=?,before_hash=?,after_hash=? WHERE id=?",
                    receipt, beforeHash, afterHash, id);
            return null;
        });
    }

    public synchronized void finishReturn(UUID id, UUID receipt, boolean applied) throws SQLException {
        tx(() -> {
            Transfer transfer = transfer(id).orElseThrow(() -> error("找不到库存操作"));
            if (transfer.state() != TransferState.RETURNING || !receipt.equals(transfer.returnId())) return null;
            if (applied) {
                execute("UPDATE item_lot SET remaining=0 WHERE transfer=?", id);
                execute("UPDATE item_transfer SET state='RETURNED' WHERE id=?", id);
            } else {
                execute("UPDATE item_transfer SET state='REJECTED',return_id=NULL WHERE id=?", id);
            }
            return null;
        });
    }

    public synchronized Shipment addShipment(Shipment shipment) throws SQLException {
        return tx(() -> {
            Purchase purchase = purchase(shipment.purchaseId()).orElseThrow(() -> error("采购单不存在"));
            require(purchase.state() == PurchaseState.BOUGHT && purchase.nationId().equals(shipment.nationId())
                    && purchase.warehouseId().equals(shipment.origin()), "采购尚未结算或起点不匹配");
            require(one("SELECT 1 FROM shipment WHERE purchase=? AND state IN "
                    + "('OPEN','IN_TRANSIT','SETTLING','REVIEW','DELIVERED')", row -> true, purchase.id()).isEmpty(),
                    "该批物资已有运输单或已交付");
            cap("SELECT COUNT(*) FROM shipment WHERE nation=? AND state IN ('OPEN','IN_TRANSIT','SETTLING','REVIEW')",
                    MAX_ORDERS, shipment.nationId());
            execute("INSERT INTO shipment(id,purchase,nation,origin,target,reward,state,carrier,ready,expires,path,created,route_risk,risk_roll) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", shipment.id(), shipment.purchaseId(),
                    shipment.nationId(), shipment.origin(), shipment.target(), shipment.reward(), TransportState.OPEN,
                    null, 0, 0, shipment.path(), shipment.createdAt(), shipment.routeRisk(),
                    one("SELECT convoy_roll FROM item_transfer WHERE id=?", row -> row.getDouble(1),
                            purchase.settlementId()).orElse(1.0));
            require(execute("UPDATE item_lot SET shipment=? WHERE transfer=? AND warehouse=? "
                    + "AND remaining>0 AND shipment IS NULL", shipment.id(), purchase.settlementId(), shipment.origin()) > 0,
                    "仓库中没有该批可用实物");
            return shipment;
        });
    }

    public synchronized Optional<Shipment> shipment(UUID id) throws SQLException {
        return one("SELECT * FROM shipment WHERE id=?", SupplyStore::shipment, id);
    }

    public synchronized List<Shipment> shipments(UUID player) throws SQLException {
        return rows("SELECT * FROM shipment WHERE state='OPEN' OR carrier=? "
                + "ORDER BY state='IN_TRANSIT' DESC,created DESC,id LIMIT " + BOARD_LIMIT, SupplyStore::shipment, player);
    }

    public synchronized List<Shipment> nationShipments(UUID nation) throws SQLException {
        return rows("SELECT * FROM shipment WHERE nation=? ORDER BY created DESC,id LIMIT " + BOARD_LIMIT,
                SupplyStore::shipment, nation);
    }

    public synchronized boolean accept(UUID id, UUID carrier, long ready, long expires, String path,
                                        double routeRisk) throws SQLException {
        return tx(() -> {
            if (execute("UPDATE shipment SET state='IN_TRANSIT',carrier=?,ready=?,expires=?,path=?,route_risk=? "
                    + "WHERE id=? AND state='OPEN'", carrier, ready, expires, path, routeRisk, id) != 1) return false;
            // Switching roles never allows the carrier to count as their own independent guard.
            execute("DELETE FROM shipment_escort WHERE shipment=? AND player=?", id, carrier);
            return true;
        });
    }

    public synchronized boolean joinEscort(UUID shipmentId, UUID escort, long now) throws SQLException {
        return tx(() -> {
            Shipment shipment = shipment(shipmentId).orElseThrow(() -> error("运输单不存在"));
            require(shipment.state() == TransportState.OPEN, "商队已出发，无法再加入护送");
            int count = one("SELECT COUNT(*) FROM shipment_escort WHERE shipment=?", row -> row.getInt(1), shipmentId)
                    .orElse(0);
            require(count < 4, "每支商队最多可有 4 名护送者");
            require(execute("INSERT OR IGNORE INTO shipment_escort(shipment,player,joined) VALUES(?,?,?)",
                    shipmentId, escort, now) == 1, "你已经加入这支护送队");
            return true;
        });
    }

    public synchronized List<UUID> escorts(UUID shipmentId) throws SQLException {
        return rows("SELECT player FROM shipment_escort WHERE shipment=? ORDER BY joined,player",
                row -> uuid(row, "player"), shipmentId);
    }

    public synchronized int escortCount(UUID shipmentId) throws SQLException {
        return one("SELECT COUNT(*) FROM shipment_escort e JOIN shipment s ON s.id=e.shipment "
                        + "WHERE e.shipment=? AND (s.carrier IS NULL OR e.player<>s.carrier)",
                row -> row.getInt(1), shipmentId).orElse(0);
    }

    public synchronized int escortCount(UUID shipmentId, UUID prospectiveCarrier) throws SQLException {
        if (prospectiveCarrier == null) return escortCount(shipmentId);
        return one("SELECT COUNT(*) FROM shipment_escort e JOIN shipment s ON s.id=e.shipment "
                        + "WHERE e.shipment=? AND e.player<>? AND (s.carrier IS NULL OR e.player<>s.carrier)",
                row -> row.getInt(1), shipmentId, prospectiveCarrier).orElse(0);
    }

    public synchronized boolean failRiskyShipment(UUID shipmentId, UUID carrier) throws SQLException {
        return failRiskyShipment(shipmentId, carrier, System.currentTimeMillis());
    }

    public synchronized boolean failRiskyShipment(UUID shipmentId, UUID carrier, long now) throws SQLException {
        return tx(() -> {
            boolean riskHit = one("SELECT risk_roll<route_risk FROM shipment WHERE id=? AND carrier=? "
                            + "AND state='IN_TRANSIT' AND ready<=? AND expires>?", row -> row.getBoolean(1),
                    shipmentId, carrier, now, now).orElse(false);
            if (!riskHit || !closeShipmentRows(shipmentId, TransportState.FAILED)) return false;
            execute("UPDATE shipment SET failure_reason='RISK',failed_at=? WHERE id=?", now, shipmentId);
            return true;
        });
    }

    public record ShipmentFailure(String reason, long occurredAt) {}

    public synchronized Optional<ShipmentFailure> shipmentFailure(UUID id) throws SQLException {
        return one("SELECT failure_reason,failed_at FROM shipment WHERE id=? AND state='FAILED'",
                row -> new ShipmentFailure(row.getString(1), row.getLong(2)), id);
    }

    public synchronized boolean hasFailedPurchase(UUID purchase) throws SQLException {
        return one("SELECT 1 FROM shipment WHERE purchase=? AND state='FAILED' LIMIT 1", row -> true, purchase)
                .orElse(false);
    }

    public synchronized boolean reroute(UUID id, UUID carrier, String oldPath, String newPath, long ready,
                                         double routeRisk) throws SQLException {
        return execute("UPDATE shipment SET path=?,ready=?,route_risk=? WHERE id=? AND carrier=? "
                + "AND state='IN_TRANSIT' AND path=?", newPath, ready, routeRisk, id, carrier, oldPath) == 1;
    }

    public synchronized boolean closeShipment(UUID id, TransportState closeState) throws SQLException {
        require(closeState == TransportState.CANCELLED || closeState == TransportState.FAILED, "无效运输状态");
        return tx(() -> closeShipmentRows(id, closeState));
    }

    private boolean closeShipmentRows(UUID id, TransportState state) throws SQLException {
        if (execute("UPDATE shipment SET state=? WHERE id=? AND state IN ('OPEN','IN_TRANSIT')", state, id) != 1) {
            return false;
        }
        execute("UPDATE item_lot SET shipment=NULL WHERE shipment=?", id);
        return true;
    }

    public synchronized int expire(long now) throws SQLException {
        return tx(() -> {
            var expired = rows("SELECT id FROM shipment WHERE state='IN_TRANSIT' AND expires<=? LIMIT 128",
                    row -> uuid(row, "id"), now);
            for (UUID id : expired) {
                if (closeShipmentRows(id, TransportState.FAILED)) {
                    execute("UPDATE shipment SET failure_reason='EXPIRED',failed_at=? WHERE id=?", now, id);
                }
            }
            return expired.size();
        });
    }

    public synchronized boolean deliver(UUID id, UUID carrier, long now) throws SQLException {
        return tx(() -> {
            Shipment shipment = shipment(id).orElseThrow(() -> error("运输单不存在"));
            if (execute("UPDATE shipment SET state='SETTLING' WHERE id=? AND carrier=? "
                    + "AND state='IN_TRANSIT' AND ready<=? AND expires>?", id, carrier, now, now) != 1) {
                return false;
            }
            queue(new Settlement(id, id, shipment.nationId(), carrier, "TRANSPORT", "PENDING", shipment.reward(), now));
            return true;
        });
    }

    private void queue(Settlement settlement) throws SQLException {
        execute("INSERT INTO settlement VALUES(?,?,?,?,?,?,?,?)", settlement.id(), settlement.orderId(),
                settlement.nationId(), settlement.playerId(), settlement.kind(), settlement.amount(),
                settlement.outcome(), settlement.timestamp());
    }

    public synchronized Optional<Settlement> settlement(UUID id) throws SQLException {
        return one("SELECT * FROM settlement WHERE id=?", SupplyStore::settlement, id);
    }

    public synchronized List<Settlement> pendingSettlements(long through) throws SQLException {
        return rows("SELECT * FROM settlement WHERE state='PENDING' AND updated<=? ORDER BY updated,id LIMIT 32",
                SupplyStore::settlement, through);
    }

    public synchronized void retryLater(UUID id, long at) throws SQLException {
        execute("UPDATE settlement SET updated=? WHERE id=? AND state='PENDING'", at, id);
    }

    public synchronized Optional<Settlement> finishSettlement(UUID id, String outcome, long now,
                                                               boolean reconcile) throws SQLException {
        require(List.of("PAID", "REJECTED", "REVIEW").contains(outcome), "无效结算结果");
        return tx(() -> {
            Settlement s = settlement(id).orElseThrow(() -> error("结算不存在"));
            String expected = reconcile ? "REVIEW" : "PENDING";
            if (!s.outcome().equals(expected) || outcome.equals(expected)) return Optional.empty();
            execute("UPDATE settlement SET state=?,updated=? WHERE id=? AND state=?", outcome, now, id, expected);
            if (s.kind().equals("PURCHASE")) finishPurchase(s, outcome);
            else finishShipment(s, outcome);
            return settlement(id);
        });
    }

    private void finishPurchase(Settlement settlement, String outcome) throws SQLException {
        Purchase purchase = purchase(settlement.orderId()).orElseThrow();
        UUID transfer = settlement.id();
        if (outcome.equals("PAID")) {
            if (taxState(settlement.id()).isPresent()) {
                require(taxState(settlement.id()).filter(state -> state == TaxState.FUNDED).isPresent(),
                        "StarCore 关税尚未确认入账");
            }
            require(execute("UPDATE item_lot SET warehouse=? WHERE transfer=? AND warehouse IS NULL "
                    + "AND remaining>0", purchase.warehouseId(), transfer) > 0, "结算物资丢失");
            execute("UPDATE item_transfer SET state='STORED' WHERE id=?", transfer);
            execute("UPDATE purchase SET state='BOUGHT' WHERE id=?", purchase.id());
        } else if (outcome.equals("REJECTED")) {
            execute("UPDATE item_transfer SET state='REJECTED' WHERE id=?", transfer);
            execute("UPDATE purchase SET state='CANCELLED' WHERE id=?", purchase.id());
        } else {
            execute("UPDATE item_transfer SET state='REVIEW',note='payment' WHERE id=?", transfer);
            execute("UPDATE purchase SET state='REVIEW' WHERE id=?", purchase.id());
        }
    }

    private void finishShipment(Settlement settlement, String outcome) throws SQLException {
        Shipment shipment = shipment(settlement.orderId()).orElseThrow();
        if (outcome.equals("PAID")) {
            require(execute("UPDATE item_lot SET warehouse=?,shipment=NULL WHERE shipment=? "
                    + "AND warehouse=? AND remaining>0", shipment.target(), shipment.id(), shipment.origin()) > 0,
                    "运输物资丢失");
            execute("UPDATE shipment SET state='DELIVERED' WHERE id=?", shipment.id());
        } else if (outcome.equals("REJECTED")) {
            execute("UPDATE item_lot SET shipment=NULL WHERE shipment=?", shipment.id());
            execute("UPDATE shipment SET state='FAILED' WHERE id=?", shipment.id());
        } else {
            execute("UPDATE shipment SET state='REVIEW' WHERE id=?", shipment.id());
        }
    }

    public synchronized Stock stock(String warehouse) throws SQLException {
        Map<ResourceType, Long> available = new EnumMap<>(ResourceType.class);
        Map<ResourceType, Long> reserved = new EnumMap<>(ResourceType.class);
        rows("SELECT resource,shipment IS NOT NULL AS reserved,SUM(remaining * units_per_item) AS total "
                + "FROM item_lot WHERE warehouse=? AND remaining>0 GROUP BY resource,reserved", row -> {
                    (row.getBoolean("reserved") ? reserved : available)
                            .put(ResourceType.valueOf(row.getString("resource")), row.getLong("total"));
                    return true;
                }, warehouse);
        return new Stock(warehouse, available, reserved);
    }

    /** Counts actual remaining physical stacks and open purchase demand for a depot-local market. */
    public synchronized MarketSignals marketSignals(String depot, ResourceType resource) throws SQLException {
        Warehouse warehouse = warehouse(depot).orElseThrow(() -> error("仓库不存在"));
        require(warehouse.kind() == WarehouseKind.DEPOT, "区域市场只设在国家采购仓库");
        long stockItems = one("SELECT COALESCE(SUM(remaining),0) FROM item_lot "
                        + "WHERE warehouse=? AND resource=? AND remaining>0 AND shipment IS NULL",
                row -> row.getLong(1), depot, resource)
                .orElse(0L);
        long demandItems = one("SELECT COALESCE(SUM(item_count),0) FROM purchase WHERE depot=? AND resource=? "
                        + "AND state IN ('OPEN','RESERVED','SETTLING','REVIEW')", row -> row.getLong(1), depot, resource)
                .orElse(0L);
        return new MarketSignals(stockItems, demandItems);
    }

    /** Persist only live siege continuity. A missed observation window or a new army resets pressure. */
    public synchronized Optional<SiegeContinuity> observeSiege(Warehouse front, boolean underSiege,
                                                               long observedAt, long now, long maximumGap)
            throws SQLException {
        require(front.kind() == WarehouseKind.FRONT && front.armyId() != null, "前线站未绑定军队");
        require(maximumGap >= 15_000 && maximumGap <= 300_000, "围城观察间隔超出范围");
        return tx(() -> {
            if (!underSiege || observedAt <= 0 || observedAt > now || now - observedAt > 15_000) {
                execute("DELETE FROM campaign_siege WHERE front=?", front.id());
                return Optional.empty();
            }
            Optional<SiegeContinuity> previous = one(
                    "SELECT since,last_observed FROM campaign_siege WHERE front=? AND army=? AND nation=?",
                    row -> new SiegeContinuity(row.getLong("since"), row.getLong("last_observed")),
                    front.id(), front.armyId(), front.nationId());
            if (previous.isPresent() && previous.get().lastObserved() == observedAt) return previous;
            long since = previous.filter(row -> observedAt >= row.lastObserved()
                    && observedAt - row.lastObserved() <= maximumGap).map(SiegeContinuity::since)
                    .orElse(observedAt);
            execute("INSERT INTO campaign_siege(front,army,nation,since,last_observed) VALUES(?,?,?,?,?) "
                            + "ON CONFLICT(front) DO UPDATE SET army=excluded.army,nation=excluded.nation,"
                            + "since=excluded.since,last_observed=excluded.last_observed",
                    front.id(), front.armyId(), front.nationId(), since, observedAt);
            return Optional.of(new SiegeContinuity(since, observedAt));
        });
    }

    /** Spend whole physical items once per army and time bucket; surplus value is capped at demand. */
    public synchronized Map<ResourceType, Consumption> consume(Warehouse front, long interval,
                                                               Map<ResourceType, Integer> demand) throws SQLException {
        require(front.kind() == WarehouseKind.FRONT && front.armyId() != null, "前线站未绑定军队");
        return tx(() -> {
            var existing = consumption(front.armyId(), interval);
            if (!existing.isEmpty()) return existing;
            Map<ResourceType, Consumption> consumed = new EnumMap<>(ResourceType.class);
            for (var entry : demand.entrySet()) {
                int needed = entry.getValue();
                require(needed > 0 && needed <= 100_000, "补给需求超出范围");
                int remaining = needed;
                try (PreparedStatement batches = statement(
                        "SELECT transfer,ordinal,remaining,units_per_item FROM item_lot "
                        + "WHERE warehouse=? AND resource=? AND shipment IS NULL AND remaining>0 "
                        + "ORDER BY rowid", front.id(), entry.getKey());
                        ResultSet row = batches.executeQuery()) {
                    while (remaining > 0 && row.next()) {
                        int unit = row.getInt("units_per_item");
                        int spent = Math.min(row.getInt("remaining"), (remaining + unit - 1) / unit);
                        execute("UPDATE item_lot SET remaining=remaining-? WHERE transfer=? AND ordinal=?",
                                spent, row.getString("transfer"), row.getInt("ordinal"));
                        remaining = Math.max(0, remaining - spent * unit);
                    }
                }
                int supplied = needed - remaining;
                execute("INSERT INTO consumption VALUES(?,?,?,?,?)", front.armyId(), interval, entry.getKey(),
                        needed, supplied);
                consumed.put(entry.getKey(), new Consumption(needed, supplied));
            }
            return Map.copyOf(consumed);
        });
    }

    public synchronized Map<ResourceType, Consumption> consumption(UUID army, long interval) throws SQLException {
        Map<ResourceType, Consumption> result = new EnumMap<>(ResourceType.class);
        rows("SELECT resource,demand,units FROM consumption WHERE army=? AND slot=?", row -> {
            result.put(ResourceType.valueOf(row.getString("resource")),
                    new Consumption(row.getInt("demand"), row.getInt("units")));
            return true;
        }, army, interval);
        return Map.copyOf(result);
    }

    public synchronized void pruneConsumption(long before) throws SQLException {
        execute("DELETE FROM consumption WHERE slot<?", before);
    }

    private void reopenPurchase(Transfer transfer) throws SQLException {
        execute("UPDATE purchase SET state='OPEN',seller=NULL,settlement=NULL "
                + "WHERE id=? AND settlement=? AND state='RESERVED'", transfer.purchaseId(), transfer.id());
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }

    private void cap(String query, int maximum, Object... args) throws SQLException {
        require(one(query, row -> row.getInt(1), args).orElseThrow() < maximum, "此国家已达到登记上限");
    }

    private int execute(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = statement(sql, args)) {
            statement.execute();
            return statement.getUpdateCount();
        }
    }

    private PreparedStatement statement(String sql, Object... args) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof Enum<?> e) value = e.name();
                else if (value instanceof UUID || value instanceof BigDecimal) value = value.toString();
                statement.setObject(i + 1, value);
            }
            return statement;
        } catch (SQLException | RuntimeException ex) {
            statement.close();
            throw ex;
        }
    }

    private <T> Optional<T> one(String sql, Row<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement query = statement(sql, args);
                ResultSet result = query.executeQuery()) {
            return result.next() ? Optional.of(mapper.read(result)) : Optional.empty();
        }
    }

    private <T> List<T> rows(String sql, Row<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement query = statement(sql, args);
                ResultSet result = query.executeQuery()) {
            List<T> values = new ArrayList<>();
            while (result.next()) values.add(mapper.read(result));
            return List.copyOf(values);
        }
    }

    private <T> T tx(SqlWork<T> work) throws SQLException {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException ex) {
            try {
                connection.rollback();
            } catch (SQLException rollback) {
                ex.addSuppressed(rollback);
            }
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    @FunctionalInterface private interface Row<T> { T read(ResultSet row) throws SQLException; }
    @FunctionalInterface private interface SqlWork<T> { T run() throws SQLException; }

    public static void require(boolean condition, String message) {
        if (!condition) throw error(message);
    }

    public static IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message);
    }

    private static UUID uuid(ResultSet row, String column) throws SQLException {
        String value = row.getString(column);
        return value == null ? null : UUID.fromString(value);
    }

    private static Warehouse warehouse(ResultSet row) throws SQLException {
        return new Warehouse(row.getString("id"), uuid(row, "nation"), WarehouseKind.valueOf(row.getString("kind")),
                new Position(row.getString("world"), row.getDouble("x"), row.getDouble("y"), row.getDouble("z")),
                uuid(row, "army"));
    }

    private static Road road(ResultSet row) throws SQLException {
        return new Road(row.getString("id"), uuid(row, "nation"), row.getString("origin"), row.getString("target"),
                row.getDouble("efficiency"), row.getBoolean("blocked"), uuid(row, "core_line"));
    }

    private static Purchase purchase(ResultSet row) throws SQLException {
        return new Purchase(uuid(row, "id"), uuid(row, "nation"), row.getString("depot"),
                ResourceType.valueOf(row.getString("resource")), row.getInt("item_count"),
                new BigDecimal(row.getString("price")), PurchaseState.valueOf(row.getString("state")),
                uuid(row, "seller"), uuid(row, "settlement"), row.getLong("created"));
    }

    private static Shipment shipment(ResultSet row) throws SQLException {
        return new Shipment(uuid(row, "id"), uuid(row, "nation"), uuid(row, "purchase"), row.getString("origin"),
                row.getString("target"), new BigDecimal(row.getString("reward")),
                TransportState.valueOf(row.getString("state")), uuid(row, "carrier"), row.getLong("ready"),
                row.getLong("expires"), row.getString("path"), row.getLong("created"), row.getDouble("route_risk"));
    }

    private static Settlement settlement(ResultSet row) throws SQLException {
        return new Settlement(uuid(row, "id"), uuid(row, "order_id"), uuid(row, "nation"), uuid(row, "player"),
                row.getString("kind"), row.getString("state"), new BigDecimal(row.getString("amount")),
                row.getLong("updated"));
    }

    private static Transfer transfer(ResultSet row) throws SQLException {
        return new Transfer(uuid(row, "id"), uuid(row, "purchase"), uuid(row, "player"),
                ResourceType.valueOf(row.getString("resource")), TransferState.valueOf(row.getString("state")),
                row.getString("before_hash"), row.getString("after_hash"), uuid(row, "return_id"),
                row.getString("note"), uuid(row, "exporter_nation"), uuid(row, "trade_agreement"),
                row.getLong("agreement_limit"), row.getLong("trade_units"), row.getString("tax_type"),
                new BigDecimal(row.getString("tax_amount")));
    }

    @Override
    public synchronized void close() throws SQLException {
        connection.close();
    }
}
