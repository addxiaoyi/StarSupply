package dev.starsupply.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncTasksTest {
    @Test
    void successfulTasksRetainTheirResultsIncludingNull() {
        var result = new CompletableFuture<String>();
        AsyncTasks.complete(result, () -> "committed");
        assertEquals("committed", result.join());
        var empty = new CompletableFuture<Void>();
        AsyncTasks.complete(empty, () -> null);
        assertNull(empty.join());
    }

    @Test
    void checkedAndLinkageFailuresCompleteWithoutEscapingTheTaskBoundary() {
        for (Throwable failure : new Throwable[]{new IOException("database unavailable"),
                new IllegalStateException("invalid state"), new NoClassDefFoundError("optional integration")}) {
            var result = new CompletableFuture<Object>();
            AsyncTasks.complete(result, () -> {
                if (failure instanceof Exception exception) throw exception;
                throw (LinkageError) failure;
            });
            assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        }
    }

    @Test
    void fatalWorkerFailureReleasesTheCallerAndStillDrainsAlreadyAcceptedTransactions() throws Exception {
        var uncaught = new AtomicReference<Throwable>();
        var observed = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fatal = new AssertionError("transaction assertion");
        var failed = new CompletableFuture<String>();
        var queued = new CompletableFuture<String>();
        var worker = Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "StarSupply-test-SQL");
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                uncaught.set(failure);
                observed.countDown();
            });
            return thread;
        });
        try {
            worker.execute(() -> AsyncTasks.complete(failed, () -> {
                started.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                throw fatal;
            }));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            worker.execute(() -> AsyncTasks.complete(queued, () -> "next transaction committed"));
            worker.shutdown();
            release.countDown();

            assertSame(fatal, assertThrows(CompletionException.class,
                    () -> failed.orTimeout(5, TimeUnit.SECONDS).join()).getCause());
            assertEquals("next transaction committed", queued.get(5, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(observed.await(5, TimeUnit.SECONDS));
            assertSame(fatal, uncaught.get(), "fatal errors must still reach the worker error handler");
        } finally {
            release.countDown();
            worker.shutdownNow();
        }
    }
}
