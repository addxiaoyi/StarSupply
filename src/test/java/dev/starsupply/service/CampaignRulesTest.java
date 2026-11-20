package dev.starsupply.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.CampaignStatus;
import dev.starsupply.integration.CoreGateway.CampaignConditions;
import dev.starsupply.storage.SupplyStore.SiegeContinuity;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CampaignRulesTest {
    private static final long NOW = 100_000_000;
    private static final CampaignRules.Config CONFIG = CampaignRules.Config.DEFAULT;

    @Test
    void winterAndContinuousSiegeAddBoundedCurrentDemandWithoutCompounding() {
        var conditions = new CampaignConditions(true, true, NOW);
        var status = CampaignRules.status(conditions, new SiegeContinuity(NOW - 15 * 60_000, NOW),
                true, NOW, CONFIG);
        assertEquals(0.25, status.siegeExtraRatio());
        assertEquals(900, status.siegeSeconds());
        Map<ResourceType, Integer> base = Map.of(ResourceType.FOOD, 100, ResourceType.MEDICAL, 100,
                ResourceType.MATERIALS, 100, ResourceType.ENGINEERING, 100,
                ResourceType.EQUIPMENT, 100, ResourceType.STRATEGIC, 100);
        var demand = CampaignRules.demand(base, status, CONFIG);
        assertEquals(150, demand.get(ResourceType.FOOD));
        assertEquals(110, demand.get(ResourceType.MEDICAL), "decimal rounding must not create a spurious unit");
        assertEquals(125, demand.get(ResourceType.ENGINEERING));
        assertEquals(125, demand.get(ResourceType.MATERIALS));
        assertEquals(100, demand.get(ResourceType.EQUIPMENT));
        assertEquals(demand, CampaignRules.demand(base, status, CONFIG));
        var capped = CampaignRules.status(conditions, new SiegeContinuity(NOW - 3 * 60 * 60_000, NOW),
                true, NOW, CONFIG);
        assertEquals(0.5, capped.siegeExtraRatio());
        assertEquals(175, CampaignRules.demand(base, capped, CONFIG).get(ResourceType.FOOD));
        assertEquals(100_000, CampaignRules.demand(Map.of(ResourceType.FOOD, 100_000), capped, CONFIG)
                .get(ResourceType.FOOD));
        var decimalConfig = new CampaignRules.Config(0.1, 0.1, 0.5, 30 * 60_000L, 60_000);
        var decimalStatus = new CampaignStatus(true, true, NOW - 720_000, 720, 0.2, NOW);
        assertEquals(130, CampaignRules.demand(base, decimalStatus, decimalConfig).get(ResourceType.FOOD));
    }

    @Test
    void ceasefireStaleObservationsAndInvalidConfigurationCannotLeavePermanentPressure() {
        var conditions = new CampaignConditions(true, true, NOW);
        var siege = new SiegeContinuity(NOW - 60_000, NOW);
        assertEquals(CampaignStatus.NORMAL, CampaignRules.status(conditions, siege, false, NOW, CONFIG));
        assertEquals(CampaignStatus.NORMAL, CampaignRules.status(conditions, siege, true, NOW + 15_001, CONFIG));
        assertEquals(CampaignStatus.NORMAL, CampaignRules.status(conditions, siege, true, NOW - 1, CONFIG));
        var lifted = CampaignRules.status(new CampaignConditions(true, false, NOW), siege, true, NOW, CONFIG);
        assertTrue(lifted.winter());
        assertFalse(lifted.underSiege());
        assertEquals(0, lifted.siegeExtraRatio());
        assertThrows(IllegalArgumentException.class, () -> new CampaignRules.Config(0.51, 0.1, 0.5, 60_000, 60_000));
        assertThrows(IllegalArgumentException.class, () -> new CampaignRules.Config(0.25, Double.NaN, 0.5, 60_000, 60_000));
        assertThrows(IllegalArgumentException.class, () -> new CampaignRules.Config(0.25, 0.1, 0.5, 1, 60_000));
    }
}
