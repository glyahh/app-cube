# 隔离执行副本：步骤 5 增量

日期：2026-10-05。分支 `code_board`，HEAD `d452804`；改动前 `git status --porcelain` 基线（146 条）见 `temp/import-startup-isolation-20261005/git-status-before.txt`，改动后见同目录 `git-status-after.txt`，本步源码 SHA256 见 `source-manifest.txt`。**第一项 NOT_COMPLETE；本步只建成后端内部“隔离副本执行原语”，没有控制器/接口/前端入口引用它，安装与运行仍不对用户开放（步骤 6–7）；只用本步自建的受控夹具执行过脚本，从未运行用户真实导入工程；S05 未声称通过。**

## 环境事实（决定实现方式）

- 开发机为 Windows；仓库原有 `runtime` 模块已通过 `wsl.exe` 调用 WSL Ubuntu 中的 Docker（`DockerCli`），并已有沙箱镜像 `glyahh-sandbox-runtime:24.04`（python3.12、node 22）。Windows 侧无 Docker Desktop，未引入 JNA/Job Object 新依赖，改为复用已有 WSL Docker。
- `docker info`：Docker 29.1.3、cgroup v2、cgroupfs 驱动、WSL2 内核 6.18；`MemoryLimit/PidsLimit/CpuCfsQuota=true`，**`SwapLimit=false`**；WSL 交换区 4096 MB。
- 实测：`--memory 64m --memory-swap 64m` 的容器仍能分配 256 MB（`memory.swap.max=max`，docker 提示 kernel does not support swap limit）。**内存硬上限在本机无法保证。**
- 用户已有的容器（freqtrade 运行中、octobot、旧 `glyahh-sbx-*`）全程未触碰；本步容器统一前缀 `glyahh-imp-exec-`、标签 `glyahh.import.execution`，核对与清理只针对该标签。
- 未修改防火墙、管理员配置、`.wslconfig` 或 Docker daemon 配置。

## 实现

- `V26__project_import_startup_execution.sql`：执行登记表 `biz.project_import_startup_execution`。记录 owner/app/计划（外键）、planRevision、contentHash、目录、命令哈希、限额 JSON、执行者身份（`executor_pid` + `executor_start`）、容器名/容器 id（64 位十六进制）/容器启动时间、状态、原因、退出码、输出字节、清理状态、revision。状态 PREPARING/RUNNING/STOPPING/SUCCEEDED/FAILED/REFUSED/STOPPED/STOP_FAILED/INTERRUPTED；部分唯一索引保证同一应用最多一个未结束执行；触发器冻结身份字段、已登记容器不能换，结束行只允许清理 FAILED→DONE。
- `ImportExecutionSandbox`：Docker 执行引擎（`wsl.exe --exec`，参数原样传递）。
  - 预检：读取 docker 能力与镜像；`SwapLimit=false` 时启动探针容器读 SwapTotal，非 0 即 `MEMORY_LIMIT_UNENFORCEABLE`。
  - 创建容器：`--network none`、`--read-only`、`--user 65534:65534`、`--cap-drop ALL`、`no-new-privileges`、memory=memory-swap、cpu-period/quota、pids-limit、ulimit、`/work` tmpfs（磁盘上限）、`/tmp` tmpfs、`--shm-size 1m`、`--log-driver none`；副本以只读方式挂到 `/input`。命令与随机 nonce 只经 env-file 传入。
  - 启动前先登记容器 id，再 inspect HostConfig 与请求逐项比对（内存、CPU、进程、网络、特权、tmpfs、挂载集合、用户、端口、设备），任何不符删除容器并 REFUSED `LIMIT_NOT_APPLIED_*`。
  - 容器内守卫脚本：等待 go 信号后核对 pids.max/cpu.max/memory.max；硬内存模式要求 swap.max=0 或 SwapTotal=0；只允许 `lo` 网卡；根文件系统与 `/input` 不可写；`/work` 容量符合；`cp -R /input/. /work/` 后清除变量、输出带 nonce 的 OK 标记，再 `exec timeout -s KILL` 运行命令。没有带 nonce 的 OK 标记不判成功（脚本无法伪造）。
  - 运行中监控输出上限、耗时、停止请求，任一触发即 kill；`attach --sig-proxy=false`，执行者进程被杀不会把信号转给容器；失联且容器仍在则 `ATTACH_LOST` 并 kill。
  - 结束：读退出码与 OOM 标记，`rm -f` 后必须 inspect 到 “no such object” 才算资源退出，否则 `STOP_FAILED`。
- `ImportExecutionLedger`：所有状态变化按 revision CAS。
- `ImportExecutionRunner`：预检或存在未解决执行时只写 REFUSED 行，不复制、不建容器；否则在工作区锁下用 `ImportStartupSnapshotStore` 捕获到独立根 `temp/project_import_exec`，contentHash 必须等于已确认计划，否则 `PLAN_CONTENT_CHANGED`；核对磁盘大小后物化 `<id>.workspace`。原工作区与保护快照只读不写。执行失败只清理本次副本，不删除导入工程；保留 `<id>.output.log`。
  - `stop`：只置停止意图，以执行者确认容器已消失为准；执行者已不在则走核对。
  - `reconcile`（启动时后台线程）：执行者已消失的行先删容器并确认消失，记 INTERRUPTED/EXECUTOR_GONE（不能确认则 STOP_FAILED）；清理失败行重试；无活跃登记的带标签容器删除；孤立副本文件删除（输出日志保留）。
- `ImportExecutionService`：内部服务，要求硬内存模式、计划 CONFIRMED、目录属于计划的服务/依赖/构建目录、工作区由 `apps.resolveWorkspaceDir` 解析。**无控制器引用**（`rg ImportExecutionService src/main/java` 只命中本包两个文件）。
- `ImportStartupSnapshotStore` 增加 `materialize/materialized/discardMaterialized`（委托已有 staging 方法）；修改前副本 `before/ImportStartupSnapshotStore.java.before`（与原文件 SHA256 `451defe5…` 一致）；`before/` 中其余 `.before` 是本步新增文件的较早版本，不是原有文件。

### 限额与失败即拒绝

| 边界 | 默认 | 执行方式 | 本机能否保证 |
|---|---|---|---|
| CPU | 1 核 | cpu.max 配额 | 能（实测 50000/100000 节流） |
| 内存 | 1 GiB | memory.max = memory-swap | **不能**：无 swap 限制，交换区可越过上限 → 生产执行 REFUSED `MEMORY_LIMIT_UNENFORCEABLE` |
| 进程数 | 128 | pids.max | 能 |
| 磁盘 | 1 GiB（副本）、64 MiB `/tmp` | tmpfs 容量 + 只读根/输入 | 能（副本超过上限执行前拒绝） |
| 耗时 | 15 分钟 | 执行者计时 kill + 容器内 `timeout -s KILL` | 能 |
| 输出 | 4 MiB | 执行者计数截断并 kill | 能 |
| 网络 | 无 | `--network none`（仅 lo） | 能，无需防火墙改动 |

任何能力探测失败、HostConfig 不符或守卫核对失败都拒绝执行，不降级运行。

## 验证（仅受控夹具）

命令（在仓库根目录；数据库凭据经 `GLYAHH_HARNESS_*` 环境变量传给 harness，不写入文件）：

```powershell
.\agent\.venv\Scripts\python.exe scripts\checks\verify-startup-isolation-real.py --restart
```

脚本新注册带标签的夹具账号，导入自建的夹具文件夹（`tools/*.sh`、`*.py` 小脚本），识别、选 `index.html` 候选生成并确认计划；记录工作区与保护快照（`temp/project_import_private/<taskId>/<n>.bin`）哈希；经 `mvnw.cmd` 运行真实 harness（`ImportExecutionRealHarnessTest`，默认跳过）；再核对两者未变。

最终证据 `temp/import-startup-isolation-20261005/real-1791140054717768000/`（`result.json`、`harness-results.json`、日志、`crash-marker.json`、`runtime/`），**19 项 PASS**，appId `2106820210665017344`，计划 `af321fb8-4d84-4f21-8d80-c3f8be7dc65d`；该应用账本 SUCCEEDED 7、FAILED 4、REFUSED 2、STOPPED 1、INTERRUPTED 1。

| 用例 | 结果 |
|---|---|
| 默认硬内存 | REFUSED `MEMORY_LIMIT_UNENFORCEABLE`，无容器、无副本 |
| 正常 | SUCCEEDED，在 `/work` 以 uid 65534 运行 |
| 非零退出 | FAILED `EXIT_NONZERO` 3，导入工程保留 |
| 篡改 | `/input` 写/删被拒；副本 `/work` 可写；`/mnt/d` 不可见；用户命令环境中 nonce 0 次 |
| 内存缺口（仅验证模式） | 128 MiB 上限下分配到 384 MB，swap.current≈275 MB——即上述已知缺口 |
| fork | 成功 29、失败 71；pids.max/current/peak 均 32 |
| 磁盘 | `/work` 32 MiB、`/tmp` 8 MiB 封顶；根与 `/input` 为 EROFS |
| 输出 | FAILED `OUTPUT_LIMIT`，退出 137，日志恰好 262144 字节 |
| 超时 | FAILED `TIMEOUT`，退出 137 |
| 网络 | 只有 lo；TCP 全部失败；DNS 失败；未改防火墙 |
| CPU | cpu.max 50000/100000；墙钟 3879 ms、CPU 1954 ms、节流 39 次 |
| 内容漂移 | FAILED `PLAN_CONTENT_CHANGED` |
| 停止 | STOPPED；容器 cgroup 内的 WSL 进程 37660 停止后消失（4368 ms） |
| 并发第二次 | REFUSED `PREVIOUS_EXECUTION_UNRESOLVED` |

- 原工作区与保护快照字节在全部用例前后一致（`result.json` 中哈希比对）。
- 重启核对：运行中的执行 `68d9b9d5-21be-4d88-959a-af3d0478929f` 被 `taskkill /F /T` 杀掉执行者 JVM，证明残留（容器仍运行、行 RUNNING、副本存在），另加孤立带标签容器与孤立文件；按标准脚本重启后端，日志 `导入执行对账完成 rows=1 containers=1 files=2 unresolved=[]`，行变为 INTERRUPTED/EXECUTOR_GONE/cleanup DONE，两个容器均消失，副本删除，输出日志保留。
- 回归结束时：带标签容器 0 个；`temp/project_import_exec` 只剩输出日志。
- 自审过程中前 5 次真实运行（`real-run-1..5.log` 与对应 `real-*` 目录，保留）暴露并修复了：`wsl.exe --` 改写 `$`/`|`、`-v` 挂载顺序随机导致误判、`docker start -a` 把执行者被杀的 SIGHUP 转给容器（退出 129）、docker “no such object” 小写判断等问题；最终第 6 次（`real-run-6.log`）全部通过。这些运行留下的带标签夹具应用未删除，与前几步做法一致；其中第 3 次的残留已在第 5 次的重启核对中被收尾（rows=2）。

## 回归

证据均在 `temp/import-startup-isolation-20261005/`：

- Java：`regression-java.log` 退出0，`regression-java-summary.txt` **54 类 515 项，0 失败 0 错误，2 跳过**（新增执行相关 26 项；第二个跳过是默认不运行的真实 harness）。
- pytest：`regression-pytest.log` **357 passed**。
- Schema：`regression-schema-{approval,model-lease,plan,execution}.log` 全部 PASS。新增 `scripts/archive/verify-startup-execution-schema.py`：在事务内临时 schema 中套用 V26 并执行账本实际 SQL，验证唯一未结束执行、revision CAS、容器登记一次、身份/结束行冻结、清理补记，全部回滚。
- API：`regression-api-inspection.log`（`--python --config --async --cancel --recovery --rules`）**19 项 PASS**；`regression-api-approval.log --restart` **9 项 PASS**；`regression-api-candidate-publish.log --restart` **7 PASS、1 NOT_COVERED**（真实模型未成功 `MODEL_RESPONSE_FAILED`，与前几步一致）；`regression-api-plan.log --restart` **8 项 PASS**；均退出0。三次标准重启后后端在 8124 正常服务（最终 PID 53588）。
- 前端未改，未运行前端单测。

## 待用户决定与后续

- **内存硬上限（需要用户决定，均未执行）：**
  - (a) `%USERPROFILE%\.wslconfig` 设 `swap=0` 后 `wsl --shutdown`——会停止 WSL 内全部容器（包括用户的 freqtrade）；
  - (b) 修改 Docker daemon / cgroup 交换计量配置；
  - (c) 后端经 `wsl -u root` 为每个容器写 `memory.swap.max=0`——需要 root 提权。
  - 在此之前生产执行一律 REFUSED；harness 的 `VERIFICATION_ONLY_UNGUARANTEED` 模式只用于证明其余边界，服务层拒绝该模式。
- 已有 `runtime` 模块 `DockerCli.run` 使用 `wsl.exe -- cmd`，参数中的 `$`、`|` 会被默认 shell 改写；本步新引擎用 `--exec`，未改原模块，属潜在问题。
- 输出日志 `<id>.output.log` 目前永久保留，需要保留期/清理策略。
- `/work` 是 tmpfs，执行产物（依赖、构建结果）容器删除后不保留；步骤 6–7 的安装/运行若需持久产物，需要设计受限的可写卷。
- 受控执行入口（步骤 6–7）在本步之后才可讨论，本步未开放。

## 回退

- 删除新增的 5 个 Java 主文件、5 个测试、两个脚本，恢复 `before/ImportStartupSnapshotStore.java.before`。
- V26 已被 Flyway 应用（版本 26）。回退需另写迁移删除表、触发器与函数，不能直接删迁移文件；表中只有夹具执行记录。
- 清理：`docker rm -f` 带 `glyahh.import.execution` 标签的容器（当前 0 个），删除 `temp/project_import_exec`。
