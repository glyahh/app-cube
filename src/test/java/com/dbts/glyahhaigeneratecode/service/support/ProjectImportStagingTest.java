package com.dbts.glyahhaigeneratecode.service.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.dbts.glyahhaigeneratecode.service.support.ProjectImportManifest.*;
import static org.junit.jupiter.api.Assertions.*;

class ProjectImportStagingTest {
    @TempDir Path temp;
    private final Duration timeout = Duration.ofSeconds(5);

    private ProjectImportStaging staging() throws IOException {
        return new ProjectImportStaging(temp.resolve("private"), List.of(temp.resolve("output"), temp.resolve("deploy")));
    }

    @Test
    void storesActualBinaryBytesAndHashesWithoutTouchingOriginal() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        byte[] content = {0, 1, -1, 13, 10};
        Path original = temp.resolve("原工程.bin");
        Files.write(original, content);
        var receipt = store.receive(id, 0, new FileEntry("工程/二进制 文件.bin", content.length),
                Files.newInputStream(original), () -> true, timeout);
        assertArrayEquals(content, Files.readAllBytes(original));
        assertArrayEquals(content, Files.readAllBytes(temp.resolve("private/" + id + "/0.bin")));
        assertEquals(content.length, receipt.size());
        assertEquals(64, receipt.sha256().length());
        assertEquals(64, store.seal(id, List.of(receipt)).length());
        assertFalse(Files.exists(temp.resolve("output")));
    }

    @Test
    void sealsZeroBytesAndDifferentUploadOrdersToSameContentIdentity() throws Exception {
        var store = staging();
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        store.create(first);
        store.create(second);
        var a = store.receive(first, 0, new FileEntry("a", 0), InputStream.nullInputStream(), () -> true, timeout);
        var b = store.receive(first, 1, new FileEntry("b", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout);
        var c = store.receive(second, 0, new FileEntry("b", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout);
        var d = store.receive(second, 1, new FileEntry("a", 0), InputStream.nullInputStream(), () -> true, timeout);
        assertEquals(store.seal(first, List.of(a, b)), store.seal(second, List.of(c, d)));
    }

    @Test
    void rejectsShortLongAndFailingStreamsAndRemovesOnlyItsPartialFile() throws Exception {
        for (int length : List.of(0, 2)) {
            var store = staging();
            var id = UUID.randomUUID();
            store.create(id);
            assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1),
                    new ByteArrayInputStream(new byte[length]), () -> true, timeout));
            assertFalse(Files.exists(temp.resolve("private/" + id + "/0.bin")));
        }
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        InputStream failing = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("FAKE_SECRET_DO_NOT_LOG"); }
        };
        var error = assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1), failing, () -> true, timeout));
        assertFalse(error.toString().contains("FAKE_SECRET"));
        assertNull(error.getCause());
    }

    @Test
    void closesStreamOnSuccessAndFailureAndDoesNotOverwriteExistingFile() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        var closed = new AtomicBoolean();
        InputStream input = new ByteArrayInputStream(new byte[]{1}) {
            @Override public void close() { closed.set(true); }
        };
        store.receive(id, 0, new FileEntry("a", 1), input, () -> true, timeout);
        assertTrue(closed.get());
        assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1),
                new ByteArrayInputStream(new byte[]{2}), () -> true, timeout));
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(temp.resolve("private/" + id + "/0.bin")));
    }

    @Test
    void revokedPermissionAndExpiredDeadlineNeverLeaveCompletePayload() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1),
                new ByteArrayInputStream(new byte[]{1}), () -> false, timeout));
        assertFalse(Files.exists(temp.resolve("private/" + id + "/0.bin")));
        assertThrows(IllegalArgumentException.class, () -> store.receive(id, 0, new FileEntry("a", 1),
                new ByteArrayInputStream(new byte[]{1}), () -> true, Duration.ZERO));
        InputStream cancelling = new ByteArrayInputStream(new byte[]{1}) {
            @Override public synchronized int read(byte[] bytes, int offset, int length) {
                Thread.currentThread().interrupt();
                return super.read(bytes, offset, length);
            }
        };
        try {
            assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1), cancelling, () -> true, timeout));
        } finally { Thread.interrupted(); }
        assertFalse(Files.exists(temp.resolve("private/" + id + "/0.bin")));
    }

    @Test
    void contentMutationWithSameSizeAndMtimeInvalidatesSeal() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        var receipt = store.receive(id, 0, new FileEntry("a", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout);
        Path path = temp.resolve("private/" + id + "/0.bin");
        FileTime originalTime = Files.getLastModifiedTime(path);
        Files.write(path, new byte[]{2});
        Files.setLastModifiedTime(path, originalTime);
        assertThrows(IOException.class, () -> store.seal(id, List.of(receipt)));
    }

    @Test
    void incompleteExtraOrDuplicateReceiptsCannotSeal() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        var receipt = store.receive(id, 0, new FileEntry("a", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout);
        assertThrows(IOException.class, () -> store.seal(id, List.of()));
        assertThrows(IOException.class, () -> store.seal(id, List.of(receipt, receipt)));
        Files.writeString(temp.resolve("private/" + id + "/extra.bin"), "x");
        assertThrows(IOException.class, () -> store.seal(id, List.of(receipt)));
    }

    @Test
    void cleanupIsIdempotentAndOnlyDeletesOneOwnedTask() throws Exception {
        var store = staging();
        var a = UUID.randomUUID();
        var b = UUID.randomUUID();
        store.create(a);
        store.create(b);
        store.receive(b, 0, new FileEntry("a", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout);
        store.cleanup(a);
        store.cleanup(a);
        assertTrue(Files.exists(temp.resolve("private/" + b + "/0.bin")));
        assertFalse(Files.exists(temp.resolve("private/" + a)));
    }

    @Test
    void refusesRootsOverlappingServedOrWorkspaceDirectories() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectImportStaging(temp.resolve("output/private"), List.of(temp.resolve("output"))));
        assertThrows(IllegalArgumentException.class, () -> new ProjectImportStaging(temp, List.of(temp.resolve("output"))));
    }

    @Test
    void refusesMissingTasksNegativeIndexAndBadPath() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout));
        store.create(id);
        assertThrows(IllegalArgumentException.class, () -> store.receive(id, -1, new FileEntry("a", 1), InputStream.nullInputStream(), () -> true, timeout));
        assertThrows(IllegalArgumentException.class, () -> store.receive(id, 0, new FileEntry("../a", 1), new ByteArrayInputStream(new byte[]{1}), () -> true, timeout));
        assertThrows(IOException.class, () -> store.create(id));
    }

    @Test
    void nonProgressingInputHitsDeadlineAndDeletesPartialPayload() throws Exception {
        var store = staging();
        var id = UUID.randomUUID();
        store.create(id);
        InputStream stalled = new InputStream() {
            @Override public int read() { return 0; }
            @Override public int read(byte[] bytes, int offset, int length) { return 0; }
        };
        assertThrows(IOException.class, () -> store.receive(id, 0, new FileEntry("a", 1), stalled, () -> true, Duration.ofMillis(10)));
        assertFalse(Files.exists(temp.resolve("private/" + id + "/0.bin")));
    }

    @Test
    void windowsJunctionRootCannotBecomeManagedStaging() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path link = temp.resolve("junction");
        createJunction(link, outside);
        try {
            assertThrows(IOException.class, () -> new ProjectImportStaging(link, List.of(temp.resolve("output"))));
        } finally { Files.deleteIfExists(link); }
    }

    @Test
    void childJunctionCleanupNeverDeletesItsOutsideTarget() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        var store = staging();
        UUID id = UUID.randomUUID();
        store.create(id);
        Path outside = Files.createDirectory(temp.resolve("outside"));
        Path original = Files.writeString(outside.resolve("keep.txt"), "keep");
        Path link = temp.resolve("private/" + id + "/junction");
        createJunction(link, outside);
        try {
            try { store.cleanup(id); } catch (IOException expected) { /* 保持清理失败也不能越出任务范围。 */ }
            assertEquals("keep", Files.readString(original));
        } finally { Files.deleteIfExists(link); }
    }

    private void createJunction(Path link, Path target) throws Exception {
        assertTrue(link.toAbsolutePath().normalize().startsWith(temp.toAbsolutePath().normalize()));
        assertTrue(target.toAbsolutePath().normalize().startsWith(temp.toAbsolutePath().normalize()));
        Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes()));
    }
}
