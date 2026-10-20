# 持久扫描流程：真实后台任务与取消

2026-10-04；`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。用户已批准启动后端。仅第一项，扫描不安装、不运行工程、不调用模型。

## 实际接入

- V21 已应用，不再是[账本预检节点](project-import-startup-scan-ledger-verification.md)的未应用草案。固定 2 个只读 worker、有界队列；扫描请求先验证 App owner/导入资格/目录与配置版本，持久分配同一 `scanId` 给内容副本、Python 私有输入和资源账本。
- 后台扫描通过短事务准入、冻结 request、租约/版本/epoch 检查；Python PID/启动时间也写数据库。停止/私有输入清理确认后，在同一数据库事务中发布完成证据与任务成功；取消后的旧 lease 不发布候选。
- 原同步 `POST startup/inspection` 现在也经过相同准入和后台扫描，没有保留一个网络入口绕过任务账本。同步等待中断/超时只提示查状态，不盲取消正在运行的任务。
- 相同 request 的重复提交直接找回原状态，不排队重跑；找回只返回历史任务元数据，不授予当前代码或执行权限。完成候选仍走既有实际 owner/当前完整内容资格检查。
- 取消先 CANCELLING；执行者结束后核对私有输入不存在和记录的资源不在运行，才 CANCELLED。未确认保留 STOP_FAILED/RECOVERY_REQUIRED，占用不释放；不会因取消失败删除导入应用。
- 原完成记录/配置接口保留。完整任务重启资源对账、过期调度、STOP_FAILED 实际重试停止、硬 CPU/内存/磁盘总额度仍未实现；正常取消不能代表全部停止故障已验收。

## 接口

前缀 `/api/project/import/apps/{appId}/startup`，全部沿用会话、来源/CSRF 和 no-store。

| 方法与路径 | 输入/结果 |
|---|---|
| POST `/scans` | ownerId、requestId(UUID)、configRevision、contextEpoch、scope；返回任务 View |
| GET `/scans/{scanId}` | owner 绑定状态、string revision、cleanup 和脱敏错误，不暴露执行者/资源账本 |
| GET `/scan-requests/{requestId}` | 请求丢失只读找回，不重新扫描 |
| POST `/scans/{scanId}/cancel` | ownerId、revision；拒绝旧状态版本 |
| POST `/inspection` | 原 ownerId/scope；相同任务执行器，返回完成检查或明确错误 |

SUCCEEDED 是扫描流程完成，逐范围 FAILED/INCOMPLETE/AMBIGUOUS 等仍由诊断保留，不等于所有服务已识别，更不是运行就绪。

## 实际命令与证据

```powershell
./mvnw.cmd -q test '-Dtest=ImportStartupControllerTest,ImportStartupServiceTest,ImportPythonStartupReaderTest,ImportStartupScanRepositoryTest'
./mvnw.cmd -q test '-Dtest=ImportStartupScanServiceTest,ImportStartupControllerTest,ImportStartupServiceTest,ImportStartupScanRepositoryTest,ImportPythonStartupReaderTest'
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
./mvnw.cmd -q test '-Dtest=ImportStartupScanServiceTest,ImportStartupControllerTest'
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -TimeoutSeconds 180 -LogDirectory "temp/import-startup-scan-flow-20261004/runtime-idempotent"
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --async --cancel
```

证据根 `temp/import-startup-scan-flow-20261004/`：

- 新 worker 5 项：正常提交、取消期间迟到完成被拒、未清理私有输入保持 STOP_FAILED、幂等不再调度、foreign/旧 rev 拒绝。Controller 新增 3 项覆盖冻结参数、会话路由和取消必填。worker 单元是明确 Mock，不冒充真实停止故障。
- `unit-first.log`、`unit-worker.log` 初步通过；初次 `combined-regression.log` 失败，344 项中 Controller 7 ERROR，原因是新 cancel 控制器测试调用漏传 UUID；保留日志，修正测试签名。`controller-final.log`、`combined-qualified.log`、`idempotent-qualified.log`、`worker-final.log`、`combined-final.log` 均退出 0。最终 **37 类 344 项，343 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**；最终 XML `junit-final/`。原 Git 网络条件未启用，警告/预期异常保留。
- 第一次真实 API `real-api-first.log` 失败，`api-1791120133224304900/result.json` 保留：后台扫描已成功，但马上重复提交先走 workspace 校验，被正在建立输入的锁拒绝。修正共享 create 入口：先按 owner/request 核对冻结参数找回旧任务，重复请求不再碰工作区；新增断言只验证一次目录校验，未放松新任务准入或取消 CAS。
- 标准首启实际 PID44668，V21 成功迁移、13.082 秒启动，`runtime/`；修正并测试后标准重启实际 **PID38612**、8124，8.02 秒启动，`runtime-idempotent/` 与 `restart-idempotent.log`。外层后台管道观察未结束，不虚构退出码。
- 新独立基线真实 API **14 组 PASS**，Python 退出 0：`real-api-qualified.log`；结果 `temp/import-startup-business-20261004/api-1791120367499018400/result.json`。包含实际 folder 提交/静态+Python只解析、scope/owner拒绝、后台创建/重复/异输入/request找回、真实取消不发布候选、配置 CAS/手动优先、跨连接占锁拒绝、内容/epoch失效、并发同版本一次保存、保护原字节和私有清理。
- app `2106737643748179968`、task `bfb57024-631e-43e1-813a-853d4219133d`、owner `464345975476916224`。取消样本额外 32×512KiB 二进制，扩大真实快照建立窗口；未执行这些文件。第 1 次取消若遇旧 rev，明确读取新状态后只再试一次，没有降低终态断言。
- `database-proof.json` 确认 V21 success=true、本应用 6 个扫描：5 历史完成、1 **CANCELLED / rev3 / DONE / error=null**。取消 scan `8652dc6f-7ca2-49ff-9cd1-a11095ce39bc` 完成证据数0、资源登记数0，说明本次发生在 Python 前，**不宣称已验证辅助进程运行中取消**。正常成功任务各记录3个实际资源身份。
- `resource-proof.json` 核对正常私有输入/解析目录和本 helper 进程均为0；`source-manifest.json` 固定实际源/脚本 SHA256。HEAD 不能代替未提交源身份。

## 剩余与回退

- 扫描中后端崩溃、孤儿辅助进程、PID复用、过期任务、停止失败与重新停止、辅助进程中取消、跨进程并发准入、全局硬额度：未通过真实验收，继续实现资源对账，不假装重启后自动恢复。
- 把任务状态保持为历史成功不意味着当前候选有效；候选/计划必须再次完整内容和 epoch 资格化。当前仍没有可执行计划、安装批准或隔离多服务。
- 正式启动 UI、只读 Agent授权、实际安装/构建/多服务、有效私有平台成功与第一项全面矩阵仍未交付。其他三项不展开；跨功能 X05/X13/X16/X17 只有第一项局部证据，整体仍 NOT_RUN。
- V21 已应用，不能再修改/删除它。回退停用扫描创建和旧同步入口，保留只读历史/配置与旧执行门禁；不删除私有失败残留、原工程、导入副本或用户 Git 历史。若有未确认资源先按记录核对，不凭进程名误杀。
- **第一项 NOT_COMPLETE。**
