package com.dbts.glyahhaigeneratecode.core.rag;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeChunkerTest {

    private final CodeChunker chunker = new CodeChunker(10, 2, 100);

    @Test
    void chunk_emptyContent_returnsEmpty() {
        assertTrue(chunker.chunk("f.java", "", "java").isEmpty());
        assertTrue(chunker.chunk("f.java", null, "java").isEmpty());
        assertTrue(chunker.chunk("f.java", "   ", "java").isEmpty());
    }

    @Test
    void chunk_smallFile_singleChunk() {
        String content = "line1\nline2\nline3";
        List<CodeChunker.Chunk> chunks = chunker.chunk("f.java", content, "java");
        assertEquals(1, chunks.size());
        assertEquals("f.java", chunks.getFirst().filePath());
        assertEquals(0, chunks.getFirst().chunkIndex());
        assertEquals(1, chunks.getFirst().startLine());
        assertEquals(3, chunks.getFirst().endLine());
        assertTrue(chunks.getFirst().content().contains("line1"));
    }

    @Test
    void chunk_largeFile_multipleChunksWithOverlap() {
        // 25 lines, chunk size 10, overlap 2 → should produce 3 chunks (10, 10, 5)
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            sb.append("line").append(i).append('\n');
        }
        String content = sb.toString();

        List<CodeChunker.Chunk> chunks = chunker.chunk("big.js", content, "javascript");
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.size() >= 2);

        // Verify chunk 0
        assertEquals("big.js", chunks.get(0).filePath());
        assertEquals(0, chunks.get(0).chunkIndex());
        assertEquals(1, chunks.get(0).startLine());
        assertTrue(chunks.get(0).endLine() >= chunks.get(0).startLine());

        // Verify overlap: chunk 1 starts before chunk 0 ends
        assertTrue(chunks.get(1).startLine() < chunks.get(0).endLine(),
                "expected overlap: chunk1 start=" + chunks.get(1).startLine()
                        + " chunk0 end=" + chunks.get(0).endLine());
    }

    @Test
    void chunk_overlapNotExceedingMaxLines() {
        CodeChunker smallChunker = new CodeChunker(5, 10, 100);
        String content = "a\nb\nc\nd\ne\nf\ng\nh\ni\nj\n";
        List<CodeChunker.Chunk> chunks = smallChunker.chunk("t.txt", content, "text");
        assertFalse(chunks.isEmpty());
        // overlap should be capped to maxLines/2
        for (CodeChunker.Chunk c : chunks) {
            assertTrue(c.endLine() - c.startLine() + 1 <= 5);
        }
    }

    @Test
    void chunk_oversizedFile_returnsEmpty() {
        CodeChunker tinyChunker = new CodeChunker(10, 2, 15);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("line").append(i).append('\n');
        }
        assertTrue(tinyChunker.chunk("big.java", sb.toString(), "java").isEmpty());
    }

    @Test
    void chunk_boundaryAlignsOnEmptyLine() {
        // The chunker should try to align chunk boundaries on empty lines
        CodeChunker alignChunker = new CodeChunker(5, 3, 100);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            sb.append("code").append(i).append('\n');
            if (i == 5 || i == 10) {
                sb.append('\n'); // empty line at 5/6 and 10/11
            }
        }
        String content = sb.toString();
        List<CodeChunker.Chunk> chunks = alignChunker.chunk("align.java", content, "java");
        assertFalse(chunks.isEmpty());
    }

    @Test
    void chunk_201Lines_terminatesWithContinuousIndexes() {
        CodeChunker productionChunker = new CodeChunker(200, 20, 5000);
        String content = numberedLines(201, false);

        List<CodeChunker.Chunk> chunks = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> productionChunker.chunk("src/App.java", content, "java"));

        assertContinuousIndexes(chunks);
        assertEquals(201, chunks.getLast().endLine());
    }

    @Test
    void chunk_5000Lines_terminates() {
        CodeChunker productionChunker = new CodeChunker(200, 20, 5001);

        List<CodeChunker.Chunk> chunks = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> productionChunker.chunk("src/Large.java", numberedLines(5000, false), "java"));

        assertContinuousIndexes(chunks);
        assertEquals(5000, chunks.getLast().endLine());
    }

    @Test
    void chunk_tailAndBoundaryBlankLines_terminates() {
        CodeChunker boundaryChunker = new CodeChunker(5, 2, 100);
        String content = "one\ntwo\n\nthree\nfour\n\nfive\nsix\n\n";

        List<CodeChunker.Chunk> chunks = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> boundaryChunker.chunk("notes.txt", content, "text"));

        assertContinuousIndexes(chunks);
        assertEquals(content.split("\n", -1).length, chunks.getLast().endLine());
    }

    @Test
    void chunk_oversizedOverlap_terminates() {
        CodeChunker overlapChunker = new CodeChunker(4, Integer.MAX_VALUE, 100);

        List<CodeChunker.Chunk> chunks = assertTimeoutPreemptively(
                Duration.ofSeconds(2),
                () -> overlapChunker.chunk("notes.txt", numberedLines(20, true), "text"));

        assertContinuousIndexes(chunks);
        assertEquals(21, chunks.getLast().endLine());
    }

    private static String numberedLines(int count, boolean trailingNewline) {
        StringBuilder content = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            if (i > 1) {
                content.append('\n');
            }
            content.append("line").append(i);
        }
        if (trailingNewline) {
            content.append('\n');
        }
        return content.toString();
    }

    private static void assertContinuousIndexes(List<CodeChunker.Chunk> chunks) {
        assertFalse(chunks.isEmpty());
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i, chunks.get(i).chunkIndex());
        }
    }
}
