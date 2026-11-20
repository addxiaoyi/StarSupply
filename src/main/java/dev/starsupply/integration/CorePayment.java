package dev.starsupply.integration;

import dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState;
import dev.starcore.starcore.foundation.economy.transaction.RefundOwner;
import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.foundation.economy.transaction.TransactionRecord;
import dev.starcore.starcore.foundation.economy.transaction.TransactionRequest;
import dev.starcore.starcore.foundation.economy.transaction.TransactionType;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import dev.starsupply.integration.CoreGateway.PaymentResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState.*;

/** Coordinates a receipt-backed treasury debit with the existing player economy. */
public final class CorePayment {
    private final NationSpendingService spending;
    private final TransactionFabric ledger;
    private final BiPredicate<UUID, BigDecimal> depositPlayer;
    private final Predicate<UUID> nationExists;
    private final Logger logger;

    public CorePayment(NationSpendingService spending, TransactionFabric ledger,
                BiPredicate<UUID, BigDecimal> depositPlayer, Predicate<UUID> nationExists,
                Logger logger) {
        this.spending = spending;
        this.ledger = ledger;
        this.depositPlayer = depositPlayer;
        this.nationExists = nationExists;
        this.logger = logger;
    }

    public synchronized PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId,
                                   BigDecimal amount) {
        return pay(settlementId, nationId, playerId, amount, null, null);
    }

    synchronized PaymentResult pay(UUID settlementId, UUID nationId, UUID playerId,
                                   BigDecimal amount, TaxAssessment tax,
                                   java.util.function.Predicate<TaxAssessment> fundTax) {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(playerId, "playerId");
        amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.UNNECESSARY);
        if (tax != null && (!tax.settlementId().equals(settlementId) || !tax.nationId().equals(nationId)
                || !tax.payerId().equals(playerId) || tax.grossAmount().compareTo(amount) != 0)) {
            return PaymentResult.REVIEW;
        }
        BigDecimal playerAmount = tax == null ? amount : tax.netAmount();
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
        try {
            TransactionRecord transaction = ledger.begin(new TransactionRequest(
                    nationId, amount, RefundOwner.WAR, TransactionType.TRADE,
                    "StarSupply payment", paymentKey(settlementId),
                    paymentMetadata(settlementId, playerId, tax)));
            return advance(transaction, tax, fundTax, playerAmount);
        } catch (IllegalArgumentException mismatch) {
            logger.log(Level.SEVERE, "StarSupply settlement key mismatch: " + settlementId, mismatch);
            return PaymentResult.REVIEW;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "StarSupply could not advance payment " + settlementId, unavailable);
            return PaymentResult.RETRY;
        }
    }

    private static Map<String, String> paymentMetadata(UUID settlementId, UUID playerId, TaxAssessment tax) {
        Map<String, String> metadata = new java.util.HashMap<>();
        metadata.put("settlementId", settlementId.toString());
        metadata.put("playerId", playerId.toString());
        if (tax != null) {
            metadata.put("taxType", tax.type());
            metadata.put("taxNation", tax.nationId().toString());
            metadata.put("taxAmount", tax.taxAmount().toPlainString());
        }
        return Map.copyOf(metadata);
    }

    /** Receipt-backed national reserve. PAID here confirms the reserve, without crediting a player. */
    public synchronized PaymentResult reserve(UUID settlementId, UUID nationId, UUID playerId, BigDecimal amount) {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(nationId, "nationId");
        Objects.requireNonNull(playerId, "playerId");
        amount = Objects.requireNonNull(amount, "amount").setScale(2, RoundingMode.UNNECESSARY);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Reserve amount must be positive");
        try {
            TransactionRecord transaction = ledger.begin(new TransactionRequest(nationId, amount,
                    RefundOwner.WAR, TransactionType.TRADE, "StarSupply payment", paymentKey(settlementId),
                    paymentMetadata(settlementId, playerId, null)));
            return advance(transaction, null, null, amount, true);
        } catch (IllegalArgumentException mismatch) {
            logger.log(Level.SEVERE, "StarSupply reserve key mismatch: " + settlementId, mismatch);
            return PaymentResult.REVIEW;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "StarSupply could not reserve national funds " + settlementId, unavailable);
            return PaymentResult.RETRY;
        }
    }

    /** Releases only a reservation whose player credit has never started. REJECTED means released. */
    public synchronized PaymentResult release(UUID settlementId) {
        try {
            TransactionRecord transaction = ledger.findByIdempotencyKey(paymentKey(settlementId))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown StarSupply reservation"));
            if (transaction.state() == CANCELLED || transaction.state() == REFUNDED) return PaymentResult.REJECTED;
            if (ledger.findByIdempotencyKey(creditKey(transaction)).isPresent()
                    || transaction.metadata().containsKey("taxType")) return PaymentResult.REVIEW;
            if (transaction.state() == CREATED) {
                move(transaction, CANCELLED, "unused reservation cancelled");
                return PaymentResult.REJECTED;
            }
            if (transaction.state() == RESERVED) {
                OptionalSpend receipt = receipt(transaction);
                if (receipt == OptionalSpend.ABSENT) {
                    move(transaction, DEBIT_FAILED, "unused reservation has no debit receipt");
                    move(transaction, CANCELLED, "unused reservation cancelled");
                    return PaymentResult.REJECTED;
                }
                if (receipt == OptionalSpend.CONFLICT) return PaymentResult.REVIEW;
                if (receipt == OptionalSpend.REFUNDED) {
                    transaction = move(transaction, DEBITED, "previous reservation receipt found");
                } else {
                    transaction = move(transaction, DEBITED, "reservation receipt confirmed");
                }
            }
            if (transaction.state() == DEBITED) {
                transaction = move(transaction, DELIVERY_FAILED, "unused national reserve released");
            }
            if (transaction.state() == DELIVERY_FAILED || transaction.state() == REFUND_PENDING) return refund(transaction);
            return PaymentResult.REVIEW;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "StarSupply could not release national reserve " + settlementId, unavailable);
            return PaymentResult.RETRY;
        }
    }

    /** Only after independent evidence proves an interrupted credit did not reach the player. */
    public synchronized PaymentResult retryUnappliedCredit(UUID settlementId) {
        try {
            TransactionRecord transaction = ledger.findByIdempotencyKey(paymentKey(settlementId))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown StarSupply reservation"));
            if (transaction.state() != DEBITED || transaction.metadata().containsKey("taxType")
                    || receipt(transaction) != OptionalSpend.APPLIED) return PaymentResult.REVIEW;
            TransactionRecord credit = ledger.findByIdempotencyKey(creditKey(transaction))
                    .orElseThrow(() -> new IllegalArgumentException("No interrupted player credit"));
            if (credit.state() == RESERVED) {
                credit = move(credit, DEBIT_FAILED, "operator proved player credit unapplied; retry authorized");
            }
            if (credit.state() == DEBIT_FAILED) {
                move(credit, CREATED, "retry confirmed unpaid player credit using original reserve");
                return PaymentResult.RETRY;
            }
            return credit.state() == CREATED ? PaymentResult.RETRY : PaymentResult.REVIEW;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "StarSupply could not reset confirmed unpaid credit " + settlementId, unavailable);
            return PaymentResult.REVIEW;
        }
    }

    private PaymentResult advance(TransactionRecord transaction) {
        return advance(transaction, null, null, transaction.amount());
    }

    private PaymentResult advance(TransactionRecord transaction, TaxAssessment tax,
                                  java.util.function.Predicate<TaxAssessment> fundTax,
                                  BigDecimal playerAmount) {
        return advance(transaction, tax, fundTax, playerAmount, false);
    }

    private PaymentResult advance(TransactionRecord transaction, TaxAssessment tax,
                                  java.util.function.Predicate<TaxAssessment> fundTax,
                                  BigDecimal playerAmount, boolean reserveOnly) {
        if (transaction.state() == COMMITTED) {
            return PaymentResult.PAID;
        }
        if (transaction.state() == CANCELLED || transaction.state() == REFUNDED) {
            return PaymentResult.REJECTED;
        }
        if (transaction.state() == MANUAL_REVIEW) {
            return PaymentResult.REVIEW;
        }
        if (transaction.state() == DEBIT_FAILED) {
            move(transaction, CANCELLED, "national spend rejected before debit");
            return PaymentResult.REJECTED;
        }
        if (transaction.state() == CREATED) {
            transaction = move(transaction, RESERVED, "national spend requested");
        }
        if (transaction.state() == RESERVED) {
            OptionalSpend receipt = receipt(transaction);
            if (receipt == OptionalSpend.CONFLICT || receipt == OptionalSpend.REFUNDED) {
                move(transaction, MANUAL_REVIEW, "unexpected national spend receipt");
                return PaymentResult.REVIEW;
            }
            if (receipt == OptionalSpend.ABSENT) {
                if (!nationExists.test(transaction.accountId())) {
                    move(transaction, CANCELLED, "nation no longer exists");
                    return PaymentResult.REJECTED;
                }
                NationSpendingService.SpendResult spend = spending.spend(spendRequest(transaction));
                if (spend.status() == NationSpendingService.SpendingStatus.INSUFFICIENT_FUNDS) {
                    move(transaction, DEBIT_FAILED, "insufficient national treasury balance");
                    move(transaction, CANCELLED, "national spend rejected before debit");
                    return PaymentResult.REJECTED;
                }
                if (!spend.applied()) {
                    return PaymentResult.RETRY;
                }
            }
            transaction = move(transaction, DEBITED, "national spend receipt confirmed");
        }
        if (transaction.state() == DELIVERY_FAILED || transaction.state() == REFUND_PENDING) {
            return refund(transaction);
        }
        if (transaction.state() == DELIVERED) {
            move(transaction, COMMITTED, "payment committed");
            return PaymentResult.PAID;
        }
        if (transaction.state() != DEBITED) {
            return PaymentResult.REVIEW;
        }

        if (reserveOnly) return PaymentResult.PAID;

        UUID playerId = UUID.fromString(transaction.metadata().get("playerId"));
        TransactionRecord credit = ledger.begin(new TransactionRequest(
                playerId, playerAmount, RefundOwner.WAR, TransactionType.TRADE,
                "StarSupply player credit", creditKey(transaction), transaction.metadata()));

        // The player API has no durable receipt. A restarted RESERVED credit must never be retried.
        if (credit.state() == RESERVED) {
            return PaymentResult.REVIEW;
        }
        if (credit.state() == CREATED) {
            if (receipt(transaction) != OptionalSpend.APPLIED) {
                move(transaction, MANUAL_REVIEW, "missing or refunded debit before player credit");
                return PaymentResult.REVIEW;
            }
            credit = move(credit, RESERVED, "player credit started; requires confirmation");
            boolean credited;
            try {
                credited = depositPlayer.test(playerId, playerAmount);
            } catch (RuntimeException ambiguous) {
                logger.log(Level.SEVERE, "StarSupply player credit is ambiguous: "
                        + transaction.idempotencyKey(), ambiguous);
                return PaymentResult.REVIEW;
            }
            if (!credited) {
                // The public economy API returns false for extension rejection and storage failure.
                // In particular, a database commit can fail after its durable outcome became
                // uncertain. False therefore does not prove the player was not credited; never
                // refund national funds until the operator has checked independent evidence.
                logger.log(Level.SEVERE, "StarSupply player credit returned an unclassified "
                        + "failure; settlement held for review: " + transaction.idempotencyKey());
                return PaymentResult.REVIEW;
            }
            credit = move(credit, DEBITED, "player credit confirmed");
        }
        return finishCredit(transaction, credit, tax, fundTax);
    }

    private PaymentResult finishCredit(TransactionRecord transaction, TransactionRecord credit,
                                       TaxAssessment tax,
                                       java.util.function.Predicate<TaxAssessment> fundTax) {
        if (credit.state() == DEBIT_FAILED || credit.state() == CANCELLED) {
            if (credit.state() == DEBIT_FAILED) {
                move(credit, CANCELLED, "no player credit applied");
            }
            return refund(move(transaction, DELIVERY_FAILED, "player credit rejected"));
        }
        if (credit.state() == DEBITED) {
            credit = move(credit, DELIVERED, "player balance updated");
        }
        if (credit.state() == DELIVERED) {
            credit = move(credit, COMMITTED, "player credit committed");
        }
        if (credit.state() != COMMITTED) {
            return PaymentResult.REVIEW;
        }
        if (tax != null && tax.taxAmount().signum() > 0) {
            try {
                if (fundTax == null || !fundTax.test(tax)) return PaymentResult.REVIEW;
            } catch (RuntimeException ambiguous) {
                logger.log(Level.SEVERE, "StarSupply tariff result is ambiguous: " + tax.settlementId(), ambiguous);
                return PaymentResult.REVIEW;
            }
        }
        transaction = move(transaction, DELIVERED, "player credit receipt confirmed by adapter");
        move(transaction, COMMITTED, "payment committed");
        return PaymentResult.PAID;
    }

    private PaymentResult refund(TransactionRecord transaction) {
        if (transaction.state() == DELIVERY_FAILED) {
            transaction = move(transaction, REFUND_PENDING, "refund requested for national debit");
        }
        OptionalSpend receipt = receipt(transaction);
        if (receipt == OptionalSpend.ABSENT || receipt == OptionalSpend.CONFLICT) {
            move(transaction, MANUAL_REVIEW, "cannot match national receipt for refund");
            return PaymentResult.REVIEW;
        }
        if (receipt != OptionalSpend.REFUNDED && !spending.refund(transaction.idempotencyKey())) {
            return PaymentResult.RETRY;
        }
        move(transaction, REFUNDED, "national refund confirmed");
        return PaymentResult.REJECTED;
    }

    /** Resolve only a credit that was interrupted in the external call window. Never credits again. */
    public synchronized PaymentResult reconcile(UUID settlementId, boolean playerActuallyCredited) {
        try {
            TransactionRecord transaction = ledger.findByIdempotencyKey(paymentKey(settlementId))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown StarSupply settlement"));
            TransactionRecord credit = ledger.findByIdempotencyKey(creditKey(transaction))
                    .orElseThrow(() -> new IllegalArgumentException("No player credit started"));
            if (transaction.state() != DEBITED || credit.state() != RESERVED) {
                if (transaction.metadata().containsKey("taxType")) return PaymentResult.REVIEW;
                return advance(transaction);
            }
            if (receipt(transaction) != OptionalSpend.APPLIED) {
                return PaymentResult.REVIEW;
            }
            credit = playerActuallyCredited
                    ? move(credit, DEBITED, "player credit confirmed by operator")
                    : move(credit, DEBIT_FAILED, "operator confirmed player was not credited");
            if (playerActuallyCredited && transaction.metadata().containsKey("taxType")) {
                if (credit.state() == DEBITED) credit = move(credit, DELIVERED, "player credit confirmed by operator");
                if (credit.state() == DELIVERED) move(credit, COMMITTED, "player credit committed after review");
                return PaymentResult.REVIEW;
            }
            return finishCredit(transaction, credit, null, null);
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "StarSupply could not reconcile payment " + settlementId, unavailable);
            return PaymentResult.RETRY;
        }
    }

    synchronized PaymentResult reconcileTax(UUID settlementId, TaxAssessment tax, boolean alreadyFunded,
                                             java.util.function.Predicate<TaxAssessment> fundTax) {
        try {
            TransactionRecord transaction = ledger.findByIdempotencyKey(paymentKey(settlementId))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown StarSupply settlement"));
            if (!matchesTaxMetadata(transaction, tax)) return PaymentResult.REVIEW;
            if (transaction.state() == COMMITTED) return PaymentResult.PAID;
            if (transaction.state() != DEBITED || !transaction.metadata().containsKey("taxType")) {
                return PaymentResult.REVIEW;
            }
            TransactionRecord credit = ledger.findByIdempotencyKey(creditKey(transaction))
                    .orElseThrow(() -> new IllegalArgumentException("No player credit exists"));
            if (credit.state() != COMMITTED) return PaymentResult.REVIEW;
            if (!alreadyFunded && (fundTax == null || !fundTax.test(tax))) return PaymentResult.REVIEW;
            move(transaction, DELIVERED, alreadyFunded ? "tariff confirmed by operator" : "tariff funded after review");
            move(transaction, COMMITTED, "payment and tariff committed");
            return PaymentResult.PAID;
        } catch (RuntimeException unavailable) {
            logger.log(Level.WARNING, "StarSupply could not reconcile tariff " + settlementId, unavailable);
            return PaymentResult.RETRY;
        }
    }

    private static boolean matchesTaxMetadata(TransactionRecord transaction, TaxAssessment tax) {
        return tax.settlementId().toString().equals(transaction.metadata().get("settlementId"))
                && tax.payerId().toString().equals(transaction.metadata().get("playerId"))
                && tax.nationId().toString().equals(transaction.metadata().get("taxNation"))
                && tax.type().equals(transaction.metadata().get("taxType"))
                && tax.taxAmount().toPlainString().equals(transaction.metadata().get("taxAmount"))
                && tax.nationId().equals(transaction.accountId())
                && tax.grossAmount().compareTo(transaction.amount()) == 0;
    }

    private NationSpendingService.SpendRequest spendRequest(TransactionRecord transaction) {
        return new NationSpendingService.SpendRequest(transaction.idempotencyKey(),
                NationId.of(transaction.accountId()), transaction.amount(),
                NationSpendingService.SpendingCategory.MILITARY,
                "StarSupply payment to " + transaction.metadata().get("playerId"));
    }

    private OptionalSpend receipt(TransactionRecord transaction) {
        return spending.spendingReceipt(transaction.idempotencyKey()).map(receipt -> {
            if (!receipt.nationId().value().equals(transaction.accountId())
                    || receipt.amount().compareTo(transaction.amount()) != 0
                    || receipt.category() != NationSpendingService.SpendingCategory.MILITARY
                    || !receipt.description().equals(spendRequest(transaction).description())) {
                return OptionalSpend.CONFLICT;
            }
            return receipt.refunded() ? OptionalSpend.REFUNDED : OptionalSpend.APPLIED;
        }).orElse(OptionalSpend.ABSENT);
    }

    private TransactionRecord move(TransactionRecord transaction, EconomyTransactionState state,
                                   String message) {
        return ledger.transition(transaction.transactionId(), state, message);
    }

    static String paymentKey(UUID settlementId) {
        return "starsupply:payment:" + settlementId;
    }

    private static String creditKey(TransactionRecord transaction) {
        return transaction.idempotencyKey() + ":player";
    }

    private enum OptionalSpend { ABSENT, APPLIED, REFUNDED, CONFLICT }
}
