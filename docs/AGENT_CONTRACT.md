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

Python worker 常驻以摊薄导入与持久化初始化开销。历史测量中的导入耗时不作为当前版本的性能承诺；启动状态需以实际日志验证。

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
    "name": "project_1876432091234567800",
    "codeGenType": "project",
    "mode": "create"
  },
  "input": {
    "message": "用户原话",
    "initPrompt": "应用的 initPrompt",
    "historyTail": [{"role": "user", "content": "…"}],
    "historyTailTruncated": false
  },
  "limits": {"deadlineMs": 1800000, "maxModelCalls": 60},
  "artifacts": {"deployKey": "gOGMr8S8"},
  "callback": {"enabled": false}
}
```

**所有 ID 一律 string** —— 雪花 ID 约 19 位，超过 `2^53`，经过 JS `Number` 或
`float64` 会丢精度。

**`workspace.root` / `workspace.name` 由 Java 下发，Python 不做任何路径拼接。**
Java 是路径真相源，Python 是内容真相源。新 Auto 应用的业务类型为 `auto`，请求中的工作区类型使用目录前缀 `project`；旧目录前缀继续兼容。

该示例对应 `AgentRunRequest.toFrameJson`。模型、Backend 和权限策略由 Worker 配置及执行侧校验决定，不由本帧中的模型或权限字段任意覆盖。`input.taskKind` 可选，缺省为可写的 `coding`；知识分析使用 `analysis`，问答使用 `qa`，由 Python 的只读 Backend 强制限制副作用。

### 导入启动补充内部契约（尚未接业务批准接口）

`input.taskKind=startup` 在解析工作区前进入封闭只读分支。`input.message` 是服务器构造的 JSON 文本，字段必须恰为：

- `approvalId / scanId / importTaskId`：规范 UUID string；`appId / configRevision / contextEpoch`：十进制 string；`contentHash`：完整内容 SHA256。
- `directories`：本次批准的 1–20 个确实未识别目录，不是任意用户输入；`files`：服务器建立的合法文本投影，最多256个文件、单文件16KiB、整请求64KiB。超限明确失败，不静默截断。

Worker 复用主模型与既有路由，不自动切线路；一次调用，不装配生成 Agent 的 Shell、文件工具、Git、checkpoint、store 或历史消息，也不读取原工程路径。保护路径拒绝，模型输出工具调用拒绝；缺凭据不走占位成功。

模型仅返回 `services / unidentified`；每个批准目录须包含一个或多个有依据候选，或者恰好列为一次未识别，不得同时属于两者。同目录同命令与重复服务名称拒绝，不限制一个目录只能有一个服务。服务字段恰为 `name / directory / command / build / evidence`；依据必须存在于批准投影。服务端包裹原冻结身份、标 `source=AGENT / certainty=INFERRED`，计算依据文本 SHA256；模型不能自己改身份、声称运行成功或取得安装权。`text.message` 的 `messageId=startup-candidates` 承载结构化结果，合格后才 `run.completed`；失败发脱敏 `error`，主循环始终发 `__end__`。

Java 内部已有 V23 冻结批准账本、同锁当前证据复核与封存文本投影。专属执行器通过平台 `scripts/import-startup-agent.py` 一次性 Python `-I` 进程消费私有输入，先校验 SHA256/96KiB 上限；正文48KiB、封装消息64KiB，不截断。UTF-8、应用/批准/序号/事件次序与 EOF 严格核对；90秒有界执行，批准到期持续撤销。资源使用独占 PID/启动时间，不借共享 Worker 的 PID 终止任务。

**边界**：这些仍是内部能力；approvalId 格式正确不代表真实用户批准存在。持久执行租约、真实批准 API、取消/重启恢复及返回时全内容发布校验仍待接；客户端不得直接提交这个消息。候选命令只是不可信推断，不可直接交旧运行入口执行。正式授权/API/界面联调与真实模型成功均未验收。现有限额/排除策略不替代相关上下文与实际模型窗口预算，见 [本节点证据](PLANS/project-import-startup-model-executor-verification.md)。

Java 默认墙钟上限和请求缺省 `deadlineMs` 均为 30 分钟，Python 默认 `AGENT_DEADLINE_MS` 为 `1800000`；显式配置可覆盖，修改时需核对两端一致性。

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

# Java 侧
./mvnw.cmd -q -DskipTests compile
./mvnw.cmd test -Dtest=AgentWorkerPoolTest,WorkspacePathResolverTest
```

## 改动历史

| 日期 | 改动 |
|---|---|
| 2026-10-02 | 仅同步文档：请求示例对齐 Auto 目录、30 分钟预算及实际序列化字段，说明只读任务与配置边界；未修改协议实现或新增运行验收。 |
| 2026-09-12 | 初版。子进程 stdio 协议、14 个事件、进程池伸缩策略（3/5/缩容冷却 5 分钟） |
| 2026-09-12 | 旧链路下线后复验。补第 3 条硬约束「网络出口必须显式」——修 httpx 静默走系统代理导致的 `Connection error.` |
| 2026-09-19 | 模型出口简化为直连/指定代理二选一；新增按 App 持久化的单调用自动降级。 |
