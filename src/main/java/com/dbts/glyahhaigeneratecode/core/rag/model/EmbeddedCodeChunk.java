package com.dbts.glyahhaigeneratecode.core.rag.model;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;

/**
 * 已完成向量化、可以提交给索引仓储的代码块。
 */
public record EmbeddedCodeChunk(CodeChunkDocument document, float[] embedding) {

    public EmbeddedCodeChunk {
        embedding = embedding == null ? null : embedding.clone();
    }

    @Override
    public float[] embedding() {
        return embedding == null ? null : embedding.clone();
    }
}
