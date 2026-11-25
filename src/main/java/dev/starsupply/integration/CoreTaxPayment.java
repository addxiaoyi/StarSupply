package dev.starsupply.integration;

import dev.starcore.starcore.foundation.economy.transaction.*;
import dev.starcore.starcore.module.nation.model.NationId;
import dev.starcore.starcore.module.treasury.TaxationService.TaxQuote;
import dev.starcore.starcore.module.treasury.TaxationService.TaxType;
import dev.starsupply.api.SupplyModels.TaxAssessment;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import static dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState.*;

/** Public API bridge, compiled against the pinned Core and bound to immutable tax intents. */
final class CoreTaxPayment {
    private static final String PROTOCOL = "taxFundingProtocol";
    private final TransactionFabric ledger;
    private final Object service;
    private final Method fund, lookup, receiptQuote, fundedAt;

    CoreTaxPayment(TransactionFabric ledger, Object service) {
        this.ledger = ledger; this.service = service;
        Method method;
        try { method = service == null ? null : service.getClass().getMethod("fundTaxWithReceipt", TaxQuote.class); }
        catch (NoSuchMethodException legacy) { method = null; }
        fund = method;
        if (fund == null) { lookup = null; receiptQuote = null; fundedAt = null; return; }
        try {
            lookup = service.getClass().getMethod("taxFundingReceipt", java.util.UUID.class);
            receiptQuote = fund.getReturnType().getMethod("quote");
            fundedAt = fund.getReturnType().getMethod("fundedAt");
            if (lookup.getReturnType() != Optional.class || receiptQuote.getReturnType() != TaxQuote.class
                    || fundedAt.getReturnType() != Instant.class) throw new NoSuchMethodException("Tax receipt signature");
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Incompatible Core tax receipt API", incompatible);
        }
    }

    synchronized boolean usesReceipts(TaxAssessment tax) {
        TransactionRecord record = ledger.findByIdempotencyKey(key(tax)).orElse(null);
        if (record == null) return false;
        if (!"1".equals(record.metadata().get(PROTOCOL))) throw new IllegalArgumentException("Unknown tax intent protocol");
        ledger.begin(request(tax)); // Validate immutable identity without changing the recorded state.
        return true;
    }

    synchronized boolean prepare(TaxAssessment tax) {
        if (usesReceipts(tax)) return true;
        if (fund == null) return false;
        ledger.begin(request(tax));
        return true;
    }

    CoreGateway.PaymentResult reconcile(CorePayment payments, TaxAssessment tax, boolean alreadyFunded,
                                         java.util.function.Predicate<TaxAssessment> funding) {
        return payments.reconcileTax(tax.settlementId(), tax, alreadyFunded && !usesReceipts(tax), funding);
    }

    synchronized boolean fund(TaxAssessment tax) {
        if (!usesReceipts(tax) || fund == null) throw new IllegalArgumentException("Marked tax authority is unavailable");
        TransactionRecord record = ledger.begin(request(tax));
        if (record.state() == CREATED) record = ledger.transition(record.transactionId(), RESERVED, "Core tax receipt requested");
        if (record.state() != RESERVED && record.state() != DEBITED && record.state() != DELIVERED
                && record.state() != COMMITTED) throw new IllegalArgumentException("Tax intent cannot be replayed");
        TaxQuote quote = quote(tax);
        Object answer = invoke(lookup, tax.settlementId());
        if (!(answer instanceof Optional<?> stored)) throw new IllegalArgumentException("Invalid tax receipt lookup");
        Object receipt;
        if (stored.isPresent()) receipt = stored.get();
        else {
            if (record.state() != RESERVED) throw new IllegalArgumentException("Missing previously confirmed tax receipt");
            receipt = invoke(fund, quote);
        }
        if (receipt == null || !quote.equals(invokeOn(receiptQuote, receipt))
                || !((Instant) invokeOn(fundedAt, receipt)).isAfter(Instant.EPOCH)) {
            throw new IllegalArgumentException("Tax receipt identity does not match original quote");
        }
        if (record.state() == RESERVED) record = ledger.transition(record.transactionId(), DEBITED, "Core tax credited");
        if (record.state() == DEBITED) record = ledger.transition(record.transactionId(), DELIVERED, "Tax receipt confirmed");
        if (record.state() == DELIVERED) ledger.transition(record.transactionId(), COMMITTED, "Tax funding committed");
        return true;
    }

    private static TaxQuote quote(TaxAssessment t) {
        return new TaxQuote(t.settlementId(), NationId.of(t.nationId()), TaxType.valueOf(t.type()),
                t.payerId(), t.grossAmount(), t.taxAmount());
    }
    private static String key(TaxAssessment t) { return "starsupply:tax:" + t.settlementId(); }
    private static TransactionRequest request(TaxAssessment t) {
        return new TransactionRequest(t.nationId(), t.taxAmount(), RefundOwner.WAR, TransactionType.TRADE,
                "StarSupply withheld tariff", key(t), Map.of(PROTOCOL, "1", "settlementId", t.settlementId().toString(),
                "payer", t.payerId().toString(), "gross", t.grossAmount().toPlainString(), "type", t.type()));
    }
    private Object invoke(Method method, Object... arguments) { return invokeOn(method, service, arguments); }
    private static Object invokeOn(Method method, Object receiver, Object... arguments) {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) throw runtime;
            if (e.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Core tax operation failed", e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Core tax receipt API unavailable", e); }
    }
}
