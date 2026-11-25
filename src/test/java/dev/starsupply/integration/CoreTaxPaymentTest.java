package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;
import static dev.starsupply.integration.CoreGateway.PaymentResult.*;
import dev.starcore.starcore.foundation.economy.transaction.TransactionFabric;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.*;
import dev.starcore.starcore.module.treasury.TaxationService.TaxQuote;
import dev.starsupply.api.ResourceType;
import dev.starsupply.api.SupplyModels.*;
import dev.starsupply.service.TariffFunding;
import dev.starsupply.storage.SupplyStore;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CoreTaxPaymentTest {
    @TempDir Path directory;
    private static final Logger LOG = Logger.getAnonymousLogger();
    private final UUID nation=UUID.randomUUID(), player=UUID.randomUUID(), id=UUID.randomUUID(), purchase=UUID.randomUUID();
    private final TaxAssessment tax=new TaxAssessment(id,"TARIFF",nation,player,new BigDecimal("100"),new BigDecimal("5"));
    static Stream<Boolean> authorities() {
        return Boolean.getBoolean("starsupply.test.actual-player-money") ? Stream.of(false,true) : Stream.of(false);
    }

    @ParameterizedTest @MethodSource("authorities")
    void lostFundingReplyKeepsPurchasePendingAndReopenPaysAndStocksOnlyOnce(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.taxes.loseReply.set(true);
            assertEquals(RETRY,h.pay());
            assertEquals("PENDING",h.store.settlement(id).orElseThrow().outcome());
            assertTrue(h.store.stock("depot").available().isEmpty());
            assertEquals(new BigDecimal("95.00"),h.money.balance(player));
            assertEquals(new BigDecimal("905.00"),h.treasury.treasury().balance(NationId.of(nation)));
            h.reopen();
            assertEquals(PAID,h.pay());
            h.store.finishSettlement(id,"PAID",1002,false);
            assertEquals(8L,h.store.stock("depot").available().get(ResourceType.FOOD));
            assertEquals(PAID,h.pay());
            assertEquals(new BigDecimal("95.00"),h.money.balance(player));
            assertEquals(new BigDecimal("905.00"),h.treasury.treasury().balance(NationId.of(nation)));
            assertEquals(1,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void localTaxResultRollbackRecoversSameAuthorityReceipt(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.sql("CREATE TRIGGER refuse_tax_result BEFORE UPDATE OF state ON settlement_tax WHEN NEW.state='FUNDED' BEGIN SELECT RAISE(ABORT,'fault'); END");
            assertEquals(RETRY,h.pay());
            assertEquals(SupplyStore.TaxState.FUNDING,h.store.taxState(id).orElseThrow());
            h.sql("DROP TRIGGER refuse_tax_result");
            h.reopen();
            assertEquals(PAID,h.pay());
            assertEquals(1,h.taxes.calls.get());
            assertEquals(new BigDecimal("905.00"),h.treasury.treasury().balance(NationId.of(nation)));
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void fabricWriteFailureAfterTaxCreditDoesNotFundTwice(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            Path blocked=directory.resolve("fabric.properties.tmp");
            h.taxes.afterFund=() -> { try { Files.createDirectory(blocked); } catch(Exception e) { throw new IllegalStateException(e); } };
            assertEquals(RETRY,h.pay());
            assertTrue(Files.isDirectory(blocked));
            Files.delete(blocked); h.taxes.afterFund=() -> {};
            h.reopen();
            assertEquals(PAID,h.pay());
            assertEquals(1,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void protocolIsSavedBeforeSqlFundingAndResumesIfSqlTransitionFails(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.sql("CREATE TRIGGER refuse_tax_start BEFORE UPDATE OF state ON settlement_tax WHEN NEW.state='FUNDING' BEGIN SELECT RAISE(ABORT,'fault'); END");
            assertEquals(RETRY,h.pay());
            assertTrue(h.adapter.usesReceipts(tax));
            assertEquals(0,h.taxes.calls.get());
            h.sql("DROP TRIGGER refuse_tax_start");
            h.reopen();
            assertEquals(PAID,h.pay());
            assertEquals(1,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void legacyFundingWindowNeverUpgradesAndExistingReviewStaysFrozen(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.store.beginTaxFunding(id,1000);
            assertFalse(h.funding.fund(tax));
            assertFalse(h.adapter.usesReceipts(tax));
            assertEquals(SupplyStore.TaxState.REVIEW,h.store.taxState(id).orElseThrow());
            h.reopen();
            assertFalse(h.funding.fund(tax));
            assertEquals(0,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void missingProviderAndConflictingQuoteCannotFallBack(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.adapter.prepare(tax);
            h.adapter=new CoreTaxPayment(h.fabric,null);
            assertFalse(h.funding.fund(tax));
            assertEquals(0,h.taxes.calls.get());
            h.reopen();
            assertThrows(IllegalArgumentException.class,() -> h.adapter.prepare(new TaxAssessment(id,"TARIFF",nation,
                    UUID.randomUUID(),tax.grossAmount(),tax.taxAmount())));
            assertEquals(0,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void badAuthoritativeIdentityAndMissingTerminalReceiptStayFrozen(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.taxes.wrongQuote=true;
            assertEquals(REVIEW,h.pay());
            assertEquals(new BigDecimal("95.00"),h.money.balance(player));
            assertEquals(new BigDecimal("905.00"),h.treasury.treasury().balance(NationId.of(nation)));
            h.taxes.wrongQuote=false;
            assertTrue(h.adapter.fund(tax));
            h.taxes.hideReceipt=true;
            assertFalse(h.funding.fund(tax));
            assertEquals(1,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void operatorFlagsCannotManufactureFundingOrRepeatAnAppliedTax(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.taxes.unavailable=true;
            assertEquals(RETRY,h.pay());
            assertEquals(RETRY,h.adapter.reconcile(h.payment,tax,true,h.funding::fund));
            assertEquals(new BigDecimal("900.00"),h.treasury.treasury().balance(NationId.of(nation)));
            h.taxes.unavailable=false;
            h.taxes.loseReply.set(true);
            assertEquals(RETRY,h.adapter.reconcile(h.payment,tax,true,h.funding::fund));
            assertEquals(PAID,h.adapter.reconcile(h.payment,tax,false,h.funding::fund));
            assertEquals(new BigDecimal("905.00"),h.treasury.treasury().balance(NationId.of(nation)));
            assertEquals(new BigDecimal("95.00"),h.money.balance(player));
            assertEquals(1,h.taxes.calls.get());
        }
    }

    @ParameterizedTest @MethodSource("authorities")
    void authorityUnavailableBeforeCreditRetainsOriginalPendingIntent(boolean real) throws Exception {
        try(Harness h=new Harness(real)) {
            h.taxes.unavailable=true;
            assertEquals(RETRY,h.pay());
            assertEquals(SupplyStore.TaxState.FUNDING,h.store.taxState(id).orElseThrow());
            assertEquals(new BigDecimal("900.00"),h.treasury.treasury().balance(NationId.of(nation)));
            h.taxes.unavailable=false;
            h.reopen();
            assertEquals(PAID,h.pay());
            assertEquals(1,h.taxes.calls.get());
        }
    }

    final class Harness implements AutoCloseable {
        final ReceiptTreasuryFixture treasury;
        final CorePlayerMoneyTest.PublicMoney money;
        final PublicTaxes taxes;
        SupplyStore store; TransactionFabric fabric; CoreTaxPayment adapter; CorePayment payment; TariffFunding funding;
        Harness(boolean real) throws Exception {
            treasury=new ReceiptTreasuryFixture(directory.resolve("treasury.db"));
            assertTrue(treasury.treasury().tryDeposit(NationId.of(nation),new BigDecimal("1000")));
            money=new CorePlayerMoneyTest.PublicMoney(real,directory.resolve("players.db"));
            taxes=new PublicTaxes(real,directory.resolve("tax-authority.db"),treasury.credits());
            reopen();
            store.addWarehouse(new Warehouse("depot",nation,WarehouseKind.DEPOT,new Position("world",0,64,0),null));
            store.addPurchase(new Purchase(purchase,nation,"depot",ResourceType.FOOD,8,tax.grossAmount(),PurchaseState.OPEN,null,null,1000));
            store.prepareTransfer(id,purchase,player,UUID.randomUUID(),UUID.randomUUID(),8,8,tax,
                    "a".repeat(64),"b".repeat(64),List.of(new SupplyStore.Lot("minecraft:bread","BASE",1,8,8,new byte[]{1})),1000);
            store.confirmTaken(id,1001);
        }
        void reopen() throws Exception {
            if(store!=null) store.close();
            store=new SupplyStore(directory.resolve("supply.db"));
            fabric=new TransactionFabric(directory.resolve("fabric.properties"),LOG);
            taxes.reopen(); money.reopen();
            adapter=new CoreTaxPayment(fabric,taxes);
            payment=new CorePayment((NationSpendingService)treasury.credits(),fabric,
                    (i,a)->{fail("Legacy player mutation"); return false;},i->true,LOG,PlayerMoneyBridge.bindOptional(money));
            CoreGateway gateway=(CoreGateway)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{CoreGateway.class},(p,m,a)->switch(m.getName()) {
                case "prepareTariffReceipts" -> adapter.prepare((TaxAssessment)a[0]);
                case "tariffUsesReceipts" -> adapter.usesReceipts((TaxAssessment)a[0]);
                case "fundTariffReceipt" -> adapter.fund((TaxAssessment)a[0]);
                case "fundTariff" -> throw new AssertionError("Legacy tariff must not be called");
                default -> throw new UnsupportedOperationException(m.getName());
            });
            funding=new TariffFunding(store,gateway,()->1002,LOG);
        }
        CoreGateway.PaymentResult pay() { return payment.pay(id,nation,player,tax.grossAmount(),tax,funding::fund); }
        void sql(String sql) throws Exception { try(var c=DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("supply.db"));var s=c.createStatement()) { s.execute(sql); } }
        @Override public void close() throws Exception { store.close(); money.close(); treasury.close(); }
    }

    public record Receipt(TaxQuote quote,Instant fundedAt) {}
    public static final class PublicTaxes {
        final boolean real; final Path file; final NationCreditService credits;
        final Map<UUID,Receipt> receipts=new HashMap<>(); final AtomicInteger calls=new AtomicInteger();
        final AtomicBoolean loseReply=new AtomicBoolean(); boolean wrongQuote,hideReceipt,unavailable;
        Object provider; Runnable afterFund=()->{};
        PublicTaxes(boolean real,Path file,NationCreditService credits) throws Exception { this.real=real;this.file=file;this.credits=credits;reopen(); }
        void reopen() throws Exception { if(real) provider=new ReceiptTaxFixture(file,credits).service(); }
        public Receipt fundTaxWithReceipt(TaxQuote quote) {
            if(unavailable) throw new IllegalStateException("unavailable");
            calls.incrementAndGet(); Receipt r;
            if(real) r=convert(invoke("fundTaxWithReceipt",new Class<?>[]{TaxQuote.class},quote));
            else {
                r=receipts.get(quote.transactionId());
                if(r==null) {
                    var result=credits.credit(new NationCreditService.CreditRequest("test-tax:"+quote.transactionId(),quote.nationId(),quote.taxAmount(),NationCreditService.CreditCategory.OTHER,quote.toString()));
                    assertTrue(result.applied());
                    r=new Receipt(quote,Instant.now()); receipts.put(quote.transactionId(),r);
                } else if(!r.quote().equals(quote)) throw new IllegalArgumentException("identity");
            }
            afterFund.run();
            if(loseReply.getAndSet(false)) throw new IllegalStateException("reply lost after tax credit");
            return alter(r);
        }
        public Optional<Receipt> taxFundingReceipt(UUID id) {
            if(unavailable) throw new IllegalStateException("unavailable");
            if(hideReceipt) return Optional.empty();
            Optional<Receipt> r=real?((Optional<?>)invoke("taxFundingReceipt",new Class<?>[]{UUID.class},id)).map(PublicTaxes::convert):Optional.ofNullable(receipts.get(id));
            return r.map(this::alter);
        }
        private Receipt alter(Receipt r) { return wrongQuote ? new Receipt(new TaxQuote(r.quote().transactionId(),r.quote().nationId(),r.quote().type(),UUID.randomUUID(),r.quote().taxableAmount(),r.quote().taxAmount()),r.fundedAt()):r; }
        private Object invoke(String method,Class<?>[] types,Object... args) {
            try { return provider.getClass().getMethod(method,types).invoke(provider,args); }
            catch(InvocationTargetException e) {
                if(e.getCause() instanceof RuntimeException r) throw r;
                if(e.getCause() instanceof Error error) throw error;
                throw new IllegalStateException(e.getCause());
            } catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
        private static Receipt convert(Object r) {
            try { return new Receipt((TaxQuote)r.getClass().getMethod("quote").invoke(r),(Instant)r.getClass().getMethod("fundedAt").invoke(r)); }
            catch(ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }
}
