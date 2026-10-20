# 执行副本、槽位与分阶段网络

日期：2026-10-05。代码版本 `d452804` 之上的未提交改动。**第一项 NOT_COMPLETE。** 有效私有仓库成功（G02、G04）继续暂缓。S05 没有真实模型补充成功，不记通过。

## 实现

- 每个应用一份执行副本：`apps/{appId}`，旁边用 `{appId}.source` 记住来源哈希。来源变了记 `PLAN_CONTENT_CHANGED`，不覆盖已有副本。安装和构建结束后删容器，副本留下。超过计划字节上限记 `DISK_QUOTA`，不截断后报成功。不写回导入工程，也不写保护快照。
- 服务使用 `SERVE`：守卫放行且容器仍在运行才记 `RUNNING`。命令自己退出（包括退出码 0）记 `FAILED` / `NOT_READY`，不记就绪。服务进程不再套容器内超时。停止以 `inspect` 查不到容器为准；查得到就记 `STOP_FAILED`，不释放重试。
- `V28`：未结束执行的唯一约束从「一个应用一行」改为「一个应用的一个槽位一行」。安装槽位是 `install`，构建和服务槽位是种类加内容哈希前 32 位。旧行用常量默认值 `legacy` 补齐，不 UPDATE 已结束行（结束行触发器会拒绝改写）。V26 未改。
- 扫描和未声明端口的服务保持 `--network none`。已同意的安装和已列明的构建使用网络 `glyahh-imp-egress`，不发布端口，不用宿主网络。声明了端口的服务只绑定 `127.0.0.1`，并记录本次地址。没有声明端口时不编造 HTTP 地址。内存仍先钉 `memory.swap.max=0`，钉不住不运行命令。
- 工作台：已就绪且没有地址时显示「未声明端口，不提供访问地址」。停止失败显示原因，并藏起重试。
- Git / 扫描辅助进程仍登记子孙。观察到的子进程还在时，停止结果不是成功。容器进程以 cgroup / 容器消失为准，不另建一套宿主进程树。

## 本次命令

| 命令 | 退出码 | 结果 |
|---|---|---|
| `.\mvnw.cmd -q test "-Dtest=ImportExecutionSandboxTest,ImportExecutionRunnerTest,ImportStartupPreparationServiceTest,ImportStartupPreparationControllerTest"` | 0 | 持久副本、槽位参数、出站参数与准备编排通过 |
| `$env:IMPORT_EXEC_PIN='1'; .\mvnw.cmd test "-Dtest=ImportExecutionSandboxTest#livePersistKeepsInstallFilesAndServeStaysUntilRemoved"` | 0 | 25.29s，1 项通过、0 跳过。副本里 `check.sh` 先失败；只改副本后再跑成功；原件仍是 `exit 1`；出站网卡有 `eth0`；`echo` 的退出码 0 记 `NOT_READY`；`sleep` 保持 `RUNNING` 且无端口绑定，随后确认容器消失 |
| `$env:IMPORT_EXEC_PIN='1'; .\mvnw.cmd -q test "-Dtest=ImportExecutionSandboxTest#livePersistKeepsInstallFilesAndServeStaysUntilRemoved,ImportProcessRunnerTest"` | 0 | 含补丁后的窄链路，以及辅助进程子孙登记 / 父进程结束后不把仍在跑的子进程当成成功 |
| `.\agent\.venv\Scripts\python.exe scripts\archive\verify-startup-execution-schema.py` | 0 | 临时 schema 内 V26+V28：同一槽位只能有一个未结束执行，另一槽位可以同时未结束；结束行不能改写。事务回滚 |
| 重启 `scripts\restart-backend.ps1` | 0 | `http://localhost:8124/api` 已监听。Flyway 26/27/28 均为 success，无失败行，`slot` 列存在 |
| 前端 `StartupRunPreview.test.ts` | 0 | 8 项。未声明端口不出现地址；停止失败不显示重试 |
| `scripts\checks\verify-startup-plan-api.py` | 0 | 8 组。识别、手动配置和冻结计划回归。脚本自身仍标第一项 NOT_COMPLETE |
| `scripts\checks\verify-startup-inspection-api.py` | 0 | 8 组。文件夹导入后的只读检查与保护字节 |
| `scripts\checks\verify-import-task-api.py` | 0 | 47 项。文件夹暂存，未提交运行 |
| `scripts\checks\verify-import-credentials.py` | 0 | 42 项。虚构 Token |
| `scripts\checks\verify-import-auth-negative.py` | 0 | 23 项。无效 Token 负例，不是私有成功 |
| `scripts\checks\verify-import-source-request.py` | 0 | 来源找回 |
| `scripts\checks\verify-import-source.py` | 0 | 23 项。公开来源 |

证据目录在 `temp/import-startup-preparation-20261005/`、`temp/import-startup-isolation-20261005/`、`temp/import-startup-freeze-plan-20261005/`、`temp/import-startup-business-20261004/`、`temp/import-backend-publication-20261004/source-request-api`。这些目录不入库。

## 矩阵

单元和上面的真实命令**不把整组改成通过**。

| 范围 | 本次结论 |
|---|---|
| D01–D08、R01–R08 | 见下方「受控夹具的安装与运行」。当时还没有这轮接口证据 |
| 文件夹、凭据、公开 Git、来源恢复、识别、手动配置 | 上表对应脚本本次通过。这是这些入口的回归，不是 58 组里每一组的端到端通过 |
| S05 | `NOT_RUN`。没有真实模型补充成功 |
| E04、E05 | 只做了执行器窄链路：干净副本、`check.sh` 失败、只改副本、再跑成功、原件不变。没有真实业务缺陷、补丁评审和业务回归，不把这两组记成产品通过 |
| G02、G04 | 继续暂缓。无效 Token 负例通过，不算私有成功 |

## 2026-10-05 受控夹具的安装与运行

`.\agent\.venv\Scripts\python.exe scripts\checks\verify-startup-lifetime-api.py` 退出码 0。证据：`temp/import-startup-preparation-20261005/lifetime-1791195147876482600`。只用脚本生成的小工程，不调用模型，不访问私有仓库。

| 组 | 本次实际核对 | 结论 |
|---|---|---|
| D01 | 导入和识别后执行表为空，且没有本应用容器 | 这条通过 |
| D02 | 拒绝安装不产生执行，之后仍能再次调用安装 | 这条通过 |
| D03 | 无安装命令时确认安装不启动；写明 `printf` 时只安装、服务未启动 | 这两条通过 |
| D04 | 依赖未确认，以及用户拒绝后，运行都停在等待安装 | 这两条通过 |
| D05 | 安装成功后才进入启动；安装执行只有一条成功记录。该服务命令自己退出，结果是 `NOT_READY`，没有共享构建失败只挡住相关服务的样本 | 不记整组通过 |
| D06 | `exit 3` 安装失败后运行仍失败，且没有服务执行 | 这条通过 |
| D07 | 未写安装命令为 `BLOCKED`；安装成功后依赖状态仍是 `UNKNOWN` | 这条通过 |
| D08 | `installed.txt` 只在执行副本；导入工程哈希不变 | 这条通过 |
| R01 | 只选中的网页进入执行，未选服务没有执行。没有再跑聚合脚本偷偷启动未选项 | 不记整组通过 |
| R02 | `http://127.0.0.1:32770/` 返回 `{"ok":true}`。这是单个受控 HTTP 服务，不是前后端业务交互 | 不记整组通过 |
| R03 | 构建 `exit 4` 的服务停在构建失败；`quiet.py` 退出码 0 记 `NOT_READY` | 这两条通过，整组里的超时、端口冲突未跑 |
| R04 | 构建失败时，已经就绪的网页容器和地址还在 | 这条通过 |
| R05 | 重试后网页的执行编号不变 | 这条通过；重试前改工作区未跑 |
| R06 | 再点一次运行不新建网页执行 | 这条通过；并行导入未跑 |
| R07 | 停止后按标签已查不到本应用容器 | 这条通过；停止失败注入和重启对账未跑 |
| R08、S05 | 未跑 | `NOT_RUN` |

上面这张表只覆盖 `verify-startup-lifetime-api.py`。共享构建、聚合脚本、工作区失效、启动超时和资源上限见下一节。

## 2026-10-05 能用受控夹具补上的缺口

`.\agent\.venv\Scripts\python.exe scripts\checks\verify-startup-lifetime-gaps.py` 退出码 0，报告 `status` 为 PASS。证据：`temp/import-startup-preparation-20261005/gaps-1791195967946240000`。仍是脚本生成的小工程，不调用模型，不访问私有仓库。这些通过不把对应矩阵整组改成通过。

| 组 | 本次实际核对 | 结论 |
|---|---|---|
| D05 | 两个服务共用 `shared` 目录和 `exit 4`。构建执行只有一行，状态 `FAILED` / `EXIT_NONZERO`，两个服务都停在构建阶段。无关的 `other` 服务为 `READY` 并带本机地址 | 这条通过。上一轮「安装后命令自己退出记尚未就绪」仍然成立，不把 D05 整组改成通过 |
| R01 | `web/package.json:dev` 是 `concurrently` 聚合脚本，未确认展开范围时确认计划返回 40000。只确认手动 `solo` 时，执行目录只有 `solo`，容器只有一个 | 这条通过。没有在浏览器里跑真实 `concurrently` 同时拉起前后端，不把 R01 整组改成通过 |
| R03 | 声明端口 8080 的 `sleep 60` 在就绪时限内记 `FAILED` / `NOT_READY`，容器已删除 | 这条通过。宿主端口冲突未跑 |
| R05 | 改 `other/app.py` 后原计划为 `STALE` / `INSPECTION_STALE`，重试返回 40900，旧执行仍是 `RUNNING`。新计划执行编号不同，旧容器标签已消失 | 这条通过 |
| R08 | 安装命令 `yes` 记 `FAILED` / `OUTPUT_LIMIT`，服务未启动。1280MiB 分配的执行行是 `FAILED` / `MEMORY_LIMIT`；安装记录按含 MEMORY 的原因记 `REFUSED`，随后运行保持 `REFUSED` 且没有容器。导入工程里的 `alloc.py` 仍在 | 输出上限和内存硬上限这两条通过。CPU 节流和写满磁盘未跑 |

明确没做，因为触发条件过苛、当前做不到，或必须由用户提供：

- R02：两个服务都只发布到本机回环，容器内访问不到对方的 `127.0.0.1`，做不了前后端互调。
- R03 端口冲突：宿主端口由引擎分配，两个服务不会抢同一个宿主端口。
- R06 并行导入：冲突策略还没有单独定死。上一轮「再点一次运行不新建已就绪执行」仍然有效。
- R07：停止失败要制造杀不掉的容器；重启对账会打断当前后端。上一轮「停止后容器消失」仍然有效。
- R08 的 CPU 节流和写满磁盘没有稳定的短时判定。字节上限仍是执行器自己数的，不是内核磁盘配额。
- S05 需要真实模型补充成功。
- E04、E05 需要真实业务缺陷，不用脚本故障冒充。执行器窄链路仍只算窄链路。
- G02、G04 有效私有仓库继续暂缓。
- 文件夹、其余 Git、凭据泄漏、命名摘要、其余识别和失败恢复没有按矩阵整组重跑。

第一项在这一节之后仍是 **NOT_COMPLETE**。能继续落地的测试见下一节。

## 2026-10-05 能落地的补测

模型子进程原先只继承 `SystemRoot`，读不到 `.env` 里的模型配置。现已只传递模型配置和证书所需的宿主变量，不把这些值写入日志。`ImportStartupModelExecutorTest` 与 `ImportExecutionRunnerTest` 退出码 0。后端已重启，`http://localhost:8124/api` 在监听。

`.\agent\.venv\Scripts\python.exe scripts\checks\verify-startup-remaining.py` 退出码 0，报告 `status` 为 PASS。证据：`temp/import-startup-preparation-20261005/remaining-1791199554804778000`。密钥没有写入这份证据。

| 组 | 本次实际核对 | 结论 |
|---|---|---|
| R02 | 前端 `http://127.0.0.1:32787/` 含「前端页面」，接口 `http://127.0.0.1:32788/` 返回 `{"service":"api","item":"sample"}`。请求来自宿主机，不是容器互访 | 这条通过 |
| E04、E05 | 原件打印 `pang`。只改执行副本为 `pong` 后再跑，输出是 `pong`，导入工程原件仍是 `pang` | 这条窄闭环通过 |
| R08 | 8 线程忙循环 4 秒，`nr_throttled=39`。副本逻辑大小超过 1GiB 时安装为 `FAILED` / `DISK_QUOTA` | 这两条通过 |
| R06 | 同时导入两份，得到两个应用，文件分别是「并行甲」「并行乙」 | 这条通过 |
| R03 | 18765 已被占用时，第二只容器启动失败，原因是 `port is already allocated` | 这条通过。这是引擎按固定端口发布时的失败，正常服务仍用随机端口 |
| S05 | `api` 因没有启动脚本保持未识别。真实模型返回并发布候选 `node server.js`，依据是 `api/README.md` 和 `api/server.js` | 这条通过 |
| G02 | 私有仓库 `glyahh-import-g02-probe` 导入成功，commit `bbf044de36c2825a85982eb71fdce4e1caacc0d0`，工作区有 1 个文件且不含 Token | 这条通过 |

同一轮还重跑了已有入口脚本，退出码都是 0：

| 命令 | 结果 |
|---|---|
| `scripts/checks/verify-import-task-api.py --commit` | PASS，55 项。证据 `temp/import-startup-preparation-20261005/matrix-rerun/folder` |
| `scripts/checks/verify-import-credentials.py` | PASS，42 项。证据 `.../matrix-rerun/credentials` |
| `scripts/checks/verify-import-auth-negative.py` | PASS，19 项。证据 `.../matrix-rerun/auth-negative`。无效 Token，不是私有成功 |
| `scripts/checks/verify-import-source.py` | PASS，23 项。证据 `.../matrix-rerun/source` |
| `scripts/checks/verify-import-source-request.py` | PASS。证据 `temp/import-backend-publication-20261004/source-request-api` |

没有做：Gitee 私有仓库（没有 Gitee Token）、杀不掉的容器、把磁盘写满。重启对账仍以 `ImportExecutionRunnerTest` 的死执行者核对为准，没有重启正在服务的后端。上面任何一条都不把 58 组整组改成通过。

第一项保持 **NOT_COMPLETE**。

## 边界

- 副本上限是执行器自己数字节，不是可单独开关的内核磁盘配额。
- 宿主进程树只覆盖 `ProcessHandle` 能看到并登记的子孙。看不到的进程不能被记成已停止，也不会被这个登记表冒充已经杀掉。
- 持久副本挂进容器后，用 WSL `chmod -R a+rwX` 让非 root 用户能写。chmod 失败时安装会如实失败，不会改成 root 或宿主网络。
