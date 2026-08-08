package com.dbts.glyahhaigeneratecode.core.rag.repository;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.model.EmbeddedCodeChunk;

import java.util.List;

/**
 * 代码块索引写仓储，封装文件版本校验、完整写入和索引清理。
 */
public interface CodeChunkIndexRepository {

    /** 校验文件在索引中的文档集合是否与当前切块结果完全一致。 */
    boolean isFileIndexCurrent(List<CodeChunkDocument> documents);

    /**
     * 完整写入一份文件的全部向量化代码块；bulk 一旦提交，失败时清理该文件。
     */
    boolean writeAllOrCleanup(List<EmbeddedCodeChunk> chunks);

    /** 删除新切块数量之外的旧尾块。 */
    boolean deleteStaleChunks(Long appId, String filePath, int newChunkCount);

    /** 删除指定文件的全部代码块。 */
    boolean deleteFileChunks(Long appId, String filePath);

    /** 删除指定应用的全部代码块。 */
    boolean deleteAppChunks(Long appId);
}
