package com.dbts.glyahhaigeneratecode.core.rag.model;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddedCodeChunkTest {

    @Test
    void constructor_copiesEmbeddingInput() {
        float[] input = {0.1f, 0.2f};
        EmbeddedCodeChunk chunk = new EmbeddedCodeChunk(document(), input);

        input[0] = 9.9f;

        assertEquals(0.1f, chunk.embedding()[0]);
    }

    @Test
    void embeddingAccessor_returnsDefensiveCopy() {
        EmbeddedCodeChunk chunk = new EmbeddedCodeChunk(document(), new float[]{0.1f, 0.2f});

        float[] returned = chunk.embedding();
        returned[0] = 9.9f;

        assertEquals(0.1f, chunk.embedding()[0]);
    }

    private static CodeChunkDocument document() {
        return new CodeChunkDocument(
                99L, "src/App.java", 0, "class App {}", "hash", "java", 1, 1, "now");
    }
}
