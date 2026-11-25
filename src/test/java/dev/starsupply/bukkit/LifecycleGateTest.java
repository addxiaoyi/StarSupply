package dev.starsupply.bukkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LifecycleGateTest {
    @Test
    void rejectsCallbacksAfterDisableOrGenerationChange() {
        assertTrue(LifecycleGate.accepts(true, 7, 7));
        assertFalse(LifecycleGate.accepts(false, 7, 7));
        assertFalse(LifecycleGate.accepts(true, 8, 7));
    }
}
