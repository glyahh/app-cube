package com.dbts.glyahhaigeneratecode.ai.tool.tools;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vue SFC 语法检查与修复工具（后端兜底）。
 *
 * 设计目的：
 * - 当 LLM 生成的 .vue 文件存在顶层块标签开闭合不匹配（例如缺少 </style>）时，
 *   vite build 会直接失败（典型错误：Element is missing end tag）。
 * - 本工具用于在构建前做“最小侵入”修复：仅对 <template>/<script>/<style> 三类顶层块进行补齐或移除。
 */
@Slf4j
@Component
public class VueSfcSyntaxCheckFixTool {

    /**
     * LLM 常见错误：开始标签属性写完后漏写 {@code >}，直接嵌套下一个标签。
     * 例：{@code <div class="grid"      <div class="card">} → {@code <div class="grid"><div class="card">}
     */
    private static final Pattern UNCLOSED_OPENING_TAG_BEFORE_NESTED = Pattern.compile(
            "(<[A-Za-z][\\w-]*(?:\\s+[\\w:@.-]+(?:=(?:\"[^\"]*\"|'[^']*'|[^\\s>/]*))?)*\\s+)(<[A-Za-z])");

    /**
     * 扫描并修复 Vue 项目 src 目录下的 .vue 文件顶层块开闭合标签不平衡问题。
     *
     * @param projectRoot Vue 项目根目录（绝对路径）
     * @return 修复统计信息（用于日志/观测）
     */
    public FixSummary fixProjectVueFiles(String projectRoot) throws IOException {
        if (StrUtil.isBlank(projectRoot)) {
            return new FixSummary(0, 0, 0);
        }
        Path srcDir = Path.of(projectRoot).resolve("src");
        if (!Files.isDirectory(srcDir)) {
            return new FixSummary(0, 0, 0);
        }

        int scanned = 0;
        int changed = 0;
        int failed = 0;
        try (var stream = Files.walk(srcDir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(p) || !p.toString().endsWith(".vue")) {
                    continue;
                }
                scanned++;
                try {
                    boolean modified = repairSingleVueFile(p);
                    if (modified) {
                        changed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("Vue SFC 修复失败: {}, 原因: {}", p.toAbsolutePath(), e.getMessage());
                }
            }
        }
        return new FixSummary(scanned, changed, failed);
    }

    private boolean repairSingleVueFile(Path vueFile) throws IOException {
        String raw = Files.readString(vueFile, StandardCharsets.UTF_8);
        String fixed = raw;

        // 修复 template 内「开始标签未闭合就嵌套子标签」的 HTML 错误
        fixed = fixUnclosedOpeningTagBeforeNestedTag(fixed);

        // 先移除“多余的闭合标签”（常见于模型胡乱输出）
        fixed = trimExcessClosingTags(fixed, "<template", "</template>");
        fixed = trimExcessClosingTags(fixed, "<script", "</script>");
        fixed = trimExcessClosingTags(fixed, "<style", "</style>");

        // 再补齐“缺失的闭合标签”（典型错误：Element is missing end tag）
        fixed = appendMissingClosingTags(fixed, "<template", "</template>");
        fixed = appendMissingClosingTags(fixed, "<script", "</script>");
        fixed = appendMissingClosingTags(fixed, "<style", "</style>");

        if (!raw.equals(fixed)) {
            Files.writeString(vueFile, fixed.stripTrailing() + System.lineSeparator(), StandardCharsets.UTF_8);
            log.info("已修复 Vue SFC 顶层块标签: {}", vueFile.toAbsolutePath());
            return true;
        }
        return false;
    }

    private String fixUnclosedOpeningTagBeforeNestedTag(String text) {
        int templateOpen = text.indexOf("<template");
        if (templateOpen < 0) {
            return text;
        }
        int templateContentStart = text.indexOf('>', templateOpen);
        if (templateContentStart < 0) {
            return text;
        }
        int templateClose = text.lastIndexOf("</template>");
        if (templateClose <= templateContentStart) {
            return text;
        }

        String before = text.substring(0, templateContentStart + 1);
        String templateBody = text.substring(templateContentStart + 1, templateClose);
        String after = text.substring(templateClose);

        Matcher matcher = UNCLOSED_OPENING_TAG_BEFORE_NESTED.matcher(templateBody);
        String fixedBody = matcher.replaceAll("$1>$2");
        return before + fixedBody + after;
    }

    private String appendMissingClosingTags(String text, String openingTagPrefix, String closingTag) {
        int openingCount = countMatches(text, openingTagPrefix);
        int closingCount = countMatches(text, closingTag);
        int missingCount = openingCount - closingCount;
        if (missingCount <= 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        for (int i = 0; i < missingCount; i++) {
            sb.append(System.lineSeparator()).append(closingTag);
        }
        return sb.toString();
    }

    private String trimExcessClosingTags(String text, String openingTagPrefix, String closingTag) {
        int openingCount = countMatches(text, openingTagPrefix);
        int closingCount = countMatches(text, closingTag);
        int excessCount = closingCount - openingCount;
        if (excessCount <= 0) {
            return text;
        }
        String result = text;
        for (int i = 0; i < excessCount; i++) {
            int lastIdx = result.lastIndexOf(closingTag);
            if (lastIdx < 0) {
                break;
            }
            result = result.substring(0, lastIdx) + result.substring(lastIdx + closingTag.length());
        }
        return result;
    }

    private int countMatches(String text, String pattern) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    public record FixSummary(int scannedFiles, int changedFiles, int failedFiles) {
    }
}

