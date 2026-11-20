package dev.starsupply.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.starsupply.api.ResourceType;
import dev.starsupply.storage.SupplyStore.Consumption;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArmySupplyRulesTest {
    @Test
    void allSixLogisticsCategoriesHaveDemandAndBoundedCombatEffects() {
        var demand = ArmySupplyRules.demand(10);
        assertEquals(6, demand.size());
        for (ResourceType resource : ResourceType.values()) assertEquals(1, demand.get(resource));
        assertEquals(ArmySupplyRules.MIN_MULTIPLIER, ArmySupplyRules.multiplier(true, 0));
        assertEquals(ArmySupplyRules.MAX_MULTIPLIER, ArmySupplyRules.multiplier(true, 1));
        assertEquals(1.13, ArmySupplyRules.multiplier(true, 1, 0.08), 0.000001);
        assertEquals(1.0, ArmySupplyRules.multiplier(false, 0, 0.08), 0.0);
        assertTrue(ArmySupplyRules.coverage(demand, Map.of(
                ResourceType.FOOD, 1L, ResourceType.MEDICAL, 1L, ResourceType.EQUIPMENT, 1L,
                ResourceType.MATERIALS, 1L, ResourceType.ENGINEERING, 1L, ResourceType.STRATEGIC, 1L), Map.of())
                >= 0.999999);
        assertEquals(0.0, ArmySupplyRules.coverage(demand, Map.of(), Map.of()), 0.0,
                "empty supply should imply no coverage when active demands exist");
    }

    @Test
    void nationalSupplyEfficiencyReducesTroopEquivalentDemandWithinBounds() {
        var standard = ArmySupplyRules.demand(1_000);
        var efficient = ArmySupplyRules.demand(1_000, 0.20);
        assertEquals(standard.get(ResourceType.FOOD), 100);
        assertEquals(efficient.get(ResourceType.FOOD), 80);
        assertTrue(efficient.get(ResourceType.FOOD) < standard.get(ResourceType.FOOD));
        assertEquals(50, ArmySupplyRules.demand(1_000, 0.50).get(ResourceType.FOOD));
        assertThrows(IllegalArgumentException.class, () -> ArmySupplyRules.demand(1_000, 0.51));
        assertThrows(IllegalArgumentException.class, () -> ArmySupplyRules.multiplier(true, 1, 0.11));
    }
}
