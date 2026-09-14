package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.mapper.AgentRunMapper;
import com.dbts.glyahhaigeneratecode.service.ChatHistoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRunPersistenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    private AgentEvent event(String kind, String id, String summary, String details) {
        var frame = mapper.createObjectNode().put("runId", "123").put("event", kind)
                .put("thinkingId", id).put("summary", summary).put("details", details)
                .put("status", kind.endsWith("completed") ? "completed" : "running");
        return new AgentEvent("123", 1, 1, kind, frame);
    }

    @Test void preservesOrderAndDetailsAcrossCompletionAndPersistsWithoutFinalAnswer() throws Exception {
        var history = mock(ChatHistoryService.class);
        var persistence = new AgentRunPersistence(history, mock(AgentRunMapper.class));
        var request = AgentRunRequest.builder().runId("123").appId("1").userId("2").roundId("3").build();
        persistence.tap(UserFacingOutputHandler.apply(Flux.just(
                event("thinking.started", "123:thinking:1", "构思校徽", "确定构图"),
                event("thinking.delta", "123:thinking:1", "设计星空校徽", "蓝紫配色"),
                event("thinking.started", "123:thinking:2", "检查校徽", "检查布局"),
                event("thinking.completed", "123:thinking:1", "", ""),
                event("thinking.completed", "123:thinking:2", "", "")
        )), request).blockLast();
        var json = ArgumentCaptor.forClass(String.class);
        verify(history, times(1)).saveAgentMessage(eq(1L), eq(2L), eq("本轮未产生最终回答。"), json.capture());
        var rows = mapper.readTree(json.getValue());
        assertEquals(2, rows.size());
        assertEquals("123:thinking:1", rows.get(0).path("thinkingId").asText());
        assertEquals("设计星空校徽", rows.get(0).path("summary").asText());
        assertEquals(2, rows.get(0).path("details").size());
        assertEquals("completed", rows.get(0).path("status").asText());
    }
}
