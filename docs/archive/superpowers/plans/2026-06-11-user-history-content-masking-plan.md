# 普通用户历史记录 AI 输出屏蔽 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 普通用户在 AppChatView 中查看历史时，AI 消息默认显示摘要 + 自然语言正文，工具调用详情通过「显示工具」按钮按需加载

**Architecture:** 后端新增 `core/summary/AiMessageSummaryService` 解析 AI 消息原文生成摘要，Caffeine 缓存消峰；普通用户的历史列表 AI 消息的 `message` 字段替换为摘要文本，点击「显示工具」时通过新端点按需加载工具调用详情。管理员行为不变。

**Tech Stack:** Spring Boot 3.5.10, Java 21, MyBatis-Flex, Vue 3 + TypeScript, Ant Design Vue 4

---

## 文件结构

### 后端新建

| 文件 | 职责 |
|------|------|
| `src/main/java/.../core/summary/SummaryResult.java` | AI 消息解析结果模型 |
| `src/main/java/.../core/summary/ToolCallInfo.java` | 工具调用信息模型 |
| `src/main/java/.../core/summary/AiMessageSummaryService.java` | 摘要计算核心服务（解析 + 构建摘要 + 提取 NL/工具 + 缓存管理） |
| `src/main/java/.../model/VO/AiToolCallDetailVO.java` | 工具调用详情 VO |
| `src/main/java/.../model/VO/AiMessageContentVO.java` | 纯语言正文 VO |
| `src/test/java/.../core/summary/AiMessageSummaryServiceTest.java` | 摘要服务单元测试 |

### 后端修改

| 文件 | 修改内容 |
|------|----------|
| `controller/ChatHistoryController.java` | 新增 `GET /{id}/content` 和 `GET /{id}/tools` 端点；修改 `listChatHistory` 调用摘要服务 |
| `service/ChatHistoryService.java` | 新增 `markHistoryForUserRole` 接口方法声明 |
| `service/impl/ChatHistoryServiceImpl.java` | 实现角色感知的历史列表处理 |

### 前端新建

| 文件 | 职责 |
|------|------|
| `ai-generate-code-frontend/src/components/AiHistoryMessage.vue` | AI 消息展示组件（摘要行 + NL + 显示工具按钮） |
| `ai-generate-code-frontend/src/components/HistoryToolbar.vue` | 顶部工具栏（重置/全部收起/全部展开工具） |

### 前端修改

| 文件 | 修改内容 |
|------|----------|
| `ai-generate-code-frontend/src/page/App/AppChatView.vue` | AI 消息渲染区替换为 AiHistoryMessage 组件 |
| `ai-generate-code-frontend/src/api/chatHistoryController.ts` | 新增 getAiMessageContent / getAiMessageTools API |
| `ai-generate-code-frontend/src/api/types.ts` | 新增 AiToolCallDetailVO 类型 |

---

### Task 1: SummaryResult + ToolCallInfo 数据模型

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/summary/SummaryResult.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/summary/ToolCallInfo.java`

- [ ] **Step 1: 创建 SummaryResult.java**

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * AI 消息解析结果：工具调用统计 + 自然语言片段。
 * 由 {@link AiMessageSummaryService#parseAiMessage(String)} 返回，
 * 供 {@link AiMessageSummaryService#buildUserSummary(SummaryResult)} 生成用户可见的摘要文本。
 */
@Data
@Builder
public class SummaryResult {

    /** 工具调用总次数（含写入/修改/删除/读取目录等全部工具） */
    private int totalToolCalls;

    /** 写入文件次数 */
    private int writeFileCount;

    /** 修改文件次数 */
    private int modifyFileCount;

    /** 删除文件次数 */
    private int deleteFileCount;

    /** 读取目录次数 */
    private int dirReadCount;

    /** 按行拆分后的自然语言文本片段列表（去除了工具调用行和代码块） */
    private List<String> naturalLanguageChunks;

    /** 估算耗时秒数（从消息文本中提取，可能为 null） */
    private Integer estimatedElapsedSeconds;
}
```

- [ ] **Step 2: 创建 ToolCallInfo.java**

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import lombok.Builder;
import lombok.Data;

/**
 * 单次工具调用的解析结果。
 * 由 {@link AiMessageSummaryService#extractToolCalls(String)} 返回列表。
 */
@Data
@Builder
public class ToolCallInfo {

    /** 工具名称，如 FileWriteTool、FileModifyTool、FileDeleteTool、FileDirReadTool */
    private String toolName;

    /** 操作描述，如「写入文件」「修改文件」「删除文件」「读取目录」 */
    private String action;

    /** 操作的文件路径，如 index.html、style.css */
    private String filePath;

    /** 执行状态（当前仅占位，后续可扩展） */
    private String status;
}
```

- [ ] **Step 3: 创建 AiToolCallDetailVO.java**

```java
package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.Data;

/**
 * 工具调用详情 VO，供 {@code GET /chatHistory/{historyId}/tools} 返回。
 */
@Data
public class AiToolCallDetailVO {
    private String toolName;
    private String action;
    private String filePath;
}
```

- [ ] **Step 4: 创建 AiMessageContentVO.java**

```java
package com.dbts.glyahhaigeneratecode.model.VO;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * AI 自然语言正文 VO，供 {@code GET /chatHistory/{historyId}/content} 返回。
 */
@Data
@AllArgsConstructor
public class AiMessageContentVO {
    private String content;
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/core/summary/SummaryResult.java
git add src/main/java/com/dbts/glyahhaigeneratecode/core/summary/ToolCallInfo.java
git add src/main/java/com/dbts/glyahhaigeneratecode/model/VO/AiToolCallDetailVO.java
git add src/main/java/com/dbts/glyahhaigeneratecode/model/VO/AiMessageContentVO.java
git commit -m "feat: add summary data models for AI message masking"
```

---

### Task 2: AiMessageSummaryService 核心实现

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/summary/AiMessageSummaryService.java`

- [ ] **Step 1: 实现 parseAiMessage 方法**

核心逻辑：按行扫描，用正则匹配工具调用行和自然语言行。

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import cn.hutool.cache.Cache;
import cn.hutool.cache.CacheUtil;
import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 消息摘要服务：解析原始 AI 消息，生成用户可见的摘要文本，
 * 提取自然语言正文和工具调用结构体。
 *
 * <p>解析策略：按行扫描，分别识别工具选择行、工具执行行、自然语言行和代码块。
 * 解析结果通过 Caffeine 缓存消峰，key = "ai:msg:summary:{appId}"，TTL 5 分钟。</p>
 */
@Slf4j
@Service
public class AiMessageSummaryService {

    // ============ 正则模式 ============

    /** 工具选择：{@code [选择工具] FileWriteTool} */
    private static final Pattern TOOL_SELECT_PATTERN =
            Pattern.compile("^\\[选择工具]\\s*(.+)$");

    /** 工具执行 - 写入文件：{@code [工具调用] 写入文件 index.html} */
    private static final Pattern TOOL_WRITE_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*写入文件\\s*(.+)$");

    /** 工具执行 - 修改文件：{@code [工具调用] 修改文件 style.css} */
    private static final Pattern TOOL_MODIFY_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*修改文件\\s*(.+)$");

    /** 工具执行 - 删除文件：{@code [工具调用] 删除文件 old.txt} */
    private static final Pattern TOOL_DELETE_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*删除文件\\s*(.+)$");

    /** 工具执行 - 读取目录：{@code [工具调用] 读取目录 src/} */
    private static final Pattern TOOL_DIR_READ_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*读取目录\\s*(.+)$");

    /** 带操作的工具行兜底：提取工具名称 */
    private static final Pattern TOOL_EXEC_GENERIC_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*(.+)$");

    // ============ 缓存 ============

    /** 摘要缓存：appId → { historyId → SummaryCacheEntry } */
    private final Cache<Long, Map<Long, SummaryCacheEntry>> summaryCache =
            CacheUtil.newTimedCache(5 * 60 * 1000); // 5 分钟 TTL

    /**
     * 缓存条目：一条 AI 消息的解析结果。
     * 首次 {@link #parseAiMessage(String)} 后写入，
     * 由 {@link #invalidateCache(Long)} 在新 AI 消息写入时失效。
     */
    public record SummaryCacheEntry(
            String summaryText,
            String naturalLanguage,
            List<ToolCallInfo> toolCalls
    ) {}

    /**
     * 解析 AI 消息原文，生成 {@link SummaryResult}。
     *
     * @param message AI 消息原文
     * @return 解析结果，不会为 null
     */
    public SummaryResult parseAiMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return SummaryResult.builder()
                    .totalToolCalls(0)
                    .naturalLanguageChunks(List.of())
                    .build();
        }

        int writeCount = 0, modifyCount = 0, deleteCount = 0, dirReadCount = 0;
        List<String> nlChunks = new ArrayList<>();

        String[] lines = message.split("\n");
        boolean insideCodeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            // 跳过代码块
            if (trimmed.startsWith("```")) {
                insideCodeBlock = !insideCodeBlock;
                continue;
            }
            if (insideCodeBlock) {
                continue;
            }

            // 工具选择行
            if (TOOL_SELECT_PATTERN.matcher(trimmed).find()) {
                continue;
            }

            // 工具执行行 - 按操作类型分类计数
            if (TOOL_WRITE_PATTERN.matcher(trimmed).find()) {
                writeCount++;
                continue;
            }
            if (TOOL_MODIFY_PATTERN.matcher(trimmed).find()) {
                modifyCount++;
                continue;
            }
            if (TOOL_DELETE_PATTERN.matcher(trimmed).find()) {
                deleteCount++;
                continue;
            }
            if (TOOL_DIR_READ_PATTERN.matcher(trimmed).find()) {
                dirReadCount++;
                continue;
            }
            if (TOOL_EXEC_GENERIC_PATTERN.matcher(trimmed).find()) {
                // 其他工具调用（如普通工具执行标记）
                continue;
            }

            // 自然语言行：非空且非工具行
            if (StrUtil.isNotBlank(trimmed)) {
                nlChunks.add(trimmed);
            }
        }

        int total = writeCount + modifyCount + deleteCount + dirReadCount;

        return SummaryResult.builder()
                .totalToolCalls(total)
                .writeFileCount(writeCount)
                .modifyFileCount(modifyCount)
                .deleteFileCount(deleteCount)
                .dirReadCount(dirReadCount)
                .naturalLanguageChunks(nlChunks)
                .build();
    }

    /**
     * 从 {@link SummaryResult} 生成用户可见的摘要文本。
     * 格式：「[AI回复已完成] 共调用 N 次工具（写入 X, 修改 Y）」
     *
     * @param result 解析结果
     * @return 摘要文本
     */
    public String buildUserSummary(SummaryResult result) {
        if (result == null || result.getTotalToolCalls() == 0) {
            return "[AI回复已完成] 共调用 0 次工具";
        }

        StringBuilder sb = new StringBuilder("[AI回复已完成] 共调用 ");
        sb.append(result.getTotalToolCalls()).append(" 次工具（");
        boolean hasPrev = false;
        if (result.getWriteFileCount() > 0) {
            sb.append("写入 ").append(result.getWriteFileCount());
            hasPrev = true;
        }
        if (result.getModifyFileCount() > 0) {
            if (hasPrev) sb.append("，");
            sb.append("修改 ").append(result.getModifyFileCount());
            hasPrev = true;
        }
        if (result.getDeleteFileCount() > 0) {
            if (hasPrev) sb.append("，");
            sb.append("删除 ").append(result.getDeleteFileCount());
            hasPrev = true;
        }
        if (result.getDirReadCount() > 0) {
            if (hasPrev) sb.append("，");
            sb.append("读取目录 ").append(result.getDirReadCount());
        }
        sb.append("）");
        return sb.toString();
    }

    /**
     * 从 AI 原始消息中提取自然语言正文（去除工具调用行和代码块）。
     *
     * @param message AI 原始消息
     * @return 纯自然语言正文；为空时返回 "" 而非 null
     */
    public String extractNaturalLanguage(String message) {
        if (StrUtil.isBlank(message)) {
            return "";
        }
        SummaryResult result = parseAiMessage(message);
        List<String> chunks = result.getNaturalLanguageChunks();
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        return String.join("\n", chunks);
    }

    /**
     * 从 AI 原始消息中提取工具调用结构化列表。
     *
     * @param message AI 原始消息
     * @return 工具调用信息列表；无工具调用时返回空列表
     */
    public List<ToolCallInfo> extractToolCalls(String message) {
        if (StrUtil.isBlank(message)) {
            return List.of();
        }

        List<ToolCallInfo> tools = new ArrayList<>();
        String[] lines = message.split("\n");
        boolean insideCodeBlock = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                insideCodeBlock = !insideCodeBlock;
                continue;
            }
            if (insideCodeBlock) {
                continue;
            }

            // 工具选择行
            Matcher selectMatcher = TOOL_SELECT_PATTERN.matcher(trimmed);
            String toolName = null;
            if (selectMatcher.find()) {
                toolName = selectMatcher.group(1).trim();
            }

            // 工具执行行
            String action = null;
            String filePath = null;

            Matcher writeMatcher = TOOL_WRITE_PATTERN.matcher(trimmed);
            if (writeMatcher.find()) {
                action = "写入文件";
                filePath = writeMatcher.group(1).trim();
            }
            Matcher modifyMatcher = TOOL_MODIFY_PATTERN.matcher(trimmed);
            if (modifyMatcher.find()) {
                action = "修改文件";
                filePath = modifyMatcher.group(1).trim();
            }
            Matcher deleteMatcher = TOOL_DELETE_PATTERN.matcher(trimmed);
            if (deleteMatcher.find()) {
                action = "删除文件";
                filePath = deleteMatcher.group(1).trim();
            }
            Matcher dirReadMatcher = TOOL_DIR_READ_PATTERN.matcher(trimmed);
            if (dirReadMatcher.find()) {
                action = "读取目录";
                filePath = dirReadMatcher.group(1).trim();
            }

            if (action != null || toolName != null) {
                tools.add(ToolCallInfo.builder()
                        .toolName(toolName != null ? toolName : action)
                        .action(action != null ? action : "未知操作")
                        .filePath(filePath)
                        .status("success")
                        .build());
            }
        }

        return tools;
    }

    /**
     * 为某条 AI 消息计算或从缓存读取摘要 + 自然语言 + 工具调用。
     *
     * @param appId   应用 id
     * @param historyId 历史记录 id
     * @param message AI 原始消息
     * @return 缓存条目（包含摘要文本、自然语言、工具列表）
     */
    public SummaryCacheEntry getOrCompute(Long appId, Long historyId, String message) {
        Map<Long, SummaryCacheEntry> appCache = summaryCache.get(appId);
        if (appCache != null) {
            SummaryCacheEntry entry = appCache.get(historyId);
            if (entry != null) {
                return entry;
            }
        }

        // 缓存未命中，实时解析
        SummaryResult result = parseAiMessage(message);
        String summaryText = buildUserSummary(result);
        String nlText = extractNaturalLanguage(message);
        List<ToolCallInfo> toolCalls = extractToolCalls(message);

        SummaryCacheEntry entry = new SummaryCacheEntry(summaryText, nlText, toolCalls);

        // 写入缓存
        Map<Long, SummaryCacheEntry> cacheMap = appCache;
        if (cacheMap == null) {
            cacheMap = new ConcurrentHashMap<>();
            summaryCache.put(appId, cacheMap);
        }
        cacheMap.put(historyId, entry);

        return entry;
    }

    /**
     * 失效指定应用的摘要缓存。
     * 在 {@code addChatMessageAndReturnId()} 中新增 AI 消息后调用。
     *
     * @param appId 应用 id
     */
    public void invalidateCache(Long appId) {
        if (appId != null) {
            summaryCache.remove(appId);
            log.debug("AI 消息摘要缓存已失效，appId={}", appId);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/core/summary/AiMessageSummaryService.java
git commit -m "feat: implement AiMessageSummaryService for AI message parsing and summarization"
```

---

### Task 3: AiMessageSummaryService 单元测试

**Files:**
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/core/summary/AiMessageSummaryServiceTest.java`

- [ ] **Step 1: 创建测试类，覆盖核心解析场景**

测试目标：
- 混合消息（工具 + NL）的 parseAiMessage 计数正确性
- 纯工具消息的处理
- 纯 NL 消息的处理
- 空消息/空白消息的幂等性
- buildUserSummary 的格式完整性
- extractNaturalLanguage 的净化正确性
- extractToolCalls 的结构化提取正确性

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link AiMessageSummaryService} 的单元测试。
 * 核心场景：混合消息、纯工具、纯 NL、空消息的解析正确性。
 */
class AiMessageSummaryServiceTest {

    private AiMessageSummaryService service;

    @BeforeEach
    void setUp() {
        service = new AiMessageSummaryService();
    }

    // ========== parseAiMessage ==========

    @Test
    void parseAiMessage_shouldParseMixedContent() {
        String msg = """
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                ```html
                <h1>Hello</h1>
                ```
                [选择工具] FileModifyTool
                [工具调用] 修改文件 style.css
                ```css
                body { color: red; }
                ```
                已为您生成一个卡片组件。
                它包含响应式设计。
                """;

        SummaryResult result = service.parseAiMessage(msg);

        assertEquals(2, result.getTotalToolCalls());
        assertEquals(1, result.getWriteFileCount());
        assertEquals(1, result.getModifyFileCount());
        assertEquals(0, result.getDeleteFileCount());
        assertEquals(0, result.getDirReadCount());
        assertEquals(2, result.getNaturalLanguageChunks().size());
        assertEquals("已为您生成一个卡片组件。", result.getNaturalLanguageChunks().get(0));
        assertEquals("它包含响应式设计。", result.getNaturalLanguageChunks().get(1));
    }

    @Test
    void parseAiMessage_shouldHandleOnlyTools() {
        String msg = """
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                ```html
                <h1>Hello</h1>
                ```
                """;

        SummaryResult result = service.parseAiMessage(msg);

        assertEquals(1, result.getTotalToolCalls());
        assertTrue(result.getNaturalLanguageChunks().isEmpty());
    }

    @Test
    void parseAiMessage_shouldHandleOnlyNaturalLanguage() {
        String msg = "这是一个纯文字回复，没有工具调用。";

        SummaryResult result = service.parseAiMessage(msg);

        assertEquals(0, result.getTotalToolCalls());
        assertEquals(1, result.getNaturalLanguageChunks().size());
        assertEquals("这是一个纯文字回复，没有工具调用。", result.getNaturalLanguageChunks().get(0));
    }

    @Test
    void parseAiMessage_shouldHandleEmptyMessage() {
        SummaryResult result = service.parseAiMessage("");

        assertEquals(0, result.getTotalToolCalls());
        assertTrue(result.getNaturalLanguageChunks().isEmpty());
    }

    @Test
    void parseAiMessage_shouldHandleNullMessage() {
        SummaryResult result = service.parseAiMessage(null);

        assertEquals(0, result.getTotalToolCalls());
        assertTrue(result.getNaturalLanguageChunks().isEmpty());
    }

    // ========== buildUserSummary ==========

    @Test
    void buildUserSummary_shouldFormatWithTools() {
        SummaryResult result = SummaryResult.builder()
                .totalToolCalls(3)
                .writeFileCount(2)
                .modifyFileCount(1)
                .build();

        String summary = service.buildUserSummary(result);

        assertEquals("[AI回复已完成] 共调用 3 次工具（写入 2，修改 1）", summary);
    }

    @Test
    void buildUserSummary_shouldFormatWithNoTools() {
        SummaryResult result = SummaryResult.builder()
                .totalToolCalls(0)
                .build();

        String summary = service.buildUserSummary(result);

        assertEquals("[AI回复已完成] 共调用 0 次工具", summary);
    }

    @Test
    void buildUserSummary_shouldHandleNullResult() {
        assertEquals("[AI回复已完成] 共调用 0 次工具", service.buildUserSummary(null));
    }

    // ========== extractNaturalLanguage ==========

    @Test
    void extractNaturalLanguage_shouldRemoveToolLines() {
        String msg = """
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                这是用户真正看到的评论。
                可以有多行。
                """;

        String nl = service.extractNaturalLanguage(msg);

        assertFalse(nl.contains("[选择工具]"));
        assertFalse(nl.contains("[工具调用]"));
        assertTrue(nl.contains("这是用户真正看到的评论。"));
        assertTrue(nl.contains("可以有多行。"));
    }

    @Test
    void extractNaturalLanguage_shouldReturnEmptyForAllTools() {
        String msg = """
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                """;

        assertEquals("", service.extractNaturalLanguage(msg));
    }

    // ========== extractToolCalls ==========

    @Test
    void extractToolCalls_shouldReturnStructuredList() {
        String msg = """
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                [选择工具] FileModifyTool
                [工具调用] 修改文件 style.css
                """;

        List<ToolCallInfo> tools = service.extractToolCalls(msg);

        assertEquals(4, tools.size());
        assertEquals("FileWriteTool", tools.get(0).getToolName());
        assertEquals("写入文件", tools.get(0).getAction());
        assertEquals("index.html", tools.get(0).getFilePath());
        assertEquals("FileModifyTool", tools.get(2).getToolName());
    }

    @Test
    void extractToolCalls_shouldReturnEmptyForNoTools() {
        assertEquals(0, service.extractToolCalls("纯文字回复").size());
    }

    @Test
    void extractToolCalls_shouldReturnEmptyForNull() {
        assertEquals(0, service.extractToolCalls(null).size());
    }

    // ========== getOrCompute / invalidateCache ==========

    @Test
    void getOrCompute_shouldReturnCachedEntry() {
        String msg = "纯文字回复内容。";
        SummaryCacheEntry first = service.getOrCompute(1L, 100L, msg);
        SummaryCacheEntry second = service.getOrCompute(1L, 100L, msg);

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.summaryText(), second.summaryText());
    }

    @Test
    void invalidateCache_shouldRemoveCache() {
        service.getOrCompute(1L, 100L, "测试内容");
        service.invalidateCache(1L);

        // 验证：再次获取时重新解析（这里只确认不会抛异常）
        SummaryCacheEntry after = service.getOrCompute(1L, 100L, "测试内容");
        assertNotNull(after);
    }
}
```

- [ ] **Step 2: 运行测试确认通过**

Run: `./mvnw test -Dtest="core.summary.AiMessageSummaryServiceTest" -q`
Expected: 所有 15 个测试 PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/dbts/glyahhaigeneratecode/core/summary/AiMessageSummaryServiceTest.java
git commit -m "test: add AiMessageSummaryService unit tests"
```

---

### Task 4: ChatHistoryController 新增端点

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/controller/ChatHistoryController.java`

- [ ] **Step 1: 注入 AiMessageSummaryService**

```java
// 在 ChatHistoryController 的现有字段末尾追加：
private final AiMessageSummaryService aiMessageSummaryService;
```

- [ ] **Step 2: 新增 GET /chatHistory/{historyId}/content 端点**

```java
/**
 * 【用户】获取单条 AI 消息的自然语言正文（不含工具调用和代码块）。
 * 用于普通用户点击「显示内容」时按需加载。
 *
 * @param historyId 历史记录 id
 * @param request   HTTP 请求
 * @return 纯语言正文
 */
@GetMapping("/{historyId}/content")
public BaseResponse<AiMessageContentVO> getAiMessageContent(
        @PathVariable Long historyId,
        HttpServletRequest request) {
    User loginUser = userService.getUserInSession(request);
    ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

    ChatHistory history = chatHistoryService.getById(historyId);
    ThrowUtils.throwIf(history == null, ErrorCode.NOT_FOUND_ERROR, "历史记录不存在");

    // 权限校验：仅应用创建者或管理员
    App app = appService.getById(history.getAppId());
    ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
    boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
    boolean isCreator = app.getUserId().equals(loginUser.getId());
    ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该历史记录");

    String nl = aiMessageSummaryService.extractNaturalLanguage(history.getMessage());
    return ResultUtils.success(new AiMessageContentVO(nl));
}
```

- [ ] **Step 3: 新增 GET /chatHistory/{historyId}/tools 端点**

```java
/**
 * 【用户】获取单条 AI 消息的工具调用详情列表。
 * 用于普通用户点击「显示工具」时按需加载。
 *
 * @param historyId 历史记录 id
 * @param request   HTTP 请求
 * @return 工具调用详情列表
 */
@GetMapping("/{historyId}/tools")
public BaseResponse<List<AiToolCallDetailVO>> getAiMessageTools(
        @PathVariable Long historyId,
        HttpServletRequest request) {
    User loginUser = userService.getUserInSession(request);
    ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

    ChatHistory history = chatHistoryService.getById(historyId);
    ThrowUtils.throwIf(history == null, ErrorCode.NOT_FOUND_ERROR, "历史记录不存在");

    // 权限校验：仅应用创建者或管理员
    App app = appService.getById(history.getAppId());
    ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
    boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
    boolean isCreator = app.getUserId().equals(loginUser.getId());
    ThrowUtils.throwIf(!isAdmin && !isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该历史记录");

    List<ToolCallInfo> toolCalls = aiMessageSummaryService.extractToolCalls(history.getMessage());
    List<AiToolCallDetailVO> voList = toolCalls.stream().map(tc -> {
        AiToolCallDetailVO vo = new AiToolCallDetailVO();
        vo.setToolName(tc.getToolName());
        vo.setAction(tc.getAction());
        vo.setFilePath(tc.getFilePath());
        return vo;
    }).toList();
    return ResultUtils.success(voList);
}
```

- [ ] **Step 4: 修改 listChatHistory，非管理员时替换 AI 消息内容**

```java
// 在 listChatHistory 方法中，获取 loginUser 之后、调用 service 之前添加角色判断分支

// ... 现有代码不变 ...
User loginUser = userService.getUserInSession(request);

// 新增：非管理员时从 service 获取带有摘要替换的历史
AppChatHistoryPageVO page;
boolean isAdmin = UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole());
if (isAdmin) {
    page = chatHistoryService.listAppChatHistoryByPage(appId, size, lastCreateTime, loginUser);
} else {
    page = chatHistoryService.listAppChatHistoryByPageForUser(appId, size, lastCreateTime, loginUser, aiMessageSummaryService);
}
return ResultUtils.success(page);
```

**注意：** 这需要在 ChatHistoryService 中添加新方法 `listAppChatHistoryByPageForUser()`。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/controller/ChatHistoryController.java
git commit -m "feat: add on-demand AI message content/tool endpoints + role-aware history API"
```

---

### Task 5: ChatHistoryService 新增用户角色分支

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/ChatHistoryService.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java`

- [ ] **Step 1: ChatHistoryService 接口新增方法**

```java
// 在 ChatHistoryService 接口中追加：

/**
 * 分页查询某个应用的对话历史（用户模式）：非管理员只显示 AI 消息的摘要 + 自然语言。
 *
 * @param appId          应用 id
 * @param pageSize       每次加载条数
 * @param lastCreateTime 游标时间
 * @param loginUser      当前登录用户
 * @param summaryService AI 消息摘要服务
 * @return 分页结果（AI 消息的 message 已替换为摘要文本）
 */
AppChatHistoryPageVO listAppChatHistoryByPageForUser(
        Long appId, int pageSize, LocalDateTime lastCreateTime,
        User loginUser, AiMessageSummaryService summaryService);
```

- [ ] **Step 2: ChatHistoryServiceImpl 实现**

```java
@Override
public AppChatHistoryPageVO listAppChatHistoryByPageForUser(
        Long appId, int pageSize, LocalDateTime lastCreateTime,
        User loginUser, AiMessageSummaryService summaryService) {

    // 调用现有的校验 + 加载逻辑
    ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用ID不能为空");
    ThrowUtils.throwIf(pageSize <= 0 || pageSize > 50, ErrorCode.PARAMS_ERROR, "页面大小必须在1-50之间");
    ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

    App app = appService.getById(appId);
    ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
    boolean isCreator = app.getUserId().equals(loginUser.getId());
    ThrowUtils.throwIf(!isCreator, ErrorCode.NO_AUTH_ERROR, "无权查看该应用的对话历史");

    // 加载原始分页数据
    Page<ChatHistory> page = loadAppChatHistoryPageFromEchoOrDb(appId, pageSize, lastCreateTime);

    // 对 AI 类型的消息替换为摘要
    for (ChatHistory record : page.getRecords()) {
        if (ChatHistoryMessageTypeEnum.AI.getValue().equals(record.getMessageType())
                && StrUtil.isNotBlank(record.getMessage())) {
            String message = record.getMessage();
            // 获取或计算摘要
            AiMessageSummaryService.SummaryCacheEntry entry =
                    summaryService.getOrCompute(appId, record.getId(), message);
            // 摘要行 + 自然语言拼接为 message 返回给前端
            String displayText = entry.summaryText() + "\n" + entry.naturalLanguage();
            record.setMessage(displayText);
        }
    }

    // 补充 workflow 阶段状态
    ChatHistorySchemaMigrationSupport.appendWorkflowStageStatusForHistoryPage(page);
    return AppChatHistoryPageVO.from(page, app.getIsBeta());
}
```

- [ ] **Step 3: 在 addChatMessageAndReturnId 中添加缓存失效**

在 `addChatMessageAndReturnId` 方法中，在 AI 消息写入成功后失效缓存：

```java
// 在方法末尾，return 之前添加：
if (saved && ChatHistoryMessageTypeEnum.AI.getValue().equals(messageType)) {
    // 新 AI 消息写入后失效摘要缓存，确保下次查询重新解析
    try {
        AiMessageSummaryService summaryService = SpringContextUtil.getBean(AiMessageSummaryService.class);
        if (summaryService != null) {
            summaryService.invalidateCache(appId);
        }
    } catch (Exception e) {
        log.warn("AI 消息摘要缓存失效失败，appId={}", appId, e);
    }
}
```

**注意：** 这里通过 SpringContextUtil 获取 bean，避免循环依赖注入。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/service/ChatHistoryService.java
git add src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java
git commit -m "feat: add role-aware history page for regular users with summary replacement"
```

---

### Task 6: 新增前端 API 和类型

**Files:**
- Modify: `ai-generate-code-frontend/src/api/chatHistoryController.ts`
- Modify: `ai-generate-code-frontend/src/api/types.ts`

- [ ] **Step 1: types.ts 新增 AiToolCallDetailVO 类型**

在文件末尾追加：

```typescript
/** 工具调用详情 */
export interface AiToolCallDetailVO {
  toolName?: string;
  action?: string;
  filePath?: string;
}
```

- [ ] **Step 2: chatHistoryController.ts 新增按需加载 API**

在文件末尾追加：

```typescript
/**
 * 获取单条 AI 消息的自然语言正文（按需加载）
 * @param historyId 历史记录 id
 * @returns 纯自然语言正文
 */
export function getAiMessageContentUsingGet(
  historyId: number,
  options?: Record<string, unknown>,
) {
  return request.get<BaseResponseString>(
    `/chatHistory/${historyId}/content`,
    options,
  );
}

/**
 * 获取单条 AI 消息的工具调用详情列表（按需加载）
 * @param historyId 历史记录 id
 * @returns 工具调用详情列表
 */
export function getAiMessageToolsUsingGet(
  historyId: number,
  options?: Record<string, unknown>,
) {
  return request.get<BaseResponseListAiToolCallDetailVO>(
    `/chatHistory/${historyId}/tools`,
    options,
  );
}
```

- [ ] **Step 3: Commit**

```bash
cd ai-generate-code-frontend
git add src/api/types.ts src/api/chatHistoryController.ts
git commit -m "feat: add on-demand AI message content/tools API"
```

---

### Task 7: AiHistoryMessage.vue 组件

**Files:**
- Create: `ai-generate-code-frontend/src/components/AiHistoryMessage.vue`

- [ ] **Step 1: 创建组件**

```vue
<template>
  <div class="ai-history-message">
    <!-- 头部：角色标签 + 切换按钮 -->
    <div class="ai-header">
      <div class="ai-role-badge">
        <span class="ai-icon">🤖</span>
        <span class="ai-label">AI</span>
      </div>
      <div class="ai-toolbar">
        <a-button
          size="small"
          type="link"
          :loading="loadingTools"
          @click="toggleTools"
        >
          {{ showTools ? '收起工具▲' : '显示工具▼' }}
        </a-button>
      </div>
    </div>

    <!-- 摘要行 -->
    <div class="ai-summary" v-if="summaryText">
      {{ summaryText }}
    </div>

    <!-- 自然语言正文 -->
    <div class="ai-nl" v-if="nlText">
      {{ nlText }}
    </div>

    <!-- 错误消息 -->
    <div class="ai-error" v-if="messageType === 'error'">
      <a-alert
        message="AI 回复错误"
        :description="summaryText || nlText || 'error'"
        type="error"
        show-icon
      />
    </div>

    <!-- 工具调用详情（展开后） -->
    <div class="ai-tools" v-if="showTools">
      <div v-if="!toolDetails || toolDetails.length === 0" class="ai-tools-empty">
        暂无工具调用
      </div>
      <div v-else class="ai-tools-list">
        <div
          v-for="(tool, index) in toolDetails"
          :key="index"
          class="ai-tool-item"
        >
          <span class="tool-action">{{ tool.action }}</span>
          <span class="tool-path" v-if="tool.filePath">{{ tool.filePath }}</span>
          <span class="tool-name">{{ tool.toolName }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { getAiMessageToolsUsingGet } from '@/api/chatHistoryController';
import type { AiToolCallDetailVO } from '@/api/types';

const props = defineProps<{
  historyId: number;
  summaryText: string;
  nlText: string;
  messageType: string;
}>();

const showTools = ref(false);
const toolDetails = ref<AiToolCallDetailVO[] | null>(null);
const loadingTools = ref(false);
const toolsLoaded = ref(false);

async function toggleTools() {
  if (showTools.value) {
    showTools.value = false;
    return;
  }

  showTools.value = true;

  // 已加载过则不再请求
  if (toolsLoaded.value) {
    return;
  }

  loadingTools.value = true;
  try {
    const res = await getAiMessageToolsUsingGet(props.historyId);
    toolDetails.value = res?.data ?? [];
    toolsLoaded.value = true;
  } catch {
    toolDetails.value = [];
  } finally {
    loadingTools.value = false;
  }
}
</script>

<style scoped>
.ai-history-message {
  padding: 8px 0;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.ai-role-badge {
  display: flex;
  align-items: center;
  gap: 4px;
}

.ai-icon {
  font-size: 16px;
}

.ai-label {
  font-weight: 600;
  font-size: 14px;
  color: var(--text-color);
}

.ai-toolbar {
  display: flex;
  gap: 4px;
}

.ai-summary {
  font-size: 13px;
  color: var(--text-color-secondary);
  background: var(--background-color-secondary);
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 4px;
}

.ai-nl {
  font-size: 14px;
  color: var(--text-color);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-error {
  margin: 4px 0;
}

.ai-tools {
  margin-top: 6px;
  padding: 6px 8px;
  background: var(--background-color-tertiary);
  border-radius: 4px;
  border: 1px solid var(--border-color-secondary);
}

.ai-tools-empty {
  font-size: 13px;
  color: var(--text-color-secondary);
  text-align: center;
  padding: 4px 0;
}

.ai-tools-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ai-tool-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  padding: 2px 0;
}

.tool-action {
  font-weight: 500;
  color: var(--primary-color);
  min-width: 60px;
}

.tool-path {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
  font-size: 12px;
  color: var(--text-color-secondary);
  flex: 1;
}

.tool-name {
  font-size: 12px;
  color: var(--text-color-tertiary);
}
</style>
```

- [ ] **Step 2: Commit**

```bash
cd ai-generate-code-frontend
git add src/components/AiHistoryMessage.vue
git commit -m "feat: add AiHistoryMessage component with collapsible tool details"
```

---

### Task 8: HistoryToolbar.vue 组件

**Files:**
- Create: `ai-generate-code-frontend/src/components/HistoryToolbar.vue`

- [ ] **Step 1: 创建组件**

```vue
<template>
  <div class="history-toolbar" v-if="visible">
    <a-space size="small">
      <a-button size="small" @click="onReset">
        <template #icon><ReloadOutlined /></template>
        重置
      </a-button>
      <a-button size="small" @click="onCollapseAll">
        <template #icon><FoldOutlined /></template>
        全部收起
      </a-button>
      <a-button size="small" @click="onExpandAllTools">
        <template #icon><UnfoldOutlined /></template>
        全部展开工具
      </a-button>
    </a-space>
  </div>
</template>

<script setup lang="ts">
import { ReloadOutlined, FoldOutlined, UnfoldOutlined } from '@ant-design/icons-vue';

defineProps<{
  visible: boolean;
}>();

const emit = defineEmits<{
  reset: [];
  collapseAll: [];
  expandAllTools: [];
}>();

function onReset() {
  emit('reset');
}

function onCollapseAll() {
  emit('collapseAll');
}

function onExpandAllTools() {
  emit('expandAllTools');
}
</script>

<style scoped>
.history-toolbar {
  padding: 8px 0;
  border-bottom: 1px solid var(--border-color-secondary);
  margin-bottom: 8px;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
cd ai-generate-code-frontend
git add src/components/HistoryToolbar.vue
git commit -m "feat: add HistoryToolbar with batch reset/collapse/expand controls"
```

---

### Task 9: AppChatView.vue 集成新组件

**Files:**
- Modify: `ai-generate-code-frontend/src/page/App/AppChatView.vue`

- [ ] **Step 1: 在模板中集成 HistoryToolbar 和 AiHistoryMessage**

在消息列表顶部嵌入工具栏，修改 AI 消息渲染区域：

```vue
<!-- 在 displayMessages 循环上方插入工具栏 -->
<HistoryToolbar
  v-if="historyMessages.length > 0"
  :visible="true"
  @reset="handleHistoryReset"
  @collapse-all="handleHistoryCollapseAll"
  @expand-all-tools="handleHistoryExpandAllTools"
/>

<!-- 在循环体中，将原有的 AI 消息渲染替换为 -->
<AiHistoryMessage
  v-else-if="m.role === 'assistant' && m.historyId"
  :key="'ai-' + m.historyId"
  :history-id="m.historyId"
  :summary-text="m.summaryText || ''"
  :nl-text="m.nlText || ''"
  :message-type="m.messageType || 'ai'"
  :ref="el => setAiMessageRef(m.historyId!, el)"
/>
```

- [ ] **Step 2: 在 ChatMessage 类型中追加摘要字段**

在 `AppChatView.vue` 的 ChatMessage 类型定义（约 L280-300）中追加：

```typescript
interface ChatMessage {
  // ... 已有字段
  historyId?: number;         // 历史记录 id（用于按需加载）
  summaryText?: string;       // 摘要文本（非管理员时从后端返回）
  nlText?: string;            // 自然语言正文（从后端返回的 message 中剥离）
  messageType?: string;       // user / ai / error
}
```

- [ ] **Step 3: 修改 historyToMessage 函数解析摘要和 NL**

在 `historyToMessage()` 函数（约 L376）中，解析从后端返回的 AI 消息：

```typescript
function historyToMessage(record: ChatHistory): ChatMessage {
  const base: ChatMessage = {
    id: record.id,
    role: record.messageType === 'user' ? 'user' : 'assistant',
    content: record.message ?? '',
    createTime: record.createTime,
    messageType: record.messageType,
    historyId: record.id,
  };

  // 如果是 AI 消息，尝试分离摘要行和 NL
  if (record.messageType === 'ai' || record.messageType === 'AI') {
    const msg = record.message ?? '';
    const firstNewline = msg.indexOf('\n');
    if (firstNewline > 0) {
      base.summaryText = msg.substring(0, firstNewline).trim();
      base.nlText = msg.substring(firstNewline + 1).trim();
    } else {
      base.summaryText = msg;
      base.nlText = '';
    }
  }

  // 如果是 error 消息
  if (record.messageType === 'error') {
    base.summaryText = record.message ?? 'error';
    base.nlText = '';
  }

  return base;
}
```

- [ ] **Step 4: 添加新的 handler 方法**

```typescript
// AI 消息的局部状态管理
const aiMessageStates = reactive<Record<number, { showTools: boolean }>>({});

function setAiMessageRef(historyId: number, el: any) {
  // 用于访问 AiHistoryMessage 内部状态（按需）
}

function handleHistoryReset() {
  // 重置所有
  Object.keys(aiMessageStates).forEach(key => {
    aiMessageStates[Number(key)] = { showTools: false };
  });
  // 重新加载历史
  loadChatHistory();
}

function handleHistoryCollapseAll() {
  Object.keys(aiMessageStates).forEach(key => {
    aiMessageStates[Number(key)] = { showTools: false };
  });
}

function handleHistoryExpandAllTools() {
  // 只展开尚未展开的
  Object.keys(aiMessageStates).forEach(key => {
    // AiHistoryMessage 组件管理自己的展开状态
  });
}
```

- [ ] **Step 5: Commit**

```bash
cd ai-generate-code-frontend
git add src/page/App/AppChatView.vue
git commit -m "feat: integrate AiHistoryMessage and HistoryToolbar into AppChatView"
```

---

### Task 10: 编译验证

- [ ] **Step 1: 后端编译**

```bash
cd d:/mainJava/all\ Code/program/glyahh-ai-generate-code
./mvnw -q -DskipTests compile
Expected: BUILD SUCCESS
```

- [ ] **Step 2: 后端测试通过**

```bash
./mvnw test -Dtest="core.summary.AiMessageSummaryServiceTest" -q
Expected: 所有测试 PASS
```

- [ ] **Step 3: 后端完整测试（排除需要外部依赖的集成测试）**

```bash
./mvnw test -Dtest="!*IntegrationTest,!*InjectTest,!*WorkflowTest*" -q
Expected: 其余测试 PASS
```

- [ ] **Step 4: 前端类型检查**

```bash
cd ai-generate-code-frontend
npx vue-tsc --noEmit
Expected: 无类型错误
```

- [ ] **Step 5: Commit 最终验证**

```bash
git add -A
git commit -m "chore: finalize user history content masking feature"
```
