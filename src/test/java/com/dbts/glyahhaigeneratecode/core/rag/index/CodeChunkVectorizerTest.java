package com.dbts.glyahhaigeneratecode.core.rag.index;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.model.EmbeddedCodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeChunkVectorizerTest {

    private final EmbeddingService embeddingService = mock(EmbeddingService.class);
    private final CodeChunkVectorizer vectorizer = new CodeChunkVectorizer(embeddingService);

    @Test
    void vectorize_generatesAllEmbeddingsInOneBatchAndKeepsOrder() {
        List<CodeChunkDocument> documents = documents();
        when(embeddingService.embedBatch(List.of("class App {}", "void run() {}")))
                .thenReturn(new float[][]{{0.1f, 0.2f}, {0.3f, 0.4f}});

        List<EmbeddedCodeChunk> result = vectorizer.vectorize(documents);

        assertEquals(2, result.size());
        assertEquals(documents.get(0), result.get(0).document());
        assertEquals(0.3f, result.get(1).embedding()[0]);
        verify(embeddingService).embedBatch(List.of("class App {}", "void run() {}"));
    }

    @Test
    void vectorize_whenResultCountDiffers_returnsEmpty() {
        List<CodeChunkDocument> documents = documents();
        when(embeddingService.embedBatch(List.of("class App {}", "void run() {}")))
                .thenReturn(new float[][]{{0.1f, 0.2f}});

        assertTrue(vectorizer.vectorize(documents).isEmpty());
    }

    @Test
    void vectorize_whenAnyEmbeddingIsNull_returnsEmpty() {
        List<CodeChunkDocument> documents = documents();
        when(embeddingService.embedBatch(List.of("class App {}", "void run() {}")))
                .thenReturn(new float[][]{{0.1f, 0.2f}, null});

        assertTrue(vectorizer.vectorize(documents).isEmpty());
    }

    @Test
    void vectorize_whenDocumentsAreEmpty_returnsEmptyWithoutCallingEmbedding() {
        assertTrue(vectorizer.vectorize(List.of()).isEmpty());
    }

    private static List<CodeChunkDocument> documents() {
        return List.of(
                new CodeChunkDocument(99L, "src/App.java", 0, "class App {}",
                        "hash", "java", 1, 1, "now"),
                new CodeChunkDocument(99L, "src/App.java", 1, "void run() {}",
                        "hash", "java", 2, 2, "now")
        );
    }
}
