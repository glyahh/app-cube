package com.dbts.glyahhaigeneratecode.agent;

import com.dbts.glyahhaigeneratecode.mapper.AppMapper;
import com.dbts.glyahhaigeneratecode.mapper.UserMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectTitleServiceTest {
    final AppMapper apps = mock(AppMapper.class);
    final UserMapper users = mock(UserMapper.class);
    final AgentRunService runs = mock(AgentRunService.class);
    final ObjectMapper json = new ObjectMapper();
    final ProjectTitleService service = new ProjectTitleService(apps, users, runs, json);
    final AgentRunRequest request = AgentRunRequest.builder().appId("1").userId("2").roundId("3")
            .message("制作一个空间站").build();

    @Test void laterRoundsOrReconnectDoNotCallModel() {
        service.generate(request);
        verifyNoInteractions(runs, users);
    }
    @Test void disabledPreferenceStillConsumesFirstAttempt() {
        when(apps.claimAutoTitle(1L, 2L, 3L)).thenReturn(1);
        when(users.selectOneById(2L)).thenReturn(User.builder().autoNameProject(false).build());
        service.generate(request);
        verifyNoInteractions(runs);
    }
    @Test void successfulTitleUsesIndependentTaskAndCompareAndSet() throws Exception {
        when(apps.claimAutoTitle(1L, 2L, 3L)).thenReturn(1);
        when(users.selectOneById(2L)).thenReturn(User.builder().build());
        when(apps.selectOneById(1L)).thenReturn(App.builder().appName("原名").build());
        when(runs.streamUncoordinated(anyString())).thenAnswer(call -> {
            var frame = json.readTree((String) call.getArgument(0));
            assertEquals("title", frame.path("input").path("taskKind").asText());
            assertEquals("app:1:title", frame.path("threadId").asText());
            return Flux.just(new AgentEvent("t", 1, 0, AgentEvent.TEXT_MESSAGE,
                    json.createObjectNode().put("content", "轨道空间站")),
                    new AgentEvent("t", 2, 0, AgentEvent.RUN_COMPLETED,
                    json.createObjectNode().put("status", "succeeded")));
        });
        service.generate(request);
        verify(apps).finishAutoTitle(1L, "原名", null, "轨道空间站");
    }
    @Test void failureDoesNotRename() {
        when(apps.claimAutoTitle(1L, 2L, 3L)).thenReturn(1);
        when(users.selectOneById(2L)).thenReturn(User.builder().build());
        when(apps.selectOneById(1L)).thenReturn(App.builder().appName("原名").build());
        when(runs.streamUncoordinated(anyString())).thenReturn(Flux.error(new IllegalStateException()));
        service.generate(request);
        verify(apps, never()).finishAutoTitle(any(), any(), any(), any());
    }
    @Test void rejectsProseAndLimitsTitleLength() {
        assertEquals("", ProjectTitleService.cleanTitle("标题\n解释"));
        assertEquals("空间站", ProjectTitleService.cleanTitle("「空间站」"));
        assertEquals(24, ProjectTitleService.cleanTitle("站".repeat(40)).length());
    }
}
