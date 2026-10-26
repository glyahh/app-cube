package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UserFacingOutputHandlerTest {

    @Test
    void executeExitCodeSurvivesPublicProjection() {
        AgentEvent raw = event("tool.finished", "internal");
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw.frame()).put("toolName", "execute").put("status", "failed")
                .put("exitCode", 127).put("outputPreview", "python: command not found");
        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();
        assertNotNull(safe);
        assertEquals(127, safe.frame().path("exitCode").asInt(-1));
        assertEquals("failed", safe.frame().path("status").asText());
    }

    @Test
    void legacyAndInvalidExitCodesAreNotInvented() {
        for (String tool : List.of("execute", "write_file")) {
            AgentEvent raw = event("tool.finished", "internal");
            var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
            frame.put("toolName", tool).put("status", "succeeded");
            assertFalse(UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst().frame().has("exitCode"));
            frame.put("exitCode", "127");
            assertFalse(UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst().frame().has("exitCode"));
        }
    }
    @Test void preservesOnlySafeImportFailureKeys() {
        for (String key : List.of("IMPORT_EDIT_REPEATED_COMMAND_REJECTION", "IMPORT_EDIT_MODEL_BUDGET_EXHAUSTED",
                "IMPORT_EDIT_NO_CHANGES", "IMPORT_EDIT_CANDIDATE_VERIFY_FAILED", "/private/secret", "IMPORT_BAD\nsecret")) {
            AgentEvent raw = event(AgentEvent.ERROR, "private secret");
            var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
            frame.put("failureKey", key).put("message", "private secret").put("stack", "private secret");
            AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();
            assertNotNull(safe);
            assertEquals(key.matches("IMPORT_[A-Z_]{1,70}"), safe.frame().has("failureKey"));
            assertFalse(safe.frame().toString().contains("private secret"));
            assertFalse(safe.frame().has("stack"));
        }
    }
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentEvent event(String kind, String content) {
        var frame = mapper.createObjectNode().put("event", kind).put("content", content)
                .put("runId", "123").put("seq", 1).put("ts", 1);
        return new AgentEvent("123", 1, 1, kind, frame);
    }

    @Test void buffersSplitWordsAndPublishesOnlySanitizedAnswer() {
        List<AgentEvent> events = UserFacingOutputHandler.apply(Flux.just(
                event("text.delta", "sand"), event("text.delta", "box"),
                event("text.message", "sandbox 不可用\n游戏使用 HTML Canvas"))).collectList().block();
        assertNotNull(events);
        assertEquals(2, events.size());
        assertEquals("部分内部执行信息已省略\n游戏使用 HTML Canvas", events.getFirst().textContent());
        assertEquals("answer", events.getFirst().frame().path("category").asText());
        assertEquals(events.getFirst().textContent(), events.getLast().textContent());
    }

    @Test void historiesKeepUserInputAndHideErrors() {
        assertEquals("sandbox", UserFacingOutputHandler.history("user", "sandbox"));
        assertFalse(UserFacingOutputHandler.history("ai", "C:\\Users\\example\\secret").contains("secret"));
        assertFalse(UserFacingOutputHandler.history("error", "Traceback docker").contains("docker"));
        assertEquals("游戏支持 WASD 和 HTML", UserFacingOutputHandler.sanitize("游戏支持 WASD 和 HTML"));
    }

    @Test void forwardsExecutePreviewButStillProtectsSecrets() {
        AgentEvent raw = event("tool.finished", "internal");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("toolName", "execute").put("status", "succeeded")
                .put("inputPreview", "{\"command\":\"node --version\"}")
                .put("outputPreview", "v22.0.0")
                .put("workspace", "secret");
        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();
        assertNotNull(safe);
        assertEquals("succeeded", safe.status());
        // 普通命令和输出可通过
        assertTrue(safe.frame().path("inputPreview").asText("").contains("node --version"));
        assertTrue(safe.frame().path("outputPreview").asText("").contains("v22.0.0"));
        // 密钥、内部路径、堆栈仍受保护
        assertFalse(safe.frame().has("workspace"));
        assertFalse(safe.frame().has("command"));
    }

    @Test void executeWithSensitiveContentIsProtected() {
        AgentEvent raw = event("tool.finished", "internal");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("toolName", "execute").put("status", "succeeded")
                .put("outputPreview", "C:\\Users\\admin\\secret docker sandbox");
        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();
        assertNotNull(safe);
        // 含敏感关键词的输出被 sanitize 替换，清洗后全空则不写入字段
        assertFalse(safe.frame().path("outputPreview").asText("").contains("docker"));
    }

    @Test void nonExecuteToolStillHidesPreview() {
        AgentEvent raw = event("tool.finished", "internal");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("toolName", "write_file").put("status", "succeeded")
                .put("outputPreview", "file content");
        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();
        assertNotNull(safe);
        assertFalse(safe.frame().has("outputPreview"));
    }

    @Test void forwardsThinkingEventsAsIndependentSegments() {
        AgentEvent raw = event("thinking.delta", "ignored");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("thinkingId", "123:thinking:model:1").put("summary", "正在构思校徽的视觉结构")
                .put("details", "确定构图").put("status", "running");
        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();
        assertNotNull(safe);
        assertEquals("thinking", safe.frame().path("category").asText());
        assertEquals("123:thinking:model:1", safe.frame().path("thinkingId").asText());
        assertEquals("正在构思校徽的视觉结构", safe.frame().path("summary").asText());
        assertEquals("确定构图", safe.frame().path("details").asText());
    }

    @Test void overflowAndIncompleteFailureNeverPassRawText() {
        assertEquals(UserFacingOutputHandler.OMITTED, UserFacingOutputHandler.sanitize("x".repeat(65537)));
        var result = UserFacingOutputHandler.apply(Flux.just(event("text.delta", "workspace"),
                event("error", "raw error"))).collectList().block();
        assertEquals(1, result.size());
        assertFalse(result.getFirst().frame().toString().contains("workspace"));
    }

    @Test void fallbackOnlyForwardsFixedPublicFields() {
        AgentEvent raw = event(AgentEvent.MODEL_ROUTE_FALLBACK, "ignored");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("fromRoute", "malicious").put("toRoute", "malicious")
                .put("reason", "malicious").put("proxyUrl", "http://secret:1234");

        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();

        assertNotNull(safe);
        assertEquals("proxy", safe.frame().path("fromRoute").asText());
        assertEquals("direct", safe.frame().path("toRoute").asText());
        assertEquals("connect_error", safe.frame().path("reason").asText());
        assertFalse(safe.frame().has("proxyUrl"));
    }

    @Test void forwardsValidatedImportModelAuditWithoutRequestBody() {
        AgentEvent raw = event("import.edit.model.audit", "ignored");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("editId", "123e4567-e89b-12d3-a456-426614174000")
                .put("count", 1).put("blocked", 0)
                .put("requestBody", "secret must never leave the worker");
        frame.putArray("digests").add("a".repeat(64));

        AgentEvent safe = UserFacingOutputHandler.apply(Flux.just(raw)).blockFirst();

        assertNotNull(safe);
        assertEquals("import.edit.model.audit", safe.event());
        assertEquals(1, safe.frame().path("count").asInt());
        assertEquals(1, safe.frame().path("digests").size());
        assertFalse(safe.frame().has("requestBody"));
    }

    @Test void dropsInvalidImportModelAudit() {
        AgentEvent raw = event("import.edit.model.audit", "ignored");
        var frame = (com.fasterxml.jackson.databind.node.ObjectNode) raw.frame();
        frame.put("editId", "not-an-edit-id").put("count", 1).put("blocked", 0);
        frame.putArray("digests").add("short");

        assertTrue(UserFacingOutputHandler.apply(Flux.just(raw)).collectList().block().isEmpty());
    }
    @Test void completeStopMessageAndCancelledSummarySurviveProjection() {
        String text = "本轮已停止。";
        AgentEvent terminal = event(AgentEvent.RUN_COMPLETED, "ignored");
        ((com.fasterxml.jackson.databind.node.ObjectNode) terminal.frame())
                .put("status", "cancelled").put("summary", text);
        var events = UserFacingOutputHandler.apply(Flux.just(
                event(AgentEvent.TEXT_DELTA, "private unfinished candidate"),
                event(AgentEvent.TEXT_MESSAGE, text), terminal, event(AgentEvent.END, "")))
                .collectList().block();
        assertNotNull(events);
        assertEquals(List.of(AgentEvent.TEXT_DELTA, AgentEvent.TEXT_MESSAGE, AgentEvent.RUN_COMPLETED, AgentEvent.END),
                events.stream().map(AgentEvent::event).toList());
        assertEquals(text, events.getFirst().textContent());
        var completion = events.get(2);
        assertEquals("cancelled", completion.status());
        assertEquals(text, completion.frame().path("summary").asText());
        assertTrue(completion.frame().path("summaryDelivered").asBoolean());
        assertFalse(events.toString().contains("private unfinished candidate"));
    }

    @Test void terminalSummaryIsSanitizedAndDoesNotFlushUnconfirmedDelta() {
        AgentEvent terminal = event(AgentEvent.RUN_COMPLETED, "ignored");
        ((com.fasterxml.jackson.databind.node.ObjectNode) terminal.frame())
                .put("status", "cancelled").put("summary", "docker /tmp/private-data");
        var events = UserFacingOutputHandler.apply(Flux.just(
                event(AgentEvent.TEXT_DELTA, "unconfirmed answer"), terminal)).collectList().block();
        assertNotNull(events);
        assertEquals(1, events.size());
        assertEquals("cancelled", events.getFirst().status());
        assertEquals(UserFacingOutputHandler.OMITTED, events.getFirst().frame().path("summary").asText());
        assertFalse(events.getFirst().frame().path("summaryDelivered").asBoolean());
        assertFalse(events.toString().contains("unconfirmed answer"));
    }

}
