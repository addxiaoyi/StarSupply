package dev.starsupply.integration;

import dev.starcore.starcore.api.StarCoreApi;
import dev.starcore.starcore.foundation.economy.EconomyService;
import dev.starcore.starcore.foundation.economy.InternalEconomyService;
import dev.starcore.starcore.foundation.economy.transaction.*;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TreasuryService;
import dev.starcore.starcore.module.treasury.NationCreditService;
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

/** Uses national credit receipts for new premiums and preserves review for unreceipted player legs. */
public final class CoreInsurancePayment implements InsuranceEconomy {
    private static final String CREDIT_PROTOCOL = "nationCreditProtocol";
    private static final String CREDIT_PROTOCOL_V1 = "1";
    private final CorePayment payments;
    private final TransactionFabric ledger;
    private final BiPredicate<UUID, BigDecimal> hasBalance;
    private final BiPredicate<UUID, BigDecimal> withdrawPremium;
    private final BiPredicate<UUID, BigDecimal> depositPremium;
    private final BiPredicate<UUID, BigDecimal> refundPremium;
    private final Logger logger;
    private final NationCreditService nationalCredits;
    private final PlayerMoneyBridge playerMoney;

    public CoreInsurancePayment(CorePayment payments, StarCoreApi api, Logger logger) {
        this.payments = Objects.requireNonNull(payments);
        this.logger = Objects.requireNonNull(logger);
        this.ledger = api.service(TransactionFabric.class).orElseThrow();
        EconomyService provider = api.service(EconomyService.class).orElseThrow();
        if (!(provider instanceof InternalEconomyService internal)) {
            throw new IllegalStateException("Freight insurance requires StarCore internal economy");
        }
        TreasuryService treasury = api.service(TreasuryService.class).orElseThrow();
        this.nationalCredits = api.service(NationCreditService.class).orElse(null);
        this.playerMoney = PlayerMoneyBridge.bindOptional(internal);
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
        this(payments, ledger, hasBalance, withdrawPremium, depositPremium, refundPremium, logger, null);
    }

    CoreInsurancePayment(CorePayment payments, TransactionFabric ledger,
                         BiPredicate<UUID, BigDecimal> hasBalance,
                         BiPredicate<UUID, BigDecimal> withdrawPremium,
                         BiPredicate<UUID, BigDecimal> depositPremium,
                         BiPredicate<UUID, BigDecimal> refundPremium, Logger logger, NationCreditService nationalCredits) {
        this(payments, ledger, hasBalance, withdrawPremium, depositPremium, refundPremium, logger, nationalCredits, null);
    }

    CoreInsurancePayment(CorePayment payments, TransactionFabric ledger,
                         BiPredicate<UUID, BigDecimal> hasBalance,
                         BiPredicate<UUID, BigDecimal> withdrawPremium,
                         BiPredicate<UUID, BigDecimal> depositPremium,
                         BiPredicate<UUID, BigDecimal> refundPremium, Logger logger,
                         NationCreditService nationalCredits, PlayerMoneyBridge playerMoney) {
        this.payments = Objects.requireNonNull(payments);
        this.ledger = Objects.requireNonNull(ledger);
        this.hasBalance = Objects.requireNonNull(hasBalance);
        this.withdrawPremium = Objects.requireNonNull(withdrawPremium);
        this.depositPremium = Objects.requireNonNull(depositPremium);
        this.refundPremium = Objects.requireNonNull(refundPremium);
        this.logger = Objects.requireNonNull(logger);
        this.nationalCredits = nationalCredits;
        this.playerMoney = playerMoney;
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
                    TransactionRecord existing = ledger.findByIdempotencyKey(key(op)).orElse(null);
                    if (existing != null && existing.metadata().containsKey(CREDIT_PROTOCOL)) {
                        yield CREDIT_PROTOCOL_V1.equals(existing.metadata().get(CREDIT_PROTOCOL)) && nationalCredits != null
                                ? creditPremium(op) : MoneyResult.REVIEW;
                    }
                    // Never replay an older ordinary tryDeposit under the new receipt identity.
                    yield existing == null && nationalCredits != null ? creditPremium(op)
                            : premium(op, () -> depositPremium.test(op.nationId(), op.amount()));
                }
                case PREMIUM_REFUND -> {
                    requireSibling(op, MoneyLeg.PREMIUM_DEBIT, COMMITTED);
                    TransactionRecord credit = requireSibling(op, MoneyLeg.PREMIUM_CREDIT, CANCELLED);
                    if (credit.metadata().containsKey(CREDIT_PROTOCOL)) {
                        if (!CREDIT_PROTOCOL_V1.equals(credit.metadata().get(CREDIT_PROTOCOL))
                                || nationalCredits == null) yield MoneyResult.REVIEW;
                        synchronized (nationalCredits) {
                            if (nationalCredits.creditReceipt(credit.idempotencyKey()).isPresent()) yield MoneyResult.REVIEW;
                        }
                    }
                    // This restores a confirmed withdrawal that never reached the treasury.
                    yield premium(op, () -> refundPremium.test(op.carrierId(), op.amount()));
                }
            };
        } catch (RuntimeException failure) {
            logger.log(Level.WARNING, "Insurance money needs review: " + op.id() + " " + op.leg(), failure);
            return MoneyResult.REVIEW;
        }
    }

    @Override public synchronized boolean canReplay(MoneyOperation op) {
        if (op.leg() == MoneyLeg.PREMIUM_DEBIT || op.leg() == MoneyLeg.PREMIUM_REFUND) {
            if (playerMoney == null) return false;
            TransactionRecord record = ledger.findByIdempotencyKey(key(op)).orElse(null);
            return record == null || PlayerMoneyBridge.VERSION.equals(record.metadata().get(PlayerMoneyBridge.PROTOCOL));
        }
        if (op.leg() != MoneyLeg.PREMIUM_CREDIT || nationalCredits == null) return false;
        TransactionRecord record = ledger.findByIdempotencyKey(key(op)).orElse(null);
        if (record == null) return true; // Both protocols persist their intent before calling treasury.
        return CREDIT_PROTOCOL_V1.equals(record.metadata().get(CREDIT_PROTOCOL));
    }

    private MoneyResult creditPremium(MoneyOperation op) {
        try {
            TransactionRecord record = ledger.begin(request(op, true));
            if (record.state() == MANUAL_REVIEW) return MoneyResult.REVIEW;
            if (record.state() == CREATED) record = ledger.transition(record.transactionId(), RESERVED,
                    "treasury premium requested with durable national credit receipt");
            // TreasuryModule serializes credits on its own monitor. Hold that same monitor
            // while inspecting receipts so its tentative in-memory receipt is never accepted.
            synchronized (nationalCredits) {
                NationCreditService.CreditRequest request = creditRequest(op);
                var receipt = nationalCredits.creditReceipt(request.transactionId());
                if (record.state() == CANCELLED || record.state() == DEBIT_FAILED) {
                    if (receipt.isPresent()) return MoneyResult.REVIEW;
                } else {
                    if (receipt.isEmpty() && record.state() == RESERVED) {
                        var result = nationalCredits.credit(request);
                        if (result == null || !request.transactionId().equals(result.transactionId())) return MoneyResult.REVIEW;
                        receipt = nationalCredits.creditReceipt(request.transactionId());
                        // REJECTED can include a persistence failure. It cannot authorize a refund.
                        if (receipt.isEmpty()) return MoneyResult.RETRY;
                    }
                    if (receipt.isEmpty() || !matches(receipt.get(), request)) return MoneyResult.REVIEW;
                }
            }
            if (record.state() == CANCELLED) return MoneyResult.REJECTED;
            if (record.state() == DEBIT_FAILED) {
                ledger.transition(record.transactionId(), CANCELLED, "confirmed unapplied treasury premium closed");
                return MoneyResult.REJECTED;
            }
            if (record.state() == RESERVED) record = ledger.transition(record.transactionId(), DEBITED,
                    "national premium credit receipt confirmed");
            return commit(record);
        } catch (IllegalArgumentException conflict) {
            logger.log(Level.WARNING, "Treasury premium identity needs review: " + op.id(), conflict);
            return MoneyResult.REVIEW;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "Treasury premium will retry its original credit receipt: " + op.id(), unavailable);
            return MoneyResult.RETRY;
        }
    }

    private static NationCreditService.CreditRequest creditRequest(MoneyOperation op) {
        return new NationCreditService.CreditRequest(key(op), NationId.of(op.nationId()), op.amount(),
                NationCreditService.CreditCategory.OTHER,
                "StarSupply insurance premium policy=" + op.policyId() + " shipment=" + op.shipmentId()
                        + " purchase=" + op.purchaseId() + " carrier=" + op.carrierId() + " money=" + op.id());
    }

    private static boolean matches(NationCreditService.CreditReceipt receipt, NationCreditService.CreditRequest request) {
        return receipt.transactionId().equals(request.transactionId()) && receipt.nationId().equals(request.nationId())
                && receipt.amount().compareTo(request.amount()) == 0 && receipt.category() == request.category()
                && receipt.description().equals(request.description());
    }

    private MoneyResult premium(MoneyOperation op, BooleanSupplier action) {
        if (op.leg() == MoneyLeg.PREMIUM_DEBIT || op.leg() == MoneyLeg.PREMIUM_REFUND) {
            TransactionRecord existing = ledger.findByIdempotencyKey(key(op)).orElse(null);
            if ((existing == null && playerMoney != null)
                    || (existing != null && existing.metadata().containsKey(PlayerMoneyBridge.PROTOCOL))) {
                return playerPremium(op);
            }
        }
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

    private MoneyResult playerPremium(MoneyOperation op) {
        if (playerMoney == null) return MoneyResult.REVIEW;
        try {
            TransactionRequest legacy = request(op);
            Map<String, String> metadata = new java.util.HashMap<>(legacy.metadata());
            metadata.put(PlayerMoneyBridge.PROTOCOL, PlayerMoneyBridge.VERSION);
            TransactionRecord record = playerMoney.advance(ledger, new TransactionRequest(legacy.accountId(), legacy.amount(),
                    legacy.owner(), legacy.type(), legacy.reason(), legacy.idempotencyKey(), Map.copyOf(metadata)),
                    op.leg() == MoneyLeg.PREMIUM_DEBIT ? "DEBIT" : "CREDIT");
            return record.state() == COMMITTED ? MoneyResult.APPLIED : record.state() == CANCELLED ? MoneyResult.REJECTED : MoneyResult.REVIEW;
        } catch (IllegalArgumentException conflict) {
            logger.log(Level.WARNING, "Insurance player receipt conflict: " + op.id(), conflict);
            return MoneyResult.REVIEW;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "Insurance player receipt will retry original operation: " + op.id(), unavailable);
            return MoneyResult.RETRY;
        }
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
            if (record.metadata().containsKey(PlayerMoneyBridge.PROTOCOL)) {
                if (op.leg() != MoneyLeg.PREMIUM_DEBIT && op.leg() != MoneyLeg.PREMIUM_REFUND) return MoneyResult.REVIEW;
                return execute(op); // Operator flags cannot override or manufacture authoritative player receipts.
            }
            boolean receiptBacked = record.metadata().containsKey(CREDIT_PROTOCOL);
            // begin validates the original amount, account and immutable policy metadata.
            ledger.begin(request(op, receiptBacked));
            if (receiptBacked) {
                if (op.leg() != MoneyLeg.PREMIUM_CREDIT || !CREDIT_PROTOCOL_V1.equals(record.metadata().get(CREDIT_PROTOCOL))
                        || nationalCredits == null) return MoneyResult.REVIEW;
                java.util.Optional<NationCreditService.CreditReceipt> receipt;
                synchronized (nationalCredits) {
                    receipt = nationalCredits.creditReceipt(key(op));
                }
                if (receipt.isPresent()) {
                    if (record.state() == CANCELLED || record.state() == DEBIT_FAILED) return MoneyResult.REVIEW;
                    return matches(receipt.get(), creditRequest(op)) ? execute(op) : MoneyResult.REVIEW;
                }
                if (applied) return MoneyResult.REVIEW; // An operator flag cannot manufacture the missing receipt.
                if (record.state() != RESERVED && record.state() != DEBIT_FAILED && record.state() != CANCELLED) {
                    return MoneyResult.REVIEW;
                }
                // Independent evidence of no credit may close an interrupted request. A known
                // receipt above always wins over an operator's "unapplied" flag.
            }
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

    private TransactionRecord requireSibling(MoneyOperation op, MoneyLeg sibling, EconomyTransactionState state) {
        String siblingKey = "starsupply:insurance:" + op.policyId() + ":" + sibling;
        TransactionRecord record = ledger.findByIdempotencyKey(siblingKey).orElseThrow();
        if (record.state() != state || record.amount().compareTo(op.amount()) != 0
                || !op.policyId().toString().equals(record.metadata().get("policy"))
                || !op.shipmentId().toString().equals(record.metadata().get("shipment"))
                || !op.purchaseId().toString().equals(record.metadata().get("purchase"))
                || !op.nationId().toString().equals(record.metadata().get("nation"))
                || !op.carrierId().toString().equals(record.metadata().get("carrier"))) {
            throw new IllegalStateException("Insurance premium evidence mismatch");
        }
        return record;
    }

    private static TransactionRequest request(MoneyOperation op) {
        return request(op, false);
    }

    private static TransactionRequest request(MoneyOperation op, boolean receiptBacked) {
        Map<String, String> metadata = new java.util.HashMap<>(Map.of("policy", op.policyId().toString(),
                "shipment", op.shipmentId().toString(), "purchase", op.purchaseId().toString(),
                "nation", op.nationId().toString(), "carrier", op.carrierId().toString(), "moneyId", op.id().toString()));
        if (receiptBacked) metadata.put(CREDIT_PROTOCOL, CREDIT_PROTOCOL_V1);
        return new TransactionRequest(op.leg() == MoneyLeg.PREMIUM_CREDIT ? op.nationId() : op.carrierId(),
                op.amount(), RefundOwner.WAR, TransactionType.OTHER, "StarSupply freight insurance " + op.leg(), key(op),
                Map.copyOf(metadata));
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
