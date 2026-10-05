# 启动补充执行租约：内部验证

日期：2026-10-04。`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`；未提交源码摘要见 `temp/import-startup-model-lease-20261004/source-manifest.json`。**第一项 NOT_COMPLETE，正式批准/调度/取消恢复接口未接。**

## 实现范围

- V24 独立执行租约表以 approvalId 为唯一执行身份，组合外键绑定原 owner/app；保存批准运行版本、独占 executor UUID、Java PID/启动时间、资源列表、清理状态和 revision。V23 不修改；V24 已应用，不再编辑它。
- `ImportStartupModelLeaseRepository.claim` 在同一 Spring 事务将 APPROVED 转 RUNNING，再插入唯一执行身份。拒绝/取消/过期/非批准状态、无效进程参数不 claim；插入异常应整体回滚。当前 Spring 事务声明和两段 SQL 已验证，**真实业务事务/worker 调度尚未接入验收**。
- 资格 SQL 同时核对租约 revision/executor/PID/start、批准运行 revision/未取消/期限、owner、原导入、完整内容身份、规则、成功扫描、配置/epoch 和发布 READY。它不替代磁盘全内容复核。
- 资源追加固定本租约身份与版本，最多128项；取消/到期后继续登记已存在资源，不因为撤销运行资格而丢失清理责任。清理 DONE 后拒绝旧登记和旧清理 CAS。清理 repository 不自行证明实际停止；后续 service 必须先确认资源退出与私有输入。
- 现有 `ImportStartupResourceGuard` 提供 `stopResources/stoppedResources` 和执行者 PID/start 核对，扫描路径继续复用同一实现。保留 PID复用保护、启动时间不可读拒绝、子进程登记、实际退出确认；不按进程名批量终止。
- 复用范围仅第一项；不授权安装/运行，不开放导入旧 Agent/runtime/deploy 入口。

## 命令和证据

```powershell
.\mvnw.cmd -q test '-Dtest=ImportStartupModelLeaseRepositoryTest,ImportStartupResourceGuardTest,ImportStartupModelExecutorTest'
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe scripts/verify-startup-model-lease-schema.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-model-lease-20261004/runtime
.\agent\.venv\Scripts\python.exe scripts/verify-startup-inspection-api.py --python --config --async --cancel --recovery --rules
```

证据根 `temp/import-startup-model-lease-20261004/`：

- `unit-first.log` 退出1：新增同名 List 重载使原 Mockito `any()` 调用编译歧义。改为有含义的 `stopResources/stoppedResources`，不改旧测试调用制造通过。`unit-qualified.log` / `.exit` 退出0。
- `java-final.log` / `.exit` 退出0，43类424项，423 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED；数量 `java-result.json`，XML `java-xml-final/`。新租约8项单元含准入、唯一身份、资格条件、资源登记及清理 CAS；JDBC 是替身，不是业务调度成功。网络 Git 条件未启用。
- 真实平台 Python 双进程夹具通过资源列表入口停止一个、保留旁路并验证目标确已退出；这是停止原语，不是模型调用中取消或后端崩溃恢复。
- `schema-first.log` 退出0，4组真实 SQL；`schema-final.log` / `.exit` 退出0，最终5组，包括另一 owner 的真实组合外键拒绝、epoch/过期撤销、取消后资源可登记、清理后旧写入拒绝。最终 `schema-1791129265342859100/result.json`。全部事务私有 schema 操作、最终回滚，生产业务行只读，不创建真实批准或模型任务。
- `restart.log` / `runtime/`：标准重启真实 PID56160，8124 `/api`，9.037秒启动。外层观察句柄未结束不虚构退出码。`database-proof.json` 实际确认 V23、V24 success=true，批准和执行租约表均0行。
- `api-final.log` / `.exit` 退出0，重启后已有 API19组通过；结果 `temp/import-startup-business-20261004/api-1791129279177655700/result.json`，app `2106775022785609728`、importTask `eff56aff-b1a8-48f4-a8f5-d7b630ee613b`、owner `464383354266882048`。恢复项为受控 DB 夹具，不是本轮真实后端崩溃。此脚本不覆盖新批准/租约业务，不冒称真实模型或第一项整组通过。

## 下一步与边界

接持久用户批准/拒绝、owner/request 找回 API、有限工作线程与本 lease 原子 claim、实际资源登记、取消与过期/重启对账。取消必须先撤销旧批准 revision，再核对独占进程和本任务输入；PID登记前有输入但无资源的窗口保留失败/恢复状态及配额，不猜停止。完成回包需重新全内容校验，再发布候选；不能凭数据库资格不变认定工作区未变。

正式工作台、相关合法上下文/模型窗口、同目录局部未识别粒度、冻结 RunPlan、独立执行副本、安装批准、隔离准备和多服务就绪/部分失败/重试/停止仍未完成。私有平台成功测试用户暂缓。S05–S07及完整第一项矩阵未整组通过；其他三项不开发，跨项整组 NOT_RUN。

回退停用新补充入口/调度，保留原导入、账本和证据；不删除未确认停止的输入、用户工程或 Git 历史。V24保持已应用文件，不伪造反向迁移。
