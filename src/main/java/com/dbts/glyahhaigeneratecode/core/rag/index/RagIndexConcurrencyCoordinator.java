package com.dbts.glyahhaigeneratecode.core.rag.index;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * RAG 索引并发协调器。
 * <p>
 * 两级锁定义如下：应用读锁允许同一 app 的不同文件并行索引，文件独占锁保证同一文件的索引与删除
 * 串行；应用写锁用于整应用删除，必须等待全部在途文件操作结束。不同 app 之间没有共享锁。
 * </p>
 * <p>
 * 协调器只负责进程内互斥，不保存“已删除应用”状态。删除后的权威判断属于数据库存在性检查，适用于
 * 进程重启和多实例部署。锁条目通过引用计数回收，持锁者及等待者全部退出后不再占用内存。
 * </p>
 */
@Component
public class RagIndexConcurrencyCoordinator {

    private final ConcurrentHashMap<FileIndexKey, LockEntry<ReentrantLock>> fileLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, LockEntry<ReentrantReadWriteLock>> appLocks = new ConcurrentHashMap<>();

    /**
     * 在应用读锁和文件独占锁内执行单文件索引。
     *
     * @return 参数有效且 action 实际进入临界区时返回 {@code true}
     */
    public boolean executeIndex(Long appId, String filePath, Runnable action) {
        if (appId == null || filePath == null) {
            return false;
        }

        // 1. 先保留应用锁条目，再竞争读锁；等待者必须计入引用数。
        LockEntry<ReentrantReadWriteLock> appEntry = retain(
                appLocks, appId, ReentrantReadWriteLock::new);
        Lock appReadLock = appEntry.lock.readLock();
        appReadLock.lock();
        try {
            // 2. 应用读锁内继续获取文件锁，锁顺序固定为 app -> file，避免循环等待。
            executeWithFileLock(appId, filePath, action);
            return true;
        } finally {
            appReadLock.unlock();
            release(appLocks, appId, appEntry, () -> isIdle(appEntry.lock));
        }
    }

    /**
     * 在应用读锁和文件独占锁内删除单文件索引。
     * <p>与 {@link #executeIndex} 使用相同锁顺序，保证同文件写入和删除不会交错。</p>
     */
    public void executeFileDeletion(Long appId, String filePath, Runnable action) {
        if (appId == null || filePath == null) {
            return;
        }

        LockEntry<ReentrantReadWriteLock> appEntry = retain(
                appLocks, appId, ReentrantReadWriteLock::new);
        Lock appReadLock = appEntry.lock.readLock();
        appReadLock.lock();
        try {
            executeWithFileLock(appId, filePath, action);
        } finally {
            appReadLock.unlock();
            release(appLocks, appId, appEntry, () -> isIdle(appEntry.lock));
        }
    }

    /**
     * 在应用写锁内删除整应用索引。
     * <p>写锁会等待该应用全部在途索引和文件删除结束，但不会阻塞其他应用。</p>
     */
    public void executeAppDeletion(Long appId, Runnable action) {
        if (appId == null) {
            return;
        }

        LockEntry<ReentrantReadWriteLock> appEntry = retain(
                appLocks, appId, ReentrantReadWriteLock::new);
        Lock appWriteLock = appEntry.lock.writeLock();
        appWriteLock.lock();
        try {
            action.run();
        } finally {
            appWriteLock.unlock();
            release(appLocks, appId, appEntry, () -> isIdle(appEntry.lock));
        }
    }

    private void executeWithFileLock(Long appId, String filePath, Runnable action) {
        FileIndexKey key = new FileIndexKey(appId, filePath);
        LockEntry<ReentrantLock> fileEntry = retain(fileLocks, key, ReentrantLock::new);
        fileEntry.lock.lock();
        try {
            action.run();
        } finally {
            fileEntry.lock.unlock();
            release(fileLocks, key, fileEntry,
                    () -> !fileEntry.lock.isLocked() && !fileEntry.lock.hasQueuedThreads());
        }
    }

    private static boolean isIdle(ReentrantReadWriteLock lock) {
        return lock.getReadLockCount() == 0 && !lock.isWriteLocked() && !lock.hasQueuedThreads();
    }

    private static <K, L> LockEntry<L> retain(ConcurrentHashMap<K, LockEntry<L>> locks,
                                               K key,
                                               Supplier<L> lockFactory) {
        // map.compute 使“获取条目 + 增加引用”成为同一 key 上的原子操作。
        return locks.compute(key, (ignored, current) -> {
            LockEntry<L> entry = current == null ? new LockEntry<>(lockFactory.get()) : current;
            entry.references.incrementAndGet();
            return entry;
        });
    }

    private static <K, L> void release(ConcurrentHashMap<K, LockEntry<L>> locks,
                                       K key,
                                       LockEntry<L> entry,
                                       BooleanSupplier idle) {
        // 仅回收当前仍映射到该实例且已完全空闲的条目，避免误删后继锁。
        locks.computeIfPresent(key, (ignored, current) -> {
            if (current != entry) {
                return current;
            }
            int remaining = entry.references.decrementAndGet();
            return remaining == 0 && idle.getAsBoolean() ? null : entry;
        });
    }

    private static final class LockEntry<L> {
        private final L lock;
        /** 持锁者和等待者总数；为 0 时才允许从 map 回收。 */
        private final AtomicInteger references = new AtomicInteger();

        private LockEntry(L lock) {
            this.lock = lock;
        }
    }

    private record FileIndexKey(Long appId, String filePath) {
    }
}
