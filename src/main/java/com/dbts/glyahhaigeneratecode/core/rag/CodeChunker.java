package com.dbts.glyahhaigeneratecode.core.rag;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 源码切块器。
 * <p>按行切分，支持 overlap，对大文件和二进制/不可读文件做跳过。</p>
 * <p>切块策略保守：先按行分块，再尝试对齐到空行边界削减碎片感。
 * 不对语言 AST 做依赖，以稳定、可解释为首要目标。</p>
 */
@Slf4j
public class CodeChunker {
    // 每块最多多少行
    private final int maxLines;
    // 相邻两块重叠多少行
    private final int overlapLines;
    // 超过多少行的文件直接跳过
    private final int maxFileLines;

    public CodeChunker(int maxLines, int overlapLines, int maxFileLines) {
        this.maxLines = Math.max(1, maxLines);
        this.overlapLines = Math.max(0, Math.min(overlapLines, this.maxLines / 2));
        this.maxFileLines = maxFileLines > 0 ? maxFileLines : Integer.MAX_VALUE;
    }

    /**
     * 切分源码内容。
     *
     * @param filePath 文件路径（仅用于日志和语言检测）
     * @param content  文件文本内容
     * @param language 语言标签
     * @return 有序的代码块列表（不可变）；内容为空或不可切时返回空列表
     */
    public List<Chunk> chunk(String filePath, String content, String language) {
        if (StrUtil.isBlank(content)) {
            return Collections.emptyList();
        }

        String[] lines = content.split("\n", -1);
        int totalLines = lines.length;

        // 跳过超大文件
        if (totalLines > maxFileLines) {
            log.debug("跳过超大文件, path={}, lines={}, maxFileLines={}", filePath, totalLines, maxFileLines);
            return Collections.emptyList();
        }

        // 文件很小，整文件一块
        if (totalLines <= maxLines) {
            return List.of(new Chunk(filePath, 0, content, language, 1, totalLines));
        }

        List<Chunk> result = new ArrayList<>();
        int chunkIndex = 0;
        int start = 0;

        // 将代码块切成 chunk
        while (start < totalLines) {
            int previousStart = start;
            int end = Math.min(start + maxLines, totalLines);
            // 尝试向前找空行做边界对齐（避免在代码行中间断句）
            if (end < totalLines) {
                int adjustedEnd = findBoundaryLine(lines, end, start);
                end = adjustedEnd;
            }

            // 提取文本
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(lines[i]).append('\n');
            }
            // 去掉末尾多余换行
            String chunkText = sb.toString().stripTrailing();

            result.add(new Chunk(filePath, chunkIndex, chunkText, language, start + 1, end));

            chunkIndex++;
            // 已经覆盖到 EOF，不再为了 overlap 重复生成尾块
            if (end >= totalLines) {
                break;
            }

            // 下一块保留 overlap，但起点必须比上一轮严格前进
            int nextStart = end - overlapLines;
            if (nextStart <= previousStart) {
                nextStart = end;
            }
            start = nextStart;
        }

        return Collections.unmodifiableList(result);
    }

    /**
     * 从当前结束位置向前搜索空行或只含空白字符的行，使断句更自然。
     * 至少保留 start+1，保证每块至少 1 行。
     */
    private int findBoundaryLine(String[] lines, int candidateEnd, int chunkStart) {
        int boundary = candidateEnd;
        // 最多回退 overlapLines 行
        int searchStart = Math.max(chunkStart + 1, candidateEnd - Math.max(overlapLines, 1));
        for (int i = candidateEnd - 1; i >= searchStart; i--) {
            if (i >= 0 && i < lines.length && lines[i].trim().isEmpty()) {
                boundary = i + 1; // 空行作为下一块起点
                break;
            }
        }
        return Math.max(boundary, chunkStart + 1);
    }

    /**
     * 一个代码块。
     */
    public record Chunk(
            String filePath,
            int chunkIndex,
            String content,
            String language,
            int startLine,
            int endLine
    ) {
    }
}
