package com.dbts.glyahhaigeneratecode.projectimport;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImportInstallStagesTest {

    @Test
    void interruptedStagesKeepIdentityAndDoNotInventDuration() {
        var start = Instant.parse("2026-10-09T00:00:00Z");
        var started = new ImportStartupInstallJob.Stage(
                "NPM_INSTALL", "NPM_INSTALL", "STARTED", null, null, start, null, null, "execution-1");
        var otherExecution = new ImportStartupInstallJob.Stage(
                "NPM_INSTALL", "NPM_INSTALL", "SUCCEEDED", null, 0,
                start, start.plusSeconds(1), 1000L, "execution-2");
        var job = mock(ImportStartupInstallJob.class);
        when(job.stages()).thenReturn(List.of(started, otherExecution));

        assertTrue(ImportInstallStages.unfinished(job, started));
        assertEquals("NPM_INSTALL", ImportInstallStages.failureStage(job));

        var terminal = ImportInstallStages.terminalStages(job, ImportStartupInstallJob.State.CANCELLED);
        var cancelled = terminal.getFirst();
        assertEquals("CANCELLED", cancelled.status());
        assertEquals("INSTALL_CANCELLED", cancelled.reason());
        assertEquals(start, cancelled.startTime());
        assertEquals("execution-1", cancelled.executionId());
        assertNull(cancelled.endTime());
        assertNull(cancelled.durationMs());
        assertEquals(ImportInstallStages.NAMES.size(), terminal.size());
        assertTrue(terminal.stream().skip(1).allMatch(stage -> "NOT_STARTED".equals(stage.status())));
    }

    @Test
    void completedStageIsNotInterruptedAndExplicitFailureTakesPriority() {
        var start = Instant.parse("2026-10-09T00:00:00Z");
        var started = new ImportStartupInstallJob.Stage(
                "NPM_INSTALL", "NPM_INSTALL", "STARTED", null, null, start, null, null, "execution-1");
        var finished = new ImportStartupInstallJob.Stage(
                "NPM_INSTALL", "NPM_INSTALL", "SUCCEEDED", null, 0,
                start, start.plusSeconds(1), 1000L, "execution-1");
        var refused = new ImportStartupInstallJob.Stage(
                "VERIFY", "VERIFY", "REFUSED", "LIMIT_NOT_APPLIED", null, null, null, null, null);
        var job = mock(ImportStartupInstallJob.class);
        when(job.stages()).thenReturn(List.of(started, finished, refused));

        assertFalse(ImportInstallStages.unfinished(job, started));
        assertEquals("VERIFY", ImportInstallStages.failureStage(job));
        assertTrue(ImportInstallStages.terminalStages(job, ImportStartupInstallJob.State.FAILED)
                .stream().allMatch(stage -> "NOT_STARTED".equals(stage.status())));
    }
}
