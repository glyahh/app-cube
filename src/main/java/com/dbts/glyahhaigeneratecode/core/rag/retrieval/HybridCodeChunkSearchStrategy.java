package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import com.dbts.glyahhaigeneratecode.core.rag.repository.CodeChunkSearchCriteria;
import com.dbts.glyahhaigeneratecode.core.rag.repository.CodeChunkSearchRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * BM25 与 KNN 混合检索策略；查询向量不可用时自动退化为 BM25。
 */
@Slf4j
@Component
public class HybridCodeChunkSearchStrategy implements CodeChunkSearchStrategy {

    private final EmbeddingService embeddingService;
    private final CodeChunkSearchRepository repository;
    private final RagProperties properties;

    public HybridCodeChunkSearchStrategy(
            EmbeddingService embeddingService,
            CodeChunkSearchRepository repository,
            RagProperties properties) {
        this.embeddingService = embeddingService;
        this.repository = repository;
        this.properties = properties;
    }

    @Override
    public List<RagCodeChunkCandidate> search(Long appId, String userQuery) throws IOException {
        // 1. 生成查询向量；失败时沿用现有 BM25-only 降级语义。
        float[] queryVector = embeddingService.embed(userQuery);
        if (queryVector == null) {
            log.warn("RAG embedding 失败，降级为 BM25-only, appId={}", appId);
        }

        // 2. 计算候选数量并把稳定参数交给检索仓储。
        int topK = properties.getRetrieval().getTopK();
        int candidateMultiplier = Math.max(1, properties.getRetrieval().getNumCandidatesMultiplier());
        int numCandidates = Math.max(topK, topK * candidateMultiplier);
        CodeChunkSearchCriteria criteria = new CodeChunkSearchCriteria(
                appId,
                userQuery,
                topK,
                properties.getRetrieval().getKeywordBoost(),
                properties.getRetrieval().getVectorBoost(),
                numCandidates,
                toFloatList(queryVector)
        );
        return repository.search(criteria);
    }

    private List<Float> toFloatList(float[] vector) {
        if (vector == null) {
            return null;
        }
        List<Float> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(value);
        }
        return values;
    }
}
