package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryInjectTexts;
import com.dbts.glyahhaigeneratecode.core.memory.ConversationMemoryManifestSupport;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagFileHash;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.model.enums.CodeGenTypeEnum;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码块检索服务：根据用户 message 做 BM25 + KNN 混合检索 Top-K，返回格式化片段。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeChunkRetrievalService {

    private final ElasticsearchClient esClient;
    private final EmbeddingService embeddingService;
    private final RagProperties properties;

    /**
     * RAG 检索入口：在 ES 中组合 BM25 与 KNN，返回 Top-K 相关代码块。
     *
     * @param appId     应用 id，作为 ES filter 限定检索范围
     * @param userQuery 用户本轮输入，用于生成查询向量
     * @return 按相关性排序的代码块命中列表；RAG 禁用、参数无效或检索失败时返回空列表
     */
    public List<CodeChunkHit> searchTopK(Long appId, String userQuery, CodeGenTypeEnum codeGenType) {
        // 方法大纲：
        // 1. RAG 开关与入参校验    L46-L48
        // 2. 生成查询向量并构造 BM25 + KNN SearchRequest    L51-L76
        // 3. 执行检索并解析 Hit 为 CodeChunkHit 列表    L68-L94
        // 4. 异常时降级返回空列表    L96-L99

        // 1. RAG 开关与入参校验
        if (!properties.isEnabled() || appId == null || appId <= 0 || userQuery == null
                || userQuery.isBlank() || codeGenType == null) {
            return Collections.emptyList();
        }

        try {
            // 2. BM25 始终可用；embedding 失败时仅跳过 KNN 路径。
            float[] queryVector = embeddingService.embed(userQuery);
            if (queryVector == null) {
                log.warn("RAG embedding 失败，降级为 BM25-only, appId={}", appId);
            }

            int topK = properties.getRetrieval().getTopK();
            int candidateMultiplier = Math.max(1, properties.getRetrieval().getNumCandidatesMultiplier());
            int numCandidates = Math.max(topK, topK * candidateMultiplier);


            SearchRequest.Builder requestBuilder = new SearchRequest.Builder()
                    .index(properties.getElasticsearch().getIndexName())
                    .size(topK)
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(mm -> mm
                                    .field("content")
                                    .query(userQuery)
                                    .boost(properties.getRetrieval().getKeywordBoost())
                            ))
                            .filter(f -> f.term(t -> t.field("appId").value(appId)))
                    ))
                    // 排除出包含embedding的向量
                    .source(sc -> sc.filter(sf -> sf.excludes("embedding")));

            if (queryVector != null) {
                List<Float> queryVectorList = toFloatList(queryVector);
                requestBuilder
                        .knn(k -> k
                            .field("embedding")
                            .queryVector(queryVectorList)
                            .k(topK)
                            .numCandidates(numCandidates)
                            .boost(properties.getRetrieval().getVectorBoost())
                            .filter(f -> f.term(t -> t.field("appId").value(appId)))
                );
            }
            SearchRequest searchRequest = requestBuilder.build();

            // 3. 执行检索并解析 Hit 为 CodeChunkHit 列表
            SearchResponse<ObjectNode> response = esClient.search(searchRequest, ObjectNode.class);

            List<Hit<ObjectNode>> hits = response.hits().hits();
            if (hits == null || hits.isEmpty()) {
                return Collections.emptyList();
            }

            List<CodeChunkHit> results = new ArrayList<>(hits.size());
            for (Hit<ObjectNode> hit : hits) {
                ObjectNode source = hit.source();
                if (source == null) continue;

                double score = hit.score() != null ? hit.score() : 0.0;
                CodeChunkHit chunkHit = new CodeChunkHit(
                        getString(source, "filePath"),
                        getInt(source, "chunkIndex", 0),
                        getString(source, "content"),
                        getString(source, "contentHash"),
                        getString(source, "language"),
                        getInt(source, "startLine", 1),
                        getInt(source, "endLine", 1),
                        (float) score
                );
                results.add(chunkHit);
            }

            List<CodeChunkHit> verifiedResults = filterCurrentDiskVersion(appId, codeGenType, results);
            log.debug("RAG 检索完成 appId={}, queryLen={}, candidates={}, verified={}",
                    appId, userQuery.length(), results.size(), verifiedResults.size());
            return verifiedResults;
        } catch (Exception e) {
            // 4. 异常时降级返回空列表
            log.warn("RAG 检索失败，已降级，appId={}", appId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 只保留与磁盘当前文件 hash 一致的命中。
     */
    private List<CodeChunkHit> filterCurrentDiskVersion(
            Long appId, CodeGenTypeEnum codeGenType, List<CodeChunkHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Path root = ConversationMemoryManifestSupport.resolveProjectRoot(appId, codeGenType)
                .toAbsolutePath().normalize();
        Map<String, String> diskHashes = new HashMap<>();
        List<CodeChunkHit> verified = new ArrayList<>(candidates.size());
        for (CodeChunkHit hit : candidates) {
            if (hit.filePath() == null || hit.filePath().isBlank()
                    || hit.contentHash() == null || hit.contentHash().isBlank()) {
                continue;
            }

            String diskHash = diskHashes.computeIfAbsent(
                    hit.filePath(), ignored -> readCurrentHash(root, hit.filePath()));
            if (hit.contentHash().equals(diskHash)) {
                verified.add(hit);
            }
        }
        return Collections.unmodifiableList(verified);
    }

    private String readCurrentHash(Path root, String filePath) {
        try {
            Path candidate = root.resolve(filePath).normalize();
            if (!candidate.startsWith(root)) {
                return "";
            }
            Path realRoot = root.toRealPath();
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile)) {
                return "";
            }
            return RagFileHash.sha256Prefix(Files.readAllBytes(realFile));
        } catch (Exception e) {
            log.debug("RAG 磁盘文件校验失败, root={}, filePath={}", root, filePath, e);
            return "";
        }
    }

    /**
     * 将检索命中结果格式化为可注入 LLM 上下文的文本块，受字符预算约束。
     *
     * @param hits {@link #searchTopK} 返回的命中列表
     * @return 带 INJECT_TAG 的格式化片段；无命中时返回 null
     */
    public String formatAsInject(List<CodeChunkHit> hits) {
        // 方法大纲：
        // 1. 空列表早退    L115-L117
        // 2. 写入注入头与说明文案    L120-L123
        // 3. 按预算逐条拼接片段，超长时截断后结束    L126-L145

        // 1. 空列表早退
        if (hits == null || hits.isEmpty()) {
            return null;
        }

        // 2. 写入注入头与说明文案
        int budget = properties.getRetrieval().getCharBudget();
        StringBuilder sb = new StringBuilder();
        sb.append(ConversationMemoryInjectTexts.INJECT_TAG).append("\n");
        sb.append("以下为 ES 向量检索到的相关源码片段，已按相关性排序。以磁盘 readFile 为准。\n\n");

        // 3. 按预算逐条拼接片段，超长时截断后结束
        int remaining = budget - sb.length();
        for (CodeChunkHit hit : hits) {
            if (remaining <= 0) break;

            String header = String.format("=== %s (lines %d-%d, score=%.2f) ===",
                    hit.filePath(), hit.startLine(), hit.endLine(), hit.score());
            String entry = header + "\n" + hit.content() + "\n\n";

            if (entry.length() > remaining) {
                int maxContentLen = remaining - header.length() - 10;
                if (maxContentLen > 80) {
                    String truncated = hit.content().substring(0, Math.min(hit.content().length(), maxContentLen));
                    sb.append(header).append("\n").append(truncated).append("\n...(truncated)\n\n");
                }
                break;
            }

            sb.append(entry);
            remaining -= entry.length();
        }

        return sb.toString();
    }

    /**
     * 将 float[] 查询向量转为 ES knn API 所需的 List&lt;Float&gt;。
     *
     * @param arr 嵌入向量数组
     * @return 浮点列表；arr 为 null 时返回空列表
     */
    private static List<Float> toFloatList(float[] arr) {
        if (arr == null) return Collections.emptyList();
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) {
            list.add(v);
        }
        return list;
    }

    /**
     * 从 ES 文档 JSON 节点安全读取字符串字段。
     *
     * @param node  ES 命中文档的 source 节点
     * @param field 字段名
     * @return 字段文本值；缺失时返回空字符串
     */
    private String getString(ObjectNode node, String field) {
        var v = node.get(field);
        return v == null ? "" : v.asText();
    }

    /**
     * 从 ES 文档 JSON 节点安全读取整型字段。
     *
     * @param node  ES 命中文档的 source 节点
     * @param field 字段名
     * @param def   字段缺失或解析失败时的默认值
     * @return 字段整型值
     */
    private int getInt(ObjectNode node, String field, int def) {
        var v = node.get(field);
        return v == null ? def : v.asInt(def);
    }

    /**
     * ES knn 检索单条命中结果，携带文件路径、行号范围与相关性分数。
     *
     * @param filePath   项目内相对路径
     * @param chunkIndex 文件内切块序号
     * @param content    切块源码文本
     * @param language   语言标识
     * @param startLine  起始行号（含）
     * @param endLine    结束行号（含）
     * @param score      ES knn 相关性分数
     */
    public record CodeChunkHit(
            String filePath,
            int chunkIndex,
            String content,
            String contentHash,
            String language,
            int startLine,
            int endLine,
            float score
    ) {
    }
}
