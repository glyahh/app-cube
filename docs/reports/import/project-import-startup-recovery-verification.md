# 启动扫描恢复：局部实现与验证

日期：2026-10-04。分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，实际未提交源身份见 `temp/import-startup-recovery-20261004/source-manifest.json`。第一项 **NOT_COMPLETE**。

## 实现范围

- V22 保存取消意图，不依赖错误文字推断；旧任务停止失败后恢复仍保持原取消决定。
- 后台每 2 秒核对未结束扫描。过期先撤销资格；仍存活或身份无法确认的执行者不允许并发删输入，不自动重跑扫描。
- 恢复与编辑复用同应用 PostgreSQL advisory lock，锁内重新读取版本。`POST /project/import/apps/{appId}/startup/scans/{scanId}/reconcile` 必须带当前 owner/revision；不是重新运行入口。
- 核对可信账本 PID/启动时间及其当前子进程，登记后停止；PID 复用不终止新进程。停止返回失败仍复核资源是否已经退出，不能直接报成功。
- 执行者已退出、资源已停止，才清该扫描私有快照/解析输入。无 PID 登记但已有辅助输入的崩溃窗口保持 RECOVERY_REQUIRED、保留配额，不猜测已停止。
- 这些能力仅复用于第一项扫描，不提前授予 Agent、安装、构建、服务运行或其他三项权限。

## 命令与结果

```powershell
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-scan-schema.py --evidence-dir temp/import-startup-recovery-20261004/schema-current
powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-recovery-20261004/runtime
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --async --cancel --recovery
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --async --cancel --recovery --crash
```

证据根 `temp/import-startup-recovery-20261004/`：

- 初次 `unit-guard.log` 测试漏 import 编译失败，保留；补充 import 后 `unit-qualified.log`、`unit-native.log` 退出 0。
- 初次 `combined-final.log` **355 项中 1 FAIL / 1 SKIPPED**，真实平台进程停止断言失败。补停止失败后的资源身份复核及确定性并发退出用例；`unit-stop-race.log`、`combined-stop-race.log` 退出 0，最终 **38 类 356 项，355 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**，XML `xml-final/`。网络 Git 条件未启用，不能当实际平台验收。
- Guard 6 项包含 PID 复用、无启动时间、停止拒绝、并发退出及真正的两个平台 Python 进程；只停止登记的测试进程，旁路仍存活。进程代码属于测试夹具，不是导入工程。
- `schema-preflight.log` 最初失败于 verifier 漏 SQL 参数；`schema-qualified.log`、`schema-current.log` 均退出 0，真实 PostgreSQL 回滚 schema 5 组通过。预检不应用迁移、不测试 worker。旧共享 `temp/import-startup-scan-20261004/schema-preflight.json` 曾被该失败运行覆盖，旧通过日志仍保留；以后使用独立 evidence-dir，不冒称旧 JSON 未变。
- 标准重启真实 PID5052，V22 已应用；首次实际 API `real-api-first.log` 退出 0，**18 组通过**，结果 `temp/import-startup-business-20261004/api-1791122708861995500/result.json`。恢复部分是本脚本新建应用中的受控 DB/私有输入夹具：孤儿、停止失败取消意图、未登记窗口与 owner/revision。未登记输入由 verifier 明确移除后才恢复，不能冒称服务自动证明未知进程停止。
- 首次真实崩溃 verifier 因后台启动器继承输出管道而等待超时；真实服务已恢复，但不把失败 verifier 记为通过。改用文件日志，保留 `real-crash-first.log` 及对应 `api-1791122788409997500/`。没有改服务启动脚本，也未降低业务断言。
- 修正后 `real-crash-qualified.log` 退出 0，**19 组通过**，结果 `temp/import-startup-business-20261004/api-1791122930994425800/result.json`。app `2106748394680430592`、importTask `60e3e094-176d-460c-a625-c2efe7e4b903`、owner `464356727633903616`。
- 真实扫描 `99e0362e-d617-4c8e-9dd6-d4ff4150220c` 在 SCANNING/私有副本已创建时，核对 Java PID21080/启动时间后终止；标准重启 **PID10724 / 8124**，7.168 秒启动。原 Redis 会话读取任务为 **FAILED / DONE / SCAN_INTERRUPTED**，无完成候选，不重跑；最终应用编辑字节及原导入保护字节保留。
- `database-proof.json` 确认 V22 success=true、真实中断任务证据数0、取消夹具 CANCELLED/DONE/持久取消意图；`resource-proof.json` 确认实际 helper0、扫描私有输入0、Python输入0。这里崩溃发生在 Python 前，资源登记为空，**不是运行中辅助进程崩溃/取消验收**。
- PostgreSQL 18.6/Flyway 支持上限17警告保留，不吞警告。测试日志预期业务异常保留。

## 剩余与回退

- 辅助进程运行中取消/崩溃、真实停止拒绝后的恢复、跨实例执行者并发和全局硬资源额度仍 NOT_RUN。资源账本不替代 OS Job Object/容器级硬限制。
- 只读 Agent、正式启动界面联调、冻结运行计划、安装批准、独立执行副本、多服务就绪/部分失败/重试/停止尚未交付。成功私有 GitHub/Gitee 验收用户暂缓。
- 其他三项未展开；跨项矩阵 X05/X13/X16/X17 只有本项局部证据，整组 NOT_RUN。不能将本页局部检查改记完整导入验收矩阵 PASS。
- V22 已应用，不能修改/删除。回退先停止新扫描与恢复调度，保留账本、保护副本、失败输入和旧导入执行门禁；核对实际执行者/资源后处理残留，不按进程名删除/停止，不改用户 Git 历史。
