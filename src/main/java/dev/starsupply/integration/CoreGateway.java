package dev.starsupply.integration;

import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import dev.starsupply.api.SupplyModels.Warehouse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Immutable views and authority operations used by StarSupply. */
public interface CoreGateway {

    Optional<UUID> nationOf(UUID playerId);

    boolean nationExists(UUID nationId);

    boolean canManage(UUID actorId, UUID nationId);

    Optional<Army> army(UUID armyId);

    List<Army> armies(UUID nationId);

    boolean isAtWar(UUID nationId);

    boolean atWar(UUID left, UUID right);

    /** Current bounded logistics bonuses sourced from StarCore's active nation strategy. */
    default LogisticsModifiers logisticsModifiers(UUID nationId) {
        return LogisticsModifiers.NONE;
    }

    /** Immutable server-thread sample. Missing/expired upstream data applies no extra demand. */
    default CampaignConditions campaignConditions(Warehouse front) {
        return CampaignConditions.NORMAL;
    }

    default TaxAssessment quoteTariff(UUID settlementId, UUID importer, UUID seller, BigDecimal gross) {
        throw new UnsupportedOperationException("StarCore transaction tax is unavailable");
    }

    default boolean fundTariff(TaxAssessment assessment) {
        throw new UnsupportedOperationException("StarCore transaction tax is unavailable");
    }

    default PaymentResult reconcileTariff(UUID settlementId, TaxAssessment assessment,
                                          boolean alreadyFunded,
                                          Predicate<TaxAssessment> fundTax) {
        return PaymentResult.REVIEW;
    }

    default PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId, BigDecimal gross,
                              TaxAssessment tax, Predicate<TaxAssessment> fundTax) {
        if (tax.taxAmount().signum() != 0) throw new UnsupportedOperationException("Tax-aware payment is unavailable");
        return pay(settlementId, nationId, playerId, gross);
    }

    /** Checks StarCore's embargo registry before cross-nation sales of mapped supplies. */
    default boolean tradeEmbargoed(UUID exporterNation, UUID importerNation, ResourceType resource) {
        return true;
    }

    /** Active bilateral agreement authorizing mapped physical supplies. */
    default Optional<TradePermit> tradePermit(UUID exporterNation, UUID importerNation, ResourceType resource) {
        return Optional.empty();
    }

    Optional<UUID> territoryOwner(String world, int chunkX, int chunkZ);

    Optional<Line> line(UUID supplyLineId);

    /** A route may use a military line only while the nation participates in its current war. */
    default boolean canUseLine(UUID nationId, UUID supplyLineId) {
        return line(supplyLineId).isPresent();
    }

    /** Uses a stable settlement ID. Call outside a local SQL transaction on the settlement worker. */
    PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId, BigDecimal amount);

    record Army(UUID id, UUID nationId, int soldiers, int supply, String world, int x, int y, int z) {}

    record Line(UUID id, UUID warId, String world, int startX, int startY, int startZ,
                int endX, int endY, int endZ, int capacity, double efficiency, boolean disrupted) {}

    record LogisticsModifiers(double supplyEfficiency, double transportSpeed, double combatReadiness) {
        public static final LogisticsModifiers NONE = new LogisticsModifiers(0, 0, 0);

        public LogisticsModifiers {
            if (!Double.isFinite(supplyEfficiency) || supplyEfficiency < 0 || supplyEfficiency > 0.5
                    || !Double.isFinite(transportSpeed) || transportSpeed < 0 || transportSpeed > 1.5
                    || !Double.isFinite(combatReadiness) || combatReadiness < 0 || combatReadiness > 0.10) {
                throw new IllegalArgumentException("Logistics modifiers are outside their safe bounds");
            }
        }
    }

    record CampaignConditions(boolean winter, boolean underSiege, long observedAt) {
        public static final long MAX_AGE_MILLIS = 15_000;
        public static final CampaignConditions NORMAL = new CampaignConditions(false, false, 0);

        public CampaignConditions {
            if (observedAt < 0) throw new IllegalArgumentException("Invalid campaign observation time");
        }

        public boolean freshAt(long now) {
            return observedAt > 0 && now >= observedAt && now - observedAt <= MAX_AGE_MILLIS;
        }
    }

    record TradePermit(UUID agreementId, long maximumUnits, BigDecimal unitPrice) {
        public TradePermit {
            Objects.requireNonNull(agreementId, "agreementId");
            Objects.requireNonNull(unitPrice, "unitPrice");
            if (maximumUnits <= 0 || unitPrice.signum() <= 0 || unitPrice.scale() > 6) {
                throw new IllegalArgumentException("Invalid StarCore trade agreement terms");
            }
        }
    }

    enum PaymentResult {
        PAID,
        REJECTED,
        RETRY,
        REVIEW
    }
}
