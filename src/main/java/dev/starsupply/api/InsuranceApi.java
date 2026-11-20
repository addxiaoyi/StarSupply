package dev.starsupply.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.bukkit.entity.Player;

/** Optional freight-income cover. It never creates or reimburses the physical cargo. */
public interface InsuranceApi {
    CompletionStage<Quote> insuranceQuote(UUID shipmentId);
    CompletionStage<Quote> insuranceQuote(Player prospectiveCarrier, UUID shipmentId);
    CompletionStage<Policy> insure(Player carrier, UUID shipmentId);
    CompletionStage<Optional<Policy>> insurance(UUID shipmentId);

    enum State { INSURING, ACTIVE, CLAIM_PENDING, CLAIMED, CLOSING, CLOSED }
    enum MoneyLeg { RESERVE, PREMIUM_DEBIT, PREMIUM_CREDIT, CLAIM, RELEASE, PREMIUM_REFUND }
    enum MoneyState { PENDING, STARTED, APPLIED, REJECTED, REVIEW }
    enum MoneyResult { APPLIED, REJECTED, RETRY, REVIEW }

    record Quote(UUID shipmentId, UUID purchaseId, UUID nationId, BigDecimal freight,
                 BigDecimal claimAmount, BigDecimal premium, double maximumRisk, long quotedAt) {
        public Quote {
            Objects.requireNonNull(shipmentId); Objects.requireNonNull(purchaseId); Objects.requireNonNull(nationId);
            Objects.requireNonNull(freight); Objects.requireNonNull(claimAmount); Objects.requireNonNull(premium);
            freight = freight.setScale(2, RoundingMode.UNNECESSARY);
            claimAmount = claimAmount.setScale(2, RoundingMode.UNNECESSARY);
            premium = premium.setScale(2, RoundingMode.UNNECESSARY);
            if (freight.signum() <= 0 || claimAmount.signum() <= 0 || premium.signum() <= 0
                    || claimAmount.compareTo(freight.multiply(new BigDecimal("0.80"))) > 0
                    || !Double.isFinite(maximumRisk) || maximumRisk < 0 || maximumRisk > 1) {
                throw new IllegalArgumentException("Invalid freight insurance quote");
            }
        }
    }

    record Policy(UUID id, UUID shipmentId, UUID purchaseId, UUID nationId, UUID carrierId,
                  BigDecimal freight, BigDecimal claimAmount, BigDecimal premium, double maximumRisk,
                  State state, boolean refundPremium, long createdAt, long updatedAt, long activatedAt,
                  Map<MoneyLeg, MoneyState> money) {
        public Policy {
            Objects.requireNonNull(id); Objects.requireNonNull(carrierId); Objects.requireNonNull(state);
            Quote checked = new Quote(shipmentId, purchaseId, nationId, freight, claimAmount, premium,
                    maximumRisk, createdAt);
            freight = checked.freight(); claimAmount = checked.claimAmount(); premium = checked.premium();
            money = Map.copyOf(money);
            if (money.size() != MoneyLeg.values().length || !money.keySet().containsAll(java.util.List.of(MoneyLeg.values()))) {
                throw new IllegalArgumentException("Missing insurance money stages");
            }
            if (activatedAt < 0) throw new IllegalArgumentException("Invalid insurance activation time");
        }
        public boolean review() { return money.containsValue(MoneyState.REVIEW); }
        public boolean terminal() { return state == State.CLAIMED || state == State.CLOSED; }
        public boolean blocksTransport() { return !terminal() && (state != State.ACTIVE || review()); }
    }

    /** Every operation has a stable identity; reserve, claim and release share policyId in StarCore. */
    record MoneyOperation(UUID id, UUID policyId, UUID shipmentId, UUID purchaseId, UUID nationId,
                          UUID carrierId, MoneyLeg leg, BigDecimal amount) {
        public MoneyOperation {
            Objects.requireNonNull(id); Objects.requireNonNull(policyId); Objects.requireNonNull(shipmentId);
            Objects.requireNonNull(purchaseId); Objects.requireNonNull(nationId); Objects.requireNonNull(carrierId);
            Objects.requireNonNull(leg); Objects.requireNonNull(amount);
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() <= 0) throw new IllegalArgumentException("Invalid insurance money amount");
        }
    }
}
