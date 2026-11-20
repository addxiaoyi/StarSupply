package dev.starsupply.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Purchase;
import dev.starsupply.api.SupplyModels.Shipment;
import dev.starsupply.api.TavernModels.Hub;
import dev.starsupply.api.TavernModels.ScoutReport;
import dev.starsupply.integration.CoreGateway;
import dev.starsupply.storage.SupplyStore;
import dev.starsupply.storage.SupplyStore.Lot;
import dev.starsupply.storage.TavernStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TavernServiceTest {
    private static final UUID NATION = UUID.randomUUID();
    private static final UUID ENEMY = UUID.randomUUID();
    private static final UUID LEADER = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID ENEMY_LEADER = UUID.randomUUID();
    private static final UUID ENEMY_MEMBER = UUID.randomUUID();
    private static final UUID ARMY = UUID.randomUUID();
    private static final UUID OUTSIDER = UUID.randomUUID();
    private static final Position HOME = new Position("world", 0.25, 64.5, 0.75);
    private static final Position ENEMY_HOME = new Position("world", 1000, 64, 0);
    private static final Position OBSERVATION = new Position("world", 1050, 64, 10);
    private static final long NOW = 10_000_000;
    @TempDir Path directory;

    @Test
    void onlyAuthorizedNationManagersRegisterOwnedExactLocationsAndLimitsApply() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TavernService service = fixture.service;
            assertThrows(IllegalArgumentException.class, () -> service.registerHub(OUTSIDER, HOME, "none", false, NOW));
            assertThrows(IllegalArgumentException.class, () -> service.registerHub(MEMBER, HOME, "none", false, NOW));
            assertThrows(IllegalArgumentException.class, () -> service.registerHub(LEADER, ENEMY_HOME, "none", false, NOW));
            service.configure(32, 8, 900_000, true);
            assertThrows(IllegalArgumentException.class, () -> service.registerHub(LEADER, HOME, "inn", false, NOW));
            Hub hub = service.registerHub(LEADER, HOME, "inn", true, NOW);
            assertEquals(HOME, hub.position());
            assertTrue(hub.tavernVerified());
            assertEquals(LEADER, hub.registeredBy());
            assertThrows(IllegalArgumentException.class, () -> service.registerHub(LEADER, HOME, "inn", true, NOW));
            assertThrows(IllegalArgumentException.class, () -> service.removeHub(MEMBER, hub.id()));
            assertThrows(IllegalArgumentException.class, () -> service.removeHub(ENEMY_LEADER, hub.id()));
            for (int i = 1; i < 16; i++) service.registerHub(LEADER, HOME, "inn" + i, true, NOW);
            assertEquals(16, service.hubs(MEMBER).size());
            assertThrows(IllegalArgumentException.class, () -> service.registerHub(LEADER, HOME, "overflow", true, NOW));
            service.removeHub(LEADER, "inn");
            assertEquals(15, service.hubs(MEMBER).size());
            assertTrue(service.hubs(ENEMY_MEMBER).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.configure(33, 8, 900_000, false));
            assertThrows(IllegalArgumentException.class, () -> service.configure(32, 17, 900_000, false));
            assertThrows(IllegalArgumentException.class, () -> service.configure(32, 8, 900_001, false));
        }
    }

    @Test
    void reconnaissanceRequiresPhysicalObservationAndCapturesAuthoritativeImmutableRoute() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TavernService service = fixture.service;
            assertThrows(IllegalArgumentException.class, () -> service.scoutRoute(MEMBER, HOME, "enemy-road", NOW));
            assertThrows(IllegalArgumentException.class, () -> service.scoutRoute(MEMBER,
                    new Position("elsewhere", 1050, 64, 10), "enemy-road", NOW));
            assertThrows(IllegalArgumentException.class, () -> service.scoutRoute(MEMBER,
                    new Position("world", 1050, 64, 33), "enemy-road", NOW));
            assertThrows(IllegalArgumentException.class, () -> service.scoutRoute(OUTSIDER, OBSERVATION, "enemy-road", NOW));
            var edge = fixture.logistics.graph(ENEMY).edges().getFirst();
            ScoutReport report = service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW);
            assertEquals(NATION, report.nationId());
            assertEquals(ENEMY, report.roadNationId());
            assertEquals(MEMBER, report.scoutId());
            assertEquals(OBSERVATION, report.observedFrom());
            assertEquals(edge.blocked(), report.blocked());
            assertEquals(edge.roadEfficiency(), report.roadEfficiency());
            assertEquals(edge.hostileRisk(), report.hostileRisk());
            assertEquals(edge.distance(), report.distance());
            assertTrue(report.hostileRisk() > 0);
            assertEquals(NOW + 900_000, report.expiresAt());
            fixture.logistics.blockRoad(ENEMY_LEADER, "enemy-road", true);
            ScoutReport changed = service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW + 1);
            assertTrue(changed.blocked());
            assertFalse(report.blocked(), "old reconnaissance must remain an observation rather than a live pointer");
            assertTrue(TavernService.nearSegment(new Position("world", 1132, 64, 0), ENEMY_HOME,
                    new Position("world", 1100, 64, 0), 32));
            assertFalse(TavernService.nearSegment(new Position("world", 1133, 64, 0), ENEMY_HOME,
                    new Position("world", 1100, 64, 0), 32));
        }
    }

    @Test
    void publishedBoardsEnforceNationPresenceAndTerritoryAndContainOnlyOpenContracts() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TavernService service = fixture.service;
            service.registerHub(LEADER, HOME, "inn", false, NOW);
            service.registerHub(ENEMY_LEADER, ENEMY_HOME, "enemy-inn", false, NOW);
            ScoutReport friendly = service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW);
            ScoutReport hostile = service.scoutRoute(ENEMY_MEMBER, OBSERVATION, "enemy-road", NOW);
            assertThrows(IllegalArgumentException.class, () -> service.publishIntelligence(MEMBER, OBSERVATION,
                    friendly.id(), "inn", NOW + 1));
            assertThrows(IllegalArgumentException.class, () -> service.publishIntelligence(ENEMY_MEMBER, HOME,
                    friendly.id(), "inn", NOW + 1));
            assertThrows(IllegalArgumentException.class, () -> service.publishIntelligence(MEMBER, HOME,
                    hostile.id(), "inn", NOW + 1));
            service.publishIntelligence(MEMBER, HOME, friendly.id(), "inn", NOW + 1);
            service.publishIntelligence(ENEMY_MEMBER, ENEMY_HOME, hostile.id(), "enemy-inn", NOW + 1);

            Purchase open = fixture.logistics.createPurchase(LEADER, "depot", ResourceType.FOOD, 8,
                    new BigDecimal("40.00"), NOW);
            Purchase cancelled = fixture.logistics.createPurchase(LEADER, "depot", ResourceType.FOOD, 8,
                    new BigDecimal("40.00"), NOW);
            fixture.logistics.cancelPurchase(LEADER, cancelled.id());
            Purchase bought = fixture.logistics.createPurchase(LEADER, "depot", ResourceType.FOOD, 8,
                    new BigDecimal("40.00"), NOW);
            UUID transfer = UUID.randomUUID();
            fixture.supply.prepareTransfer(transfer, bought.id(), MEMBER, "a".repeat(64), "b".repeat(64),
                    List.of(new Lot("bread", "NORMAL", 1, 8, 8, new byte[] {1, 2, 3})), NOW);
            fixture.supply.confirmTaken(transfer, NOW);
            fixture.supply.finishSettlement(transfer, "PAID", NOW, false);
            Shipment transport = fixture.logistics.createShipment(LEADER, bought.id(), "front", new BigDecimal("5.00"), NOW);

            var board = service.board(MEMBER, HOME, "inn", NOW + 2);
            assertEquals(List.of(friendly.id()), board.reports().stream().map(ScoutReport::id).toList());
            assertEquals(List.of(open.id()), board.purchases().stream().map(Purchase::id).toList());
            assertEquals(List.of(transport.id()), board.shipments().stream().map(Shipment::id).toList());
            assertEquals(List.of(hostile.id()), service.board(ENEMY_MEMBER, ENEMY_HOME, "enemy-inn", NOW + 2)
                    .reports().stream().map(ScoutReport::id).toList());
            assertThrows(IllegalArgumentException.class, () -> service.board(MEMBER, OBSERVATION, "inn", NOW + 2));
            assertThrows(IllegalArgumentException.class, () -> service.board(ENEMY_MEMBER, HOME, "inn", NOW + 2));
            fixture.core.homeConquered = true;
            assertThrows(IllegalArgumentException.class, () -> service.board(MEMBER, HOME, "inn", NOW + 2));
        }
    }

    @Test
    void restartDuplicatePublicationAndHubDeletionNeverExtendReportLifetime() throws Exception {
        UUID reportId;
        long expiry;
        try (Fixture fixture = new Fixture()) {
            TavernService service = fixture.service;
            service.registerHub(LEADER, HOME, "inn", false, NOW);
            service.registerHub(LEADER, HOME, "second-inn", false, NOW);
            ScoutReport report = service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW);
            reportId = report.id();
            expiry = report.expiresAt();
            ScoutReport first = service.publishIntelligence(MEMBER, HOME, report.id(), "inn", NOW + 5);
            assertEquals(first, service.publishIntelligence(MEMBER, HOME, report.id(), "inn", NOW + 10));
            assertThrows(IllegalArgumentException.class, () -> service.publishIntelligence(MEMBER, HOME,
                    report.id(), "second-inn", NOW + 10));
        }
        try (Fixture fixture = new Fixture()) {
            TavernService service = fixture.service;
            var restored = service.board(MEMBER, HOME, "inn", NOW + 20).reports().getFirst();
            assertEquals(reportId, restored.id());
            assertEquals(NOW + 5, restored.publishedAt());
            service.removeHub(LEADER, "inn");
            ScoutReport republished = service.publishIntelligence(MEMBER, HOME, reportId, "second-inn", NOW + 25);
            assertEquals(expiry, republished.expiresAt());
            assertEquals(NOW, republished.observedAt());
            assertTrue(service.board(MEMBER, HOME, "second-inn", expiry).reports().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.publishIntelligence(MEMBER, HOME,
                    reportId, "second-inn", expiry));
        }
    }

    @Test
    void activeReportCapExpiresNaturallyAndCannotBeResetByPublication() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TavernService service = fixture.service;
            service.configure(32, 8, 60_000, false);
            service.registerHub(LEADER, HOME, "inn", false, NOW);
            ScoutReport first = null;
            for (int i = 0; i < 128; i++) {
                ScoutReport report = service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW);
                if (first == null) first = report;
            }
            service.publishIntelligence(MEMBER, HOME, first.id(), "inn", NOW + 30_000);
            assertThrows(IllegalArgumentException.class, () -> service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW + 30_001));
            assertTrue(service.board(MEMBER, HOME, "inn", NOW + 60_000).reports().isEmpty());
            assertNotNull(service.scoutRoute(MEMBER, OBSERVATION, "enemy-road", NOW + 60_000));
        }
    }

    private final class Fixture implements AutoCloseable {
        final FakeCore core = new FakeCore();
        final SupplyStore supply;
        final TavernStore taverns;
        final Logistics logistics;
        final TavernService service;

        Fixture() throws Exception {
            supply = new SupplyStore(directory.resolve("supply.sqlite"));
            taverns = new TavernStore(directory.resolve("tavern.sqlite"));
            logistics = new Logistics(supply, core, 8, 10, 60_000, 60_000, 256, new BigDecimal("100000.00"));
            if (supply.warehouse("depot").isEmpty()) {
                logistics.createWarehouse(LEADER, "depot", HOME, null);
                logistics.createWarehouse(LEADER, "front", new Position("world", 100, 64, 0), ARMY);
                logistics.createRoad(LEADER, "home-road", "depot", "front", 1, null);
                logistics.createWarehouse(ENEMY_LEADER, "enemy-depot", ENEMY_HOME, null);
                logistics.createWarehouse(ENEMY_LEADER, "enemy-target", new Position("world", 1100, 64, 0), null);
                logistics.createRoad(ENEMY_LEADER, "enemy-road", "enemy-depot", "enemy-target", 0.75, null);
            }
            service = new TavernService(taverns, supply, logistics, core);
        }

        @Override public void close() throws Exception {
            taverns.close();
            supply.close();
        }
    }

    private static final class FakeCore implements CoreGateway {
        boolean homeConquered;
        @Override public Optional<UUID> nationOf(UUID player) {
            if (player.equals(LEADER) || player.equals(MEMBER)) return Optional.of(NATION);
            if (player.equals(ENEMY_LEADER) || player.equals(ENEMY_MEMBER)) return Optional.of(ENEMY);
            return Optional.empty();
        }
        @Override public boolean nationExists(UUID nation) { return nation.equals(NATION) || nation.equals(ENEMY); }
        @Override public boolean canManage(UUID actor, UUID nation) {
            return actor.equals(LEADER) && nation.equals(NATION) || actor.equals(ENEMY_LEADER) && nation.equals(ENEMY);
        }
        @Override public Optional<Army> army(UUID army) {
            return army.equals(ARMY) ? Optional.of(new Army(ARMY, NATION, 10, 100, "world", 100, 64, 0)) : Optional.empty();
        }
        @Override public List<Army> armies(UUID nation) { return nation.equals(NATION) ? List.of(army(ARMY).orElseThrow()) : List.of(); }
        @Override public boolean isAtWar(UUID nation) { return true; }
        @Override public boolean atWar(UUID left, UUID right) { return !left.equals(right); }
        @Override public Optional<UUID> territoryOwner(String world, int x, int z) {
            if (homeConquered && x == 0 && z == 0) return Optional.of(ENEMY);
            return Optional.of(x == 65 ? NATION : x >= 62 ? ENEMY : NATION);
        }
        @Override public Optional<Line> line(UUID id) { return Optional.empty(); }
        @Override public PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId, BigDecimal amount) {
            return PaymentResult.PAID;
        }
    }
}
