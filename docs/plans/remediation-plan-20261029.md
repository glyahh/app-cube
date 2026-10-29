# 修复计划：从自证交付转向可复跑证据（2026-10-29）

[返回方案目录](README.md) · [当前状态](../status/current.md) · [威胁模型](../threat-model.md)

状态：设计稿。未开始实施；本文不是验收结果。

## 1. 背景

当前仓库有 298 个 Java 文件、92 个 Python 文件（含 42 个测试）、153 个前端文件，迁移到 V50。已有项目导入按 2026-10-09 批准的范围标为 COMPLETE，之后新增的会话授权模式仍未整体验收。质量证据主要来自 152 篇文档和四十余个 `scripts/checks/verify-*` 脚本，缺少固定任务集、跨站拒绝测试和沙箱运行时证据。

本计划针对六项可核查的不足：无外部可比较基准、跨站来源放开、执行环境缺少运行时证据、主 Agent 持久化静默降级、无内容级快照、复杂度接近单人维护边界。前五项在本计划内处理，第六项只记录，不在本计划内重构。

## 2. 目标与非目标

目标按顺序：

1. 任何一次改动都能用固定命令证明没变差。
2. 跨站请求与 WebSocket 来源有拒绝测试。
3. 沙箱的实际运行参数有 inspect 证据，导入不可信工程有网络边界。
4. 主 Agent 在数据库不可用时显式失败，不再静默降级。
5. 候选合入失败能恢复到原字节。
6. 有 5 个固定任务可以复跑，用来回答“改了以后整体是否还正常”。

非目标：

- 不新增导入来源、不接 MCP、不做 IDE 补全。
- 不重写 `projectimport/`，不拆分 `AppServiceImpl`。
- 不做完整对话回档与上下文回退，那属于[会话回档设计](conversation-rollback-design.md)。
- 不以新增文档替代测试或运行时证据。

## 3. 现状核对

以下为 2026-10-29 读取源码的结论，不是历史报告转述。

| 项 | 结论 | 源码位置 |
| --- | --- | --- |
| 容器权限 | 已有 `--cap-drop ALL`、`no-new-privileges`、pids 512、memory 4g、cpus 2，镜像以 `runner`(uid 1001) 运行 | `agent/backends/docker_sandbox.py:393`、`deploy/sandbox/Dockerfile:49` |
| 容器网络 | 普通生成用 `--network bridge`；运行时预检与导入编辑容器均为 `none` | `agent/backends/docker_sandbox.py:146`、`:393`、`scripts/import_disk_volume.py:116` |
| 工作区挂载 | 宿主工作区以 rw 直接挂载，无独立调试副本 | `agent/backends/docker_sandbox.py:398` |
| 导入编辑容器 | 不复用普通容器，由平台登记容器与磁盘卷管理 | `agent/backends/import_platform_docker.py:1`、`scripts/import_disk_volume.py` |
| 跨站来源 | REST 与 WS 均为 `*`，且 REST 允许凭据 | `config/CorsConfig.java:15`、`config/AgentWebSocketConfig.java:35` |
| 前端来源 | 开发时经 Vite 代理访问 `/api`，WS 代理开启 `ws: true`，浏览器 Origin 为 `127.0.0.1:5173` | `ai-generate-code-frontend/vite.config.ts:31`、`:41` |
| 会话 Cookie | 只配置 `max-age`，未显式设置 HttpOnly、SameSite、Secure | `application.yml:60` |
| 健康检查 | actuator 已暴露 health、info、prometheus | `application.yml:88` |
| 持久化 | 导入编辑已强制 `require_persistent=True`；主 Agent 入口默认 false，失败降级内存 | `agent/core/import_edit_runtime.py:135`、`agent/app.py:87`、`agent/core/persistence.py:45` |
| 版本识别 | 相对路径、大小、修改时间，不含内容哈希 | `runtime/WorkspaceVersioner.java:27` |
| 既有持久化测试 | 已有内存降级拒绝用例 | `agent/tests/test_persistence_required.py` |
| 基准 | 无 `benchmarks/` 目录，无固定任务集 | 仓库根 |

## 4. 验收口径

每阶段必须同时满足：

- 有可执行命令，退出码为 0。
- 有产物，落在 `docs/reports/evidence/`，可随提交复查。`temp/**` 已被 `.gitignore:44` 忽略，只能放体积大、无需长期保留的原始输出，并在报告中写明生成命令和本机路径。
- 环境不满足时输出明确的 skip，退出码不当作通过。
- 报告只记录实际执行的命令、环境、时间和结果，不引用历史数字。

阶段状态分三种，写在阶段报告的标题行：

- **完成**：命令退出码 0，产物齐全。
- **阻塞**：缺少该阶段声明的环境（P2 无 Docker、P3 无 PostgreSQL、P5 无模型密钥），记录 skip 原因与缺失项。阻塞不计入已交付，环境到位后重跑可转为完成。
- **未开始**：没有产物。

同一阶段不允许同时存在完成和阻塞两份记录；出现时以时间较晚的为准，并在报告里说明前一份作废的原因。

证据目录按日期建子目录，例如 `docs/reports/evidence/{date}-p0-baseline/`，`{date}` 取执行当日的 `yyyymmdd`。每个子目录放一个 `README.md`，写明命令、环境、时间和未覆盖项。首次创建时在 `docs/reports/README.md` 登记说明。

阶段之间默认串行；标注可并行的阶段需要两个开发者或两个独立会话，且不共享同一批文件。

## 5. 阶段总览

| 阶段 | 产出 | 预估 | 依赖 | 可并行 |
| --- | --- | --- | --- | --- |
| P0 基线冻结 | `scripts/verify-baseline.ps1`、基线 JSON | 0.5 天 | 无 | 否 |
| P1 跨站收口 | 统一来源配置、拒绝测试、Cookie 属性 | 2 天 | 无 | 与 P2 |
| P2 沙箱证据 | inspect 核对脚本、导入网络策略 | 2 天 | Docker | 与 P1 |
| P3 持久化显式失败 | 配置开关、启动退出、测试 | 0.5 天（健康检查可选 +0.5） | PostgreSQL | 与 P1/P2 无文件冲突 |
| P4 内容快照 | V51 迁移、快照服务、合入接入、恢复测试 | 3–5 天 | 无 | 与 P5 |
| P5 固定任务基准 | 基准驱动、`benchmarks/`、首次基线 | 8–11 天 | 真实模型 | 与 P4 |

单人总工期约 3 周（15 个工作日）。P4 与 P5 并行后，整体受 P5 的 8–11 天约束，其余阶段填在它的空档里。

## 6. P0 基线冻结

### 目标

把“当前代码是什么状态”变成一条命令加一个 JSON，替代引用历史报告数字。

### 改动清单

新增 `scripts/verify-baseline.ps1`：

1. 记录 `git rev-parse HEAD` 与工作区是否干净。
2. 依次执行：
   - `./mvnw.cmd -q test`，读取 `target/surefire-reports/*.xml` 统计。
   - `agent/.venv/Scripts/python.exe -m pytest agent/tests -q --junitxml=temp/baseline/pytest.xml`。
   - `cd ai-generate-code-frontend && npm run test:unit -- --reporter=json --outputFile=../temp/baseline/vitest.json`。
3. 写 `docs/reports/evidence/{date}-p0-baseline/baseline-{sha}.json`，字段：`sha`、`generatedAt`、`dirty`、`java`、`python`、`frontend`，每项含 `total/passed/failed/skipped/exitCode`，另加顶层 `unstable` 数组记录两次结果不一致的用例。
4. 任一阶段非零退出仍继续执行其余阶段，最终以“是否存在失败”决定整体退出码。

新增 `scripts/tests/test_baseline_report.py`：用固定样式的 surefire、junit、vitest 报告片段验证统计逻辑，不启动真实测试。

### 验收

- 干净 checkout 上执行 `powershell -ExecutionPolicy Bypass -File .\scripts\verify-baseline.ps1`，产出 JSON 并提交到 `docs/reports/evidence/{date}-p0-baseline/`。
- 连续执行两次，通过用例集合一致。已知非确定性用例（例如随机阈值相关测试）单独列在 `unstable`，不计入失败，但必须在同一 JSON 和阶段报告中列出；`unstable` 非空时基线标记为“非完全确定”，后续阶段对比时按同一清单排除。
- 故意改坏一个断言时，`failed` 计数增加且整体退出码非零。

### 回滚

新增脚本，删除即可，不改动任何现有测试。

### 风险

Java 全量测试耗时较长。若超过 15 分钟，允许基线阶段只跑 `-Dtest=*Test` 的默认范围，但需在同一 JSON 中记录实际命令，不隐瞒裁剪。

## 7. P1 跨站与 Origin 收口

### 目标

REST 与 WebSocket 只接受配置中的来源；未配置来源的部署无法启动，而不是默认放开。

### 改动清单

1. 新增 `config/SecurityProperties.java`：
   - `@ConfigurationProperties(prefix = "app.security")`
   - 字段 `List<String> allowedOriginPatterns`，默认空列表。
2. 修改 `config/CorsConfig.java`：
   - 构造器注入 `SecurityProperties`。
   - `allowedOriginPatterns` 使用配置值；清洗后为空列表时启动失败，错误信息指向配置键。
   - 清洗规则：去空白、去空串、去重后再判空。`${APP_ALLOWED_ORIGINS:}` 在未配置时可能绑定成含一个空字符串的单元素列表，直接用 `isEmpty()` 判不出来。
   - 保留 `allowCredentials(true)`，但此时不允许 `*`。
3. 修改 `config/AgentWebSocketConfig.java`：
   - 删除硬编码 `setAllowedOriginPatterns("*")`，改用同一属性。
4. 修改 `application.yml`：
   - 默认值为空，未配置来源时启动失败：
     ```yaml
     app:
       security:
         allowed-origin-patterns: ${APP_ALLOWED_ORIGINS:}
     ```
   - 文件末尾追加 local 文档段，给本地开发来源。`application-local*.yml` 当前都未被 Git 跟踪，本地默认值只能写在受跟踪的 `application.yml`：
     ```yaml
     ---
     spring:
       config:
         activate:
           on-profile: "local | local-a | local-b"
     app:
       security:
         allowed-origin-patterns: http://127.0.0.1:5173,http://localhost:5173
     ```
   - 生产环境不匹配任何 local profile，必须显式提供 `APP_ALLOWED_ORIGINS`，否则启动失败。
5. 修改 `application.yml` 会话 Cookie：
   ```yaml
   server:
     servlet:
       session:
         cookie:
           max-age: 2592000
           http-only: true
           same-site: lax
   ```
   `secure` 不在默认配置中写死，按部署的 HTTPS 情况由 profile 打开。

### 测试

新增 `CorsOriginTest`（MockMvc 或 `WebMvcTest`）：

- 非白名单 Origin 的预检返回无 `Access-Control-Allow-Origin`。
- 非白名单 Origin 的带凭据简单请求不返回 `Access-Control-Allow-Credentials`。
- 白名单 Origin 返回正确头。
- 空白名单配置触发上下文启动失败。

新增 `AgentWebSocketOriginTest`：

- 直接调用握手拦截器与 `setAllowedOriginPatterns` 生成的实际判断路径，断言未知 Origin 返回 403，白名单 Origin 通过。
- 不依赖真实网络端口。

### 验收

```bash
./mvnw.cmd test -Dtest=CorsOriginTest,AgentWebSocketOriginTest
```

另用两个 Origin 手工请求一次，把请求头与响应头记录到阶段报告。

### 回滚

回退 P1 的代码提交。不能通过把配置改回 `*` 回滚，因为代码会拒绝 `*` 和空列表，改配置只会让启动失败。

### 风险

- 生产域名未知时不能上线本阶段。执行前必须确认实际域名，否则前端直接不可用。
- 反向代理改写 Origin 时，配置要填代理后的值。需要实际抓包确认，不能凭猜测。
- Cookie 的 `secure` 打开后，HTTP 部署会失去会话。

## 8. P2 沙箱证据

### 目标

把“容器应该被限制”变成“容器实际被限制”，并给导入不可信工程一个明确的网络边界。

### 改动清单

1. 新增 `scripts/checks/verify-sandbox-isolation.ps1`，参数 `-Mode generate|import`：
   - 启动一个测试容器，等待就绪。
   - `docker inspect` 后读取：`Config.User`、`HostConfig.Privileged`、`HostConfig.CapDrop`、`HostConfig.SecurityOpt`、`HostConfig.PidsLimit`、`HostConfig.Memory`、`HostConfig.NetworkMode`、`Mounts`。
   - 运行用户用 `docker exec <cid> id -u` 取实际 uid，断言不等于 0。`Config.User` 只记录不判定：`docker create` 没有传 `--user` 时该字段为空，用户来自镜像的 `USER runner`，属于当前健康状态。
   - 与期望值比对，不一致即非零退出。
   - 输出 `docs/reports/evidence/{date}-p2-sandbox/sandbox-{mode}-{timestamp}.json`；大体积 `inspect` 原文留在 `temp/` 并在 JSON 中记录路径。
   - 结束时删除容器；停止未确认时保留容器并报错。
2. 新增 `agent/tests/test_sandbox_inspect_contract.py`：
   - 用固定的 inspect JSON 片段验证断言函数。
   - 覆盖 uid 为 0、CapDrop 缺失、NetworkMode 不符、多出挂载四种失败。
3. 导入执行路径：容器由 `scripts/import_disk_volume.py:116` 的 `edit-container-create` 创建，已是 `--network none`、`--read-only`、`--cap-drop ALL`；`agent/backends/import_platform_docker.py` 只采用已登记容器，不创建新容器，本阶段不必查它的网络参数。
   - 已是 `none` 时，本阶段只补证据。
   - 若后续改动让导入容器改用 `bridge`，必须改回 `none`；依赖安装走已有的受控阶段，若现有阶段不足，记录为已知限制，不在本阶段新建通道。
4. 更新 `docs/threat-model.md` 的 T09：写明已核对参数、证据位置和仍未验证的逃逸路径。

### 验收

```bash
powershell -ExecutionPolicy Bypass -File .\scripts\checks\verify-sandbox-isolation.ps1 -Mode generate
powershell -ExecutionPolicy Bypass -File .\scripts\checks\verify-sandbox-isolation.ps1 -Mode import
```

Docker 不可用时脚本以专用退出码 3 结束并写明 skip，不报告成功。

### 回滚

脚本与文档为新增；网络参数变更以单行 diff 回退。

### 风险

- Docker CLI 不可用则本阶段阻塞，不用单测替代运行时证据。
- Windows 与 WSL 路径差异会让挂载断言误报，脚本需要同时接受两种路径写法。
- 导入容器的创建入口不止一处，执行前要 grep 全部 `docker create/run` 调用，不能只改一处。

## 9. P3 持久化显式失败

### 目标

主 Agent 在正式环境无法连接 PostgreSQL 时启动失败，而不是降级内存继续服务。导入编辑路径已经强制持久化（`agent/core/import_edit_runtime.py:135`），本阶段只补主路径的开关来源与启动脚本。

现有 `agent/app.py:123-126` 已经是 `if require_persistent: raise RuntimeError("POSTGRES_PERSISTENCE_UNAVAILABLE")`，异常冒泡导致非零退出。保持这一行为，不改成返回码，也不新增重复判断。

### 改动清单

1. 修改 `agent/core/settings.py`：
   - 新增 `require_persistent`，来源 `AGENT_REQUIRE_PERSISTENT`，未显式设置时为 `false`。
   - 由启动脚本为正式后端设 `true`。
2. 修改 `agent/app.py`：
   - `main()` 从 settings 取默认值，命令行参数可覆盖。
   - 失败仍按现有路径抛 `RuntimeError`，进程非零退出，不进入 stdin 循环。
   - 成功与降级状态通过 `logger` 写到 stderr，格式固定为 `persistence: postgres` / `persistence: memory`。不写 stdout —— stdout 只承载协议帧。
3. 修改 `scripts/start-backend.ps1`：
   - 正式启动时设置 `AGENT_REQUIRE_PERSISTENT=true`。
   - 启动预检失败时直接报错退出，不重试。
4. 健康检查：
   - 导入路径已有：`ImportNativeWorker.java:70` 校验 `worker.ready` 帧的 `persistence == POSTGRES`，直接复用，不新造启动报告。
   - 主 Agent 的 `AgentWorker` 目前没有 `worker.ready` 帧，也没有读取 `persistence` 字段。要在 `/actuator/health` 里反映主 Agent 状态，必须先给主 Agent 增加该帧，属于 `docs/contracts/agent.md` 的协议变更。列为可选 P3b，不阻塞本阶段。

### 测试

- 复用 `agent/tests/test_persistence_required.py`。
- 新增 `agent/tests/test_main_startup_requires_persistence.py`：坏 DSN 下断言进程以非零退出、未消费 stdin、stderr 含 `POSTGRES_PERSISTENCE_UNAVAILABLE`。
- P3b 若实施，补 `AgentWorker` 解析 ready 帧的单元测试与健康指示器状态映射测试。

### 验收

- `pytest agent/tests/test_main_startup_requires_persistence.py -q` 通过。
- 本地断开 PostgreSQL 后执行 `scripts/start-backend.ps1`，后端启动失败且错误指向持久化。
- `local` profile 不设该变量时仍可启动，日志明确标注 `persistence: memory`。

### 工期

必做部分 0.5 天；P3b 另加 0.5 天，含协议文档同步。

### 回滚

移除 `AGENT_REQUIRE_PERSISTENT` 即恢复旧行为。代码保留 `require_persistent=false` 分支。

### 风险

- 本地开发者没有 PostgreSQL 时会被卡住。默认值必须留在 `false`，只在正式启动脚本中开启。
- 健康检查读取的是启动报告，不是实时探测。断线后的状态变化不在本阶段范围。

## 10. P4 内容快照最小闭环

### 目标

候选合入失败或误覆盖后，能证明恢复到原字节。只做文件级快照与恢复，不做对话回档。

### 改动清单

1. 新增 `runtime/WorkspaceSnapshotService.java`：

   ```java
   Snapshot create(Path workspace, Collection<String> relativePaths, String reason);
   void markCommitted(String snapshotId);
   void markFailed(String snapshotId, String reason);
   void restore(Path workspace, String snapshotId, boolean force);
   Optional<Snapshot> find(String snapshotId);
   ```

   - 状态机为 `PENDING → COMMITTED | FAILED`。
   - `create` 只写 `beforeHashes`，此时合入尚未发生。
   - 合入成功后由调用方执行 `markCommitted`，计算并写入 `afterHashes`，状态置 `COMMITTED`。
   - 合入异常时由调用方执行 `markFailed`，状态置 `FAILED`，`afterHashes` 保持为空。
   - `restore` 的判定：
     - `COMMITTED`：当前文件哈希必须等于 `afterHashes` 才直接覆盖；不一致且 `force=false` 时拒绝并列出差异文件。
     - `PENDING` / `FAILED`：合入未完成，直接恢复；恢复前把当前哈希记入 `recoveryHashes` 供审计，不要求 `force`。

   - 每条快照存 `.glyahh/snapshots/{snapshotId}/manifest.json` 与 `files/` 下的原文件副本，保留相对路径。
   - 只处理传入的相对路径；路径经 `WorkspacePathResolver` 校验，拒绝逃逸与链接。
   - 排除 `.glyahh`、`.git`、`.scratch`。
   - 快照目录不参与发布、检索与版本摘要。

2. 新增 `runtime/SnapshotRecord.java`：`id`、`status`（`PENDING/COMMITTED/FAILED`）、`createdAt`、`reason`、`Map<String, String> beforeHashes`、`Map<String, String> afterHashes`、`Map<String, String> recoveryHashes`。

3. 修改 `runtime/WorkspaceVersioner.java`：
   - 新增 `contentVersion(Path workspace)`，对纳入版本范围的文件流式计算 SHA-256。
   - 原 `version()` 保留原语义与用途，注释补一句“不能作为内容证据”。
   - `contentVersion` 不参与高频轮询，只在候选绑定与合入前调用。

4. 新增迁移 `V51__import_agent_edit_snapshot.sql`：

   ```sql
   ALTER TABLE biz.project_import_agent_edit ADD COLUMN snapshot_id UUID;
   ```

   不复用已有的 `recovery_json`：该字段由发布恢复流程独占写入，快照服务再写会覆盖在途恢复状态。

5. 修改 `projectimport/ImportAgentEditLedger.java`：`Row` 增加 `snapshotId`，`require` 查询带上该列；新增 `bindSnapshot(editId, snapshotId)` 供合入前写入。

6. 接入 `projectimport/ImportAgentPublication.java`：
   - 合入前对候选涉及文件创建快照，状态为 `PENDING`，并 `bindSnapshot`。
   - 合入成功后调用 `markCommitted` 写入 `afterHashes`。
   - 合入异常时先 `markFailed` 再按快照恢复；恢复失败要报错并保留快照，不吞异常。
   - **与现有恢复的关系**：`ImportAgentPublication` 已有 `recover()` 和 `publish_phase`（`CANDIDATE_READY`、`OLD_MOVED`、`NEW_MOVED`、`COMMITTED`），负责把目录移动补完或回退到一致状态。快照不替代它，只在它之后补文件字节。执行顺序固定为先 `recover()`、再 `markFailed`、最后 `restore`；`publish_phase` 未到达 `OLD_MOVED` 时不创建快照的恢复动作，因为此时还没有字节被改写。

7. 快照清理：只保留最近 N 条（配置项，默认 20），超出的按时间删除；不删除本次合入仍在用的条目。

### 测试

新增 `WorkspaceSnapshotServiceTest`：

- 创建后修改文件，恢复后 sha256 与创建时一致。
- 路径含 `..`、绝对路径、符号链接时被拒绝，根外文件不变。
- 合入后文件被再次修改，快照为 `COMMITTED` 时 `force=false` 恢复被拒绝；`force=true` 时覆盖并保留快照。
- 快照为 `PENDING` 或 `FAILED` 时直接恢复，不要求 `force`，并把恢复前哈希写入 `recoveryHashes`。
- 并发创建同一 snapshotId 只有一个成功。

新增 `ImportAgentPublicationSnapshotTest`：

- 模拟合入写文件时抛异常，断言已写入的文件被恢复，未涉及文件不变。

### 验收

```bash
./mvnw.cmd test -Dtest=WorkspaceSnapshotServiceTest,ImportAgentPublicationSnapshotTest
```

另做一次手工演练：改三个文件，制造合入失败，比对恢复前后 `certutil -hashfile`/`sha256sum` 结果。

### 回滚

新增服务与目录；接入点回退为直接合入。已产生的快照目录可手工删除。

### 风险

- 大工程复制成本高。只做受影响文件，必要时用硬链接；硬链接在跨卷时不可用，要有复制回退。
- 与已有编辑账本的状态机交叉，接入点必须放在现有锁内。执行前先读 `ImportAgentEditLedger` 与 `ImportAgentPublication` 的锁顺序，避免死锁。
- 快照包含用户源码，属于敏感数据。目录权限与清理策略要和现有秘密隔离规则一致。

## 11. P5 固定任务基准

### 目标

用 5 个固定任务回答“改动之后整体是否还正常”。评分只认确定性信号。

### 目录结构

```text
benchmarks/
  README.md
  tasks/
    01-java-bug-fix/
      task.json
      seed/            # 初始仓库快照或子模块引用
      grade.ps1
    02-frontend-bug-fix/
    03-dependency-upgrade/
    04-api-failure-repro/
    05-import-then-test/
scripts/checks/
  run-benchmark.ps1
  agent_driver.py
  summarize.py
```

### Agent 入口

现有 `agent/app.py` 只从 stdin 读 NDJSON，没有 argv 解析、没有 CLI。Java 侧的 `AgentWorker` 有进程池和请求构造，但需要 Spring 上下文、登录会话和 Redis，不适合基准环境。

因此新增 `scripts/checks/agent_driver.py`，直接按 `docs/contracts/agent.md` 的请求格式驱动 Python 执行面：

1. 构造一行请求，字段包括 `v`、`runId`、`threadId`、`appId`、`userId`、`workspace`、`input`，ID 一律字符串。
2. 以任务副本作为 `workspace.root`，工作区名固定，避免污染主工作区。
3. 用解释器绝对路径启动 `-m app` 子进程：驱动脚本先按仓库根解析出 `<repo>/agent/.venv/Scripts/python.exe`，再把 `cwd` 设为 `<repo>/agent/`，否则 `-m app` 找不到模块。不要写相对 `cwd` 的 `agent/.venv/...`，那会解析成 `agent/agent/.venv/...`。写一行请求，读 stdout NDJSON 直到终止帧。
4. 汇总事件、token、耗时与退出状态；stderr 落到任务目录，stdout 只做协议解析。
5. 超时后终止整个进程树，不能只杀父进程。

驱动脚本本身要有单元测试：用假的子进程脚本返回固定事件流，验证正常结束、超时、协议错误和无终止帧四种情况。

边界：这条链路只衡量 Python 执行面，不覆盖 Java 鉴权、权限门禁、候选审阅与持久化。要覆盖整条业务链，需要走 HTTP 加登录，成本约 3–5 天，不在本阶段。

### `task.json` 字段

```json
{
  "id": "01-java-bug-fix",
  "repo": "local-fixture | https://...",
  "commit": "固定 sha",
  "prompt": "任务描述，给 Agent 的原话",
  "setup": ["可选命令"],
  "grade": [
    {"cmd": "命令", "expectExit": 0, "files": ["必须产出的文件"]}
  ],
  "timeoutSec": 900,
  "maxAttempts": 2,
  "model": "固定模型标识",
  "backend": "docker | local_shell",
  "notes": "评分的确定性依据"
}
```

### 评分规则

- 只认：编译退出码、指定测试退出码、服务就绪探测结果、产物文件存在且非空。
- 不认：模型自评、日志中的“已完成”、失败后的人工解释。
- 每个任务最多两次尝试；两次失败即记为失败，不用重跑覆盖。

### 运行器

`scripts/checks/run-benchmark.ps1`：

1. 校验工作区干净，任务 commit 匹配。
2. 按任务复制种子到临时目录，不污染主工作区。
3. 调用 `scripts/checks/agent_driver.py` 执行任务描述，不经过 Java。
4. 运行 `grade`，记录退出码、耗时、token 使用。
5. 输出 `docs/reports/evidence/{date}-p5-benchmark/benchmark-{sha}-{timestamp}.json` 与逐任务摘要。

### 验收

- `agent_driver.py` 的四种假子进程用例通过。
- 同一代码、同一模型连续跑两次，5 个任务的通过集合一致；不一致的任务标为 `unstable`，单独列出。
- 首次运行结果作为基线，写入 `docs/reports/` 的阶段报告。

### 回滚

目录与脚本为新增，删除即可。

### 风险

- 真实模型费用与延迟。任务数先固定为 5，不随需求临时增加；每次运行记录 token 与耗时。
- 模型版本变化会让历史结果不可比。`model` 字段必须写具体版本，不能用“默认模型”。
- 同一任务必须固定 `backend`。Docker 与 local_shell 的文件权限和网络条件不同，混用会让结果不可比。
- 任务如果依赖外网仓库，网络波动会造成假失败。优先用本地固定快照，其次才用远程固定 commit。

## 12. 排期建议

| 周 | 内容 |
| --- | --- |
| 第 1 周 | P0 完成；P1 与 P2 并行；P3 插入任意空档 |
| 第 2 周 | P4 完成；P5 启动驱动脚本与任务集 |
| 第 3 周 | P5 首次基线、处理不稳定任务、汇总报告 |

P0 不完成不进入 P1。P1 未确认生产域名不上线。P2 无 Docker 时先做脚本与单测，运行时证据留空并明确标注。

## 13. 环境与前置

| 阶段 | 需要 | 缺失时的处理 |
| --- | --- | --- |
| P0 | JDK、Node、Python venv | 阻塞 |
| P1 | 无特殊依赖，需要实际域名 | 本地可做，上线前需域名 |
| P2 | Docker CLI 与运行镜像 | 阻塞运行时证据 |
| P3 | PostgreSQL，或可断开的本地实例 | 阻塞验收 |
| P4 | 无 | 可做 |
| P5 | 真实模型密钥与预算；`backend=docker` 的任务需要 Docker | 阻塞 |

不沿用历史通过数字。测试资源清理与专用账户按现有脚本执行，不新建测试数据目录。

## 14. 阶段完成后的能力陈述

全部完成后可以陈述：

- 跨站来源有拒绝测试，未知 Origin 无法调用 REST 或建立 WS。
- 沙箱关键参数有 inspect 证据；导入不可信工程的网络边界有明确结论。
- 正式环境数据库不可用时后端启动失败，不会带病运行。
- 候选合入失败可恢复到原字节，且能证明。
- 5 个固定任务可复跑，改动的整体影响有对照。

仍然不能陈述：

- 多租户隔离已验收。
- 任意工程都能改对。
- 可以替代 IDE 或云端托管 agent。
- 性能、稳定性或安全已经达到生产标准。

## 15. 变更记录

- 2026-10-29 初稿。依据当日源码核对，未执行任何阶段。
- 2026-10-29 修订。按 review 修正 11 处：P5 增加 stdio 驱动脚本并调整工期；P1 默认来源改为空、由 local profile 覆盖；P2 改用运行时 uid 判定；P3 复用现有持久化异常与导入 ready 通道，拆出可选 P3b；P4 补快照状态机与 `afterHashes` 写入时机；证据产物改到 `docs/reports/evidence/`；统一脚本路径与 `grade` 结构；补不稳定测试处理；修正行号与文件数。
- 2026-10-29 二次修订。按复审修正 11 处：P4 补 V51 迁移、`bindSnapshot` 与账本记录变更，说明快照与 `recover()` 的执行顺序，阶段总览同步产出项；删掉 `restore` 重复且冲突的判定；P5 解释器改用绝对路径；现状表补导入容器 `--network none`；P2 不再要求核对不建容器的类；P3 状态标志改走 stderr；验收口径补阶段状态定义（完成／阻塞／未开始）；日期占位符统一；工期改为 3 周；P1 补来源列表清洗规则。
