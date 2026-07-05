package com.dbts.glyahhaigeneratecode.core.rag;

/**
 * ES 索引中一条代码块文档。
 * <p>对应 index {@code code_chunks}，包含元数据与向量字段。</p>
 *
 * @param appId       应用 id（用于隔离检索）
 * @param filePath    文件相对路径（如 {@code src/App.vue}）
 * @param chunkIndex  块序号（0 起）
 * @param content     块文本内容
 * @param contentHash 文件内容 SHA-256 前缀（20 位 hex），用于检测文件变动
 * @param language    语言标签（java / vue / html 等）
 * @param startLine   块起始行号（1 起）
 * @param endLine     块结束行号（含）
 * @param updatedAt   索引时间戳
 */
public record CodeChunkDocument(
        Long appId,
        String filePath,
        int chunkIndex,
        String content,
        String contentHash,
        String language,
        int startLine,
        int endLine,
        String updatedAt
) {

    // 存储 ID
    public String docId() {
        return appId + ":" + filePath + ":" + chunkIndex;
    }
}
