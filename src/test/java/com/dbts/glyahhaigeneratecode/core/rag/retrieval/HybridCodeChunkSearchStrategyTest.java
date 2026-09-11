package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.repository.CodeChunkSearchCriteria;
import com.dbts.glyahhaigeneratecode.core.rag.repository.CodeChunkSearchRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridCodeChunkSearchStrategyTest {

    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private CodeChunkSearchRepository repository;

    private HybridCodeChunkSearchStrategy strategy;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setTopK(4);
        properties.getRetrieval().setKeywordBoost(1.5f);
        properties.getRetrieval().setVectorBoost(2.0f);
        properties.getRetrieval().setNumCandidatesMultiplier(3);
        strategy = new HybridCodeChunkSearchStrategy(embeddingService, repository, properties);
    }

    @Test
    void search_embedsQueryAndComputesRetrievalCriteria() throws Exception {
        when(embeddingService.embed("find component")).thenReturn(new float[]{0.1f, 0.2f});
        when(repository.search(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        strategy.search(42L, "find component");

        ArgumentCaptor<CodeChunkSearchCriteria> captor = ArgumentCaptor.forClass(CodeChunkSearchCriteria.class);
        verify(repository).search(captor.capture());
        assertEquals(42L, captor.getValue().appId());
        assertEquals(4, captor.getValue().topK());
        assertEquals(12, captor.getValue().numCandidates());
        assertEquals(List.of(0.1f, 0.2f), captor.getValue().queryVector());
    }

    @Test
    void search_nullEmbeddingUsesBm25Only() throws Exception {
        when(embeddingService.embed("find component")).thenReturn(null);
        when(repository.search(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        strategy.search(42L, "find component");

        ArgumentCaptor<CodeChunkSearchCriteria> captor = ArgumentCaptor.forClass(CodeChunkSearchCriteria.class);
        verify(repository).search(captor.capture());
        assertTrue(!captor.getValue().hasQueryVector());
    }

    @Test
    void search_emptyEmbeddingStillProvidesKnnVector() throws Exception {
        when(embeddingService.embed("find component")).thenReturn(new float[0]);
        when(repository.search(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        strategy.search(42L, "find component");

        ArgumentCaptor<CodeChunkSearchCriteria> captor = ArgumentCaptor.forClass(CodeChunkSearchCriteria.class);
        verify(repository).search(captor.capture());
        assertTrue(captor.getValue().hasQueryVector());
        assertTrue(captor.getValue().queryVector().isEmpty());
    }
}
