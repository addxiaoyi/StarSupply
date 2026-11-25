package dev.starsupply.integration;

import dev.starcore.starcore.foundation.economy.transaction.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static dev.starcore.starcore.foundation.economy.transaction.EconomyTransactionState.*;

/** Public compatibility API only; balances and receipts remain entirely owned by Core. */
final class PlayerMoneyBridge {
    static final String PROTOCOL = "playerMoneyProtocol";
    static final String VERSION = "1";
    private final Object service;
    private final Method apply, lookup, receiptRequest, status, before, after, recordedAt;
    private final Method operationId, accountId, direction, amount, source;

    static PlayerMoneyBridge bindOptional(Object service) {
        Objects.requireNonNull(service);
        Method apply;
        try {
            apply = service.getClass().getMethod("applyMoney", UUID.class, UUID.class, String.class,
                    BigDecimal.class, String.class);
        } catch (NoSuchMethodException legacy) { return null; }
        try { return new PlayerMoneyBridge(service, apply); }
        catch (ReflectiveOperationException invalid) {
            throw new IllegalStateException("Incompatible public Core player-money receipt API", invalid);
        }
    }

    private PlayerMoneyBridge(Object service, Method apply) throws ReflectiveOperationException {
        this.service = service; this.apply = apply;
        lookup = getter(service.getClass(), "moneyReceipt", Optional.class, UUID.class);
        Class<?> receipt = apply.getReturnType();
        receiptRequest = receipt.getMethod("request");
        status = receipt.getMethod("status");
        if (!status.getReturnType().isEnum()) throw new NoSuchMethodException("Receipt status must be an enum");
        before = getter(receipt, "balanceBefore", BigDecimal.class);
        after = getter(receipt, "balanceAfter", BigDecimal.class);
        recordedAt = getter(receipt, "recordedAt", long.class);
        Class<?> request = receiptRequest.getReturnType();
        operationId = getter(request, "operationId", UUID.class);
        accountId = getter(request, "accountId", UUID.class);
        direction = request.getMethod("direction");
        if (!direction.getReturnType().isEnum()) throw new NoSuchMethodException("Receipt direction must be an enum");
        amount = getter(request, "amount", BigDecimal.class);
        source = getter(request, "source", String.class);
    }

    /** Advances a marked request only from its authoritative terminal result. */
    TransactionRecord advance(TransactionFabric ledger, TransactionRequest request, String debitOrCredit) {
        if (!VERSION.equals(request.metadata().get(PROTOCOL))) throw new IllegalArgumentException("Unknown player money protocol");
        TransactionRecord record = ledger.begin(request);
        if (record.state() == MANUAL_REVIEW) return record;
        if (record.state() == CREATED) record = ledger.transition(record.transactionId(), RESERVED,
                "player money requested with durable Core receipt");
        UUID id = id(request.idempotencyKey());
        String identity = identity(request);
        Optional<Boolean> existing = receipt(id, request.accountId(), debitOrCredit, request.amount(), identity);
        boolean applied;
        if (existing.isEmpty()) {
            if (record.state() != RESERVED) throw new IllegalArgumentException("Missing receipt for recorded player money result");
            applied = validate(invoke(apply, service, id, request.accountId(), debitOrCredit, request.amount(), identity),
                    id, request.accountId(), debitOrCredit, request.amount(), identity);
        } else applied = existing.get();
        if (applied) {
            if (record.state() == CANCELLED || record.state() == DEBIT_FAILED) {
                throw new IllegalArgumentException("Applied player receipt conflicts with cancelled transaction");
            }
            if (record.state() == RESERVED) record = ledger.transition(record.transactionId(), DEBITED, "Core player receipt applied");
            if (record.state() == DEBITED) record = ledger.transition(record.transactionId(), DELIVERED, "Core player result confirmed");
            if (record.state() == DELIVERED) record = ledger.transition(record.transactionId(), COMMITTED, "Core player receipt committed");
        } else {
            if (record.state() == RESERVED) record = ledger.transition(record.transactionId(), DEBIT_FAILED, "Core player receipt rejected");
            if (record.state() == DEBIT_FAILED) record = ledger.transition(record.transactionId(), CANCELLED, "Core rejected original player money request");
            if (record.state() != CANCELLED) throw new IllegalArgumentException("Rejected player receipt conflicts with local success");
        }
        return record;
    }

    static UUID id(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }

    private static String identity(TransactionRequest request) {
        // Hash all immutable metadata so arbitrary tax/config labels cannot exceed Core's source bound.
        String metadata = new java.util.TreeMap<>(request.metadata()).entrySet().stream()
                .map(e -> e.getKey().length() + ":" + e.getKey() + e.getValue().length() + ":" + e.getValue())
                .collect(java.util.stream.Collectors.joining());
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8));
            return request.reason() + " key=" + request.idempotencyKey() + " metadata=" + java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Optional<Boolean> receipt(UUID id, UUID account, String kind, BigDecimal value, String identity) {
        Object result = invoke(lookup, service, id);
        if (!(result instanceof Optional<?> found)) throw new IllegalArgumentException("Invalid Core player receipt lookup");
        return found.map(r -> validate(r, id, account, kind, value, identity));
    }

    private boolean validate(Object receipt, UUID id, UUID account, String kind, BigDecimal value, String identity) {
        if (receipt == null) throw new IllegalArgumentException("Missing Core player receipt");
        Object request = invoke(receiptRequest, receipt);
        if (!id.equals(invoke(operationId, request)) || !account.equals(invoke(accountId, request))
                || !kind.equals(((Enum<?>) invoke(direction, request)).name())
                || value.compareTo((BigDecimal) invoke(amount, request)) != 0
                || !identity.equals(invoke(source, request))) throw new IllegalArgumentException("Mismatched Core player receipt identity");
        String result = ((Enum<?>) invoke(status, receipt)).name();
        if (!java.util.Set.of("APPLIED", "INSUFFICIENT_FUNDS", "EXTENSION_REJECTED", "BALANCE_LIMIT").contains(result)) {
            throw new IllegalArgumentException("Unknown Core player receipt status");
        }
        BigDecimal previous = (BigDecimal) invoke(before, receipt), next = (BigDecimal) invoke(after, receipt);
        BigDecimal expected = !result.equals("APPLIED") ? previous : kind.equals("CREDIT") ? previous.add(value) : previous.subtract(value);
        if (previous.signum() < 0 || next.signum() < 0 || expected.compareTo(next) != 0
                || ((Long) invoke(recordedAt, receipt)) <= 0) throw new IllegalArgumentException("Invalid Core player receipt balance proof");
        return result.equals("APPLIED");
    }

    private static Method getter(Class<?> type, String name, Class<?> returns, Class<?>... parameters) throws ReflectiveOperationException {
        Method method = type.getMethod(name, parameters);
        if (method.getReturnType() != returns) throw new NoSuchMethodException("Incompatible receipt field " + name);
        return method;
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) {
        try { return method.invoke(receiver, arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Core player-money operation unavailable", failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Core public player-money API unavailable", failure); }
    }
}
