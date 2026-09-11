package com.dbts.glyahhaigeneratecode.core.rag.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.EmbeddedCodeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ElasticsearchCodeChunkIndexRepositoryTest {

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private BulkResponse bulkResponse;
    @Mock
    private DeleteByQueryResponse deleteResponse;
    @Mock
    private SearchResponse<ObjectNode> searchResponse;
    @Mock
    private HitsMetadata<ObjectNode> hitsMetadata;
    @Mock
    private Hit<ObjectNode> hit;

    private ElasticsearchCodeChunkIndexRepository repository;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        repository = new ElasticsearchCodeChunkIndexRepository(esClient, properties, new ObjectMapper());
    }

    @Test
    void writeAllOrCleanup_writesEveryOperationWithDeterministicIdSourceEmbeddingAndWaitForRefresh() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);

        EmbeddedCodeChunk first = embeddedChunk();
        CodeChunkDocument secondDocument = new CodeChunkDocument(
                99L, "src/App.java", 1, "void run() {}", "hash", "java", 2, 2, "now");
        EmbeddedCodeChunk second = new EmbeddedCodeChunk(secondDocument, new float[]{0.3f, 0.4f});

        assertTrue(repository.writeAllOrCleanup(List.of(first, second)));

        ArgumentCaptor<BulkRequest> captor = ArgumentCaptor.forClass(BulkRequest.class);
        verify(esClient).bulk(captor.capture());
        BulkRequest request = captor.getValue();
        assertTrue(request.toString().contains("wait_for"));
        assertTrue(request.operations().size() == 2);
        assertTrue(request.operations().get(0).index().id().equals("99:src/App.java:0"));
        assertTrue(request.operations().get(1).index().id().equals("99:src/App.java:1"));
        assertTrue(request.operations().stream()
                .allMatch(operation -> operation.index().index().equals("code_chunks_v4")));
        ObjectNode firstSource = (ObjectNode) request.operations().get(0).index().document();
        ObjectNode secondSource = (ObjectNode) request.operations().get(1).index().document();
        assertTrue(firstSource.get("appId").asLong() == 99L);
        assertTrue(firstSource.get("filePath").asText().equals("src/App.java"));
        assertTrue(firstSource.get("chunkIndex").asInt() == 0);
        assertTrue(firstSource.get("content").asText().equals("class App {}"));
        assertTrue(firstSource.get("embedding").get(0).floatValue() == 0.1f);
        assertTrue(secondSource.get("embedding").get(1).floatValue() == 0.4f);
    }

    @Test
    void writeAllOrCleanup_whenBulkHasPartialErrors_cleansSubmittedFile() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(true);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteResponse);

        assertFalse(repository.writeAllOrCleanup(List.of(embeddedChunk())));

        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void writeAllOrCleanup_whenBulkThrows_cleansSubmittedFile() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenThrow(new RuntimeException("bulk failed"));
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteResponse);

        assertFalse(repository.writeAllOrCleanup(List.of(embeddedChunk())));

        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void writeAllOrCleanup_whenInputIsEmpty_doesNotSubmitOrClean() throws Exception {
        assertFalse(repository.writeAllOrCleanup(List.of()));

        verify(esClient, never()).bulk(any(BulkRequest.class));
        verify(esClient, never()).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void writeAllOrCleanup_whenChunksBelongToDifferentFiles_doesNotSubmitOrClean() throws Exception {
        CodeChunkDocument anotherFile = new CodeChunkDocument(
                99L, "src/Other.java", 1, "class Other {}", "hash", "java", 1, 1, "now");

        assertFalse(repository.writeAllOrCleanup(List.of(
                embeddedChunk(), new EmbeddedCodeChunk(anotherFile, new float[]{0.3f, 0.4f}))));

        verify(esClient, never()).bulk(any(BulkRequest.class));
        verify(esClient, never()).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void writeAllOrCleanup_whenChunksBelongToDifferentApps_doesNotSubmitOrClean() throws Exception {
        CodeChunkDocument anotherApp = new CodeChunkDocument(
                100L, "src/App.java", 1, "class App {}", "hash", "java", 1, 1, "now");

        assertFalse(repository.writeAllOrCleanup(List.of(
                embeddedChunk(), new EmbeddedCodeChunk(anotherApp, new float[]{0.3f, 0.4f}))));

        verify(esClient, never()).bulk(any(BulkRequest.class));
        verify(esClient, never()).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void writeAllOrCleanup_whenCompensationDeleteFails_stillReturnsFalse() throws Exception {
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(true);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(null);

        assertFalse(repository.writeAllOrCleanup(List.of(embeddedChunk())));

        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void isFileIndexCurrent_matchesIdsAndHashes() throws Exception {
        ObjectNode source = new ObjectMapper().createObjectNode().put("contentHash", "hash");
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        when(hit.id()).thenReturn("99:src/App.java:0");
        when(hit.source()).thenReturn(source);

        assertTrue(repository.isFileIndexCurrent(List.of(document())));
    }

    @Test
    void isFileIndexCurrent_whenResponseIsIncomplete_returnsFalse() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(null);

        assertFalse(repository.isFileIndexCurrent(List.of(document())));
    }

    @Test
    void deleteStaleChunks_whenDeleteTimedOut_returnsFalse() throws Exception {
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteResponse);
        when(deleteResponse.timedOut()).thenReturn(true);

        assertFalse(repository.deleteStaleChunks(99L, "src/App.java", 1));
    }

    @Test
    void deleteStaleChunks_filtersExactFileAndChunkIndexesAtOrAboveCount() throws Exception {
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteResponse);

        assertTrue(repository.deleteStaleChunks(99L, "src/App.java", 3));

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient).deleteByQuery(captor.capture());
        List<Query> must = captor.getValue().query().bool().must();
        assertTrue(must.stream().anyMatch(query -> query.isTerm()
                && query.term().field().equals("appId")
                && query.term().value().longValue() == 99L));
        assertTrue(must.stream().anyMatch(query -> query.isTerm()
                && query.term().field().equals("filePath")
                && query.term().value().stringValue().equals("src/App.java")));
        assertTrue(must.stream().anyMatch(query -> query.isRange()
                && query.range().number().field().equals("chunkIndex")
                && query.range().number().gte() == 3.0));
    }

    @Test
    void deleteAppChunks_usesOnlyAppFilter() throws Exception {
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteResponse);

        repository.deleteAppChunks(99L);

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient).deleteByQuery(captor.capture());
        assertTrue(captor.getValue().toString().contains("appId"));
        assertFalse(captor.getValue().toString().contains("filePath"));
    }

    @Test
    void deleteFileChunks_nullResponse_returnsFalse() throws Exception {
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(null);

        assertFalse(repository.deleteFileChunks(99L, "src/App.java"));
        verify(esClient, times(1)).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    private static EmbeddedCodeChunk embeddedChunk() {
        return new EmbeddedCodeChunk(document(), new float[]{0.1f, 0.2f});
    }

    private static CodeChunkDocument document() {
        return new CodeChunkDocument(
                99L, "src/App.java", 0, "class App {}", "hash", "java", 1, 1, "now");
    }
}
