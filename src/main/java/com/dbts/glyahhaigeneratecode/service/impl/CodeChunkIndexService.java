package com.dbts.glyahhaigeneratecode.service.impl;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunkDocument;
import com.dbts.glyahhaigeneratecode.core.rag.CodeChunker;
import com.dbts.glyahhaigeneratecode.core.rag.EmbeddingService;
import com.dbts.glyahhaigeneratecode.core.rag.RagProperties;
import com.dbts.glyahhaigeneratecode.core.rag.file.CodeChunkDocumentAssembler;
import com.dbts.glyahhaigeneratecode.core.rag.file.CodeLanguageDetector;
import com.dbts.glyahhaigeneratecode.core.rag.file.RagSourceFileReader;
import com.dbts.glyahhaigeneratecode.core.rag.index.CodeChunkVectorizer;
import com.dbts.glyahhaigeneratecode.core.rag.index.RagIndexConcurrencyCoordinator;
import com.dbts.glyahhaigeneratecode.core.rag.model.EmbeddedCodeChunk;
import com.dbts.glyahhaigeneratecode.core.rag.model.RagSourceFile;
import com.dbts.glyahhaigeneratecode.core.rag.repository.CodeChunkIndexRepository;
import com.dbts.glyahhaigeneratecode.core.rag.repository.ElasticsearchCodeChunkIndexRepository;
import com.dbts.glyahhaigeneratecode.service.support.AppExistenceChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * 代码块索引门面，编排文件读取、切块、向量化和 Elasticsearch 版本替换。
 * <p>
 * 文件版本只有在全部 chunk 向量化、批量写入、旧尾块清理和最终一致性校验均成功后才保留；任一阶段
 * 失败均按文件维度清理，避免检索到混合版本。
 * </p>
 * <p>
 * 应用主表是“应用是否仍存在”的权威来源。索引在进入锁队列前和获得应用读锁后各检查一次，从而拒绝
 * 删除后到达的异步任务，并封闭排队期间应用被删除的竞争窗口。
 * </p>
 */
@Slf4j
@Service
public class CodeChunkIndexService {

    private static final CodeLanguageDetector LANGUAGE_DETECTOR = new CodeLanguageDetector();

    private final RagProperties properties;
    private final RagSourceFileReader sourceFileReader;
    private final CodeChunkDocumentAssembler documentAssembler;
    private final CodeChunkVectorizer vectorizer;
    private final CodeChunkIndexRepository repository;
    private final RagIndexConcurrencyCoordinator concurrencyCoordinator;
    private final AppExistenceChecker appExistenceChecker;

    /**
     * Spring 生产构造器，必须注入数据库实现的 {@link AppExistenceChecker}。
     */
    @Autowired
    public CodeChunkIndexService(RagProperties properties,
                                 RagSourceFileReader sourceFileReader,
                                 CodeChunkDocumentAssembler documentAssembler,
                                 CodeChunkVectorizer vectorizer,
                                 CodeChunkIndexRepository repository,
                                 RagIndexConcurrencyCoordinator concurrencyCoordinator,
                                 AppExistenceChecker appExistenceChecker) {
        this.properties = properties;
        this.sourceFileReader = sourceFileReader;
        this.documentAssembler = documentAssembler;
        this.vectorizer = vectorizer;
        this.repository = repository;
        this.concurrencyCoordinator = concurrencyCoordinator;
        this.appExistenceChecker = appExistenceChecker;
    }

    /**
     * 兼容旧直接调用方的构造器。
     * <p>默认 checker 允许索引，不具备删除后迟到任务防护；生产环境应使用 Spring 完整构造器。</p>
     */
    public CodeChunkIndexService(RagProperties properties,
                                 RagSourceFileReader sourceFileReader,
                                 CodeChunkDocumentAssembler documentAssembler,
                                 CodeChunkVectorizer vectorizer,
                                 CodeChunkIndexRepository repository,
                                 RagIndexConcurrencyCoordinator concurrencyCoordinator) {
        this(properties, sourceFileReader, documentAssembler, vectorizer, repository,
                concurrencyCoordinator, ignored -> true);
    }

    /**
     * 保留第一阶段使用的构造方式，兼容现有测试和调用方。
     */
    public CodeChunkIndexService(ElasticsearchClient esClient,
                                 EmbeddingService embeddingService,
                                 RagProperties properties,
                                 ObjectMapper objectMapper,
                                 RagSourceFileReader sourceFileReader,
                                 CodeChunkDocumentAssembler documentAssembler) {
        this(properties, sourceFileReader, documentAssembler,
                new CodeChunkVectorizer(embeddingService),
                new ElasticsearchCodeChunkIndexRepository(esClient, properties, objectMapper),
                new RagIndexConcurrencyCoordinator(),
                ignored -> true);
    }

    /**
     * 支持显式注入存在性策略的兼容构造器，主要用于隔离测试和非 Spring 集成。
     */
    public CodeChunkIndexService(ElasticsearchClient esClient,
                                 EmbeddingService embeddingService,
                                 RagProperties properties,
                                 ObjectMapper objectMapper,
                                 RagSourceFileReader sourceFileReader,
                                 CodeChunkDocumentAssembler documentAssembler,
                                 AppExistenceChecker appExistenceChecker) {
        this(properties, sourceFileReader, documentAssembler,
                new CodeChunkVectorizer(embeddingService),
                new ElasticsearchCodeChunkIndexRepository(esClient, properties, objectMapper),
                new RagIndexConcurrencyCoordinator(), appExistenceChecker);
    }

    /**
     * 保留原有五参数构造方式，兼容现有调用方。
     */
    public CodeChunkIndexService(ElasticsearchClient esClient,
                                 EmbeddingService embeddingService,
                                 CodeChunker chunker,
                                 RagProperties properties,
                                 ObjectMapper objectMapper) {
        this(esClient, embeddingService, properties, objectMapper,
                new RagSourceFileReader(),
                new CodeChunkDocumentAssembler(chunker, LANGUAGE_DETECTOR));
    }

    /**
     * 索引单个项目文件。文件版本只有完整向量化并通过最终校验后才会保留。
     * <p>存在性检查异常采用 fail-closed 策略：跳过索引，避免不确定状态下重新写入已删除应用。</p>
     *
     * @param appId    应用 id
     * @param filePath 项目内相对路径
     * @param root     代码输出根目录
     * @param fileNote 预留的文件变更摘要，当前未参与切块
     */
    public void indexFile(Long appId, String filePath, Path root, String fileNote) {
        // 1. 校验 RAG 开关和入口参数。
        if (!properties.isEnabled()) {
            log.debug("RAG 已禁用，跳过索引 appId={}, filePath={}", appId, filePath);
            return;
        }
        if (appId == null || appId <= 0 || filePath == null || root == null) {
            return;
        }

        // 2. 第一次检查快速拒绝已删除应用，减少无意义的锁竞争和 embedding 成本。
        if (!appExists(appId)) {
            log.debug("应用已不存在，跳过 RAG 索引，appId={}, filePath={}", appId, filePath);
            return;
        }

        // 3. 第二次检查位于应用读锁内，封闭首次检查与获得锁之间的删除竞争窗口。
        concurrencyCoordinator.executeIndex(appId, filePath, () -> {
            if (!appExists(appId)) {
                log.debug("应用在等待索引锁期间已删除，跳过 RAG 索引，appId={}, filePath={}", appId, filePath);
                return;
            }
            indexFileWithinLock(appId, filePath, root);
        });
    }

    private boolean appExists(Long appId) {
        try {
            return appExistenceChecker.exists(appId);
        } catch (Exception e) {
            log.warn("检查应用存在性失败，跳过 RAG 索引，appId={}", appId, e);
            return false;
        }
    }

    private void indexFileWithinLock(Long appId, String filePath, Path root) {
        try {
            // 1. 读取经过项目目录边界校验的文件快照。
            RagSourceFile sourceFile = sourceFileReader.read(root, filePath).orElse(null);
            if (sourceFile == null) {
                log.warn("拒绝索引无效或越界文件, appId={}, filePath={}", appId, filePath);
                repository.deleteFileChunks(appId, filePath);
                return;
            }

            // 2. 空文件、超限文件或无法生成 chunk 时清理旧索引。
            if (sourceFile.content().isBlank()) {
                repository.deleteFileChunks(appId, filePath);
                return;
            }
            List<CodeChunkDocument> documents = documentAssembler.assemble(appId, sourceFile);
            if (documents.isEmpty()) {
                repository.deleteFileChunks(appId, filePath);
                return;
            }

            // 3. 当前版本完整存在时跳过向量化和写入。
            if (repository.isFileIndexCurrent(documents)) {
                log.debug("RAG 索引未变化，跳过 embedding 与 bulk, appId={}, filePath={}, chunks={}",
                        appId, filePath, documents.size());
                return;
            }

            // 4. 全部向量生成成功后才允许仓储提交 bulk，禁止写入部分文件版本。
            List<EmbeddedCodeChunk> chunks = vectorizer.vectorize(documents);
            if (chunks.size() != documents.size() || !repository.writeAllOrCleanup(chunks)) {
                return;
            }

            // 5. 清理旧尾块并回读校验，确保最终只保留本次完整文件版本。
            if (!repository.deleteStaleChunks(appId, filePath, documents.size())
                    || !repository.isFileIndexCurrent(documents)) {
                log.warn("RAG 文件索引最终校验失败，清理该文件索引, appId={}, filePath={}", appId, filePath);
                repository.deleteFileChunks(appId, filePath);
                return;
            }
            log.info("RAG 索引完成 appId={}, filePath={}, chunks={}", appId, filePath, documents.size());
        } catch (Exception e) {
            log.warn("RAG 索引失败，已降级，appId={}, filePath={}", appId, filePath, e);
            repository.deleteFileChunks(appId, filePath);
        }
    }

    /** 批量索引多个文件，逐文件委托 {@link #indexFile} 处理。 */
    public void indexFiles(Long appId, List<String> filePaths, Path root) {
        // 1. 校验 RAG 开关和文件列表。
        if (!properties.isEnabled() || filePaths == null || filePaths.isEmpty()) {
            return;
        }

        // 2. 逐文件执行完整索引流程。
        for (String filePath : filePaths) {
            indexFile(appId, filePath, root, null);
        }
    }

    /**
     * 删除指定文件的全部代码块，与同文件索引共用独占锁。
     */
    public void deleteFileChunks(Long appId, String filePath) {
        if (appId == null || appId <= 0 || filePath == null) {
            return;
        }
        concurrencyCoordinator.executeFileDeletion(appId, filePath,
                () -> repository.deleteFileChunks(appId, filePath));
    }

    /**
     * 删除应用的全部代码块。
     * <p>应用写锁会等待在途文件索引结束；仓储异常由上层应用删除流程记录并降级。</p>
     */
    public void deleteAppChunks(Long appId) {
        if (appId == null || appId <= 0) {
            return;
        }
        concurrencyCoordinator.executeAppDeletion(appId, () -> repository.deleteAppChunks(appId));
    }

    /** 根据文件扩展名推断语言标识。 */
    static String detectLang(String filePath) {
        return LANGUAGE_DETECTOR.detect(filePath);
    }
}
