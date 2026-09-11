package com.dbts.glyahhaigeneratecode.core.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RagPropertiesTest {

    @Test
    void defaults_useVersionedV4EmbeddingIndex() {
        RagProperties properties = new RagProperties();

        assertEquals("http://localhost:9201", properties.getElasticsearch().getUris());
        assertEquals("code_chunks_v4", properties.getElasticsearch().getIndexName());
        assertEquals("text-embedding-v4", properties.getEmbedding().getModelName());
        assertEquals(1024, properties.getEmbedding().getDimension());
        assertEquals(10, properties.getEmbedding().getBatchSize());
        assertEquals(1, properties.getEmbedding().getBatchMaxRetries());
        assertEquals(300, properties.getEmbedding().getRetryBaseDelayMs());
        assertEquals(1.0f, properties.getRetrieval().getKeywordBoost());
        assertEquals(1.0f, properties.getRetrieval().getVectorBoost());
        assertEquals(2, properties.getRetrieval().getNumCandidatesMultiplier());
    }
}
