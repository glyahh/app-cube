package com.dbts.glyahhaigeneratecode.knowledge;

import com.dbts.glyahhaigeneratecode.agent.AgentRunFinishedEvent;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.KnowledgeTask;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeAutoTriggerTest {
    private final KnowledgeTaskService tasks = mock(KnowledgeTaskService.class);
    private final AppService apps = mock(AppService.class);
    private final WorkspacePathResolver paths = mock(WorkspacePathResolver.class);
    private final KnowledgeAutoTrigger trigger = new KnowledgeAutoTrigger(tasks, apps, paths);

    private void project() {
        App app = new App();
        app.setCodeGenType("auto");
        app.setDashboardFeatures("[\"knowledge\"]");
        when(apps.getById(1L)).thenReturn(app);
        when(paths.outputDir(eq(1L), any())).thenReturn(Path.of("workspace"));
    }

    @Test
    void firstSuccessfulGenerationCreatesKnowledge() {
        project();
        when(tasks.latestSuccessful(1L)).thenReturn(List.of());
        trigger.onRunFinished(new AgentRunFinishedEvent(1L, 2L, "run", "SUCCEEDED"));
        verify(tasks, timeout(2000)).submit(eq(1L), eq(2L), any());
    }

    @Test
    void existingKnowledgeWaitsForManualUpdateAfterCodeChanges() {
        project();
        when(tasks.latestSuccessful(1L)).thenReturn(List.of(new KnowledgeTask()));
        trigger.onRunFinished(new AgentRunFinishedEvent(1L, 2L, "run", "SUCCEEDED"));
        verify(tasks, timeout(2000)).latestSuccessful(1L);
        verify(tasks, after(100).never()).submit(anyLong(), anyLong(), any());
    }

    @Test
    void reenablingKnowledgeDoesNotReplaceExistingSnapshot() {
        when(tasks.latestSuccessful(1L)).thenReturn(List.of(new KnowledgeTask()));
        trigger.onDashboardFeaturesChanged(new DashboardFeaturesChangedEvent(1L, 2L, List.of("knowledge"), true));
        verify(tasks, timeout(2000)).latestSuccessful(1L);
        verify(tasks, after(100).never()).submit(anyLong(), anyLong(), any());
    }
}
