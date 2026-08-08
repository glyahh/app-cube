package com.dbts.glyahhaigeneratecode.core.rag.index;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.model.EmbeddedCodeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码块向量化器，只有整份文件的向量全部生成成功时才返回结果。
 */
@Slf4j
@Component
public class CodeChunkVectorizer {

    private final EmbeddingService embeddingService;

    public CodeChunkVectorizer(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    /**
     * 按输入顺序向量化全部代码块，失败时返回空列表。
     */
    public List<EmbeddedCodeChunk> vectorize(List<CodeChunkDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        CodeChunkDocument first = documents.getFirst();
        try {
            // 1. 一次提交整份文件的代码块内容。
            List<String> contents = documents.stream().map(CodeChunkDocument::content).toList();
            float[][] vectors = embeddingService.embedBatch(contents);
            if (vectors == null || vectors.length != documents.size()) {
                log.warn("RAG embedding 数量不完整, appId={}, file={}, expected={}, actual={}",
                        first.appId(), first.filePath(), documents.size(), vectors == null ? 0 : vectors.length);
                return List.of();
            }

            // 2. 任一向量缺失时整批失败，禁止提交部分文件版本。
            List<EmbeddedCodeChunk> result = new ArrayList<>(documents.size());
            for (int i = 0; i < documents.size(); i++) {
                if (vectors[i] == null) {
                    log.warn("RAG embedding 失败，整份文件不提交, appId={}, file={}, idx={}",
                            first.appId(), first.filePath(), i);
                    return List.of();
                }
                result.add(new EmbeddedCodeChunk(documents.get(i), vectors[i]));
            }
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("RAG embedding 失败，整份文件不提交, appId={}, file={}",
                    first.appId(), first.filePath(), e);
            return List.of();
        }
    }
}
