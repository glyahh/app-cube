package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryManifestSupport;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagFileHash;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeChunkRetrievalServiceTest {

    private static final Long APP_ID = 991_234_567L;

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private SearchResponse<ObjectNode> searchResponse;
    @Mock
    private HitsMetadata<ObjectNode> hitsMetadata;
    @Mock
    private Hit<ObjectNode> searchHit;

    private CodeChunkRetrievalService retrievalService;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.getRetrieval().setTopK(4);
        properties.getRetrieval().setKeywordBoost(1.5f);
        properties.getRetrieval().setVectorBoost(2.0f);
        properties.getRetrieval().setNumCandidatesMultiplier(3);
        retrievalService = new CodeChunkRetrievalService(esClient, embeddingService, properties);
    }

    @Test
    void searchTopK_usesVersionedV4EmbeddingIndex() throws Exception {
        when(embeddingService.embed("find app component")).thenReturn(new float[]{0.1f, 0.2f});
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of());

        retrievalService.searchTopK(APP_ID, "find app component", CodeGenTypeEnum.VUE);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(captor.capture(), eq(ObjectNode.class));
        assertEquals("code_chunks_v4", captor.getValue().index().getFirst());
    }

    @Test
    void searchTopK_buildsBm25AndKnnHybridQuery() throws Exception {
        when(embeddingService.embed("find app component")).thenReturn(new float[]{0.1f, 0.2f});
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of());

        retrievalService.searchTopK(APP_ID, "find app component", CodeGenTypeEnum.VUE);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(captor.capture(), eq(ObjectNode.class));
        SearchRequest request = captor.getValue();
        assertEquals(4, request.size());
        assertNotNull(request.query());
        assertEquals(1, request.knn().size());
        assertEquals(4, request.knn().getFirst().k());
        assertEquals(12, request.knn().getFirst().numCandidates());
        assertEquals(2.0f, request.knn().getFirst().boost());
        assertTrue(request.source().filter().excludes().contains("embedding"));
        String json = request.toString();
        assertTrue(json.contains("content"));
        assertTrue(json.contains("1.5"));
        assertEquals(2, countOccurrences(json, "appId"));
    }

    @Test
    void searchTopK_embeddingFailureFallsBackToBm25Only() throws Exception {
        when(embeddingService.embed("find app component")).thenReturn(null);
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of());

        retrievalService.searchTopK(APP_ID, "find app component", CodeGenTypeEnum.VUE);

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(esClient).search(captor.capture(), eq(ObjectNode.class));
        SearchRequest request = captor.getValue();
        assertEquals(4, request.size());
        assertNotNull(request.query());
        assertTrue(request.knn().isEmpty());
        assertEquals(1, countOccurrences(request.toString(), "appId"));
    }

    @Test
    void searchTopK_currentDiskHash_keepsHit() throws Exception {
        String content = "export default { name: 'App' };\n";
        Path root = ConversationMemoryManifestSupport.resolveProjectRoot(APP_ID, CodeGenTypeEnum.VUE);
        Path file = root.resolve("src/App.vue");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        ObjectNode source = hitSource("src/App.vue", RagFileHash.sha256Prefix(content.getBytes(StandardCharsets.UTF_8)));
        stubSearchHit(source);

        List<CodeChunkRetrievalService.CodeChunkHit> hits = retrievalService.searchTopK(
                APP_ID, "find app component", CodeGenTypeEnum.VUE);

        assertEquals(1, hits.size());
        assertEquals(source.get("contentHash").asText(), hits.getFirst().contentHash());
    }

    @Test
    void searchTopK_staleDiskHash_discardsHit() throws Exception {
        Path root = ConversationMemoryManifestSupport.resolveProjectRoot(APP_ID, CodeGenTypeEnum.VUE);
        Path file = root.resolve("src/App.vue");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "new content\n", StandardCharsets.UTF_8);
        stubSearchHit(hitSource("src/App.vue", RagFileHash.sha256Prefix("old content".getBytes(StandardCharsets.UTF_8))));

        List<CodeChunkRetrievalService.CodeChunkHit> hits = retrievalService.searchTopK(
                APP_ID, "find app component", CodeGenTypeEnum.VUE);

        assertTrue(hits.isEmpty());
    }

    @Test
    void searchTopK_pathOutsideProject_discardsHit() throws Exception {
        stubSearchHit(hitSource("../outside.txt", RagFileHash.sha256Prefix("content".getBytes(StandardCharsets.UTF_8))));

        List<CodeChunkRetrievalService.CodeChunkHit> hits = retrievalService.searchTopK(
                APP_ID, "find app component", CodeGenTypeEnum.VUE);

        assertTrue(hits.isEmpty());
    }

    @Test
    void searchTopK_symlinkOutsideProject_discardsHit() throws Exception {
        Path root = ConversationMemoryManifestSupport.resolveProjectRoot(APP_ID, CodeGenTypeEnum.VUE);
        Files.createDirectories(root);
        Path outsideFile = root.getParent().resolve("rag-outside-" + APP_ID + ".vue");
        String outsideContent = "outside content\n";
        Files.writeString(outsideFile, outsideContent, StandardCharsets.UTF_8);
        Path link = root.resolve("linked.vue");
        Files.deleteIfExists(link);
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }
        stubSearchHit(hitSource(
                "linked.vue", RagFileHash.sha256Prefix(outsideContent.getBytes(StandardCharsets.UTF_8))));

        List<CodeChunkRetrievalService.CodeChunkHit> hits = retrievalService.searchTopK(
                APP_ID, "find app component", CodeGenTypeEnum.VUE);

        assertTrue(hits.isEmpty());
    }

    private void stubSearchHit(ObjectNode source) throws Exception {
        when(embeddingService.embed("find app component")).thenReturn(new float[]{0.1f, 0.2f});
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(searchHit));
        when(searchHit.source()).thenReturn(source);
        when(searchHit.score()).thenReturn(0.9);
    }

    private static ObjectNode hitSource(String filePath, String contentHash) {
        return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("filePath", filePath)
                .put("chunkIndex", 0)
                .put("content", "chunk")
                .put("contentHash", contentHash)
                .put("language", "vue")
                .put("startLine", 1)
                .put("endLine", 2);
    }

    private static int countOccurrences(String text, String target) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(target, offset)) >= 0) {
            count++;
            offset += target.length();
        }
        return count;
    }
}
