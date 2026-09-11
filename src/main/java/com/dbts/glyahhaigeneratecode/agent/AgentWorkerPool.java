package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Python Agent 进程池。
 *
 * <p>为什么池化：实测 {@code import langgraph} 5.35 秒、{@code import deepagents} 7.11 秒。
 * 每次 run 起新进程会让首屏卡死，所以必须常驻。
 *
 * <p>伸缩策略见 {@link AgentPoolProperties}：扩容到 maxSize、活跃数低于 shrinkBelow 时
 * 缩回 coreSize、缩容后有一段时间禁止扩容（防抖动）。
 *
 * <p><b>注意每个 worker 是 2 个操作系统进程</b>：venv 里的 {@code python.exe} 是个
 * launcher，会再拉起真正的解释器。杀进程树时靠 {@code descendants} 一并覆盖。
 */
@Slf4j
@Component
public class AgentWorkerPool implements DisposableBean {

    private final AgentPoolProperties props;
    private final AgentProperties runtimeProps;

    /** 空闲进程。借出时从这里取，归还时放回。 */
    private final BlockingQueue<AgentWorker> idleWorkers = new LinkedBlockingQueue<>();
    /**
     * 所有活着的进程，含借出中的。
     *
     * <p>必须要这个集合：{@link #destroy()} 只遍历空闲队列的话，
     * 关停时正被借出的进程会成为孤儿。
     */
    private final Set<AgentWorker> allWorkers = ConcurrentHashMap.newKeySet();

    /** 池内总进程数（空闲 + 借出）。 */
    private final AtomicInteger total = new AtomicInteger();
    /** 借出中的进程数。 */
    private final AtomicInteger active = new AtomicInteger();
    /** 上次缩容时刻，冷却基准。0 表示从未缩容。 */
    private final AtomicLong lastShrinkNanos = new AtomicLong(0);

    private final Object scaleLock = new Object();

    private volatile String pythonExecutable;
    private volatile String scriptPath;
    private volatile boolean shutdown;

    public AgentWorkerPool(AgentPoolProperties props, AgentProperties runtimeProps) {
        this.props = props;
        this.runtimeProps = runtimeProps;
    }

    @PostConstruct
    void warmUp() {
        resolvePaths();
        if (pythonExecutable == null) {
            // 不阻断启动 —— 让应用先跑起来，用户发请求时再报明确错误。
            log.error("未找到 Python 解释器，Agent 功能不可用。请先在 agent/ 下执行 uv sync。");
            return;
        }
        // 预热放后台：每个进程要 import 7 秒左右，阻塞 Spring 启动不划算。
        Thread.ofVirtual().name("agent-pool-warmup").start(() -> {
            for (int i = 0; i < props.getCoreSize(); i++) {
                if (shutdown) {
                    return;
                }
                // 必须先占名额再创建 —— 漏掉这一步会让 total 比实际少 coreSize 个，
                // 之后每次取消都把 total 递减成负数，补齐逻辑便以为缺很多，
                // 于是超额创建，真实进程数突破 maxSize。
                if (!reserveSlotIgnoringCooldown()) {
                    return;
                }
                try {
                    idleWorkers.offer(createWorker());
                } catch (Exception e) {
                    total.decrementAndGet();
                    log.warn("预热第 {} 个 Python 进程失败，池将以更小规模启动", i + 1, e);
                    return;
                }
            }
            log.info("Agent 进程池预热完成，常驻 {} 个", props.getCoreSize());
        });
    }

    /**
     * 占一个名额，不受缩容冷却限制。
     *
     * <p>预热用 —— 冷却是给「扩缩容抖动」设的，启动时的首次填充不该被它挡住。
     */
    private boolean reserveSlotIgnoringCooldown() {
        synchronized (scaleLock) {
            if (total.get() >= props.getMaxSize()) {
                return false;
            }
            total.incrementAndGet();
            return true;
        }
    }

    private void resolvePaths() {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        String exe = runtimeProps.getPythonExecutable();
        if (exe == null || exe.isBlank()) {
            exe = detectVenvPython(repoRoot.resolve("agent"));
        }
        String script = runtimeProps.getScriptPath();
        if (script == null || script.isBlank()) {
            script = repoRoot.resolve("agent").resolve("app.py").toString();
        }
        if (exe == null || !Files.isExecutable(Path.of(exe))) {
            log.error("Python 解释器不可用：{}", exe);
            return;
        }
        if (!Files.isRegularFile(Path.of(script))) {
            log.error("Agent 入口脚本不存在：{}", script);
            return;
        }
        this.pythonExecutable = exe;
        this.scriptPath = script;
    }

    /** Windows 是 Scripts/python.exe，其他平台是 bin/python。 */
    private static String detectVenvPython(Path agentDir) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path candidate = windows
                ? agentDir.resolve(".venv").resolve("Scripts").resolve("python.exe")
                : agentDir.resolve(".venv").resolve("bin").resolve("python");
        return Files.isExecutable(candidate) ? candidate.toString() : null;
    }

    /**
     * 借一个进程。
     *
     * <p>池满时排队等待，超时抛 {@code TOO_MANY_REQUEST} —— 不能无限等，
     * 那会把 SSE/WS 的首帧永远挂住。
     */
    public AgentWorker acquire() {
        if (pythonExecutable == null) {
            resolvePaths();
            if (pythonExecutable == null) {
                throw new MyException(ErrorCode.SYSTEM_ERROR, "Agent 运行时不可用，请先执行 agent/ 下的 uv sync");
            }
        }
        long deadline = System.nanoTime() + props.getAcquireTimeout().toNanos();

        while (true) {
            // 1. 先取空闲的，跳过已经死掉的
            AgentWorker worker = pollAliveIdle();
            if (worker != null) {
                active.incrementAndGet();
                return worker;
            }

            // 2. 没有空闲的，在允许时扩容
            if (reserveScaleSlot()) {
                try {
                    worker = createWorker();
                } catch (RuntimeException e) {
                    total.decrementAndGet();
                    throw e;
                }
                active.incrementAndGet();
                return worker;
            }

            // 3. 既不能扩也不能立刻拿到，等别人归还
            long remain = deadline - System.nanoTime();
            if (remain <= 0) {
                throw new MyException(ErrorCode.TOO_MANY_REQUEST, "当前生成任务较多，请稍后重试");
            }
            try {
                worker = idleWorkers.poll(remain, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MyException(ErrorCode.SYSTEM_ERROR, "等待 Agent 进程时被中断");
            }
            if (worker != null && worker.isAlive()) {
                active.incrementAndGet();
                return worker;
            }
            if (worker != null) {
                forget(worker);
            }
        }
    }

    /** 取一个还活着的空闲进程；顺手回收已死的。 */
    private AgentWorker pollAliveIdle() {
        AgentWorker worker;
        while ((worker = idleWorkers.poll()) != null) {
            if (worker.isAlive()) {
                return worker;
            }
            log.warn("回收已退出的空闲 Python 进程 pid={}", worker.pid());
            forget(worker);
        }
        return null;
    }

    /** 判断能不能扩容，能就先占住一个名额。 */
    private boolean reserveScaleSlot() {
        synchronized (scaleLock) {
            if (total.get() >= props.getMaxSize()) {
                return false;
            }
            long last = lastShrinkNanos.get();
            if (last != 0 && System.nanoTime() - last < props.getScaleCooldown().toNanos()) {
                // 刚缩过容，说明负载低。宁可排队也不反复拉起销毁。
                return false;
            }
            total.incrementAndGet();
            return true;
        }
    }

    private AgentWorker createWorker() {
        try {
            AgentWorker worker = AgentWorker.start(pythonExecutable, scriptPath, runtimeProps.getStartupTimeout());
            allWorkers.add(worker);
            log.info("拉起 Python 进程 pid={}（池内 {} 个，其中活跃 {} 个）",
                    worker.pid(), total.get(), active.get());
            return worker;
        } catch (IOException e) {
            throw new MyException(ErrorCode.SYSTEM_ERROR, "拉起 Agent 进程失败：" + e.getMessage());
        }
    }

    /** 把一个进程从账上抹掉：杀干净、从两个集合里摘除。 */
    private void forget(AgentWorker worker) {
        allWorkers.remove(worker);
        total.decrementAndGet();
        worker.destroyTree();
    }

    /** 正常归还。 */
    public void release(AgentWorker worker) {
        if (worker == null) {
            return;
        }
        active.decrementAndGet();

        if (shutdown) {
            forget(worker);
            return;
        }
        if (!worker.isAlive() || worker.isEof()) {
            // 进程已经死了（跑挂了或者被外部杀掉），丢弃让下次 acquire 重建。
            // 子进程方案的最大好处就在这里：进程死了 stdout 立刻 EOF，不需要心跳去发现。
            log.warn("归还时发现 Python 进程已退出，pid={}", worker.pid());
            forget(worker);
            replenishToCoreAsync();
            return;
        }
        idleWorkers.offer(worker);
        shrinkIfIdle();
    }

    /** 丢弃（取消时用）。杀掉并且不归还。 */
    public void discard(AgentWorker worker) {
        if (worker == null) {
            return;
        }
        active.decrementAndGet();
        forget(worker);
        // 取消是常态操作，不补的话池会越用越冷，下次请求就得付 7 秒冷启动。
        replenishToCoreAsync();
    }

    /**
     * 把池补回 coreSize。异步做，不阻塞取消路径。
     *
     * <p>只在低于 coreSize 时补 —— 高于就交给缩容逻辑处理，两条路不会打架。
     */
    private void replenishToCoreAsync() {
        if (shutdown) {
            return;
        }
        Thread.ofVirtual().name("agent-pool-replenish").start(() -> {
            while (!shutdown) {
                synchronized (scaleLock) {
                    if (total.get() >= props.getCoreSize()) {
                        return;
                    }
                    total.incrementAndGet();
                }
                AgentWorker worker;
                try {
                    worker = createWorker();
                } catch (Exception e) {
                    total.decrementAndGet();
                    log.warn("补齐进程池失败，等待下次 acquire 重建", e);
                    return;
                }
                // 创建期间可能已经关停 —— 这时不能再放进池里，否则它会成为孤儿。
                if (shutdown) {
                    allWorkers.remove(worker);
                    total.decrementAndGet();
                    worker.destroyTree();
                    return;
                }
                idleWorkers.offer(worker);
            }
        });
    }

    /**
     * 缩容：活跃数低于阈值时，销毁多余的空闲进程，回到 coreSize。
     *
     * <p>在 release 之后调用 —— 那是活跃数唯一会下降的时刻。
     */
    private void shrinkIfIdle() {
        if (active.get() >= props.getShrinkBelow()) {
            return;
        }
        List<AgentWorker> toKill = new ArrayList<>();
        synchronized (scaleLock) {
            if (active.get() >= props.getShrinkBelow() || total.get() <= props.getCoreSize()) {
                return;
            }
            while (total.get() > props.getCoreSize()) {
                AgentWorker worker = idleWorkers.poll();
                if (worker == null) {
                    break;
                }
                allWorkers.remove(worker);
                total.decrementAndGet();
                toKill.add(worker);
            }
            if (!toKill.isEmpty()) {
                lastShrinkNanos.set(System.nanoTime());
            }
        }
        for (AgentWorker worker : toKill) {
            worker.destroyTree();
        }
        if (!toKill.isEmpty()) {
            log.info("缩容：回收 {} 个空闲 Python 进程，池内回到 {} 个；{} 分钟内不再扩容",
                    toKill.size(), total.get(), props.getScaleCooldown().toMinutes());
        }
    }

    /** 当前池状态，供监控与测试。 */
    public PoolStats stats() {
        return new PoolStats(total.get(), active.get(), idleWorkers.size());
    }

    public record PoolStats(int total, int active, int idle) {
    }

    /**
     * 关停。
     *
     * <p>遍历的是 {@code allWorkers} 而不是空闲队列 —— 借出中的进程也要杀掉，
     * 否则应用退出时会留下孤儿 Python 进程。
     */
    @Override
    public void destroy() {
        shutdown = true;
        for (AgentWorker worker : allWorkers) {
            worker.destroyTree();
        }
        allWorkers.clear();
        idleWorkers.clear();
        total.set(0);
        active.set(0);
        log.info("Agent 进程池已关闭");
    }
}
