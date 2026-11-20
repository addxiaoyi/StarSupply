package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsupply.api.SupplyModels.Position;
import dev.starsupply.api.SupplyModels.Warehouse;
import dev.starsupply.api.SupplyModels.WarehouseKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CampaignSiegeTargetTest {
    @Test
    void siegeInfluenceUsesTargetsNationWorldAndBoundedDistance() {
        UUID defender = UUID.randomUUID();
        Warehouse front = new Warehouse("front", defender, WarehouseKind.FRONT,
                new Position("world", 100, 64, 0), UUID.randomUUID());
        assertTrue(new CampaignSiegeTarget(defender, new Position("world", 0, 64, 0)).affects(front, 128));
        assertFalse(new CampaignSiegeTarget(UUID.randomUUID(), new Position("world", 100, 64, 0))
                .affects(front, 128));
        assertFalse(new CampaignSiegeTarget(defender, new Position("other", 100, 64, 0)).affects(front, 128));
        assertFalse(new CampaignSiegeTarget(defender, new Position("world", 0, 64, 0)).affects(front, 64));
    }
}
