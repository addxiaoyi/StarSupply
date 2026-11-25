package dev.starsupply.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToDoubleFunction;
import org.junit.jupiter.api.Test;

class TemporaryMoraleBridgeTest {
    @Test
    void missingOrIncompatiblePublicAuthorityContractFailsClosed() {
        var missing = assertThrows(IllegalStateException.class, () -> TemporaryMoraleBridge.bind(new Object()));
        assertTrue(missing.getMessage().contains("StarCore compatibility patch"));
        assertThrows(IllegalStateException.class, () -> TemporaryMoraleBridge.bind(new InvalidAuthority()));
    }

    @Test
    void publicRegistrationChangesAuthorityViewAndUnregisterIsIdempotent() {
        Authority authority = new Authority();
        TemporaryMoraleBridge bridge = TemporaryMoraleBridge.bind(authority);
        UUID army = UUID.randomUUID();
        Runnable unregister = bridge.register("starsupply:logistics", ignored -> -10);
        assertEquals(90, bridge.effective(army));
        assertEquals(100, authority.baseMorale);
        unregister.run();
        unregister.run();
        assertEquals(100, bridge.effective(army));
        assertEquals(1, authority.removals.get());
        authority.baseMorale = Double.NaN;
        assertThrows(IllegalStateException.class, () -> bridge.effective(army));
    }

    @Test
    void authorityRegistrationFailureIsNotSilentlyIgnored() {
        Authority authority = new Authority();
        TemporaryMoraleBridge bridge = TemporaryMoraleBridge.bind(authority);
        bridge.register("starsupply:logistics", ignored -> -10);
        assertThrows(IllegalArgumentException.class, () -> bridge.register("starsupply:logistics", ignored -> -10));
    }

    public static final class Authority {
        double baseMorale = 100;
        ToDoubleFunction<UUID> contribution;
        AtomicInteger removals = new AtomicInteger();

        public Runnable registerTemporaryMoraleModifier(String owner, ToDoubleFunction<UUID> callback) {
            if (contribution != null) throw new IllegalArgumentException("duplicate owner");
            contribution = callback;
            return () -> { contribution = null; removals.incrementAndGet(); };
        }

        public double effectiveMorale(UUID armyId) {
            return baseMorale + (contribution == null ? 0 : contribution.applyAsDouble(armyId));
        }
    }

    public static final class InvalidAuthority {
        public String registerTemporaryMoraleModifier(String owner, ToDoubleFunction<UUID> callback) { return "bad"; }
        public double effectiveMorale(UUID armyId) { return 100; }
    }
}
