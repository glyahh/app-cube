package com.dbts.glyahhaigeneratecode.core.rag.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Elasticsearch 代码块检索适配器，负责构造查询请求并转换命中文档。
 */
@Repository
public class ElasticsearchCodeChunkSearchRepository implements CodeChunkSearchRepository {

    private final ElasticsearchClient esClient;
    private final RagProperties properties;

    public ElasticsearchCodeChunkSearchRepository(
            ElasticsearchClient esClient,
            RagProperties properties) {
        this.esClient = esClient;
        this.properties = properties;
    }

    @Override
    public List<RagCodeChunkCandidate> search(CodeChunkSearchCriteria criteria) throws IOException {
        // 1. 按策略计算结果构造 BM25 查询，并始终限定应用范围。
        SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                .index(properties.getElasticsearch().getIndexName())
                .size(criteria.topK())
                .query(q -> q.bool(b -> b
                        .must(m -> m.match(mm -> mm
                                .field("content")
                                .query(criteria.userQuery())
                                .boost(criteria.keywordBoost())
                        ))
                        .filter(f -> f.term(t -> t.field("appId").value(criteria.appId())))
                ))
                .source(sc -> sc.filter(sf -> sf.excludes("embedding")));

        // 2. 查询向量有效时追加 KNN；无向量时保留 BM25-only 请求。
        if (criteria.hasQueryVector()) {
            requestBuilder.knn(k -> k
                    .field("embedding")
                    .queryVector(criteria.queryVector())
                    .k(criteria.topK())
                    .numCandidates(criteria.numCandidates())
                    .boost(criteria.vectorBoost())
                    .filter(f -> f.term(t -> t.field("appId").value(criteria.appId()))));
        }

        // 3. 执行查询并按 ES 返回顺序转换候选结果。
        SearchResponse<ObjectNode> response = esClient.search(requestBuilder.build(), ObjectNode.class);
        List<Hit<ObjectNode>> hits = response.hits().hits();
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }

        List<RagCodeChunkCandidate> candidates = new ArrayList<>(hits.size());
        for (Hit<ObjectNode> hit : hits) {
            ObjectNode source = hit.source();
            if (source == null) {
                continue;
            }
            candidates.add(toCandidate(hit, source));
        }
        return List.copyOf(candidates);
    }

    private RagCodeChunkCandidate toCandidate(Hit<ObjectNode> hit, ObjectNode source) {
        double score = hit.score() == null ? 0.0 : hit.score();
        return new RagCodeChunkCandidate(
                getString(source, "filePath"),
                getInt(source, "chunkIndex", 0),
                getString(source, "content"),
                getString(source, "contentHash"),
                getString(source, "language"),
                getInt(source, "startLine", 1),
                getInt(source, "endLine", 1),
                (float) score
        );
    }

    private String getString(ObjectNode source, String field) {
        return source.get(field) == null ? "" : source.get(field).asText();
    }

    private int getInt(ObjectNode source, String field, int defaultValue) {
        return source.get(field) == null ? defaultValue : source.get(field).asInt(defaultValue);
    }
}
