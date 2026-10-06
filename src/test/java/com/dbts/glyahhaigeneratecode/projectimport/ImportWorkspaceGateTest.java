package com.dbts.glyahhaigeneratecode.projectimport;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportWorkspaceGateTest {
    @Test void legacyWorkspacesRemainUnaffectedAndPendingImportsAreClosed() {
        var repository = mock(ImportTaskRepository.class);
        var gate = new ImportWorkspaceGate(repository);
        assertNull(gate.requireReadable("html_12"));
        when(repository.publicationByApp(12)).thenReturn(new ImportTaskRepository.Publication(java.util.UUID.randomUUID(), 12, 7, "PUBLISHING", null, 1, java.time.Instant.now()));
        assertThrows(MyException.class, () -> gate.requireReadable("project_12"));
        assertThrows(MyException.class, () -> gate.requireReadable("PROJECT_00012"));
    }
    @Test void readyImportsStillRequireOwnerForStaticPreview() {
        var repository = mock(ImportTaskRepository.class);
        var gate = new ImportWorkspaceGate(repository);
        when(repository.publicationByApp(12)).thenReturn(new ImportTaskRepository.Publication(java.util.UUID.randomUUID(), 12, 7, "READY", "a".repeat(64), 1, java.time.Instant.now()));
        assertNotNull(gate.requireReadable("project_12"));
        assertThrows(MyException.class, () -> gate.authorizeStatic("project_12", null));
        assertThrows(MyException.class, () -> gate.authorizeStatic("project_12", 8L));
        assertTrue(gate.authorizeStatic("project_12", 7L));
        assertThrows(MyException.class, () -> gate.requireLegacyExecutionAllowed(12L));
        assertDoesNotThrow(() -> gate.requireLegacyExecutionAllowed(13L));
    }
    @Test void agentEditingOpensOnlyAfterPublicationIsReady() {
        var repository = mock(ImportTaskRepository.class);
        var gate = new ImportWorkspaceGate(repository);
        assertDoesNotThrow(() -> gate.requireAgentEditAllowed(13L));
        assertDoesNotThrow(() -> gate.requireAgentEditAllowed(null));
        when(repository.publicationByApp(12)).thenReturn(new ImportTaskRepository.Publication(java.util.UUID.randomUUID(), 12, 7, "PUBLISHING", null, 1, java.time.Instant.now()));
        assertThrows(MyException.class, () -> gate.requireAgentEditAllowed(12L));
        when(repository.publicationByApp(12)).thenReturn(new ImportTaskRepository.Publication(java.util.UUID.randomUUID(), 12, 7, "READY", "a".repeat(64), 1, java.time.Instant.now()));
        assertDoesNotThrow(() -> gate.requireAgentEditAllowed(12L));
    }
}
