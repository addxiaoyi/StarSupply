package dev.starsupply.bukkit;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Inventory ownership lasts until the actual pipeline finishes, independently of its observer. */
final class InventoryTasks {
    private InventoryTasks() {}

    static <T> CompletionStage<T> start(Set<UUID> busy, UUID player, Supplier<CompletionStage<T>> start) {
        if (!busy.add(player)) return CompletableFuture.failedFuture(
                new IllegalArgumentException("背包操作正在处理，请稍后重试"));
        try {
            var observer = new CompletableFuture<T>();
            // Never expose the dependent stage owning cleanup: cancelling or completing that
            // stage early lets CompletableFuture skip its whenComplete action entirely.
            start.get().whenComplete((value, failure) -> {
                busy.remove(player);
                if (failure == null) observer.complete(value);
                else observer.completeExceptionally(failure);
            });
            return observer;
        } catch (RuntimeException | Error failure) {
            busy.remove(player);
            throw failure;
        }
    }
}
