package dev.starsupply.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class EntityCallbacksTest {
    @Test
    void shutdownFinishesQueuedOperationsAndReleasesTheInventoryGuard() {
        var callbacks = new EntityCallbacks();
        CompletableFuture<String> queued = callbacks.register();
        var inventoryBusy = new AtomicBoolean(true);
        var completed = queued.whenComplete((value, failure) -> inventoryBusy.set(false));

        callbacks.cancelWaiting(new IllegalStateException("plugin disabled"));

        assertThrows(CompletionException.class, completed::join);
        assertFalse(inventoryBusy.get());
        callbacks.execute(queued, () -> fail("a late callback must not touch inventory"));
        assertTrue(callbacks.isIdle());
    }

    @Test
    void shutdownDoesNotCompleteAnInventoryWriteAlreadyInProgress() {
        var callbacks = new EntityCallbacks();
        CompletableFuture<String> running = callbacks.register();
        callbacks.execute(running, () -> {
            callbacks.cancelWaiting(new IllegalStateException("plugin disabled"));
            assertFalse(running.isDone());
            assertFalse(callbacks.isIdle(), "re-enable must wait for the inventory write to return");
            return "durable player receipt";
        });
        assertEquals("durable player receipt", running.join());
        assertTrue(callbacks.isIdle());
    }

    @Test
    void retiredOrRejectedTasksCannotLaterBeClaimed() {
        var callbacks = new EntityCallbacks();
        var retired = callbacks.register();
        retired.completeExceptionally(new IllegalArgumentException("player retired"));
        callbacks.execute(retired, () -> fail("a retired callback cannot execute"));
        callbacks.cancelWaiting(new IllegalStateException("plugin disabled"));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, retired::join).getCause());
        assertTrue(callbacks.isIdle());
    }

    @Test
    void oldCallbacksRemainCancelledWhenTheNextGenerationRegistersWork() {
        var callbacks = new EntityCallbacks();
        var previous = callbacks.register();
        callbacks.cancelWaiting(new IllegalStateException("plugin disabled"));
        CompletableFuture<String> current = callbacks.register();

        callbacks.execute(previous, () -> fail("an older generation cannot execute"));
        callbacks.execute(current, () -> "new generation");
        assertEquals("new generation", current.join());
        assertTrue(callbacks.isIdle());
    }

    @Test
    void shutdownRacingTheSchedulerEitherCancelsOrFinishesEachOperation() throws Exception {
        try (var threads = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                var callbacks = new EntityCallbacks();
                CompletableFuture<String> operation = callbacks.register();
                var start = new CountDownLatch(1);
                var invoked = new AtomicBoolean();
                var scheduler = threads.submit(() -> {
                    start.await();
                    callbacks.execute(operation, () -> {
                        invoked.set(true);
                        return "written";
                    });
                    return null;
                });
                var shutdown = threads.submit(() -> {
                    start.await();
                    callbacks.cancelWaiting(new IllegalStateException("plugin disabled"));
                    return null;
                });
                start.countDown();
                scheduler.get(5, TimeUnit.SECONDS);
                shutdown.get(5, TimeUnit.SECONDS);
                assertTrue(operation.isDone());
                assertEquals(!invoked.get(), operation.isCompletedExceptionally());
                callbacks.execute(operation, () -> fail("a callback only executes once"));
                assertTrue(callbacks.isIdle());
            }
        }
    }

    @Test
    void pendingCallbacksKeepTheGenerationBusyUntilRetired() {
        var callbacks = new EntityCallbacks();
        var queued = callbacks.register();
        assertFalse(callbacks.isIdle());
        queued.completeExceptionally(new IllegalStateException("player retired"));
        assertTrue(callbacks.isIdle());
    }

    @Test
    void cancellingARunningFutureDoesNotAllowReenableDuringItsInventoryWrite() {
        var callbacks = new EntityCallbacks();
        var running = callbacks.register();
        callbacks.execute(running, () -> {
            running.cancel(false);
            callbacks.cancelWaiting(new IllegalStateException("plugin disabled"));
            assertFalse(callbacks.isIdle());
            return "inventory write still completed";
        });
        assertTrue(running.isCancelled());
        assertTrue(callbacks.isIdle());
    }

    @Test
    void inlineCompletionChainsFinishBeforeTheGenerationBecomesIdle() {
        var callbacks = new EntityCallbacks();
        var operation = callbacks.register();
        var completed = operation.whenComplete((value, failure) -> {
            assertTrue(operation.isDone());
            assertFalse(callbacks.isIdle(), "completion can still enqueue SQL or release an inventory guard");
        });
        callbacks.execute(operation, () -> "durable receipt");
        assertEquals("durable receipt", completed.join());
        assertTrue(callbacks.isIdle());
    }

    @Test
    void fatalCallbackFailureCompletesTheResultAndReleasesRunningStateBeforePropagating() {
        var callbacks = new EntityCallbacks();
        var operation = callbacks.register();
        var guard = new AtomicBoolean(true);
        var completion = operation.whenComplete((value, failure) -> guard.set(false));
        var fatal = new AssertionError("custom inventory serializer failed");

        assertSame(fatal, assertThrows(AssertionError.class,
                () -> callbacks.execute(operation, () -> { throw fatal; })));
        assertSame(fatal, assertThrows(CompletionException.class, completion::join).getCause());
        assertFalse(guard.get());
        assertTrue(callbacks.isIdle());
    }
}
