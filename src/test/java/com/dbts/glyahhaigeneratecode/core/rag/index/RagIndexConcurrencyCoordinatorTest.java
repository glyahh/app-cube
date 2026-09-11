package com.dbts.glyahhaigeneratecode.core.rag.index;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagIndexConcurrencyCoordinatorTest {

    private final RagIndexConcurrencyCoordinator coordinator = new RagIndexConcurrencyCoordinator();

    @Test
    void executeIndex_serializesTheSameAppAndFile() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondAttempted = new CountDownLatch(1);
        CountDownLatch secondActionEntered = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> coordinator.executeIndex(
                    1L, "src/App.java", () -> trackConcurrency(active, maxActive, firstEntered, releaseFirst)), executor);
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> {
                secondAttempted.countDown();
                return coordinator.executeIndex(1L, "src/App.java", () -> {
                    secondActionEntered.countDown();
                    trackConcurrency(active, maxActive, null, null);
                });
            }, executor);

            assertTrue(secondAttempted.await(2, TimeUnit.SECONDS));
            assertFalse(secondActionEntered.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertTrue(second.get(2, TimeUnit.SECONDS));
        }

        assertTrue(maxActive.get() == 1);
    }

    @Test
    void executeIndex_whenActionThrows_releasesLocksForNextExecution() {
        assertThrows(IllegalStateException.class, () -> coordinator.executeIndex(
                1L, "src/App.java", () -> {
                    throw new IllegalStateException("failed");
                }));

        AtomicInteger executions = new AtomicInteger();
        assertTrue(coordinator.executeIndex(1L, "src/App.java", executions::incrementAndGet));
        assertTrue(executions.get() == 1);
    }

    @Test
    void executeIndex_allowsDifferentFilesToRunIndependently() throws Exception {
        CountDownLatch bothEntered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> coordinator.executeIndex(
                    1L, "src/A.java", () -> awaitTogether(bothEntered, release)), executor);
            CompletableFuture<Boolean> second = CompletableFuture.supplyAsync(() -> coordinator.executeIndex(
                    1L, "src/B.java", () -> awaitTogether(bothEntered, release)), executor);

            assertTrue(bothEntered.await(2, TimeUnit.SECONDS));
            release.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS));
            assertTrue(second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void executeAppDeletion_waitsForIndexThenAllowsCoordinatorReuse() throws Exception {
        CountDownLatch indexEntered = new CountDownLatch(1);
        CountDownLatch releaseIndex = new CountDownLatch(1);
        CountDownLatch deletionAttempted = new CountDownLatch(1);
        CountDownLatch deletionActionEntered = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Boolean> index = CompletableFuture.supplyAsync(() -> coordinator.executeIndex(
                    1L, "src/App.java", () -> {
                        executions.incrementAndGet();
                        indexEntered.countDown();
                        await(releaseIndex);
                    }), executor);
            assertTrue(indexEntered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> deletion = CompletableFuture.runAsync(() -> {
                deletionAttempted.countDown();
                coordinator.executeAppDeletion(1L, () -> {
                    deletionActionEntered.countDown();
                    executions.incrementAndGet();
                });
            }, executor);

            assertTrue(deletionAttempted.await(2, TimeUnit.SECONDS));
            assertFalse(deletionActionEntered.await(100, TimeUnit.MILLISECONDS));
            releaseIndex.countDown();
            assertTrue(index.get(2, TimeUnit.SECONDS));
            deletion.get(2, TimeUnit.SECONDS);
            assertTrue(deletionActionEntered.getCount() == 0);
        }

        assertTrue(coordinator.executeIndex(1L, "src/App.java", executions::incrementAndGet));
        assertTrue(executions.get() == 3);
        assertTrue(mapSize("appLocks") == 0);
        assertTrue(mapSize("fileLocks") == 0);
    }

    @Test
    void completedOperationsReleaseAllLockEntries() throws Exception {
        for (long appId = 1; appId <= 100; appId++) {
            coordinator.executeIndex(appId, "src/App.java", () -> { });
            coordinator.executeFileDeletion(appId, "src/App.java", () -> { });
            coordinator.executeAppDeletion(appId, () -> { });
        }

        assertTrue(mapSize("appLocks") == 0);
        assertTrue(mapSize("fileLocks") == 0);
    }

    @Test
    void failedAppDeletionStillReleasesLockEntry() throws Exception {
        assertThrows(IllegalStateException.class, () -> coordinator.executeAppDeletion(1L, () -> {
            throw new IllegalStateException("delete failed");
        }));

        assertTrue(mapSize("appLocks") == 0);
        assertTrue(coordinator.executeIndex(1L, "src/App.java", () -> { }));
    }

    private static void trackConcurrency(AtomicInteger active, AtomicInteger maxActive,
                                         CountDownLatch entered, CountDownLatch release) {
        int current = active.incrementAndGet();
        maxActive.accumulateAndGet(current, Math::max);
        if (entered != null) {
            entered.countDown();
        }
        if (release != null) {
            await(release);
        }
        active.decrementAndGet();
    }

    private static void awaitTogether(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        await(release);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private int mapSize(String fieldName) throws Exception {
        Field field = RagIndexConcurrencyCoordinator.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(coordinator)).size();
    }
}
