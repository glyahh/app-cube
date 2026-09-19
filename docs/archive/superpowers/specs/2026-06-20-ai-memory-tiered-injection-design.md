# AI 记忆分层注入方案设计

## 背景

当前实现中，用户风格（[user_app_style]/[user_answer_style]）和 Loop 技能（[loop_skill]）都混入同一个 UserMessage 字符串，历史 Redis 不区分层级；压缩时完整消息含注入标签，浪费 token；Loop ID 仅 HTTP 参数不落库，重建时丢失。

## 目标

1. 分层管理：会话级（SystemMessage）与轮次级（UserMessage）分离
2. 省 token：风格仅会话级 1 次，不每轮重复
3. 压缩隔离：仅 `<user_original>` 参与 AI 摘要
4. 审计完整：MySQL 存原话 + loop_id
5. 重建保真：shrink + chat_history + 实时风格 + loop_id assemble → renderXml

## 物理注入顺序

```
@SystemMessage 代码生成规范          ← AiServices 框架，每次调用注入（ChatMemory 外）
[memory_policy] / [memory_index] / [memory_file_note]  ← SystemMessage，现有
<inject_prompt> 元说明 + <user_style>  ← SystemMessage，方案 1 每轮热更新
历史 user/ai（shrink 摘要 + 未合并 chat_history）
本轮 <user_original> + <loop_skill>  ← UserMessage（轮次级）
```

## 架构变更

### 1. ChatToGenCodeImpl — 入口调整

- `injectPersonalizationPrompt()` 改为 `injectSessionStyleAsSystemMessage(appId, userId)`
  - 读取最新风格值
  - 构造 `<inject_prompt>` + `<user_style>` XML 块
  - 以 SystemMessage 写入 ChatMemory（更新而非追加，避免重复）
- `injectIfPresent()` 保持后缀注入，但标签格式改为 `<loop_skill loopId="...">...</loop_skill>`
- 发送给 AI 的消息：原始用户消息包裹 `<user_original>...</user_original>`
- 日志打印仍用原始 message，不暴露 XML 标签

### 2. 新增 MemoryInjectAssemblySupport（或类似名称）

职责：组装最终发给 LLM 的 ChatMessage 列表

输入：
- 当前 appId 的 ChatMemory（含历史 + memory_state）
- 当前风格值（实时读）
- 当前用户原话
- 当前 loopId（可选）

输出：renderForLlm(List<ChatMessage>) → 确保顺序正确的消息列表

### 3. ChatHistoryServiceImpl — 压缩隔离

- `trySummarizeOldestRoundsIfNeeded` → 摘要输入仅取 `<user_original>` 纯文本
- `summarizeTwoRoundsWithAi` / `summarizeWithExistingSummary` 的输入剥离 XML 包裹

### 4. MySQL chat_history — 新增 loop_id

- 新增 `loop_id` BIGINT nullable 列
- `addChatMessageAndReturnId` 增加 loopId 参数
- 写入时快照当前 loopId

### 5. Redis ChatMemory 重建 — assemble + renderXml

`ChatHistoryAiMemoryRebuildSupport.rebuildAiChatMemoryFromShrink` 调整：
- 读取 chat_history 时连带新 loop_id 列
- 读取当前 user_personalization（方案 1：实时读）
- assemble 为结构化消息 → renderXml 写入 Redis

### 6. 兼容旧数据

- Redis 中无分层结构或为裸文本 UserMessage → 按「仅 <user_original>」处理
- MySQL 旧行 loop_id=null → 按无 Loop 处理

## 模块拆分

| 模块 | 职责 | 涉及文件 |
|------|------|----------|
| M1 | Style SystemMessage 注入 | ChatToGenCodeImpl, UserPersonalizationServiceImpl |
| M2 | 用户消息 XML 包裹 | ChatToGenCodeImpl, LoopInjectService |
| M3 | loop_id 落库 | ChatHistory entity/Mapper/Service, ChatToGenCodeImpl |
| M4 | 压缩隔离 | ChatHistoryServiceImpl, ChatHistorySchemaMigrationSupport |
| M5 | 重建 assemble + renderXml | ChatHistoryAiMemoryRebuildSupport, 新增 MemoryAssembleSupport |
| M6 | 兼容层 | 各模块兼容 null 旧数据 |

## 数据流（单轮请求）

```
User sends message
  ↓
① ChatToGenCodeImpl
  ├─ MySQL: insert chat_history (原话 + loop_id)
  ├─ Redis: read user_personalization (最新风格)
  ├─ ChatMemory: upsert SystemMessage(<inject_prompt> + <user_style>)
  ├─ 本轮 UserMessage: wrap <user_original>message</user_original>
  ├─ 若有 loop: append <loop_skill loopId="...">...</loop_skill>
  └─ 调用 AiServices
  ↓
② AiCodeGeneratorFacade 流式生成
  ↓
③ 生成结束后 trySummarizeOldestRoundsIfNeeded
    - 摘要输入仅 user_original（去 XML）
    - 成功 → rebuildAiChatMemoryFromShrink
