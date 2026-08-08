package com.dbts.glyahhaigeneratecode.core.rag.retrieval;

import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryInjectTexts;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagCodeChunkCandidate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 把已校验的代码块候选格式化为受字符预算约束的模型注入文本。
 */
@Component
public class RagInjectFormatter {

    private final RagProperties properties;

    public RagInjectFormatter(RagProperties properties) {
        this.properties = properties;
    }

    public String format(List<RagCodeChunkCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        // 1. 写入固定注入标签和说明文本。
        int budget = properties.getRetrieval().getCharBudget();
        StringBuilder builder = new StringBuilder();
        builder.append(ConversationMemoryInjectTexts.INJECT_TAG).append("\n");
        builder.append("以下为 ES 向量检索到的相关源码片段，已按相关性排序。以磁盘 readFile 为准。\n\n");

        // 2. 按现有预算规则追加候选；首个超预算条目截断后停止。
        int remaining = budget - builder.length();
        for (RagCodeChunkCandidate candidate : candidates) {
            if (remaining <= 0) {
                break;
            }
            String header = String.format("=== %s (lines %d-%d, score=%.2f) ===",
                    candidate.filePath(), candidate.startLine(), candidate.endLine(), candidate.score());
            String entry = header + "\n" + candidate.content() + "\n\n";
            if (entry.length() > remaining) {
                int maxContentLength = remaining - header.length() - 10;
                if (maxContentLength > 80) {
                    String content = candidate.content();
                    String truncated = content.substring(0, Math.min(content.length(), maxContentLength));
                    builder.append(header).append("\n").append(truncated).append("\n...(truncated)\n\n");
                }
                break;
            }
            builder.append(entry);
            remaining -= entry.length();
        }
        return builder.toString();
    }
}
