package dev.starsupply.bukkit;

/**
 * Guards callbacks queued on an entity scheduler across plugin disable/re-enable cycles.
 * A callback from an older generation must never touch the newly opened stores.
 */
final class LifecycleGate {
    private LifecycleGate() {
    }

    static boolean accepts(boolean accepting, long currentGeneration, long callbackGeneration) {
        return accepting && currentGeneration == callbackGeneration;
    }
}
