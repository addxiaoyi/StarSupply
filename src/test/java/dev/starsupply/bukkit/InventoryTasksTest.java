package dev.starsupply.bukkit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class InventoryTasksTest {
    @Test
    void cancellingTheApiResultKeepsOwnershipUntilTheInventoryPipelineCompletes() {
        var busy = ConcurrentHashMap.<UUID>newKeySet();
        UUID player = UUID.randomUUID();
        var pipeline = new CompletableFuture<String>();
        var result = InventoryTasks.start(busy, player, () -> pipeline).toCompletableFuture();
        assertTrue(result.cancel(false));
        assertFalse(pipeline.isDone(), "a caller cannot undo an in-flight physical inventory transaction");
        assertTrue(busy.contains(player));
        var competing = InventoryTasks.start(busy, player, () -> fail("no overlapping inventory mutation"));
        assertInstanceOf(IllegalArgumentException.class,
                assertThrows(CompletionException.class, () -> competing.toCompletableFuture().join()).getCause());

        pipeline.complete("receipt persisted");

        assertTrue(result.isCancelled());
        assertTrue(busy.isEmpty(), "cancelling the observer must not suppress the cleanup callback");
        assertEquals("next transaction", InventoryTasks.start(busy, player,
                () -> CompletableFuture.completedFuture("next transaction")).toCompletableFuture().join());
        assertTrue(busy.isEmpty());
    }

    @Test
    void completingTheApiResultEarlyCannotStrandTheGuardOnPipelineFailure() {
        var busy = ConcurrentHashMap.<UUID>newKeySet();
        UUID player = UUID.randomUUID();
        for (boolean exceptional : new boolean[] {false, true}) {
            var pipeline = new CompletableFuture<String>();
            var result = InventoryTasks.start(busy, player, () -> pipeline).toCompletableFuture();
            if (exceptional) result.completeExceptionally(new IllegalStateException("observer timed out"));
            else result.complete("observer override");
            assertTrue(busy.contains(player));
            assertFalse(pipeline.isDone());

            pipeline.completeExceptionally(new AssertionError("physical save failed"));

            assertTrue(busy.isEmpty(), "completion of the public observer must not suppress cleanup");
        }
    }

    @Test
    void originalPipelineFailureIsPreservedAndReleasesOwnershipBeforeObserversRun() {
        var busy = ConcurrentHashMap.<UUID>newKeySet();
        UUID player = UUID.randomUUID();
        var pipeline = new CompletableFuture<String>();
        var failure = new AssertionError("durable receipt write failed");
        var result = InventoryTasks.start(busy, player, () -> pipeline).toCompletableFuture();
        var observation = result.handle((value, error) -> {
            assertTrue(busy.isEmpty());
            assertSame(failure, error instanceof CompletionException ? error.getCause() : error);
            return InventoryTasks.start(busy, player, () -> CompletableFuture.completedFuture("recovery"))
                    .toCompletableFuture().join();
        });
        pipeline.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        assertEquals("recovery", observation.join());
        assertTrue(busy.isEmpty());
    }

    @Test
    void successReleasesOnlyItsPlayerBeforeCompletion() {
        var busy = ConcurrentHashMap.<UUID>newKeySet();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var firstPipeline = new CompletableFuture<String>();
        var secondPipeline = new CompletableFuture<String>();
        var firstResult = InventoryTasks.start(busy, first, () -> firstPipeline).toCompletableFuture();
        var secondResult = InventoryTasks.start(busy, second, () -> secondPipeline).toCompletableFuture();
        var observation = firstResult.thenRun(() -> {
            assertFalse(busy.contains(first));
            assertTrue(busy.contains(second));
        });
        firstPipeline.complete("stored");
        assertEquals("stored", firstResult.join());
        observation.join();
        secondPipeline.cancel(false);
        assertTrue(secondResult.isCompletedExceptionally());
        assertTrue(busy.isEmpty());
    }

    @Test
    void synchronousStartupFailuresReleaseTheGuardAndKeepTheOriginalFailure() {
        var busy = ConcurrentHashMap.<UUID>newKeySet();
        UUID player = UUID.randomUUID();
        var fatal = new AssertionError("cannot create plan");
        var rejected = new IllegalArgumentException("invalid inventory");
        assertSame(fatal, assertThrows(AssertionError.class,
                () -> InventoryTasks.start(busy, player, () -> { throw fatal; })));
        assertTrue(busy.isEmpty());
        assertSame(rejected, assertThrows(IllegalArgumentException.class,
                () -> InventoryTasks.start(busy, player, () -> { throw rejected; })));
        assertTrue(busy.isEmpty());
    }
}
