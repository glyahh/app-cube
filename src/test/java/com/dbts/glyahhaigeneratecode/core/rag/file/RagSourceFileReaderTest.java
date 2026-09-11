package com.dbts.glyahhaigeneratecode.core.rag.file;

import com.dbts.glyahhaigeneratecode.core.rag.RagFileHash;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagSourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagSourceFileReaderTest {

    private final RagSourceFileReader reader = new RagSourceFileReader();

    @TempDir
    Path tempDir;

    @Test
    void read_returnsUtf8ContentAndCurrentHash() throws Exception {
        Path source = tempDir.resolve("src/App.java");
        Files.createDirectories(source.getParent());
        byte[] bytes = "class App { // 中文\n}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(source, bytes);

        Optional<RagSourceFile> result = reader.read(tempDir, "src/App.java");

        assertTrue(result.isPresent());
        assertEquals(source.toRealPath(), result.orElseThrow().path());
        assertEquals("src/App.java", result.orElseThrow().filePath());
        assertEquals(new String(bytes, StandardCharsets.UTF_8), result.orElseThrow().content());
        assertEquals(RagFileHash.sha256Prefix(bytes), result.orElseThrow().contentHash());
    }

    @Test
    void read_rejectsLexicalPathTraversalAndMissingFiles() throws Exception {
        Path outside = tempDir.resolve("../outside.java").normalize();
        Files.writeString(outside, "class Outside {}", StandardCharsets.UTF_8);
        try {
            assertTrue(reader.read(tempDir, "../outside.java").isEmpty());
            assertTrue(reader.read(tempDir, "missing.java").isEmpty());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void read_rejectsDirectories() {
        assertTrue(reader.read(tempDir, ".").isEmpty());
    }
}
