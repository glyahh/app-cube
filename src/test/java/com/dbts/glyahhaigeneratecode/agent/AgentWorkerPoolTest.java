package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 进程池的端到端测试。
 *
 * <p>跑的是一个**不调用模型**的 stub 脚本（{@code src/test/resources/agent-stub.py}），
 * 协议与真实 {@code app.py} 完全一致。
 *
 * <p>为什么不用真脚本：这里测的是借出/归还/取消/伸缩，不是 agent 会不会写文件。
 * 用真脚本会让测试依赖网络、模型密钥和余额，慢且不稳。真实执行的验证在
 * Python 侧单测与端到端脚本里做。
 */
@DisplayName("Agent 进程池")
class AgentWorkerPoolTest {

    private static Path repoRoot;
    private static String pythonExe;
    private static String stubScript;

    private AgentWorkerPool pool;

    @BeforeAll
    static void locatePython() {
        repoRoot = Path.of(System.getProperty("user.dir"));
        Path exe = repoRoot.resolve("agent").resolve(".venv").resolve("Scripts").resolve("python.exe");
        if (!Files.isExecutable(exe)) {
            exe = repoRoot.resolve("agent").resolve(".venv").resolve("bin").resolve("python");
        }
        pythonExe = Files.isExecutable(exe) ? exe.toString() : null;
        stubScript = repoRoot.resolve("target").resolve("test-classes").resolve("agent-stub.py").toString();
    }

    @BeforeEach
    void setUp() {
        // venv 还没建时跳过，而不是让整条构建挂掉 —— 新人 clone 下来第一件事是 uv sync。
        Assumptions.assumeTrue(pythonExe != null, "agent/.venv 不存在，先执行 cd agent && uv sync");
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(stubScript)), "找不到 test stub：" + stubScript);

        AgentPoolProperties poolProps = new AgentPoolProperties();
        poolProps.setCoreSize(1);
        poolProps.setMaxSize(2);
        poolProps.setShrinkBelow(1);
        poolProps.setScaleCooldown(Duration.ofMillis(80));
        poolProps.setAcquireTimeout(Duration.ofSeconds(10));

        AgentProperties runtimeProps = new AgentProperties();
        runtimeProps.setPythonExecutable(pythonExe);
        runtimeProps.setScriptPath(stubScript);
        runtimeProps.setStartupTimeout(Duration.ofSeconds(30));
        runtimeProps.setReadTimeout(Duration.ofSeconds(30));

        pool = new AgentWorkerPool(poolProps, runtimeProps);
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.destroy();
        }
    }

    private static String request(String runId) {
        Path workspaceRoot = repoRoot.resolve("temp").resolve("code_output");
        return "{\"v\":1,\"runId\":\"" + runId + "\""
                + ",\"threadId\":\"app:1\",\"appId\":\"1\",\"userId\":\"1\",\"roundId\":\"2\""
                + ",\"workspace\":{\"root\":\"" + workspaceRoot.toString().replace("\\", "/") + "\""
                + ",\"name\":\"html_1\",\"codeGenType\":\"html\"}"
                + ",\"input\":{\"message\":\"跑一轮\"}}";
    }

    /** 跑完一轮，返回收到的事件行。读到终止帧就停。 */
    private List<String> runOnce(AgentWorker worker, String runId) throws Exception {
        worker.send(request(runId));
        List<String> events = new ArrayList<>();
        Duration timeout = Duration.ofSeconds(30);
        String line;
        while ((line = worker.readEvent(timeout)) != null) {
            events.add(line);
            if (line.contains("\"event\": \"__end__\"")) {
                break;
            }
        }
        return events;
    }

    @Test
    @DisplayName("借出时创建进程，一轮跑完拿到完整事件流并以终止帧收尾")
    void acquireCreatesWorkerAndCompletesOneRun() throws Exception {
        AgentWorker worker = pool.acquire();
        try {
            assertTrue(worker.isAlive());

            List<String> events = runOnce(worker, "t-1");

            assertTrue(events.get(0).contains("\"event\": \"run.started\""), "首帧应是 run.started");
            assertTrue(events.get(events.size() - 1).contains("\"event\": \"__end__\""), "末帧应是终止帧");
            assertTrue(events.stream().anyMatch(e -> e.contains("run.completed")), "应含 run.completed");
            assertTrue(events.stream().anyMatch(e -> e.contains("跑一轮")), "应回显用户需求");
        } finally {
            pool.release(worker);
        }
    }

    @Test
    @DisplayName("归还后复用同一个进程，不重新拉起")
    void releasedWorkerIsReused() throws Exception {
        AgentWorker first = pool.acquire();
        long firstPid = first.pid();
        runOnce(first, "t-2a");
        pool.release(first);

        AgentWorker second = pool.acquire();
        try {
            assertEquals(firstPid, second.pid(), "应复用同一个进程");
        } finally {
            pool.release(second);
        }
    }

    @Test
    @DisplayName("进程被杀后归还时丢弃，下次借出重建新进程")
    void deadWorkerIsReplacedOnNextAcquire() throws Exception {
        AgentWorker first = pool.acquire();
        long firstPid = first.pid();
        first.destroyTree();

        // 等它真的退出 —— destroyForcibly 是异步的
        for (int i = 0; i < 50 && first.isAlive(); i++) {
            Thread.sleep(100);
        }
        assertTrue(!first.isAlive(), "进程应已被杀掉");

        pool.release(first);

        AgentWorker second = pool.acquire();
        try {
            assertNotEquals(firstPid, second.pid(), "应重建一个新进程");
            assertTrue(second.isAlive());
            assertTrue(runOnce(second, "t-3").size() > 0, "新进程应能正常工作");
        } finally {
            pool.release(second);
        }
    }

    @Test
    @DisplayName("池满时借出排队，超时抛 TOO_MANY_REQUEST 而不是无限挂住")
    void acquireTimesOutWhenPoolExhausted() {
        AgentWorker a = pool.acquire();
        AgentWorker b = pool.acquire();
        try {
            // coreSize=1 / maxSize=2，两个都借出后既没空闲也扩不了
            MyException ex = assertThrows(MyException.class, () -> pool.acquire());
            assertTrue(ex.getMessage().contains("稍后重试"), "错误提示应可读：" + ex.getMessage());
        } finally {
            pool.release(a);
            pool.release(b);
        }
    }

    @Test
    @DisplayName("活跃数降到阈值以下时缩容回 coreSize")
    void shrinkReturnsToCoreSize() throws Exception {
        AgentWorker a = pool.acquire();
        AgentWorker b = pool.acquire();
        assertEquals(2, pool.stats().total(), "借两个后池内应有 2 个进程");

        pool.release(a);
        pool.release(b);

        // 缩容在 release 里同步发生
        assertEquals(1, pool.stats().total(), "活跃归零后应缩回 coreSize=1");
        assertEquals(1, pool.stats().idle());
    }

    @Test
    @DisplayName("缩容后的冷却期内不再扩容，宁可排队")
    void cooldownBlocksScalingRightAfterShrink() throws Exception {
        AgentPoolProperties aggressive = new AgentPoolProperties();
        aggressive.setCoreSize(1);
        aggressive.setMaxSize(3);
        aggressive.setShrinkBelow(1);
        aggressive.setScaleCooldown(Duration.ofMinutes(5));
        aggressive.setAcquireTimeout(Duration.ofMillis(500));

        AgentProperties runtimeProps = new AgentProperties();
        runtimeProps.setPythonExecutable(pythonExe);
        runtimeProps.setScriptPath(stubScript);
        runtimeProps.setStartupTimeout(Duration.ofSeconds(30));

        AgentWorkerPool cooldownPool = new AgentWorkerPool(aggressive, runtimeProps);
        try {
            AgentWorker a = cooldownPool.acquire();
            AgentWorker b = cooldownPool.acquire();   // 扩到 2
            cooldownPool.release(a);
            cooldownPool.release(b);                  // 活跃归零 → 缩回 1，进入冷却

            assertEquals(1, cooldownPool.stats().total());

            AgentWorker held = cooldownPool.acquire();
            try {
                // 池满（total=1 已借出）且冷却中不得扩容 → 只能排队到超时
                assertThrows(MyException.class, cooldownPool::acquire);
                assertEquals(1, cooldownPool.stats().total(), "冷却期内不得扩容");
            } finally {
                cooldownPool.release(held);
            }
        } finally {
            cooldownPool.destroy();
        }
    }

    @Test
    @DisplayName("discard 后进程不归还，池恢复容量")
    void discardDoesNotReturnWorkerToPool() throws Exception {
        AgentWorker worker = pool.acquire();
        assertEquals(1, pool.stats().active());

        pool.discard(worker);

        assertEquals(0, pool.stats().active());
        assertEquals(0, pool.stats().total(), "discard 应从池内移除");
        assertEquals(0, pool.stats().idle(), "不应回到空闲队列");
    }

    @Test
    @DisplayName("终止帧在异常路径下也会发出，Java 侧不会永久阻塞在读上")
    void endFrameArrivesEvenOnError() throws Exception {
        AgentWorker worker = pool.acquire();
        try {
            // 故意给一个缺 workspace 的坏请求
            worker.send("{\"runId\":\"t-err\",\"input\":{\"message\":\"x\"}}");

            List<String> events = new ArrayList<>();
            String line;
            while ((line = worker.readEvent(Duration.ofSeconds(30))) != null) {
                events.add(line);
                if (line.contains("\"event\": \"__end__\"")) {
                    break;
                }
            }
            assertEquals(2, events.size(), "应恰好是 error + 终止帧");
            assertTrue(events.get(0).contains("WORKSPACE_INIT_FAILED"));
            assertTrue(events.get(1).contains("__end__"));
        } finally {
            pool.release(worker);
        }
    }

    @Test
    @DisplayName("并发借出时不会把同一个进程发给两个调用方")
    void concurrentAcquireNeverHandsOutSameWorker() throws Exception {
        AgentPoolProperties wide = new AgentPoolProperties();
        wide.setCoreSize(1);
        wide.setMaxSize(3);
        wide.setShrinkBelow(1);
        wide.setAcquireTimeout(Duration.ofSeconds(30));

        AgentProperties runtimeProps = new AgentProperties();
        runtimeProps.setPythonExecutable(pythonExe);
        runtimeProps.setScriptPath(stubScript);
        runtimeProps.setStartupTimeout(Duration.ofSeconds(30));

        AgentWorkerPool widePool = new AgentWorkerPool(wide, runtimeProps);
        int threads = 3;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Set<Long> pids = java.util.Collections.synchronizedSet(new HashSet<>());
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < threads; i++) {
                String runId = "t-c" + i;
                Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        AgentWorker w = widePool.acquire();
                        try {
                            pids.add(w.pid());
                            runOnce(w, runId);
                        } finally {
                            widePool.release(w);
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务应在 60 秒内完成");

            assertTrue(failures.isEmpty(), "并发执行不应失败：" + failures);
            assertEquals(threads, pids.size(), "3 个并发调用应各自拿到不同的进程");
        } finally {
            widePool.destroy();
        }
    }

    @Test
    @DisplayName("关停时借出中的进程也要被杀掉，不能留孤儿")
    void destroyKillsCheckedOutWorkers() throws Exception {
        AgentWorker checkedOut = pool.acquire();
        assertTrue(checkedOut.isAlive());
        assertFalse(checkedOut.isEof());

        pool.destroy();

        // destroyForcibly 是异步的，等它真的退出
        for (int i = 0; i < 50 && checkedOut.isAlive(); i++) {
            Thread.sleep(100);
        }
        assertFalse(checkedOut.isAlive(),
                "关停只遍历空闲队列的话，借出中的进程会变成孤儿");
    }

    @Test
    @DisplayName("discard 之后池会补齐回 coreSize，避免越用越冷")
    void discardReplenishesBackToCoreSize() throws Exception {
        AgentWorker worker = pool.acquire();
        assertEquals(1, pool.stats().total());

        pool.discard(worker);

        // 补齐是异步的：total 先自增占位，进程真正可用要等它进空闲队列。
        // 所以等的是 idle，不是 total。
        boolean ready = false;
        for (int i = 0; i < 100; i++) {
            if (pool.stats().idle() >= 1) {
                ready = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(ready, "取消后应补齐一个可用进程，否则下次请求要付冷启动。实际：" + pool.stats());
        assertEquals(1, pool.stats().total());
    }

    @Test
    @DisplayName("预热创建的进程必须计入 total —— 漏计会让后续补齐超额创建")
    void warmUpAccountsForEveryWorker() throws Exception {
        AgentPoolProperties props = new AgentPoolProperties();
        props.setCoreSize(2);
        props.setMaxSize(3);
        props.setShrinkBelow(1);
        props.setAcquireTimeout(Duration.ofSeconds(10));

        AgentProperties runtimeProps = new AgentProperties();
        runtimeProps.setPythonExecutable(pythonExe);
        runtimeProps.setScriptPath(stubScript);
        runtimeProps.setStartupTimeout(Duration.ofSeconds(30));

        AgentWorkerPool warmed = new AgentWorkerPool(props, runtimeProps);
        try {
            warmed.warmUp();

            for (int i = 0; i < 100 && warmed.stats().idle() < 2; i++) {
                Thread.sleep(100);
            }

            assertEquals(2, warmed.stats().idle(), "应预热出 2 个");
            assertEquals(2, warmed.stats().total(),
                    "预热创建的进程没计入 total 的话，取消时递减会把 total 压成负数，"
                            + "补齐逻辑会超额创建，真实进程数突破 maxSize");
        } finally {
            warmed.destroy();
        }
    }
}
