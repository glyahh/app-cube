package com.dbts.glyahhaigeneratecode.core.rag.file;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunker;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagSourceFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CodeChunkDocumentAssemblerTest {

    @Test
    void assemble_preservesChunkFieldsHashAndTimestamp() {
        Instant now = Instant.parse("2026-07-23T08:30:00Z");
        CodeChunkDocumentAssembler assembler = new CodeChunkDocumentAssembler(
                new CodeChunker(2, 0, 100),
                new CodeLanguageDetector(),
                Clock.fixed(now, ZoneOffset.UTC));
        RagSourceFile source = new RagSourceFile(
                Path.of("src/App.java"), "src/App.java", "line1\nline2\nline3", "hash-v1");

        List<CodeChunkDocument> documents = assembler.assemble(7L, source);

        assertEquals(2, documents.size());
        assertEquals(List.of(0, 1), documents.stream().map(CodeChunkDocument::chunkIndex).toList());
        assertEquals("7:src/App.java:0", documents.getFirst().docId());
        assertEquals("hash-v1", documents.getFirst().contentHash());
        assertEquals("java", documents.getFirst().language());
        assertEquals(1, documents.getFirst().startLine());
        assertEquals(2, documents.getFirst().endLine());
        assertEquals(now.toString(), documents.getFirst().updatedAt());
    }

    @Test
    void assemble_returnsEmptyWhenChunkerRejectsContent() {
        CodeChunkDocumentAssembler assembler = new CodeChunkDocumentAssembler(
                new CodeChunker(2, 0, 1), new CodeLanguageDetector());
        RagSourceFile source = new RagSourceFile(
                Path.of("large.txt"), "large.txt", "line1\nline2", "hash-v1");

        assertEquals(List.of(), assembler.assemble(7L, source));
    }
}
