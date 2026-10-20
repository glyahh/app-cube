# 会话记忆完整架构图

> 类型：技术说明与学习复盘；旧架构、类名和数字按原记录时点理解。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

本文用 **Mermaid flowchart** 描述从 HTTP 入口到后台治理的**端到端会话记忆链路**（普通生成与 Workflow 共用记忆路径）。在支持 Mermaid 的预览中（VS Code / Cursor、GitHub）可直接渲染。

> 关联文档：`learn/会话记忆精简图.md`（仅「进不进 LLM API」视角）、`learn/会话记忆指南（会话记忆重构V4学习复盘）.md`、`learn/数据库表结构图-会话记忆与核心域.md`

---

## 全流程（T0 → T5）

```mermaid
flowchart TB
  subgraph T0["阶段 0：HTTP 入口"]
    U[用户发消息] --> CTL[ChatToGenCodeController]
    CTL --> IMPL{普通 / Workflow}
    IMPL -->|普通| NORM[ChatToGenCodeImpl.chatToGenCode]
    IMPL -->|Workflow| WF[ChatToGenCodeImpl.chatToGenCodeByWorkflow]
  end

  subgraph T1["阶段 1：入库与压缩（生成前，同步）"]
    NORM --> LOCK[首轮锁 + 安全审查]
    WF --> LOCK
    LOCK --> USERDB[(MySQL chat_history\nUSER 行 → roundId)]
    USERDB --> SUM{trySummarizeOldestRoundsIfNeeded\n轮数 > 3 ?}
    SUM -->|是| MERGE[合并最早 2 轮为摘要\n逻辑删原文 + 写 DB]
    MERGE --> REBUILD[可选：单独 rebuild Memory\n仅 turnHistoryToMemory]
    SUM -->|否| SKIP1[跳过]
    REBUILD --> GEN
    SKIP1 --> GEN
  end

  subgraph T2["阶段 2：创建 AI 服务 + 预加载注入（生成前）"]
    GEN[Facade 流式生成] --> GETSVC[getAiCodeGeneratorService\nCaffeine 缓存 ?]
    GETSVC -->|缓存 miss 或 Redis 空| CREATE[createAiCodeGeneratorServiceForEachApp]
    GETSVC -->|缓存 hit| HIT[复用已有 Service\n+ compactMemoryMessagesIfNeeded]
    CREATE --> MEMNEW[新建 MessageWindowChatMemory\nid=appId]
    MEMNEW --> PRELOAD[ChatHistoryService\n.loadConversationMemoryStateAndInject]
    
    PRELOAD --> H1[① turnHistoryToMemory]
    H1 --> H1A[MySQL 倒序取 limit 1,40\n跳过「刚写入」的最新 USER]
    H1A --> H1B[反转 + clear Redis ChatMemory]
    H1B --> H1C[UserMessage / AiMessage 写入\nRedis chatMemoryStore]
    H1C --> H1D[过滤/压缩超长 AI 行\n跳过与系统 Prompt 相同的 AI 行]

    PRELOAD --> H2[② ConversationMemoryStateService\n.loadConversationMemoryStateAndInject]
    H2 --> H2A[读 cm:state 或 conversation_memory_state\n含 changedFilesJson / 摘要]
    H2A --> H2B[扫磁盘项目目录\ncollectInjectCandidates 三档优先级]
    H2B --> H2C{预算内逐个文件}
    H2C --> PAGE[readFilePageHead\n读盘全文→截前 10000 字]
    PAGE --> INJ[SystemMessage 注入 ChatMemory\nmemory_inject 前缀]
    INJ --> H2C

    HIT --> STREAM
    CREATE --> BIND[AiServices 绑定 chatMemory\n+ 工具集 / Guardrail]
    BIND --> STREAM
  end

  subgraph T3["阶段 3：SSE 流式生成（进行中）"]
    STREAM[StreamHandlerExecutor.doExecute] --> HAND{Handler}
    HAND -->|HTML/MULTI| SIMPLE[SimpleTextStreamHandler]
    HAND -->|VUE| JSON[JsonMessageStreamHandler]
    HAND -->|Workflow| WFH[WorkflowTextStreamHandler]
    SIMPLE --> SSE[SSE 推前端 + 落盘代码]
    JSON --> SSE
    WFH --> SSE
    SSE --> AIDB[(MySQL chat_history\nAI 回复入库)]
  end

  subgraph T4["阶段 4：轮次收口（生成后，doFinally 一次）"]
    SSE --> FIN[Flux.doFinally\ncomplete/error/cancel]
    FIN --> ORC[ChatHistoryService.onRoundCompleted]
    ORC --> MEMW[ConversationMemoryStateService.onRoundCompleted]
    MEMW --> STABLE[目录稳定性重试]
    STABLE --> MANI[buildManifest + diff → changedFiles]
    MANI --> SNAP[(MySQL snapshot_history)]
    MANI --> STATE[(MySQL conversation_memory_state\n+ changedFilesJson)]
    MANW2[≥8000 字的 changed 文件] --> REFDB[(MySQL conversation_memory_ref 全文)]
    REFDB --> REFR[(Redis cm:ref:refId\nTTL 3d)]
    STATE --> STATER[(Redis cm:state:appId\nTTL 14d)]
    NOTE_REF[TODO: ref 尚未读回注入模型]
  end

  subgraph T5["阶段 5：后台治理（异步定时）"]
    CRON["@Scheduled 每 30min"] --> CLEAN[cleanupMemoryRefsAndSnapshots\n按条数/天数/字节删旧 ref]
  end

  T0 --> T1 --> T2 --> T3 --> T4
  T4 -.->|下一轮 T2 读取| T2
  T5 -.-> REFDB
```

---

## 阶段速查

| 阶段 | 时机 | 核心职责 |
|------|------|----------|
| T0 | HTTP 请求 | 路由普通 / Workflow 生成入口 |
| T1 | 生成前（同步） | USER 入库、`roundId`、超 3 轮合并最早 2 轮摘要 |
| T2 | 生成前 | Caffeine/Redis 复用或新建 Service；`turnHistoryToMemory` + 状态/磁盘注入 |
| T3 | 生成中 | SSE 流式输出、代码落盘、AI 行入库 |
| T4 | 生成后（`doFinally`） | manifest/diff、snapshot/state/ref 落库与 Redis 热缓存 |
| T5 | 定时异步 | 清理过期 ref / snapshot，控制存储体量 |

---

## 存储分工（与图一致）

| 介质 | 典型 Key / 表 | 作用 |
|------|----------------|------|
| MySQL `chat_history` | 每轮 USER/AI | 对话真相源、`roundId` |
| MySQL `conversation_memory_state` | 每 `appId` 一行 | 摘要、`changedFilesJson`、最新轮次指针 |
| MySQL `snapshot_history` | 每轮 manifest | 项目文件清单快照 |
| MySQL `conversation_memory_ref` | 大文件全文 | ≥8000 字变更文件的 ref 归档 |
| Redis `chatMemoryStore` | LangChain4j ChatMemory | 近期 User/Ai 消息窗口 |
| Redis `cm:state:{appId}` | TTL 14d | 状态热读 |
| Redis `cm:ref:{refId}` | TTL 3d | 大文件 ref 热读（**注入 TODO**） |
| 磁盘 `temp/code_output/...` | 生成项目目录 | 注入候选与 manifest diff 来源 |
