package dev.starsupply.storage;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Warehouse;
import dev.starsupply.api.SupplyModels.WarehouseKind;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CampaignPersistenceTest {
    @TempDir Path directory;

    @Test
    void shortRestartPreservesSiegeContinuityWhileGapLiftStaleAndClockRollbackResetIt() throws Exception {
        Path database = directory.resolve("campaign.sqlite");
        Warehouse front = new Warehouse("front", UUID.randomUUID(), WarehouseKind.FRONT,
                new Position("world", 0, 64, 0), UUID.randomUUID());
        long started = 1_000_000;
        try (SupplyStore store = new SupplyStore(database)) {
            store.addWarehouse(front);
            assertEquals(started, store.observeSiege(front, true, started, started, 60_000)
                    .orElseThrow().since());
            assertEquals(started, store.observeSiege(front, true, started + 10_000, started + 10_000, 60_000)
                    .orElseThrow().since());
        }
        try (SupplyStore store = new SupplyStore(database)) {
            assertEquals(started, store.observeSiege(front, true, started + 35_000, started + 35_000, 60_000)
                    .orElseThrow().since());
            long gap = started + 100_000;
            assertEquals(gap, store.observeSiege(front, true, gap, gap, 60_000).orElseThrow().since());
            assertTrue(store.observeSiege(front, false, gap + 1, gap + 1, 60_000).isEmpty());
            assertEquals(gap + 2, store.observeSiege(front, true, gap + 2, gap + 2, 60_000).orElseThrow().since());
            assertTrue(store.observeSiege(front, true, gap + 2, gap + 15_003, 60_000).isEmpty());
            assertEquals(gap + 20_000, store.observeSiege(front, true, gap + 20_000, gap + 20_000, 60_000)
                    .orElseThrow().since());
            assertEquals(gap + 19_000, store.observeSiege(front, true, gap + 19_000, gap + 19_000, 60_000)
                    .orElseThrow().since());
        }
    }
}
