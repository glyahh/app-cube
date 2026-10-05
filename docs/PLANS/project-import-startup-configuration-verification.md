# 应用级启动配置：局部验证

2026-10-04；`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。用户已批准启动前端接后端。仅第一项；保存声明不执行工程、安装依赖或调用模型。

## 实际增量

- V19 按应用保存运行目录与手动服务声明，名称、服务端 UUID、目录、启动/安装/构建命令分别保存。新服务不能借用其他应用的 UUID；已有自动识别证据不被手动声明静默删除。
- owner、最终 App owner、原导入任务和 READY 发布资格同时核对，管理员不能借原导入 owner 的资格读取他人应用。
- 配置保存必须同时携带 `configRevision`、`contextEpoch`，CAS 成功后两者递增。缺少版本或重复旧请求不更新。受管代码保存撤销 epoch，保留手动声明与配置版本；失败写入可以保守地撤销旧结果，不冒称文件系统与数据库原子提交。
- 识别结束复核完整内容与配置/上下文，拒绝迟到结果；手动目录再次校验。自动排除依赖目录不等于禁止用户显式选择合法的工程内手动目录。
- `GET /api/project/import/apps/{appId}/startup/configuration`；`PUT` 同一路径，输入 `ownerId/configRevision/contextEpoch/scope/services`。ID 与返回版本使用 string。配置保存仅记录声明，不授予任何执行权限。

## 真实证据与命令

```powershell
./mvnw.cmd -q test '-Dtest=ImportStartupConfigurationTest,ImportStartupServiceTest,ImportStartupControllerTest'
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe scripts/verify-startup-config-schema.py
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -TimeoutSeconds 180 -LogDirectory "temp/import-startup-config-20261004/runtime"
.\agent\.venv\Scripts\python.exe scripts/verify-startup-inspection-api.py --python --config
```

- `temp/import-startup-config-20261004/`：配置校验 5 项、Service 当时 10 项、Controller 当时 3 项通过；组合与最后受影响类 XML 合计 33 类 **312 项，311 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**。`combined-regression.log` 后新增必填版本用例，另跑 `controller-final.log`，均退出 0；不伪称其他类在最后新增测试后再次全跑。原 Git 网络条件用例 SKIPPED。
- 数据库预检先失败，保留 `schema-preflight-first.json` 和 `schema-preflight.log`。改为事务内独立普通 schema，不再用临时表引用正式 FK；整笔事务回滚，既有业务行不修改。`schema-preflight-qualified.log` 退出 0、`schema-preflight.json` **2 组 PASS**：真实约束拒绝负版本/非数组，重复 CAS 不递增。
- 标准重启实际 **PID41736**；`runtime/backend.stdout.log` 显示 V19 成功迁移、11.226 秒启动。外层后台管道未回收，不虚构该观察进程的退出码。`database-final-state.json` 只读确认 Flyway V19 success=true，业务最终配置版本 2、epoch 3、1 个手动服务。
- 首轮 API **9 组 PASS**：`config-real-api-first.log`，退出 0；独立新基线加入双保存后 **10 组 PASS**：`config-real-api-qualified.log`，退出 0。
- 最终结果 `temp/import-startup-business-20261004/api-1791117278206660200/result.json`：app `2106724676323274752`，task `0e261673-ee05-4e9e-8000-5f89f270a68e`，owner `464333017468354560`。检查真实最终应用、Python AST 不执行、跨用户/会话/目录拒绝、配置持久化和手动优先、跨连接占锁拒绝、改代码失效、同版本双保存一次成功、保护字节与私有输入清理。
- 双保存结果为一次 20000/一次 40900、最终版本只增加一次；未区分第二个请求究竟由共用锁还是 SQL CAS 拒绝，不将观察扩大为全部并发情形已通过。

## 未执行、运行与回退

- 正式启动 UI 联调、持久扫描过程/取消/恢复、只读 Agent 授权、冻结计划、安装批准、硬隔离副本、多服务及完整矩阵：本节点 **NOT_RUN 或待开发**。后续完成证据持久化另见增量报告，不混为扫描过程恢复。
- 此时的数据库只读检查不是跨重启会话验收。此前前端 208 测试属于此前节点，未在本节点重跑。真实有效私有 GitHub/Gitee 成功验收按用户要求暂缓。
- V19 已应用，不改写或删除迁移、不删除配置或导入应用。回退仅停止使用新增配置/识别接口并保持旧执行门禁；需要数据结构变更时另做前向迁移。原工程与保护字节不变，无用户 Git 操作。
- 运行前仍须准备现有 PostgreSQL/Redis/Python 环境；当前命令只运行平台自己的验证器，不运行导入工程。首次复跑会新增专用测试账号和保留导入夹具，不输出密码、Cookie、Token 或连接串。
- **第一项 NOT_COMPLETE**，其他三项不展开。
