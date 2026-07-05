package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunker;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 代码块索引服务：新增/修改文件时切块 → 生成 embedding → 写入 ES；
 * 删除文件时清理对应 chunk 文档。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeChunkIndexService {

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final CodeChunker chunker;
    private final RagProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * RAG 单文件索引入口：读取磁盘源码 → 切块 → bulk upsert → 清理多余旧 chunk。
     *
     * @param appId    应用 id，用于 ES 文档按应用隔离
     * @param filePath 项目内相对路径
     * @param root     代码输出根目录（与 filePath 拼接为绝对路径）
     * @param fileNote 预留的文件变更摘要，当前未参与切块，可传 null
     */
    public void indexFile(Long appId, String filePath, Path root, String fileNote) {
        // 1. RAG 开关与入参校验
        if (!properties.isEnabled()) {
            log.debug("RAG 已禁用，跳过索引 appId={}, filePath={}", appId, filePath);
            return;
        }
        if (appId == null || appId <= 0 || filePath == null || root == null) {
            return;
        }

        try {
            // 2. 读取文件内容、计算 hash、按扩展名切块
            Path fullPath = root.resolve(filePath);
            if (!Files.isRegularFile(fullPath)) {
                log.debug("索引文件不存在或不可读，跳过 appId={}, filePath={}", appId, filePath);
                return;
            }

            byte[] rawBytes = Files.readAllBytes(fullPath);
            String content = new String(rawBytes, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                return;
            }
            String fileHash = sha256Prefix(rawBytes);

            String lang = detectLang(filePath);
            List<CodeChunker.Chunk> chunks = chunker.chunk(filePath, content, lang);
            if (chunks.isEmpty()) {
                return;
            }

            // 3. 组装 CodeChunkDocument 列表
            List<CodeChunkDocument> docs = new ArrayList<>(chunks.size());
            for (CodeChunker.Chunk c : chunks) {
                CodeChunkDocument doc = new CodeChunkDocument(
                        appId, c.filePath(), c.chunkIndex(), c.content(),
                        fileHash, c.language(), c.startLine(), c.endLine(),
                        Instant.now().toString()
                );
                docs.add(doc);
            }

            // 4. bulk upsert；成功后再清理 chunkIndex >= newCount 的旧文档
            if (indexDocuments(docs)) {
                deleteStaleChunks(appId, filePath, chunks.size());
                log.info("RAG 索引完成 appId={}, filePath={}, chunks={}", appId, filePath, chunks.size());
            }
        } catch (Exception e) {
            log.warn("RAG 索引失败，已降级，appId={}, filePath={}", appId, filePath, e);
        }
    }

    /**
     * 批量索引多个文件，逐文件委托 {@link #indexFile} 处理。
     *
     * @param appId     应用 id
     * @param filePaths 待索引的相对路径列表
     * @param root      代码输出根目录
     */
    public void indexFiles(Long appId, List<String> filePaths, Path root) {

        // 1. RAG 开关与列表空校验
        if (!properties.isEnabled() || filePaths == null || filePaths.isEmpty()) {
            return;
        }
        // 2. 逐文件调用 indexFile 完成索引
        for (String fp : filePaths) {
            indexFile(appId, fp, root, null);
        }
    }

    /**
     * 按 appId + filePath 删除 ES 中该文件的全部 chunk 文档（索引前清理或文件删除时调用）。
     *
     * @param appId    应用 id
     * @param filePath 项目内相对路径
     */
    public void deleteFileChunks(Long appId, String filePath) {

        // 1. RAG 开关校验
        if (!properties.isEnabled()) {
            return;
        }
        try {
            // 2. 构造 term 查询并执行 deleteByQuery
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .query(q -> q.bool(bq -> bq
                            .must(m -> m.term(t -> t.field("appId").value(appId)))
                            .must(m -> m.term(t -> t.field("filePath").value(filePath)))
                    ))
            );
            esClient.deleteByQuery(request);
            log.debug("RAG 已清理旧 chunks, appId={}, filePath={}", appId, filePath);
        } catch (Exception e) {
            log.warn("RAG 清理旧 chunks 失败, appId={}, filePath={}", appId, filePath, e);
        }
    }

    /**
     * 删除 chunkIndex &gt;= newChunkCount 的多余旧文档（bulk 成功写入后调用）。
     */
    void deleteStaleChunks(Long appId, String filePath, int newChunkCount) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .query(q -> q.bool(bq -> bq
                            .must(m -> m.term(t -> t.field("appId").value(appId)))
                            .must(m -> m.term(t -> t.field("filePath").value(filePath)))
                            .must(m -> m.range(r -> r.number(nr -> nr.field("chunkIndex").gte((double) newChunkCount))))
                    ))
            );
            esClient.deleteByQuery(request);
            log.debug("RAG 已清理多余旧 chunks, appId={}, filePath={}, newChunkCount={}", appId, filePath, newChunkCount);
        } catch (Exception e) {
            log.warn("RAG 清理多余旧 chunks 失败, appId={}, filePath={}", appId, filePath, e);
        }
    }

    /**
     * 为每个 chunk 生成 embedding 并 bulk 写入 ES 索引。
     *
     * @param docs 待索引的代码块文档列表
     * @return bulk 是否全部成功
     */
    private boolean indexDocuments(List<CodeChunkDocument> docs) {
        // 1. 空列表早退
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        try {
            // 2. 逐文档 embed、序列化并组装 BulkOperation
            List<BulkOperation> operations = new ArrayList<>(docs.size());
            for (CodeChunkDocument doc : docs) {
                float[] vector = embeddingService.embed(doc.content());
                if (vector == null) {
                    log.warn("跳过 chunk embedding 失败, appId={}, file={}, idx={}",
                            doc.appId(), doc.filePath(), doc.chunkIndex());
                    continue;
                }
                // 将 doc 序列化为 Json
                ObjectNode docNode = objectMapper.valueToTree(doc);
                // 将 embedding 数组添加到 docNode 中
                ArrayNode embeddingArray = docNode.putArray("embedding");
                for (float v : vector) {
                    embeddingArray.add(v);
                }

                IndexOperation<ObjectNode> indexOp = IndexOperation.of(io -> io
                        .index(properties.getElasticsearch().getIndexName())
                        .id(doc.docId())
                        .document(docNode)
                );
                operations.add(BulkOperation.of(b -> b.index(indexOp)));
            }

            if (operations.isEmpty()) {
                log.warn("RAG bulk 跳过：无有效 embedding 操作");
                return false;
            }

            // 3. 提交 bulk 请求写入 ES
            BulkRequest bulkRequest = BulkRequest.of(b -> b.operations(operations));
            BulkResponse response = esClient.bulk(bulkRequest);
            if (response.errors()) {
                log.warn("RAG bulk 部分失败，已降级");
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("RAG bulk 写入失败，已降级", e);
            return false;
        }
    }

    /**
     * 计算文件内容的 SHA-256 前 20 个十六进制字符，用作 chunk 版本指纹。
     *
     * @param bytes 文件原始字节
     * @return 20 位十六进制前缀；摘要失败时退化为字节长度的十六进制
     */
    static String sha256Prefix(byte[] bytes) {

        try {
            // 1. SHA-256 摘要并截取前 10 字节转 hex
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(20);
            for (int i = 0; i < 10; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // 2. 算法不可用时退化为字节长度 hex
            return Long.toHexString(bytes.length);
        }
    }

    /**
     * 根据文件扩展名推断语言标识，供切块器选择切分策略。
     *
     * @param filePath 项目内相对路径
     * @return 语言标识（如 java、vue、typescript）；未知扩展名返回 text
     */
    static String detectLang(String filePath) {
        // 方法大纲：
        // 1. 空路径返回 text    L243
        // 2. 按扩展名后缀匹配语言标识    L245-L258

        // 1. 空路径返回 text
        if (filePath == null) return "text";
        // 2. 按扩展名后缀匹配语言标识
        String lower = filePath.toLowerCase(java.util.Locale.ROOT);
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
