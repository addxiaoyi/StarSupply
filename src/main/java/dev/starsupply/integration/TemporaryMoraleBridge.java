package dev.starsupply.integration;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToDoubleFunction;

/** Version bridge using only StarCore's public temporary morale service contract. */
final class TemporaryMoraleBridge {
    private final Object armyService;
    private final Method register;
    private final Method effective;

    static TemporaryMoraleBridge bind(Object armyService) {
        Objects.requireNonNull(armyService, "armyService");
        try {
            Method register = armyService.getClass().getMethod("registerTemporaryMoraleModifier",
                    String.class, ToDoubleFunction.class);
            Method effective = armyService.getClass().getMethod("effectiveMorale", UUID.class);
            if (!Runnable.class.isAssignableFrom(register.getReturnType()) || effective.getReturnType() != double.class) {
                throw new NoSuchMethodException("Incompatible temporary morale signatures");
            }
            return new TemporaryMoraleBridge(armyService, register, effective);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("StarSupply requires the supplied StarCore compatibility patch with "
                    + "public ArmyService.registerTemporaryMoraleModifier/effectiveMorale APIs; "
                    + "the unpatched core cannot apply the required logistics morale effect.", unavailable);
        }
    }

    private TemporaryMoraleBridge(Object armyService, Method register, Method effective) {
        this.armyService = armyService;
        this.register = register;
        this.effective = effective;
    }

    Runnable register(String owner, ToDoubleFunction<UUID> contribution) {
        Runnable unregister = (Runnable) invoke(register, owner, contribution);
        if (unregister == null) throw new IllegalStateException("StarCore returned no morale registration handle");
        AtomicBoolean removed = new AtomicBoolean();
        return () -> {
            if (removed.compareAndSet(false, true)) unregister.run();
        };
    }

    double effective(UUID armyId) {
        double value = ((Number) invoke(effective, armyId)).doubleValue();
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalStateException("StarCore returned invalid effective army morale");
        }
        return value;
    }

    private Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(armyService, arguments);
        } catch (InvocationTargetException authorityFailure) {
            Throwable cause = authorityFailure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("StarCore public morale operation failed", cause);
        } catch (ReflectiveOperationException unavailable) {
            throw new IllegalStateException("StarCore public morale API is unavailable", unavailable);
        }
    }
}
