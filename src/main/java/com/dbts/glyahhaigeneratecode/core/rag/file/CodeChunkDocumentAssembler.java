package com.dbts.glyahhaigeneratecode.core.rag.file;

import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunker;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagSourceFile;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 将源文件快照切块并装配为 Elasticsearch 代码块文档。
 */
public class CodeChunkDocumentAssembler {

    private final CodeChunker chunker;
    private final CodeLanguageDetector languageDetector;
    private final Clock clock;

    public CodeChunkDocumentAssembler(CodeChunker chunker, CodeLanguageDetector languageDetector) {
        this(chunker, languageDetector, Clock.systemUTC());
    }

    CodeChunkDocumentAssembler(CodeChunker chunker, CodeLanguageDetector languageDetector, Clock clock) {
        this.chunker = chunker;
        this.languageDetector = languageDetector;
        this.clock = clock;
    }

    public List<CodeChunkDocument> assemble(Long appId, RagSourceFile sourceFile) {
        // 1. 使用文件语言标签执行现有切块算法。
        String language = languageDetector.detect(sourceFile.filePath());
        List<CodeChunker.Chunk> chunks = chunker.chunk(
                sourceFile.filePath(), sourceFile.content(), language);
        if (chunks.isEmpty()) {
            return List.of();
        }

        // 2. 按切块顺序构造文档，保留原有字段和值语义。
        List<CodeChunkDocument> documents = new ArrayList<>(chunks.size());
        for (CodeChunker.Chunk chunk : chunks) {
            documents.add(new CodeChunkDocument(
                    appId,
                    chunk.filePath(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    sourceFile.contentHash(),
                    chunk.language(),
                    chunk.startLine(),
                    chunk.endLine(),
                    Instant.now(clock).toString()
            ));
        }
        return List.copyOf(documents);
    }
}
