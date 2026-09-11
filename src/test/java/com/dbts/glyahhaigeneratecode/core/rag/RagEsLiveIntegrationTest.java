package com.dbts.glyahhaigeneratecode.core.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.dbts.glyahhaigeneratecode.service.impl.CodeChunkIndexService;
import com.dbts.glyahhaigeneratecode.service.impl.CodeChunkRetrievalService;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 ES + 百炼 text-embedding-v4 联调（需本机 MySQL、Redis、ES:9201 在线）。
 */
@SpringBootTest
@ActiveProfiles("local-b")
@EnabledIfEnvironmentVariable(named = "RAG_ES_LIVE_TESTS", matches = "true")
class RagEsLiveIntegrationTest {

    private static final long APP_ID = 431380715279372288L;
    private static final String FILE_PATH = "index.html";

    @Autowired
    private CodeChunkIndexService codeChunkIndexService;
    @Autowired
    private CodeChunkRetrievalService codeChunkRetrievalService;
    @Autowired
    private ElasticsearchClient esClient;
    @Autowired
    private RagProperties ragProperties;

    @Test
    void indexAndSearchRealHtmlChunk() throws Exception {
        Assumptions.assumeTrue(ragProperties.isEnabled(), "rag.enabled=false");

        Path root = Paths.get("temp/code_output/html_" + APP_ID);
        Assumptions.assumeTrue(Files.isRegularFile(root.resolve(FILE_PATH)),
                "missing generated html at " + root);

        codeChunkIndexService.indexFile(APP_ID, FILE_PATH, root, null);

        long count = esClient.count(CountRequest.of(c -> c.index(ragProperties.getElasticsearch().getIndexName()))).count();
        assertTrue(count > 0, "ES should have chunks after indexFile");

        List<CodeChunkRetrievalService.CodeChunkHit> hits =
                codeChunkRetrievalService.searchTopK(
                        APP_ID, "counter reset button dark blue theme", CodeGenTypeEnum.HTML);
        assertFalse(hits.isEmpty(), "vector search should return hits");

        SearchResponse<ObjectNode> verify = esClient.search(SearchRequest.of(s -> s
                .index(ragProperties.getElasticsearch().getIndexName())
                .size(1)
                .query(q -> q.term(t -> t.field("appId").value(APP_ID)))
                .source(sc -> sc.filter(sf -> sf.excludes("embedding")))), ObjectNode.class);

        List<Hit<ObjectNode>> docs = verify.hits().hits();
        assertFalse(docs.isEmpty());
        assertTrue(docs.getFirst().source().has("content"));
    }
}
