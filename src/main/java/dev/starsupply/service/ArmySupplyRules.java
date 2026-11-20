package dev.starsupply.service;

import dev.starsupply.api.ResourceType;
import dev.starsupply.storage.SupplyStore.Consumption;
import java.util.Map;

/** The army modifier is recalculated from current stock and the latest interval. It never compounds. */
public final class ArmySupplyRules {
    public static final double MIN_MULTIPLIER = 0.85;
    public static final double MAX_MULTIPLIER = 1.05;

    private ArmySupplyRules() {}

    public static Map<ResourceType, Integer> demand(int soldiers) {
        return demand(soldiers, 0);
    }

    /** Strategic efficiency reduces troop-equivalent consumption without changing StarCore army size. */
    public static Map<ResourceType, Integer> demand(int soldiers, double supplyEfficiency) {
        if (soldiers < 1) return Map.of();
        if (!Double.isFinite(supplyEfficiency) || supplyEfficiency < 0 || supplyEfficiency > 0.5) {
            throw new IllegalArgumentException("补给效率修正须在 0–50% 内");
        }
        int effectiveSoldiers = (int) Math.max(1, Math.ceil(soldiers * (1 - supplyEfficiency)));
        return Map.of(ResourceType.FOOD, ceil(effectiveSoldiers, 10),
                ResourceType.MEDICAL, ceil(effectiveSoldiers, 40),
                ResourceType.EQUIPMENT, ceil(effectiveSoldiers, 50),
                ResourceType.MATERIALS, ceil(effectiveSoldiers, 50),
                ResourceType.ENGINEERING, ceil(effectiveSoldiers, 100),
                ResourceType.STRATEGIC, ceil(effectiveSoldiers, 200));
    }

    private static int ceil(int soldiers, int perUnit) {
        return (int) Math.min(100_000L, (soldiers + perUnit - 1L) / perUnit);
    }

    public static double coverage(Map<ResourceType, Integer> demand, Map<ResourceType, Long> available,
                                  Map<ResourceType, Consumption> consumption) {
        if (demand.isEmpty()) return 1.0;
        double coverage = 0;
        for (var entry : demand.entrySet()) {
            ResourceType type = entry.getKey();
            long supply = available.getOrDefault(type, 0L)
                    + (consumption.containsKey(type) ? consumption.get(type).units() : 0);
            double weight = switch (type) {
                case FOOD -> 0.45;
                case MEDICAL -> 0.12;
                case EQUIPMENT -> 0.13;
                case MATERIALS, ENGINEERING, STRATEGIC -> 0.10;
            };
            coverage += weight * Math.max(0, Math.min(1, (double) supply / entry.getValue()));
        }
        return Math.min(1, coverage);
    }

    public static double multiplier(boolean atWar, double coverage) {
        return multiplier(atWar, coverage, 0);
    }

    public static double multiplier(boolean atWar, double coverage, double policyReadiness) {
        if (!atWar) return 1.0;
        if (!Double.isFinite(policyReadiness) || policyReadiness < 0 || policyReadiness > 0.10) {
            throw new IllegalArgumentException("国策战备修正须在 0–10% 内");
        }
        return Math.clamp(MIN_MULTIPLIER + (MAX_MULTIPLIER - MIN_MULTIPLIER) * coverage + policyReadiness,
                MIN_MULTIPLIER, MAX_MULTIPLIER + 0.10);
    }
}
