package dev.starsupply.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Uses only the patched public, receipt-backed StarCore logistics contract. */
final class NativeSupplyBridge {
    private final Object service;
    private final Method apply;
    private final Method lookup;
    private final Method requestId;
    private final Method armyId;
    private final Method nationId;
    private final Method requestedPoints;
    private final Method appliedPoints;

    static NativeSupplyBridge bind(Object service) {
        Objects.requireNonNull(service, "armyService");
        try {
            Method apply = service.getClass().getMethod("applyLogisticsSupply",
                    UUID.class, UUID.class, UUID.class, int.class);
            Method lookup = service.getClass().getMethod("logisticsSupplyReceipt", UUID.class);
            if (lookup.getReturnType() != Optional.class) throw new NoSuchMethodException("Missing receipt lookup");
            return new NativeSupplyBridge(service, apply, lookup);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("StarSupply requires the supplied StarCore compatibility patch with "
                    + "public ArmyService.applyLogisticsSupply/logisticsSupplyReceipt APIs", unavailable);
        }
    }

    private NativeSupplyBridge(Object service, Method apply, Method lookup) throws ReflectiveOperationException {
        this.service = service;
        this.apply = apply;
        this.lookup = lookup;
        Class<?> receipt = apply.getReturnType();
        requestId = getter(receipt, "requestId", UUID.class);
        armyId = getter(receipt, "armyId", UUID.class);
        nationId = getter(receipt, "nationId", UUID.class);
        requestedPoints = getter(receipt, "requestedPoints", int.class);
        appliedPoints = getter(receipt, "appliedPoints", int.class);
    }

    private static Method getter(Class<?> receipt, String name, Class<?> type) throws ReflectiveOperationException {
        Method method = receipt.getMethod(name);
        if (method.getReturnType() != type) throw new NoSuchMethodException("Incompatible receipt field " + name);
        return method;
    }

    boolean replenish(UUID request, UUID army, UUID nation, int points) {
        Objects.requireNonNull(request);
        Objects.requireNonNull(army);
        Objects.requireNonNull(nation);
        if (points < 1 || points > 10) throw new IllegalArgumentException("Invalid physical FOOD replenishment");
        // Always call apply, which confirms persistence even if an earlier call lost its response.
        Object receipt = invoke(apply, service, request, army, nation, points);
        if (receipt == null || !request.equals(invoke(requestId, receipt))
                || !army.equals(invoke(armyId, receipt)) || !nation.equals(invoke(nationId, receipt))
                || ((Number) invoke(requestedPoints, receipt)).intValue() != points) {
            throw new IllegalStateException("StarCore returned a mismatched logistics supply receipt");
        }
        int applied = ((Number) invoke(appliedPoints, receipt)).intValue();
        if (applied < 0 || applied > points) throw new IllegalStateException("Invalid applied supply points");
        return true;
    }

    Optional<CoreGateway.NativeSupplyReceipt> receipt(UUID request) {
        Objects.requireNonNull(request);
        Object result = invoke(lookup, service, request);
        if (!(result instanceof Optional<?> optional)) {
            throw new IllegalStateException("StarCore returned an invalid logistics receipt lookup");
        }
        return optional.map(receipt -> {
            UUID receiptRequest = (UUID) invoke(requestId, receipt);
            if (!request.equals(receiptRequest)) {
                throw new IllegalStateException("StarCore returned a receipt for a different request");
            }
            return new CoreGateway.NativeSupplyReceipt(receiptRequest, (UUID) invoke(armyId, receipt),
                    (UUID) invoke(nationId, receipt), ((Number) invoke(requestedPoints, receipt)).intValue(),
                    ((Number) invoke(appliedPoints, receipt)).intValue());
        });
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("StarCore logistics supply confirmation failed", cause);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("StarCore public logistics supply API is unavailable", unavailable);
        }
    }
}
