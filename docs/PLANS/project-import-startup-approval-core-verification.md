# 启动补充批准账本与回包校验：内部增量

日期：2026-10-04。分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`；未提交源码 SHA256 见 `temp/import-startup-approval-20261004/source-manifest.json`。**第一项 NOT_COMPLETE；尚无正式批准/模型调度业务接口。**

## 实现与复用

- V23 独立保存本次用户批准/拒绝及原 app/owner/scan/importTask/content/config/epoch/rules/目录；请求 UUID 幂等，改变决定或冻结参数不复用旧请求。拒绝不占模型任务数量，批准准入采用短事务 advisory lock。
- 状态与 revision CAS、取消意图持久保存；拒绝/取消/终态不重新启动。运行与发布再次通过实际 SQL 核对当前 owner、应用未删除、原导入、扫描成功、规则、配置/epoch 和过期时间。这里是账本，不是停止进程证明。
- Java 严格校验 Python 回包所有冻结身份、目录覆盖、额外字段、命令限制、依据文件与实际批准文本 SHA。只有 AGENT/INFERRED 合法，不能声称 FACT 或安装权。过期/不完整/重复字段/改投影/另一身份明确拒绝；候选 ID 由批准身份与服务信息稳定生成，不由模型指定。
- 同目录可有多个不同命令服务，不机械压成一个；同时属于候选和未识别、重名或同目录同命令重复拒绝。Java/Python 双侧规则一致。
- 修正源扫描一个配置异常吞掉同目录其他语言的问题：Node 与 Maven 独立解析，失败诊断保留，合法 Spring 候选保留。扫描规则提升为 `import-startup-2026-10-04-v2`，旧规则完成证据必须过期，不能悄悄冒充当前证据。
- 复用范围仅第一项；没有新增其他三项、独立问答或海报，导入旧 Agent/运行/部署门禁仍关闭。

## 命令与证据

```powershell
.\mvnw.cmd -q test '-Dtest=ImportStartupApprovalRepositoryTest,ImportStartupSupplementResultTest'
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
.\agent\.venv\Scripts\python.exe scripts/verify-startup-approval-schema.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-approval-20261004/runtime-final
.\agent\.venv\Scripts\python.exe scripts/verify-startup-inspection-api.py --python --config --async --cancel --recovery --rules
```

证据根 `temp/import-startup-approval-20261004/`：

- `unit-first.log`、`unit-qualified.log`、`unit-expiry-final.log` 退出0。新批准仓库8项；最终回包19项，包括过期、7类身份串写、依据改动、字段/覆盖/格式与多服务身份。明确 JDBC 替身，不是业务批准通过。
- `combined-first.log`、`combined-qualified.log`、`combined-final.log`、`combined-multiservice.log` 及最终 `combined-delivery.log` 均退出0。最终 **41类395项，394 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**；XML `java-xml-delivery/`。网络 Git 条件未启用，Mockito/Java agent 警告保留。
- `python-final.log` 退出0，**350项全部通过**；新增同目录多服务不减为一个。模型仍为明确替身，不算真实模型成功。
- `schema-first.log`、`schema-qualified.log`、`schema-final.log` 退出0，各 **5组真实 PostgreSQL 回滚 schema SQL预检通过**。最终 `schema-1791125101510203300/result.json`；只在事务私有 schema 克隆并调整扫描/配置夹具，生产业务行只读、最终全部回滚。核对请求唯一、owner、epoch/扫描过期、批准过期、取消与迟到成功拒绝；不是 HTTP批准或模型流水线。脚本使用当前源码规则版本，不用历史v1参数冒称v2已验证。
- 标准首次重启真实 PID5108，V23成功应用、9.606秒启动，`runtime/`；最终重启 **PID32588 / 8124**，7.79秒启动，`runtime-final/`与`restart-final.log`。后台外层观察句柄不虚构退出码。
- `api-regression-final.log` 退出0，**19组真实已有 API 回归通过**；结果 `temp/import-startup-business-20261004/api-1791125411497505700/result.json`。app `2106758797879672832`、importTask `4a038214-ad50-45ea-bd84-e334b3175cb0`、owner `464367131428868096`。覆盖实际导入保护字节、Python只解析、配置/内容/epoch、并发同版本保存、取消恢复；额外受控规则版本变化后真实 GET 返回 STALE/无候选。
- `database-proof.json` 确认 V23 success=true、批准表实际行数0、受控旧规则记录STALE。说明账本已部署但**没有假造用户批准/模型调用成功**。真实 API回归没有使用新模型补充分支，不算S05整体通过。

## 待完成与回退

- 待接服务器受控文本投影、持久用户决定的真实 API、独立可核对的模型执行租约/资源/取消/重启恢复、实际模型返回后完整内容复核、正式工作台。不能因为UUID合法、内部回包校验或DB epoch不变，就认定真实用户批准/完整内容没有变化。
- 不直接复用共享池的 PID当任务专属资源：进程归池后可能属于另一任务。接执行器时必须核对实际独占资格，取消不得误杀后续任务。当前账本未解决执行资源归属，不能记停止与清理通过。
- 同目录多语言的局部未识别范围粒度、相关合法上下文与实际模型窗口预算继续核对；不能靠目录级保守拒绝缩小原需求。
- S05–S07只增加局部内部与既有业务证据，不把整组改为PASS。冻结计划、安装批准、隔离准备与多服务真实生命周期仍未交付。私有平台成功测试用户暂缓；其他三项不展开，跨功能整组NOT_RUN。
- V23已应用，不修改/删除它。回退停用新补充调度/入口，保留批准与证据、原导入和旧执行门禁；不能清理用户工程、私有故障输入或Git历史来制造成功。
