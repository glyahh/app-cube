> 当前范围（2026-10-09用户最终确认）：原pip传输根因调查/修复指标已从第一项开发指标、验收条件、待办与延期清单中删除，不是暂缓。本次安装超时机制修复COMPLETE，可以关闭；历史网络失败仅作事实保留，pip安装功能与失败/超时/取消/安全清理验收不变。

# 安装超时修复：最终版本实施与验收报告

日期：2026-10-09。此报告替代早轮的安装验收结论，不删除历史记录。

- 最终产品源码摘要：`6711e80c644dd9cd9b630dbaaee9cd74fd9e5aa9704e68852cb058d91a8c4d81`。
- 工件目录：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125`。
- 本轮进入时工作区已有 239 条脏改动，且已含早轮异步任务/V38 实现；未 reset、未覆盖无关改动。
- 本轮差异以 `D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\baseline`、`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\current-source`、`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\implementation-manifest.json`（24 个文件）及 `D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\implementation-vs-baseline.patch` 为准；不将基线已有代码全算成本轮新增。

## 1. 根因

**同步 HTTP 承载整次安全准备与依赖安装，客户端先超时；旧验收器随即删除应用，制造后台继续执行与计划失效竞态；独立真实复现中 pip 又确实发生 HTTPS 正文读取超时。**

必须分开：

1. HTTP 超时与任务寿命耦合：机制已修复。
2. 超时触发删除、后台迟到失败：安装生命周期保护已修复，并验证准入尚无 ledger 的窗口。
3. 原 pip 传输故障：已定位至正文读取阶段与 exit 2，**失败瞬间的底层传输原因未确认、未针对网络实施修复**。后续下载成功不能倒推原故障原因。

安装全程时间由准备、重复安全准入、npm、pip、验证及清理累计产生；不能把全部 62 秒归因于 pip 23.934 秒，也不能把不同历史现场拼成同一次故障。

## 2. 证据链

### 原失败（北京时间，2026-10-08）

权威原诊断：`D:\codex\WorkDocument\install-timeout-20261008\diagnostic-204619\diagnosis-and-proposal.md`。原始证据均保留。

| 编号 | 时间 / 耗时 | 事实与依据 |
|---|---|---|
| E1 | 21:10:05.059952 | 安装请求发起；`reproduction\requests.json`，客户端 timeout=60 |
| E2 | 21:10:20.064651—21:10:30.7208147，10655ms | npm 成功，execution SUCCEEDED / exit 0 |
| E3 | 21:10:41.9601477—21:11:05.8947248，23934ms | pip 下载 Flask metadata，`files.pythonhosted.org` 响应正文读取超时，exit 2 |
| E4 | 21:11:05.074363，等待60.015s | HTTP `TimeoutError`；此时 pip 还没有保存最终失败，不能解释成后台成功 |
| E5 | 21:11:07.151782 | preparation FAILED 落库；后台请求累计62081ms；两条 execution cleanup DONE |
| E6 | 后续只读检查 | 原 app `2108183137317916672` 未删除，计划 CONFIRMED，副本、卷、输出日志保留；本次没有 STALE |

pip 原始执行日志：`D:\mainJava\all Code\program\glyahh-ai-generate-code\temp\project_import_exec\ecab217f-7101-4951-bbd6-df1f510eef8f.output.log`。

早一轮“计划失效”现场：客户端 `finally` 删除后，原请求继续复核计划，发生 `STALE / PERMISSION_CHANGED`；当时 ledger/preparation 为0，不能将该现场归因于 npm/pip。

### 修复后最终版本

| 编号 | 现场 | 关键结果 |
|---|---|---|
| E7 | `long-final`，12:19:26受理—12:21:34终态 | 发送POST后直接断连接，独立查询找回同job；安装观察128.344s，npm68120ms，最终SUCCEEDED/DONE |
| E8 | `business-release-1`，12:21:40—12:22:22安装 | POST0.031s返回；真实Vue构建、双服务、浏览器写入/权威接口读回、非法输入拒绝和停止PASS |
| E9 | `business-release-2`，12:23:14—12:24:01安装 | POST0.016s返回；第二个新应用/新计划完整业务闭环PASS |
| E10 | `cancel-delete-verified`，12:25:29—12:25:49任务 | 执行中删除拒绝，显式取消后CANCELLED/DONE，才删除专用夹具；卷/挂载/loop均释放，终态不被迟到失败改写 |
| E11 | `browser-refresh-real`，12:28:31—12:29:41 | 实际产品工作台刷新恢复同requestId/job；只提交一次，阶段耗时可见，终态解除保护，页面无错误 |
| E12 | `admission-final`，12:32:45—12:32:47 | 无execution ledger时拒绝删除，取消后CANCELLED/DONE；npm/pip均NOT_STARTED |
| E13 | `failure-final`，12:32:51—12:33:37 | npm夹具exit7，FAILED/NPM_INSTALL/EXIT_NONZERO；pip NOT_STARTED，不重跑安装 |
| E14 | `recovery-final`，12:33:47—12:34:47 | npm执行中标准重启；FAILED/NPM_INSTALL/INSTALL_INTERRUPTED，execution STOPPED/DONE；不续装，pip NOT_STARTED |
| E15 | 12:36只读运行核验 | 后端PID12660、8124监听、受保护接口401/40100；jcmd成功，恢复线程正常空闲；仅3个既有Python Worker，无本轮安装子进程 |

**注入边界：** E7的65秒、E11的20秒及取消/重启的120秒均为测试夹具 `npm preinstall` 等待；E13的exit7也是注入。只证明生命周期与失败处理，不冒充原业务自然慢点或网络复现。

8份收据全部为最终源码摘要，见 `D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\acceptance-summary.json`。各run的 `result.json` 保存请求时间、timeout、断言；`evidence-final` 保存脱敏后端诊断、Docker事件/daemon记录、真实ledger、任务/计划/preparation/发布状态、阶段计时及输出日志。

Agent均 `NOT_STARTED`，数据库 `agent_run` 为0；没有调用模型，不能将本轮计为真实模型编辑/WS验收。

Docker日志驱动为none，安装输出来自attach；已保存实际输出与Docker事件，不伪造独立stdout日志。

## 3. 修复内容

以下路径均位于 `D:\mainJava\all Code\program\glyahh-ai-generate-code`。安装范围未扩展至通用执行系统。

### 安装任务与原执行层

- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\controller\ImportStartupPreparationController.java`：`install`受理持久任务；`installJob/installCurrent/installByRequest/installCancel`查询、恢复与显式取消。公共响应保持 `BaseResponse`；requestId必填，不保留同步分支。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobService.java`：`submit/execute/align/settle`，有界单线程后台执行、用户重载、应用协调锁、提交线程退出后才执行；活worker自行收口，外部存活或身份未知执行者不抢占；重启中断明确失败不续装。`failureStage/terminalStages/unfinished`保留中断事实，不编造endTime/durationMs。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobRepository.java`：`create/requestCancel/complete/stage/execution/probe`，requestId幂等绑定原approved；preparation与job终态锁行同事务；阶段/资源追加不推进控制revision。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportInstallControl.java`：显式后台取消上下文与execution/probe登记，不依赖HTTP连接。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportInstallProbeRecovery.java`：`confirmed`，按精确名称/ID/任务标签核验探针，删除后读回；修正WSL命令引号导致inspect结果错判。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupPreparationService.java`：`installObserved/requireInstallIdle`，完整复核计划后安装；完成前再次复核；失败不继续后续依赖；活动/未成功安装阻止run/retry。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionRunner.java`：真实ledger创建后关联executionId；关联异常也进入finally；复制/materialize/命令取消联动。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionLedger.java`的`unresolvedInstall`及`ImportExecutionService.java`的`installAppClean`：覆盖ledger已建但尚未绑定job的中断窗口。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionSandbox.java`：`managed`排除活安装任务pinprobe，防通用孤儿恢复误删；原硬swap与准入不删减。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportInstallDiagnostics.java`：STARTED实时发布，单调时钟计时，原字段完整；命令耗时/exit与清理分离，未开始NOT_STARTED，execution登记前为null。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\service\impl\AppServiceImpl.java`：`deleteMyApp/deleteAppByAdmin/requireNoActiveInstall`，保留归属校验和锁内删除保护；覆盖准入前窗口，不提前删除状态。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\resources\db\migration\V39__install_job_safety.sql`：已真实应用；冻结身份/approved，终态不可修改，终态cleanup必须DONE。V38为早轮已有迁移；不修改已执行迁移。

### 前端及验收器

- `D:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend\src\preview\project-import\StartupRunPreview.vue`：`openPanel/recoverInstall/followInstall`，按owner/app保存原requestId/plan/approved，刷新恢复同job；观察中断不解锁安装保护，不删除；404后由用户显式重新提交原请求；终态才解除保护，展示真实阶段耗时。
- `D:\mainJava\all Code\program\glyahh-ai-generate-code\scripts\verify-import-formal-edit.py`：失败先保留现场；`--business --install-business`仅验安装、构建、双服务和浏览器业务；查询中断不是安装失败，HTTP仍60秒。

未采用：延长HTTP/Docker/pip timeout、替换registry/proxy、删除校验、自动重装、伪造成功、Spring Batch/Kafka/RabbitMQ、无关重构。前端安装请求120秒是原基线值，未修改。

### 可观测性

固定阶段：PLAN_VALIDATE、ADMISSION、COPY_PROJECT、PREPARE_WORKSPACE、PREPARE_VOLUME、CREATE_CONTAINER、START_CONTAINER、NPM_INSTALL、PIP_INSTALL、BUILD_PREPARE、VERIFY、CLEANUP。

日志字段：stage/startTime/endTime/durationMs/executionId/appId/commandType/exitCode/status/failureReason，加diagnosticId/jobId/spanId关联。时间点带时区，duration用单调时钟；不输出token、密码、凭据、环境秘密值或私有源码。

`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\business-release-1\evidence-final\stage-durations.json`为逐span原值；npm2865ms、pip12945ms。复制/准入/清理有多个span，父子不重复相加。独立build的业务收据另外保存；安装尚未进入build时BUILD_PREPARE明确NOT_STARTED。

## 4. 修复前后流程

```text
修复前：
POST install
  同步计划复核、复制、安全准入、卷/容器准备、npm/pip、验证
客户端60秒超时，旧finally删除应用
后台继续，真正安装失败被遮蔽，或因清理导致计划失效

修复后：
POST install
  身份/CSRF与快速持久资格检查，创建幂等job，提交后入后台队列
立即返回jobId，不等安全准备/安装完成
后台完整计划/内容/权限/版本/硬资源复核，沿用execution ledger与sandbox
GET job / by-request持续观察阶段与终态；断连、刷新不取消任务
显式cancel先落库，停止并核实资源后才收口；活动安装拒绝delete
成功SUCCEEDED；真实命令失败FAILED；确认取消CANCELLED
重启中断FAILED/INSTALL_INTERRUPTED，不自动续装
只有停止/资源核实完成才能cleanup=DONE和进入终态
失败保留应用、独立副本、卷和脱敏日志
```

实际API前缀：`/api/project/import/apps/{appId}/startup/preparations`；安装POST `/install`；状态GET `/install-jobs/{jobId}`、`/install-jobs/by-request/{requestId}`、`/install-jobs/current?planId=...`；取消POST `/install-jobs/{jobId}/cancel`。

状态模型：PENDING/PREPARING/INSTALLING/VERIFYING/CANCELLING/STOP_FAILED/RECOVERY_REQUIRED以及SUCCEEDED/FAILED/CANCELLED/REJECTED。以数据库和服务端终态为准，不用HTTP200判定安装成功。

### 当前调用链（源码位置与真实收据相互印证）

```text
[受理，不等待安装结果]
D:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend\src\preview\project-import\StartupRunPreview.vue:259
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\ai-generate-code-frontend\src\features\project-import\startup.ts:125-127
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\controller\ImportStartupPreparationController.java:32
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobService.java:40-61
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobRepository.java:35-61（持久受理）
→ HTTP立即返回job身份；前端followInstall:272观察，不以受理当成功

[后台完整执行]
D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobService.java:80-87
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupPreparationService.java:56-88
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupService.java:226（计划复核复制）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionService.java:42（初次准入）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionSandbox.java:247-289（准入、硬swap探针）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionService.java:76-98（逐命令完整资格复核）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionRunner.java:116-185（ledger、复制、workspace、volume）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportDiskVolume.java:282（平台create/seed，helper每次180秒）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionSandbox.java:304、321、326、329（create/start/attach/命令计时）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportExecutionSandbox.java:123（容器内timeout sh执行计划中的npm/pip）
→ 原runner验证/停止/容器删除及资源读回
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupPreparationService.java:87-90（最终计划复核/保存）
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobRepository.java:186（锁行、preparation与job同事务收口）

[显式取消/删除]
D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\controller\ImportStartupPreparationController.java:52
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\projectimport\ImportStartupInstallJobService.java:73（取消意图持久化）
→ 活执行者或恢复对账确认停止/资源清理后收口
→ D:\mainJava\all Code\program\glyahh-ai-generate-code\src\main\java\com\dbts\glyahhaigeneratecode\service\impl\AppServiceImpl.java:541-582 / 661-670（未完成安装拒绝删除）
```

验收HTTP60秒定义仍在`D:\mainJava\all Code\program\glyahh-ai-generate-code\scripts\verify-import-formal-edit.py:98`；保留失败现场的finally在373-375。正式前端120秒在startup.ts:127，未调大。查询请求只承载状态读操作，不承载安装。

原timeout预算保留：Docker info/image inspect各30秒；准入probe create60秒、start30秒；实际执行create120秒、start60秒；卷helper每次180秒；命令默认wall15分钟，不包含前置准备全程。容器内wall兜底加5秒是原执行保护，不是本轮延长安装预算。

## 5. 验证结果

### 构建与局部测试

- 后端编译exit0：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\compile-frozen.log`。
- Java9个suite，72项：68通过、4跳过、0失败/错误：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\frozen-test-results.json`。4项是未启用开关的Docker单测，不伪称72全通过；本轮另有真实Docker业务/生命周期验收。
- 前端2个suite、18通过；typecheck/build通过：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\frontend-tests-release.log`、`frontend-typecheck-release.log`、`frontend-build-release.log`；原有chunk-size警告保留。
- Python验收脚本2通过：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\script-tests-retention.log`。没有运行全量Agent测试或声称全仓回归通过。
- 真实PostgreSQL安全约束4通过，临时行回滚确认：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\postgresql-safety-results.json`。
- 聚焦本轮文件的git diff --check通过，manifest与patch一致。
- 标准后端重启exit0、V39成功、8124就绪。最后一次重启证据：`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\recovery-final\result.json`及同目录`restart-backend`。

### 同版本真实收据

| run | appId | jobId | 产品终态 | npm / pip | 验收 |
|---|---|---|---|---|---|
| business-release-1 | 2108412552614256640 | 8e0bb879-6304-4fbe-8eba-4aa39c289744 | SUCCEEDED/DONE | NPM_INSTALL: SUCCEEDED 2865ms; PIP_INSTALL: SUCCEEDED 12945ms | PASS |
| business-release-2 | 2108412946174189568 | f9878710-0558-46cc-b1ad-940e24615962 | SUCCEEDED/DONE | NPM_INSTALL: SUCCEEDED 3892ms; PIP_INSTALL: SUCCEEDED 12651ms | PASS |
| long-final | 2108411985661796352 | 5cae8daf-4309-44ea-b39f-489418f15f5b | SUCCEEDED/DONE | NPM_INSTALL: SUCCEEDED 68120ms; PIP_INSTALL: SUCCEEDED 13174ms | PASS |
| admission-final | 2108415341532815360 | 334c97f6-638d-4acd-906b-7fb3054361b0 | CANCELLED/DONE | NPM_INSTALL: NOT_STARTED duration=null; PIP_INSTALL: NOT_STARTED duration=null | PASS |
| failure-final | 2108415368300863488 | f852b1ea-bda5-490f-847f-995676ee0e27 | FAILED/DONE | NPM_INSTALL: FAILED 4099ms; PIP_INSTALL: NOT_STARTED duration=null | PASS |
| recovery-final | 2108415600078102528 | 2c047474-ffc3-4611-8683-1a792d752d81 | FAILED/DONE | NPM_INSTALL: INTERRUPTED duration=null; PIP_INSTALL: NOT_STARTED duration=null | PASS |
| cancel-delete-verified | 2108413513130844160 | 0951aa05-4636-4e32-9c4e-5f2a2ada39ad | CANCELLED/DONE | NPM_INSTALL: STOPPED 787ms; PIP_INSTALL: NOT_STARTED duration=null | PASS |
| browser-refresh-real | 2108414247482171392 | 0bb5a1b2-66e4-49fe-90b5-c7fcb32450bc | SUCCEEDED/DONE | NPM_INSTALL: SUCCEEDED 24737ms; PIP_INSTALL: SUCCEEDED 17947ms | PASS |

上表的PASS是对应验收断言通过；failure/recovery场景的产品任务必须是FAILED，不能改成SUCCEEDED。所有job终态均cleanup=DONE。浏览器业务是真的Vue＋Flask服务，真实工作台刷新不是Mock；无需模型调用。

### 最终资源与保留现场

- `D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\final-resource-verification.json`：8个本轮run的应用标签Docker容器查询为空/exit0；原pip失败、新exit7失败、新重启失败的源码/副本/卷挂载/输出日志均保留。
- `D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\final-retention-and-states.json`：原应用is_delete=0；8个job都有明确终态与finishedAt。
- `D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\runtime-release.json`、`backend-threads-release.log`、`backend-children-release.json`：PID12660、8124、接口40100，恢复线程TIMED_WAITING正常空闲；新进程未启动安装worker；3个既有Python Worker保留，未误杀。
- 专用取消夹具在证据保存后删除，实际持久卷/挂载/loop释放，见`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\cancel-delete-verified`；没有清理历史失败应用。
- 启动通用对账日志曾报告新重启execution `NOT_CONFIRMED`；该日志不是当前终态。后续SQL为STOPPED/DONE，精确容器查询为空、执行目录仅.output.log；不能用旧启动扫描输出覆写最终事实。日志与最终状态均保留，不删除冲突证据。
- 原有`2107413934964998144.volume.json:DISK_NOT_CONFIRMED`是无关旧资源，未修改。这里只证明本轮安装执行资源无残留，不宣称全机无问题。

### 验收清单

- [x] 每个安装阶段计时可查；未开始与重启中断不编造耗时。
- [x] 原异常阶段定位到PIP_INSTALL正文读取，区分累计耗时。
- [x] 超过60秒长安装不再制造HTTP假失败。
- [x] 客户端直接断连、真实页面刷新不破坏任务。
- [x] 后台真实命令失败有FAILED与失败阶段/原因。
- [x] 正常后台成功有SUCCEEDED明确终态。
- [x] 正确停止确认后清理；执行中delete拒绝。
- [x] 覆盖无ledger准入窗口及执行窗口，专用取消删除后无迟到终态改写。
- [x] 当前版本Vue＋Flask完整安装/构建/双服务/浏览器/停止闭环，两次新应用通过。
- [x] 原安全准入、硬swap、权限、内容、版本、删除前停止校验保留；PG约束与幂等绑定加固。

### 精确重放（手动执行，不在本轮自动再跑）

仓库根目录使用新输出目录、新应用/新计划；不要复用保留失败应用的旧资格或自动重复安装。

```powershell
.\mvnw.cmd -q -DskipTests compile
.\mvnw.cmd test '-Dtest=ImportStartupInstallJobServiceTest,ImportStartupInstallJobRepositoryTest,ImportInstallProbeRecoveryTest,ImportStartupPreparationServiceTest,ImportStartupPreparationControllerTest,ImportExecutionSandboxTest,ImportExecutionRunnerTest,ImportExecutionServiceTest,ImportInstallDiagnosticsTest'
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1"
.\agent\.venv\Scripts\python.exe scripts\verify-import-formal-edit.py --business --install-business --output D:\codex\WorkDocument\install-timeout-replay\business-new
.\agent\.venv\Scripts\python.exe D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\verify_install_lifecycle.py --mode long-disconnect --output D:\codex\WorkDocument\install-timeout-replay\long-new
```

生命周期其余模式：`cancel-admission`、`failure`、`restart`、`cancel-delete`（以已保存`final-safety-commands.json`与各run原始command为准）。前端真实刷新命令：`node D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\verify_install_browser_refresh.mjs D:\codex\WorkDocument\install-timeout-replay\browser-new`（需要正式前端localhost:5173已就绪）。夹具参数保存于`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\verify_install_browser_refresh.mjs`；运行前核对该脚本参数和前端真实地址。

只读核验可使用`D:\codex\WorkDocument\install-timeout-20261009\implementation-1125\collect_install_evidence.py --identity <run的install-identity.json> --output <新的证据目录> --backend-log <对应后端日志>`。查询SQL与secret脱敏逻辑在脚本中；不重新提交安装。

## 6. 是否可以关闭此 Bug

**可以关闭。**

用户明确要求从第一项开发指标中删除“原pip HTTPS响应正文读取超时的底层原因调查及针对性修复”。该附加指标不再属于第一项开发目标、验收条件、阻塞项、待办或延期清单，不自动恢复；此前暂缓口径被撤销。

安装HTTP生命周期解耦、阶段可观测性、断连/刷新恢复，以及取消/删除/重启恢复保护已取得当前版本真实验收证据，标记 **COMPLETE**。pip正常安装、真实失败明确终态、超时/取消、安全准入及资源清理验收保持不变。

原传输失败记录保留为历史事实，不标为已经修复；不修改timeout、换源或自动重装，不因指标删除自动清理现场。原较大范围报告与此前暂缓记录保存在工件备份目录，均不参与当前完成判定。

第一项其余有效开发指标保持原样；本次不代表整项、WS、真实模型编辑或原完整矩阵已通过。
