package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;

import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starsupply.api.InsuranceApi.*;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreInsurancePaymentTest {
    @TempDir Path folder;
    private final UUID policy = UUID.randomUUID(), shipment = UUID.randomUUID(), purchase = UUID.randomUUID(),
            nation = UUID.randomUUID(), carrier = UUID.randomUUID();
    private static final Logger LOGGER = Logger.getLogger(CoreInsurancePaymentTest.class.getName());

    @Test void ambiguousPlayerPremiumDebitIsNotRepeatedAndConfirmedDebitFundsPremiumOnlyOnce() {
        AtomicInteger debits = new AtomicInteger(), credits = new AtomicInteger();
        BiPredicate<UUID, BigDecimal> debit = (player, amount) -> { debits.incrementAndGet(); return false; };
        BiPredicate<UUID, BigDecimal> credit = (target, amount) -> { credits.incrementAndGet(); return true; };
        CoreInsurancePayment first = gateway(debit, credit, (id, amount) -> fail("Unexpected refund"));
        assertEquals(MoneyResult.REVIEW, first.execute(op(MoneyLeg.PREMIUM_DEBIT)));
        CoreInsurancePayment restarted = gateway(debit, credit, (id, amount) -> fail("Unexpected refund"));
        assertEquals(MoneyResult.REVIEW, restarted.execute(op(MoneyLeg.PREMIUM_DEBIT)));
        assertEquals(1, debits.get());
        assertEquals(MoneyResult.APPLIED, restarted.reconcile(op(MoneyLeg.PREMIUM_DEBIT), true));
        assertEquals(MoneyResult.APPLIED, restarted.execute(op(MoneyLeg.PREMIUM_CREDIT)));
        assertEquals(MoneyResult.APPLIED, restarted.execute(op(MoneyLeg.PREMIUM_CREDIT)));
        assertEquals(1, credits.get());
    }

    @Test void treasuryPremiumUncertaintyNeedsEvidenceBeforeCompensatingPlayerAndRefundNeverReplays() {
        AtomicInteger credits = new AtomicInteger(), refunds = new AtomicInteger();
        CoreInsurancePayment adapter = gateway((id, amount) -> true,
                (id, amount) -> { credits.incrementAndGet(); throw new IllegalStateException("Unknown treasury result"); },
                (id, amount) -> { refunds.incrementAndGet(); return true; });
        assertEquals(MoneyResult.APPLIED, adapter.execute(op(MoneyLeg.PREMIUM_DEBIT)));
        assertEquals(MoneyResult.REVIEW, adapter.execute(op(MoneyLeg.PREMIUM_CREDIT)));
        assertEquals(MoneyResult.REVIEW, adapter.execute(op(MoneyLeg.PREMIUM_CREDIT)));
        assertEquals(1, credits.get());
        assertEquals(MoneyResult.REVIEW, adapter.execute(op(MoneyLeg.PREMIUM_REFUND)));
        assertEquals(0, refunds.get());
        assertEquals(MoneyResult.REJECTED, adapter.reconcile(op(MoneyLeg.PREMIUM_CREDIT), false));
        assertEquals(MoneyResult.APPLIED, adapter.execute(op(MoneyLeg.PREMIUM_REFUND)));
        assertEquals(MoneyResult.APPLIED, adapter.execute(op(MoneyLeg.PREMIUM_REFUND)));
        assertEquals(1, refunds.get());
    }

    private CoreInsurancePayment gateway(BiPredicate<UUID, BigDecimal> debit,
                                         BiPredicate<UUID, BigDecimal> credit,
                                         BiPredicate<UUID, BigDecimal> refund) {
        TransactionFabric fabric = new TransactionFabric(folder.resolve("insurance-transactions.properties"), LOGGER);
        NationSpendingService treasury = new NationSpendingService() {
            @Override public SpendResult spend(SpendRequest request) { throw new AssertionError("No reserve in premium tests"); }
            @Override public Optional<SpendingReceipt> spendingReceipt(String transactionId) { return Optional.empty(); }
        };
        CorePayment payment = new CorePayment(treasury, fabric, (id, amount) -> true, id -> true, LOGGER);
        return new CoreInsurancePayment(payment, fabric, (id, amount) -> true, debit, credit, refund, LOGGER);
    }

    private MoneyOperation op(MoneyLeg leg) {
        UUID id = UUID.nameUUIDFromBytes((policy + ":" + leg).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new MoneyOperation(id, policy, shipment, purchase, nation, carrier, leg, new BigDecimal("2.80"));
    }
}
