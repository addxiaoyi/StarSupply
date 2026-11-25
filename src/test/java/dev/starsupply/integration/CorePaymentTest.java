package dev.starsupply.integration;

import static dev.starsupply.integration.CoreGateway.PaymentResult.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorePaymentTest {
    private static final Logger LOGGER = Logger.getLogger(CorePaymentTest.class.getName());
    private static final BigDecimal AMOUNT = new BigDecimal("5.00");
    private final UUID settlement = UUID.randomUUID();
    private final UUID nation = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final TestTreasury treasury = new TestTreasury();
    @TempDir Path folder;

    @Test
    void paidSettlementDoesNotDebitOrCreditTwiceUnderConcurrencyAndRestart() throws Exception {
        AtomicInteger credits = new AtomicInteger();
        BiPredicate<UUID, BigDecimal> deposit = (recipient, amount) -> {
            assertEquals(player, recipient);
            assertEquals(AMOUNT, amount);
            credits.incrementAndGet();
            return true;
        };
        CorePayment service = payment(deposit);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> service.pay(settlement, nation, player, AMOUNT));
            var duplicate = pool.submit(() -> service.pay(settlement, nation, player, AMOUNT));
            assertEquals(PAID, first.get());
            assertEquals(PAID, duplicate.get());
        }
        assertEquals(PAID, payment(deposit).pay(settlement, nation, player, AMOUNT));
        assertEquals(REVIEW, payment(deposit).pay(settlement, nation, UUID.randomUUID(), AMOUNT));
        assertEquals(1, credits.get());
        assertEquals(1, treasury.debits);
        assertEquals(new BigDecimal("15.00"), treasury.balance);
    }

    @Test
    void lostNationalSpendResponseResumesFromAuthorityReceipt() {
        treasury.loseNextSpendResponse = true;
        AtomicInteger credits = new AtomicInteger();
        BiPredicate<UUID, BigDecimal> deposit = (recipient, amount) -> {
            credits.incrementAndGet();
            return true;
        };
        assertEquals(RETRY, payment(deposit).pay(settlement, nation, player, AMOUNT));
        assertEquals(0, credits.get());
        assertEquals(PAID, payment(deposit).pay(settlement, nation, player, AMOUNT));
        assertEquals(1, credits.get());
        assertEquals(1, treasury.debits);
    }

    @Test
    void insufficientFundsStatusWithSufficientBalanceRetries() {
        treasury.insufficientNextSpendWhileBalanceSufficient = true;
        AtomicInteger credits = new AtomicInteger();
        BiPredicate<UUID, BigDecimal> deposit = (recipient, amount) -> {
            credits.incrementAndGet();
            return true;
        };

        assertEquals(RETRY, payment(deposit).pay(settlement, nation, player, AMOUNT));
        assertEquals(0, credits.get());
        assertEquals(0, treasury.debits);
        assertEquals(PAID, payment(deposit).pay(settlement, nation, player, AMOUNT));
        assertEquals(1, credits.get());
        assertEquals(1, treasury.debits);
        assertEquals(new BigDecimal("15.00"), treasury.balance);
    }

    @Test
    void insufficientTreasuryBalanceRejectsWithoutCreditingPlayer() {
        treasury.balance = new BigDecimal("4.99");
        AtomicInteger credits = new AtomicInteger();

        assertEquals(REJECTED, payment((recipient, amount) -> {
            credits.incrementAndGet();
            return true;
        }).pay(settlement, nation, player, AMOUNT));
        assertEquals(0, credits.get());
        assertEquals(0, treasury.debits);
        assertEquals(new BigDecimal("4.99"), treasury.balance);
    }

    @Test
    void knownCreditFailureRefundsUsingSameNationalReceiptEvenAfterLostResponse() {
        treasury.loseNextRefundResponse = true;
        assertEquals(REVIEW, payment((recipient, amount) -> false)
                .pay(settlement, nation, player, AMOUNT));
        CorePayment restarted = payment((recipient, amount) -> fail("Replayed rejected player credit"));
        assertEquals(REVIEW, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(0, treasury.refunds);
        // Only independent confirmation that the credit did not reach the player's account
        // makes a receipt-backed national refund safe.
        assertEquals(RETRY, restarted.reconcile(settlement, false));
        // The authority applied the refund but its response was lost. Retry with the
        // same StarCore receipt and confirm the already-applied refund without duplicating it.
        assertEquals(REJECTED, restarted.reconcile(settlement, false));
        assertEquals(REJECTED, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(REJECTED, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(1, treasury.debits);
        assertEquals(1, treasury.refunds);
        assertEquals(new BigDecimal("20.00"), treasury.balance);
    }

    @Test
    void uncertainCreditStaysHeldUntilOperatorConfirmsApplied() {
        AtomicInteger credits = new AtomicInteger();
        assertEquals(REVIEW, payment((recipient, amount) -> {
            credits.incrementAndGet();
            throw new IllegalStateException("Simulated crash after player deposit");
        }).pay(settlement, nation, player, AMOUNT));
        CorePayment restarted = payment((recipient, amount) -> fail("Replayed uncertain credit"));
        assertEquals(REVIEW, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(0, treasury.refunds);
        assertEquals(PAID, restarted.reconcile(settlement, true));
        assertEquals(PAID, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(1, credits.get());
        assertEquals(new BigDecimal("15.00"), treasury.balance);
    }

    @Test
    void falseResultAfterPossibleCommitNeverRefundsUntilReconciled() {
        AtomicInteger calls = new AtomicInteger();
        CorePayment service = payment((recipient, amount) -> {
            calls.incrementAndGet();
            return false; // The provider may already have persisted the balance.
        });
        assertEquals(REVIEW, service.pay(settlement, nation, player, AMOUNT));

        CorePayment restarted = payment((recipient, amount) -> fail("Must not repeat credit"));
        assertEquals(REVIEW, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(0, treasury.refunds);
        assertEquals(PAID, restarted.reconcile(settlement, true));
        assertEquals(1, calls.get());
        assertEquals(0, treasury.refunds);
        assertEquals(PAID, restarted.pay(settlement, nation, player, AMOUNT));
    }

    @Test
    void tariffWithholdsFromSellerOnlyAfterGrossTreasuryDebitAndRetriesWithoutDuplicateCredit() {
        AtomicInteger credits = new AtomicInteger();
        AtomicInteger tariffCalls = new AtomicInteger();
        TaxAssessment assessment = new TaxAssessment(settlement, "TARIFF", nation, player,
                new BigDecimal("5.00"), new BigDecimal("1.25"));
        CorePayment service = payment((recipient, amount) -> {
            assertEquals(player, recipient);
            assertEquals(new BigDecimal("3.75"), amount);
            credits.incrementAndGet();
            return true;
        });
        assertEquals(REVIEW, service.pay(settlement, nation, player, new BigDecimal("5.00"), assessment,
                quote -> { tariffCalls.incrementAndGet(); return false; }));
        assertEquals(1, credits.get());
        assertEquals(1, treasury.debits);
        assertEquals(REVIEW, payment((recipient, amount) -> fail("seller credit must not repeat"))
                .pay(settlement, nation, player, new BigDecimal("5.00"), assessment,
                        quote -> { tariffCalls.incrementAndGet(); return false; }));
        assertEquals(PAID, payment((recipient, amount) -> fail("seller credit must not repeat"))
                .pay(settlement, nation, player, new BigDecimal("5.00"), assessment,
                        quote -> { tariffCalls.incrementAndGet(); return true; }));
        assertEquals(1, credits.get());
        assertEquals(3, tariffCalls.get());
        assertEquals(1, treasury.debits);
        assertEquals(new BigDecimal("15.00"), treasury.balance);
    }

    @Test
    void operatorCanConfirmFundedTariffAfterRestartWithoutFundingOrCreditingAgain() {
        AtomicInteger credits = new AtomicInteger();
        TaxAssessment assessment = new TaxAssessment(settlement, "TARIFF", nation, player,
                AMOUNT, new BigDecimal("1.25"));
        assertEquals(REVIEW, payment((recipient, amount) -> {
            credits.incrementAndGet();
            return true;
        }).pay(settlement, nation, player, AMOUNT, assessment,
                quote -> { throw new IllegalStateException("Lost tax receipt after funding"); }));
        CorePayment restarted = payment((recipient, amount) -> fail("Seller must not be credited twice"));
        assertEquals(REVIEW, restarted.reconcileTax(settlement, new TaxAssessment(settlement, "TARIFF",
                UUID.randomUUID(), player, AMOUNT, new BigDecimal("1.25")), true,
                quote -> fail("Mismatched tax must not be funded")));
        assertEquals(PAID, restarted.reconcileTax(settlement, assessment, true,
                quote -> fail("Already funded tariff must not be paid twice")));
        assertEquals(PAID, restarted.reconcileTax(settlement, assessment, true,
                quote -> fail("Duplicate reconciliation must not fund")));
        assertEquals(PAID, restarted.pay(settlement, nation, player, AMOUNT, assessment,
                quote -> fail("Committed payment must not fund")));
        assertEquals(1, credits.get());
        assertEquals(1, treasury.debits);
    }

    @Test
    void operatorCanFundConfirmedUnpaidTariffWithoutRepeatingSellerCredit() {
        TaxAssessment assessment = new TaxAssessment(settlement, "TARIFF", nation, player,
                AMOUNT, new BigDecimal("1.25"));
        AtomicInteger credits = new AtomicInteger();
        assertEquals(REVIEW, payment((recipient, amount) -> {
            credits.incrementAndGet();
            return true;
        }).pay(settlement, nation, player, AMOUNT, assessment, quote -> false));
        CorePayment restarted = payment((recipient, amount) -> fail("Seller must not be credited twice"));
        AtomicInteger taxPayments = new AtomicInteger();
        assertEquals(PAID, restarted.reconcileTax(settlement, assessment, false, quote -> {
            assertEquals(assessment, quote);
            taxPayments.incrementAndGet();
            return true;
        }));
        assertEquals(PAID, restarted.reconcileTax(settlement, assessment, false,
                quote -> fail("Confirmed tariff payment must not repeat")));
        assertEquals(1, taxPayments.get());
        assertEquals(1, credits.get());
        assertEquals(1, treasury.debits);
    }

    @Test
    void nationalReserveIsReceiptBackedAndCanEitherReleaseOrPayOnlyOnce() {
        AtomicInteger credits = new AtomicInteger();
        CorePayment service = payment((recipient, amount) -> {
            credits.incrementAndGet();
            return true;
        });
        assertEquals(PAID, service.reserve(settlement, nation, player, AMOUNT));
        assertEquals(PAID, service.reserve(settlement, nation, player, AMOUNT));
        assertEquals(0, credits.get());
        assertEquals(1, treasury.debits);
        assertEquals(PAID, service.pay(settlement, nation, player, AMOUNT));
        assertEquals(REVIEW, service.release(settlement), "A paid claim cannot release its reserve");
        assertEquals(1, credits.get());

        UUID unused = UUID.randomUUID();
        assertEquals(PAID, service.reserve(unused, nation, player, AMOUNT));
        treasury.loseNextRefundResponse = true;
        assertEquals(RETRY, service.release(unused));
        assertEquals(REJECTED, service.release(unused));
        assertEquals(REJECTED, service.release(unused));
        assertEquals(REJECTED, service.pay(unused, nation, player, AMOUNT));
        assertEquals(1, treasury.refunds);
        assertEquals(1, credits.get());
        assertEquals(new BigDecimal("15.00"), treasury.balance);
    }

    @Test
    void independentlyConfirmedUnpaidClaimCanRetryOriginalReserveWithoutNewNationalDebit() {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(PAID, payment((recipient, amount) -> fail("Reserving cannot pay player"))
                .reserve(settlement, nation, player, AMOUNT));
        assertEquals(REVIEW, payment((recipient, amount) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("Credit did not reach player; independently confirmed later");
        }).pay(settlement, nation, player, AMOUNT));
        CorePayment restarted = payment((recipient, amount) -> {
            calls.incrementAndGet();
            return true;
        });
        assertEquals(RETRY, restarted.retryUnappliedCredit(settlement));
        assertEquals(PAID, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(REVIEW, restarted.retryUnappliedCredit(settlement));
        assertEquals(PAID, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(2, calls.get());
        assertEquals(1, treasury.debits);
        assertEquals(0, treasury.refunds);
    }

    @Test
    void uncertainCreditCanBeConfirmedUnpaidAndRefundedAfterRestart() {
        assertEquals(REVIEW, payment((recipient, amount) -> {
            throw new IllegalStateException("Simulated crash before player deposit");
        }).pay(settlement, nation, player, AMOUNT));
        CorePayment restarted = payment((recipient, amount) -> fail("Replayed uncertain credit"));
        assertEquals(REJECTED, restarted.reconcile(settlement, false));
        assertEquals(REJECTED, restarted.pay(settlement, nation, player, AMOUNT));
        assertEquals(1, treasury.refunds);
        assertEquals(new BigDecimal("20.00"), treasury.balance);
    }

    private CorePayment payment(BiPredicate<UUID, BigDecimal> deposit) {
        return new CorePayment(treasury, new TransactionFabric(folder.resolve("ledger"), LOGGER),
                deposit, nation::equals, LOGGER);
    }

    private final class TestTreasury implements NationSpendingService {
        private final Map<String, SpendingReceipt> receipts = new HashMap<>();
        private BigDecimal balance = new BigDecimal("20.00");
        private int debits;
        private int refunds;
        private boolean loseNextSpendResponse;
        private boolean loseNextRefundResponse;
        private boolean insufficientNextSpendWhileBalanceSufficient;

        @Override
        public SpendResult spend(SpendRequest request) {
            assertEquals(NationId.of(nation), request.nationId());
            if (receipts.containsKey(request.transactionId())) {
                return new SpendResult(SpendingStatus.ALREADY_APPLIED, request.transactionId(), balance);
            }
            if (insufficientNextSpendWhileBalanceSufficient) {
                insufficientNextSpendWhileBalanceSufficient = false;
                return new SpendResult(SpendingStatus.INSUFFICIENT_FUNDS, request.transactionId(), balance);
            }
            if (balance.compareTo(request.amount()) < 0) {
                return new SpendResult(SpendingStatus.INSUFFICIENT_FUNDS, request.transactionId(), balance);
            }
            receipts.put(request.transactionId(), new SpendingReceipt(request.transactionId(),
                    request.nationId(), request.amount(), request.category(), request.description(), Instant.now()));
            balance = balance.subtract(request.amount());
            debits++;
            if (loseNextSpendResponse) {
                loseNextSpendResponse = false;
                throw new IllegalStateException("Simulated lost national spend response");
            }
            return new SpendResult(SpendingStatus.APPLIED, request.transactionId(), balance);
        }

        @Override
        public Optional<SpendingReceipt> spendingReceipt(String transactionId) {
            return Optional.ofNullable(receipts.get(transactionId));
        }

        @Override
        public boolean refund(String transactionId) {
            SpendingReceipt receipt = receipts.get(transactionId);
            if (receipt == null) {
                return false;
            }
            if (!receipt.refunded()) {
                receipts.put(transactionId, new SpendingReceipt(receipt.transactionId(), receipt.nationId(),
                        receipt.amount(), receipt.category(), receipt.description(), receipt.appliedAt(), true));
                balance = balance.add(receipt.amount());
                refunds++;
                if (loseNextRefundResponse) {
                    loseNextRefundResponse = false;
                    throw new IllegalStateException("Simulated lost national refund response");
                }
            }
            return true;
        }
    }
}
