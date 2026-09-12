package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事件解析 + 进程读写的 Reactor 桥接。
 *
 * <p>跑的是不调用模型的 stub 脚本（协议与真 app.py 一致）—— 这里测的是事件解析、
 * 完成/取消语义、进程归还，与 agent 的真实产出无关。
 */
@DisplayName("Agent 事件流")
class AgentRunServiceTest {

    private static Path repoRoot;
    private static String pythonExe;
    private static String stubScript;

    private final ObjectMapper mapper = new ObjectMapper();
    private AgentWorkerPool pool;
    private AgentRunService service;

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
        Assumptions.assumeTrue(pythonExe != null, "agent/.venv 不存在，先执行 cd agent && uv sync");
        Assumptions.assumeTrue(Files.isRegularFile(Path.of(stubScript)), "找不到 test stub：" + stubScript);

        AgentPoolProperties poolProps = new AgentPoolProperties();
        poolProps.setCoreSize(1);
        poolProps.setMaxSize(2);
        poolProps.setShrinkBelow(1);
        poolProps.setAcquireTimeout(Duration.ofSeconds(10));

        AgentProperties runtimeProps = new AgentProperties();
        runtimeProps.setPythonExecutable(pythonExe);
        runtimeProps.setScriptPath(stubScript);
        runtimeProps.setStartupTimeout(Duration.ofSeconds(30));
        runtimeProps.setReadTimeout(Duration.ofSeconds(30));

        pool = new AgentWorkerPool(poolProps, runtimeProps);
        service = new AgentRunService(pool, runtimeProps, mapper, new AppRunCoordinator());
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.destroy();
        }
    }

    private String requestJson(String runId, String message) {
        Path workspaceRoot = repoRoot.resolve("temp").resolve("code_output");
        return AgentRunRequest.builder()
                .runId(runId)
                .threadId("app:1")
                .appId("1")
                .userId("1")
                .roundId("2")
                .traceId("t-1")
                .workspaceRoot(workspaceRoot.toString())
                .workspaceName("html_1")
                .codeGenType("html")
                .mode("create")
                .message(message)
                .build()
                .toFrameJson(mapper);
    }

    // ─────────────────────────── 解析 ───────────────────────────

    @Test
    @DisplayName("解析出信封字段")
    void parsesEnvelope() throws IOException {
        AgentEvent event = AgentEvent.parse(mapper,
                "{\"v\":1,\"runId\":\"r-1\",\"seq\":7,\"ts\":1757692800123,\"event\":\"text.delta\",\"content\":\"<div>\"}");

        assertEquals("r-1", event.runId());
        assertEquals(7, event.seq());
        assertEquals(1757692800123L, event.ts());
        assertEquals(AgentEvent.TEXT_DELTA, event.event());
        assertEquals("<div>", event.textContent());
        assertTrue(event.isTextDelta());
        assertFalse(event.isEnd());
    }

    @Test
    @DisplayName("缺少 event 字段的帧被拒绝")
    void rejectsFrameWithoutEventName() {
        assertThrows(IOException.class, () -> AgentEvent.parse(mapper, "{\"runId\":\"r-1\"}"));
    }

    @Test
    @DisplayName("非对象的帧被拒绝")
    void rejectsNonObjectFrame() {
        assertThrows(IOException.class, () -> AgentEvent.parse(mapper, "[1,2,3]"));
    }

    @Test
    @DisplayName("错误帧能取出 code 与 message")
    void readsErrorFields() throws IOException {
        AgentEvent event = AgentEvent.parse(mapper,
                "{\"event\":\"error\",\"code\":\"WORKSPACE_INIT_FAILED\",\"message\":\"路径越界\",\"retryable\":false}");

        assertTrue(event.isError());
        assertTrue(event.isTerminal());
        assertEquals("WORKSPACE_INIT_FAILED", event.errorCode());
        assertEquals("路径越界", event.errorMessage());
    }

    @Test
    @DisplayName("终止帧不算业务终态")
    void endFrameIsNotTerminalBusinessEvent() throws IOException {
        AgentEvent end = AgentEvent.parse(mapper, "{\"runId\":\"r-1\",\"seq\":9,\"event\":\"__end__\"}");
        assertTrue(end.isEnd());
        // 终止帧只是传输层收尾，不代表 run 成功或失败
        assertFalse(end.isTerminal());
        assertFalse(end.isFailure());
    }

    // ─────────────────────────── 流 ───────────────────────────

    @Test
    @DisplayName("完整跑一轮：事件顺序正确，终止帧收尾，进程归还池中")
    void streamsFullRunAndReturnsWorker() {
        List<AgentEvent> events = service.stream(1L, requestJson("s-1", "做一个登录页"))
                .collectList()
                .block(Duration.ofSeconds(60));

        assertNotNull(events);
        assertFalse(events.isEmpty());

        assertEquals(AgentEvent.RUN_STARTED, events.get(0).event());
        assertEquals(AgentEvent.END, events.get(events.size() - 1).event());
        assertTrue(events.stream().anyMatch(e -> e.event().equals(AgentEvent.RUN_COMPLETED)));

        String text = events.stream()
                .filter(AgentEvent::isTextDelta)
                .map(AgentEvent::textContent)
                .reduce("", String::concat);
        assertTrue(text.contains("做一个登录页"), "应回显用户需求，实际：" + text);

        assertEquals(1, pool.stats().idle(), "正常结束后进程应回到空闲队列");
        assertEquals(0, pool.stats().active());
    }

    @Test
    @DisplayName("seq 单调递增且不重复")
    void seqIsMonotonic() {
        List<AgentEvent> events = service.stream(1L, requestJson("s-2", "x"))
                .collectList()
                .block(Duration.ofSeconds(60));

        assertNotNull(events);
        List<Integer> seqs = events.stream().map(AgentEvent::seq).toList();
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < seqs.size(); i++) {
            expected.add(i);
        }
        assertEquals(expected, seqs, "seq 应从 0 连续递增");
    }

    @Test
    @DisplayName("取消订阅会杀掉 Python 进程，池随后补齐一个可用的新进程")
    void cancellingKillsWorkerAndReplenishes() throws Exception {
        CountDownLatch gotFirst = new CountDownLatch(1);
        Disposable subscription = service.stream(1L, requestJson("s-3", "取消我"))
                .subscribe(event -> gotFirst.countDown());

        assertTrue(gotFirst.await(30, TimeUnit.SECONDS), "应能拿到首帧");
        assertEquals(1, pool.stats().active(), "此时应有一个进程被借出");

        subscription.dispose();

        // 取消会丢弃该进程（状态不可信），随后异步补齐回 coreSize
        boolean ready = false;
        for (int i = 0; i < 100; i++) {
            AgentWorkerPool.PoolStats stats = pool.stats();
            if (stats.idle() >= 1 && stats.active() == 0) {
                ready = true;
                break;
            }
            Thread.sleep(100);
        }
        assertTrue(ready, "取消后应补齐一个可用进程，实际：" + pool.stats());

        // 补齐的进程必须是新的且真的能用
        List<AgentEvent> events = service.stream(1L, requestJson("s-3b", "再来一轮"))
                .collectList()
                .block(Duration.ofSeconds(60));
        assertNotNull(events);
        assertEquals(AgentEvent.END, events.get(events.size() - 1).event(), "补齐后的进程应能正常跑完");
    }

    @Test
    @DisplayName("请求缺工作区时，错误事件正常透出，进程仍可回收")
    void errorEventSurfacesAndWorkerIsRecyclable() {
        String bad = "{\"v\":1,\"runId\":\"s-4\",\"input\":{\"message\":\"x\"}}";

        List<AgentEvent> events = service.stream(1L, bad).collectList().block(Duration.ofSeconds(60));

        assertNotNull(events);
        assertEquals(2, events.size(), "应是错误帧 + 终止帧");
        assertTrue(events.get(0).isError());
        assertEquals("WORKSPACE_INIT_FAILED", events.get(0).errorCode());
        assertTrue(events.get(1).isEnd());

        // 业务错误不应导致进程被丢弃 —— Python 侧活得好好的
        assertEquals(1, pool.stats().idle(), "业务错误后进程应可回收");
    }

    @Test
    @DisplayName("连续两轮复用同一个进程")
    void reusesWorkerAcrossRuns() {
        service.stream(1L, requestJson("s-5a", "第一次")).collectList().block(Duration.ofSeconds(60));
        assertEquals(1, pool.stats().idle());

        service.stream(1L, requestJson("s-5b", "第二次")).collectList().block(Duration.ofSeconds(60));
        assertEquals(1, pool.stats().total(), "不应为第二轮新建进程");
    }
}
