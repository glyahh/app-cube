package com.dbts.glyahhaigeneratecode.runtime;

import com.dbts.glyahhaigeneratecode.config.RuntimeProperties;
import com.dbts.glyahhaigeneratecode.config.WorkspaceProperties;
import com.dbts.glyahhaigeneratecode.mapper.AppRuntimeMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.AppRuntime;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RuntimeOrchestratorTest {
    @Test
    void liveContainerIsNotRunningUntilServiceIsReady() {
        var manager = mock(RuntimeManager.class);
        var mapper = mock(AppRuntimeMapper.class);
        var record = AppRuntime.builder().id(1L).appId(2L).runNo(1)
                .containerName("container").gatewayPort(18080).status("STARTING").build();
        when(mapper.selectListByQuery(any())).thenReturn(List.of(record));
        when(manager.containerStatus("container")).thenReturn("running");
        var service = new RuntimeOrchestrator(manager, mock(RuntimeSpecResolver.class),
                mock(RuntimeLogService.class), mock(RuntimeMetricsStore.class), mock(WorkspaceVersioner.class),
                new RuntimeProperties(), new WorkspaceProperties(), mapper,
                mock(RuntimeIncidentService.class), mock(RuntimeTrafficStats.class));

        var starting = service.status(2L, Path.of("workspace"));
        assertEquals("STARTING", starting.getStatus());
        assertFalse(starting.getRunning());

        when(manager.isReady("container", 18080)).thenReturn(true);
        var running = service.status(2L, Path.of("workspace"));
        assertEquals("RUNNING", running.getStatus());
        assertTrue(running.getRunning());
        assertEquals("RUNNING", record.getStatus());
    }
}
