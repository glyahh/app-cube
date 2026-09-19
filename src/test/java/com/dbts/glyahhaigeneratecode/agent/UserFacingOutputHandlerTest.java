package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UserFacingOutputHandlerTest {
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
}
