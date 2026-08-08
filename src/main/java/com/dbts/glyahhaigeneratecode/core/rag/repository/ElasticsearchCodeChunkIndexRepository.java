package com.dbts.glyahhaigeneratecode.core.rag.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.EmbeddedCodeChunk;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Elasticsearch 代码块索引写仓储，集中管理请求构造和响应完整性判断。
 */
@Slf4j
@Repository
public class ElasticsearchCodeChunkIndexRepository implements CodeChunkIndexRepository {

    private final ElasticsearchClient esClient;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    public ElasticsearchCodeChunkIndexRepository(ElasticsearchClient esClient,
                                                 RagProperties properties,
                                                 ObjectMapper objectMapper) {
        this.esClient = esClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean isFileIndexCurrent(List<CodeChunkDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return false;
        }

        CodeChunkDocument first = documents.getFirst();
        try {
            // 1. 只查询当前应用和文件的 contentHash，避免读取向量和源码。
            SearchRequest request = SearchRequest.of(s -> s
                    .index(properties.getElasticsearch().getIndexName())
                    .size(documents.size() + 1)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("appId").value(first.appId())))
                            .filter(f -> f.term(t -> t.field("filePath").value(first.filePath())))
                    ))
                    .source(sc -> sc.filter(sf -> sf.includes("contentHash")))
            );
            SearchResponse<ObjectNode> response = esClient.search(request, ObjectNode.class);
            if (response == null || response.hits() == null || response.hits().hits() == null) {
                return false;
            }

            // 2. 文档数量、确定性 ID 和文件 hash 必须全部一致。
            List<Hit<ObjectNode>> hits = response.hits().hits();
            if (hits.size() != documents.size()) {
                return false;
            }
            Map<String, String> expectedHashes = new HashMap<>(documents.size());
            for (CodeChunkDocument document : documents) {
                expectedHashes.put(document.docId(), document.contentHash());
            }
            Set<String> seenIds = new HashSet<>(hits.size());
            for (Hit<ObjectNode> hit : hits) {
                String expectedHash = expectedHashes.get(hit.id());
                ObjectNode source = hit.source();
                if (expectedHash == null || !seenIds.add(hit.id()) || source == null
                        || source.get("contentHash") == null
                        || !expectedHash.equals(source.get("contentHash").asText())) {
                    return false;
                }
            }
            return seenIds.size() == expectedHashes.size();
        } catch (Exception e) {
            log.warn("RAG 索引状态检查失败，将重新索引, appId={}, filePath={}, reason={}",
                    first.appId(), first.filePath(), e.getMessage());
            return false;
        }
    }

    @Override
    public boolean writeAllOrCleanup(List<EmbeddedCodeChunk> chunks) {
        if (!isSingleCompleteFile(chunks)) {
            return false;
        }

        CodeChunkDocument first = chunks.getFirst().document();
        boolean bulkSubmitted = false;
        try {
            // 1. 在调用 bulk 前完整组装请求，防止准备阶段失败时误删现有索引。
            List<BulkOperation> operations = new ArrayList<>(chunks.size());
            for (EmbeddedCodeChunk chunk : chunks) {
                CodeChunkDocument document = chunk.document();
                ObjectNode documentNode = objectMapper.valueToTree(document);
                ArrayNode embeddingArray = documentNode.putArray("embedding");
                for (float value : chunk.embedding()) {
                    embeddingArray.add(value);
                }
                IndexOperation<ObjectNode> indexOperation = IndexOperation.of(io -> io
                        .index(properties.getElasticsearch().getIndexName())
                        .id(document.docId())
                        .document(documentNode));
                operations.add(BulkOperation.of(b -> b.index(indexOperation)));
            }
            BulkRequest request = BulkRequest.of(b -> b
                    .operations(operations)
                    .refresh(Refresh.WaitFor));

            // 2. bulk 一旦提交，异常或部分失败都清理该文件可能写入的文档。
            bulkSubmitted = true;
            BulkResponse response = esClient.bulk(request);
            if (response == null || response.errors()) {
                log.warn("RAG bulk 部分失败，清理该文件可能写入的文档, appId={}, file={}",
                        first.appId(), first.filePath());
                deleteFileChunks(first.appId(), first.filePath());
                return false;
            }
            return true;
        } catch (Exception e) {
            if (bulkSubmitted) {
                deleteFileChunks(first.appId(), first.filePath());
            }
            log.warn("RAG bulk 写入失败，已降级, appId={}, file={}", first.appId(), first.filePath(), e);
            return false;
        }
    }

    private boolean isSingleCompleteFile(List<EmbeddedCodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty() || chunks.getFirst() == null
                || chunks.getFirst().document() == null || chunks.getFirst().embedding() == null) {
            return false;
        }

        CodeChunkDocument first = chunks.getFirst().document();
        for (EmbeddedCodeChunk chunk : chunks) {
            if (chunk == null || chunk.document() == null || chunk.embedding() == null
                    || !Objects.equals(first.appId(), chunk.document().appId())
                    || !Objects.equals(first.filePath(), chunk.document().filePath())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean deleteStaleChunks(Long appId, String filePath, int newChunkCount) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            // 1. 删除当前文件中超出新切块范围的旧文档。
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .refresh(true)
                    .query(q -> q.bool(bq -> bq
                            .must(m -> m.term(t -> t.field("appId").value(appId)))
                            .must(m -> m.term(t -> t.field("filePath").value(filePath)))
                            .must(m -> m.range(r -> r.number(
                                    nr -> nr.field("chunkIndex").gte((double) newChunkCount))))
                    )));
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (!isDeleteByQuerySuccessful(response)) {
                log.warn("RAG 清理多余旧 chunks 未完整成功, appId={}, filePath={}", appId, filePath);
                return false;
            }
            log.debug("RAG 已清理多余旧 chunks, appId={}, filePath={}, newChunkCount={}",
                    appId, filePath, newChunkCount);
            return true;
        } catch (Exception e) {
            log.warn("RAG 清理多余旧 chunks 失败, appId={}, filePath={}", appId, filePath, e);
            return false;
        }
    }

    @Override
    public boolean deleteFileChunks(Long appId, String filePath) {
        try {
            // 1. 按应用和文件路径清理全部代码块。
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .refresh(true)
                    .query(q -> q.bool(bq -> bq
                            .filter(f -> f.term(t -> t.field("appId").value(appId)))
                            .filter(f -> f.term(t -> t.field("filePath").value(filePath)))
                    )));
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (!isDeleteByQuerySuccessful(response)) {
                log.warn("RAG 清理文件 chunks 未完整成功, appId={}, filePath={}", appId, filePath);
                return false;
            }
            log.debug("RAG 已清理文件 chunks, appId={}, filePath={}", appId, filePath);
            return true;
        } catch (Exception e) {
            log.warn("RAG 清理文件 chunks 失败, appId={}, filePath={}", appId, filePath, e);
            return false;
        }
    }

    @Override
    public boolean deleteAppChunks(Long appId) {
        try {
            // 1. 只按应用过滤，清理该应用的全部文件文档。
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("appId").value(appId))));
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (!isDeleteByQuerySuccessful(response)) {
                log.warn("RAG 清理应用 chunks 未完整成功, appId={}", appId);
                return false;
            }
            log.debug("RAG 已清理应用 chunks, appId={}", appId);
            return true;
        } catch (Exception e) {
            log.warn("RAG 清理应用 chunks 失败, appId={}", appId, e);
            return false;
        }
    }

    private boolean isDeleteByQuerySuccessful(DeleteByQueryResponse response) {
        return response != null
                && !response.timedOut()
                && (response.failures() == null || response.failures().isEmpty());
    }
}
