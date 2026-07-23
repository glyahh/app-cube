package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.IndexOperation;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch._types.Refresh;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunker;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagFileHash;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

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

    // 文件锁
    private final ConcurrentHashMap<FileIndexKey, ReentrantLock> fileLocks = new ConcurrentHashMap<>();
    // app锁
    private final ConcurrentHashMap<Long, ReentrantReadWriteLock> appLocks = new ConcurrentHashMap<>();
    // 删除的appId
    private final Set<Long> deletedApps = ConcurrentHashMap.newKeySet();

    /**
     * RAG 单文件索引入口：读取磁盘源码 → 切块 → bulk upsert → 清理多余旧 chunk。
     *
     * @param appId    应用 id，用于 ES 文档按应用隔离
     * @param filePath 项目内相对路径
     * @param root     代码输出根目录（与 filePath 拼接为绝对路径）
     * @param fileNote 预留的文件变更摘要，当前未参与切块，可传 null
     */
    public void indexFile(Long appId, String filePath, Path root, String fileNote) {
        if (!properties.isEnabled()) {
            log.debug("RAG 已禁用，跳过索引 appId={}, filePath={}", appId, filePath);
            return;
        }
        if (appId == null || appId <= 0 || filePath == null || root == null) {
            return;
        }

        if (deletedApps.contains(appId)) {
            return;
        }

        // 拿到每个app的锁,如果没有就按照方法新建一个锁
        Lock appReadLock = appLocks.computeIfAbsent(appId, ignored -> new ReentrantReadWriteLock()).readLock();
        // 锁住!
        appReadLock.lock();

        if (deletedApps.contains(appId)) {
            appReadLock.unlock();
            return;
        }

        FileIndexKey lockKey = new FileIndexKey(appId, filePath);

        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();

        try {
            // 1. 只允许读取项目根目录内的文件
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidatePath = normalizedRoot.resolve(filePath).normalize();
            if (!candidatePath.startsWith(normalizedRoot)) {
                log.warn("拒绝索引越界路径, appId={}, filePath={}", appId, filePath);
                deleteFileChunksInternal(appId, filePath);
                return;
            }
            Path realRoot = normalizedRoot.toRealPath();
            Path fullPath = candidatePath.toRealPath();
            if (!fullPath.startsWith(realRoot) || !Files.isRegularFile(fullPath)) {
                log.warn("拒绝索引链接越界路径, appId={}, filePath={}", appId, filePath);
                deleteFileChunksInternal(appId, filePath);
                return;
            }

            // 2. 空文件、超限文件或无法生成 chunk 时清理旧索引
            byte[] rawBytes = Files.readAllBytes(fullPath);
            String content = new String(rawBytes, StandardCharsets.UTF_8);
            if (content.isBlank()) {
                deleteFileChunksInternal(appId, filePath);
                return;
            }
            String fileHash = RagFileHash.sha256Prefix(rawBytes);

            String lang = detectLang(filePath);
            List<CodeChunker.Chunk> chunks = chunker.chunk(filePath, content, lang);
            if (chunks.isEmpty()) {
                deleteFileChunksInternal(appId, filePath);
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

            // 4. ES 中已有完整且同 hash 的 chunks 时，无需重复生成向量和写入。
            if (isFileIndexCurrent(docs)) {
                log.debug("RAG 索引未变化，跳过 embedding 与 bulk, appId={}, filePath={}, chunks={}",
                        appId, filePath, chunks.size());
                return;
            }

            // 5. 完整写入、清理旧尾块，再校验 ES 中只有本次文件版本
            if (!indexDocuments(docs)) {
                return;
            }
            if (!deleteStaleChunks(appId, filePath, chunks.size()) || !isFileIndexCurrent(docs)) {
                log.warn("RAG 文件索引最终校验失败，清理该文件索引, appId={}, filePath={}", appId, filePath);
                deleteFileChunksInternal(appId, filePath);
                return;
            }
            log.info("RAG 索引完成 appId={}, filePath={}, chunks={}", appId, filePath, chunks.size());
        } catch (Exception e) {
            log.warn("RAG 索引失败，已降级，appId={}, filePath={}", appId, filePath, e);
            deleteFileChunksInternal(appId, filePath);
        } finally {
            lock.unlock();
            appReadLock.unlock();
        }
    }

    /**
     * 校验 ES 中该文件的 chunk 集合是否与当前切块结果完全一致。
     * 查询失败时返回 false，让调用方重新索引，避免误跳过更新。
     */
    boolean isFileIndexCurrent(List<CodeChunkDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return false;
        }

        CodeChunkDocument first = docs.getFirst();
        try {
            // 1. 只查询当前应用和文件的 contentHash，避免把向量和源码拉回应用。
            SearchRequest request = SearchRequest.of(s -> s
                    .index(properties.getElasticsearch().getIndexName())
                    .size(docs.size() + 1)
                    .query(q -> q.bool(b -> b
                            .filter(f -> f.term(t -> t.field("appId").value(first.appId())))
                            .filter(f -> f.term(t -> t.field("filePath").value(first.filePath())))
                    ))
                    .source(sc -> sc.filter(sf -> sf.includes("contentHash")))
            );

            SearchResponse<ObjectNode> response = esClient.search(request, ObjectNode.class);
            if (response == null || response.hits() == null || response.hits().hits() == null) {
                return false;
            }

            // 2. 命中数量必须与当前切块数量一致；多出旧 chunk 或缺少 chunk 都需要重建。
            List<Hit<ObjectNode>> hits = response.hits().hits();
            if (hits.size() != docs.size()) {
                return false;
            }

            // 3. 按确定性文档 ID 建立当前文件的期望 hash，用于校验每个 chunk。
            Map<String, String> expectedHashes = new HashMap<>(docs.size());
            for (CodeChunkDocument doc : docs) {
                expectedHashes.put(doc.docId(), doc.contentHash());
            }

            // 4. 逐个核对文档 ID、去重状态和 contentHash，全部通过才允许跳过索引。
            Set<String> seenIds = new HashSet<>(hits.size());
            for (Hit<ObjectNode> hit : hits) {
                String expectedHash = expectedHashes.get(hit.id());
                ObjectNode source = hit.source();
                if (expectedHash == null || !seenIds.add(hit.id()) || source == null
                        || source.get("contentHash") == null
                        || !expectedHash.equals(source.get("contentHash").asText())) {
                    return false;
                }
            }
            return seenIds.size() == expectedHashes.size();
        } catch (Exception e) {
            log.warn("RAG 索引状态检查失败，将重新索引, appId={}, filePath={}, reason={}",
                    first.appId(), first.filePath(), e.getMessage());
            return false;
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
     * 给外部的 按 appId + filePath 删除 ES 中该文件的全部 chunk 文档（索引前清理或文件删除时调用）。
     *
     * @param appId    应用 id
     * @param filePath 项目内相对路径
     */
    public void deleteFileChunks(Long appId, String filePath) {
        if (appId == null || appId <= 0 || filePath == null) {
            return;
        }

        Lock appReadLock = appLocks.computeIfAbsent(appId, ignored -> new ReentrantReadWriteLock()).readLock();
        appReadLock.lock();
        FileIndexKey lockKey = new FileIndexKey(appId, filePath);
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            deleteFileChunksInternal(appId, filePath);
        } finally {
            lock.unlock();
            appReadLock.unlock();
        }
    }

    /**
     * 删除某个应用的全部 chunk。失败只记录日志，不影响应用删除。
     */
    public void deleteAppChunks(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        Lock appWriteLock = appLocks.computeIfAbsent(appId, ignored -> new ReentrantReadWriteLock()).writeLock();
        appWriteLock.lock();
        deletedApps.add(appId);
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .refresh(true)
                    .query(q -> q.term(t -> t.field("appId").value(appId))));
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (isDeleteByQuerySuccessful(response)) {
                log.debug("RAG 已清理应用 chunks, appId={}", appId);
            } else {
                log.warn("RAG 清理应用 chunks 未完整成功, appId={}", appId);
            }
        } catch (Exception e) {
            log.warn("RAG 清理应用 chunks 失败, appId={}", appId, e);
        } finally {
            appWriteLock.unlock();
        }
    }

    /**
     * 发给ES的删除 Elasticsearch 中指定 appId + filePath 对应的全部 RAG chunk
     * @param appId
     * @param filePath
     * @return
     */
    private boolean deleteFileChunksInternal(Long appId, String filePath) {
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .refresh(true)
                    .query(q -> q.bool(bq -> bq
                            .filter(f -> f.term(t -> t.field("appId").value(appId)))
                            .filter(f -> f.term(t -> t.field("filePath").value(filePath)))
                    )));
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (!isDeleteByQuerySuccessful(response)) {
                log.warn("RAG 清理文件 chunks 未完整成功, appId={}, filePath={}", appId, filePath);
                return false;
            }
            log.debug("RAG 已清理文件 chunks, appId={}, filePath={}", appId, filePath);
            return true;
        } catch (Exception e) {
            log.warn("RAG 清理文件 chunks 失败, appId={}, filePath={}", appId, filePath, e);
            return false;
        }
    }

    /**
     * 删除 chunkIndex &gt;= newChunkCount 的多余旧文档（bulk 成功写入后调用）。
     */
    boolean deleteStaleChunks(Long appId, String filePath, int newChunkCount) {
        if (!properties.isEnabled()) {
            return false;
        }
        try {
            DeleteByQueryRequest request = DeleteByQueryRequest.of(b -> b
                    .index(properties.getElasticsearch().getIndexName())
                    .refresh(true)
                    .query(q -> q.bool(bq -> bq
                            .must(m -> m.term(t -> t.field("appId").value(appId)))
                            .must(m -> m.term(t -> t.field("filePath").value(filePath)))
                            .must(m -> m.range(r -> r.number(nr -> nr.field("chunkIndex").gte((double) newChunkCount))))
                    ))
            );
            DeleteByQueryResponse response = esClient.deleteByQuery(request);
            if (!isDeleteByQuerySuccessful(response)) {
                log.warn("RAG 清理多余旧 chunks 未完整成功, appId={}, filePath={}", appId, filePath);
                return false;
            }
            log.debug("RAG 已清理多余旧 chunks, appId={}, filePath={}, newChunkCount={}", appId, filePath, newChunkCount);
            return true;
        } catch (Exception e) {
            log.warn("RAG 清理多余旧 chunks 失败, appId={}, filePath={}", appId, filePath, e);
            return false;
        }
    }

    /**
     * 为每个 chunk 生成 embedding 并 bulk 写入 ES 索引。
     *
     * @param docs 待索引的代码块文档列表
     * @return bulk 是否全部成功
     */
    private boolean indexDocuments(List<CodeChunkDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return false;
        }
        CodeChunkDocument first = docs.getFirst();
        boolean bulkSubmitted = false;
        try {
            // 1. 所有向量必须先完整生成，禁止提交部分文件版本
            List<String> contents = docs.stream().map(CodeChunkDocument::content).toList();
            float[][] vectors = embeddingService.embedBatch(contents);
            if (vectors == null || vectors.length != docs.size()) {
                log.warn("RAG embedding 数量不完整, appId={}, file={}, expected={}, actual={}",
                        first.appId(), first.filePath(), docs.size(), vectors == null ? 0 : vectors.length);
                return false;
            }
            for (int i = 0; i < docs.size(); i++) {
                if (vectors[i] == null) {
                    log.warn("RAG embedding 失败，整份文件不提交, appId={}, file={}, idx={}",
                            first.appId(), first.filePath(), i);
                    return false;
                }
            }

            // 2. 向量完整后再组装包含全部 chunks 的 bulk
            List<BulkOperation> operations = new ArrayList<>(docs.size());
            for (int i = 0; i < docs.size(); i++) {
                CodeChunkDocument doc = docs.get(i);
                ObjectNode docNode = objectMapper.valueToTree(doc);
                ArrayNode embeddingArray = docNode.putArray("embedding");
                for (float v : vectors[i]) {
                    embeddingArray.add(v);
                }

                IndexOperation<ObjectNode> indexOp = IndexOperation.of(io -> io
                        .index(properties.getElasticsearch().getIndexName())
                        .id(doc.docId())
                        .document(docNode)
                );
                operations.add(BulkOperation.of(b -> b.index(indexOp)));
            }

            // 3. 刷新后才能执行最终版本校验
            BulkRequest bulkRequest = BulkRequest.of(b -> b
                    .operations(operations)
                    .refresh(Refresh.WaitFor));
            bulkSubmitted = true;
            BulkResponse response = esClient.bulk(bulkRequest);
            if (response == null || response.errors()) {
                log.warn("RAG bulk 部分失败，清理该文件可能写入的文档, appId={}, file={}",
                        first.appId(), first.filePath());
                deleteFileChunksInternal(first.appId(), first.filePath());
                return false;
            }
            return true;
        } catch (Exception e) {
            if (bulkSubmitted) {
                deleteFileChunksInternal(first.appId(), first.filePath());
            }
            log.warn("RAG bulk 写入失败，已降级, appId={}, file={}", first.appId(), first.filePath(), e);
            return false;
        }
    }

    private boolean isDeleteByQuerySuccessful(DeleteByQueryResponse response) {
        return response != null
                && !response.timedOut()
                && (response.failures() == null || response.failures().isEmpty());
    }

    /**
     * 根据文件扩展名推断语言标识，供切块器选择切分策略。
     *
     * @param filePath 项目内相对路径
     * @return 语言标识（如 java、vue、typescript）；未知扩展名返回 text
     */
    static String detectLang(String filePath) {
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

    private record FileIndexKey(Long appId, String filePath) {
    }
}
