package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.file.RagSourceFileReader;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import com.dbts.glyahhaigeneratecode.core.rag.repository.ElasticsearchCodeChunkSearchRepository;
import com.dbts.glyahhaigeneratecode.core.rag.retrieval.CodeChunkSearchStrategy;
import com.dbts.glyahhaigeneratecode.core.rag.retrieval.CurrentDiskChunkFilter;
import com.dbts.glyahhaigeneratecode.core.rag.retrieval.HybridCodeChunkSearchStrategy;
import com.dbts.glyahhaigeneratecode.core.rag.retrieval.RagInjectFormatter;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * RAG 代码块检索门面，编排候选检索、磁盘版本校验和注入文本格式化。
 */
@Slf4j
@Service
public class CodeChunkRetrievalService {

    private final CodeChunkSearchStrategy searchStrategy;
    private final CurrentDiskChunkFilter diskChunkFilter;
    private final RagInjectFormatter injectFormatter;
    private final RagProperties properties;

    /**
     * Spring 使用的完整依赖构造器。
     */
    @Autowired
    public CodeChunkRetrievalService(
            CodeChunkSearchStrategy searchStrategy,
            CurrentDiskChunkFilter diskChunkFilter,
            RagInjectFormatter injectFormatter,
            RagProperties properties) {
        this.searchStrategy = searchStrategy;
        this.diskChunkFilter = diskChunkFilter;
        this.injectFormatter = injectFormatter;
        this.properties = properties;
    }

    /**
     * 保留原三参数构造器，兼容现有测试和直接创建服务的调用方。
     */
    public CodeChunkRetrievalService(
            ElasticsearchClient esClient,
            EmbeddingService embeddingService,
            RagProperties properties) {
        this(
                new HybridCodeChunkSearchStrategy(
                        embeddingService,
                        new ElasticsearchCodeChunkSearchRepository(esClient, properties),
                        properties),
                new CurrentDiskChunkFilter(new RagSourceFileReader()),
                new RagInjectFormatter(properties),
                properties
        );
    }

    /**
     * 检索当前应用相关代码块；任一外部依赖失败时返回空结果。
     */
    public List<CodeChunkHit> searchTopK(Long appId, String userQuery, CodeGenTypeEnum codeGenType) {
        // 1. 保留原有开关与参数校验。
        if (!properties.isEnabled() || appId == null || appId <= 0 || userQuery == null
                || userQuery.isBlank() || codeGenType == null) {
            return List.of();
        }

        try {
            // 2. 执行检索并以磁盘当前版本过滤候选。
            List<RagCodeChunkCandidate> candidates = searchStrategy.search(appId, userQuery);
            List<RagCodeChunkCandidate> verified = diskChunkFilter.filter(appId, codeGenType, candidates);
            log.debug("RAG 检索完成 appId={}, queryLen={}, candidates={}, verified={}",
                    appId, userQuery.length(), candidates.size(), verified.size());

            // 3. 转换为兼容公开 DTO，保持命中顺序不变。
            return verified.stream().map(CodeChunkRetrievalService::toHit).toList();
        } catch (Exception e) {
            log.warn("RAG 检索失败，已降级，appId={}", appId, e);
            return List.of();
        }
    }

    /**
     * 将检索结果格式化为可注入 LLM 上下文的文本；无结果时返回 {@code null}。
     */
    public String formatAsInject(List<CodeChunkHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return injectFormatter.format(List.of());
        }
        return injectFormatter.format(hits.stream()
                .map(CodeChunkRetrievalService::toCandidate)
                .toList());
    }

    private static CodeChunkHit toHit(RagCodeChunkCandidate candidate) {
        return new CodeChunkHit(
                candidate.filePath(),
                candidate.chunkIndex(),
                candidate.content(),
                candidate.contentHash(),
                candidate.language(),
                candidate.startLine(),
                candidate.endLine(),
                candidate.score()
        );
    }

    private static RagCodeChunkCandidate toCandidate(CodeChunkHit hit) {
        return new RagCodeChunkCandidate(
                hit.filePath(),
                hit.chunkIndex(),
                hit.content(),
                hit.contentHash(),
                hit.language(),
                hit.startLine(),
                hit.endLine(),
                hit.score()
        );
    }

    /**
     * 公开兼容的代码块命中结果。
     */
    public record CodeChunkHit(
            String filePath,
            int chunkIndex,
            String content,
            String contentHash,
            String language,
            int startLine,
            int endLine,
            float score
    ) {
    }
}
