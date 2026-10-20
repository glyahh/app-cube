# 启动识别完成证据：持久化与重启找回

2026-10-04；`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d`，未提交工作区。仅第一项的只读识别增量，非完整 StartupScan 任务系统、授权或运行验收。

## 实际行为

- POST 原 `startup/inspection` 返回服务端 `scanId`，绑定 app、owner、import task、完整内容 hash、scope、配置版本、contextEpoch 与规则版本。最终复核与私有输入清理成功后才写 V20 完成记录；失败检查不写成功记录。
- `GET /api/project/import/apps/{appId}/startup/inspections/{scanId}` 只读找回已完成记录，不重复解析 Python、不执行工程、不调用模型。查询与 JSON 内容都核对身份；未知 ID、他人 owner（包括 admin）、不一致数据不返回候选。
- 当前资格首先核对实际 App owner 与 READY 导入关系，再核对版本、规则与当前完整内容（不是大小/mtime）。配置、上下文、规则或内容不匹配则返回 `status=STALE`、`inspection=null`，持久标记已观察到的过期。旧字节恢复不会自动复活该记录。
- 数据库中的 `SUCCEEDED` 表示原检查完成，不保证现在仍有效。尚未读取的历史记录可以保留该历史状态；所有恢复入口必须重新资格化，不能直接拿表中 JSON 执行。没有运行计划或执行授权。
- 只保存完成证据，仍未实现扫描中的持久任务、请求幂等、失败/取消/重启资源对账和保留执行快照。正常输入副本已清理，不能把本记录当作以后执行用的完整字节快照。

## 可重放验证

```powershell
./mvnw.cmd -q test '-Dtest=ImportStartupInspectionRepositoryTest,ImportStartupServiceTest,ImportStartupControllerTest'
./mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -TimeoutSeconds 180 -LogDirectory "temp/import-startup-record-20261004/runtime"
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --restart
```

`--restart` 明确调用标准后端重启，不能在共享业务运行时随意执行；本次只有专用测试夹具，没有运行导入工程。脚本新建测试账号与最终应用，密码/会话只留内存，不把凭据写日志。

- `temp/import-startup-record-20261004/unit-first.log`、`unit-qualified.log`、`combined-regression.log`：退出 0。新增 **11 项**覆盖完成证据找回、不重复 parser、外部同版本磁盘变化、配置/规则失效、跨用户拒绝、过期不可复活、JSON 与行身份/内容/版本匹配、坏/缺记录和控制器会话。Repository 单元使用明确 JDBC Mock，只证明映射/拒绝分支，不冒充数据库成功。
- 完整组合 **34 类 323 项，322 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**，原 Git 网络条件未启用。最终 XML 复制至 `junit-final/`。Mockito/ByteBuddy 警告与预期异常日志保留。
- 标准重启 **PID33488**，实际 V20 应用成功、17.604 秒启动，日志 `runtime/backend.stdout.log`。外层后台管道观察未结束，不虚构其退出码。
- 独立新基线真实 API **13 组 PASS**，Python 退出 0：`real-api-restart.log`。完整结果 `temp/import-startup-business-20261004/api-1791118237172514100/result.json`。
- app `2106728702695825408`，task `bbd2e518-9e04-4bc1-bbe9-79b6da8780f5`，owner `464337040573411328`。其中实际停止 PID33488 并标准重启至 **PID23432**，restart 子进程退出 0；用原真实会话找回完全相同的 `scanId` 和候选。重启日志在该 API 证据目录 `restart.log`、`runtime/`，当前 Flyway 20，启动 12.206 秒。
- 同一轮覆盖此前真实 folder 提交/混合 Python/静态识别、owner/session/scope 拒绝、配置 CAS、另一连接占锁拒绝、编辑失效、同版本双保存仅一次成功、同大小外部改动并恢复旧字节仍 STALE、原保护字节不改、所有正常私有输入已清理。
- `database-proof.json` 只读确认 V19/V20 均 success=true；本应用 4 条完成记录，其中 3 条已观察 STALE。第 3 条还保留历史 SUCCEEDED，但 epoch 已旧，不能直接使用；这不是当前有效授权。
- `source-manifest.json` 固定受影响源文件 SHA256；`source-manifest-final.json` 补完整相关代码/脚本和验证版本。HEAD 加未提交文件内容身份，不能只凭 HEAD 重放。
- `resource-proof.json`：实际 8124 PID23432，匹配平台 helper 的进程数 0、正常私有输入/解析作业目录 0；不将其他 Agent Python 进程混为本功能资源。

## 共享契约和剩余边界

- X05/X13 仅验证第一项的受管编辑与完整内容/epoch 资格；X16/X17 仅验证完成证据跨真实后端重启找回。其他三项尚未开发，四功能整体联动全部仍 **NOT_RUN**。读取记录不授予模型上下文资格。
- 没有执行扫描中崩溃、私有清理失败、STOP_FAILED、全局硬配额、真实有效私有 GitHub/Gitee、只读 Agent、安装/构建/多服务及正式启动 UI 联调的整项验收。此前前端测试不记成本节点重跑。
- V19/V20 已应用，禁止改写或删除迁移、回滚用户 Git 历史或删除导入数据。回退停用新增记录/配置接口，保留旧执行门禁；数据库变化仅做另一个前向迁移。
- **第一项 NOT_COMPLETE；其余三项不展开。**
