package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunker;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.file.CodeChunkDocumentAssembler;
import com.dbts.glyahhaigeneratecode.core.rag.file.CodeLanguageDetector;
import com.dbts.glyahhaigeneratecode.core.rag.file.RagSourceFileReader;
import com.dbts.glyahhaigeneratecode.service.support.AppExistenceChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeChunkIndexServiceTest {

    private static final Long APP_ID = 99L;

    @Mock
    private ElasticsearchClient esClient;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private BulkResponse bulkResponse;
    @Mock
    private DeleteByQueryResponse deleteByQueryResponse;
    @Mock
    private SearchResponse<ObjectNode> searchResponse;
    @Mock
    private HitsMetadata<ObjectNode> hitsMetadata;
    @Mock
    private Hit<ObjectNode> existingChunkHit;
    @Mock
    private Hit<ObjectNode> extraChunkHit;

    @TempDir
    Path tempDir;

    private CodeChunkIndexService indexService;
    private RagProperties properties;

    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setEnabled(true);
        CodeChunker chunker = new CodeChunker(200, 20, 5000);
        indexService = new CodeChunkIndexService(
                esClient, embeddingService, chunker, properties, new ObjectMapper());
    }


    @Test
    void indexFile_bulkFailure_cleansPossiblePartialDocuments(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");

        when(embeddingService.embedBatch(any())).thenReturn(new float[][]{{0.1f, 0.2f}});
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(true);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void indexFile_bulkSuccess_deletesStaleChunks(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");

        when(embeddingService.embedBatch(any())).thenReturn(new float[][]{{0.1f, 0.2f, 0.3f}});
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient, atLeastOnce()).deleteByQuery(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(request -> request.toString().contains("chunkIndex")));
    }

    @Test
    void indexFile_completeSameHashIndex_skipsEmbeddingAndBulk(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        String content = "class App {}\n";
        Files.writeString(file, content);

        String hash = com.dbts.glyahhaigeneratecode.core.rag.RagFileHash.sha256Prefix(
                content.getBytes(StandardCharsets.UTF_8));
        ObjectNode source = new ObjectMapper().createObjectNode().put("contentHash", hash);
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(existingChunkHit));
        when(existingChunkHit.id()).thenReturn(APP_ID + ":src/App.java:0");
        when(existingChunkHit.source()).thenReturn(source);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(embeddingService, never()).embedBatch(any());
        verify(esClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void indexFile_hashChanged_reindexesExistingChunk(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");

        ObjectNode source = new ObjectMapper().createObjectNode().put("contentHash", "old-hash");
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(existingChunkHit));
        when(existingChunkHit.id()).thenReturn(APP_ID + ":src/App.java:0");
        when(existingChunkHit.source()).thenReturn(source);
        when(embeddingService.embedBatch(any())).thenReturn(new float[][]{{0.1f, 0.2f}});
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(embeddingService).embedBatch(any());
        verify(esClient).bulk(any(BulkRequest.class));
    }

    @Test
    void indexFile_extraStaleChunk_reindexesAndCleansStaleChunks(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        String content = "class App {}\n";
        Files.writeString(file, content);

        String hash = com.dbts.glyahhaigeneratecode.core.rag.RagFileHash.sha256Prefix(
                content.getBytes(StandardCharsets.UTF_8));
        ObjectNode source = new ObjectMapper().createObjectNode().put("contentHash", hash);
        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(existingChunkHit, extraChunkHit));
        when(embeddingService.embedBatch(any())).thenReturn(new float[][]{{0.1f, 0.2f}});
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(embeddingService).embedBatch(any());
        verify(esClient).bulk(any(BulkRequest.class));
        verify(esClient, atLeastOnce()).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void indexFile_partialExistingIndex_doesNotSkipReindex(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");

        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class))).thenReturn(searchResponse);
        when(searchResponse.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of());
        when(embeddingService.embedBatch(any())).thenReturn(new float[][]{{0.1f, 0.2f}});
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(embeddingService).embedBatch(any());
        verify(esClient).bulk(any(BulkRequest.class));
    }

    @Test
    void indexFile_indexStateCheckFails_continuesReindex(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");

        when(esClient.search(any(SearchRequest.class), eq(ObjectNode.class)))
                .thenThrow(new RuntimeException("es unavailable"));
        when(embeddingService.embedBatch(any())).thenReturn(new float[][]{{0.1f, 0.2f}});
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(embeddingService).embedBatch(any());
        verify(esClient).bulk(any(BulkRequest.class));
    }

    @Test
    void sha256Prefix_consistent() {
        byte[] data = "hello".getBytes();
        String hash1 = com.dbts.glyahhaigeneratecode.core.rag.RagFileHash.sha256Prefix(data);
        String hash2 = com.dbts.glyahhaigeneratecode.core.rag.RagFileHash.sha256Prefix(data);
        assertEquals(hash1, hash2);
        assertEquals(20, hash1.length());
    }

    @Test
    void indexFile_blankFile_deletesExistingChunks(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "   \n");
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
        verify(esClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void deleteAppChunks_deletesAllDocumentsForApp() throws Exception {
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        indexService.deleteAppChunks(APP_ID);

        ArgumentCaptor<DeleteByQueryRequest> captor = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(esClient).deleteByQuery(captor.capture());
        String query = captor.getValue().toString();
        assertTrue(query.contains("appId"));
        assertFalse(query.contains("filePath"));
    }

    @Test
    void deleteAppChunks_whenRagDisabled_stillDeletesHistoricalDocuments() throws Exception {
        properties.setEnabled(false);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        indexService.deleteAppChunks(APP_ID);

        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void deleteAppChunks_waitsForInFlightIndexAndPreventsLaterWrites(@TempDir Path root) throws Exception {
        AtomicBoolean appExists = new AtomicBoolean(true);
        indexService = newIndexService(ignored -> appExists.get());
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");
        CountDownLatch embeddingStarted = new CountDownLatch(1);
        CountDownLatch releaseEmbedding = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        when(embeddingService.embedBatch(any())).thenAnswer(invocation -> {
            embeddingStarted.countDown();
            assertTrue(releaseEmbedding.await(2, TimeUnit.SECONDS));
            return new float[][]{{0.1f, 0.2f}};
        });
        when(esClient.bulk(any(BulkRequest.class))).thenReturn(bulkResponse);
        when(bulkResponse.errors()).thenReturn(false);
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<Void> indexFuture = CompletableFuture.runAsync(
                    () -> indexService.indexFile(APP_ID, "src/App.java", root, null), executor);
            assertTrue(embeddingStarted.await(2, TimeUnit.SECONDS));
            appExists.set(false);
            CompletableFuture<Void> deleteFuture = CompletableFuture.runAsync(() -> {
                deleteStarted.countDown();
                indexService.deleteAppChunks(APP_ID);
            }, executor);
            assertTrue(deleteStarted.await(2, TimeUnit.SECONDS));
            assertFalse(deleteFuture.isDone());

            releaseEmbedding.countDown();
            indexFuture.get(3, TimeUnit.SECONDS);
            deleteFuture.get(3, TimeUnit.SECONDS);
        }

        indexService.indexFile(APP_ID, "src/App.java", root, null);
        verify(esClient, times(1)).bulk(any(BulkRequest.class));
    }

    @Test
    void indexFile_checksAppExistenceAgainInsideAppReadLock(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");
        AtomicInteger checks = new AtomicInteger();
        indexService = newIndexService(appId -> checks.incrementAndGet() == 1);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        assertEquals(2, checks.get());
        verify(embeddingService, never()).embedBatch(any());
        verify(esClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void indexFile_appMissingBeforeLock_skipsIndexing(@TempDir Path root) throws Exception {
        Path file = root.resolve("src/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class App {}\n");
        indexService = newIndexService(appId -> false);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(embeddingService, never()).embedBatch(any());
        verify(esClient, never()).bulk(any(BulkRequest.class));
    }

    @Test
    void indexFile_symlinkOutsideProject_doesNotIndex(@TempDir Path root) throws Exception {
        Path outsideFile = root.getParent().resolve("outside-App.java");
        Files.writeString(outsideFile, "class Outside {}\n");
        Path link = root.resolve("src/App.java");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "symbolic links unavailable: " + e.getMessage());
        }
        when(esClient.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(deleteByQueryResponse);

        indexService.indexFile(APP_ID, "src/App.java", root, null);

        verify(esClient, never()).bulk(any(BulkRequest.class));
        verify(esClient).deleteByQuery(any(DeleteByQueryRequest.class));
    }

    @Test
    void detectLang_byExtension() {
        assertEquals("java", CodeChunkIndexService.detectLang("src/Test.java"));
        assertEquals("vue", CodeChunkIndexService.detectLang("App.vue"));
        assertEquals("typescript", CodeChunkIndexService.detectLang("index.ts"));
        assertEquals("html", CodeChunkIndexService.detectLang("index.html"));
        assertEquals("text", CodeChunkIndexService.detectLang("file.unknown"));
        assertEquals("text", CodeChunkIndexService.detectLang(null));
    }

    private CodeChunkIndexService newIndexService(AppExistenceChecker checker) {
        CodeChunker chunker = new CodeChunker(200, 20, 5000);
        return new CodeChunkIndexService(
                esClient,
                embeddingService,
                properties,
                new ObjectMapper(),
                new RagSourceFileReader(),
                new CodeChunkDocumentAssembler(chunker, new CodeLanguageDetector()),
                checker);
    }
}
