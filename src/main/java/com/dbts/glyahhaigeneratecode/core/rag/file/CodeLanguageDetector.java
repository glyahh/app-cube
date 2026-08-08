package com.dbts.glyahhaigeneratecode.core.rag.file;

import java.util.Locale;

/**
 * 根据源码文件扩展名生成 RAG 文档使用的语言标签。
 */
public class CodeLanguageDetector {

    public String detect(String filePath) {
        // 1. 空路径沿用 text 兜底标签。
        if (filePath == null) {
            return "text";
        }

        // 2. 按原有后缀映射返回语言标签。
        String lower = filePath.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".java")) return "java";
        if (lower.endsWith(".vue")) return "vue";
        if (lower.endsWith(".ts") || lower.endsWith(".tsx")) return "typescript";
        if (lower.endsWith(".js") || lower.endsWith(".jsx")) return "javascript";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "html";
        if (lower.endsWith(".css") || lower.endsWith(".scss") || lower.endsWith(".less")) return "css";
        if (lower.endsWith(".json")) return "json";
        if (lower.endsWith(".xml")) return "xml";
        if (lower.endsWith(".md")) return "markdown";
        if (lower.endsWith(".sql")) return "sql";
        if (lower.endsWith(".yml") || lower.endsWith(".yaml")) return "yaml";
        if (lower.endsWith(".properties")) return "properties";
        return "text";
    }
}
