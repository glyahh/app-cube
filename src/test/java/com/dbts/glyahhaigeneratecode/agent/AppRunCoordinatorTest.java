package com.dbts.glyahhaigeneratecode.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 串行化协调器的行为契约。
 *
 * <p>这个组件取代旧链路的 {@code AppGenerationCoordinator} —— 它随 {@code core/memory/}
 * 被整包清掉过一轮，结果是同一 app 的并发 run 直接并跑（实测 3 个请求全部并行）。
 * 那批测试存在的意义就是这条不能再丢。
 */
@DisplayName("AppRunCoordinator：同一 app 串行、不同 app 并行")
class AppRunCoordinatorTest {

    private final AppRunCoordinator coordinator = new AppRunCoordinator();

    /** 探测并发峰值。串行时峰值只能是 1。 */
    private static final class Probe {
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        /** 造一个持续一小段时间的流，让重叠真的有机会发生。 */
        Flux<String> slow(String tag) {
            return Flux.defer(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                return Flux.just(tag)
                        .delayElements(Duration.ofMillis(120))
                        .doFinally(signal -> running.decrementAndGet());
            });
        }
    }

    @Test
    @DisplayName("同一 appId 的两个流不并行")
    void sameAppRunsSerial() {
        Probe probe = new Probe();
        Flux<String> a = coordinator.serialize(1L, () -> probe.slow("A"));
        Flux<String> b = coordinator.serialize(1L, () -> probe.slow("B"));

        // merge 才会同时订阅 —— 顺序 block 是测不出并发的
        List<String> got = Flux.merge(a, b).collectList().block(Duration.ofSeconds(20));

        assertEquals(2, got.size());
        assertEquals(1, probe.peak.get(),
                "同一 app 的两个 run 重叠了：它们写同一个产物目录，会互相覆盖");
    }

    @Test
    @DisplayName("不同 appId 的流并行")
    void differentAppsRunParallel() {
        Probe probe = new Probe();
        Flux<String> a = coordinator.serialize(1L, () -> probe.slow("A"));
        Flux<String> b = coordinator.serialize(2L, () -> probe.slow("B"));

        Flux.merge(a, b).collectList().block(Duration.ofSeconds(20));

        assertEquals(2, probe.peak.get(), "不同 app 被错误地串行化了，吞吐白掉一半");
    }

    @Test
    @DisplayName("流异常终止后锁仍会释放")
    void releasesOnError() {
        Flux<String> boom = coordinator.serialize(1L,
                () -> Flux.error(new IllegalStateException("模拟执行失败")));
        assertThrows(Exception.class, () -> boom.blockLast(Duration.ofSeconds(10)));

        Probe probe = new Probe();
        List<String> after = coordinator.serialize(1L, () -> probe.slow("after"))
                .collectList().block(Duration.ofSeconds(10));

        assertEquals(List.of("after"), after, "异常路径漏了释放，该 app 会被永久卡死");
    }

    @Test
    @DisplayName("订阅取消后锁仍会释放")
    void releasesOnCancel() {
        Probe probe = new Probe();
        var subscription = coordinator.serialize(1L, () -> probe.slow("长任务")).subscribe();
        // 等它真正跑起来再取消 —— 否则取消可能发生在订阅之前，测不到释放路径
        try {
            Thread.sleep(60);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        subscription.dispose();

        List<String> after = coordinator.serialize(1L, () -> probe.slow("after"))
                .collectList().block(Duration.ofSeconds(10));

        assertEquals(List.of("after"), after, "取消路径漏了释放，该 app 会被永久卡死");
    }

    @Test
    @DisplayName("action 在返回流之前就抛异常时也要释放")
    void releasesWhenActionThrowsBeforeReturning() {
        Flux<String> broken = coordinator.serialize(1L, () -> {
            throw new IllegalStateException("构造流就失败了");
        });
        assertThrows(Exception.class, () -> broken.blockLast(Duration.ofSeconds(10)));

        // 这条路径没有终止信号，只能靠 catch 里主动释放。漏了就是死锁。
        Probe probe = new Probe();
        List<String> after = coordinator.serialize(1L, () -> probe.slow("after"))
                .collectList().block(Duration.ofSeconds(10));

        assertEquals(List.of("after"), after);
    }

    @Test
    @DisplayName("无效 appId 不进锁表，也不会互相阻塞")
    void ignoresInvalidAppId() throws Exception {
        Probe probe = new Probe();

        Flux.merge(
                coordinator.serialize(null, () -> probe.slow("A")),
                coordinator.serialize(0L, () -> probe.slow("B")),
                coordinator.serialize(-1L, () -> probe.slow("C"))
        ).collectList().block(Duration.ofSeconds(20));

        // 没有业务归属的 id 建了条目就无从回收，只能一开始就不建
        assertEquals(0, lockTableSize(), "无效 appId 在锁表里留下了条目");
    }

    @Test
    @DisplayName("引用归零后锁条目被回收，不随 appId 增长泄漏")
    void reclaimsIdleEntries() throws Exception {
        coordinator.serialize(7L, () -> Flux.just("x")).collectList().block(Duration.ofSeconds(10));
        assertEquals(0, lockTableSize(), "空闲锁条目没被回收 —— appId 一多就是常驻内存泄漏");
    }

    @Test
    @DisplayName("并发等待时条目不被回收")
    void keepsEntryWhileAnotherWaits() throws Exception {
        Probe probe = new Probe();

        // 必须真的订阅 —— serialize 返回的是冷流，defer 里的登记与抢锁要到订阅才发生。
        // 也不能在主线程直接订阅：第二个订阅会在 acquire 上阻塞住主线程，测不下去。
        var first = coordinator.serialize(9L, () -> probe.slow("先来的"))
                .subscribeOn(Schedulers.boundedElastic()).subscribe();
        Thread.sleep(30);

        var second = coordinator.serialize(9L, () -> probe.slow("排队的"))
                .subscribeOn(Schedulers.boundedElastic()).subscribe();
        // 此刻第一个还没跑完（120ms），第二个必然还在排队
        Thread.sleep(60);

        assertTrue(lockTableSize() >= 1, "等待者还在排队，锁条目却被回收了 —— 它会等在一把已被丢弃的锁上");

        first.dispose();
        second.dispose();
        Thread.sleep(200);
    }

    @SuppressWarnings("unchecked")
    private int lockTableSize() throws Exception {
        Field field = AppRunCoordinator.class.getDeclaredField("appLocks");
        field.setAccessible(true);
        return ((Map<Long, ?>) field.get(coordinator)).size();
    }
}
