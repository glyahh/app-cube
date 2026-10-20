# 历史计划：Java 会话记忆 V4

> 本文保留原设计记录，不作为当前运行架构或后续开发主线。当前主配置为 PostgreSQL + Redis，生成执行由 Java 接入层与 Python Agent 协作完成。现状、差距与第一阶段验收见 [当前项目状态与开发主线](../../status/current.md)，接口见 [Java ↔ Python 契约](../../contracts/agent.md)。下文的 MySQL、LangChain4j 和旧类名按历史上下文理解；“上线”不代表当前版本已验证。

### 标题
会话记忆重构 V4（V3 定稿 + E/F/G 上线口径）

### 摘要
- 保持 V3 方向不变：落盘真相源、memory 仅索引/摘要/短 buffer、超长引用化、按需分页 readFile、MySQL 真相源 + Redis 热缓存。
- 在 V3 基础上补齐 E/F/G：可观测性指标固化、失败降级与主链路隔离、数据治理清理策略。

### 关键实现（含 A~G）
1. Round 与触发链路（A + 既有）
- `roundId` 唯一来源：本轮 USER 入库后的 `chat_history.id`。
- `roundId` 透传：`ChatToGenCodeImpl -> StreamHandlerExecutor -> onRoundCompleted`。
- Round 完成 Hook 统一在 `StreamHandlerExecutor#doFinally`，`AtomicBoolean` 保证一轮只触发一次，覆盖 complete/error/cancel。

2. onRoundCompleted 最终态与隔离（B + F）
- 生成 manifest 前做目录稳定性检查：存在性 + mtime/文件计数稳定性，2~3 次短重试（100~300ms）。
- `onRoundCompleted` 全流程 `try/catch` 自吞异常：只告警，不中断 SSE/主生成链路。
- 存储降级：
  - MySQL 失败：记录错误并跳过本次状态持久化（下轮补偿）。
  - Redis 失败：不影响 MySQL 真相，允许后续 DB 回填重建。
  - 局部成功允许提交，不做“全有或全无”强事务绑定主链路。

3. 注入职责与分页细节（C + 既有）
- `turnHistoryToMemory` 仅做 `chat_history -> Redis` 重建。
- 新增独立注入入口：`loadConversationMemoryStateAndInject(...)`（或同义命名）。
- 代码细节按需 readFile（不在服务层拼大 prompt）：
  - 触发条件：编辑意图/changedFiles 命中/文件名命中/报错定位命中。
  - 预算双阈：字符预算 + token 近似预算（`chars/4`），取更小者。
  - 顺序：入口/配置/路由/主文件 -> changedFiles -> 其余补齐。

4. 快照与差异（既有）
- `snapshotManifestJson` 仅存 `path/hash/size/mtime/lang`，不存全文。
- 白名单仅文本代码文件；忽略目录含 `node_modules/.git/dist/target/temp/build/coverage/.idea/.vscode`。
- `changedFiles` 基于前后 manifest diff；无上一快照兜底 `changedFiles=entryFiles` 或空。

5. 摘要与历史瘦身职责隔离（既有）
- `conversation_memory_state` 双摘要：由 `onRoundCompleted` 软/硬阈值滚动维护（模型用）。
- `trySummarizeOldestRoundsIfNeeded`：仅 `chat_history` 瘦身与导出可见，不写 memory_state。
- 两者禁止交叉写源。

6. 可观测性字段固化（E）
- `onRoundCompleted` 必须记录结构化日志/埋点字段：
  - `appId, roundId, snapshotId, manifestFilesCount, changedFilesCount, bufferChars, summarizeLevel(soft|hard|none), refArchivedCount, elapsedMs, redisHit/dbFallback`
- 指标用途：
  - 识别 manifest 扫描慢、ref 膨胀、摘要频繁触发、Redis 抖动导致回源等问题。
- 建议增加告警阈值：
  - `elapsedMs` 超阈、`refArchivedCount` 异常增长、`summarizeLevel=hard` 频率过高。

7. 数据治理与清理策略（G）
- `conversation_memory_ref` 清理策略（至少一条强规则）：
  - 按 `appId` 保留最近 `N` 条，或最近 `X` 天，或总字节上限滚动清理最旧。
- 推荐默认：
  - 保留最近 30 天 + 每 app 最近 500 条 + 每 app ref 总量上限（如 200MB）三重约束，任一触发即清理最旧。
- 若保留 `snapshot_history`（可选），同步采用相同保留口径，防表无限增长。
- 清理任务独立定时作业，失败不影响主链路。

8. Redis TTL 固化（D）
- `cm:state:{appId}`：7~30 天（默认 14 天）。
- `cm:ref:{refId}`：1~7 天（默认 3 天）。
- Redis 过期后允许 DB 回填恢复。
- inject 文件头直读磁盘（已移除 `cm:page:*`）。

### 测试与验收
- 并发与串轮：同 app 并发请求 roundId 不串绑。
- 主链路隔离：模拟 MySQL/Redis 故障，SSE 不中断，生成结果仍返回。
- 目录稳定性：模拟落盘延迟，重试后 manifest 正确。
- 预算控制：高密度文本触发 token 预算截断。
- 可观测性：日志/埋点字段完整，`summarizeLevel` 与 ref 计数符合预期。
- 清理任务：按 N/天数/字节上限触发回收，且不影响查询恢复。

### 默认假设
- 轮数仍按 USER 条数统计，工具调用不单独计轮。
- 完整代码不入 chat_history；只通过快照索引 + 按需分页 readFile让模型获取细节。
