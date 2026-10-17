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

### 导入编辑准入（2026-10-07，隔离执行与合入仍待接通）

> 历史时点记录：以下拒绝与准备边界保持 2026-10-07 当时状态。正式导入编辑链路与合入门禁见文末「导入工具许可与候选审阅增量（2026-10-17，实施中）」。

SSE/WebSocket 共用请求工厂从当前发布记录取得 `workspace.importTaskId`（UUID string），拿到应用锁后再次读取。该标记不是执行授权。当前 Worker 收到导入身份后，在路径解析、占位成功、模型装配和 Docker/Shell 创建之前返回 `error/code=IMPORT_EDIT_NOT_READY/retryable=false`；Java 展示为“导入项目编辑尚未就绪，本次未执行”。不携带导入身份的新项目维持既有链路。

正式 Java 展示入口在主应用锁内调用 `ImportAgentEditPreparation`，再持有工作区数据库锁，只读冻结完整内容（包括秘密文件、二进制、空目录和属性），写入 V30 账本。请求增加 `workspace.importEditId / sourceHash / configRevision / contextEpoch / importVolumePath` 和 `limits.importDiskBytes`；路径仅由平台按edit UUID生成，身份与代次均为string，秘密值和映射不进入请求。V31核对创建前登记的卷身份和实际分配容量；编辑卷独占`/var/lib/glyahh/import-disks/edit-{editId}/work`，不复用安装运行卷。

正式准备现在实际创建和核对固定容量卷，但尚未生成秘密隔离视图、启动模型或生成授权候选；Worker继续明确拒绝执行。拒绝/取消后实际释放卷并核对loop消失，再终结准备记录；释放未确认保留RECOVERY_REQUIRED和活动槽位。不能用卷准备记录证明编辑成功；仍需接文件工具授权候选、Java复核、原子发布及重启恢复，之后才能替换明确拒绝分支。准备期间进程崩溃仍需核对恢复，不能自动释放或续跑。

平台卷助手新增 `edit-view` 核心，但尚未由上述Java准备流程调用：受限卷内 `work/view` 是唯一容器挂载点，`work/private-state.json` 为root私有恢复映射，不进入模型请求、工具输出或receipt。当前不能据此放行Worker；须先补正式视图创建登记、隔离文件工具、候选验证和发布恢复。

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

主 Agent Docker 准入补充（2026-10-07）：创建时 `--memory-swap` 与 `--memory` 相等；新建、复用和每次 Shell 执行均核对容器内 `memory.swap.max=0`。WSL 内核未应用 Docker 参数时，仅平台按该容器 PID 和完整 cgroup 身份补救并独立读回；不能限制则发 `SANDBOX_UNAVAILABLE`，不装配模型。停止或删除未确认时保留进程内登记并阻止同 Session 重试，释放不能清除失败资格。此增量不代表持久资源账本、硬磁盘、正式隔离合入或重启恢复已完成。

| 日期 | 改动 |
|---|---|
| 2026-10-02 | 仅同步文档：请求示例对齐 Auto 目录、30 分钟预算及实际序列化字段，说明只读任务与配置边界；未修改协议实现或新增运行验收。 |
| 2026-09-12 | 初版。子进程 stdio 协议、14 个事件、进程池伸缩策略（3/5/缩容冷却 5 分钟） |
| 2026-09-12 | 旧链路下线后复验。补第 3 条硬约束「网络出口必须显式」——修 httpx 静默走系统代理导致的 `Connection error.` |
| 2026-09-19 | 模型出口简化为直连/指定代理二选一；新增按 App 持久化的单调用自动降级。 |
| 2026-10-17 | 增量导入工具许可、消费证明、心跳与候选审阅事件；请求帧冻结权限模式与模型/力度。实现位于工作区，未验收。 |

### 正式隔离视图准备增量（2026-10-07）

Java已正式调用edit-view，V32在实际复制前登记view/私有映射路径，封存仅保留viewHash、viewSourceHash、viewStateHash。请求新增workspace.importViewPath、viewHash、viewStateHash，不携带原值或私有映射路径。实际卷释放后才能释放子账本。Worker仍拒绝模型执行；上述旧段的“尚未调用”仅属历史，实际工具、候选和发布仍待开发。

### 平台工具与私有恢复增量

file-edit是平台结构化文件操作，不是Shell或新模型入口；冻结身份来自平台上下文。请求1MiB、edit文件/结果16MiB，意图先APPLYING，私有授权摘要更新后READY。模型工具尚未接入，当前要求无edit容器；实际执行租约、候选Java校验与最终发布仍须实现，不能直接删除Worker拒绝分支。

### 平台候选封存增量

`edit-candidate`只接受冻结编辑身份及平台保存的最新`--state-hash`，不接受模型自报路径、授权列表或秘密映射。edit容器（含已停止未删除）必须全部消失且查询成功；卷内先登记SEALING，之后复用SecretFreeView恢复私有授权并产生root私有候选与manifest，sync及版本复核后才登记SEALED。失败保留未决，不覆盖重试；已有candidateState时file-edit拒绝继续修改。

平台回执包含`candidatePath / candidateManifestPath / candidateHash / candidateManifestHash / authorizedStateHash`及原冻结身份，不包含文件正文。manifest绑定全部候选文件/属性摘要、目录、逐文件before/after、授权记录及保留产物摘要；原秘密只存在于root私有候选，不给模型挂载。

Java平台客户端已核对回执身份、派生路径及摘要格式，但**尚未独立读取验证manifest、接正式模型结束流程或发布**。平台封存成功不是业务成功事件；完整Java资格/取消/内容/秘密复核、持久候选登记与发布恢复仍须接入。

### 候选持久账本增量

V33新增候选子账本，绑定既有edit_id并冻结平台派生候选/manifest路径和授权摘要；PLANNED创建前登记、SEALED只保存平台两个摘要、VERIFIED单独表示Java独立核对、RELEASED表示原卷实际消失。不能删除审计、改路径/授权摘要、覆盖已封存回执或未释放即关闭父任务。父任务COMMITTING要求VERIFIED；SUCCEEDED要求保留验证记录且候选已RELEASED，单有平台封存不能开始发布。

Java已提供planCandidate/candidateReady/candidateReleased；正式准备失败的清理链在实际卷释放后核对候选登记，再释放视图和关闭准备任务。**正式模型结束尚未调用前两项，独立内容验证及VERIFIED写入方法尚未实现**；数据库中的验证阶段用例只是约束反例/协议检查，不是修复证据，不得凭SQL夹具声称Java已安全发布。

### 实际候选读取与秘密字段绑定增量

平台candidate-read是Java私有通道，不是公共API或模型工具。只接受平台冻结身份、state/candidate/manifest三项摘要和固定part；manifest/private-state/inventory按256KiB分块，file/view-file只能读取已登记相对路径。私有正文不得进入模型、日志或公共响应。inventory实际遍历候选目录和字节，不能以manifest文件集合替代。

候选恢复除核对占位符集合及数量，还核对由冻结源重新生成的去秘密基线、绑定及秘密所在整行；改秘密字段名、注释或表达式即拒绝，不以数量不变放行。其他行仍可合法编辑。Java新增独立恢复摘要核心，从原内容、基线、授权字节及平台绑定重建原内容后计算恢复摘要，不接受候选自报恢复值。绑定对象文本只显示private。

**完整Frozen构建、真实来源读取接入、正式候选校验/VERIFIED、模型与最终发布仍未接。** Java恢复核心单元不能代替正式模型或生产恢复验收，不删除Worker安全拒绝。

### Java Frozen构建核心增量

Java独立校验器已增加freeze：严格解析平台私有状态及完整正文SHA，核对业务身份/固定view路径/可信源路径/额度、原清单和基线清单的规范摘要。必须从独立FilesAccess实际来源和视图取得输入，读取前后复核范围及资格；授权字节重新SHA并从原内容重建去秘密基线，经独立恢复器产生恢复摘要。未授权基线源码变化或删除拒绝，执行产物单独记录，不提升为源码授权。

**此为构建核心，并未接平台实际来源/完整视图FilesAccess、正式模型结束或VERIFIED写入。** 调用方还须核对Java冻结内容/属性版本与当前归属/来源/上下文，不能把Python inventory摘要当作Java完整内容版本，不删除安全拒绝。

### 平台实际来源/完整视图读取增量

候选封存记录新增candidateViewHash，固定结束时完整视图的文件/目录/属性摘要。candidate-read新增source-inventory、view-inventory、source-file；source根仅来自已核对摘要的root私有状态，不接受外部宿主路径。来源库存须符合冻结sourceHash，视图库存须符合封存candidateViewHash；旧记录无此字段时视图读取明确拒绝，不自动补新摘要。source-file仅读取原清单文件，view-file仍限定授权文件。

Java已提供sourceAccess/viewAccess生产读桥接，动态库存正文逐块保持固定摘要、整体SHA验证后再严格解析并独立重算规范摘要。实际原内容仍在Java平台内存，不进入模型或公共接口。真实受限卷已验证生产Java Engine读取私有状态、源/视图、构建Frozen并验证实际候选字节；该联调采用合成业务身份/资格，不代替正式账本准入与原Java版本复核。

**正式业务freeze/verify/VERIFIED、模型编辑、最终发布及恢复仍未接。** 不删除Worker安全拒绝，不以本次独立联调宣称正式修复完成。

### 正式候选验证服务与持久准入增量

Java新增ImportAgentCandidateVerification平台服务：应用协调锁内重读账本与正式来源，路径由WorkspacePathResolver生成，重新describe完整Java内容/属性并核对冻结sourceHash；先登记候选意图、平台实际确认停止并封存、登记SEALED，再以账本原库存摘要和实际源/视图读取构建Frozen、验证候选，末尾重新核对Java原版本及资格。

ImportAgentEditLedger增加封存资格及candidateVerified。VERIFIED只保存source/candidate/manifest/authorization四个摘要；CAS限定SEALING、停止、未取消/关闭/过期、父版本/执行者、SEALED子记录及两个回执摘要。现有V33触发器再核对归属、来源、上下文和精确证据。失败服务记录RECOVERY_REQUIRED并保留资源，不发布文件或发送业务成功。

平台私有状态声明的容量仅验证平台硬上限；实际源/视图库存仍受Java配置额度检查。避免把平台100000文件的默认上限与产品10000文件的实际额度混淆而永久拒绝正常编辑，不扩大实际输入额度。

**此服务尚未由正式Worker结束调用；真实业务锁内完整服务及Java持久写入仍待联调。** 当前单元/数据库约束与真实独立Java读验证分开登记；仍不能声称正式模型修复或发布完成。

### 验证服务当前执行者准入

验证服务在任何候选操作前要求SEALING、未取消、已停止，且冻结executorPid/executorStart同时匹配当前Java进程。旧进程或PID复用但启动时刻不同不得重入；COMMITTING、终态、EDITING、RECOVERY_REQUIRED及取消请求直接拒绝，不触发候选平台读取或恢复写入。失败处理中只允许当前SEALING/CANCELLING登记RECOVERY_REQUIRED，不能借重复验证把已提交/已结束任务改成恢复。

正常服务路径已增加单元：实际完整Java源快照，受控平台/账本，完整freeze/verify后才调用candidateVerified且顺序正确。该单元不是实际数据库写入或模型闭环；重启主动资源恢复和正式结束事件仍待实现。


### 平台登记容器启动与实际内核准入（2026-10-07）

Java已提供createdContainer/startEditContainer，ImportAgentContainerLifecycle.beginEditing在业务应用锁、当前executor PID/启动时刻及冻结资格下，先启动原登记ID、核验平台实际内核limits，再CAS EDITING。平台新增edit-container-start及固定--container-id；不新建替代容器或挂载发布目录。实际memory/CPU/pids有界、swap强制0读回，远程引擎/不可强制/未知容器均拒绝。停止失败保留STOPPING/RECOVERY，不释放。WSL MemorySwap记录不可靠时只认可实际cgroup证明，不以当前没有swap代替硬限制。

实际生产Java/数据库/Docker已证明启动、四项内核读回、停止、SEALING和未编辑基线VERIFIED；正式Worker尚未调用该链、工具/真实模型/最终发布仍未接，不移除IMPORT_EDIT_NOT_READY。尚须逐执行复核资格与限额及持久Worker生命周期，不能把一次启动证明当作完整模型执行保护。

### 导入工具许可与候选审阅增量（2026-10-17，实施中）

正式导入编辑由独立入口 `agent/native_import_worker.py` 启动：它校验编辑/租约身份、要求持久 PostgreSQL，再以 `app.main(require_persistent=True, import_only=True, import_runner=run_import_edit)` 进入 `core/import_edit_runtime.py`。普通 `app.py` 主循环仍对带 `workspace.importTaskId` 的请求返回 `IMPORT_EDIT_NOT_READY`，该拒绝分支不能删除。

请求帧在 `workspace.importTaskId` 非空时新增六个 Java 冻结字段，Python 不得自行选择或替换：`importPermissionMode`（`MANUAL` / `GUARDED_AUTO` / `AUTO`）、`importPolicyRevision`、`importSessionEpoch`、`importServerGeneration`、`importModelProfileId`、`importThinkingEffort`（可空）。ID、版本一律 string；非导入请求不携带这些字段。

Python 在工具门禁内新增以下内部事件，信封仍为 `{v,runId,seq,ts}`：

| event | payload 要点 | Java 处理 |
|---|---|---|
| `import.permission.requested` | `editId, permissionId(UUID), actionHash(SHA256), action(规范化动作)` | 平台从真实视图重算动作与风险、按冻结模式决定、登记 V40 票据；转前端时只保留白名单字段，不转发原始动作 |
| `import.permission.consume` | `permissionId, actionHash, consumeRequestId` | CAS 将 APPROVED 票据置 CONSUMED 并写消费证明 |
| `import.permission.result` | `permissionId, actionHash, consumeProof, state=RESULT_CONFIRMED\|RESULT_UNCONFIRMED, succeeded` | 关联票据结果；不再执行或重放 |
| `import.permission.heartbeat` | `editId, permissionId` | 复核编辑身份与当前资格，失效即断流 |
| `import.review.requested` | `editId, hash, risk, status, revision` | 候选审阅已持久化后发出；`agent_run` 记 `WAITING_REVIEW`，不视为成功完成 |

约束：

- `actionHash` 由 `backends/import_action.py` 从真实隔离视图重算，覆盖工具、参数、视图状态哈希与前置文件摘要；Java 用 `ImportAgentCanonicalJson` 复核，不接受模型自报摘要或执行 Agent 的风险结论。
- Python 只通过 PostgreSQL 只读资格通道等待决定；不写批准表，不新增 HTTP 服务，也不争抢 stdin 的第二个读者。等待单请求默认 300 秒，每 5 秒发心跳；超时、票据不匹配、动作变化、重复消费均按不可恢复失败处理，不转成可重试 ToolMessage。
- 只有拿到消费证明并再次核对动作未变化后才允许副作用。副作用开始后失败记 `RESULT_UNCONFIRMED`，不自动重放；工具完成事件只说明执行结果，不等于候选已合入。
- 候选整批决定由 Java 候选审阅服务登记；发布必须存在与候选、清单、授权、源版本匹配的 APPROVED 决策。`WAITING_REVIEW` 时 Worker 与编辑容器已停止，执行流结束，候选与卷保留并计入额度；服务重启不恢复旧等待。
- 本节的实现位于工作区未提交改动，尚未在真实 PostgreSQL、Docker、模型、SSE/WS 或浏览器中验收；不得把“已接线”当作授权系统交付。
