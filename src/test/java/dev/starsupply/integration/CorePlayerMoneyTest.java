package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;
import static dev.starsupply.integration.CoreGateway.PaymentResult.*;
import static dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState.*;

import dev.starcore.starcore.foundation.economy.ReceiptPlayerFixture;
import dev.starcore.starcore.foundation.economy.transaction.*;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.ReceiptTreasuryFixture;
import dev.starcore.starcore.module.treasury.NationSpendingService;
import dev.starsupply.api.InsuranceApi.*;
import dev.starsupply.api.SupplyModels.*;
import dev.starsupply.service.CaravanInsurance;
import dev.starsupply.storage.InsuranceStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

class CorePlayerMoneyTest {
    @TempDir Path directory;
    private static final Logger LOGGER = Logger.getLogger(CorePlayerMoneyTest.class.getName());
    private final UUID nation = UUID.randomUUID(), player = UUID.randomUUID(), settlement = UUID.randomUUID();
    private final UUID policy = UUID.randomUUID(), shipment = UUID.randomUUID(), purchase = UUID.randomUUID();
    private final AtomicInteger legacyCalls = new AtomicInteger();

    static Stream<Boolean> authorities() {
        return Boolean.getBoolean("starsupply.test.actual-player-money") ? Stream.of(false, true) : Stream.of(false);
    }

    private TransactionFabric ledger() { return new TransactionFabric(directory.resolve("fabric.properties"), LOGGER); }

    private CorePayment payment(ReceiptTreasuryFixture treasury, TransactionFabric ledger, PublicMoney money) {
        return new CorePayment((NationSpendingService) treasury.credits(), ledger,
                (id, amount) -> { legacyCalls.incrementAndGet(); return true; }, id -> true, LOGGER,
                money == null ? null : PlayerMoneyBridge.bindOptional(money));
    }

    private CoreInsurancePayment insurance(ReceiptTreasuryFixture treasury, TransactionFabric ledger, PublicMoney money) {
        return new CoreInsurancePayment(payment(treasury, ledger, money), ledger, (id, amount) -> true,
                (id, amount) -> { legacyCalls.incrementAndGet(); return true; },
                (id, amount) -> { legacyCalls.incrementAndGet(); return true; },
                (id, amount) -> { legacyCalls.incrementAndGet(); return true; }, LOGGER,
                treasury.credits(), money == null ? null : PlayerMoneyBridge.bindOptional(money));
    }

    private ReceiptTreasuryFixture treasury() throws Exception { return new ReceiptTreasuryFixture(directory.resolve("treasury.db")); }

    @ParameterizedTest @MethodSource("authorities")
    void interruptedPurchaseResumesAfterReopenWithoutPayingTwice(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            money.loseReply.set(true);
            assertEquals(RETRY, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(new BigDecimal("5.00"), money.balance(player));
            money.reopen();
            var resumed = payment(treasury, ledger(), money);
            assertEquals(PAID, resumed.pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(PAID, resumed.pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(new BigDecimal("5.00"), money.balance(player));
            assertEquals(new BigDecimal("95.00"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(1, money.calls.get()); assertEquals(0, legacyCalls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void rejectedCreditHasDurableProofBeforeNationRefund(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            money.set(player, new BigDecimal("99999999999999999.99"));
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            var payments = payment(treasury, ledger(), money);
            assertEquals(REJECTED, payments.pay(settlement, nation, player, new BigDecimal("5")));
            money.reopen();
            assertEquals(REJECTED, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(new BigDecimal("100.00"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(new BigDecimal("99999999999999999.99"), money.balance(player));
            assertEquals(1, money.calls.get()); assertEquals(0, legacyCalls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void operatorUnpaidCannotOverrideACommittedPlayerReceipt(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            money.loseReply.set(true);
            assertEquals(RETRY, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(PAID, payment(treasury, ledger(), money).reconcile(settlement, false));
            assertEquals(new BigDecimal("95.00"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(new BigDecimal("5.00"), money.balance(player));
            assertEquals(1, money.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void markedIntentCannotDowngradeAndLegacyAmbiguityCannotUpgrade(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            money.unavailable.set(true);
            assertEquals(RETRY, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(REVIEW, payment(treasury, ledger(), null).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(0, legacyCalls.get());
            money.unavailable.set(false);
            assertEquals(PAID, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            UUID oldId = UUID.randomUUID();
            var old = new CorePayment((NationSpendingService) treasury.credits(), ledger(),
                    (id, amount) -> { legacyCalls.incrementAndGet(); throw new IllegalStateException("old ambiguous credit"); }, id -> true, LOGGER);
            assertEquals(REVIEW, old.pay(oldId, nation, player, new BigDecimal("4")));
            assertEquals(REVIEW, payment(treasury, ledger(), money).pay(oldId, nation, player, new BigDecimal("4")));
            assertEquals(new BigDecimal("5.00"), money.balance(player)); assertEquals(1, legacyCalls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void mismatchedReceiptFreezesPaymentWithoutRefund(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            money.loseReply.set(true);
            assertEquals(RETRY, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            money.wrongIdentity = true;
            assertEquals(REVIEW, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(REVIEW, payment(treasury, ledger(), money).reconcile(settlement, false));
            assertEquals(new BigDecimal("95.00"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(new BigDecimal("5.00"), money.balance(player));
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void taxUncertaintyStillFreezesAfterPlayerReceiptRecovery(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            TaxAssessment tax = new TaxAssessment(settlement, "TARIFF", nation, player, new BigDecimal("5"), new BigDecimal("1"));
            money.loseReply.set(true);
            assertEquals(RETRY, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5"), tax, ignored -> false));
            var resumed = payment(treasury, ledger(), money);
            assertEquals(REVIEW, resumed.reconcile(settlement, false));
            assertEquals(REVIEW, resumed.pay(settlement, nation, player, new BigDecimal("5"), tax, ignored -> false));
            assertEquals(new BigDecimal("4.00"), money.balance(player));
            assertEquals(PAID, resumed.reconcileTax(settlement, tax, true, null));
            assertEquals(1, money.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void premiumDebitAndRefundRecoverUsingOriginalIdentities(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            money.set(player, new BigDecimal("10"));
            money.loseReply.set(true);
            assertEquals(MoneyResult.RETRY, insurance(treasury, ledger(), money).execute(op(MoneyLeg.PREMIUM_DEBIT)));
            money.reopen();
            var adapter = insurance(treasury, ledger(), money);
            assertTrue(adapter.canReplay(op(MoneyLeg.PREMIUM_DEBIT)));
            assertEquals(MoneyResult.APPLIED, adapter.reconcile(op(MoneyLeg.PREMIUM_DEBIT), false));
            // Seed an independently confirmed legacy treasury cancellation, to exercise premium refund alone.
            var fabric = ledger(); var credit = fabric.begin(legacyCreditRequest());
            credit = fabric.transition(credit.transactionId(), RESERVED, "attempted");
            credit = fabric.transition(credit.transactionId(), DEBIT_FAILED, "independently proved unapplied");
            fabric.transition(credit.transactionId(), CANCELLED, "cancelled");
            money.loseReply.set(true);
            assertEquals(MoneyResult.RETRY, insurance(treasury, ledger(), money).execute(op(MoneyLeg.PREMIUM_REFUND)));
            money.reopen();
            assertEquals(MoneyResult.APPLIED, insurance(treasury, ledger(), money).execute(op(MoneyLeg.PREMIUM_REFUND)));
            assertEquals(new BigDecimal("10.00"), money.balance(player));
            assertEquals(2, money.calls.get()); assertEquals(0, legacyCalls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void insuranceLocalResultRollbackAndRestartDoNotRepeatPlayerDebit(boolean real) throws Exception {
        Path file = directory.resolve("insurance.db"); UUID policyId;
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            money.set(player, new BigDecimal("10"));
            Shipment cargo = new Shipment(shipment, nation, purchase, "depot", "front", new BigDecimal("10"),
                    TransportState.OPEN, null, 1000, 100000, "route", 1000, 0.2);
            try (var store = new InsuranceStore(file)) {
                var service = new CaravanInsurance(store, insurance(treasury, ledger(), money), new BigDecimal("0.1"), BigDecimal.ONE);
                policyId = store.create(service.quote(cargo, 0.2, 1000), player, 1000).id();
                sql(file, "CREATE TRIGGER reject_result BEFORE UPDATE OF state ON insurance_money WHEN NEW.leg='PREMIUM_DEBIT' AND NEW.state='APPLIED' BEGIN SELECT RAISE(ABORT,'lost result'); END");
                assertThrows(java.sql.SQLException.class, () -> service.resume(2000));
                assertEquals(MoneyState.STARTED, store.find(policyId).orElseThrow().money().get(MoneyLeg.PREMIUM_DEBIT));
                sql(file, "DROP TRIGGER reject_result");
            }
            money.reopen();
            try (var store = new InsuranceStore(file)) {
                var service = new CaravanInsurance(store, insurance(treasury, ledger(), money), new BigDecimal("0.1"), BigDecimal.ONE);
                service.resume(3000); service.resume(4000);
                assertEquals(State.ACTIVE, store.find(policyId).orElseThrow().state());
                assertEquals(3000, store.find(policyId).orElseThrow().activatedAt());
            }
            assertEquals(new BigDecimal("7.60"), money.balance(player));
            assertEquals(new BigDecimal("94.40"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(1, money.calls.get()); assertEquals(0, legacyCalls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void reservedClaimRecoversAppliedReceiptInsteadOfRepeatingPayout(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            var payments = payment(treasury, ledger(), money);
            assertEquals(PAID, payments.reserve(settlement, nation, player, new BigDecimal("8")));
            money.loseReply.set(true);
            assertEquals(RETRY, payments.pay(settlement, nation, player, new BigDecimal("8")));
            assertEquals(PAID, payment(treasury, ledger(), money).retryUnappliedCredit(settlement));
            assertEquals(new BigDecimal("8.00"), money.balance(player));
            assertEquals(new BigDecimal("92.00"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(1, money.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void transactionResultWriteFailureRecoversFromAuthoritativePlayerReceipt(boolean real) throws Exception {
        Path temporary = directory.resolve("fabric.properties.tmp");
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            treasury.treasury().tryDeposit(NationId.of(nation), new BigDecimal("100"));
            money.afterApply = () -> {
                try { java.nio.file.Files.createDirectory(temporary); }
                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
            };
            assertEquals(RETRY, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(RESERVED, ledger().findByIdempotencyKey(CorePayment.paymentKey(settlement) + ":player").orElseThrow().state());
            assertEquals(new BigDecimal("5.00"), money.balance(player));
            java.nio.file.Files.delete(temporary);
            money.afterApply = () -> {}; money.reopen();
            assertEquals(PAID, payment(treasury, ledger(), money).pay(settlement, nation, player, new BigDecimal("5")));
            assertEquals(new BigDecimal("5.00"), money.balance(player));
            assertEquals(new BigDecimal("95.00"), treasury.treasury().balance(NationId.of(nation)));
            assertEquals(1, money.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void legacyPremiumDebitIsNotUpgradedAndNewMarkedDebitCannotDowngrade(boolean real) throws Exception {
        try (var money = new PublicMoney(real, directory.resolve("players.db")); var treasury = treasury()) {
            money.set(player, new BigDecimal("10"));
            money.unavailable.set(true);
            assertEquals(MoneyResult.RETRY, insurance(treasury, ledger(), money).execute(op(MoneyLeg.PREMIUM_DEBIT)));
            assertEquals(MoneyResult.REVIEW, insurance(treasury, ledger(), null).execute(op(MoneyLeg.PREMIUM_DEBIT)));
            assertEquals(0, legacyCalls.get()); money.unavailable.set(false);
            assertEquals(MoneyResult.APPLIED, insurance(treasury, ledger(), money).execute(op(MoneyLeg.PREMIUM_DEBIT)));
            var oldPolicy = UUID.randomUUID();
            var old = new MoneyOperation(UUID.randomUUID(), oldPolicy, shipment, purchase, nation, player, MoneyLeg.PREMIUM_DEBIT, new BigDecimal("2.40"));
            var fabric = ledger();
            var oldRecord = fabric.begin(new TransactionRequest(player, old.amount(), RefundOwner.WAR, TransactionType.OTHER,
                    "StarSupply freight insurance PREMIUM_DEBIT", "starsupply:insurance:" + oldPolicy + ":PREMIUM_DEBIT",
                    Map.of("policy", oldPolicy.toString(), "shipment", shipment.toString(), "purchase", purchase.toString(),
                            "nation", nation.toString(), "carrier", player.toString(), "moneyId", old.id().toString())));
            fabric.transition(oldRecord.transactionId(), RESERVED, "legacy call might have applied");
            var upgraded = insurance(treasury, ledger(), money);
            assertFalse(upgraded.canReplay(old)); assertEquals(MoneyResult.REVIEW, upgraded.execute(old));
            assertEquals(new BigDecimal("7.60"), money.balance(player)); assertEquals(1, money.calls.get());
        }
    }

    private MoneyOperation op(MoneyLeg leg) {
        return new MoneyOperation(PlayerMoneyBridge.id("starsupply:insurance:" + policy + ":" + leg), policy,
                shipment, purchase, nation, player, leg, new BigDecimal("2.40"));
    }
    private TransactionRequest legacyCreditRequest() {
        MoneyOperation op = op(MoneyLeg.PREMIUM_CREDIT);
        return new TransactionRequest(nation, op.amount(), RefundOwner.WAR, TransactionType.OTHER,
                "StarSupply freight insurance PREMIUM_CREDIT", "starsupply:insurance:" + policy + ":PREMIUM_CREDIT",
                Map.of("policy", policy.toString(), "shipment", shipment.toString(), "purchase", purchase.toString(),
                        "nation", nation.toString(), "carrier", player.toString(), "moneyId", op.id().toString()));
    }
    private static void sql(Path file, String command) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file); var statement = connection.createStatement()) { statement.execute(command); }
    }

    public enum Direction { CREDIT, DEBIT }
    public enum Status { APPLIED, INSUFFICIENT_FUNDS, EXTENSION_REJECTED, BALANCE_LIMIT }
    public record Request(UUID operationId, UUID accountId, Direction direction, BigDecimal amount, String source) {}
    public record Receipt(Request request, Status status, BigDecimal balanceBefore, BigDecimal balanceAfter, long recordedAt) {}

    /** Runs every scenario against a test authority; opt-in repeats them against actual patched Core/SQLite. */
    public static final class PublicMoney implements AutoCloseable {
        final AtomicBoolean loseReply = new AtomicBoolean(), unavailable = new AtomicBoolean();
        final AtomicInteger calls = new AtomicInteger();
        final Map<UUID, Receipt> receipts = new HashMap<>(); final Map<UUID, BigDecimal> balances = new HashMap<>();
        final boolean real; final Path file; ReceiptPlayerFixture core; boolean wrongIdentity;
        Runnable afterApply = () -> {};
        PublicMoney(boolean real, Path file) { this.real = real; this.file = file; reopen(); }
        void reopen() {
            if (real) {
                if (core != null) core.close(); core = new ReceiptPlayerFixture(file);
                assertNotNull(PlayerMoneyBridge.bindOptional(core.economy()), "Actual Core receipt API required for opt-in tests");
            }
        }
        void set(UUID player, BigDecimal amount) { if (real) assertTrue(core.economy().setBalance(player, amount)); else balances.put(player, amount.setScale(2)); }
        BigDecimal balance(UUID player) { return real ? core.economy().balance(player) : balances.getOrDefault(player, BigDecimal.ZERO).setScale(2); }
        public Receipt applyMoney(UUID id, UUID player, String kind, BigDecimal amount, String source) {
            if (unavailable.get()) throw new IllegalStateException("unavailable");
            calls.incrementAndGet(); Receipt receipt;
            if (real) receipt = convert(invoke(core.economy(), "applyMoney", new Class<?>[]{UUID.class, UUID.class, String.class, BigDecimal.class, String.class}, id, player, kind, amount, source));
            else {
                Request request = new Request(id, player, Direction.valueOf(kind), amount.setScale(2), source);
                receipt = receipts.get(id);
                if (receipt == null) {
                    BigDecimal before = balance(player), next = kind.equals("CREDIT") ? before.add(amount) : before.subtract(amount);
                    Status result = next.signum() < 0 ? Status.INSUFFICIENT_FUNDS : next.compareTo(new BigDecimal("99999999999999999.99")) > 0 ? Status.BALANCE_LIMIT : Status.APPLIED;
                    if (result != Status.APPLIED) next = before;
                    receipt = new Receipt(request, result, before, next, System.currentTimeMillis());
                    receipts.put(id, receipt); balances.put(player, next);
                } else if (!request.equals(receipt.request())) throw new IllegalArgumentException("conflict");
            }
            afterApply.run();
            if (loseReply.getAndSet(false)) throw new IllegalStateException("lost response after actual money result");
            return receipt;
        }
        public Optional<Receipt> moneyReceipt(UUID id) {
            if (unavailable.get()) throw new IllegalStateException("unavailable");
            Optional<Receipt> receipt = real ? ((Optional<?>) invoke(core.economy(), "moneyReceipt", new Class<?>[]{UUID.class}, id)).map(PublicMoney::convert) : Optional.ofNullable(receipts.get(id));
            return wrongIdentity ? receipt.map(r -> new Receipt(new Request(r.request().operationId(), r.request().accountId(), r.request().direction(), r.request().amount(), "wrong"), r.status(), r.balanceBefore(), r.balanceAfter(), r.recordedAt())) : receipt;
        }
        private static Receipt convert(Object value) {
            Object request = read(value, "request");
            return new Receipt(new Request((UUID) read(request, "operationId"), (UUID) read(request, "accountId"), Direction.valueOf(((Enum<?>) read(request, "direction")).name()),
                    (BigDecimal) read(request, "amount"), (String) read(request, "source")), Status.valueOf(((Enum<?>) read(value, "status")).name()),
                    (BigDecimal) read(value, "balanceBefore"), (BigDecimal) read(value, "balanceAfter"), (Long) read(value, "recordedAt"));
        }
        private static Object read(Object receiver, String method) { return invoke(receiver, method, new Class<?>[0]); }
        private static Object invoke(Object receiver, String method, Class<?>[] types, Object... args) {
            try { return receiver.getClass().getMethod(method, types).invoke(receiver, args); }
            catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                if (e.getCause() instanceof Error error) throw error;
                throw new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        @Override public void close() { if (core != null) core.close(); }
    }
}
