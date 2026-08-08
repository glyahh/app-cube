package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryManifestSupport;
import com.dbts.glyahhaigeneratecode.core.rag.file.RagSourceFileReader;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用磁盘当前文件 hash 过滤检索候选，阻止旧版本代码进入模型上下文。
 */
@Component
public class CurrentDiskChunkFilter {

    private final RagSourceFileReader sourceFileReader;

    public CurrentDiskChunkFilter(RagSourceFileReader sourceFileReader) {
        this.sourceFileReader = sourceFileReader;
    }

    public List<RagCodeChunkCandidate> filter(
            Long appId,
            CodeGenTypeEnum codeGenType,
            List<RagCodeChunkCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 1. 定位当前应用项目目录，并为每个相对路径缓存一次磁盘 hash。
        Path root = ConversationMemoryManifestSupport.resolveProjectRoot(appId, codeGenType)
                .toAbsolutePath().normalize();
        Map<String, String> diskHashes = new HashMap<>();
        List<RagCodeChunkCandidate> verified = new ArrayList<>(candidates.size());

        // 2. 保留路径有效且文件版本与磁盘一致的候选，顺序保持不变。
        for (RagCodeChunkCandidate candidate : candidates) {
            if (candidate.filePath() == null || candidate.filePath().isBlank()
                    || candidate.contentHash() == null || candidate.contentHash().isBlank()) {
                continue;
            }
            String diskHash = diskHashes.computeIfAbsent(
                    candidate.filePath(), filePath -> readCurrentHash(root, filePath));
            if (candidate.contentHash().equals(diskHash)) {
                verified.add(candidate);
            }
        }
        return List.copyOf(verified);
    }

    private String readCurrentHash(Path root, String filePath) {
        return sourceFileReader.read(root, filePath)
                .map(sourceFile -> sourceFile.contentHash())
                .orElse("");
    }
}
