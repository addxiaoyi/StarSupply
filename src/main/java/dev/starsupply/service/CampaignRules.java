package dev.starsupply.service;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.CampaignStatus;
import dev.starsupply.integration.CoreGateway.CampaignConditions;
import dev.starsupply.storage.SupplyStore.SiegeContinuity;
import java.util.EnumMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;

/** Temporary campaign pressure only changes current supply demand; it never mutates army morale. */
public final class CampaignRules {
    private CampaignRules() {}

    public record Config(double winterFoodExtra, double winterMedicalExtra, double siegeMaximumExtra,
                         long siegeRampMillis, long continuityGapMillis) {
        public static final Config DEFAULT = new Config(0.25, 0.10, 0.50, 30 * 60_000L, 60_000);

        public Config {
            bounded(winterFoodExtra, "winter food");
            bounded(winterMedicalExtra, "winter medical");
            bounded(siegeMaximumExtra, "siege");
            if (siegeRampMillis < 60_000 || siegeRampMillis > 24 * 60 * 60_000L
                    || continuityGapMillis < CampaignConditions.MAX_AGE_MILLIS
                    || continuityGapMillis > 5 * 60_000L) {
                throw new IllegalArgumentException("Invalid siege campaign timing bounds");
            }
        }

        private static void bounded(double value, String label) {
            if (!Double.isFinite(value) || value < 0 || value > 0.50) {
                throw new IllegalArgumentException(label + " extra demand must be between 0 and 50%");
            }
        }
    }

    public static CampaignStatus status(CampaignConditions conditions, SiegeContinuity siege,
                                        boolean atWar, long now, Config config) {
        Objects.requireNonNull(conditions);
        Objects.requireNonNull(config);
        if (!atWar || !conditions.freshAt(now)) return CampaignStatus.NORMAL;
        boolean activeSiege = conditions.underSiege() && siege != null && siege.since() > 0;
        long elapsed = activeSiege ? Math.max(0, now - siege.since()) : 0;
        double pressure = activeSiege ? BigDecimal.valueOf(config.siegeMaximumExtra())
                .multiply(BigDecimal.valueOf(Math.min(elapsed, config.siegeRampMillis())))
                .divide(BigDecimal.valueOf(config.siegeRampMillis()), 12, RoundingMode.HALF_UP).doubleValue() : 0;
        return new CampaignStatus(conditions.winter(), activeSiege, activeSiege ? siege.since() : 0,
                elapsed / 1000, pressure, conditions.observedAt());
    }

    /** Additive and bounded: even simultaneous winter/siege cannot exceed twice base food demand. */
    public static Map<ResourceType, Integer> demand(Map<ResourceType, Integer> base, CampaignStatus status,
                                                  Config config) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(status);
        Objects.requireNonNull(config);
        Map<ResourceType, Integer> result = new EnumMap<>(ResourceType.class);
        base.forEach((resource, value) -> {
            if (value == null || value < 1 || value > 100_000) {
                throw new IllegalArgumentException("Invalid base campaign demand");
            }
            double winterExtra = status.winter() ? switch (resource) {
                case FOOD -> config.winterFoodExtra();
                case MEDICAL -> config.winterMedicalExtra();
                default -> 0;
            } : 0;
            double siegeExtra = switch (resource) {
                case FOOD, MATERIALS, ENGINEERING -> status.siegeExtraRatio();
                default -> 0;
            };
            int needed = BigDecimal.valueOf(value).multiply(BigDecimal.ONE.add(BigDecimal.valueOf(winterExtra))
                            .add(BigDecimal.valueOf(siegeExtra)))
                    .setScale(0, RoundingMode.CEILING).min(BigDecimal.valueOf(100_000)).intValueExact();
            result.put(resource, needed);
        });
        return Map.copyOf(result);
    }
}
