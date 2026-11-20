package dev.starsupply.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MarketRulesTest {
    @Test
    void depotPriceRisesWithDemandAndFallsWithRealStock() {
        BigDecimal base = new BigDecimal("2.50");
        BigDecimal quiet = MarketRules.quote(base, 0, 0, 16);
        BigDecimal shortage = MarketRules.quote(base, 0, 8, 16);
        BigDecimal surplus = MarketRules.quote(base, 32, 0, 16);

        assertEquals(new BigDecimal("2.50"), quiet);
        assertEquals(new BigDecimal("3.33"), shortage);
        assertEquals(new BigDecimal("1.25"), surplus);
    }

    @Test
    void marketQuoteIsBoundedAndRejectsInvalidInputs() {
        BigDecimal base = new BigDecimal("10.00");
        assertEquals(new BigDecimal("20.00"), MarketRules.quote(base, 0, 10_000, 1));
        assertEquals(new BigDecimal("5.00"), MarketRules.quote(base, 10_000, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> MarketRules.quote(BigDecimal.ZERO, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> MarketRules.quote(base, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> MarketRules.quote(base, 0, 0, 0));
    }
}
