# 修复 `/gen/code` 工具请求时机漂移

## 摘要
当前 `/gen/code` 下 `[选择工具]` 偶发晚出、甚至和正式工具输出挤在一起的主因不在前端，而在后端把“模型已决定使用工具”的最早信号吞晚了。

当前诊断结论：
- 底层 `OpenAiStreamingChatModel` 已支持“只要流里先出现 tool `name` 或 `id`，就立刻回调 `onPartialToolExecutionRequest`”。
- `/gen/code` 的两条适配链路都没有直接利用这个最早时机：
  - `HTML/MULTI_FILE` 旧文本链路把 request 发射和 `writeFile` synthetic executed 绑在同一个 helper 里，且要求 `toolCallId != null` 才发。
  - `VUE` JSON 链路同样要求 `toolCallId != null` 才把 `ToolRequestMessage` 推给下游。
- `JsonMessageStreamHandler` 还会直接丢弃 `id == null` 的 `TOOL_REQUEST`，导致即使上游愿意早发，Vue 链路也吃不到。
- 前端 `AppChatView` 与 `UserFacingOutputSanitizer` 不是主因：它们当前都能保留并渲染独立的 `[选择工具]` 文本/协议片段。第一轮修复不需要改前端。

## 关键改动
### 1. 只修 `/gen/code`，不动 `/gen/workflow`
- 优先收敛 `ChatToGenCodeImpl -> AiCodeGeneratorFacade -> StreamHandlerExecutor` 这条链。
- `workflow` 路径保持现状，避免混入第二套时序问题。

### 2. 先把 `tool_request` 与 `tool_executed` 解耦
- 在 `AiCodeGeneratorFacade` 内新增一层“请求发射判定”逻辑，分别供：
  - `wireHtmlMultiFileTokenStream(...)`
  - `adaptVueTokenStream(...)`
- `LegacyHtmlToolStreamSupport` 不再负责“是否该发 request”的决策，只保留：
  - `writeFile` 参数累计
  - `writeFile` synthetic executed 合成
  - fallback executed 文本格式化
- 默认规则：
  - 只要 `ToolExecutionRequest` 已有 `name` 或 `id`，就允许发第一张 request。
  - `tool_executed` 仍按现有逻辑，`writeFile` 可 synthetic，其他工具保持 native `onToolExecuted`。

### 3. `/gen/code` 统一采用“早发 request，晚发 executed”
- `HTML/MULTI_FILE`：
  - 在 `onPartialToolExecutionRequest` 里优先判定是否需要发 `[选择工具]`。
  - 发完后再进入 `LegacyHtmlToolStreamSupport` 的 `writeFile` synthetic executed 逻辑。
  - `onCompleteToolExecutionRequest` 仅作为补发 request 的兜底，不再承担“首发 request”的主要职责。
- `VUE`：
  - 在 `adaptVueTokenStream(...)` 里同样先发 `ToolRequestMessage`，再累计 `writeFile` 参数、再决定是否发 synthetic executed。
  - `onCompleteToolExecutionRequest` 只补发漏掉的 request。

### 4. 允许“无 id 的早期 request”，但去重必须稳定
- 采用双键去重：
  - 已解析到 `toolCallId` 时，主键用 `id:{toolCallId}`
  - `toolCallId` 还没到时，早期键用 `early:{index}:{toolName}`
- 在同一条流内维护：
  - `Map<Integer, String> earlyRequestKeyByIndex`
  - `Set<String> emittedRequestKeys`
- 行为固定为：
  - partial 先到 `name` 无 `id`：立刻发 request，并记录 `early:{index}:{toolName}`
  - 后续同 index 又来了更多 partial：不重复发
  - complete/native 阶段终于拿到真实 `id`：只登记 `id:{toolCallId}`，如果该 index 早已发过 early request，则不再补第二张卡片
- 这套去重只在单次流内生效，不改数据库结构，不改对话历史 schema。

### 5. Vue JSON 链路必须放开 `id == null` 的 request
- `ToolRequestMessage` 保持现有字段，不新增 schema。
- `JsonMessageStreamHandler` 修改为：
  - `TOOL_REQUEST` 不再以“`toolId != null` 才能显示”为前提。
  - 如果 `id` 为空，只要 `name` 非空，就直接生成一次 `[选择工具] xxx` 文本。
  - 该 handler 内部保留最小去重：
    - `id` 有值时按 `id` 去重
    - `id` 为空时不再做二次去重，依赖 `adaptVueTokenStream(...)` 上游已经完成去重
- 这样 Vue 链路就能真正吃到“模型首次决定使用工具”的早期 request。

### 6. 前端第一轮不改
- `AppChatView.vue` 现有逻辑已能把独立 `[选择工具]` 渲染成 `tool_request` segment。
- `UserFacingOutputSanitizer` 已明确保留 `[选择工具]` / `[工具调用]` 协议行。
- 第一轮目标只修“什么时候发 request”，不碰“卡片内容是否流式”。

## 需要修改的接口/内部行为
- `LegacyHtmlToolStreamSupport.emitLegacyHtmlToolStreamChunk(...)`
  - 调整职责，去掉 request 发射决策。
  - 如需最小改动，可拆成：
    - `emitLegacyToolRequestIfNeeded(...)` 放回 `AiCodeGeneratorFacade`
    - `emitLegacyWriteFileExecutedIfReady(...)` 留在 `LegacyHtmlToolStreamSupport`
- `AiCodeGeneratorFacade`
  - 新增统一私有 helper，负责 `/gen/code` 两条链路的 request 早发与去重。
- `JsonMessageStreamHandler`
  - 放开 `id == null` 的 `TOOL_REQUEST` 显示逻辑。
- 无公共 HTTP API 变化；SSE 仍是现有 `text/event-stream`，只是 `[选择工具]` 会更早、更稳定地单独作为一条 chunk 出现。

## 测试方案
### 单测/回归
- Vue 链路：
  - partial 只到 `name`、`id` 为空时，必须先产出一条 `ToolRequestMessage`，且最终 SSE 文本里 `[选择工具]` 出现在任何 `[工具调用]` 之前。
  - 后续 complete 才拿到 `id` 时，不得补出第二张相同 request 卡片。
- HTML/MULTI_FILE 链路：
  - partial 先到 `name`、后到 arguments 时，必须先 `sink.next("[选择工具] ...")`，再出现 `writeFile` synthetic executed。
  - “只有 complete、没有 partial arguments”的兼容场景，仍能至少补出一张 request。
- 非 `writeFile` 工具：
  - request 早发；
  - executed 仍只在 native `onToolExecuted` 时输出；
  - 不允许 request 丢失或重复。
- 顺序测试：
  - 同一工具在一次调用中，request 与 executed 必须是两个独立 Flux 元素，不允许拼成同一个字符串 chunk。

### 手工验证
- `/gen/code` + `VUE` 首轮生成：
  - 打开浏览器 Network，确认在正式 `[工具调用] 写入文件 ...` 之前先看到独立 `[选择工具] 写入文件`。
- `/gen/code` + `HTML/MULTI_FILE` 编辑轮：
  - 触发 `modifyFile` / `readFile` / `deleteFile`，确认 request 总是先出现。
- 长文本 `writeFile`：
  - 即使 arguments 分多帧才完整，request 也要在第一帧出现，不依赖 arguments 完整。

## 假设与默认决策
- 保持现有文本协议，不引入新前端协议面。
- 本轮只修“request 卡片出现时机”，不改 executed 卡片内容流式策略。
- `/gen/workflow` 不纳入首批修复，避免扩大回归面。
- 允许无 `toolCallId` 的早期 request 先显示；真实 `id` 到达后只用于后端流内去重，不再二次展示。
