package dev.starsupply.storage;

import static dev.starsupply.api.ResourceType.FOOD;
import static dev.starsupply.api.SupplyModels.PurchaseState.BOUGHT;
import static dev.starsupply.api.SupplyModels.PurchaseState.CANCELLED;
import static dev.starsupply.api.SupplyModels.PurchaseState.OPEN;
import static dev.starsupply.api.SupplyModels.PurchaseState.RESERVED;
import static dev.starsupply.api.SupplyModels.PurchaseState.SETTLING;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Purchase;
import dev.starsupply.api.SupplyModels.Settlement;
import dev.starsupply.api.SupplyModels.Shipment;
import dev.starsupply.api.SupplyModels.TransferState;
import dev.starsupply.api.SupplyModels.TransportState;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import dev.starsupply.integration.CoreGateway;
import dev.starsupply.service.Logistics;
import dev.starsupply.api.SupplyModels.MarketQuote;
import dev.starsupply.storage.SupplyStore.Lot;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupplyLifecycleTest {
    private static final UUID NATION = UUID.fromString("bf30669d-f3df-4d12-9ea2-f9bf459a0827");
    private static final UUID OFFICER = UUID.fromString("076e0abd-038c-48dc-b352-aeef4056da80");
    private static final UUID ARMY = UUID.fromString("30ecbbfe-3a3c-4bb6-aa90-7a90f3b5684e");
    private static final UUID FORWARD_ARMY = UUID.fromString("7e55961d-4191-4cb6-8181-736b7a66c8b0");
    private static final UUID FOREIGN_ARMY = UUID.fromString("f1a0cf72-9ecb-4cc3-9580-620b0cff9900");
    private static final UUID SELLER = UUID.fromString("069a2914-938f-40aa-94bb-024463bac5f5");
    private static final UUID COURIER = UUID.fromString("8c01833d-e0af-4bcf-ba0e-acef508be253");
    private static final UUID ENEMY = UUID.fromString("753c5f54-6571-48fa-b493-ae5940117af7");
    private static final UUID ENEMY_PLAYER = UUID.fromString("938c4830-cb02-4475-b418-fc6fa5a14df2");
    private static final UUID ESCORT_PLAYER = UUID.fromString("42bfdf1c-0a29-49cc-a438-b9c6a70aadcb");
    private static final long NOW = 1_000_000;
    private static final long INTERVAL = 60_000;
    private static final long SHIPMENT_LIFE = 45_000;
    private static final Position DEPOT = new Position("world", 0, 64, 0);
    private static final Position FRONT = new Position("world", 100, 64, 0);
    private static final Position JUNCTION = new Position("world", 50, 64, 30);
    private static final BigDecimal PURCHASE_PRICE = new BigDecimal("40.00");
    private static final BigDecimal REWARD = new BigDecimal("15.00");
    private static final String BEFORE = "a".repeat(64);
    private static final String AFTER = "b".repeat(64);
    private static final byte[] PAYLOAD = {1, 31, 9, -4};

    @TempDir Path directory;
    private final LedgerCore core = new LedgerCore();

    @Test
    void migratesLegacyDatabaseAndQuarantinesUncertainInventoryPerPlayer() throws Exception {
        Path database = directory.resolve("migration-v1.sqlite");
        UUID transfer = UUID.randomUUID();
        UUID purchase;
        try (SupplyStore store = new SupplyStore(database)) {
            purchase = purchase(configured(store)).id();
            prepare(store, transfer, purchase, SELLER);
            store.inventoryReview(transfer, "missing durable player receipt after interruption");
            assertEquals(TransferState.REVIEW, store.pendingInventory(SELLER).orElseThrow().state());
        }

        // Recreate the original release schema to exercise the full on-disk v1 -> v11 upgrade.
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TABLE campaign_siege");
                statement.execute("DROP TABLE native_supply_credit");
                statement.execute("DROP TABLE consumption");
                statement.execute("CREATE TABLE consumption (army TEXT NOT NULL, slot INTEGER NOT NULL, "
                        + "resource TEXT NOT NULL, demand INTEGER NOT NULL, units INTEGER NOT NULL, "
                        + "PRIMARY KEY(army,slot,resource))");
                statement.execute("CREATE INDEX consumption_expiry ON consumption(slot)");
                statement.execute("INSERT INTO consumption VALUES('" + ARMY + "',42,'FOOD',3,2)");
                statement.execute("ALTER TABLE shipment DROP COLUMN failure_reason");
                statement.execute("ALTER TABLE shipment DROP COLUMN failed_at");
                statement.execute("DROP TABLE IF EXISTS settlement_tax");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN tax_amount");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN tax_type");
                statement.execute("DROP TABLE shipment_escort");
                statement.execute("ALTER TABLE shipment DROP COLUMN risk_roll");
                statement.execute("ALTER TABLE shipment DROP COLUMN route_risk");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN convoy_roll");
                statement.execute("DROP INDEX item_transfer_agreement");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN trade_units");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN agreement_limit");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN trade_agreement");
                statement.execute("ALTER TABLE item_transfer DROP COLUMN exporter_nation");
                statement.execute("DROP INDEX transfer_pending");
                statement.execute("CREATE UNIQUE INDEX transfer_pending ON item_transfer(player) "
                        + "WHERE state IN ('PREPARED','RETURNING')");
                statement.execute("PRAGMA user_version=1");
            }
        }
        try (SupplyStore migrated = new SupplyStore(database)) {
            assertEquals(11, migratedSchemaVersion(database));
            assertEquals(new SupplyStore.Consumption(3, 2), migrated.consumption(ARMY, 42, INTERVAL).get(FOOD),
                    "legacy cycle rows remain available when their original interval is unknown");
            var front = migrated.warehouse("front").orElseThrow();
            assertEquals(new SupplyStore.Consumption(3, 2),
                    migrated.consume(front, 42, Map.of(FOOD, 1), INTERVAL).get(FOOD),
                    "an unknown-period legacy row must block another physical debit in its slot");
            assertEquals(transfer, migrated.pendingInventory(SELLER).orElseThrow().id());
            assertThrows(IllegalArgumentException.class, () -> prepare(migrated,
                    UUID.randomUUID(), purchase, SELLER));

            // Settlements waiting for payment are not inventory ambiguities and do not lock a player.
            migrated.reconcileInventory(transfer, true, NOW);
            assertTrue(migrated.pendingInventory(SELLER).isEmpty());
        }
    }

    private static int migratedSchemaVersion(Path database) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement();
             var result = statement.executeQuery("PRAGMA user_version")) {
            return result.next() ? result.getInt(1) : -1;
        }
    }

    @Test
    void nativeSupplySurvivesLostResponseRestartPruningAndRebindingWithoutRepeatingFood() throws Exception {
        Path database = directory.resolve("native-supply-recovery.sqlite");
        long tick;
        UUID request;
        long remaining;
        BigDecimal treasury;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            Purchase order = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            tick = (Math.floorDiv(moving.readyAt(), INTERVAL) + 1) * INTERVAL;
            core.loseNativeResponse = true;
            treasury = core.treasury;
            logistics.computeArmySupply(tick, true);
            var credit = store.nativeSupplyCredits(ARMY).getFirst();
            request = credit.id();
            assertEquals("PENDING", credit.state());
            assertEquals(10, credit.points());
            assertEquals(10, core.nativePoints);
            assertEquals(1, core.nativeCalls);
            remaining = store.stock("front").available().get(FOOD);
            logistics.computeArmySupply(tick + 1, true);
            assertEquals(remaining, store.stock("front").available().get(FOOD));
            assertEquals(1, core.nativeCalls, "retry backoff must be durable");
            logistics.rebindFront(OFFICER, "front", FORWARD_ARMY);
            store.pruneConsumption(Long.MAX_VALUE);
        }
        core.atWar = false;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            logistics.computeArmySupply(tick + 5_001, false);
            assertEquals(1, core.nativeCalls, "status reads must not submit pending obligations");
            logistics.computeArmySupply(tick + 5_001, true);
            var credit = store.nativeSupplyCredits(ARMY).getFirst();
            assertEquals(request, credit.id());
            assertEquals("APPLIED", credit.state());
            assertEquals(2, core.nativeCalls);
            assertEquals(10, core.nativePoints, "lost response must not double replenishment");
            assertEquals(remaining, store.stock("front").available().get(FOOD));
            assertEquals(treasury, core.treasury, "physical supply never charges StarCore again");
            logistics.computeArmySupply(tick + INTERVAL * 50, true);
            assertEquals(2, core.nativeCalls, "confirmed requests must stay confirmed");
        }
    }

    @Test
    void nativeCreditUsesOnlyActuallyConsumedFoodAndEmptyOrLegacyCyclesNeverCreateCredit() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("native-supply-accounting.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase order = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            var front = store.warehouse("front").orElseThrow();
            Map<dev.starsupply.api.ResourceType, Integer> invalid = new java.util.EnumMap<>(dev.starsupply.api.ResourceType.class);
            invalid.put(FOOD, 100);
            invalid.put(dev.starsupply.api.ResourceType.MEDICAL, 0);
            assertThrows(IllegalArgumentException.class, () -> store.consume(front, 1, invalid));
            assertEquals(16L, store.stock("front").available().get(FOOD));
            assertTrue(store.consumption(ARMY, 1).isEmpty());
            assertTrue(store.nativeSupplyCredits(ARMY).isEmpty());
            store.consume(front, 1, Map.of(FOOD, 100));
            assertEquals(2, store.nativeSupplyCredits(ARMY).getFirst().points(), "16/100 FOOD gives ceil(1.6) points");
            store.consume(front, 1, Map.of(FOOD, 1));
            assertEquals(1, store.nativeSupplyCredits(ARMY).size(), "same cycle cannot rewrite its points");
            store.consume(front, 2, Map.of(FOOD, 1));
            store.consume(front, 3, Map.of(dev.starsupply.api.ResourceType.MEDICAL, 1));
            assertEquals(1, store.nativeSupplyCredits(ARMY).size(), "zero FOOD and nonfood do not replenish");
        }
    }

    @Test
    void migratesV10ConsumptionRowsWithoutChangingTheirIdempotency() throws Exception {
        Path database = directory.resolve("migration-v10.sqlite");
        try (SupplyStore ignored = new SupplyStore(database)) {
            // A fresh store supplies the complete schema before this fixture restores v10 consumption.
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.createStatement()) {
            statement.execute("DROP INDEX consumption_expiry");
            statement.execute("DROP TABLE consumption");
            statement.execute("CREATE TABLE consumption (army TEXT NOT NULL, slot INTEGER NOT NULL, "
                    + "resource TEXT NOT NULL, demand INTEGER NOT NULL, units INTEGER NOT NULL, "
                    + "PRIMARY KEY(army,slot,resource))");
            statement.execute("CREATE INDEX consumption_expiry ON consumption(slot)");
            statement.execute("INSERT INTO consumption VALUES('" + ARMY + "',20778,'FOOD',1,1)");
            statement.execute("PRAGMA user_version=10");
        }

        try (SupplyStore migrated = new SupplyStore(database)) {
            assertEquals(11, migratedSchemaVersion(database));
            assertEquals(new SupplyStore.Consumption(1, 1),
                    migrated.consumption(ARMY, 20_778, 86_400_000L).get(FOOD));
            assertEquals(1, migrated.consumption(ARMY, 20_778, 86_399_999L).get(FOOD).units(),
                    "v10 rows have unknown cycle length and guard any matching legacy time bucket");
        }
    }

    @Test
    void consumptionSeparatesDifferentCycleLengthsThatShareTheSameSlot() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("consumption-period-collision.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase purchase = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, purchase.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            var front = store.warehouse("front").orElseThrow();
            long timestamp = 1_795_252_394_135L;
            long slot = Math.floorDiv(timestamp, 86_400_000L);
            assertEquals(slot, Math.floorDiv(timestamp, 86_399_999L));

            store.consume(front, slot, Map.of(FOOD, 1), 86_400_000L);
            store.consume(front, slot, Map.of(FOOD, 1), 86_399_999L);
            assertEquals(1, store.consumption(ARMY, slot, 86_400_000L).get(FOOD).units());
            assertEquals(1, store.consumption(ARMY, slot, 86_399_999L).get(FOOD).units());
            assertEquals(12, store.stock("front").available().get(FOOD));

            store.consume(front, slot, Map.of(FOOD, 1), 86_400_000L);
            assertEquals(12, store.stock("front").available().get(FOOD),
                    "retrying one cycle must not consume another physical item");

            store.pruneConsumption(1_795_219_185_000L);
            assertTrue(store.consumption(ARMY, slot, 86_400_000L).containsKey(FOOD));
            assertTrue(store.consumption(ARMY, slot, 86_399_999L).isEmpty(),
                    "pruning must compare cycle timestamps, not slots with different units");
            assertEquals(12, store.stock("front").available().get(FOOD),
                    "retrying one cycle must not consume another physical item");
        }
    }

    @Test
    void nativeCreditKeepsCycleLengthInItsStableIdentityAfterConsumptionPruning() throws Exception {
        Path database = directory.resolve("native-supply-period.sqlite");
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            Purchase first = bought(store, logistics);
            Shipment firstShipment = logistics.createShipment(OFFICER, first.id(), "front", REWARD, NOW);
            Shipment firstMoving = logistics.accept(COURIER, DEPOT, firstShipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, firstMoving.id(), firstMoving.readyAt());
            settle(store, firstMoving.id(), firstMoving.readyAt());
            var front = store.warehouse("front").orElseThrow();
            store.consume(front, 7, Map.of(FOOD, 1), 300_000);
            UUID firstRequest = store.nativeSupplyCredits(ARMY).getFirst().id();

            store.pruneConsumption(Long.MAX_VALUE);
            assertTrue(store.consumption(ARMY, 7).isEmpty());
            var secondConsumption = store.consume(front, 7, Map.of(FOOD, 1), 600_000);
            assertEquals(1, secondConsumption.get(FOOD).units());

            var credits = store.nativeSupplyCredits(ARMY);
            assertEquals(2, credits.size());
            var requestsByPeriod = credits.stream().collect(java.util.stream.Collectors.toMap(
                    credit -> credit.intervalMillis(), credit -> credit.id()));
            assertEquals(firstRequest, requestsByPeriod.get(300_000L));
            assertNotEquals(firstRequest, requestsByPeriod.get(600_000L));
        }
    }

    @Test
    void invalidNativeRecipientIsHeldForReviewAndDoesNotBlockPhysicalStatus() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("native-supply-review.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase order = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            long tick = (Math.floorDiv(moving.readyAt(), INTERVAL) + 1) * INTERVAL;
            core.rejectNativeRecipient = true;
            assertTrue(logistics.computeArmySupply(tick, true).containsKey(ARMY));
            var credit = store.nativeSupplyCredits(ARMY).getFirst();
            assertEquals("REVIEW", credit.state());
            logistics.computeArmySupply(tick + 10_000, true);
            assertEquals(1, core.nativeCalls);
            assertEquals(0, core.nativePoints);
            assertFalse(logistics.reconcileNativeSupply(credit.id(), tick + 20_000));
            assertEquals("PENDING", store.nativeSupplyCredit(credit.id()).orElseThrow().state());
            core.rejectNativeRecipient = false;
            logistics.computeArmySupply(tick + 20_000, true);
            assertEquals("APPLIED", store.nativeSupplyCredits(ARMY).getFirst().state());
            assertEquals(2, core.nativeCalls);
            assertEquals(10, core.nativePoints);
        }
    }

    @Test
    void nativeSupplyReconciliationRequiresMatchingReceiptAndNeverReappliesIt() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("native-supply-reconcile.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase order = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            long tick = (Math.floorDiv(moving.readyAt(), INTERVAL) + 1) * INTERVAL;
            core.loseNativeResponse = true;
            logistics.computeArmySupply(tick, true);
            var credit = store.nativeSupplyCredits(ARMY).getFirst();
            assertEquals("PENDING", credit.state());
            assertEquals(10, core.nativePoints);
            store.deferNativeSupply(credit, tick + 5_000, true, "simulated lost confirmation");
            core.nativeSupplyReceiptOverride = new CoreGateway.NativeSupplyReceipt(credit.id(), FORWARD_ARMY,
                    NATION, credit.points(), credit.points());
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.reconcileNativeSupply(credit.id(), tick + 10_000));
            assertEquals("REVIEW", store.nativeSupplyCredit(credit.id()).orElseThrow().state());

            core.nativeSupplyReceiptOverride = null;
            assertTrue(logistics.reconcileNativeSupply(credit.id(), tick + 11_000));
            assertEquals("APPLIED", store.nativeSupplyCredit(credit.id()).orElseThrow().state());
            assertEquals(1, core.nativeCalls, "receipt reconciliation must not replay native supply");
            assertEquals(10, core.nativePoints);
        }
    }

    @Test
    void campaignDemandIsChargedOnceAndPeaceClearsTemporaryPressure() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("campaign-consumption.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase purchase = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, purchase.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            long tick = (Math.floorDiv(moving.readyAt(), INTERVAL) + 1) * INTERVAL + 1_000;
            core.campaign = new CoreGateway.CampaignConditions(true, false, tick);
            var winter = logistics.computeArmySupply(tick, true).get(ARMY);
            assertTrue(winter.campaign().winter());
            assertEquals(2, winter.demand().get(FOOD));
            assertEquals(2, winter.demand().get(dev.starsupply.api.ResourceType.MEDICAL));
            long stockAfterWinter = winter.available().get(FOOD);

            core.campaign = new CoreGateway.CampaignConditions(false, false, tick + 1_000);
            var thaw = logistics.computeArmySupply(tick + 1_000, true).get(ARMY);
            assertFalse(thaw.campaign().winter());
            assertEquals(winter.demand(), thaw.demand(), "a charged interval keeps its persisted demand");
            assertEquals(stockAfterWinter, thaw.available().get(FOOD));
            assertEquals(2, store.consumption(ARMY, winter.interval()).get(FOOD).demand());

            core.campaign = new CoreGateway.CampaignConditions(false, false, tick + INTERVAL);
            var spring = logistics.computeArmySupply(tick + INTERVAL, true).get(ARMY);
            assertEquals(1, spring.demand().get(FOOD));
            core.atWar = false;
            core.campaign = new CoreGateway.CampaignConditions(true, true, tick + INTERVAL * 2);
            var peace = logistics.computeArmySupply(tick + INTERVAL * 2, true).get(ARMY);
            assertEquals(dev.starsupply.api.SupplyModels.CampaignStatus.NORMAL, peace.campaign());
            assertEquals(1.0, peace.combatMultiplier());
            assertEquals(spring.available(), peace.available());
            assertTrue(store.consumption(ARMY, peace.interval()).isEmpty());
        }
    }

    @Test
    void capturedAndDistantFrontsPausePhysicalSupplyUntilControlAndRangeRecover() throws Exception {
        Path database = directory.resolve("front-access.sqlite");
        long recoveryTime;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            List<dev.starsupply.api.SupplyModels.SupplyConsumption> events = new java.util.ArrayList<>();
            logistics.configureFrontlines(256, events::add);
            Purchase order = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            settle(store, moving.id(), moving.readyAt());
            long time = Math.floorDiv(moving.readyAt(), INTERVAL) * INTERVAL + INTERVAL;
            var normal = logistics.computeArmySupply(time, true).get(ARMY);
            assertEquals(dev.starsupply.api.SupplyModels.FrontAccess.CONNECTED, normal.frontAccess());
            assertEquals(1, events.size());
            assertEquals(1, events.getFirst().consumed().get(FOOD));
            assertEquals(1, events.getFirst().demand().get(FOOD));
            long remaining = normal.available().get(FOOD);

            core.enemyRoadChunks = Set.of(6);
            var captured = logistics.computeArmySupply(time + INTERVAL, true).get(ARMY);
            assertEquals(dev.starsupply.api.SupplyModels.FrontAccess.CONTROL_LOST, captured.frontAccess());
            assertEquals(0, captured.coverage());
            assertEquals(remaining, captured.available().get(FOOD));
            assertTrue(store.consumption(ARMY, captured.interval()).isEmpty());
            assertTrue(logistics.route(NATION, "depot", "front").isEmpty());
            assertEquals(1, events.size());

            core.enemyRoadChunks = Set.of();
            core.armyPosition = new Position("world", 1_000, 64, 0);
            var distant = logistics.computeArmySupply(time + INTERVAL + 1, true).get(ARMY);
            assertEquals(dev.starsupply.api.SupplyModels.FrontAccess.ARMY_OUT_OF_RANGE, distant.frontAccess());
            assertEquals(remaining, distant.available().get(FOOD));
            core.armyPosition = FRONT;
            var recovered = logistics.computeArmySupply(time + INTERVAL + 2, true).get(ARMY);
            recoveryTime = time + INTERVAL + 2;
            assertEquals(dev.starsupply.api.SupplyModels.FrontAccess.CONNECTED, recovered.frontAccess());
            assertTrue(recovered.coverage() > 0);
            assertEquals(remaining - 2, recovered.available().get(FOOD));
            assertEquals(2, events.size());
            logistics.computeArmySupply(time + INTERVAL + 3, true);
            assertEquals(2, events.size(), "same army cycle cannot publish or consume twice");
        }
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            List<dev.starsupply.api.SupplyModels.SupplyConsumption> events = new java.util.ArrayList<>();
            logistics.configureFrontlines(256, events::add);
            logistics.computeArmySupply(recoveryTime + 3, true);
            assertTrue(events.isEmpty(), "a persisted cycle must not publish again after restart");
        }
    }

    @Test
    void wartimeExportControlsFollowExporterAuthorityAndDoNotBlockImportsOrInternalSupply() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("wartime-controls.sqlite"))) {
            Logistics logistics = configured(store);
            logistics.configureWartimeExports(Set.of(FOOD));
            Purchase purchase = purchase(logistics);
            core.hostility = false; // The two countries are neutral while one fights a third country.
            core.wartimeNations = Set.of(ENEMY);
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.openSale(ENEMY_PLAYER, DEPOT, purchase.id()));
            assertTrue(store.lots(purchase.id()).isEmpty());
            assertTrue(store.pendingInventory(ENEMY_PLAYER).isEmpty());
            logistics.openSale(OFFICER, DEPOT, purchase.id());
            core.wartimeNations = Set.of(NATION);
            logistics.openSale(ENEMY_PLAYER, DEPOT, purchase.id());
            core.wartimeNations = Set.of(ENEMY);
            logistics.configureWartimeExports(Set.of());
            logistics.openSale(ENEMY_PLAYER, DEPOT, purchase.id());
        }
    }

    @Test
    void lostDepotCannotBuyOrReceiveGoodsAndForwardRebindingDoesNotMoveStock() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("warehouse-control.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase original = bought(store, logistics);
            Purchase open = purchase(logistics);
            core.enemyRoadChunks = Set.of(0);
            assertThrows(IllegalArgumentException.class, () -> purchase(logistics));
            assertThrows(IllegalArgumentException.class, () -> logistics.openSale(OFFICER, DEPOT, open.id()));
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.createShipment(OFFICER, original.id(), "front", REWARD, NOW));
            assertEquals(16L, store.stock("depot").available().get(FOOD));
            core.enemyRoadChunks = Set.of();
            logistics.openSale(OFFICER, DEPOT, open.id());
            core.armyPosition = new Position("world", 1_000, 64, 0);
            logistics.createWarehouse(OFFICER, "forward", core.armyPosition, ARMY);
            assertNull(store.warehouse("front").orElseThrow().armyId());
            assertEquals(ARMY, store.warehouse("forward").orElseThrow().armyId());
            assertEquals(16L, store.stock("depot").available().get(FOOD));
            assertTrue(store.stock("forward").available().isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.createWarehouse(OFFICER, "depot", FRONT, ARMY));
            assertEquals(ARMY, store.warehouse("forward").orElseThrow().armyId(),
                    "failed new station creation must roll back the binding replacement");
        }
    }

    @Test
    void rebindExistingFrontPreservesDeliveredLotsAndRejectsForeignOrDuplicateArmyBindings() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("front-rebind.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase openedOrder = purchase(logistics);
            UUID purchaseTransfer = UUID.randomUUID();
            prepare(store, purchaseTransfer, openedOrder.id(), SELLER);
            store.confirmTaken(purchaseTransfer, NOW);
            settle(store, purchaseTransfer, NOW);
            Purchase order = store.purchase(openedOrder.id()).orElseThrow();
            Shipment opened = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            Shipment accepted = logistics.accept(COURIER, DEPOT, opened.id(), NOW);
            logistics.deliver(COURIER, FRONT, opened.id(), accepted.readyAt());
            settle(store, opened.id(), accepted.readyAt());
            assertEquals(TransportState.DELIVERED, logistics.getShipment(opened.id()).state());
            assertEquals(16L, store.stock("front").available().get(FOOD));

            assertThrows(IllegalArgumentException.class,
                    () -> logistics.rebindFront(OFFICER, "depot", FORWARD_ARMY));
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.rebindFront(OFFICER, "front", FOREIGN_ARMY));
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.rebindFront(ENEMY_PLAYER, "front", FORWARD_ARMY));
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.rebindFront(OFFICER, "front", UUID.randomUUID()));
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.rebindFront(OFFICER, "front", null));
            assertEquals(ARMY, store.warehouse("front").orElseThrow().armyId());

            var rebound = logistics.rebindFront(OFFICER, "front", FORWARD_ARMY);
            assertEquals(FORWARD_ARMY, rebound.armyId());
            assertEquals(FRONT, rebound.position());
            assertEquals(16L, store.stock("front").available().get(FOOD));
            assertEquals(TransportState.DELIVERED, logistics.getShipment(opened.id()).state());
            var recoveredStatus = logistics.computeArmySupply(accepted.readyAt() + INTERVAL, false)
                    .get(FORWARD_ARMY);
            assertEquals(0.94, recoveredStatus.combatMultiplier(), 0.000001);
            assertEquals(-5.5, recoveredStatus.moraleDelta(), 0.000001);

            logistics.createWarehouse(OFFICER, "second-front", FRONT, ARMY);
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.rebindFront(OFFICER, "front", ARMY));
            assertEquals(FORWARD_ARMY, store.warehouse("front").orElseThrow().armyId());
            assertEquals(16L, store.stock("front").available().get(FOOD));
        }
    }

    @Test
    void restartsAtInventoryAndPaymentBoundariesThenDeliversAndConsumesExactlyOnce() throws Exception {
        Path database = directory.resolve("transactions.sqlite");
        UUID transferId = UUID.randomUUID();
        UUID purchaseId;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            Purchase order = purchase(logistics);
            purchaseId = order.id();
            assertEquals(0.85, logistics.computeArmySupply(NOW, false).get(ARMY).combatMultiplier());
            assertEquals(-10, logistics.computeArmySupply(NOW, false).get(ARMY).moraleDelta());
            prepare(store, transferId, order.id(), SELLER);
            assertEquals(RESERVED, store.purchase(order.id()).orElseThrow().state());
            assertTrue(store.pendingSettlements(NOW).isEmpty());
            assertTrue(store.stock("depot").available().isEmpty());
        }

        try (SupplyStore store = new SupplyStore(database)) {
            assertEquals(TransferState.PREPARED, store.pendingInventory(SELLER).orElseThrow().state());
            assertEquals(transferId, store.pendingInventory(SELLER).orElseThrow().id());
            Lot lot = store.lots(transferId).getFirst();
            assertEquals("kaleidoscopecookery:mantou", lot.itemId());
            assertEquals("GOOD", lot.quality());
            assertEquals(1.2, lot.qualityRatio());
            assertArrayEquals(PAYLOAD, lot.payload());
            store.confirmTaken(transferId, NOW);
            store.confirmTaken(transferId, NOW);
            assertEquals(TransferState.TAKEN, store.transfer(transferId).orElseThrow().state());
            assertEquals(SETTLING, store.purchase(purchaseId).orElseThrow().state());
            Settlement pending = store.pendingSettlements(NOW).getFirst();
            assertEquals(transferId, pending.id());
            assertEquals(CoreGateway.PaymentResult.PAID, pay(pending));
            // Emulate a stop after the external debit/credit but before recording its local outcome.
        }

        UUID shipmentId;
        long readyAt;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            settle(store, transferId, NOW + 1);
            assertEquals(BOUGHT, store.purchase(purchaseId).orElseThrow().state());
            assertEquals(16L, store.stock("depot").available().get(FOOD));
            assertEquals(PURCHASE_PRICE, core.balance(SELLER));
            assertEquals(1, core.appliedPayments());
            assertTrue(store.finishSettlement(transferId, "PAID", NOW + 1, false).isEmpty());
            Shipment shipment = logistics.createShipment(OFFICER, purchaseId, "front", REWARD, NOW + 2);
            shipmentId = shipment.id();
            assertTrue(store.stock("depot").available().isEmpty());
            assertEquals(16L, store.stock("depot").reserved().get(FOOD));
            readyAt = logistics.accept(COURIER, DEPOT, shipmentId, NOW + 3).readyAt();
        }

        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            Shipment recovered = logistics.getShipment(shipmentId);
            assertEquals(COURIER, recovered.carrier());
            assertEquals(readyAt, recovered.readyAt());
            assertThrows(IllegalArgumentException.class, () -> logistics.deliver(COURIER, FRONT, shipmentId, readyAt - 1));
            assertThrows(IllegalArgumentException.class, () -> logistics.deliver(COURIER, DEPOT, shipmentId, readyAt));
            assertThrows(IllegalArgumentException.class, () -> logistics.deliver(SELLER, FRONT, shipmentId, readyAt));
            logistics.deliver(COURIER, FRONT, shipmentId, readyAt);
            logistics.deliver(COURIER, FRONT, shipmentId, readyAt);
            assertEquals(List.of(shipmentId), store.pendingSettlements(readyAt).stream().map(Settlement::id).toList());
            assertTrue(store.stock("front").available().isEmpty());
            assertEquals(CoreGateway.PaymentResult.PAID, pay(store.settlement(shipmentId).orElseThrow()));
        }

        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            settle(store, shipmentId, readyAt);
            logistics.deliver(COURIER, FRONT, shipmentId, readyAt + 1);
            assertEquals(TransportState.DELIVERED, logistics.getShipment(shipmentId).state());
            assertTrue(store.pendingSettlements(readyAt + 1).isEmpty());
            assertTrue(store.stock("depot").reserved().isEmpty());
            assertEquals(16L, store.stock("front").available().get(FOOD));
            assertEquals(REWARD, core.balance(COURIER));
            assertEquals(new BigDecimal("45.00"), core.treasury);
            assertEquals(2, core.appliedPayments());

            var first = logistics.computeArmySupply(readyAt, true).get(ARMY);
            assertEquals(1, first.demand().get(FOOD));
            assertEquals(14L, first.available().get(FOOD));
            assertEquals(0.94, first.combatMultiplier(), 0.000001);
            assertEquals(-5.5, first.moraleDelta(), 0.000001);
            assertEquals(first, logistics.computeArmySupply(readyAt + 1, true).get(ARMY));
        }

        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            assertEquals(14L, logistics.computeArmySupply(readyAt + 2, true).get(ARMY).available().get(FOOD));
            assertEquals(12L, logistics.computeArmySupply(readyAt + INTERVAL, true).get(ARMY).available().get(FOOD));
            core.atWar = false;
            var peace = logistics.computeArmySupply(readyAt + INTERVAL * 2, true).get(ARMY);
            assertEquals(12L, peace.available().get(FOOD));
            assertEquals(1.0, peace.combatMultiplier());
            assertEquals(0, peace.moraleDelta());
        }
    }

    @Test
    void reservesASingleSellerAndCourierUnderConcurrentAttempts() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("concurrent.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase order = purchase(logistics);
            UUID firstId = UUID.randomUUID();
            UUID secondId = UUID.randomUUID();
            try (var workers = Executors.newFixedThreadPool(2)) {
                CountDownLatch startSale = new CountDownLatch(1);
                var first = workers.submit(() -> {
                    startSale.await();
                    return tryPrepare(store, firstId, order.id(), SELLER);
                });
                var second = workers.submit(() -> {
                    startSale.await();
                    return tryPrepare(store, secondId, order.id(), COURIER);
                });
                startSale.countDown();
                boolean firstWon = first.get(5, TimeUnit.SECONDS);
                assertTrue(firstWon ^ second.get(5, TimeUnit.SECONDS));
                UUID winningId = firstWon ? firstId : secondId;
                UUID losingId = firstWon ? secondId : firstId;
                assertTrue(store.transfer(losingId).isEmpty());
                assertEquals(winningId, store.purchase(order.id()).orElseThrow().settlementId());
                store.confirmTaken(winningId, NOW);
                settle(store, winningId, NOW);
                Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);

                CountDownLatch startClaim = new CountDownLatch(1);
                var firstClaim = workers.submit(() -> {
                    startClaim.await();
                    return tryAccept(logistics, SELLER, shipment.id());
                });
                var secondClaim = workers.submit(() -> {
                    startClaim.await();
                    return tryAccept(logistics, COURIER, shipment.id());
                });
                startClaim.countDown();
                boolean sellerWon = firstClaim.get(5, TimeUnit.SECONDS);
                assertTrue(sellerWon ^ secondClaim.get(5, TimeUnit.SECONDS));
                assertEquals(sellerWon ? SELLER : COURIER, logistics.getShipment(shipment.id()).carrier());
            }
        }
    }

    @Test
    void expiresOrCancelsShipmentsWithoutPayingOrLosingReservedItems() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("expire.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase purchase = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, purchase.id(), "front", REWARD, NOW);
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            assertEquals(0, store.expire(moving.expiresAt() - 1));
            assertEquals(1, store.expire(moving.expiresAt()));
            assertEquals(0, store.expire(moving.expiresAt() + 1));
            assertEquals(TransportState.FAILED, logistics.getShipment(moving.id()).state());
            assertEquals(16L, store.stock("depot").available().get(FOOD));
            assertTrue(store.stock("depot").reserved().isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.deliver(COURIER, FRONT, moving.id(), moving.expiresAt()));
            assertTrue(store.settlement(moving.id()).isEmpty());

            Shipment retry = logistics.createShipment(OFFICER, purchase.id(), "front", REWARD, moving.expiresAt());
            assertThrows(IllegalArgumentException.class, () -> logistics.cancelShipment(COURIER, retry.id()));
            logistics.cancelShipment(OFFICER, retry.id());
            assertEquals(TransportState.CANCELLED, logistics.getShipment(retry.id()).state());
            assertEquals(16L, store.stock("depot").available().get(FOOD));
            assertTrue(store.settlement(retry.id()).isEmpty());
            assertEquals(1, core.appliedPayments());
        }
    }

    @Test
    void rejectedPaymentAndInterruptedReturnKeepExactlyOneRecoverablePhysicalBatch() throws Exception {
        Path database = directory.resolve("returns.sqlite");
        UUID id = UUID.randomUUID();
        UUID firstReceipt = UUID.randomUUID();
        UUID purchaseId;
        core.treasury = BigDecimal.ZERO;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            purchaseId = purchase(logistics).id();
            prepare(store, id, purchaseId, SELLER);
            store.confirmTaken(id, NOW);
            settle(store, id, NOW);
            assertEquals(CANCELLED, store.purchase(purchaseId).orElseThrow().state());
            assertEquals(TransferState.REJECTED, store.recoverable(SELLER).getFirst().state());
            assertTrue(store.recoverable(COURIER).isEmpty());
            assertTrue(store.stock("depot").available().isEmpty());
            assertEquals(BigDecimal.ZERO, core.balance(SELLER));
            store.beginReturn(id, firstReceipt, AFTER, BEFORE);
        }

        try (SupplyStore store = new SupplyStore(database)) {
            assertEquals(firstReceipt, store.pendingInventory(SELLER).orElseThrow().returnId());
            assertArrayEquals(PAYLOAD, store.lots(id).getFirst().payload());
            store.finishReturn(id, UUID.randomUUID(), true);
            assertEquals(TransferState.RETURNING, store.transfer(id).orElseThrow().state());
            store.finishReturn(id, firstReceipt, false);
            assertEquals(TransferState.REJECTED, store.recoverable(SELLER).getFirst().state());
            UUID secondReceipt = UUID.randomUUID();
            store.beginReturn(id, secondReceipt, AFTER, BEFORE);
            store.finishReturn(id, firstReceipt, true);
            assertEquals(secondReceipt, store.pendingInventory(SELLER).orElseThrow().returnId());
            store.finishReturn(id, secondReceipt, true);
            store.finishReturn(id, secondReceipt, true);
            assertEquals(TransferState.RETURNED, store.transfer(id).orElseThrow().state());
            assertTrue(store.recoverable(SELLER).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> store.beginReturn(id, UUID.randomUUID(), AFTER, BEFORE));
        }
    }

    @Test
    void crossNationSupplySalesRespectStarCoreResourceEmbargoes() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("embargo.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase order = purchase(logistics);
            core.atWar = false;
            core.embargoed = true;

            assertThrows(IllegalArgumentException.class,
                    () -> logistics.openSale(COURIER, DEPOT, order.id()));

            assertThrows(IllegalArgumentException.class,
                    () -> logistics.openSale(ENEMY_PLAYER, DEPOT, order.id()));
            assertEquals(OPEN, store.purchase(order.id()).orElseThrow().state());
            assertTrue(store.stock("depot").available().isEmpty());

            core.embargoed = false;
            var authorization = logistics.openSale(ENEMY_PLAYER, DEPOT, order.id());
            assertEquals(order.id(), authorization.purchase().id());
            assertEquals(16, logistics.validateSaleUnits(authorization,
                    List.of(new Lot("kaleidoscopecookery:mantou", "BASE", 1.0, 8, 16, PAYLOAD))));
            assertThrows(IllegalArgumentException.class, () -> logistics.validateSaleUnits(authorization,
                    List.of(new Lot("kaleidoscopecookery:mantou", "BASE", 1.0, 8, 200, PAYLOAD))));
        }
    }

    @Test
    void crossNationSalesRejectMissingOrInsufficientAgreementTermsWithoutReservingInventory() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("agreement-rejections.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase order = purchase(logistics);
            core.hostility = false;
            core.tradePermitAvailable = false;

            assertThrows(IllegalArgumentException.class,
                    () -> logistics.openSale(ENEMY_PLAYER, DEPOT, order.id()));

            core.tradePermitAvailable = true;
            core.tradeMaximumUnits = 8;
            var insufficient = logistics.openSale(ENEMY_PLAYER, DEPOT, order.id());
            List<Lot> goods = List.of(new Lot("minecraft:bread", "BASE", 1.0, 8, 16, PAYLOAD));
            assertThrows(IllegalArgumentException.class, () -> logistics.validateSaleUnits(insufficient, goods));

            core.tradeMaximumUnits = 100;
            core.tradeUnitPrice = new BigDecimal("3.00");
            var mispriced = logistics.openSale(ENEMY_PLAYER, DEPOT, order.id());
            assertThrows(IllegalArgumentException.class, () -> logistics.validateSaleUnits(mispriced, goods));

            assertEquals(OPEN, store.purchase(order.id()).orElseThrow().state());
            assertTrue(store.lots(order.id()).isEmpty());
            assertTrue(store.pendingInventory(ENEMY_PLAYER).isEmpty());
            assertTrue(store.stock("depot").available().isEmpty());
        }
    }

    @Test
    void crossNationPhysicalLotsReserveStarCoreAgreementVolumeIdempotently() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("trade-volume.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase first = purchase(logistics);
            Purchase second = logistics.createPurchase(OFFICER, "depot", FOOD, 8, PURCHASE_PRICE, NOW + 1);
            UUID agreement = UUID.fromString("1c937a5c-5b38-4d50-b1cd-121123548f17");
            UUID firstTransfer = UUID.randomUUID();
            UUID secondTransfer = UUID.randomUUID();
            store.prepareTransfer(firstTransfer, first.id(), ENEMY_PLAYER, ENEMY, agreement, 20, 16,
                    BEFORE, AFTER, List.of(new Lot("minecraft:bread", "BASE", 1.0, 4, 8, PAYLOAD),
                            new Lot("minecraft:bread", "BASE", 1.0, 4, 8, PAYLOAD)), NOW);
            var stored = store.transfer(firstTransfer).orElseThrow();
            assertEquals(ENEMY, stored.exporterNation());
            assertEquals(agreement, stored.tradeAgreement());
            assertEquals(16, stored.tradeUnits());

            assertThrows(IllegalArgumentException.class, () -> store.prepareTransfer(secondTransfer, second.id(), SELLER,
                    ENEMY, agreement, 20, 16, BEFORE, AFTER,
                    List.of(new Lot("minecraft:bread", "BASE", 1.0, 4, 8, PAYLOAD),
                            new Lot("minecraft:bread", "BASE", 1.0, 4, 8, PAYLOAD)), NOW + 1));
            assertEquals(OPEN, store.purchase(second.id()).orElseThrow().state());
            assertTrue(store.transfer(secondTransfer).isEmpty());

            store.abandonTransfer(firstTransfer);
            store.prepareTransfer(secondTransfer, second.id(), SELLER, ENEMY, agreement, 20, 16,
                    BEFORE, AFTER, List.of(new Lot("minecraft:bread", "BASE", 1.0, 4, 8, PAYLOAD),
                            new Lot("minecraft:bread", "BASE", 1.0, 4, 8, PAYLOAD)), NOW + 2);
            assertEquals(secondTransfer, store.transfer(secondTransfer).orElseThrow().id());
        }
    }

    @Test
    void escortedHighRiskShipmentPersistsParticipantsAndReturnsCargoWithoutFee() throws Exception {
        Path database = directory.resolve("escort.sqlite");
        UUID shipmentId = UUID.randomUUID();
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            Purchase order = bought(store, logistics);
            store.addShipment(new Shipment(shipmentId, NATION, order.id(), "depot", "front", REWARD,
                    TransportState.OPEN, null, 0, 0, "main", NOW, 1.0));

            store.joinEscort(shipmentId, SELLER, NOW);
            assertEquals(List.of(SELLER), store.escorts(shipmentId));
            assertThrows(IllegalArgumentException.class, () -> store.joinEscort(shipmentId, SELLER, NOW + 1));
            for (int index = 0; index < 3; index++) {
                UUID escort = UUID.nameUUIDFromBytes(("escort-" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                store.joinEscort(shipmentId, escort, NOW + index + 1);
            }
            assertEquals(4, store.escortCount(shipmentId));
            assertThrows(IllegalArgumentException.class,
                    () -> store.joinEscort(shipmentId, COURIER, NOW + 10));
        }

        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            assertEquals(4, store.escortCount(shipmentId));
            assertEquals(List.of(SELLER), store.escorts(shipmentId).subList(0, 1));
            assertEquals(1.0, logistics.getShipment(shipmentId).routeRisk());
            store.accept(shipmentId, COURIER, NOW + 1_000, NOW + 10_000, "main", 1.0);
            assertFalse(store.failRiskyShipment(shipmentId, COURIER, NOW), "risk cannot resolve before ready time");
            assertTrue(store.failRiskyShipment(shipmentId, COURIER, NOW + 1_000));
            assertEquals(new SupplyStore.ShipmentFailure("RISK", NOW + 1_000),
                    store.shipmentFailure(shipmentId).orElseThrow());
            assertEquals(TransportState.FAILED, logistics.getShipment(shipmentId).state());
            assertEquals(16L, store.stock("depot").available().get(FOOD));
            assertTrue(store.stock("depot").reserved().isEmpty());
            assertFalse(store.failRiskyShipment(shipmentId, COURIER, NOW + 1_001));
            assertThrows(IllegalArgumentException.class, () -> store.joinEscort(shipmentId, SELLER, NOW + 20));
            assertTrue(store.settlement(shipmentId).isEmpty());
        }
    }

    @Test
    void logisticsEscortRequiresNationAndOriginAndPersistsProtectedRiskAcrossRestart() throws Exception {
        Path database = directory.resolve("escort-permission.sqlite");
        UUID shipmentId;
        double expectedRisk;
        long expectedReady;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            core.enemyRoadChunk = true;
            Purchase order = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, order.id(), "front", REWARD, NOW);
            shipmentId = shipment.id();
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.joinEscort(ENEMY_PLAYER, DEPOT, shipmentId, NOW + 1));
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.joinEscort(ESCORT_PLAYER, FRONT, shipmentId, NOW + 1));
            assertEquals(1, logistics.joinEscort(ESCORT_PLAYER, DEPOT, shipmentId, NOW + 2).size());
            var route = logistics.route(NATION, "depot", "front").orElseThrow();
            expectedRisk = route.risk() * 0.9;
            Shipment accepted = logistics.accept(COURIER, DEPOT, shipmentId, NOW + 3);
            assertEquals(expectedRisk, accepted.routeRisk(), 0.000001);
            expectedReady = accepted.readyAt();
            assertTrue(expectedReady - (NOW + 3) < (long) Math.ceil(route.cost() * 1000 / 10));
        }

        try (SupplyStore store = new SupplyStore(database)) {
            Shipment resumed = store.shipment(shipmentId).orElseThrow();
            assertEquals(expectedRisk, resumed.routeRisk(), 0.000001);
            assertEquals(expectedReady, resumed.readyAt());
            assertEquals(List.of(ESCORT_PLAYER), store.escorts(shipmentId));
        }
    }

    @Test
    void carrierCannotEarnTheirOwnEscortRiskOrTravelBonus() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("self-escort.sqlite"))) {
            Logistics logistics = configured(store);
            core.enemyRoadChunk = true;
            Shipment shipment = logistics.createShipment(OFFICER, bought(store, logistics).id(), "front", REWARD, NOW);
            logistics.joinEscort(OFFICER, DEPOT, shipment.id(), NOW + 1);
            var route = logistics.route(NATION, "depot", "front").orElseThrow();
            assertTrue(route.risk() > 0);
            assertEquals(route.risk(), logistics.transportRisk(shipment.id(), OFFICER), 0.000001,
                    "Insurance for the future carrier excludes their own escort registration");
            Shipment accepted = logistics.accept(OFFICER, DEPOT, shipment.id(), NOW + 2);
            assertEquals(route.risk(), accepted.routeRisk(), 0.000001);
            assertEquals(NOW + 2 + (long) Math.ceil(route.cost() * 1000 / 10), accepted.readyAt());
            assertEquals(0, store.escortCount(shipment.id()));
            assertTrue(store.escorts(shipment.id()).isEmpty(), "Role switch removes the carrier's escort row");
        }
    }

    @Test
    void switchingEscortToCarrierKeepsOnlyIndependentGuardBonusesAcrossRestart() throws Exception {
        Path database = directory.resolve("escort-carrier-switch.sqlite");
        UUID shipmentId;
        double expectedRisk;
        long expectedReady;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            core.enemyRoadChunk = true;
            Shipment shipment = logistics.createShipment(OFFICER, bought(store, logistics).id(), "front", REWARD, NOW);
            shipmentId = shipment.id();
            logistics.joinEscort(OFFICER, DEPOT, shipmentId, NOW + 1);
            logistics.joinEscort(ESCORT_PLAYER, DEPOT, shipmentId, NOW + 2);
            var route = logistics.route(NATION, "depot", "front").orElseThrow();
            expectedRisk = route.risk() * 0.9;
            assertEquals(2, store.escortCount(shipmentId));
            assertEquals(expectedRisk, logistics.transportRisk(shipmentId, OFFICER), 0.000001);
            Shipment accepted = logistics.accept(OFFICER, DEPOT, shipmentId, NOW + 3);
            expectedReady = NOW + 3 + (long) Math.ceil(route.cost() * 0.95 * 1000 / 10);
            assertEquals(expectedRisk, accepted.routeRisk(), 0.000001);
            assertEquals(expectedReady, accepted.readyAt());
            assertEquals(List.of(ESCORT_PLAYER), store.escorts(shipmentId));
            assertFalse(store.accept(shipmentId, ESCORT_PLAYER, NOW + 1_000, NOW + 10_000, "main", 0));
            assertEquals(List.of(ESCORT_PLAYER), store.escorts(shipmentId),
                    "A rejected duplicate accept cannot remove another guard");
        }
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            Shipment resumed = logistics.getShipment(shipmentId);
            assertEquals(expectedRisk, resumed.routeRisk(), 0.000001);
            assertEquals(expectedReady, resumed.readyAt());
            assertEquals(1, store.escortCount(shipmentId));
            assertEquals(List.of(ESCORT_PLAYER), store.escorts(shipmentId));
            assertEquals(expectedRisk, logistics.transportRisk(shipmentId), 0.000001);
        }
    }

    @Test
    void routeRiskScansNarrowHostileTerritoryBeyondThirtyTwoSamples() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("long-road-risk.sqlite"))) {
            Logistics logistics = logistics(store);
            Position start = new Position("world", 0, 64, 8);
            Position end = new Position("world", 3_200, 64, 8);
            logistics.createWarehouse(OFFICER, "long-depot", start, null);
            logistics.createWarehouse(OFFICER, "long-front", end, null);
            core.enemyRoadChunks = Set.of(51);
            logistics.createRoad(OFFICER, "long-road", "long-depot", "long-front", 1, null);

            var edge = logistics.graph(NATION).edges().getFirst();
            assertFalse(edge.blocked());
            assertEquals(1.0 / 201, edge.hostileRisk(), 0.000001);
            assertEquals(edge.hostileRisk(), logistics.route(NATION, "long-depot", "long-front")
                    .orElseThrow().risk(), 0.000001);
        }
    }

    @Test
    void routeRiskAcceptsTheChunkScanLimitAndDisablesLegacyRoadsOverIt() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("route-risk-limit.sqlite"))) {
            Logistics logistics = logistics(store);
            Position start = new Position("world", 0, 64, 8);
            Position withinLimit = new Position("world", 65_535, 64, 8);
            Position overLimit = new Position("world", 65_536, 64, 8);
            logistics.createWarehouse(OFFICER, "limit-depot", start, null);
            logistics.createWarehouse(OFFICER, "limit-front", withinLimit, null);
            logistics.createWarehouse(OFFICER, "over-front", overLimit, null);
            core.enemyRoadChunks = Set.of(4_094);
            logistics.createRoad(OFFICER, "limit-road", "limit-depot", "limit-front", 1, null);

            var edgeAtLimit = logistics.graph(NATION).edges().stream()
                    .filter(road -> road.id().equals("limit-road")).findFirst().orElseThrow();
            assertFalse(edgeAtLimit.blocked());
            assertEquals(1.0 / 4_096, edgeAtLimit.hostileRisk(), 0.000001);

            assertThrows(IllegalArgumentException.class,
                    () -> logistics.createRoad(OFFICER, "too-long", "limit-depot", "over-front", 1, null));
            store.addRoad(new SupplyStore.Road("legacy-long", NATION, "limit-depot", "over-front", 1, false, null));
            var legacy = logistics.graph(NATION).edges().stream()
                    .filter(road -> road.id().equals("legacy-long")).findFirst().orElseThrow();
            assertTrue(legacy.blocked());
            assertEquals(0.0, legacy.hostileRisk());
            assertTrue(logistics.route(NATION, "limit-depot", "over-front").isEmpty());
        }
    }

    @Test
    void starCoreTechnologyTransportBonusImprovesRouteCostWithoutChangingThreatRisk() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("strategy-route.sqlite"))) {
            Logistics logistics = configured(store);
            var baseline = logistics.route(NATION, "depot", "front").orElseThrow();
            core.logisticsModifiers = new CoreGateway.LogisticsModifiers(0.10, 0.50, 0.08);
            var improved = logistics.route(NATION, "depot", "front").orElseThrow();
            assertEquals(baseline.cost() / 1.5, improved.cost(), 0.000001);
            assertEquals(baseline.risk(), improved.risk(), 0.000001);
        }
    }

    @Test
    void reshippingTheSamePhysicalBatchCannotRerollConvoyRisk() throws Exception {
        Path database = directory.resolve("risk-retry.sqlite");
        UUID purchaseId;
        UUID transferId;
        UUID firstShipmentId;
        double firstRiskRoll;
        double batchRoll;
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = configured(store);
            Purchase bought = bought(store, logistics);
            purchaseId = bought.id();
            transferId = bought.settlementId();
            Shipment first = logistics.createShipment(OFFICER, purchaseId, "front", REWARD, NOW);
            firstShipmentId = first.id();
            firstRiskRoll = persistedRiskRoll(database, firstShipmentId);
            batchRoll = persistedConvoyRoll(database, transferId);
            assertEquals(batchRoll, firstRiskRoll, 0.0);
            logistics.cancelShipment(OFFICER, firstShipmentId);
        }
        try (SupplyStore store = new SupplyStore(database)) {
            Logistics logistics = logistics(store);
            Shipment retry = logistics.createShipment(OFFICER, purchaseId, "front", REWARD, NOW + 1);
            assertNotEquals(firstShipmentId, retry.id());
            assertEquals(batchRoll, persistedConvoyRoll(database, transferId), 0.0);
            assertEquals(firstRiskRoll, persistedRiskRoll(database, retry.id()), 0.0);
        }
    }

    private static double persistedRiskRoll(Path database, UUID shipment) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("SELECT risk_roll FROM shipment WHERE id=?")) {
            statement.setString(1, shipment.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("missing shipment risk roll");
                return result.getDouble(1);
            }
        }
    }

    private static double persistedConvoyRoll(Path database, UUID transfer) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement("SELECT convoy_roll FROM item_transfer WHERE id=?")) {
            statement.setString(1, transfer.toString());
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new AssertionError("missing convoy risk roll");
                return result.getDouble(1);
            }
        }
    }

    @Test
    void militaryResourceCategoriesMapToStarCoreStrategicResourceKeys() {
        assertEquals("wheat", dev.starsupply.api.ResourceType.FOOD.starCoreTradeId());
        assertEquals("medical_supplies", dev.starsupply.api.ResourceType.MEDICAL.starCoreTradeId());
        assertEquals("weapons", dev.starsupply.api.ResourceType.EQUIPMENT.starCoreTradeId());
        assertEquals("steel", dev.starsupply.api.ResourceType.MATERIALS.starCoreTradeId());
        assertEquals("explosives", dev.starsupply.api.ResourceType.ENGINEERING.starCoreTradeId());
        assertEquals("ammunition", dev.starsupply.api.ResourceType.STRATEGIC.starCoreTradeId());
    }

    @Test
    void regionalQuotesUseDepotPhysicalStockAndOpenItemDemand() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("market.sqlite"))) {
            Logistics logistics = configured(store);
            logistics.createWarehouse(OFFICER, "depot-two", JUNCTION, null);

            MarketQuote depotOneQuiet = logistics.marketQuote("depot", FOOD, NOW);
            MarketQuote depotTwoQuiet = logistics.marketQuote("depot-two", FOOD, NOW);
            assertEquals(new BigDecimal("2.50"), depotOneQuiet.unitPrice());
            assertEquals(depotOneQuiet.unitPrice(), depotTwoQuiet.unitPrice());

            Purchase demand = logistics.createPurchase(OFFICER, "depot", FOOD, 8, PURCHASE_PRICE, NOW);
            MarketQuote shortage = logistics.marketQuote("depot", FOOD, NOW + 1);
            assertEquals(8, shortage.demandItems());
            assertEquals(new BigDecimal("3.33"), shortage.unitPrice());
            assertEquals(new BigDecimal("2.50"), logistics.marketQuote("depot-two", FOOD, NOW + 1).unitPrice());

            Purchase lockedMarketOrder = logistics.createMarketPurchase(OFFICER, "depot-two", FOOD, 8, NOW + 1);
            assertEquals(new BigDecimal("20.00"), lockedMarketOrder.price());
            assertEquals(new BigDecimal("20.00"), store.purchase(lockedMarketOrder.id()).orElseThrow().price());

            store.cancelPurchase(demand.id());
            assertEquals(depotOneQuiet.unitPrice(), logistics.marketQuote("depot", FOOD, NOW + 2).unitPrice());

            Purchase restock = bought(store, logistics);
            assertEquals(dev.starsupply.api.SupplyModels.PurchaseState.BOUGHT, restock.state());
            assertEquals(8, logistics.marketQuote("depot", FOOD, NOW + 3).stockItems());
            logistics.createShipment(OFFICER, restock.id(), "front", REWARD, NOW + 4);
            assertEquals(0, logistics.marketQuote("depot", FOOD, NOW + 5).stockItems());
        }
    }

    @Test
    void persistsStarCoreTariffSnapshotAndHoldsRealStockUntilFundingIsResolved() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("tariff-snapshot.sqlite"))) {
            Logistics logistics = configured(store);
            Purchase purchase = purchase(logistics);
            UUID transfer = UUID.randomUUID();
            TaxAssessment assessment = new TaxAssessment(transfer, "TARIFF", NATION, SELLER,
                    PURCHASE_PRICE, new BigDecimal("10.00"));
            store.prepareTransfer(transfer, purchase.id(), SELLER, ENEMY,
                    UUID.fromString("a6673528-cdd2-46bf-9c7d-08ef3c4e3f07"), 16, 16, assessment,
                    BEFORE, AFTER, List.of(new Lot("minecraft:bread", "BASE", 1, 8, 8, PAYLOAD)), NOW);
            store.confirmTaken(transfer, NOW + 1);
            assertEquals(assessment, store.taxAssessment(transfer).orElseThrow());
            assertEquals(SupplyStore.TaxState.QUOTED, store.taxState(transfer).orElseThrow());
            assertTrue(store.stock("depot").available().isEmpty());

            assertTrue(store.beginTaxFunding(transfer, NOW + 2));
            store.finishTaxFunding(transfer, false, NOW + 3);
            store.finishSettlement(transfer, "REVIEW", NOW + 4, false);
            assertTrue(store.stock("depot").available().isEmpty(),
                    "cargo remains held while StarCore tax funding is uncertain");

            store.reconcileTaxFunding(transfer, true, NOW + 5);
            store.finishSettlement(transfer, "PAID", NOW + 6, true);
            assertEquals(8L, store.stock("depot").available().get(FOOD));
        }
    }

    @Test
    void safelyAbandonsAnUnappliedInventoryTransferAcrossRestart() throws Exception {
        Path database = directory.resolve("abandon.sqlite");
        UUID id = UUID.randomUUID();
        UUID purchaseId;
        try (SupplyStore store = new SupplyStore(database)) {
            purchaseId = purchase(configured(store)).id();
            prepare(store, id, purchaseId, SELLER);
        }
        try (SupplyStore store = new SupplyStore(database)) {
            store.abandonTransfer(id);
            assertEquals(OPEN, store.purchase(purchaseId).orElseThrow().state());
            assertTrue(store.pendingInventory(SELLER).isEmpty());
            assertTrue(store.lots(id).isEmpty());
            assertTrue(store.settlement(id).isEmpty());
            prepare(store, UUID.randomUUID(), purchaseId, COURIER);
            assertEquals(COURIER, store.purchase(purchaseId).orElseThrow().seller());
        }
    }

    @Test
    void reroutesAfterRoadClosureAndRechecksPermissionsAndTravelTime() throws Exception {
        try (SupplyStore store = new SupplyStore(directory.resolve("reroute.sqlite"))) {
            Logistics logistics = configured(store);
            logistics.createWarehouse(OFFICER, "junction", JUNCTION, null);
            logistics.createRoad(OFFICER, "north", "depot", "junction", 1, null);
            logistics.createRoad(OFFICER, "east", "junction", "front", 1, null);
            Purchase purchase = bought(store, logistics);
            Shipment shipment = logistics.createShipment(OFFICER, purchase.id(), "front", REWARD, NOW);
            assertThrows(IllegalArgumentException.class, () -> logistics.accept(ENEMY_PLAYER, DEPOT, shipment.id(), NOW));
            assertThrows(IllegalArgumentException.class, () -> logistics.blockRoad(COURIER, "main", true));
            Shipment moving = logistics.accept(COURIER, DEPOT, shipment.id(), NOW);
            assertEquals("main", moving.path());
            logistics.blockRoad(OFFICER, "main", true);
            logistics.deliver(COURIER, FRONT, moving.id(), moving.readyAt());
            Shipment detour = logistics.getShipment(moving.id());
            assertEquals("north → east", detour.path());
            assertTrue(detour.readyAt() > moving.readyAt());
            assertThrows(IllegalArgumentException.class,
                    () -> logistics.deliver(COURIER, FRONT, detour.id(), detour.readyAt() - 1));
            assertTrue(store.settlement(detour.id()).isEmpty());
            logistics.deliver(COURIER, FRONT, detour.id(), detour.readyAt());
            assertEquals(TransportState.SETTLING, logistics.getShipment(detour.id()).state());
        }
    }

    private Logistics configured(SupplyStore store) throws Exception {
        Logistics logistics = logistics(store);
        logistics.createWarehouse(OFFICER, "depot", DEPOT, null);
        logistics.createWarehouse(OFFICER, "front", FRONT, ARMY);
        logistics.createRoad(OFFICER, "main", "depot", "front", 1, null);
        return logistics;
    }

    private Logistics logistics(SupplyStore store) {
        return new Logistics(store, core, 8, 10, SHIPMENT_LIFE, INTERVAL, 256, new BigDecimal("100000.00"));
    }

    private static Purchase purchase(Logistics logistics) throws Exception {
        return logistics.createPurchase(OFFICER, "depot", FOOD, 8, PURCHASE_PRICE, NOW);
    }

    private Purchase bought(SupplyStore store, Logistics logistics) throws Exception {
        Purchase purchase = purchase(logistics);
        UUID transfer = UUID.randomUUID();
        prepare(store, transfer, purchase.id(), SELLER);
        store.confirmTaken(transfer, NOW);
        settle(store, transfer, NOW);
        return store.purchase(purchase.id()).orElseThrow();
    }

    private static void prepare(SupplyStore store, UUID transfer, UUID purchase, UUID player) throws Exception {
        store.prepareTransfer(transfer, purchase, player, BEFORE, AFTER,
                List.of(new Lot("kaleidoscopecookery:mantou", "GOOD", 1.2, 8, 16, PAYLOAD)), NOW);
    }

    private void settle(SupplyStore store, UUID settlement, long now) throws Exception {
        var row = store.settlement(settlement).orElseThrow();
        var result = pay(row);
        assertTrue(store.finishSettlement(settlement, result.name(), now, false).isPresent());
    }

    private CoreGateway.PaymentResult pay(Settlement row) {
        return core.pay(row.id(), row.nationId(), row.playerId(), row.amount());
    }

    private static boolean tryPrepare(SupplyStore store, UUID transfer, UUID purchase, UUID player) throws Exception {
        try {
            prepare(store, transfer, purchase, player);
            return true;
        } catch (IllegalArgumentException alreadyReserved) {
            return false;
        }
    }

    private static boolean tryAccept(Logistics logistics, UUID carrier, UUID shipment) throws Exception {
        try {
            logistics.accept(carrier, DEPOT, shipment, NOW);
            return true;
        } catch (IllegalArgumentException alreadyClaimed) {
            return false;
        }
    }

    /** The identity ledger survives reopening the independent StarSupply database. */
    private static final class LedgerCore implements CoreGateway {
        private final Map<UUID, Payment> receipts = new HashMap<>();
        private final Map<UUID, BigDecimal> balances = new HashMap<>();
        private BigDecimal treasury = new BigDecimal("100.00");
        private boolean atWar = true;
        private boolean hostility = true;
        private Set<UUID> wartimeNations = Set.of(NATION, ENEMY);
        private boolean embargoed;
        private boolean tradePermitAvailable = true;
        private long tradeMaximumUnits = 100;
        private BigDecimal tradeUnitPrice = new BigDecimal("2.50");
        private boolean enemyRoadChunk;
        private Set<Integer> enemyRoadChunks = Set.of();
        private Position armyPosition = FRONT;
        private LogisticsModifiers logisticsModifiers = LogisticsModifiers.NONE;
        private CampaignConditions campaign = CampaignConditions.NORMAL;
        private final Map<UUID, NativeReceipt> nativeReceipts = new HashMap<>();
        private int nativeCalls;
        private int nativePoints;
        private boolean loseNativeResponse;
        private boolean rejectNativeRecipient;
        private CoreGateway.NativeSupplyReceipt nativeSupplyReceiptOverride;

        @Override public boolean replenishArmy(UUID request, UUID army, UUID nation, int points) {
            nativeCalls++;
            if (rejectNativeRecipient) throw new IllegalArgumentException("Army no longer exists");
            NativeReceipt receipt = new NativeReceipt(army, nation, points);
            NativeReceipt existing = nativeReceipts.putIfAbsent(request, receipt);
            if (existing != null && !existing.equals(receipt)) throw new IllegalArgumentException("Request mismatch");
            if (existing == null) nativePoints += points;
            if (loseNativeResponse) {
                loseNativeResponse = false;
                throw new IllegalStateException("lost authority response after durable application");
            }
            return true;
        }

        @Override public Optional<CoreGateway.NativeSupplyReceipt> nativeSupplyReceipt(UUID request) {
            if (nativeSupplyReceiptOverride != null) return Optional.of(nativeSupplyReceiptOverride);
            NativeReceipt receipt = nativeReceipts.get(request);
            return receipt == null ? Optional.empty() : Optional.of(new CoreGateway.NativeSupplyReceipt(request,
                    receipt.army(), receipt.nation(), receipt.points(), receipt.points()));
        }

        private record NativeReceipt(UUID army, UUID nation, int points) {}

        @Override public Optional<UUID> nationOf(UUID player) {
            if (player.equals(OFFICER) || player.equals(ESCORT_PLAYER)) return Optional.of(NATION);
            return player.equals(ENEMY_PLAYER) ? Optional.of(ENEMY) : Optional.empty();
        }

        @Override public boolean nationExists(UUID nation) {
            return nation.equals(NATION) || nation.equals(ENEMY);
        }

        @Override public boolean canManage(UUID actor, UUID nation) {
            return OFFICER.equals(actor) && NATION.equals(nation);
        }

        @Override public Optional<Army> army(UUID id) {
            if (ARMY.equals(id) || FORWARD_ARMY.equals(id)) {
                return Optional.of(new Army(id, NATION, 10, 100, armyPosition.world(),
                        (int) armyPosition.x(), (int) armyPosition.y(), (int) armyPosition.z()));
            }
            if (FOREIGN_ARMY.equals(id)) {
                return Optional.of(new Army(id, ENEMY, 10, 100, armyPosition.world(),
                        (int) armyPosition.x(), (int) armyPosition.y(), (int) armyPosition.z()));
            }
            return Optional.empty();
        }

        @Override public List<Army> armies(UUID nation) {
            return NATION.equals(nation)
                    ? List.of(army(ARMY).orElseThrow(), army(FORWARD_ARMY).orElseThrow()) : List.of();
        }

        @Override public boolean isAtWar(UUID nation) {
            return atWar && wartimeNations.contains(nation) && nationExists(nation);
        }

        @Override public boolean atWar(UUID first, UUID second) {
            return hostility && !first.equals(second) && isAtWar(first) && isAtWar(second);
        }

        @Override public LogisticsModifiers logisticsModifiers(UUID nation) {
            return NATION.equals(nation) ? logisticsModifiers : LogisticsModifiers.NONE;
        }

        @Override public CampaignConditions campaignConditions(dev.starsupply.api.SupplyModels.Warehouse front) {
            return campaign;
        }

        @Override public boolean tradeEmbargoed(UUID exporter, UUID importer,
                                                dev.starsupply.api.ResourceType resource) {
            return embargoed && exporter.equals(ENEMY) && importer.equals(NATION)
                    && resource == dev.starsupply.api.ResourceType.FOOD;
        }

        @Override public Optional<TradePermit> tradePermit(UUID exporter, UUID importer,
                                                           dev.starsupply.api.ResourceType resource) {
            return tradePermitAvailable && exporter.equals(ENEMY) && importer.equals(NATION)
                    && resource == dev.starsupply.api.ResourceType.FOOD && !embargoed
                    ? Optional.of(new TradePermit(UUID.fromString("1c937a5c-5b38-4d50-b1cd-121123548f17"),
                            tradeMaximumUnits, tradeUnitPrice)) : Optional.empty();
        }

        @Override public Optional<UUID> territoryOwner(String world, int chunkX, int chunkZ) {
            if (!"world".equals(world)) return Optional.empty();
            return enemyRoadChunk && chunkX == 3 || enemyRoadChunks.contains(chunkX)
                    ? Optional.of(ENEMY) : Optional.of(NATION);
        }

        @Override public Optional<Line> line(UUID supplyLineId) {
            return Optional.empty();
        }

        @Override public PaymentResult pay(UUID id, UUID nation, UUID player, BigDecimal amount) {
            assertEquals(NATION, nation);
            Payment payment = new Payment(player, amount);
            Payment existing = receipts.get(id);
            if (existing != null) {
                assertEquals(existing, payment);
                return PaymentResult.PAID;
            }
            if (treasury.compareTo(amount) < 0) return PaymentResult.REJECTED;
            treasury = treasury.subtract(amount);
            balances.merge(player, amount, BigDecimal::add);
            receipts.put(id, payment);
            return PaymentResult.PAID;
        }

        private int appliedPayments() { return receipts.size(); }

        private BigDecimal balance(UUID player) { return balances.getOrDefault(player, BigDecimal.ZERO); }

        private record Payment(UUID player, BigDecimal amount) {}
    }
}
