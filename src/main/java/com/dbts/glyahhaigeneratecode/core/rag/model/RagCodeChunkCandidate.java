package com.dbts.glyahhaigeneratecode.core.rag.model;

/**
 * Elasticsearch 返回的代码块候选，供检索应用层执行版本过滤和排序。
 */
public record RagCodeChunkCandidate(
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
