# RAG V5 Grep/Glob 检索更新计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. 每个任务完成后运行对应测试，再进入下一个任务。

**目标：** 在现有 RAG 旁边增加一条“Glob 定位文件、Grep 精确检索、现有混合检索兜底”的新版本检索链路，优先返回当前磁盘上的准确代码，不修改旧版本检索逻辑。

**架构：** 第一期新增 `Grep/Glob` 检索器作为独立实现，由新的编排器根据查询内容先定位文件和符号；精确候选不足时，直接调用现有 `HybridCodeChunkSearchStrategy` 兜底。第二期在第一期稳定后增加符号声明、引用和调用点扩展。所有候选最终都从磁盘重新读取并校验 hash；旧的混合检索、注入入口和索引流程保持不变，两期能力分别使用独立功能开关。

**技术栈：** Java 21、Spring Boot 3.5.10、`java.nio.file.Files`、正则表达式、现有 Elasticsearch BM25/KNN、JUnit 5、Spring Boot Test。

## 全局约束

- 新代码放在 `com.dbts.glyahhaigeneratecode.core.rag.retrieval` 及其子包中。
- 不删除、不重写旧的 `HybridCodeChunkSearchStrategy`、`MemoryRagInjectSupport`、`RagIndexConcurrencyCoordinator` 和旧 ES 索引流程。
- 文件遍历必须限制在当前应用的代码输出目录，禁止根据用户输入直接访问任意绝对路径。
- Glob 只负责文件名/路径定位，Grep 只负责文本或正则匹配；两者都不能替代最终的磁盘 hash 校验。
- 默认顺序为 `Glob -> Grep -> 现有 BM25+KNN 混合检索`，只有 Grep 候选不足才调用旧混合检索。
- 新链路失败时返回空候选并记录结构化日志，不改变旧链路行为。
- 默认每次最多返回 5 个代码块；Glob 文件数、Grep 文件数、候选总字数都必须有上限。

## 新链路数据流

```text
用户问题
  -> RagQueryAnalyzer 提取路径/文件名/类名/方法名/错误信息/普通词
  -> GlobFileSearcher 定位候选文件
  -> GrepContentSearcher 在候选文件中做字面量或正则匹配
  -> [第二期，可选] SymbolContextExpander 扩展声明、import、调用点
  -> 候选足够? --是--> 去重、重排、磁盘 hash 校验
                 --否--> 调用现有 BM25+KNN 混合检索
  -> RagInjectFormatter 生成上下文
```

## 第一期：Grep/Glob 精确检索

第一期必须能够独立上线和回滚。符号关系扩展不作为第一期验收条件，也不能阻塞第一期开关启用。

### Task 1: 新链路配置与开关

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/RagProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/RagPropertiesTest.java`

**Interfaces:**
- 新增配置：`retrieval.v5-enabled`、`retrieval.glob-max-files`、`retrieval.grep-max-files`、`retrieval.grep-max-matches`、`retrieval.min-exact-results`、`retrieval.hybrid-fallback-enabled`。
- `RagProperties` 提供类型安全 getter，默认值分别为 `false`、`50`、`50`、`200`、`5`、`true`。

- [ ] **Step 1: 写配置解析测试**

```java
@Test
void v5DefaultsAreBound() {
    assertThat(properties.getRetrieval().isV5Enabled()).isFalse();
    assertThat(properties.getRetrieval().getGlobMaxFiles()).isEqualTo(50);
    assertThat(properties.getRetrieval().getMinExactResults()).isEqualTo(5);
}
```

- [ ] **Step 2: 运行测试确认当前没有新配置**

Run: `./mvnw.cmd -q -Dtest=RagPropertiesTest test`
Expected: 新增断言失败，直到属性和默认值实现完成。

- [ ] **Step 3: 添加配置字段和 YAML 默认值**

配置必须位于现有 `rag.retrieval` 配置树下，不新增第二套 RAG 配置前缀。

- [ ] **Step 4: 重新运行测试**

Run: `./mvnw.cmd -q -Dtest=RagPropertiesTest test`
Expected: PASS。

### Task 2: 查询解析器

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagQuery.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagQueryAnalyzer.java`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagQueryAnalyzerTest.java`

**Interfaces:**
- `RagQueryAnalyzer#analyze(String userMessage)` 返回 `RagQuery`。
- `RagQuery` 至少包含：`rawText`、`globPatterns`、`literalTerms`、`symbolTerms`、`errorTerms`、`isNaturalLanguage`。
- 出现路径分隔符、`.java`、`.ts`、`.vue` 等特征时生成 Glob pattern；识别 `ClassName`、`methodName(`、`foo.bar` 等符号词；错误码、异常类名和错误短语放入 `errorTerms`。
- 解析器只做字符串处理，不读取磁盘、不访问 ES，并去重、去掉空白和长度小于 2 的普通词。

- [ ] **Step 1: 编写路径、符号和错误信息测试**

```java
@Test
void extractsPathSymbolsAndErrorText() {
    RagQuery query = analyzer.analyze("修复 src/main/UserService.java 的 findById()，异常 NullPointerException");
    assertThat(query.globPatterns()).contains("**/UserService.java");
    assertThat(query.symbolTerms()).contains("findById", "UserService");
    assertThat(query.errorTerms()).contains("NullPointerException");
}
```

- [ ] **Step 2: 运行 `./mvnw.cmd -q -Dtest=RagQueryAnalyzerTest test` 确认失败**
- [ ] **Step 3: 实现解析逻辑**
- [ ] **Step 4: 运行同一命令确认 PASS**

### Task 3: Glob 文件定位器

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GlobFileSearcher.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GlobMatch.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GlobSearchResult.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagProjectRootResolver.java`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GlobFileSearcherTest.java`

**Interfaces:**
- `GlobFileSearcher#search(Long appId, CodeGenTypeEnum codeGenType, List<String> patterns)` 返回 `GlobSearchResult`，其中包含按路径排序的 `matches` 和 `truncated`。
- `RagProjectRootResolver#resolve(Long appId, CodeGenTypeEnum codeGenType)` 根据现有 `temp/code_output/{type}_{appId}` 和 `vue_project_{appId}` 规则返回规范化项目根目录。
- `GlobFileSearcher` 只接受解析器返回的项目根目录，不能接受用户提供的 root；文件读取继续交给现有 `RagSourceFileReader` 做越界和真实路径校验。
- 忽略 `node_modules`、`.git`、`dist`、`target`、`temp`、`coverage`、`.idea`、`.vscode`。
- 使用 `PathMatcher("glob:" + pattern)`，超过配置上限时停止遍历并记录 `truncated=true`。
- 查询未提供路径时，根据 `CodeGenTypeEnum` 生成受支持源码扩展名的默认 Glob patterns，不能因为 patterns 为空而跳过 Grep。

- [ ] **Step 1: 编写测试**

覆盖 `**/UserService.java` 命中、忽略 `target/UserService.java`、超过上限截断、目录不存在返回空列表。

- [ ] **Step 2: 运行 `./mvnw.cmd -q -Dtest=GlobFileSearcherTest test` 确认失败**
- [ ] **Step 3: 用 `Files.walkFileTree` 实现受限遍历**
- [ ] **Step 4: 运行同一命令确认 PASS**

### Task 4: Grep 内容匹配器

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GrepContentSearcher.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GrepMatch.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GrepSearchResult.java`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GrepContentSearcherTest.java`

**Interfaces:**
- `GrepContentSearcher#search(Long appId, CodeGenTypeEnum codeGenType, GlobSearchResult files, RagQuery query)` 返回 `GrepSearchResult`。
- `GrepSearchResult` 包含 `matches`、由命中行转换出的 `RagCodeChunkCandidate` 列表和 `truncated`。
- 每个命中包含 `filePath`、`lineNumber`、`lineText`、`matchedTerm`、`score`。
- 默认按字面量匹配；只有查询明确标记正则时才使用 `Pattern`，非法正则降级为字面量。
- 使用 UTF-8 逐行读取；二进制或无法解码文件跳过；单文件和总匹配数受配置限制。
- 使用现有 `RagSourceFileReader` 读取文件快照，使用现有 `CodeChunker` 切块，并选择覆盖命中行的 chunk；`contentHash` 使用该磁盘快照的 hash。

- [ ] **Step 1: 编写大小写、行号、非法正则和上限测试**
- [ ] **Step 2: 运行 `./mvnw.cmd -q -Dtest=GrepContentSearcherTest test` 确认失败**
- [ ] **Step 3: 实现逐文件逐行匹配，只保留命中行及受控上下文窗口**
- [ ] **Step 4: 运行同一命令确认 PASS**

### Task 5: 候选合并与磁盘最终校验

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagCandidateMerger.java`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagCandidateMergerTest.java`

**Interfaces:**
- `RagCandidateMerger#merge(List<RagCodeChunkCandidate> exactCandidates, List<RagCodeChunkCandidate> fallbackCandidates, int topK)` 返回去重后的 `List<RagCodeChunkCandidate>`。
- 去重键为 `filePath + chunkIndex`；优先级为 Grep 精确命中 > 现有混合检索结果。
- 同一文件相邻 chunk 可合并为一个上下文窗口，但必须保留路径、起止行号和 hash。
- 编排器在合并后调用现有 `CurrentDiskChunkFilter`，磁盘 hash 不一致的旧 ES 候选必须丢弃；本任务不修改该旧类。

- [ ] **Step 1: 编写优先级、去重和过期 hash 测试**
- [ ] **Step 2: 运行 `./mvnw.cmd -q -Dtest=RagCandidateMergerTest test` 确认失败**
- [ ] **Step 3: 实现稳定排序和去重**
- [ ] **Step 4: 运行同一命令确认 PASS**

### Task 6: 新版检索编排器

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GrepGlobRagRetrievalOrchestrator.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagRetrievalResult.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/CodeChunkRetrievalService.java`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/service/impl/GrepGlobRagRetrievalOrchestratorTest.java`

**Interfaces:**
- `GrepGlobRagRetrievalOrchestrator#retrieve(Long appId, String userMessage, CodeGenTypeEnum codeGenType)` 返回 `RagRetrievalResult`，包含 `hits`、`stages`、`truncated`、`fallbackUsed`。
- 调度顺序：先 Glob/Grep；结果不少于 `min-exact-results` 时不调用旧混合检索；不足时调用现有 `CodeChunkSearchStrategy#search(...)`；最后合并、去重、磁盘校验并截断到 `topK`。
- `CodeChunkRetrievalService` 增加 `searchTopKV5(...)`；旧 `searchTopK(...)` 保持不变，便于灰度对比。

- [ ] **Step 1: 写编排顺序测试**

验证精确命中时旧混合检索 mock 未调用；候选不足时调用一次旧混合检索；Glob/Grep 阶段异常时可降级到旧混合检索。

- [ ] **Step 2: 运行 `./mvnw.cmd -q -Dtest=GrepGlobRagRetrievalOrchestratorTest test` 确认失败**
- [ ] **Step 3: 实现编排器和新服务入口**
- [ ] **Step 4: 运行同一命令确认 PASS**

### Task 7: 功能开关和集成验收

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/CodeChunkRetrievalService.java`
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/RagV5RetrievalIntegrationTest.java`
- Create: `docs/compile/rag-v5-grep-glob-rollout.md`

- [ ] **Step 1: 测试开关关闭时旧链路不变**
- [ ] **Step 2: 用临时目录验证路径/类名/方法名查询优先返回当前磁盘内容**
- [ ] **Step 3: 验证 Grep 命中足够时旧混合检索未调用，自然语言查询不足时才进入旧混合检索**
- [ ] **Step 4: 运行新链路测试集**

Run: `./mvnw.cmd -q -Dtest='*RagV5*,*Grep*,*Glob*,RagQueryAnalyzerTest,RagCandidateMergerTest' test`
Expected: PASS。

- [ ] **Step 5: 运行完整后端测试**

Run: `./mvnw.cmd -q test`
Expected: 现有测试不因新开关默认关闭而改变结果。

## 第二期：符号分析扩展

第二期在第一期完成并取得检索指标后实施。目标不是立刻构建完整 IDE 级语义图，而是用受控的词法分析补齐“定义在哪里、谁引用它、谁调用它”三类上下文；后续再根据误命中率决定是否引入 AST/LSP。

### Task 8: 符号声明、引用和调用点扩展

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/CodeSymbol.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/SymbolRelation.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/SymbolAnalysisResult.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/CodeSymbolAnalyzer.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/LexicalCodeSymbolAnalyzer.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/SymbolContextExpander.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/GrepGlobRagRetrievalOrchestrator.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/core/rag/RagProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/LexicalCodeSymbolAnalyzerTest.java`
- Test: `src/test/java/com/dbts/glyahhaigeneratecode/core/rag/retrieval/v5/symbol/SymbolContextExpanderTest.java`

**Interfaces:**
- `CodeSymbolAnalyzer#analyze(RagSourceFile sourceFile)` 返回 `SymbolAnalysisResult`。
- `CodeSymbol` 包含 `name`、`kind`、`filePath`、`lineNumber`；`kind` 取 `TYPE`、`METHOD`、`FUNCTION`、`VARIABLE`。
- `SymbolRelation` 包含 `symbolName`、`relationType`、`filePath`、`lineNumber`；`relationType` 取 `DECLARATION`、`IMPORT`、`CALL`、`REFERENCE`。
- `SymbolContextExpander#expand(Long appId, CodeGenTypeEnum codeGenType, RagQuery query, List<RagCodeChunkCandidate> exactCandidates)` 返回补充候选。
- 新增配置：`retrieval.symbol-expansion-enabled=false`、`retrieval.symbol-max-terms=3`、`retrieval.symbol-max-related-chunks=10`。
- 词法分析先屏蔽字符串和注释，再按语言规则识别 Java、JavaScript、TypeScript、Vue 中的类型、方法、函数、变量、import 和调用表达式。
- 排序固定为声明 > import > 调用 > 普通引用；只扩展查询中出现或第一期精确候选中命中的符号，不递归扩展第二层关系。
- 分析异常时返回空补充候选，第一期 Grep/Glob 结果和旧混合检索兜底继续工作。

- [ ] **Step 1: 写词法分析测试**

覆盖 Java 类/方法、TypeScript 函数/import、Vue `<script setup>` 变量与调用，并验证注释和字符串中的同名文本不会被当作符号。

- [ ] **Step 2: 运行 `./mvnw.cmd -q -Dtest=LexicalCodeSymbolAnalyzerTest test` 确认失败**
- [ ] **Step 3: 实现语言规则和字符串/注释屏蔽**
- [ ] **Step 4: 运行同一命令确认 PASS**
- [ ] **Step 5: 写扩展排序、数量上限和禁止递归扩展测试**
- [ ] **Step 6: 实现 `SymbolContextExpander` 并接入独立开关**
- [ ] **Step 7: 运行符号扩展测试集**

Run: `./mvnw.cmd -q -Dtest='*Symbol*Test' test`
Expected: PASS，且 `symbol-expansion-enabled=false` 时编排结果与第一期一致。

- [ ] **Step 8: 第二期验收**

给定一个明确方法名，最终上下文至少包含它的声明；存在 import 或调用点时按优先级补充，且补充数量不超过 10 个 chunk。误命中或分析失败不能触发额外的向量调用，也不能覆盖第一期的精确候选。

## 验收标准

1. 明确路径、类名、方法名查询优先由 Glob/Grep 命中。
2. Grep 已得到足够候选时不调用旧混合检索，因此不会额外生成查询向量。
3. 自然语言查询仍能通过现有 BM25+KNN 混合检索找到结果。
4. 磁盘内容变化后，旧 ES 候选不会进入最终上下文。
5. 默认关闭新开关时旧链路行为和测试结果不变。
6. 第二期开关默认关闭；启用后只补充符号上下文，不改变第一期 Grep/Glob 的命中顺序。

## 不在本计划范围内

- 不修复 `ChatMemoryMessageUpdater` 的反射问题。
- 不改 `pendingQueries`、workflow 的 stage/flush 机制。
- 不重构 ES 索引写入、per-chunk hash、旧代码块删除策略。
- 不处理 `RagIndexConcurrencyCoordinator` 的锁回收或多实例分布式锁。
