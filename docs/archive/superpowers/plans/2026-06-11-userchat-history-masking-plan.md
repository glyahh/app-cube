# UserChatHistory 列表页 AI 消息摘要展示 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task.

**Goal:** 修改「我的历史记录」列表页，AI 类型消息以「摘要行 + NL 截断」两行展示，后端返回结构化字段

**Architecture:** 后端复用 `AiMessageSummaryService` 解析 AI 消息，在 `listMyChatHistoryByPage()` 中填充 `summaryText`/`naturalLanguage` 字段；前端在表格列中按 `messageType` 分支渲染

**Tech Stack:** Spring Boot 3.5.10, Java 21, MyBatis-Flex, Vue 3 + Ant Design Vue 4

---

## 文件结构

### 新建（后端）
| 文件 | 职责 |
|------|------|
| `core/summary/AiMessageSummaryService.java` | AI 消息摘要解析服务 |
| `core/summary/SummaryResult.java` | 解析结果模型 |
| `core/summary/ToolCallInfo.java` | 工具调用信息模型 |
| `test/.../core/summary/AiMessageSummaryServiceTest.java` | 摘要服务单元测试 |

### 修改（后端）
| 文件 | 修改内容 |
|------|----------|
| `model/VO/UserChatHistoryItemVO.java` | 新增 `summaryText`、`naturalLanguage` 字段 |
| `service/impl/ChatHistoryServiceImpl.java` | `listMyChatHistoryByPage()` 中处理 AI 消息摘要 |

### 修改（前端）
| 文件 | 修改内容 |
|------|----------|
| `ai-generate-code-frontend/src/page/User/UserChatHistory.vue` | 消息内容列根据 messageType 分支渲染 |
| `ai-generate-code-frontend/src/api/types.ts` | 新增 `summaryText`、`naturalLanguage` 字段类型 |

---

### Task 1: 后端数据模型 + 摘要服务

**Files:**
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/summary/SummaryResult.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/summary/ToolCallInfo.java`
- Create: `src/main/java/com/dbts/glyahhaigeneratecode/core/summary/AiMessageSummaryService.java`
- Create: `src/test/java/com/dbts/glyahhaigeneratecode/core/summary/AiMessageSummaryServiceTest.java`
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/model/VO/UserChatHistoryItemVO.java`

- [ ] **Step 1: 创建 SummaryResult.java**

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class SummaryResult {
    private int totalToolCalls;
    private int writeFileCount;
    private int modifyFileCount;
    private int deleteFileCount;
    private int dirReadCount;
    private List<String> naturalLanguageChunks;
}
```

- [ ] **Step 2: 创建 ToolCallInfo.java**

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ToolCallInfo {
    private String toolName;
    private String action;
    private String filePath;
}
```

- [ ] **Step 3: 创建 AiMessageSummaryService.java**

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

@Slf4j
@Service
public class AiMessageSummaryService {

    private static final Pattern TOOL_SELECT_PATTERN =
            Pattern.compile("^\\[选择工具]\\s*(.+)$");
    private static final Pattern TOOL_WRITE_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*写入文件\\s*(.+)$");
    private static final Pattern TOOL_MODIFY_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*修改文件\\s*(.+)$");
    private static final Pattern TOOL_DELETE_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*删除文件\\s*(.+)$");
    private static final Pattern TOOL_DIR_READ_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*读取目录\\s*(.+)$");
    private static final Pattern TOOL_EXEC_GENERIC_PATTERN =
            Pattern.compile("^\\[工具调用]\\s*(.+)$");

    private final Cache<Long, Map<Long, SummaryCacheEntry>> summaryCache =
            CacheUtil.newTimedCache(5 * 60 * 1000);

    public record SummaryCacheEntry(String summaryText, String naturalLanguage, List<ToolCallInfo> toolCalls) {}

    public SummaryResult parseAiMessage(String message) {
        if (StrUtil.isBlank(message)) {
            return SummaryResult.builder().totalToolCalls(0).naturalLanguageChunks(List.of()).build();
        }
        int writeCount = 0, modifyCount = 0, deleteCount = 0, dirReadCount = 0;
        List<String> nlChunks = new ArrayList<>();
        String[] lines = message.split("\n");
        boolean insideCodeBlock = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) { insideCodeBlock = !insideCodeBlock; continue; }
            if (insideCodeBlock) continue;
            if (TOOL_SELECT_PATTERN.matcher(trimmed).find()) continue;
            if (TOOL_WRITE_PATTERN.matcher(trimmed).find()) { writeCount++; continue; }
            if (TOOL_MODIFY_PATTERN.matcher(trimmed).find()) { modifyCount++; continue; }
            if (TOOL_DELETE_PATTERN.matcher(trimmed).find()) { deleteCount++; continue; }
            if (TOOL_DIR_READ_PATTERN.matcher(trimmed).find()) { dirReadCount++; continue; }
            if (TOOL_EXEC_GENERIC_PATTERN.matcher(trimmed).find()) continue;
            if (StrUtil.isNotBlank(trimmed)) nlChunks.add(trimmed);
        }
        return SummaryResult.builder()
                .totalToolCalls(writeCount + modifyCount + deleteCount + dirReadCount)
                .writeFileCount(writeCount).modifyFileCount(modifyCount)
                .deleteFileCount(deleteCount).dirReadCount(dirReadCount)
                .naturalLanguageChunks(nlChunks).build();
    }

    public String buildUserSummary(SummaryResult result) {
        if (result == null || result.getTotalToolCalls() == 0) return "[AI回复已完成] 共调用 0 次工具";
        StringBuilder sb = new StringBuilder("[AI回复已完成] 共调用 ");
        sb.append(result.getTotalToolCalls()).append(" 次工具（");
        boolean hasPrev = false;
        if (result.getWriteFileCount() > 0) { sb.append("写入 ").append(result.getWriteFileCount()); hasPrev = true; }
        if (result.getModifyFileCount() > 0) { if (hasPrev) sb.append("，"); sb.append("修改 ").append(result.getModifyFileCount()); hasPrev = true; }
        if (result.getDeleteFileCount() > 0) { if (hasPrev) sb.append("，"); sb.append("删除 ").append(result.getDeleteFileCount()); hasPrev = true; }
        if (result.getDirReadCount() > 0) { if (hasPrev) sb.append("，"); sb.append("读取目录 ").append(result.getDirReadCount()); }
        sb.append("）");
        return sb.toString();
    }

    public String extractNaturalLanguage(String message) {
        if (StrUtil.isBlank(message)) return "";
        SummaryResult result = parseAiMessage(message);
        List<String> chunks = result.getNaturalLanguageChunks();
        return (chunks == null || chunks.isEmpty()) ? "" : String.join("\n", chunks);
    }

    public SummaryCacheEntry getOrCompute(Long appId, Long historyId, String message) {
        Map<Long, SummaryCacheEntry> appCache = summaryCache.get(appId);
        if (appCache != null) {
            SummaryCacheEntry entry = appCache.get(historyId);
            if (entry != null) return entry;
        }
        SummaryResult result = parseAiMessage(message);
        String summaryText = buildUserSummary(result);
        String nlText = result.getNaturalLanguageChunks() != null
                ? String.join("\n", result.getNaturalLanguageChunks()) : "";
        List<ToolCallInfo> toolCalls = new ArrayList<>();
        SummaryCacheEntry entry = new SummaryCacheEntry(summaryText, nlText, toolCalls);
        Map<Long, SummaryCacheEntry> cacheMap = appCache;
        if (cacheMap == null) { cacheMap = new ConcurrentHashMap<>(); summaryCache.put(appId, cacheMap); }
        cacheMap.put(historyId, entry);
        return entry;
    }

    public void invalidateCache(Long appId) {
        if (appId != null) { summaryCache.remove(appId); log.debug("AI 消息摘要缓存已失效，appId={}", appId); }
    }
}
```

- [ ] **Step 4: UserChatHistoryItemVO 新增字段**

```java
// 在类中追加：
/** AI 消息摘要行 */
private String summaryText;

/** AI 消息自然语言正文 */
private String naturalLanguage;
```

- [ ] **Step 5: 创建 AiMessageSummaryServiceTest.java**

```java
package com.dbts.glyahhaigeneratecode.core.summary;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AiMessageSummaryServiceTest {

    private AiMessageSummaryService service;

    @BeforeEach
    void setUp() { service = new AiMessageSummaryService(); }

    @Test
    void parseAiMessage_mixed() {
        SummaryResult r = service.parseAiMessage("""
                [选择工具] FileWriteTool
                [工具调用] 写入文件 index.html
                这是评论。""");
        assertEquals(1, r.getTotalToolCalls());
        assertEquals(1, r.getWriteFileCount());
        assertEquals(1, r.getNaturalLanguageChunks().size());
    }

    @Test
    void parseAiMessage_onlyTools() {
        SummaryResult r = service.parseAiMessage("[选择工具] FileWriteTool\n[工具调用] 写入文件 index.html");
        assertEquals(1, r.getTotalToolCalls());
        assertTrue(r.getNaturalLanguageChunks().isEmpty());
    }

    @Test
    void parseAiMessage_onlyNL() {
        SummaryResult r = service.parseAiMessage("纯文字。");
        assertEquals(0, r.getTotalToolCalls());
        assertEquals(1, r.getNaturalLanguageChunks().size());
    }

    @Test
    void parseAiMessage_empty() {
        SummaryResult r = service.parseAiMessage("");
        assertEquals(0, r.getTotalToolCalls());
        assertTrue(r.getNaturalLanguageChunks().isEmpty());
    }

    @Test
    void buildUserSummary_withTools() {
        String s = service.buildUserSummary(SummaryResult.builder().totalToolCalls(3).writeFileCount(2).modifyFileCount(1).build());
        assertEquals("[AI回复已完成] 共调用 3 次工具（写入 2，修改 1）", s);
    }

    @Test
    void buildUserSummary_noTools() {
        assertEquals("[AI回复已完成] 共调用 0 次工具", service.buildUserSummary(SummaryResult.builder().totalToolCalls(0).build()));
    }

    @Test
    void extractNaturalLanguage_removesToolLines() {
        String nl = service.extractNaturalLanguage("[工具调用] 写入文件 a.html\n这是评论。");
        assertFalse(nl.contains("[工具调用]"));
        assertTrue(nl.contains("这是评论。"));
    }
}
```

- [ ] **Step 6: 运行测试**

Run: `./mvnw test -Dtest="core.summary.AiMessageSummaryServiceTest" -q`

- [ ] **Step 7: 编译 + 提交**

```bash
./mvnw -q -DskipTests compile
git add src/main/java/com/dbts/glyahhaigeneratecode/core/summary/
git add src/main/java/com/dbts/glyahhaigeneratecode/model/VO/UserChatHistoryItemVO.java
git add src/test/java/com/dbts/glyahhaigeneratecode/core/summary/
git commit -m "新增 AI 消息摘要服务 + UserChatHistoryItemVO 扩展字段"
```

---

### Task 2: 后端 Service 改造 listMyChatHistoryByPage

**Files:**
- Modify: `src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java`

- [ ] **Step 1: 注入 AiMessageSummaryService**

```java
// 在现有 @Resource 字段后面追加
@Resource
private AiMessageSummaryService aiMessageSummaryService;
```

- [ ] **Step 2: 修改 listMyChatHistoryByPage() 的 VO 组装逻辑**

定位到 `listMyChatHistoryByPage()` 方法中，在 `records.stream().map(item -> {...})` 的 lambda 体内，在 `vo.setMessage(item.getMessage())` 之后追加：

```java
// AI 消息：填充摘要字段替代原始内容
if (ChatHistoryMessageTypeEnum.AI.getValue().equals(item.getMessageType())
        && StrUtil.isNotBlank(item.getMessage())) {
    AiMessageSummaryService.SummaryCacheEntry entry = aiMessageSummaryService.getOrCompute(
            item.getAppId(), item.getId(), item.getMessage());
    vo.setSummaryText(entry.summaryText());
    vo.setNaturalLanguage(entry.naturalLanguage());
    vo.setMessage(null);
}
```

需要新增 import：
```java
import com.dbts.glyahhaigeneratecode.core.summary.AiMessageSummaryService;
```

- [ ] **Step 3: 编译验证**

Run: `./mvnw -q -DskipTests compile`

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/dbts/glyahhaigeneratecode/service/impl/ChatHistoryServiceImpl.java
git commit -m "listMyChatHistoryByPage AI 消息填充摘要字段"
```

---

### Task 3: 前端 UserChatHistory.vue 消息内容列改造

**Files:**
- Modify: `ai-generate-code-frontend/src/page/User/UserChatHistory.vue`
- Modify: `ai-generate-code-frontend/src/api/types.ts`

- [ ] **Step 1: types.ts 追加字段**

找到 `UserChatHistoryItemVO` 类型定义，追加：
```typescript
/** AI 消息摘要行 */
summaryText?: string;
/** AI 消息自然语言正文 */
naturalLanguage?: string;
```

- [ ] **Step 2: 修改消息内容列渲染**

找到 columns 定义中的「消息内容」列（`dataIndex: 'message'`），修改 `customRender`：

```typescript
{
  title: '消息内容',
  dataIndex: 'message',
  key: 'message',
  width: 350,
  customRender: ({ text, record }: { text?: string; record: UserChatHistoryItemVO }) => {
    const type = (record.messageType || '').toLowerCase()

    // 错误消息
    if (type === 'error') {
      return h('span', { style: 'color: #999;' }, '消息出错啦')
    }

    // AI 消息：摘要 + NL 两行
    if (type === 'ai') {
      const summary = record.summaryText || 'nothing'
      const nl = record.naturalLanguage
        ? (record.naturalLanguage.length > 80 ? record.naturalLanguage.slice(0, 80) + '…' : record.naturalLanguage)
        : 'nothing'
      return h('div', { style: 'line-height: 1.8;' }, [
        h('div', { style: 'font-size: 12px; color: #999; margin-bottom: 2px;' }, summary),
        h('div', { style: 'color: #666; font-size: 13px;' }, nl),
      ])
    }

    // 用户消息：80 字截断
    return h('span', truncateMessage(text, 80))
  },
}
```

同时更新列宽度 `width: 1100` 为 `width: 1300` 给新列更多空间。

- [ ] **Step 3: 前端类型检查**

```bash
cd ai-generate-code-frontend && npx vue-tsc --noEmit
```

- [ ] **Step 4: 提交**

```bash
git add ai-generate-code-frontend/src/page/User/UserChatHistory.vue
git add ai-generate-code-frontend/src/api/types.ts
git commit -m "UserChatHistory 列表页 AI 消息显示摘要两行"
```
