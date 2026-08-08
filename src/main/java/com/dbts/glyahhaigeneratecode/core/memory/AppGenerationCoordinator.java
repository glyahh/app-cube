package com.dbts.glyahhaigeneratecode.core.memory;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 应用级代码生成并发协调器。
 * <p>
 * 同一个 app 共享 ChatMemory、AI service 缓存和制品目录，因此完整生成过程必须串行；不同 app 使用
 * 独立锁，仍可并行执行。锁的边界覆盖 service 创建、RAG 注入、模型调用以及工具写盘，直到响应流
 * 完成、失败或取消，不能只保护 service 获取阶段。
 * </p>
 * <p>
 * 锁表使用引用计数管理生命周期。持锁者和等待者都计入引用，最后一个参与者退出且信号量空闲时
 * 回收条目，避免 appId 数量增长导致常驻内存泄漏。
 * </p>
 */
@Component
public class AppGenerationCoordinator {

    private final ConcurrentHashMap<Long, LockEntry> appLocks = new ConcurrentHashMap<>();

    /**
     * 将响应式生成动作串行化。
     * <p>{@link Flux#defer} 确保在订阅时才竞争锁；{@code doFinally} 统一覆盖完成、异常和取消信号。</p>
     *
     * @param appId  应用标识；无效标识不进入锁表
     * @param action 获得锁后创建生成流的动作
     * @param <T>    流元素类型
     * @return 保持原始信号语义的串行化 Flux
     */
    public <T> Flux<T> serialize(Long appId, Supplier<Flux<T>> action) {
        // 1. 无有效 appId 时不需要应用级互斥，但仍保持 Flux 的惰性创建语义。
        if (appId == null || appId <= 0) {
            return Flux.defer(action);
        }
        return Flux.defer(() -> {
            // 2. 登记当前订阅者；若该 app 已有锁条目，则复用同一信号量。
            LockEntry entry = retain(appId);
            try {
                // 3. 获取唯一许可；同 app 的后续订阅会在此等待，其他 app 不受影响。
                entry.semaphore.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                // 尚未获得许可，只需撤销引用登记，不能释放信号量。
                releaseEntry(appId, entry);
                return Flux.error(e);
            }

            try {
                // 4. action 在锁内创建，确保 service 创建和 RAG 注入也属于临界区。
                return action.get().doFinally(ignored -> release(appId, entry));
            } catch (Throwable throwable) {
                // action 在返回 Flux 前失败时没有终止信号，需要在此主动释放。
                release(appId, entry);
                return Flux.error(throwable);
            }
        });
    }

    /**
     * 串行执行同步 AI 生成动作。
     * <p>
     * 同步入口与 {@link #serialize(Long, Supplier)} 共用同一锁表，因此同一 app 的同步请求和流式请求
     * 也会互相等待。锁覆盖 action 的完整执行过程，包括模型调用和制品落盘。
     * </p>
     *
     * @param appId  应用标识；无效标识直接执行
     * @param action 完整同步生成动作
     * @param <T>    返回值类型
     * @return action 的执行结果
     */
    public <T> T serializeBlocking(Long appId, Supplier<T> action) {
        // 1. 无有效 appId 时直接执行，不创建无法回收业务归属的锁条目。
        if (appId == null || appId <= 0) {
            return action.get();
        }

        // 2. 在等待前登记引用，保证等待期间该 app 始终复用同一把锁。
        LockEntry entry = retain(appId);
        boolean acquired = false;
        try {
            // 3. 获得许可后执行完整同步动作。
            entry.semaphore.acquire();
            acquired = true;
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待应用生成锁时被中断", e);
        } finally {
            // 未成功获得许可时不能 release，否则会把信号量许可数错误地增加到 2。
            if (acquired) {
                entry.semaphore.release();
            }
            // 4. 无论 action 成功、失败或线程中断，都必须撤销当前调用的引用。
            releaseEntry(appId, entry);
        }
    }

    /**
     * 获取指定 app 的锁条目，并登记一个新的持锁者或等待者。
     * <p>
     * {@link ConcurrentHashMap#compute(Object, java.util.function.BiFunction)} 保证“查找或创建条目”与
     * “引用数加一”对同一 appId 原子完成，避免旧条目回收时并发创建两把不同的锁。
     * </p>
     *
     * @param appId 应用标识
     * @return 当前 app 唯一的锁条目
     */
    private LockEntry retain(Long appId) {
        return appLocks.compute(appId, (ignored, current) -> {
            LockEntry entry = current == null ? new LockEntry() : current;
            entry.references.incrementAndGet();
            return entry;
        });
    }

    /**
     * 释放已获得的信号量许可，并撤销当前参与者的引用登记。
     * <p>仅供确定已经成功获取许可的响应式执行路径调用。</p>
     *
     * @param appId 应用标识
     * @param entry 本次执行持有的锁条目
     */
    private void release(Long appId, LockEntry entry) {
        entry.semaphore.release();
        releaseEntry(appId, entry);
    }

    /**
     * 减少锁条目的引用数，并在完全空闲时从锁表回收。
     * <p>
     * 回收必须同时满足：引用数为 0、信号量许可已归还、没有排队线程，并且 map 当前仍指向传入条目。
     * 最后一项用于防止迟到的释放操作误删同 app 后续创建的新条目。
     * </p>
     *
     * @param appId 应用标识
     * @param entry 需要撤销引用的锁条目
     */
    private void releaseEntry(Long appId, LockEntry entry) {
        // computeIfPresent 将引用递减和 map 条件删除置于同一 key 的原子计算中。
        appLocks.computeIfPresent(appId, (ignored, current) -> {
            if (current != entry) {
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

    /** 保存单个 app 的公平信号量及其生命周期引用数。 */
    private static final class LockEntry {
        /** 公平信号量维持同一 app 请求的到达顺序，避免长时间饥饿。 */
        private final Semaphore semaphore = new Semaphore(1, true);
        /** 当前参与者数量，包含正在执行的请求和排队等待的请求。 */
        private final AtomicInteger references = new AtomicInteger();
    }
}
