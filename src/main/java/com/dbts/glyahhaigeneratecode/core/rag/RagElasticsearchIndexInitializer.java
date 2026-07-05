package com.dbts.glyahhaigeneratecode.core.rag;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用就绪后确保 ES {@code code_chunks} 索引存在（含 dense_vector 映射）。
 * <p>与 {@link RagConfiguration} 分离，复用容器内单例 {@link ElasticsearchClient}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RagElasticsearchIndexInitializer {

    private static final int MAX_RETRIES = 5;
    private static final int BASE_DELAY_MS = 1000;

    private final RagProperties properties;
    private final ElasticsearchClient esClient;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexOnStartup() {
        if (!properties.isEnabled()) {
            log.info("RAG 已禁用，跳过 ES 索引初始化");
            return;
        }

        String indexName = properties.getElasticsearch().getIndexName();
        Exception lastEx = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                ensureIndex(indexName);
                return;
            } catch (Exception e) {
                lastEx = e;
                if (attempt >= MAX_RETRIES) {
                    break;
                }
                long delay = (long) (BASE_DELAY_MS * Math.pow(2, attempt - 1));
                log.warn("ES 索引初始化失败（{}/{}），{}ms 后重试: {}",
                        attempt, MAX_RETRIES, delay, e.getMessage());
                sleep(delay);
            }
        }
        log.warn("ES 索引初始化失败，已用尽重试。后续写入可能缺 dense_vector 映射, index={}, error={}",
                indexName, lastEx != null ? lastEx.getMessage() : "unknown");
    }

    private void ensureIndex(String indexName) throws Exception {
        ExistsRequest existsRequest = ExistsRequest.of(e -> e.index(indexName));
        if (esClient.indices().exists(existsRequest).value()) {
            log.info("ES 索引已存在，跳过创建 index={}", indexName);
            return;
        }

        int dims = properties.getEmbedding().getDimension();
        CreateIndexRequest createRequest = CreateIndexRequest.of(c -> c
                .index(indexName)
                .mappings(m -> m
                        .properties("appId", p -> p.long_(lp -> lp))
                        .properties("filePath", p -> p.keyword(k -> k))
                        .properties("chunkIndex", p -> p.integer(ip -> ip))
                        .properties("content", p -> p.text(t -> t))
                        .properties("contentHash", p -> p.keyword(k -> k))
                        .properties("language", p -> p.keyword(k -> k))
                        .properties("startLine", p -> p.integer(ip -> ip))
                        .properties("endLine", p -> p.integer(ip -> ip))
                        .properties("updatedAt", p -> p.keyword(k -> k))
                        .properties("embedding", p -> p.denseVector(dv -> dv.dims(dims)))
                )
        );
        esClient.indices().create(createRequest);
        log.info("ES 索引创建完成 index={}, dimension={}", indexName, dims);
    }

    private static void sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
