# 普通用户历史记录 AI 输出内容屏蔽设计

## 概述

普通用户在 AppChatView 中查看历史对话时，AI 消息只展示摘要统计行 + 自然语言正文。工具调用详情按需加载，通过「显示工具」按钮切换。管理员不受影响，仍可查看完整内容。

## 设计动机

- 普通用户不需要看到 AI 生成时的原始工具调用细节和代码块
- 仍需要可读的摘要信息（工具调用次数、类型分布）了解此轮 AI 做了什么
- 管理员仍需完整输出用于排查和审核
- 最小改动、不改表结构、按需加载

> **术语说明：** NL 是 Natural Language（自然语言）的缩写，指 AI 回复中「已为您生成一个卡片组件...」这类纯文字评论，区别于 `[选择工具]`、`[工具调用]`、代码块等结构化内容。

## 约束

- 不改 `chat_history` 表结构
- 管理员视图不受影响
- 前端局部渲染，不互相影响
- 后端摘要通过 Caffeine 缓存消峰

## 后端设计

### 新增包：`core/summary/`

#### `SummaryResult.java` — 摘要结果模型

```java
@Data
@Builder
public class SummaryResult {
    private int totalToolCalls;           // 工具调用总次数
    private int writeFileCount;           // 写入文件次数
    private int modifyFileCount;          // 修改文件次数
    private int deleteFileCount;          // 删除文件次数
    private int dirReadCount;             // 目录读取次数
    private List<String> naturalLanguageChunks;  // 自然语言片段
    private Integer estimatedElapsedSeconds;
}
```

#### `ToolCallInfo.java` — 工具调用信息

```java
@Data
@Builder
public class ToolCallInfo {
    private String toolName;      // FileWriteTool / FileModifyTool / FileDeleteTool / FileDirReadTool
    private String action;        // 写入文件 / 修改文件 / 删除文件 / 读取目录
    private String filePath;      // 操作的文件路径
    private String status;        // success / failed（如有）
}
```

#### `AiMessageSummaryService.java` — AI 消息摘要服务

核心职责：

1. **`parseAiMessage(String message) → SummaryResult`**
   - 按行扫描，识别三类内容：
     - 工具选择行：`[选择工具] xxx`
     - 工具执行行：`[工具调用] 写入/修改/删除文件 xxx`
     - 自然语言行：其余行
   - 统计各类型工具调用数量
   - 收集自然语言（NL）片段
   - 提取估算耗时（从消息文本中匹配时间标记）

2. **`buildUserSummary(SummaryResult result) → String`**
   - 拼接为用户可见的摘要文本
   - 格式：「[AI回复已完成] 共调用 N 次工具（写入 X, 修改 Y）」
   - 无工具调用时：「[AI回复已完成] 共调用 0 次工具」

3. **`extractNaturalLanguage(String message) → String`**
   - 去除所有 `[选择工具]` / `[工具调用]` 行
   - 去除代码块 ` ```...``` `
   - 返回纯评论正文

4. **`extractToolCalls(String message) → List<ToolCallInfo>`**
   - 解析所有工具调用行
   - 提取 toolName、action、filePath

5. **Caffeine 缓存策略**
   - key: `"ai:msg:summary:{appId}"` → `Map<Long, String>`（historyId → 摘要文本）
   - TTL: 5 分钟
   - 失效时机：该 app 有新 AI 消息写入时

### Controller 变更：`ChatHistoryController`

| 方法 | 路径 | 说明 |
|------|------|------|
| 修改 | `GET /chatHistory/app/{appId}` | 非管理员时，AI 记录的 `message` 替换为摘要文本 |
| 新增 | `GET /chatHistory/{historyId}/content` | 返回某条 AI 消息的纯语言正文（去除工具调用和代码块） |
| 新增 | `GET /chatHistory/{historyId}/tools` | 返回某条 AI 消息的工具调用结构化列表 |

新端点权限：登录用户 + 应用创建者或管理员（与现有 `listAppChatHistoryByPage` 权限一致）。

### Service 变更：`ChatHistoryServiceImpl`

`listAppChatHistoryByPage()` 新增分支：

```java
// 原有权限校验（isAdmin / isCreator）不变
if (isAdmin) {
    // 直接返回原始 ChatHistory 实体（同现有行为）
} else {
    // 对每个 AI 类型的记录：
    //   1. 调用 AiMessageSummaryService.parseAiMessage()
    //   2. 调用 buildUserSummary() 生成摘要
    //   3. 替换 message 字段
    //   4. 从缓存读取（命中）或实时解析（写入缓存）
}
```

`addChatMessageAndReturnId()`：新 AI 消息写入后失效对应 appId 的摘要缓存。

### VO 新增

```java
@Data
public class AiToolCallDetailVO {
    private String toolName;
    private String action;
    private String filePath;
}

@Data
public class AiMessageContentVO {
    private String content;  // 纯自然语言正文
}
```

## 前端设计

### 新增组件：`AiHistoryMessage.vue`

- Props: `historyId`, `summaryText`, `nlText`, `messageType`, `createTime`
- State:
  - `showTools: boolean` — 工具详情展开状态
  - `toolDetails: ToolCallDetailVO[] | null` — 按需加载的数据缓存
  - `loadingTools: boolean` — 加载中状态
- 模板结构：

```
┌────────────────────────────────────────────┐
│  AI  🤖  [显示工具▼]（按钮，始终可见）      │
│                                            │
│  [AI回复已完成] 共调用 N 次工具（写入 x..）│ ← 摘要行
│  这是 AI 的自然语言回复正文...              │ ← 自然语言全文（默认可见）
│                                            │
│  ┌ 工具调用（展开后）：                    │
│  │ FileWriteTool 写入 index.html           │
│  │ FileModifyTool 修改 style.css           │
│  │ ← 无工具调用时显示「暂无工具调用」      │
│  └──────────────────────────────────       │
└────────────────────────────────────────────┘
```

### 新增组件：`HistoryToolbar.vue`

```
[重置]  [全部收起]  [全部展开工具]
```

- **重置**：所有 AI 消息恢复默认态，清除数据缓存（重新请求）
- **全部收起**：折叠所有「显示工具」展开项，保留数据缓存
- **全部展开工具**：批量展开所有消息的工具调用详情

### 修改 `AppChatView.vue`

- `loadChatHistory()`：对 AI 类型的记录，从 response 的 `message` 字段提取摘要 + 自然语言
- 渲染区：用 `AiHistoryMessage.vue` 替换原有的 AI 消息渲染
- 消息列表顶部嵌入 `HistoryToolbar.vue`

### API 层新增

```typescript
// src/api/chatHistoryController.ts
export const getAiMessageContent = (historyId: number) =>
  request.get<BaseResponseString>(`/chatHistory/${historyId}/content`);

export const getAiMessageTools = (historyId: number) =>
  request.get<BaseResponseListToolCallDetailVO>(`/chatHistory/${historyId}/tools`);
```

## 解析规则（摘要算法）

解析 AI 消息时的行匹配规则：

| 模式 | 归类 | 示例 |
|------|------|------|
| `/^\[选择工具\]\s*(.+)/` | 工具选择 | `[选择工具] FileWriteTool` |
| `/^\[工具调用\]\s*写入文件\s*(.+)/` | 写入文件 | `[工具调用] 写入文件 index.html` |
| `/^\[工具调用\]\s*修改文件\s*(.+)/` | 修改文件 | `[工具调用] 修改文件 style.css` |
| `/^\[工具调用\]\s*删除文件\s*(.+)/` | 删除文件 | `[工具调用] 删除文件 old.txt` |
| `/^\[工具调用\]\s*读取目录\s*(.+)/` | 读取目录 | `[工具调用] 读取目录 src/` |
| ` ```[\s\S]*?``` ` | 代码块（跳过） | 被工具执行块包围的代码内容 |
| 其余非空行 | 自然语言 | 收集为自然语言(NL) chunks |

## 缓存策略

```
摘要缓存（Caffeine）:
  key: "ai:msg:summary:{appId}"
  value: ConcurrentHashMap<Long, SummaryCacheEntry>
    key: historyId
    value: { summaryText, nlText, toolCalls }
  TTL: 5 分钟
  失效: addChatMessageAndReturnId() 中 type=AI 时调用 invalidate(appId)

工具调用详情（不缓存，每次按需从 DB 读取解析）:
  原因：展开频率远低于历史列表加载，无需缓存
```

## 边界情况

| 场景 | 行为 |
|------|------|
| AI 消息无工具调用 | 摘要显示「共调用 0 次工具」；点击「显示工具」显示「暂无工具调用」 |
| AI 消息全为工具调用，无自然语言 | 摘要行正常显示，自然语言区域显示 nothing |
| Workflow 状态消息（`[workflow] 第 N 步完成`） | 按原有正则匹配后归类为自然语言的一部分，不单独展示工具卡片 |
| 错误消息（messageType=error） | AI 内容显示 error，工具显示 error |
| 用户消息（messageType=user） | 不做任何处理 |
| 空消息或极短消息 | 正常显示，工具调用数为 0 |
