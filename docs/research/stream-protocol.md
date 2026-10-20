# Coding Agent 流式协议选型：SSE 还是 WebSocket

> 类型：技术调研；候选方案与资料核对不代表已实施。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

调研 2026-09。对象：opencode、Codex CLI、Claude Code、Hermes。

## 结论

默认 SSE。推翻它的只有一条：**客户端要不要在流进行中往服务端发数据？**

- 不要（发一次 prompt，等服务端推完）→ SSE
- 要（中途打断、切模型、审批工具调用、多端同步）→ WebSocket

四家里三家主力仍是 SSE。只有 Codex CLI 转向 WebSocket，为省多轮工具调用间的反复握手。

## 四家实际用什么

| Agent | 传输 | 细节 |
|---|---|---|
| Claude Code（CLI 本地） | 不走网络 | 事件经 `AsyncGenerator` 直接喂 Ink 渲染层 |
| Claude Code（云端/web） | 三套传输层，优先 WebSocket，可降级 SSE | 见下 |
| Codex CLI | 2026-04 起默认 WebSocket | `wss://.../v1/responses`，握手失败回退 HTTP/SSE |
| OpenCode | SSE | `GET /event`，10 秒心跳 |
| Hermes | 核心 SSE，网关/Studio 用 WebSocket | 两段分离 |

### Claude Code

CLI 版没有传输层问题，事件直接进渲染管线。

云端那套（来源是逆向分析，非官方文档）三套实现，按环境变量和 URL 协议选：

- `SSETransport`：SSE 读 + HTTP POST 写。半双工。45 秒存活检测，序号 + `Last-Event-ID` 断点续传。
- `HybridTransport`：WebSocket 读 + HTTP POST 写。出站事件串行化，防重试风暴；`stream_event` 增量缓冲 100ms。
- `WebSocketTransport`：全双工。指数退避重连、ping/pong 心跳、缓冲最近 1000 条、睡眠唤醒检测。

Bridge 协议层优先 WebSocket，拿不到降级 SSE，运行时可切。

它的反向控制通道值得注意：服务端发 `control_request`（初始化、`set_model`、`set_max_thinking_tokens`、`set_permission_mode`、中断），客户端必须及时回 `control_response`，否则约 10 到 14 秒后连接被服务端杀掉。这类双向需求正是 SSE 别扭处。

### Codex CLI

转折点 2026 年 4 月。之前每轮一个 HTTP + SSE，之后改成常驻 WebSocket，内部叫 `responses_websocket`，会话级 `ModelClient` 挂 size-1 连接池复用同一条 socket。v0.133.0（PR openai/codex#13592，3 月 18 日合入）成为内置 OpenAI provider 首选。

官方数字：20 轮以上工具调用的 rollout 端到端快约 40%。Cline 自测 39%，Vercel 约 40%。

WebSocket 挂了会重试再降级 HTTP/SSE，用户看到 `Falling back from WebSockets to HTTP(S) transport`。但有报告说 v0.133.0 取消回退只走 WebSocket——与其他来源冲突，按版本相关说法看待，别当定论。

强制走 SSE：`supports_websockets = false` 在内置 provider 上不一定生效（#13103），可靠做法是自定义 provider 指向同一 endpoint：

```toml
[model_providers.openai_https]
name = "OpenAI (HTTPS only)"
base_url = "https://api.openai.com/v1"
wire_api = "responses"
env_key = "OPENAI_API_KEY"
supports_websockets = false

model = "openai_https/gpt-5.5"
```

### OpenCode

`opencode serve` 跑 Hono + Bun 无头 HTTP 服务，事件流走 SSE。

```bash
curl -N http://localhost:4096/global/event
```

连接先收 `server.connected`，之后是总线上各类事件：`message.updated`、`message.part.delta`、`session.idle`、`session.error`、`permission.asked`、`server.heartbeat`。心跳 10 秒一次，idle 超时关掉，连接可一直挂。响应头带 `X-Accel-Buffering: no` 防代理缓冲。

写入路径分离：阻塞用 `POST /session/{id}/message`，异步用 `POST /session/{id}/prompt_async`（立刻返 204），进度全从事件流读。客户端惯例是**先开 `GET /event` 再发 prompt_async**，不漏事件。

### Hermes

分两段，别混：

- 核心 agent 到模型：仍 HTTP SSE（`responses.stream()`），含 `codex_responses` 模式。Issue [#6057](https://github.com/NousResearch/hermes-agent/issues/6057) 在申请加 WebSocket，动机同 Codex——用 `previous_response_id` 只发增量，省每轮完整 TLS 握手。
- 对外集成三条路。ACP 走 stdio 上 JSON-RPC；TUI gateway 走 stdio 或 WebSocket（`tui_gateway/ws.py`）；API server 走 HTTP + SSE（`GET /v1/runs/{id}/events`）。
- Studio / 桌面端：WebSocket 网关，事件分 `chat`（`delta`/`final`/`aborted`/`error`）和 `agent` 两类。

## 为什么 SSE 是默认

LLM token 流的形状就是「请求一次，服务端单向推」，正是 SSE 的设计目标。

- **无协议升级**。普通 HTTP，Nginx、CDN、API 网关、Serverless 全原生支持。WebSocket 要 `Upgrade` 握手，中间任一跳不支持就废。
- **重连免费**。浏览器 `EventSource` 自己重连，配 `Last-Event-ID` 还能续。
- **curl 就能调试**。WebSocket 得掏 `wscat` 之类。
- **事件类型贴合 agent 执行轨迹**。`thinking`、`tool_call`、`result`、`done` 直接映射成 event 名。
- **无状态好扩容**。多实例不需要粘性会话。

控制指令用「双通道」：中断、重试走单独 REST POST，用 `task_id` / `conversation_id` 关联。Claude Code 云端版、OpenCode 都这么干。

## SSE 的坑

1. **静默断连**。OpenCode 踩过两次。[#28729](https://github.com/anomalyco/opencode/issues/28729)：`eventData` 序列化抛错（BigInt 或循环引用让 `JSON.stringify` 失败）打断 Effect-TS 流，Bun 取消 `ReadableStream` 时没调 `controller.close()`，也没发 TCP FIN，客户端 `reader.read()` 永久挂住。[#26866](https://github.com/anomalyco/opencode/issues/26866)：v1.14.48 回归，`/event` 只发 `server.connected` 然后不转任何事件，只能降级 v1.14.40。
2. **HTTP/1.1 下每域名 6 条连接**。HTTP/2 无此限制。多标签页要注意。
3. **代理缓冲**。Nginx 默认缓冲，流会卡。要显式配 `X-Accel-Buffering: no` 和 `proxy_buffering off`。
4. **只能传 UTF-8 文本**。二进制得 Base64，体积涨三分之一。
5. **认证麻烦**。原生 `EventSource` 不能带自定义 header，塞不了 JWT。绕法是改 `fetch` + `ReadableStream` 手动解析 SSE 字节流。
6. **没有会话身份**。连接一断状态就没了，多设备分发无从谈起。

## WebSocket 的坑

1. **有状态**。扩容要么粘性会话，要么上 Redis pub/sub 跨实例广播。
2. **不友好 Serverless**。函数回收连接就断。
3. **心跳、重连、消息顺序全自己写**。
4. **部分企业防火墙拦 `ws://`**。有团队把原型从 SSE 换 WebSocket，三天后回滚，理由是「负载均衡器扛不住，重连逻辑雪球越滚越大，可观测性直接没了」。
5. **Codex 实测的运维代价**：
   - WebSocket 路径**不读** `HTTP_PROXY` / `HTTPS_PROXY` / `WS_PROXY` / `WSS_PROXY`，直连 TCP/TLS（`tokio_tungstenite::connect_async_tls_with_config`），`respect_system_proxy` 只管 HTTP 客户端。
   - Windows/WSL 上先经历 5 次各 15 秒重试才降级 HTTP，用户看到启动卡 75 秒。
   - 很多网关只注册 `POST /v1/responses`，WebSocket 升级请求是带 `Upgrade` 头的 `GET`，直接 405。用 Cloudflare / nginx 兜要保住升级头，还要 `proxy_buffering off`。

## 生态动向（2026）

- **MCP** 2026 年 3 月版规范弃用 HTTP + SSE，换 Streamable HTTP：无状态、session ID 做恢复、按需升级 SSE、兼容 Serverless。
- **Vercel AI SDK** 弃原 SSE transport，改可插拔 `ChatTransport` 接口。
- **TanStack AI** 引入 `ConnectionAdapter`。
- **AG-UI** 从一开始就传输无关。
- 出现「durable session」层，架在传输之上。代表：Ably AI Transport、ElectricSQL Durable Streams、Upstash Redis Streams、Convex。解决 SSE 核心短板：流可从最后确认 offset 续传、多设备分发、双向交互、在线状态。

方向是传输层抽象化 + 上层补会话持久化，不是简单二选一。

## 对本项目的建议

当前管线「对话 → 合成 → 交付」已在用 SSE，选择是对的，别动：

- 客户端只在开头（发 prompt）和结尾（发停止）产生输入，中间纯接收。
- 制品分段落盘（Parser 解析 → Saver 写 `temp/code_output/`），天然按事件编号对应，断线后按 `Last-Event-ID` 重放即可，服务端不用保会话状态。
- 多实例无状态，SSE 直接受益。

真要上就补三件：

1. **心跳**。抄 OpenCode，10 秒一个 `heartbeat` 事件，idle 超时关掉。
2. **显式关闭**。序列化包 try/catch，出错也保证发终止块，别重演 OpenCode #28729 挂死。
3. **`X-Accel-Buffering: no`**。生产前面挂 Nginx 必须加。

何时再考虑 WebSocket：要支持**生成中途打断并保持上下文**、或**多端同看一个生成任务**。届时不必整个换掉，学 Claude Code Bridge 层——优先 WebSocket，拿不到降级 SSE，业务层只认统一事件模型。

## 来源

- [OpenCode Server Architecture](https://mintlify.wiki/anomalyco/opencode/server)
- [OpenCode issue #28729 — SSE 静默断连](https://github.com/anomalyco/opencode/issues/28729)
- [OpenCode issue #26866 — /event 不转发事件](https://github.com/anomalyco/opencode/issues/26866)
- [OpenCode server driver (Orca ADR 0014)](https://raw.githubusercontent.com/VirtusLab/orca/refs/heads/master/adr/0014-opencode-server-driver.md)
- [Claude Code Transport Implementations (DeepWiki)](https://deepwiki.com/yasasbanukaofficial/claude-code/12.1-transport-implementations)
- [Claude Code Bridge 协议设计指南](https://github.com/6551Team/claude-code-design-guide/blob/main/architecture/31-Bridge%E5%8D%8F%E8%AE%AE/bridge-protocol-en.md)
- [Streaming the Claude Agent SDK to a Browser via WebSockets](https://queryplane.com/blog/streaming-claude-agent-sdk-with-websockets/)
- [Cloud Agent 开发笔记(3)：Web 交互与数据持久化](https://www.cnblogs.com/wuyuegb2312/p/20302163)
- [Codex CLI WebSocket Mode 分析](https://codex.danielvaughan.com/2026/05/03/codex-cli-websocket-mode-responses-api-persistent-connections-agent-loop-performance/)
- [openai/codex issue #19821 — WS 失败后 HTTP 回退](https://github.com/openai/codex/issues/19821)
- [openai/codex #13103 — supports_websockets 不生效](https://github.com/openai/codex/issues/13103)
- [OpenAI Codex Model Client 与 API 通信 (DeepWiki)](https://deepwiki.com/openai/codex/3.2-tool-system-and-openai-integration)
- [Hermes Agent 程序化集成文档](https://hermes-agent.nousresearch.com/docs/developer-guide/programmatic-integration)
- [Hermes issue #6057 — 为 Responses API 加 WebSocket](https://github.com/NousResearch/hermes-agent/issues/6057)
- [Hermes3D PI + Chat Streaming](https://github.com/iamlukethedev/Hermes3D/blob/main/docs/pi-chat-streaming.md)
- [WebSockets vs SSE for AI chat (Ably)](https://ably.com/topic/ai-stack/websockets-vs-sse-ai-chat)
- [WebSockets and AI: Why LLMs Are Moving Beyond SSE](https://websocket.org/guides/websockets-and-ai/)
- [Agent Streaming Architecture 2026: The Protocol Decision Matrix](https://agentmarketcap.ai/blog/2026/04/07/agent-streaming-architecture-sse-websocket-http-2026)
- [Choosing MCP Transport: Stdio, HTTP, SSE, and WebSocket](https://heyclau.de/entry/guides/choosing-mcp-transport-stdio-http-sse-and-websocket)
- [AI Token Streaming: From SSE to Durable Sessions](https://websocket.org/guides/use-cases/ai-streaming/)
