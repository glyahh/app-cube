package com.dbts.glyahhaigeneratecode.core.rag.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElasticsearchCodeChunkSearchRepositoryTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private SearchResponse<ObjectNode> response;
    @Mock
    private HitsMetadata<ObjectNode> hitsMetadata;
    @Mock
    private Hit<ObjectNode> hit;

    private ElasticsearchCodeChunkSearchRepository repository;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        properties.getElasticsearch().setIndexName("code_chunks_v4");
        repository = new ElasticsearchCodeChunkSearchRepository(esClient, properties);
    }

    @Test
    void search_buildsHybridRequestFromCriteria() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response);
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of());

        repository.search(new CodeChunkSearchCriteria(
                42L, "find component", 4, 1.5f, 2.0f, 12, List.of(0.1f, 0.2f)));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(captor.capture(), eq(ObjectNode.class));
        SearchRequest request = captor.getValue();
        assertEquals(List.of("code_chunks_v4"), request.index());
        assertEquals(4, request.size());
        assertEquals("content", request.query().bool().must().getFirst().match().field());
        assertEquals("find component", request.query().bool().must().getFirst().match().query().stringValue());
        assertEquals(1.5f, request.query().bool().must().getFirst().match().boost());
        assertEquals("appId", request.query().bool().filter().getFirst().term().field());
        assertEquals(42L, request.query().bool().filter().getFirst().term().value().longValue());
        assertEquals("embedding", request.knn().getFirst().field());
        assertEquals(List.of(0.1f, 0.2f), request.knn().getFirst().queryVector());
        assertEquals(4, request.knn().getFirst().k());
        assertEquals(12, request.knn().getFirst().numCandidates());
        assertEquals(2.0f, request.knn().getFirst().boost());
        assertEquals("appId", request.knn().getFirst().filter().getFirst().term().field());
        assertEquals(42L, request.knn().getFirst().filter().getFirst().term().value().longValue());
        assertTrue(request.source().filter().excludes().contains("embedding"));
    }

    @Test
    void search_withoutVectorBuildsBm25OnlyAndMapsDefaults() throws Exception {
        ObjectNode source = new ObjectMapper().createObjectNode().put("filePath", "src/App.vue");
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response);
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        when(hit.source()).thenReturn(source);
        when(hit.score()).thenReturn(null);

        List<RagCodeChunkCandidate> results = repository.search(new CodeChunkSearchCriteria(
                42L, "find component", 4, 1.5f, 2.0f, 12, null));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(captor.capture(), eq(ObjectNode.class));
        assertTrue(captor.getValue().knn().isEmpty());
        assertEquals(new RagCodeChunkCandidate("src/App.vue", 0, "", "", "", 1, 1, 0.0f), results.getFirst());
    }

    @Test
    void search_emptyVectorStillBuildsKnnRequest() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(response);
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of());

        repository.search(new CodeChunkSearchCriteria(
                42L, "find component", 4, 1.5f, 2.0f, 12, List.of()));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(captor.capture(), eq(ObjectNode.class));
        assertEquals(1, captor.getValue().knn().size());
        assertTrue(captor.getValue().knn().getFirst().queryVector().isEmpty());
    }
}
