package com.dbts.glyahhaigeneratecode.core.memory;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppGenerationCoordinatorTest {

    private final AppGenerationCoordinator coordinator = new AppGenerationCoordinator();

    @Test
    void serialize_sameAppWaitsUntilFirstFluxCompletes() throws Exception {
        PendingFlux first = subscribePending(1L);
        CountDownLatch secondEntered = new CountDownLatch(1);
        Disposable second = coordinator.serialize(1L, () -> {
                    secondEntered.countDown();
                    return Flux.just("second");
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        assertFalse(secondEntered.await(150, TimeUnit.MILLISECONDS));
        first.sink().complete();
        assertTrue(secondEntered.await(2, TimeUnit.SECONDS));

        first.disposable().dispose();
        second.dispose();
        awaitLockCleanup();
    }

    @Test
    void serialize_releasesSameAppAfterError() throws Exception {
        PendingFlux first = subscribePending(2L);
        first.sink().error(new IllegalStateException("failed"));

        assertNextExecutionEnters(2L);
    }

    @Test
    void serialize_releasesSameAppAfterCancellation() throws Exception {
        PendingFlux first = subscribePending(3L);
        first.disposable().dispose();

        assertNextExecutionEnters(3L);
    }

    @Test
    void serialize_differentAppsCanRunConcurrently() throws Exception {
        PendingFlux first = subscribePending(4L);
        CountDownLatch otherEntered = new CountDownLatch(1);
        Disposable other = coordinator.serialize(5L, () -> {
                    otherEntered.countDown();
                    return Flux.never();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        assertTrue(otherEntered.await(2, TimeUnit.SECONDS));
        first.disposable().dispose();
        other.dispose();
        awaitLockCleanup();
    }

    @Test
    void serializeBlocking_usesSameAppLockAndReturnsResult() {
        assertEquals("done", coordinator.serializeBlocking(6L, () -> "done"));
        assertEquals(0, lockCount());
    }

    private PendingFlux subscribePending(Long appId) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicReference<FluxSink<String>> sinkRef = new AtomicReference<>();
        Disposable disposable = coordinator.serialize(appId, () -> Flux.<String>create(sink -> {
                    sinkRef.set(sink);
                    entered.countDown();
                }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> { }, ignored -> { });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        return new PendingFlux(disposable, sinkRef.get());
    }

    private void assertNextExecutionEnters(Long appId) throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        Disposable next = coordinator.serialize(appId, () -> {
                    entered.countDown();
                    return Flux.empty();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        next.dispose();
        awaitLockCleanup();
    }

    private void awaitLockCleanup() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (lockCount() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(0, lockCount());
    }

    private int lockCount() {
        Map<?, ?> locks = (Map<?, ?>) ReflectionTestUtils.getField(coordinator, "appLocks");
        return locks == null ? 0 : locks.size();
    }

    private record PendingFlux(Disposable disposable, FluxSink<String> sink) {
    }
}
