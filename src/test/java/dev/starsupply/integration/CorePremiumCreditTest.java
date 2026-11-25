package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;
import static dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState.*;

import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.NationCreditService;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starcore.starcore.module.treasury.ReceiptTreasuryFixture;
import dev.starsupply.api.InsuranceApi.*;
import dev.starsupply.api.SupplyModels.Shipment;
import dev.starsupply.api.SupplyModels.TransportState;
import dev.starsupply.service.CaravanInsurance;
import dev.starsupply.storage.InsuranceStore;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CorePremiumCreditTest {
    @TempDir Path directory;
    private final UUID policy = UUID.randomUUID(), shipment = UUID.randomUUID(), purchase = UUID.randomUUID(),
            nation = UUID.randomUUID(), carrier = UUID.randomUUID();
    private final AtomicInteger debits = new AtomicInteger(), legacyCredits = new AtomicInteger(), refunds = new AtomicInteger();
    private static final Logger LOGGER = Logger.getLogger(CorePremiumCreditTest.class.getName());

    private TransactionFabric fabric() { return new TransactionFabric(directory.resolve("fabric.properties"), LOGGER); }
    private CoreInsurancePayment adapter(NationCreditService credits) {
        return adapter(credits, (id, amount) -> { legacyCredits.incrementAndGet(); return true; });
    }
    private CoreInsurancePayment adapter(NationCreditService credits, BiPredicate<UUID, BigDecimal> legacy) {
        TransactionFabric fabric = fabric();
        NationSpendingService spending = new NationSpendingService() {
            @Override public SpendResult spend(SpendRequest request) { throw new AssertionError("Unexpected reserve"); }
            @Override public Optional<SpendingReceipt> spendingReceipt(String id) { return Optional.empty(); }
        };
        CorePayment payments = new CorePayment(spending, fabric, (id, amount) -> true, id -> true, LOGGER);
        return new CoreInsurancePayment(payments, fabric, (id, amount) -> true,
                (id, amount) -> { debits.incrementAndGet(); return true; }, legacy,
                (id, amount) -> { refunds.incrementAndGet(); return true; }, LOGGER, credits);
    }
    private MoneyOperation op(MoneyLeg leg) { return op(policy, leg); }
    private MoneyOperation op(UUID id, MoneyLeg leg) {
        return new MoneyOperation(UUID.nameUUIDFromBytes(("starsupply:insurance:" + id + ":" + leg).getBytes(StandardCharsets.UTF_8)),
                id, shipment, purchase, nation, carrier, leg, new BigDecimal("2.40"));
    }
    private String key(UUID id) { return "starsupply:insurance:" + id + ":PREMIUM_CREDIT"; }
    private Path treasuryFile() { return directory.resolve("treasury.db"); }

    @Test void realCoreTreasuryReceiptSurvivesRestartAndDoesNotCreditOrDebitAgain() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(core.credits());
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_DEBIT)));
            assertTrue(gateway.canReplay(op(MoneyLeg.PREMIUM_CREDIT)));
            assertFalse(gateway.canReplay(op(MoneyLeg.PREMIUM_DEBIT)));
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            assertEquals(MoneyResult.APPLIED, adapter(core.credits()).execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
            assertEquals(op(MoneyLeg.PREMIUM_CREDIT).amount(), core.credits().creditReceipt(key(policy)).orElseThrow().amount());
            assertEquals("1", fabric().findByIdempotencyKey(key(policy)).orElseThrow().metadata().get("nationCreditProtocol"));
        }
        assertEquals(1, debits.get()); assertEquals(0, legacyCredits.get()); assertEquals(0, refunds.get());
    }

    @Test void lostResponseAfterRealCoreCommitRecoversWithOriginalReceipt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            NationCreditService lost = new ForwardingCredits(core.credits()) {
                @Override public CreditResult credit(CreditRequest request) {
                    calls.incrementAndGet(); super.credit(request); throw new IllegalStateException("lost response after commit");
                }
            };
            CoreInsurancePayment first = adapter(lost);
            first.execute(op(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(MoneyResult.RETRY, first.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(RESERVED, fabric().findByIdempotencyKey(key(policy)).orElseThrow().state());
        }
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            assertEquals(MoneyResult.APPLIED, adapter(core.credits()).execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(1, calls.get()); assertEquals(1, debits.get()); assertEquals(0, refunds.get());
    }

    @Test void realSqlFailureBeforeCreditRetriesWithoutCancellingOrRefunding() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            sql(treasuryFile(), "CREATE TRIGGER reject_credit BEFORE INSERT ON starcore_treasury_state "
                    + "WHEN NEW.property_key='credit.count' BEGIN SELECT RAISE(ABORT,'credit failure'); END");
            CoreInsurancePayment gateway = adapter(core.credits());
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(MoneyResult.RETRY, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(new BigDecimal("0.00"), core.treasury().balance(NationId.of(nation)));
            assertTrue(core.credits().creditReceipt(key(policy)).isEmpty());
            assertEquals(MoneyResult.REVIEW, gateway.execute(op(MoneyLeg.PREMIUM_REFUND)));
            sql(treasuryFile(), "DROP TRIGGER reject_credit");
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(1, debits.get()); assertEquals(0, refunds.get());
    }

    @Test void existingLegacyCallIsNeverReplayedUsingANewReceiptAfterUpgrade() throws Exception {
        CoreInsurancePayment old = adapter(null, (id, amount) -> { legacyCredits.incrementAndGet(); throw new IllegalStateException("old ambiguous call"); });
        old.execute(op(MoneyLeg.PREMIUM_DEBIT));
        assertEquals(MoneyResult.REVIEW, old.execute(op(MoneyLeg.PREMIUM_CREDIT)));
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment upgraded = adapter(core.credits());
            assertFalse(upgraded.canReplay(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(MoneyResult.REVIEW, upgraded.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertTrue(core.credits().creditReceipt(key(policy)).isEmpty());
            assertEquals(MoneyResult.APPLIED, upgraded.reconcile(op(MoneyLeg.PREMIUM_CREDIT), true));
        }
        assertEquals(1, legacyCredits.get());
    }

    @Test void receiptIdentityAndImmutableOperationDetailsAreCheckedEvenOnDuplicateCalls() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(core.credits());
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            MoneyOperation original = op(MoneyLeg.PREMIUM_CREDIT);
            MoneyOperation changed = new MoneyOperation(original.id(), policy, UUID.randomUUID(), purchase, nation, carrier,
                    MoneyLeg.PREMIUM_CREDIT, original.amount());
            assertEquals(MoneyResult.REVIEW, gateway.execute(changed));
            NationCreditService wrong = new ForwardingCredits(core.credits()) {
                @Override public Optional<CreditReceipt> creditReceipt(String id) {
                    return super.creditReceipt(id).map(r -> new CreditReceipt(r.transactionId(), r.nationId(), r.amount(),
                            r.category(), "other operation", r.appliedAt()));
                }
            };
            assertEquals(MoneyResult.REVIEW, adapter(wrong).execute(original));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
    }

    @Test void anotherShipmentOrPurchaseCannotReuseThePremiumDebit() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(core.credits());
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT));
            for (MoneyOperation changed : differentContracts(MoneyLeg.PREMIUM_CREDIT)) {
                assertEquals(MoneyResult.REVIEW, gateway.execute(changed));
            }
            assertTrue(core.credits().creditReceipt(key(policy)).isEmpty());
            assertTrue(fabric().findByIdempotencyKey(key(policy)).isEmpty());
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
    }

    @Test void missingReceiptCannotBeConfirmedAppliedAndOnlyOriginalContractCanRefund() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(new ForwardingCredits(core.credits()) {
                @Override public CreditResult credit(CreditRequest request) {
                    return new CreditResult(CreditStatus.REJECTED, request.transactionId(), BigDecimal.ZERO);
                }
            });
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(MoneyResult.RETRY, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(MoneyResult.REVIEW, gateway.reconcile(op(MoneyLeg.PREMIUM_CREDIT), true));
            assertEquals(RESERVED, fabric().findByIdempotencyKey(key(policy)).orElseThrow().state());
            assertEquals(MoneyResult.REJECTED, gateway.reconcile(op(MoneyLeg.PREMIUM_CREDIT), false));
            assertEquals(MoneyResult.REJECTED, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            for (MoneyOperation changed : differentContracts(MoneyLeg.PREMIUM_REFUND)) {
                assertEquals(MoneyResult.REVIEW, gateway.execute(changed));
            }
            assertEquals(MoneyResult.REVIEW, adapter(null).execute(op(MoneyLeg.PREMIUM_REFUND)));
            assertEquals(0, refunds.get());
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_REFUND)));
            assertEquals(MoneyResult.APPLIED, gateway.execute(op(MoneyLeg.PREMIUM_REFUND)));
            assertTrue(core.credits().creditReceipt(key(policy)).isEmpty());
            assertEquals(new BigDecimal("0.00"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(1, refunds.get()); assertEquals(1, debits.get()); assertEquals(0, legacyCredits.get());
    }

    @Test void receiptConflictingWithLocalCancellationFreezesBothCreditAndRefund() throws Exception {
        AtomicReference<NationCreditService.CreditRequest> attempted = new AtomicReference<>();
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(new ForwardingCredits(core.credits()) {
                @Override public CreditResult credit(CreditRequest request) {
                    attempted.set(request);
                    return new CreditResult(CreditStatus.REJECTED, request.transactionId(), BigDecimal.ZERO);
                }
            });
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT)); gateway.execute(op(MoneyLeg.PREMIUM_CREDIT));
            assertEquals(MoneyResult.REJECTED, gateway.reconcile(op(MoneyLeg.PREMIUM_CREDIT), false));
            // Simulate conflicting authority evidence after a local cancellation, e.g. mismatched restoration.
            assertEquals(NationCreditService.CreditStatus.APPLIED, core.credits().credit(attempted.get()).status());
            assertEquals(MoneyResult.REVIEW, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(MoneyResult.REVIEW, gateway.reconcile(op(MoneyLeg.PREMIUM_CREDIT), false));
            assertEquals(MoneyResult.REVIEW, gateway.reconcile(op(MoneyLeg.PREMIUM_CREDIT), true));
            assertEquals(MoneyResult.REVIEW, gateway.execute(op(MoneyLeg.PREMIUM_REFUND)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(0, refunds.get());
    }

    @Test void failedTransactionFabricWriteRecoversTheCommittedCoreReceiptAfterRestart() throws Exception {
        Path blockedTemporaryFile = directory.resolve("fabric.properties.tmp");
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(new ForwardingCredits(core.credits()) {
                @Override public CreditResult credit(CreditRequest request) {
                    CreditResult result = super.credit(request);
                    try { Files.createDirectory(blockedTemporaryFile); }
                    catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    return result;
                }
            });
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(MoneyResult.RETRY, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(RESERVED, fabric().findByIdempotencyKey(key(policy)).orElseThrow().state());
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
            Files.delete(blockedTemporaryFile);
        }
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            assertEquals(MoneyResult.APPLIED, adapter(core.credits()).execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(COMMITTED, fabric().findByIdempotencyKey(key(policy)).orElseThrow().state());
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(1, debits.get()); assertEquals(0, refunds.get());
    }

    private MoneyOperation[] differentContracts(MoneyLeg leg) {
        MoneyOperation original = op(leg);
        return new MoneyOperation[] {
                new MoneyOperation(original.id(), policy, UUID.randomUUID(), purchase, nation, carrier, leg, original.amount()),
                new MoneyOperation(original.id(), policy, shipment, UUID.randomUUID(), nation, carrier, leg, original.amount())
        };
    }

    @Test void losingReceiptServiceAfterIntentNeverFallsBackToOrdinaryTreasuryDeposit() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(new ForwardingCredits(core.credits()) {
                @Override public CreditResult credit(CreditRequest request) { throw new IllegalStateException("unavailable"); }
            });
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT));
            assertEquals(MoneyResult.RETRY, gateway.execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertEquals(MoneyResult.REVIEW, adapter(null).execute(op(MoneyLeg.PREMIUM_CREDIT)));
            assertFalse(adapter(null).canReplay(op(MoneyLeg.PREMIUM_CREDIT)));
        }
        assertEquals(0, legacyCredits.get());
    }

    @Test void receiptTakesPrecedenceOverAnOperatorsUnappliedFlag() throws Exception {
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile())) {
            CoreInsurancePayment gateway = adapter(new ForwardingCredits(core.credits()) {
                @Override public CreditResult credit(CreditRequest request) { super.credit(request); throw new IllegalStateException("lost reply"); }
            });
            gateway.execute(op(MoneyLeg.PREMIUM_DEBIT)); gateway.execute(op(MoneyLeg.PREMIUM_CREDIT));
            CoreInsurancePayment restarted = adapter(core.credits());
            assertEquals(MoneyResult.APPLIED, restarted.reconcile(op(MoneyLeg.PREMIUM_CREDIT), false));
            assertEquals(MoneyResult.REVIEW, restarted.execute(op(MoneyLeg.PREMIUM_REFUND)));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(0, refunds.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void lostLocalSqlResultRecoversInProcessAndAfterReopeningAllLedgers(boolean recoverInProcess) throws Exception {
        Path insurance = directory.resolve("insurance.db");
        Shipment cargo = new Shipment(shipment, nation, purchase, "depot", "front", new BigDecimal("10.00"),
                TransportState.OPEN, null, 1000, 100000, "depot>front", 1000, 0.20);
        UUID id;
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile()); InsuranceStore store = new InsuranceStore(insurance)) {
            CaravanInsurance service = new CaravanInsurance(store, adapter(core.credits()), new BigDecimal("0.10"), BigDecimal.ONE);
            Policy saved = store.create(service.quote(cargo, 0.20, 1000), carrier, 1000); id = saved.id();
            store.money(id, MoneyLeg.RESERVE, MoneyState.APPLIED, 1000); // Reserve is outside this premium integration test.
            sql(insurance, "CREATE TRIGGER reject_result BEFORE UPDATE OF state ON insurance_money "
                    + "WHEN NEW.leg='PREMIUM_CREDIT' AND NEW.state='APPLIED' BEGIN SELECT RAISE(ABORT,'lost local result'); END");
            assertThrows(java.sql.SQLException.class, () -> service.resume(2000));
            assertEquals(MoneyState.STARTED, store.find(id).orElseThrow().money().get(MoneyLeg.PREMIUM_CREDIT));
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
            sql(insurance, "DROP TRIGGER reject_result");
            if (recoverInProcess) {
                service.resume(2500); service.resume(2600);
                assertEquals(State.ACTIVE, store.find(id).orElseThrow().state());
            }
        }
        try (ReceiptTreasuryFixture core = new ReceiptTreasuryFixture(treasuryFile()); InsuranceStore store = new InsuranceStore(insurance)) {
            CaravanInsurance service = new CaravanInsurance(store, adapter(core.credits()), new BigDecimal("0.10"), BigDecimal.ONE);
            assertEquals(recoverInProcess ? MoneyState.APPLIED : MoneyState.STARTED,
                    store.find(id).orElseThrow().money().get(MoneyLeg.PREMIUM_CREDIT));
            service.resume(3000); service.resume(4000);
            assertEquals(State.ACTIVE, store.find(id).orElseThrow().state());
            assertEquals(recoverInProcess ? 2500 : 3000, store.find(id).orElseThrow().activatedAt());
            assertEquals(new BigDecimal("2.40"), core.treasury().balance(NationId.of(nation)));
        }
        assertEquals(1, debits.get()); assertEquals(0, refunds.get()); assertEquals(0, legacyCredits.get());
    }

    private static void sql(Path file, String command) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath()); var statement = connection.createStatement()) {
            statement.execute(command);
        }
    }
    private static class ForwardingCredits implements NationCreditService {
        private final NationCreditService delegate;
        ForwardingCredits(NationCreditService delegate) { this.delegate = delegate; }
        @Override public CreditResult credit(CreditRequest request) { return delegate.credit(request); }
        @Override public Optional<CreditReceipt> creditReceipt(String transactionId) { return delegate.creditReceipt(transactionId); }
    }
}
