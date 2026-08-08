package com.dbts.glyahhaigeneratecode.core.rag.repository;

import java.util.List;

/**
 * 代码块检索条件，由检索策略计算后交给存储适配器执行。
 * {@code queryVector} 为 {@code null} 表示向量不可用；非 null 空列表表示已生成空向量，仍执行 KNN。
 */
public record CodeChunkSearchCriteria(
        Long appId,
        String userQuery,
        int topK,
        float keywordBoost,
        float vectorBoost,
        int numCandidates,
        List<Float> queryVector
) {

    public CodeChunkSearchCriteria {
        queryVector = queryVector == null ? null : List.copyOf(queryVector);
    }

    public boolean hasQueryVector() {
        return queryVector != null;
    }
}
