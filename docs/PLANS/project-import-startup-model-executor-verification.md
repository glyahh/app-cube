# 启动补充：封存文本与专属模型进程

日期：2026-10-04。`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交源码摘要见 `temp/import-startup-model-20261004/source-manifest.json`。
**第一项 NOT_COMPLETE。以下是内部执行器与资格校验，不是正式批准接口或模型成功验收。**

## 实现

- `ImportStartupService.withCurrentInspection` 在现有应用写入锁内复核扫描、当前完整内容、配置、上下文、规则和 owner，再执行批准回调；不先释放锁再建立批准。过期证据不调用回调。
- `approvedModelInput` 校验批准与当前证据全部身份，只从重新封存且摘要一致的数字副本建立不可变文本投影；不返回原工程路径。捕获、读取、清理失败明确报错，不返回部分结果。
- 投影排除敏感路径、依赖/构建/缓存等目录，严格 UTF-8；单文件16KiB、正文48KiB、256文件，超限失败而非截断。完整工程内容身份仍覆盖全部合法导入文件，投影不是重新定义工程版本。
- 新 `ImportStartupModelExecutor` 复用有界 `ImportProcessRunner`，一次性平台 Python `-I` 进程，不使用共享 Worker PID。输入为封存 JSON 与 SHA256，环境仅 SystemRoot，90秒、stdout96KiB、stderr8KiB；资源先交调用者登记再写本地日志。停止未确认保留输入，不能提前清理。
- `scripts/import-startup-agent.py` 在 SDK 加载前隔离协议 stdout，固定 UTF-8/换行。只调用平台 `core.startup`，不加载工程、Shell、Git、生成历史或 checkpoint/store。校验输入上限与摘要后才进入识别分支。
- 回包严格核对协议版本、批准 UUID、序号、事件次序、应用、成功状态及 EOF；UTF-8 错误不替换字符。最终仍经过原结构化候选、依据 SHA 和冻结身份校验。批准到期由执行器自身持续撤销，不能只相信调用者返回 true。
- 批准仓库读取时重新计算冻结请求摘要，未知 decision 或改目录等不返回合法批准。V23 保持原文件，没有新增/修改已应用迁移。

## 实际验证

证据根：`temp/import-startup-model-20261004/`。

```powershell
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
.\agent\.venv\Scripts\python.exe scripts/verify-startup-model-helper.py
.\agent\.venv\Scripts\python.exe scripts/verify-startup-approval-schema.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-model-20261004/runtime-final
.\agent\.venv\Scripts\python.exe scripts/verify-startup-inspection-api.py --python --config --async --cancel --recovery --rules
```

- `java-expiry-delivery.log` / `.exit` 退出0；42类416项，415 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED。XML `java-xml-final/`，数量 `java-result-final.json`。网络 Git 条件未启用；Mockito/Java agent 警告保留。JDBC 与模型协议替身不当真实批准通过。
- `python-final.log` / `.exit` 退出0，350项通过；模型成功仍使用替身，不当真实模型验收。
- 真实平台 Python 协议夹具使用生产流解析器，完整成功帧解析、EOF 与进程退出验证通过；**仅平台协议夹具，不调用模型/导入脚本**。初始 `-c` 夹具真实 stdout 为空，失败日志保留，改为平台临时脚本后通过；不把这个失败冒充产品识别成功。
- `helper-first.log` 初始失败：Windows `-I` 不读取 PYTHONIOENCODING，中文协议实际不是 UTF-8。修复后 `helper-utf8.log` 退出0，三组真实一次性 helper 拒绝通过；应用串写、缺批准上下文、摘要不符。模型调用0、创建工程工作区false。
- `schema-final.log` / `.exit` 退出0，五组真实 PostgreSQL 事务私有 schema SQL 预检，全部回滚；脚本字段 migrationApplied=false 指未执行迁移，不能解释为实际后端没有 V23。
- 标准最终重启日志 `restart-final.log`、`runtime-final/`：真实 PID59408，8124 `/api`，8.931秒启动。外层启动观察句柄尚未终止时不虚构退出码。
- `api-final.log` / `.exit` 退出0，19组已有真实 API 回归通过；结果 `temp/import-startup-business-20261004/api-1791128552106916700/result.json`，app `2106771971060277248`、task `5960613d-16b4-48ea-a222-cdd9e00a0353`、owner `464380304164876288`。恢复项是受控 DB 夹具，不是本轮真实后端崩溃；不沿用历史崩溃证据当本轮通过。只验收已有导入/扫描/配置/取消恢复能力，不是新模型补充业务成功。
- `database-proof.json` 实际只读确认 V23成功、批准表0行、受控旧规则证据STALE、三种扫描/模型私有输入均为空。初始只读核对使用错误 schema `public` 失败，纠正为真实 `biz` 后通过，不把先前异常当成功。

## 仍需实现

- 持久批准/拒绝、独占执行租约和资源归属、真实取消/重启恢复、owner/request 找回 API；正式工作台接入。现有内部方法不自行证明真实用户批准，调用者不能直接绕过这些待实现业务边界。
- 相关合法上下文与实际模型窗口预算、同目录多语言部分未识别范围粒度；固定文本上限与排除策略不是完整需求交付，更不宣称能识别全部框架或源码内嵌秘密。
- 真实批准后模型返回、当前全内容再复核、候选发布；冻结 RunPlan、独立执行副本、安装批准、隔离准备/构建、多服务生命周期与真实就绪验证。
- S05–S07仍未整组通过；完整第一项矩阵继续执行。GitHub/Gitee有效私有仓库成功测试由用户暂缓；其他三项不展开，跨项整组 NOT_RUN。

回退：停用新补充调度入口，保留原导入、账本和证据；不删除故障私有输入、用户工程或修改 Git 历史。旧导入 Agent/运行/部署门禁继续关闭；未经批准的候选不得送入旧执行器。
