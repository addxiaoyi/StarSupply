package dev.starsupply.bukkit;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Pending entity callbacks may be cancelled by plugin shutdown without a scheduler retirement call. */
final class EntityCallbacks {
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private final Set<CompletableFuture<?>> running = ConcurrentHashMap.newKeySet();

    <T> CompletableFuture<T> register() {
        var future = new CompletableFuture<T>();
        pending.add(future);
        future.whenComplete((value, failure) -> pending.remove(future));
        return future;
    }

    /** Claim before accessing a player. Shutdown leaves already running callbacks to finish. */
    private synchronized boolean claim(CompletableFuture<?> future) {
        if (!pending.remove(future) || future.isDone()) return false;
        running.add(future);
        return true;
    }

    <T> void execute(CompletableFuture<T> future, Callable<T> task) {
        if (!claim(future)) return;
        try {
            AsyncTasks.complete(future, task);
        } finally {
            synchronized (this) {
                running.remove(future);
            }
        }
    }

    /** Completion callbacks may still run inline after the result itself becomes done. */
    synchronized boolean isIdle() {
        return pending.isEmpty() && running.isEmpty();
    }

    void cancelWaiting(Throwable failure) {
        for (CompletableFuture<?> future : pending) {
            if (pending.remove(future)) future.completeExceptionally(failure);
        }
    }
}
