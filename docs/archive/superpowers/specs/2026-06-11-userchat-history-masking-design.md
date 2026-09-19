# UserChatHistory 列表页 AI 消息摘要展示设计

## 概述

修改「我的历史记录」列表页（UserChatHistory.vue），AI 类型的消息不直接显示原始内容，改为展示「摘要行 + NL 截断」两行。通过后端 `AiMessageSummaryService` 解析 AI 消息，返回结构化字段而非拼接字符串。

## 后端

### VO 变更：`UserChatHistoryItemVO`

新增两个可选字段：

```java
/** AI 消息摘要行，如「[AI回复已完成] 共调用 3 次工具（写入 2, 修改 1）」 */
private String summaryText;

/** AI 消息自然语言正文（去除了工具调用行和代码块） */
private String naturalLanguage;
```

### Service 变更：`ChatHistoryServiceImpl.listMyChatHistoryByPage()`

在现有分页查询之后，遍历 records：

```
for each record:
  if record.messageType == 'ai':
    result = AiMessageSummaryService.getOrCompute(record.appId, record.id, record.message)
    item.summaryText = result.summaryText
    item.naturalLanguage = result.naturalLanguage (截断 200 字)
    item.message = null // 不返回原始内容
```

### 新增类

复用之前设计的 `AiMessageSummaryService`（`core/summary/AiMessageSummaryService.java`）+ `SummaryResult.java` + `ToolCallInfo.java`。

**注意**：此 Service 无状态，只要类路径正确即可注入使用。

## 前端

### 变更：`UserChatHistory.vue`

**消息内容列** 修改渲染逻辑：

- `messageType === 'ai'`：
  - 第一行：`summaryText` — 灰底标签样式，`<Tag color="default">`
  - 第二行：`naturalLanguage` — 截断 80 字，灰色正文

- `messageType === 'user'`：
  - 保持现有逻辑：`truncateMessage(message, 80)`

其他列（消息类型、应用名称、应用 ID、创建时间、操作）不变。

## 边界情况

| 场景 | 行为 |
|------|------|
| AI 消息无 summaryText | 第一行显示 nothing，第二行正常显示 NL |
| AI 消息无 naturalLanguage | 第一行正常显示摘要，第二行显示 nothing |
| 两者都无 | 两行都显示 nothing |
| 错误消息（messageType=error） | 显示"消息出错啦" |
