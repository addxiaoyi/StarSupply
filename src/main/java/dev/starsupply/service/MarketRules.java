package dev.starsupply.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Depot-local, bounded physical-item quotes derived from persisted stock and purchase demand. */
public final class MarketRules {
    private static final BigDecimal MIN_FACTOR = new BigDecimal("0.50");
    private static final BigDecimal MAX_FACTOR = new BigDecimal("2.00");

    private MarketRules() {}

    public static BigDecimal quote(BigDecimal basePerItem, long stockItems, long demandItems, long bufferItems) {
        Objects.requireNonNull(basePerItem, "basePerItem");
        if (basePerItem.signum() <= 0 || basePerItem.scale() > 2 || stockItems < 0 || demandItems < 0
                || bufferItems < 1) {
            throw new IllegalArgumentException("Invalid depot market inputs");
        }
        BigDecimal numerator = BigDecimal.valueOf(demandItems).subtract(BigDecimal.valueOf(stockItems));
        BigDecimal denominator = BigDecimal.valueOf(demandItems).add(BigDecimal.valueOf(stockItems))
                .add(BigDecimal.valueOf(bufferItems));
        BigDecimal factor = BigDecimal.ONE.add(numerator.divide(denominator, 8, RoundingMode.HALF_UP));
        factor = factor.max(MIN_FACTOR).min(MAX_FACTOR);
        return basePerItem.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }
}
