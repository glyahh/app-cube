# 会话总结

## 主题概览
- LangGraph4j 工作流编排：`CodeGenWorkflow` 的节点/边、进度回调与 Vue 流式 chunk 透传。
- SSE 流式输出：后端 `Flux<String>` → `ServerSentEvent` → 前端 `EventSource` 持久在线。
- Vue 与 HTML/MULTI_FILE 的流式协议差异：纯文本流 vs JSON 事件流（工具调用/执行）。
- 前端对工具与工作流信息的解析与展示：`[选择工具]` / `[工具调用]` / fenced code block。

## 关键信息
- **`CodeGenWorkflow.createWorkflow(Consumer<String> codeStreamChunkConsumer)` 的用途**
  - 在编译工作流时将 `codeStreamChunkConsumer` 注入 `code_generator` 节点，仅用于 Vue 场景的“原始流式 chunk 透传”。
  - 无参 `createWorkflow()` 等价于 `createWorkflow(null)`，不启用 Vue chunk 透传回调。
- **`WorkflowCodeGeneratorFacade.adaptWorkflowCodeChunk` 的 TOOL_REQUEST 兜底逻辑**
  - 若 `toolManager.getTool(toolName)` 能获取到本地 `BaseTool`：使用工具自定义展示文案。
  - 若获取不到：回退为 `\n\n[选择工具] <toolName或未知工具>\n`，避免 UI 静默缺失。
- **TOOL_EXECUTED 逻辑**
  - 解析 `ToolExecutedMessage`，安全解析 `arguments`（JSON 解析失败时保留 `_rawArguments`）。
  - 若能获取到 `BaseTool`：调用 `tool.generateToolExecutedResult(arguments)` 输出富文本/卡片文案。
  - 否则使用 `fallbackToolExecutedFormatting` 输出统一格式：`[工具调用] <toolName> <relativeFilePath或relativeDirPath或->`。
- **工作流结束时的“最终回显”**
  - `emitGeneratedCodeIfPresent(sink, codeGenTypeEnum, generatedDir)` 读取 `generatedCodeDir` 中的落盘文件并包装为 Markdown fenced code blocks 回显到 SSE。
  - `HTML`：读取 `index.html`；`MULTI_FILE`：遍历目录文件；`VUE`：遍历目录回显文本文件（带过滤规则）。
- **Vue 最终回显可能“不像 HTML/MULTI_FILE 那样明显”的原因**
  - `emitVueGeneratedCode` 存在过滤：忽略目录（`node_modules/dist/build/...`）、单文件大小上限（512KB）、疑似二进制文件跳过。
  - Vue 项目文件数量多、目录深，回显片段多且分散，主观上不如单文件/少文件类型集中。

## 已形成结论
- **Flux 与 SSE 交互模型**
  - 前端通常只发起一次请求（`EventSource` 建立长连接），后端持续推送多个 chunk；并非前端轮询多次请求。
- **结束标记在项目中的体现**
  - 后端 `ChatToGenCodeController.toSseEvent(...)`：将 `Flux<String>` 映射为 `ServerSentEvent.data(chunk)`，并在末尾追加一个自定义 `event("done")` 收尾。
  - 前端 `AppChatView.vue`：既兼容处理 `onmessage` 中的 `[DONE]` / `__END__`，也通过 `addEventListener('done', ...)` 在 done 事件触发后停止流并执行收尾动作。
- **`Consumer<String>` 的本质**
  - `Consumer<String>` 为函数式接口：`accept(String)` 仅消费输入、不返回值；常用于“回调 + 副作用”（推 SSE、写日志、追加 StringBuilder）。
  - “输入 String 输出 String”的变换通常使用 `Function<String, String>` 或 Reactor 的 `.map(...)`。
- **工作流路径与非工作流路径对 Vue 解析的位置差异**
  - 非工作流（老路由）Vue：主要由 `JsonMessageStreamHandler` 解析 JSON 协议并重组为可展示/可落库文本。
  - 工作流（workflow 路由）Vue：在 `WorkflowCodeGeneratorFacade` 中对实时 chunk 做适配（`adaptWorkflowCodeChunk`），通过 `codeStreamChunkConsumer` 推给 SSE；`WorkflowTextStreamHandler` 主要负责拼接与落库。

## 待执行事项
- 若需要增强 Vue 最终回显“全量可见性”，可评估调整：
  - `VUE_ECHO_MAX_FILE_BYTES` 阈值；
  - `VUE_ECHO_IGNORED_DIR_NAMES` 忽略目录集合；
  - `isLikelyTextFile` 判定逻辑；
  - 或增加“生成产物下载/文件列表面板”等替代交互以避免长文本刷屏。

## 补充说明
- `progress` 与 `codeStreamChunkConsumer` 是两条独立回调：
  - `progress` 用于工作流步骤进度提示（`[workflow] 第 N 步完成：...`）。
  - `codeStreamChunkConsumer` 用于 Vue 代码生成阶段的实时 chunk 透传与展示适配。

