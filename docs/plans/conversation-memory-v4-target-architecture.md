# 会话记忆 V4：目标架构（To-Be / 完全完工）

> 类型：设计与实施方案；目标架构、阶段状态不等于当前交付结论。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

面向读者：实现 memory-v4 收尾、治理 token 与「磁盘为准」编辑链路的开发同学。

**文档性质**：在 [conversation-memory-v4-current-architecture.md](conversation-memory-v4-current-architecture.md) 基础上，描述**完全接线后**的目标态；每条链路标明数据形态与是否进入 LLM。

**设计原则（四条）**

1. **聊天里只留短 AI + tool 记录**（不重复整页源码）。
2. **代码以磁盘为准**（readFile / modifyFile / writeFile）。
3. **state 摘要 + ref 按需片段**进入 ChatMemory（预算内）。
4. **page 仅作读盘 IO 缓存**（变更即失效）。

---

## 1. 目标分层

| 层级 | 载体 | 真相源 | 目标职责 |
|------|------|--------|----------|
| 对话审计 | MySQL `chat_history` | 是 | 全量可回溯；进模型前强压缩 |
| 对话窗口 | Redis ChatMemory | 否 | **瘦上下文**：短 USER/AI、TOOL、摘要 SystemMessage、inject 片段 |
| 工程索引 | `conversation_memory_state` + `cm:state` | 是 | summary + changedFiles + 指针；**摘要注入 LLM** |
| 快照 | `snapshot_history` | 是 | manifest diff 驱动 changedFiles |
| 大文件冷库 | `conversation_memory_ref` + `cm:ref` | 是 | 全文归档；**按 filePath/refId 分页读回** |
| 读盘缓存 | `cm:page` | 否 | 文件头缓存；**changed 时 DEL** |
| 源码 | `temp/code_output/...` | 是 | 唯一完整源码真相 |

---

## 2. 目标：进入 LLM 的唯一拼装规则

```mermaid
flowchart TB
  subgraph LLM_In["进入大模型 API（目标态）"]
    P0["@SystemMessage\n生成规范 Prompt"]
    P1["SystemMessage\n[memory_summary] state 软/硬摘要"]
    P2["SystemMessage\n[memory_index] changedFiles + entry 提示"]
    P3["SystemMessage\n[memory_inject] 磁盘/ref 预算内片段"]
    P4["Redis ChatMemory\n短 USER / 短 AI / TOOL 记录"]
    P5["@UserMessage 本轮用户输入"]
  end

  subgraph NeverWhole["禁止整包进入"]
    N1["chat_history 全表"]
    N2["ref 全文默认"]
    N3["cm:page key"]
    N4["重复 ```html 整页在 AI 行"]
  end

  P0 --> API["大模型 API"]
  P1 --> API
  P2 --> API
  P3 --> API
  P4 --> API
  P5 --> API

  N1 -->|"仅压缩后进入 P4"| P4
  N2 -->|"仅 readRefSlice 进入 P3"| P3
  N3 -->|"仅内容经 P3"| P3
  N4 -.->|"落库策略禁止"| X["淘汰"]
```

---

## 3. 目标总览：一轮对话端到端

```mermaid
flowchart TB
  subgraph Phase1["阶段 1 — 请求入口"]
    U["用户消息"] --> ENTRY["ChatToGenCodeImpl"]
    ENTRY --> UDB["MySQL: INSERT chat_history USER"]
    UDB --> SUM["trySummarizeOldestRoundsIfNeeded\n（保持：控轮数）"]
  end

  subgraph Phase2["阶段 2 — 记忆组装（每轮执行）"]
    SUM --> REFRESH["MemoryRefreshPolicy.refresh(appId)\n见 §4"]
    REFRESH --> BUILD["组装瘦 ChatMemory"]
    BUILD --> B1["从 DB 加载历史 → 短 AI + tool"]
    BUILD --> B2["读 cm:state → 注入 summary + index"]
    BUILD --> B3["InjectPipeline：磁盘/ref 片段"]
    BUILD --> B4["失效/跳过 stale cm:page"]
  end

  subgraph Phase3["阶段 3 — 生成"]
    BUILD --> GEN["AiCodeGeneratorFacade 流式"]
    GEN --> TOOLS["工具读写磁盘"]
    TOOLS --> DISK["code_output 源码树"]
    GEN --> LLM["大模型 API"]
  end

  subgraph Phase4["阶段 4 — 持久化"]
    GEN --> SSE["StreamHandlerExecutor"]
    SSE --> AIDB["MySQL: AI 短说明\n（工具轮禁止整页 html）"]
    SSE --> REDIS["Redis ChatMemory 追加短 AI + tool"]
    SSE --> FIN["onRoundCompleted"]
    FIN --> MANI["manifest diff → state"]
    FIN --> REFARCH["大 changed → ref DB + cm:ref"]
    FIN --> INV["DEL cm:page 对应 changed 路径"]
    FIN --> STATE["SET cm:state"]
  end

  Phase1 --> Phase2 --> Phase3 --> Phase4
  STATE -.->|"下轮 Phase2 读取"| REFRESH
  INV -.->|"保证 page 新鲜"| B3
```

---

## 4. 目标：MemoryRefreshPolicy（取代「仅新建 Service 才 inject」）

| 策略 | 触发条件 | 动作 |
|------|----------|------|
| `FULL_REBUILD` | Redis ChatMemory 空；或 `memoryGeneration` 版本落后 | `turnHistoryToMemory` + 全量 `InjectPipeline` |
| `LIGHT_REFRESH` | 每轮生成前；或 `onRoundCompleted` 后 | 仅更新 summary/index inject；刷新 changed 文件头片段 |
| `CACHE_ONLY` | 单轮内多次工具调用 | 不重复 inject；依赖磁盘 + tool 结果 |

```mermaid
sequenceDiagram
  autonumber
  participant Entry as ChatToGenCodeImpl
  participant Policy as MemoryRefreshPolicy
  participant CH as ChatHistoryService
  participant CM as ConversationMemoryStateService
  participant R as Redis
  participant Disk as 磁盘

  Entry->>Policy: refresh(appId, codeGenType, roundId)
  Policy->>R: EXISTS ChatMemory(appId)?
  alt 空或版本不一致
    Policy->>CH: turnHistoryToMemory（短 AI 规则）
    Policy->>CM: injectSummaryFromState
    Policy->>CM: injectIndexFromState
    Policy->>CM: InjectPipeline.run（预算内）
  else 每轮轻量
    Policy->>CM: injectSummaryFromState（幂等替换同 tag）
    Policy->>CM: injectChangedFileSlicesOnly
  end
  Note over CM,Disk: readFilePageWithCache：先 DEL 再读若 mtime 变了
  CM->>R: GET/SET cm:page
  CM->>Disk: 读文件头
  CM->>R: merge [memory_inject] / [memory_summary] 进 ChatMemory
```

---

## 5. 目标：InjectPipeline（page / ref / 磁盘）

```mermaid
flowchart LR
  START["InjectPipeline.run(appId)"] --> READSTATE["读 cm:state 或 DB\n→ changedFiles + summaries"]
  READSTATE --> PICK["选文件集合\nentry 优先 > changed > 其余"]
  PICK --> LOOP{"foreach 文件\n剩余 budget > 0"}

  LOOP --> CHECKPAGE["GET cm:page"]
  CHECKPAGE --> STALE{"snapshot mtime\n与 cache 一致？"}
  STALE -->|否| DEL["DEL cm:page"]
  DEL --> READDISK["Disk.read 全文 → 截断 pageSize"]
  STALE -->|是 hit| USEPAGE["使用 page 文本"]
  READDISK --> SETPAGE["SET cm:page TTL"]

  USEPAGE --> NEEDREF{"需要 ref 片段？\n大文件且 header 不足"}
  SETPAGE --> NEEDREF
  NEEDREF -->|是| READREF["GET cm:ref → miss 则 DB\nreadRefSlice(offset, len)"]
  NEEDREF -->|否| INJECT["ChatMemory.add SystemMessage\n[memory_inject]"]
  READREF --> INJECT
  INJECT --> LOOP
```

**ref 读回约定（目标 API）**

- `readRefSlice(appId, refId, offset, maxChars)` → 仅返回片段。
- 默认不注入全文；仅当 Rubric 需要「跨轮恢复」时提高 budget。

---

## 6. 目标：落库与 ChatMemory 瘦身

```mermaid
flowchart TB
  subgraph StreamEnd["流式结束"]
    H["Handler 聚合 AI 输出"] --> D{"本轮是否工具编辑？"}
    D -->|是 modifyFile/writeFile| SHORT["持久化 1~5 行短说明\n+ tool 记录"]
    D -->|否 首轮生成| CODE["允许 fenced html\n但 turnHistory 时旧轮压缩"]
    SHORT --> MYSQL["MySQL chat_history"]
    SHORT --> REDIS["Redis ChatMemory"]
  end

  subgraph Dedup["去重规则"]
    R1["同一文件：磁盘 > ref 片段 > inject\n不重复 ChatMemory 内整页 html"]
    R2["USER 内联片段 > 5000 字时截断\n引导 readFile"]
    R3["[memory_summary] 单条幂等\n重复 refresh 先删后加"]
  end
```

---

## 7. 目标 Redis Key 行为

| Key | 写 | 读 | 失效 |
|-----|----|----|------|
| ChatMemory(`appId`) | 瘦消息 + tagged SystemMessage | 每轮 LLM | 窗口 80 条 + 压缩 |
| `cm:state:{appId}` | onRoundCompleted | 每轮 refresh 读 summary/index | TTL 14d |
| `cm:page:{appId}:{path}:0` | InjectPipeline miss | InjectPipeline | **changedFiles 含 path 时 DEL** |
| `cm:ref:{refId}` | 归档 ≥8KB changed | readRefSlice | TTL 3d + DB 治理 |

---

## 8. 目标与当前差异对照

| 能力 | 当前（As-Is） | 目标（To-Be） |
|------|---------------|---------------|
| state 摘要进 LLM | TODO | `[memory_summary]` SystemMessage |
| ref 进 LLM | 无 | `readRefSlice` → inject |
| page 触发 | 仅新建 Service | 每轮 `LIGHT_REFRESH` + 变更失效 |
| AI 落库 | 可整页 html | 工具轮仅短说明 |
| inject 去重 | 无 | §6 Dedup |
| 源码真相 | 已是磁盘 | 强化 Prompt：禁止重复 echo 全文件 |

---

## 9. 实施里程碑（建议顺序）

```mermaid
gantt
  title 会话记忆 V4 收尾里程碑
  dateFormat YYYY-MM-DD
  section P1 立刻收益
  工具轮短 AI 落库           :p1a, 2026-05-21, 7d
  state 摘要注入             :p1b, after p1a, 5d
  changed 失效 cm:page       :p1c, after p1a, 3d
  section P2 读回
  readRefSlice + inject      :p2a, after p1b, 7d
  MemoryRefreshPolicy 每轮轻量刷新 :p2b, after p1c, 7d
  section P3 治理
  inject 去重与 tag 幂等     :p3a, after p2b, 5d
  可观测：memory 字节/token 指标 :p3b, after p3a, 5d
```

| 阶段 | 交付物 | 验收 |
|------|--------|------|
| P1 | Handler 策略 + `injectSummaryFromState` + page 失效 | 工具编辑后 Redis 无第三份整页 html |
| P2 | `readRefSlice` + `LIGHT_REFRESH` | 缓存命中下轮仍更新 changed 片段 |
| P3 | 去重 + 指标 | 单次请求上下文可观测且低于基线 30%+ |

---

## 10. 目标源码模块（建议新增/调整）

| 模块 | 建议路径 | 职责 |
|------|----------|------|
| `MemoryRefreshPolicy` | `service/memory/MemoryRefreshPolicy.java` | 统一 refresh 策略 |
| `InjectPipeline` | `service/memory/InjectPipeline.java` | page/ref/磁盘注入 |
| `RefSliceReader` | `service/memory/RefSliceReader.java` | ref 分页读回 |
| `PageCacheInvalidator` | `service/memory/PageCacheInvalidator.java` | changed 时 DEL page |
| `AiPersistencePolicy` | `core/handler/AiPersistencePolicy.java` | 工具轮短落库 |

现有类**保留**：`ConversationMemoryStateServiceImpl` 收敛为编排入口，具体步骤委托上述组件。

---

## 11. 相关文档

| 文档 | 说明 |
|------|------|
| [conversation-memory-v4-current-architecture.md](conversation-memory-v4-current-architecture.md) | 当前已实现架构 |
| `learn/会话记忆指南（会话记忆重构V4学习复盘）.md` | 名词与流程科普 |
| `CLAUDE.md` | 仓库级会话记忆配置指针 |

---

*author By glyahh · 目标架构 · 实施时以 Issue/PR 勾选 §9 里程碑*
