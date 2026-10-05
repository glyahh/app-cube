package com.dbts.glyahhaigeneratecode.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectImportPublicationFilesTest {
    @TempDir Path root;
    @Test void preparesIndependentRealTreeAndRechecksEveryByte() throws Exception {
        var staging = new ProjectImportStaging(root.resolve("private"), List.of(root.resolve("output")));
        var id = UUID.randomUUID(); staging.create(id);
        var file = staging.receive(id, 0, new FileEntry("src/中文 文件.bin", 3), new ByteArrayInputStream(new byte[]{0, -1, 4}), () -> true, Duration.ofSeconds(2));
        var hash = staging.seal(id, List.of(file));
        Path prepared = staging.prepareWorkspace(id, List.of(file), () -> true, Duration.ofSeconds(2));
        assertEquals(hash, staging.verifyWorkspace(prepared, List.of(file), () -> true, Duration.ofSeconds(2)));
        assertArrayEquals(new byte[]{0, -1, 4}, Files.readAllBytes(prepared.resolve(file.path())));
        Files.write(prepared.resolve(file.path()), new byte[]{1, 2, 3});
        assertThrows(java.io.IOException.class, () -> staging.verifyWorkspace(prepared, List.of(file), () -> true, Duration.ofSeconds(2)));
        assertEquals(hash, staging.seal(id, List.of(file)));
    }
    @Test void cannotOverwriteExistingCandidateOrIgnoreExtraFiles() throws Exception {
        var staging = new ProjectImportStaging(root.resolve("private"), List.of());
        var id = UUID.randomUUID(); staging.create(id);
        var file = staging.receive(id, 0, new FileEntry("a", 0), java.io.InputStream.nullInputStream(), () -> true, Duration.ofSeconds(2));
        var prepared = staging.prepareWorkspace(id, List.of(file), () -> true, Duration.ofSeconds(2));
        assertThrows(java.io.IOException.class, () -> staging.prepareWorkspace(id, List.of(file), () -> true, Duration.ofSeconds(2)));
        Files.writeString(prepared.resolve("extra"), "not in manifest");
        assertThrows(java.io.IOException.class, () -> staging.verifyWorkspace(prepared, List.of(file), () -> true, Duration.ofSeconds(2)));
    }
    @Test void revokedCopyNeverPublishesAndCleanupRetainsOtherTasks() throws Exception {
        var staging = new ProjectImportStaging(root.resolve("private"), List.of());
        var id = UUID.randomUUID(); staging.create(id);
        var other = UUID.randomUUID(); staging.create(other);
        var file = staging.receive(id, 0, new FileEntry("a", 0), java.io.InputStream.nullInputStream(), () -> true, Duration.ofSeconds(2));
        assertThrows(java.io.IOException.class, () -> staging.prepareWorkspace(id, List.of(file), () -> false, Duration.ofSeconds(2)));
        staging.cleanupPreparedWorkspace(id);
        staging.cleanup(id);
        assertTrue(Files.isDirectory(root.resolve("private/" + other)));
    }
}
