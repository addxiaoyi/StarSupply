package dev.starsupply.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.foundation.economy.InternalEconomyService;
import dev.starcore.starcore.foundation.economy.transaction.*;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TreasuryService;
import dev.starsupply.api.InsuranceApi.*;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState.*;

/** Premium debit/credit evidence is conservative; existing receipt-backed payments hold claim funds. */
public final class CoreInsurancePayment implements InsuranceEconomy {
    private final CorePayment payments;
    private final TransactionFabric ledger;
    private final BiPredicate<UUID, BigDecimal> hasBalance;
    private final BiPredicate<UUID, BigDecimal> withdrawPremium;
    private final BiPredicate<UUID, BigDecimal> depositPremium;
    private final BiPredicate<UUID, BigDecimal> refundPremium;
    private final Logger logger;

    public CoreInsurancePayment(CorePayment payments, StarCoreApi api, Logger logger) {
        this.payments = Objects.requireNonNull(payments);
        this.logger = Objects.requireNonNull(logger);
        this.ledger = api.service(TransactionFabric.class).orElseThrow();
        EconomyService provider = api.service(EconomyService.class).orElseThrow();
        if (!(provider instanceof InternalEconomyService internal)) {
            throw new IllegalStateException("Freight insurance requires StarCore internal economy");
        }
        TreasuryService treasury = api.service(TreasuryService.class).orElseThrow();
        this.hasBalance = internal::has;
        this.withdrawPremium = (id, amount) -> internal.withdraw(id, amount, true, true);
        this.depositPremium = (id, amount) -> treasury.tryDeposit(NationId.of(id), amount);
        this.refundPremium = (id, amount) -> internal.deposit(id, amount, true, true,
                "starsupply:insurance-premium-refund");
    }

    CoreInsurancePayment(CorePayment payments, TransactionFabric ledger,
                         BiPredicate<UUID, BigDecimal> hasBalance,
                         BiPredicate<UUID, BigDecimal> withdrawPremium,
                         BiPredicate<UUID, BigDecimal> depositPremium,
                         BiPredicate<UUID, BigDecimal> refundPremium, Logger logger) {
        this.payments = Objects.requireNonNull(payments);
        this.ledger = Objects.requireNonNull(ledger);
        this.hasBalance = Objects.requireNonNull(hasBalance);
        this.withdrawPremium = Objects.requireNonNull(withdrawPremium);
        this.depositPremium = Objects.requireNonNull(depositPremium);
        this.refundPremium = Objects.requireNonNull(refundPremium);
        this.logger = Objects.requireNonNull(logger);
    }

    @Override public synchronized MoneyResult execute(MoneyOperation op) {
        try {
            return switch (op.leg()) {
                case RESERVE -> paymentResult(payments.reserve(op.policyId(), op.nationId(), op.carrierId(), op.amount()));
                case CLAIM -> paymentResult(payments.pay(op.policyId(), op.nationId(), op.carrierId(), op.amount()));
                case RELEASE -> switch (payments.release(op.policyId())) {
                    case REJECTED -> MoneyResult.APPLIED; // Receipt-backed refund is confirmed.
                    case RETRY -> MoneyResult.RETRY;
                    default -> MoneyResult.REVIEW;
                };
                case PREMIUM_DEBIT -> premium(op, () -> withdrawPremium.test(op.carrierId(), op.amount()));
                case PREMIUM_CREDIT -> {
                    requireSibling(op, MoneyLeg.PREMIUM_DEBIT, COMMITTED);
                    yield premium(op, () -> depositPremium.test(op.nationId(), op.amount()));
                }
                case PREMIUM_REFUND -> {
                    requireSibling(op, MoneyLeg.PREMIUM_DEBIT, COMMITTED);
                    requireSibling(op, MoneyLeg.PREMIUM_CREDIT, CANCELLED);
                    // This restores a confirmed withdrawal that never reached the treasury.
                    yield premium(op, () -> refundPremium.test(op.carrierId(), op.amount()));
                }
            };
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "Insurance money needs review: " + op.id() + " " + op.leg(), failure);
            return MoneyResult.REVIEW;
        }
    }

    private MoneyResult premium(MoneyOperation op, BooleanSupplier action) {
        TransactionRecord record = ledger.begin(request(op));
        if (record.state() == COMMITTED) return MoneyResult.APPLIED;
        if (record.state() == CANCELLED) return MoneyResult.REJECTED;
        if (record.state() == RESERVED || record.state() == MANUAL_REVIEW) return MoneyResult.REVIEW;
        if (record.state() == DEBIT_FAILED) {
            if (op.leg() != MoneyLeg.PREMIUM_REFUND) {
                ledger.transition(record.transactionId(), CANCELLED, "confirmed unapplied premium leg closed");
                return MoneyResult.REJECTED;
            }
            record = ledger.transition(record.transactionId(), CREATED, "resume confirmed unapplied premium refund");
        }
        if (record.state() == CREATED) {
            if (op.leg() == MoneyLeg.PREMIUM_DEBIT && !hasBalance.test(op.carrierId(), op.amount())) {
                record = ledger.transition(record.transactionId(), DEBIT_FAILED, "insurance premium balance insufficient before call");
                ledger.transition(record.transactionId(), CANCELLED, "insurance premium was not attempted");
                return MoneyResult.REJECTED;
            }
            record = ledger.transition(record.transactionId(), RESERVED, "insurance money call started; no replayable receipt");
            if (!action.getAsBoolean()) return MoneyResult.REVIEW;
            record = ledger.transition(record.transactionId(), DEBITED, "insurance money call confirmed");
        }
        return commit(record);
    }

    private MoneyResult commit(TransactionRecord record) {
        if (record.state() == DEBITED) record = ledger.transition(record.transactionId(), DELIVERED,
                "insurance money confirmed");
        if (record.state() == DELIVERED) record = ledger.transition(record.transactionId(), COMMITTED,
                "insurance money committed");
        return record.state() == COMMITTED ? MoneyResult.APPLIED : MoneyResult.REVIEW;
    }

    @Override public synchronized MoneyResult reconcile(MoneyOperation op, boolean applied) {
        try {
            if (op.leg() == MoneyLeg.RESERVE || op.leg() == MoneyLeg.RELEASE) {
                // These legs have authoritative idempotent spending receipts. Re-read those receipts
                // through the normal boundary; an operator flag cannot manufacture a reserve/refund.
                return execute(op);
            }
            if (op.leg() == MoneyLeg.CLAIM) {
                return paymentResult(applied ? payments.reconcile(op.policyId(), true)
                        : payments.retryUnappliedCredit(op.policyId()));
            }
            if (op.leg() != MoneyLeg.PREMIUM_DEBIT && op.leg() != MoneyLeg.PREMIUM_CREDIT
                    && op.leg() != MoneyLeg.PREMIUM_REFUND) return MoneyResult.REVIEW;
            TransactionRecord record = ledger.findByIdempotencyKey(key(op)).orElseThrow();
            // begin validates the original amount, account and immutable policy metadata.
            ledger.begin(request(op));
            if (record.state() == COMMITTED) return MoneyResult.APPLIED;
            if (record.state() == CANCELLED) return MoneyResult.REJECTED;
            if (record.state() == DEBITED || record.state() == DELIVERED) return commit(record);
            if (record.state() == DEBIT_FAILED) {
                if (op.leg() == MoneyLeg.PREMIUM_REFUND) {
                    ledger.transition(record.transactionId(), CREATED, "resume confirmed unapplied premium refund");
                    return MoneyResult.RETRY;
                }
                ledger.transition(record.transactionId(), CANCELLED, "confirmed unapplied premium leg closed");
                return MoneyResult.REJECTED;
            }
            if (record.state() != RESERVED) return MoneyResult.REVIEW;
            if (applied) return commit(ledger.transition(record.transactionId(), DEBITED,
                    "insurance money confirmed applied by operator"));
            record = ledger.transition(record.transactionId(), DEBIT_FAILED,
                    "operator confirmed insurance money was not applied");
            if (op.leg() == MoneyLeg.PREMIUM_REFUND) {
                ledger.transition(record.transactionId(), CREATED, "retry confirmed unapplied premium refund");
                return MoneyResult.RETRY;
            }
            ledger.transition(record.transactionId(), CANCELLED, "insurance premium leg cancelled after review");
            return MoneyResult.REJECTED;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "Cannot reconcile insurance money " + op.id(), unavailable);
            return MoneyResult.REVIEW;
        }
    }

    private void requireSibling(MoneyOperation op, MoneyLeg sibling, EconomyTransactionState state) {
        String siblingKey = "starsupply:insurance:" + op.policyId() + ":" + sibling;
        TransactionRecord record = ledger.findByIdempotencyKey(siblingKey).orElseThrow();
        if (record.state() != state || record.amount().compareTo(op.amount()) != 0
                || !op.policyId().toString().equals(record.metadata().get("policy"))
                || !op.nationId().toString().equals(record.metadata().get("nation"))
                || !op.carrierId().toString().equals(record.metadata().get("carrier"))) {
            throw new IllegalStateException("Insurance premium evidence mismatch");
        }
    }

    private static TransactionRequest request(MoneyOperation op) {
        return new TransactionRequest(op.leg() == MoneyLeg.PREMIUM_CREDIT ? op.nationId() : op.carrierId(),
                op.amount(), RefundOwner.WAR, TransactionType.OTHER, "StarSupply freight insurance " + op.leg(), key(op),
                Map.of("policy", op.policyId().toString(), "shipment", op.shipmentId().toString(),
                        "purchase", op.purchaseId().toString(), "nation", op.nationId().toString(),
                        "carrier", op.carrierId().toString(), "moneyId", op.id().toString()));
    }

    private static String key(MoneyOperation op) { return "starsupply:insurance:" + op.policyId() + ":" + op.leg(); }

    private static MoneyResult paymentResult(CoreGateway.PaymentResult result) {
        return switch (result) {
            case PAID -> MoneyResult.APPLIED;
            case REJECTED -> MoneyResult.REJECTED;
            case RETRY -> MoneyResult.RETRY;
            case REVIEW -> MoneyResult.REVIEW;
        };
    }
}
