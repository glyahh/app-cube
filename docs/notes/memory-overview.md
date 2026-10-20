# 会话记忆精简图

> 类型：技术说明与学习复盘；旧架构、类名和数字按原记录时点理解。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

本文用 **Mermaid flowchart** 从「**什么会进大模型 API**」与「**什么不进原文**」两个视角，概括会话记忆的**注入与拼装**关系。适合在已读过完整链路后，快速对照 Redis / MySQL / 磁盘与 `ChatMemory` 的分工。

> 关联文档：`learn/会话记忆完整架构图.md`（T0→T5 全流程）、`learn/会话记忆指南（会话记忆重构V4学习复盘）.md`、`learn/数据库表结构图-会话记忆与核心域.md`

---

## 精简关系图

```mermaid
flowchart LR
  subgraph 不进 API 原文
    ST[cm:state]
    RF[cm:ref + DB]
    DB[(chat_history 全表)]
    DISK[磁盘项目文件]
  end

  subgraph 图里会进 LLM
    CM[Redis ChatMemory]
    UM[本轮 userMessage]
    SP[@SystemMessage]
  end

  ST -->|只用来选注入哪些文件| INJ
  DISK -->|readFilePageHead 直读| INJ[[memory_inject]\n写入 ChatMemory]]
  INJ --> CM
  DB -->|最多 40 条 + 压缩| CM
  RF -.->|TODO 未接| CM
  ST -.->|摘要 TODO 未接| CM

  CM --> API[大模型 API]
  UM --> API
  SP --> API
```

---

## 读图要点

| 节点 | 角色 | 是否以原文进 API |
|------|------|------------------|
| `cm:state` | 会话记忆状态（含 `changedFilesJson`、摘要等元数据） | 否；当前主要用于**选哪些文件要注入** |
| `cm:ref` + DB | 超长附件引用（`conversation_memory_ref`） | 否（虚线：**TODO 未接**到 `ChatMemory`） |
| `chat_history` 全表 | MySQL 真相源 | 否；预加载时**最多 40 条**经压缩/过滤写入 `ChatMemory` |
| 磁盘项目文件 | `temp/code_output/...` 真实代码 | 否；经 `collectInjectCandidates` → `readFilePageHead` 直读 → 注入 |
| Redis `ChatMemory` | LangChain4j `MessageWindowChatMemory` | **是**（历史 user/ai + 注入的 `memory_inject`） |
| 本轮 `userMessage` | 当前请求用户消息 | **是** |
| `@SystemMessage` | 系统 Prompt（含工具说明等） | **是** |

**虚线（TODO）**：`cm:ref` 全文引用、`cm:state` 中的摘要字段，尚未在预加载阶段写入 `ChatMemory`，后续接入后可在本图把虚线改为实线。

---

## 与完整架构图的关系

- **完整图**（`learn/会话记忆完整架构图.md`）：HTTP → 压缩 → 预加载 → SSE → 轮次收口，含 `ConversationMemoryStateService`、快照、`cm:ref` 落库等**时序与存储**。
- **本图**：只回答「**拼装给大模型时，数据从哪来、以什么形态进 API**」，省略 Controller、Handler、落盘等阶段。
