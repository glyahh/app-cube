package com.dbts.glyahhaigeneratecode.agent;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 同一 app 的 run 串行化。
 *
 * <p>承接旧链路的 {@code AppGenerationCoordinator}。旧链路下线时它随 {@code core/memory/}
 * 整包被清掉，但新链路同样需要它 —— 同一个 app 的两次 run 写的是同一个产物目录，
 * 并跑会让两份内容互相覆盖，最后谁后落盘谁赢。实测过：同一 appId 并发 3 个请求全部并跑。
 *
 * <p>不同 app 各用一把锁，仍然并行。锁的边界覆盖整个 run 生命周期 —— 借进程、模型调用、
 * 工具写盘、事件回传，直到流终止。只保护启动阶段等于没保护。
 *
 * <p>锁表按引用计数回收：持锁者和等待者都计数，归零且无人排队才摘除条目，
 * 避免 appId 数量增长把锁表撑成常驻内存。
 */
@Component
public class AppRunCoordinator {

    private final ConcurrentHashMap<Long, LockEntry> appLocks = new ConcurrentHashMap<>();

    /** 删除等操作与 Agent 使用同一许可；忙碌时拒绝，不能先检查再删除。 */
    public <T> T whenIdle(Long appId, Supplier<T> action) {
        LockEntry entry = retain(appId);
        if (!entry.semaphore.tryAcquire()) {
            releaseEntry(appId, entry);
            throw new com.dbts.glyahhaigeneratecode.exception.MyException(
                    com.dbts.glyahhaigeneratecode.exception.ErrorCode.CONFLICT_ERROR, "应用正在生成，请先停止再删除。");
        }
        try { return action.get(); }
        finally { release(appId, entry); }
    }

    /**
     * 把一次 run 包进该 app 的临界区。
     *
     * <p>{@link Flux#defer} 保证订阅时才去竞争锁；{@code doFinally} 统一覆盖完成、异常、
     * 取消三种终止信号 —— 少覆盖一种就漏一次释放，该 app 从此再也拿不到锁。
     *
     * <p><b>调用方注意</b>：等锁是阻塞的，必须保证订阅发生在能承受阻塞的调度器上
     * （{@link AgentRunService} 用 boundedElastic）。在 Netty 事件循环或 Tomcat 请求线程上
     * 订阅会把这些线程占住。
     *
     * @param appId  应用标识；空或非正数不进锁表（没有业务归属，也无从回收）
     * @param action 拿到锁后创建事件流的动作
     */
    public <T> Flux<T> serialize(Long appId, Supplier<Flux<T>> action) {
        if (appId == null || appId <= 0) {
            return Flux.defer(action);
        }
        return Flux.defer(() -> {
            LockEntry entry = retain(appId);
            try {
                entry.semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 还没拿到许可，只撤销引用 —— 此时 release 会把许可数错误地加到 2。
                releaseEntry(appId, entry);
                return Flux.error(e);
            }
            try {
                // action 在锁内创建，保证借进程这一步也属于临界区。
                return action.get().doFinally(ignored -> release(appId, entry));
            } catch (Throwable t) {
                // action 在返回 Flux 之前就抛了，不会产生终止信号，必须在这里释放。
                release(appId, entry);
                return Flux.error(t);
            }
        });
    }

    /**
     * 取该 app 的锁条目，并登记一个参与者（持锁者或等待者）。
     *
     * <p>{@code compute} 让「查找或创建」与「引用加一」对同一 appId 原子完成，
     * 否则旧条目回收时可能并发建出两把不同的锁。
     */
    private LockEntry retain(Long appId) {
        return appLocks.compute(appId, (ignored, current) -> {
            LockEntry entry = current == null ? new LockEntry() : current;
            entry.references.incrementAndGet();
            return entry;
        });
    }

    /** 释放许可并撤销引用。只给确定已拿到许可的路径用。 */
    private void release(Long appId, LockEntry entry) {
        entry.semaphore.release();
        releaseEntry(appId, entry);
    }

    /**
     * 引用减一，完全空闲时从锁表摘除。
     *
     * <p>三个条件同时满足才摘：引用归零、许可已归还、没有排队线程。最后一项防的是
     * 「释放时正好有人刚排队进来」——摘掉会让那个等待者永远等在一把已被丢弃的锁上。
     */
    private void releaseEntry(Long appId, LockEntry entry) {
        appLocks.computeIfPresent(appId, (ignored, current) -> {
            if (current != entry) {
                // 条目已被回收又重建，这次释放不该动新条目。
                return current;
            }
            int remaining = entry.references.decrementAndGet();
            if (remaining == 0 && entry.semaphore.availablePermits() == 1
                    && !entry.semaphore.hasQueuedThreads()) {
                return null;
            }
            return entry;
        });
    }

    /** 单个 app 的公平信号量及其生命周期引用数。 */
    private static final class LockEntry {
        /** 公平模式维持同一 app 请求的到达顺序，避免先到的被后到的插队饿死。 */
        private final Semaphore semaphore = new Semaphore(1, true);
        /** 参与者数量，含正在执行与排队等待的。 */
        private final AtomicInteger references = new AtomicInteger();
    }
}
