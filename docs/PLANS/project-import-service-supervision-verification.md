# 导入服务持续日志与退出监督：局部验收

日期：2026-10-05。第一项仍为 **NOT_COMPLETE**，不展开其他三项。

## 本次实现

- 服务进入 RUNNING 后保留日志读取线程与输出文件，继续收集后续输出；总输出字节达到冻结额度时终止容器，核对容器实际消失后结束执行记录。
- 日志写入失败明确标为 EXECUTION_IO_FAILED，不吞错误继续报告服务就绪。后台监督记录实际退出码，识别 Docker 报告的 OOM；OOM 分支尚未真实验收。
- 监督回调绑定执行 UUID；已完成记录拒绝迟到回调覆盖。查询与监督同时结束同一执行、CAS 竞争失败时，只复用同 owner/app/plan 的权威终态，不将正常竞争误报 UNKNOWN。
- 执行账本由监督自动推进；准备业务记录通过现有 current/run/retry 刷新，并保留 OUTPUT_LIMIT 等原因。没有实现准备表的主动推送，也没有新增工作台日志面板。
- 只复用第一项已有执行副本、冻结计划、账本及接口；没有新增界面、依赖或迁移，没有提交、推送或发布。

## 版本与环境

- 分支 code_board，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`；包含用户已有未提交改动，HEAD 不能单独标识测试内容。
- 源码 SHA-256、测试清单、进程与退出码：`D:\codex\WorkDocument\app-cube-review-20261005\supervisor-version.json`。
- 环境为本机 Java 后端、PostgreSQL、WSL Docker；执行 Python 受控夹具，不执行用户原工程、不调用模型或私有仓库。
- 测试应用/导入任务/执行/冻结计划/内容版本/限额：同目录 `supervisor-final-identities.json`，仅查询最终受控服务应用的 12 条执行记录，ID 按字符串保存。
- 标准重启后 8124 监听 PID 46488。监听仅证明环境可用，不代替业务验收。

## 实际验证

| 命令 | 退出码 | 结果与证据 |
|---|---:|---|
| `.\mvnw.cmd -q test '-Dtest=com.dbts.glyahhaigeneratecode.projectimport.*Test'` | 0 | 53 类、433 项：428 PASS、5 SKIP、0 FAIL/ERROR；`supervisor-final-all.log`、`supervisor-unit-results.json` |
| `powershell -ExecutionPolicy Bypass -File .\scripts\restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory D:\codex\WorkDocument\app-cube-review-20261005\restart-supervisor-cas` | 0 | 重启加载修复版本；目录中的启动日志及版本记录 |
| `.\agent\.venv\Scripts\python.exe scripts\verify-startup-lifetime-api.py`（首次） | 1 | 实际退出后查询错误落入 RUNTIME_UNCONFIRMED；保留 `supervisor-real-api.log` 及 `temp/import-startup-preparation-20261005/lifetime-1791214611953585600/result.json` |
| 同一真实验收命令（修复、重启后，新测试应用基线） | 0 | 29 条局部断言 PASS；`supervisor-real-api-final.log` 及 `temp/import-startup-preparation-20261005/lifetime-1791214952536288500/result.json` |

首次失败根因是查询和监督对同一执行终态进行 CAS 更新，查询败方没有重读权威记录。修复根因并新增确定性回归后重跑，没有删除或放宽失败断言。

单元统计只取命令所选 `projectimport` 包：同报告目录另有其他包的历史 XML，未计入本轮。

新增回归覆盖持续晚输出、日志 IO 失败、输出限额监督、终态迟到回调和查询/CAS 竞争。真实链路除已有安装授权、构建失败、选定服务、部分成功、停止重跑、异常退出恢复外，还验证：

1. READY 后访问夹具 `/late-log`，标记实际出现在对应执行日志。
2. `/overflow` 输出超过限额后，仅轮询执行数据库，不调用 current 触发纠正；监督自动将记录置 FAILED/OUTPUT_LIMIT，容器实际为空。
3. 对应输出文件 `temp/project_import_exec/0c5f956e-4a87-4176-b3c0-81f9667ce4fc.output.log` 实际大小 **4194304 字节**，不超过冻结的 4 MiB。
4. 随后调用 current，服务为 FAILED/OUTPUT_LIMIT；执行、安装和构建前后原导入工程内容哈希不变。

## 未完成与未执行

- R07/R08 仅上述子场景通过，整组仍 NOT_RUN：平台运行中重启、attach 丢失、停止失败、真实 OOM/CPU/PID/磁盘等边界仍需验收。
- 持久执行副本没有磁盘硬配额；事前/事后容量检查不能替代硬保护。
- 健康检测仍固定根路径，未实现冻结健康路径；HTTP 成功不代表完整业务功能验收。
- 日志脱敏全链路及工作台展示未验收；本次夹具不含真实秘密。
- 隔离 Agent 编辑/修复链路与 Vue/Spring/Python/静态、多服务完整矩阵及必要共享契约回归尚未完成。
- GitHub/Gitee 私有仓库按用户决定暂缓为 NOT_RUN，不删除产品范围。
- 原 58 组验收矩阵保留，不使用每组一条断言宣布 COMPLETE。

## 复现和回退

准备现有本机后端、PostgreSQL、WSL Docker及 Agent 虚拟环境后，执行上述命令。真实脚本仅生成专用受控工程和测试应用，最后按这些应用标签清理容器；清理失败会使结果 FAIL。保留测试记录和证据，不清理用户工作区。

回退只撤销本次监督、持续日志、终态竞争处理及对应测试增量，再按项目脚本重启。先停止本次测试服务并确认容器消失；不要 reset 用户改动、修改 Git 历史或删除原工程。回退会失去持续输出边界与自动退出监督，不得继续沿用本轮通过结论。
