package com.dbts.glyahhaigeneratecode.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentDeadlineTest {
    @Test
    void hardDeadlineCancelsWorkerEvenWhenEventsKeepArriving() throws Exception {
        var pool = org.mockito.Mockito.mock(AgentWorkerPool.class);
        var worker = org.mockito.Mockito.mock(AgentWorker.class);
        var props = new AgentProperties();
        props.setRunDeadline(Duration.ofMillis(150));
        org.mockito.Mockito.when(pool.acquire()).thenReturn(worker);
        org.mockito.Mockito.when(worker.readEvent(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            Thread.sleep(10);
            return "{\"runId\":\"1\",\"event\":\"text.delta\",\"seq\":1,\"ts\":1,\"text\":\"tick\"}";
        });
        org.mockito.Mockito.doAnswer(call -> {
            org.mockito.Mockito.when(worker.readEvent(org.mockito.ArgumentMatchers.any())).thenReturn(null);
            return null;
        }).when(pool).discard(worker);
        var service = new AgentRunService(pool, props, new ObjectMapper(),
                org.mockito.Mockito.mock(AppRunCoordinator.class),
                org.mockito.Mockito.mock(AgentRunPersistence.class),
                org.mockito.Mockito.mock(com.dbts.glyahhaigeneratecode.projectimport.ImportAgentEditPreparation.class),
                org.mockito.Mockito.mock(com.dbts.glyahhaigeneratecode.projectimport.ImportAgentEditExecution.class));
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.streamUncoordinated("{}").blockLast(Duration.ofSeconds(3)));
        org.mockito.Mockito.verify(pool, org.mockito.Mockito.timeout(1000)).discard(worker);
        org.mockito.Mockito.verify(pool, org.mockito.Mockito.never()).release(worker);
    }

    @Test
    void defaultsAllowThirtyMinutesAndPreserveExplicitLimits() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(Duration.ofMinutes(30), new AgentProperties().getRunDeadline());
        var defaults = AgentRunRequest.builder().build().toFrameJson(mapper);
        assertEquals(1_800_000L, mapper.readTree(defaults).path("limits").path("deadlineMs").asLong());
        var explicit = AgentRunRequest.builder().deadlineMs(12_000L).build().toFrameJson(mapper);
        assertEquals(12_000L, mapper.readTree(explicit).path("limits").path("deadlineMs").asLong());
    }
}
