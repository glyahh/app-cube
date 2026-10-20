# 导入服务冻结健康路径：验收记录

日期：2026-10-06。第一项 **NOT_COMPLETE**，其余三项不展开。

## 增量范围

复用 `.glyahh/run.json` 声明、只读候选、冻结运行计划和原工作台运行接口。每个 process 可设置 `healthPath`，必须有 port；未设置仍使用 `/`。非法来源/查询/片段/路径穿越声明明确失败。没有新增 UI、依赖、数据库迁移、提交推送或生产发布。

路径进入候选的 healthPath、计划的 readiness.path 和 planRevision，执行按服务槽位匹配冻结身份，同时核对目录、命令、端口；不能借同目录其他服务的健康路径执行。检测只请求容器对应的本机映射端口，不携带凭据、不跟随跳转，只接受该路径 2xx。

规则版本 `import-startup-2026-10-06-v3` 撤销旧识别/计划复用资格；已有应用需要重新扫描并确认计划，不沿用旧规则忽略过的声明。默认根路径规则保留，但 3xx 不再当作成功。

## 版本与验证

- 分支 code_board，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`；脏工作区保留，源码和测试 SHA-256 见 `D:\codex\WorkDocument\app-cube-review-20261005\health-version.json`。
- 测试先行：健康路径测试先编译失败，Maven 退出码 1，`health-before.log`，当时新路径与检测类型尚未实现。
- 实现后定向回归退出码 0，`health-focused.log`。
- 完整命令 `.\mvnw.cmd -q test '-Dtest=com.dbts.glyahhaigeneratecode.projectimport.*Test'` 退出码 0，54 类 437 项：432 PASS、5 SKIP、0 FAIL/ERROR，见 `health-all.log`、`health-unit-results.json`。不合并其他包历史 XML。
- 新断言：路径限制、禁止外部来源、只请求声明路径、禁止跟随重定向、404 不通过、无端口声明拒绝、健康路径变化改变计划版本、执行身份/命令不匹配拒绝。
- 标准重启命令 `powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory D:\codex\WorkDocument\app-cube-review-20261005\restart-health`。脚本报告重启完成，8124 新监听 PID 40992；外层命令会话退出码未捕获，记录 NOT_CAPTURED，不冒填 0。重启日志见 `health-restart.log`，监听不代替业务验收。
- 真实命令 `.\agent\.venv\Scripts\python.exe scripts\checks\verify-startup-lifetime-api.py` 退出码 **0**，**35 条局部检查 PASS**。证据 `temp/import-startup-preparation-20261006/lifetime-1791216328982172100/result.json`、外部 `health-real-api.log`；其中包含前一轮的 29 条安装/运行/持续日志检查，以及 6 条健康路径相关检查。
- 两个健康夹具的 owner/app/导入任务/执行/计划/内容版本/限额和最终状态见外部 `health-final-identities.json`；仅只读查询这两个应用的执行记录，两次执行均 cleanup=DONE。

真实脚本沿用已有受控 Python 夹具，另添加两个新应用：根路径 404、声明 `/health` 返回业务 JSON；根路径可成功而声明 `/missing` 返回 404。验证停止后容器实际消失和原工程内容不变。脚本不跑用户原工程、私有仓库或模型，清理只针对脚本创建的应用标签。

实际健康成功项：应用 `2107141339740897280`，计划 `1280e656-6c95-4e30-92b5-e4568f870057`，执行 `985cca41-fd6c-45c7-b84c-1d2f5c1371b5`。其根路径真实 404，`/health` 返回 JSON；停止后 STOPPED/DONE。失败项应用 `2107141407676039168`，执行 `32084996-af6c-4dab-9f5c-9b378660697a`，最终 FAILED/NOT_READY/DONE，不保留可用地址。

## 剩余边界与回退

本次不是运行中周期健康检查、依赖就绪检查、业务全面验收或平台多服务完整验收；R02/R07 全组仍 NOT_RUN。持久副本磁盘硬配额、隔离 Agent 编辑/修复、日志脱敏全链路、其他限额与完整矩阵仍待完成。私有 GitHub/Gitee 验收继续按用户决定暂缓。

回退只撤销本次路径字段、读取/冻结/执行校验、检测及对应测试增量，先停止本次受控服务并核对容器消失，然后标准重启；不要 reset 或删除用户改动/原工程。回退后健康声明不再生效，不能沿用本轮验证。规则版本回退会改变旧记录可复用范围，须重新扫描确认，不自动启动。
