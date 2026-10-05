# 第一项运行状态修复与验证

日期：2026-10-05。范围仅第一项，不修改界面，不开放旧生成执行入口，不推进其余三项。

## 实现

- 停止按服务登记 STOPPED / STOP_FAILED；STOPPING、未知结果不算停止成功。
- 停止后清除访问地址，旧执行编号仅作审计，不再作为 READY 保留。
- 查询当前运行、重复运行、失败重试前检查实际容器状态。退出服务转 FAILED，可单独重试；引擎不可用转未确认，禁止重复建立容器。
- 执行状态检查先核对 owner/app/plan/execution 身份。未知响应不触发删除；已确认退出才结束登记及清理本次临时资源。
- 状态刷新按原读取版本 CAS 保存，不能读取新 revision 后覆盖并发结果。
- 历史核心断言脚本不再宣布 COMPLETE，也不虚构历史命令退出码；完整验收沿用原矩阵。

## 新增回归

1. 停止后再次运行必须有新执行编号。
2. 部分停止未确认不误标 STOPPED。
3. 实际退出不再显示 READY，失败服务可单独重试。
4. 引擎状态未知不得复用 READY 或重新执行。
5. 只读容器检查区分 true / false / 不存在 / 引擎错误 / 未知输出。
6. 执行状态查询拒绝跨所有者、跨应用、跨计划身份。

## 当前证据

- 分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，工作区未提交且保留用户原改动。
- `.\mvnw.cmd -q test '-Dtest=com.dbts.glyahhaigeneratecode.projectimport.*Test'`，退出码 0。53 个类、429 项：424 PASS、5 SKIP、0 FAIL/ERROR。不能把跳过算通过。
- 新增两条停止回归在修复前失败：9 项中 2 FAIL，退出码 1；修复后通过。
- 最后补充迟到停止 CAS 后再运行四个受影响测试类：39 项中 36 PASS/3 SKIP，退出码 0；见 `runtime-cas-final.log`、`runtime-cas-results.json`。随后标准重启重新加载，端口 8124 已监听。
- `.\agent\.venv\Scripts\python.exe scripts\verify-startup-lifetime-api.py`，退出码 0，真实 API/容器 25 条局部断言 PASS。包含停止后新执行、实际 HTTP 业务结果、受控容器退出后业务状态失败、再重试与原件字节不变。
- 真实证据：`temp/import-startup-preparation-20261005/lifetime-1791212149422316400/result.json`。该轮不是全矩阵通过；未知输出分支的小修复随后再次完成单元回归，标准后端重启重新加载。
- 外部证据根：`D:\codex\WorkDocument\app-cube-review-20261005`。包含 `stop-regression-before.log`、`runtime-regression.log`、`import-all-regression-final.log`、`runtime-real-api.log`、`runtime-fix-version.json` 及两轮标准重启日志。
- 标准重启命令：`powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory D:\codex\WorkDocument\app-cube-review-20261005\restart-runtime-final`。
- CAS 最终重启日志：外部证据根下 `restart-cas-final/`；本次监听 PID 32220。后端端口监听仅作环境确认，不作业务通过依据。
- 最终 CAS 版本再从新建应用基线重跑真实链路：退出码 0，25 条局部断言 PASS。证据 `temp/import-startup-preparation-20261005/lifetime-1791212786923576200/result.json`、外部 `runtime-real-api-final.log`；不是仅复用上一轮记录。

## 剩余边界

后续增量见 [持续日志与服务退出监督](project-import-service-supervision-verification.md)：已补齐后台采集、输出限额自动终止和查询/CAS 竞争处理。以下剩余描述保留本记录时点，不覆盖新记录的局部验证与未完成边界。

第一项 **NOT_COMPLETE**。本次只解决停止重跑、查询时状态纠正与验收口径失真，不宣称服务生命周期监督或持续日志已经实现。

仍需：持续有界日志与运行监督、冻结的健康路径、持久副本磁盘硬限制、独立隔离 Agent 编辑/修复链路、真实 Vue/Spring/Python/静态及多服务完整验收、共享契约回归。真实私有仓库按用户决定暂缓为 NOT_RUN，不视为删除 Gitee 范围。

## 使用和回退

已有安装/运行/停止接口不变。停止后可再点运行；服务实际退出后读取当前记录会明确失败，再点重试只运行未就绪项。状态不明时先核对引擎及资源，不能靠重复启动解除保护。

本次无新依赖、无数据库迁移、无提交推送。回退只撤销本次新增状态查询、逐项停止、测试和文档增量，保留原用户改动；不要 git reset 或清理工作区。回退会重新引入假 READY 问题，回退后不得继续宣称运行状态已验证。
