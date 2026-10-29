package com.dbts.glyahhaigeneratecode.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceSnapshotServiceTest {

    @TempDir
    Path workspace;

    private void write(String relative, String content) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void restoreReturnsOriginalBytes() throws IOException {
        write("src/a.txt", "before");
        write("b.txt", "b-before");
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);

        SnapshotRecord snapshot = service.create(workspace, List.of("src/a.txt", "b.txt"), "test");
        Files.writeString(workspace.resolve("src/a.txt"), "after");

        service.restore(workspace, snapshot.id(), false);

        assertEquals("before", Files.readString(workspace.resolve("src/a.txt")));
        assertEquals("b-before", Files.readString(workspace.resolve("b.txt")));
        assertEquals(SnapshotRecord.Status.PENDING, service.find(snapshot.id()).orElseThrow().status());
    }

    @Test
    void committedSnapshotRequiresForceWhenContentChanged() throws IOException {
        write("a.txt", "before");
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);
        SnapshotRecord snapshot = service.create(workspace, List.of("a.txt"), "test");
        Files.writeString(workspace.resolve("a.txt"), "committed");
        service.markCommitted(workspace, snapshot.id());

        Files.writeString(workspace.resolve("a.txt"), "edited later");
        assertThrows(IOException.class, () -> service.restore(workspace, snapshot.id(), false));
        assertEquals("edited later", Files.readString(workspace.resolve("a.txt")));

        service.restore(workspace, snapshot.id(), true);
        assertEquals("before", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);
        assertThrows(IOException.class, () -> service.create(workspace, List.of("../escape.txt"), "test"));
    }

    @Test
    void failedSnapshotRestoresWithoutForceAndKeepsRecoveryHashes() throws IOException {
        write("a.txt", "before");
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);
        SnapshotRecord snapshot = service.create(workspace, List.of("a.txt"), "test");
        Files.writeString(workspace.resolve("a.txt"), "half-written");
        service.markFailed(workspace, snapshot.id(), "simulated");

        service.restore(workspace, snapshot.id(), false);

        assertEquals("before", Files.readString(workspace.resolve("a.txt")));
        SnapshotRecord restored = service.find(snapshot.id()).orElseThrow();
        assertNotEquals(restored.beforeHashes().get("a.txt"), restored.recoveryHashes().get("a.txt"));
        assertEquals(SnapshotRecord.Status.FAILED, restored.status());
    }

    @Test
    void addedCandidateFilesAreNotSnapshotRequirements() throws IOException {
        write("a.txt", "before");
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);

        SnapshotRecord snapshot = service.create(workspace, List.of("a.txt", "new-file.txt"), "test");

        assertEquals(java.util.Set.of("a.txt"), snapshot.beforeHashes().keySet());
        service.restore(workspace, snapshot.id(), false);
        assertEquals("before", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void deletedFileIsRestoredAfterCommit() throws IOException {
        write("a.txt", "before");
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(
                workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);
        SnapshotRecord snapshot = service.create(workspace, List.of("a.txt"), "test");

        Files.delete(workspace.resolve("a.txt"));
        service.markCommitted(workspace, snapshot.id());
        service.restore(workspace, snapshot.id(), false);

        assertEquals("before", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void cleanupKeepsSnapshotsPerWorkspace() throws IOException {
        Path other = workspace.resolveSibling(workspace.getFileName() + "-other");
        Files.createDirectories(other);
        write("a.txt", "a");
        Files.writeString(other.resolve("b.txt"), "b");
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(
                workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 1);

        SnapshotRecord a1 = service.create(workspace, List.of("a.txt"), "a1");
        service.markCommitted(workspace, a1.id());
        SnapshotRecord b1 = service.create(other, List.of("b.txt"), "b1");
        service.markCommitted(other, b1.id());
        SnapshotRecord a2 = service.create(workspace, List.of("a.txt"), "a2");

        assertFalse(service.find(a1.id()).isPresent());
        assertTrue(service.find(b1.id()).isPresent());
        assertTrue(service.find(a2.id()).isPresent());
    }

    @Test
    void snapshotDirectoryDoesNotChangeContentVersion() throws IOException {
        write("a.txt", "x");
        WorkspaceVersioner versioner = new WorkspaceVersioner(new com.dbts.glyahhaigeneratecode.config.WorkspaceProperties());
        WorkspaceSnapshotService service = new WorkspaceSnapshotService(workspace.resolveSibling(workspace.getFileName() + "-snapshots"), 20);
        String before = versioner.contentVersion(workspace);

        service.create(workspace, List.of("a.txt"), "test");

        assertEquals(before, versioner.contentVersion(workspace));
    }
}
