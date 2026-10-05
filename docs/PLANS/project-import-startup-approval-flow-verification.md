# 识别补充批准业务流：步骤 1 增量

日期：2026-10-05。分支 `code_board`，HEAD `d452804`；工作区原有未提交改动基线见 `temp/grokbot-step1-baseline-20261005-003406/`。**第一项 NOT_COMPLETE；不发布候选，不是安装/运行授权，S05 未声称通过。**

## 实现与复用

- 新增 `ImportStartupApprovalService` 与独立 `ImportStartupApprovalController`（`/project/import/apps/{appId}/startup/approvals`），复用 V23 批准账本、V24 执行租约、既有执行器、资源守卫、工作区协调锁与导入 CSRF/来源门禁；未改 V23/V24。
- 批准/拒绝：请求 UUID 幂等，同 ID 异输入返回冲突；目录必须属于当前识别记录的可补充未识别范围。拒绝真实落库但从不认领、不产生租约。owner/请求找回只对本人可见。
- 批准后由有界执行池（2 线程/队列 20）原子认领租约，执行器 PID 与实际资源独立登记；模型成功只把结果写入 `result_json`（SUCCEEDED），候选复核与发布留给步骤 2。资格丢失时丢弃结果记为 `MODEL_RESULT_REVOKED`。
- 取消：APPROVED 直接 CANCELLED；RUNNING 先 CAS 到 CANCELLING 撤销旧版本，执行器退出、资源停止、仅本批准私有输入确认消失后才 CANCELLED。
- 恢复（每 2 秒，协调锁内复核版本）：过期未认领 APPROVED → STALE；执行者已消失 → 撤销为 RECOVERY_REQUIRED 后核对资源与输入再收尾；执行者存活但过期 → 只撤销不清理；有私有输入但无资源登记（PID 窗口）→ 保持 RECOVERY_REQUIRED、保留配额与输入，不猜停止。
- 自审修复：恢复轮询原先一次读出全部待处理批准，单条冻结内容校验失败会阻断其他批准的过期/对账。改为只取 owner/approvalId 身份、逐条复核；坏记录只记日志并保持占用（失败关闭）。真实 API 运行时库中正有一条坏夹具，其他批准仍正常收尾，证实修复。
- 仓库仅新增 `outstanding()`；原文件（未跟踪）修改前副本 `ImportStartupApprovalRepository.java.before` 保存在证据根。

## 命令与证据

```powershell
.\mvnw.cmd -q test '-Dtest=ImportStartupApproval*Test'
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
.\agent\.venv\Scripts\python.exe scripts/verify-startup-approval-schema.py
.\agent\.venv\Scripts\python.exe scripts/verify-startup-model-lease-schema.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-approval-flow-20261005/restart-2
.\agent\.venv\Scripts\python.exe scripts/verify-startup-approval-api.py --restart
.\agent\.venv\Scripts\python.exe scripts/verify-startup-inspection-api.py --python --config --async --cancel --recovery --rules
```

证据根 `temp/import-startup-approval-flow-20261005/`：

- `unit-first.log`、`unit-fix1.log` 退出0：服务 15 项、控制器 3 项。仓库/执行器为 Mockito 替身，不算业务通过。
- `java-final2.log` 退出0：**45 类 442 项，0 FAIL / 0 ERROR / 1 SKIPPED**（`java-result2.txt`）。
- `python-tests.log` 退出0，**350 项通过**；`schema-approval.log`、`schema-lease.log` 退出0（事务私有 schema，全部回滚）。
- 标准重启 `restart-2/`：PID 52636 / 8124，10.05 秒启动，载入修复后代码。
- `api-approval.log` 退出0，结果 `api-1791133279974500900/result.json`，**10 组全部 PASS**。app `2106791795199074304`、importTask `7491bb03-d20c-456e-bb5d-a51c42e0ca6c`、owner `464400134414774272`：
  - 真实拒绝行、重复请求同一行、异决定/越界目录/会话变化拒绝、跨用户 404、无租约。
  - 真实批准行被认领（租约存在，资源 0）后取消：CANCELLED、cleanup DONE、私有输入不存在、无结果。
  - 受控账本夹具（本脚本新建应用、执行者 PID 不存在）：过期 → STALE；执行者丢失 → FAILED/MODEL_EXECUTOR_LOST/DONE；持久取消 → CANCELLED；PID 窗口 → RECOVERY_REQUIRED/UNVERIFIED、配额与输入保留、跨用户/旧版本对账拒绝，输入消失后 FAILED/DONE。
  - **真实标准重启**：租约执行者为当时后端 JVM（PID 52636，存活、非本实例），重启前保持 RUNNING/PENDING；重启后新实例（PID 44132，`runtime/`）撤销并收尾为 FAILED/MODEL_EXECUTOR_LOST/DONE，不重跑；拒绝记录可找回。
  - 导入工程与保护字节未改；SUCCEEDED 记录 0（未声称真实模型成功）。
- `api-regression.log` 退出0，**19 组既有 API 回归通过**（`temp/import-startup-business-20261004/api-1791133464640112100/result.json`）。
- `fixture-cleanup.log`：删除第一次运行中哈希编码写错的 1 条测试夹具（测试用户、APPROVED、无租约）。脚本此后改为以真实拒绝行的已存哈希确定编码。

## 待完成与回退

- 模型结果尚未复核/发布为候选（步骤 2）；真实模型成功路径未覆盖，S05 不改 PASS。
- 未覆盖：真实运行中模型子进程被重启杀死后的资源停止（资源停止原语沿用既有守卫测试）。
- 回退：删除新增服务/控制器/两份测试与 `scripts/verify-startup-approval-api.py`，用证据根中的 `.before` 恢复仓库文件。
