package dev.starsupply.bukkit;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

/** Every accepted task completes its result, including failures that terminate its worker thread. */
final class AsyncTasks {
    private AsyncTasks() {}

    static <T> void complete(CompletableFuture<T> future, Callable<T> task) {
        try {
            future.complete(task.call());
        } catch (Exception | LinkageError failure) {
            future.completeExceptionally(failure);
        } catch (Error failure) {
            // Preserve the executor/server's fatal-error handling after releasing dependent guards.
            future.completeExceptionally(failure);
            throw failure;
        }
    }
}
