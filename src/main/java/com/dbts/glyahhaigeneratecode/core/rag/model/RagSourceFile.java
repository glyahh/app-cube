package com.dbts.glyahhaigeneratecode.core.rag.model;

import java.nio.file.Path;

/**
 * 已通过项目目录边界校验的 RAG 源文件快照。
 */
public record RagSourceFile(
        Path path,
        String filePath,
        String content,
        String contentHash
) {
}
