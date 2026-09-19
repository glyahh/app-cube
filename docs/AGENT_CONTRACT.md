# Java ↔ Python 接口契约

Python Agent 运行时（`agent/`）与 Spring Boot 接入层的唯一契约。
**两边改任何一处都要同时改这里。**

改动历史同步到本文档末尾。

## 拓扑

```
浏览器 ──┬── WebSocket /api/ws/agent          默认，双向
         └── GET SSE /api/chat/generate/stream  降级，带心跳
              │
           Java:8124/api   （鉴权、限流、biz.* 落库、制品落盘与发布）
              │
              └── ProcessBuilder，stdin JSON / stdout NDJSON
                     │
                  Python 子进程池（常驻，core=3 → max=5）
                     LangGraph · DeepAgents · Backend · PG Checkpointer
```

Python **不做 web 层**。REST 归 Spring Boot。这样省掉 FastAPI、uvicorn、端口、
CORS、JWT、心跳看门狗、熔断器一整套，而且取消直接变成杀进程。

Python **必须常驻**：实测 `import langgraph` 5.35 秒、`import deepagents` 7.11 秒，
每次 run 起新进程会让首屏卡死。

## 传输协议

NDJSON，一行一帧。`\n` 是天然分帧符，不需要 SSE 的 `event:` / `data:` / 空行那套。

### Java → Python（一行）

```json
{
  "v": 1,
  "runId": "1876432091234567890",
  "threadId": "app:1876432091234567800",
  "appId": "1876432091234567800",
  "userId": "1876432091234567700",
  "roundId": "1876432091234567999",
  "traceId": "3f2b1c8a-...",
  "modelRoute": "configured",
  "allowProxyFallback": false,
  "workspace": {
    "root": "D:/.../temp/code_output",
    "name": "vue_project_1876432091234567800",
    "codeGenType": "vue_project",
    "mode": "create",
    "ignore": ["node_modules", ".git", "dist"]
  },
  "input": {
    "message": "用户原话",
    "initPrompt": "应用的 initPrompt",
    "historyTail": [{"role": "user", "content": "…"}],
    "historyTailTruncated": false
  },
  "model": {"modelId": "deepseek-v4-flash", "baseUrl": "...", "maxTokens": 8192, "temperature": 0.3},
  "limits": {"deadlineMs": 600000, "maxModelCalls": 60, "allowShell": true, "allowNetwork": true},
  "artifacts": {"deployKey": "gOGMr8S8"},
  "callback": {"enabled": false}
}
```

**所有 ID 一律 string** —— 雪花 ID 约 19 位，超过 `2^53`，经过 JS `Number` 或
`float64` 会丢精度。

**`workspace.root` / `workspace.name` 由 Java 下发，Python 不做任何路径拼接。**
Java 是路径真相源，Python 是内容真相源。

`historyTail` 只在 LangGraph checkpointer 为空（冷启动）时灌入；**有 checkpoint 就以
checkpoint 为准**。

### Python → Java（多行）

每帧带统一信封 `{v, runId, seq, ts}`。`seq` 是 per-run 单调递增，从 0 开始，
用于去重和乱序检测。

```json
{"v":1,"runId":"1876...","seq":0,"ts":1757692800123,"event":"run.started","threadId":"app:1876...","workspaceName":"...","resumedFromCheckpoint":false}
{"v":1,"runId":"1876...","seq":1,"ts":...,"event":"text.delta","messageId":"1876...-a0","content":"<div>","mode":"append"}
{"v":1,"runId":"1876...","seq":9,"ts":...,"event":"run.completed","status":"succeeded","summary":"...","usage":{"inputTokens":0,"outputTokens":0,"modelCalls":0}}
{"v":1,"runId":"1876...","seq":10,"ts":...,"event":"__end__"}
```

**终止帧 `__end__` 必须发出**，无论前面成功还是抛异常。漏发的话 Java 会一直阻塞
在读上，只能靠超时兜底 —— 这是最难排查的一类故障，`agent/app.py` 用
`finally` 保证它。

## 事件清单

| event | payload 要点 | Java 侧落库 |
|---|---|---|
| `run.started` | `threadId, appId, roundId, workspaceName, modelId, resumedFromCheckpoint` | ✅ |
| `status` | `phase, detail` | ❌ |
| `heartbeat` | `{}` | ❌ |
| `text.delta` | `messageId, content, mode: append\|replace` | ⚠️ 累积，终态写一次 |
| `text.message` | `messageId, content`（完整终稿，用于纠偏） | ✅ |
| `todo.delta` | `messageId, todos:[{content,status}]` | ❌ |
| `step` | `key, title, kind: workflow\|think\|tool, status, detail` | ❌ |
| `tool.started` | `callId, toolName, inputDigest, inputPreview` | ❌ |
| `tool.finished` | `callId, toolName, status, durationMs, outputPreview, truncated` | ❌ |
| `artifact.changed` | `files:[{path,change,bytes,hash}], added, modified, deleted` | ✅ |
| `artifact.manifest` | `fileCount, totalBytes, topPaths, truncated` | ✅ |
| `error` | `code, message, retryable` | ✅ |
| `run.completed` | `status: succeeded\|failed\|cancelled, summary, usage` | ✅ |
| `model.route.fallback` | `fromRoute, toRoute, reason` | ✅，CAS 后仅首次转前端 |
| `__end__` | `{}`（终止帧，**不转成对前端的事件**） | ❌ |

**AI 消息只在 `run.completed` / `error` 时写一次**（`round_id` 上唯一索引做幂等）。
不要每个 `text.delta` 都写库 —— 现有 `StreamHandlerExecutor` 也是流结束才落 AI 行。

## error.code 枚举

```
WORKSPACE_INIT_FAILED     工作区不可用 / 路径越界
MODEL_CALL_FAILED         上游模型 4xx/5xx
MODEL_QUOTA_EXCEEDED      token 配额
RUN_DEADLINE_EXCEEDED     超过 deadlineMs
TOOL_EXECUTION_FAILED     工具执行失败（含 shell 非零退出）
SANDBOX_UNAVAILABLE       Docker daemon 不可达（Phase 3）
CHECKPOINT_ERROR          checkpointer 读写失败
INTERNAL                  兜底
```

Java 侧另外合成 `AGENT_UNREACHABLE`（Python 进程起不来），不是 Python 发出的。

## ID 映射

| 概念 | 生成者 | 存储 |
|---|---|---|
| `userId` / `appId` | Java（雪花） | `biz.*`；Python 只读透传 |
| `roundId` | Java（= `chat_history.id`） | `biz.chat_history.id`；幂等键 |
| `threadId` | Java（派生 `app:{appId}`） | LangGraph checkpoint。纯函数，**无新 ID 需对账** |
| `runId` | Java（雪花） | `biz.agent_run.id`。先建行再调 Python，崩溃也留痕 |
| `checkpointId` | LangGraph | `checkpoint_*` 表，**Java 永不感知** |
| `traceId` | Java（UUID） | `biz.agent_run.trace_id`；跨进程日志串联 |

**Python 禁止生成雪花 ID**（时钟回拨 + workerId 冲突）。需要 ID 时用 UUIDv7。

## 进程池伸缩

| 参数 | 默认 | 含义 |
|---|---|---|
| `coreSize` | 3 | 常驻进程数，Java 启动时拉起 |
| `maxSize` | 5 | 扩容上限 |
| `shrinkBelow` | 2 | 活跃进程数低于此值触发缩容 |
| `scaleCooldown` | 5 分钟 | **缩容之后**禁止扩容的时长 |
| `acquireTimeout` | 30 秒 | 池满时的等待上限 |

- **扩容**：活跃数达到当前池容量且仍有新请求 → 拉起新进程，直到 5 个。冷却期内不扩。
- **缩容**：`release` 时发现活跃数 < 2 且总数 > 3 → 销毁空闲进程直到总数 = 3，记下缩容时刻。
- **冷却**：距上次缩容不足 5 分钟则不扩容。这是有意取舍 —— 刚缩容说明负载低，
  短期内暴涨就先排队，避免反复拉起销毁。
- **池满**：超出 5 个的请求排队等待，最长 30 秒，超时返回 `WORKSPACE_BUSY`。

**进程健康**：`readEvent()` 拿到 EOF 说明 Python 崩了 → 丢弃该进程（`total--`），
下次 `acquire()` 重建。**不需要心跳** —— 进程死了 stdout 立刻 EOF，
这是子进程方案相对 HTTP 方案最大的简化。

## 取消

**取消 = 杀进程树**，没有第二套语义。

| 路径 | 触发 |
|---|---|
| WebSocket | 前端发 `{"action":"cancel","runId":"..."}`，或直接 `ws.close()` |
| SSE | 前端 `es.close()` → Reactor cancel → `doFinally` |

两条路都落到 `AgentWorker.destroyTree()`。

**必须杀进程树**：Python 起的 `npm.cmd` / `node` 不会随父进程退出，
`destroyForcibly()` 只杀直接子进程。遍历 `process.toHandle().descendants()` 逐个 destroy。

## Python 侧的三个硬约束

### 1. stdout 是协议通道，必须保护

`agent/app.py` 在**任何其他 import 之前**执行：

```python
_PROTOCOL_OUT = sys.stdout
sys.stdout = sys.stderr      # 之后所有 print 都进 stderr
```

任何 `print` 都会破坏协议。日志一律走 stderr。有测试盯着这条
（`tests/test_main_loop.py::test_stdout_只有协议帧没有库的_print`）。

### 2. 编码

启动时设 `PYTHONIOENCODING=utf-8`，Java 侧读写都显式指定 UTF-8。
Windows 默认编码会让中文乱码。

### 3. 网络出口必须显式

`httpx` 默认 `trust_env=True`，会去读 Windows 注册表里的系统代理。本机装了抓包 /
代理工具时，到国内模型端点的请求会被一起截走，SSL 层报
`UNEXPECTED_EOF_WHILE_READING`，而 LangChain 把它压平成一句
`Connection error.` —— 从错误信息完全看不出跟代理有关。

所以 `core/model.py` 始终使用 `trust_env=false`。`AGENT_MODEL_PROXY_ENABLED=false`
表示明确直连；开启时所有模型请求统一走 `AGENT_MODEL_PROXY_URL`。

两个客户端都要给：主模型走 `astream_events`（异步，用 `http_async_client`），
摘要模型走 `invoke`（同步，用 `http_client`）。少给一边，压缩就会在某类机器上单独失效。

客户端在进程内复用。worker 是常驻的而模型对象每次 run 重建，client 跟着重建会让连接
一直堆在池里，也白付每轮的 TLS 握手。

主生成请求可携带 `allowProxyFallback=true`。只有代理调用的底层 `ConnectError` 才重试
当前模型调用；读取/连接超时、HTTP 状态错误均不降级。直连成功后发送
`model.route.fallback`，Java 用 App 行 CAS 决定是否永久直连和提示用户。

## 本地验证

```bash
# Python 侧协议自测
cd agent && uv sync
echo '{"runId":"1","workspace":{"root":"<abs>/temp/code_output","name":"html_1"},"input":{"message":"hi"}}' \
  | uv run python app.py

# Python 单测
cd agent && uv run pytest -q

# Java 侧（Phase 0 完成后）
./mvnw.cmd -q -DskipTests compile
./mvnw.cmd test -Dtest=AgentWorkerPoolTest,WorkspacePathResolverTest
```

## 改动历史

| 日期 | 改动 |
|---|---|
| 2026-09-12 | 初版。子进程 stdio 协议、14 个事件、进程池伸缩策略（3/5/缩容冷却 5 分钟） |
| 2026-09-12 | 旧链路下线后复验。补第 3 条硬约束「网络出口必须显式」——修 httpx 静默走系统代理导致的 `Connection error.` |
| 2026-09-19 | 模型出口简化为直连/指定代理二选一；新增按 App 持久化的单调用自动降级。 |
