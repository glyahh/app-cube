# 第一项：当前版本验收收口记录（2026-10-06）

本文件记录 `code_board` 工作区（含本轮未提交修复）上重新取得的真实证据，不沿用历史通过数字。
第一项仍为 **NOT_COMPLETE**：私库验收按用户决定暂缓（G02/G04 记 `NOT_RUN`），其余范围已在本版本上执行。

## 1. 本轮产品修复

| 位置 | 改动 | 验证 |
| --- | --- | --- |
| `projectimport/ImportLogMask.java`、`ImportExecutionSandbox` | 执行日志落盘后遮盖密钥形态；非合法 UTF-8 内容原样保留 | `ImportLogMaskTest` 4/4；哨兵链路 C05 断言 |
| `projectimport/ImportExecutionRunner` | 额度被上一轮残留占满时丢弃应用级副本与受限卷，同一应用可重试 | `ImportExecutionRunnerTest` 13/13（含新增用例） |
| `projectimport/ImportTaskService` | 发布改名对 Windows 瞬时拒绝做有界重试；失败日志补 message 便于定位 | 真实框架/多服务与提交探针 10/10 |
| `projectimport/ImportWorkspaceGate`、`agent/AgentRunRequestFactory` | Agent 编辑准入：已发布（READY）的导入应用副本可编辑，未完成发布继续拒绝 | `ImportWorkspaceGateTest` 3/3 |

## 2. 本版本真实证据

| 范围 | 检查数 | 结果 | 证据 |
| --- | --- | --- | --- |
| 安装/运行/磁盘/停止/超限/健康路径 | 38 | 全 PASS | `temp/import-startup-preparation-20261006/lifetime-1791232188242695600/result.json` |
| 隔离执行（副本、限额、网络、内容身份、停止、并发拒绝） | 16 | 全 PASS | `temp/import-startup-isolation-20261005/real-*/result.json` |
| 平台秘密全链路哨兵（接口/数据库/磁盘/容器/日志） | 11 | 全 PASS | `temp/import-secret-chain-1791275493652952900/result.json`（当前版本重跑） |
| 三层识别与真实模型补充 S05 | 3 | 全 PASS | `temp/import-startup-model-supplement-*/result.json` |
| 真实框架：静态页、Flask、Express、双服务组合 | 18 | 全 PASS | `temp/import-frameworks-1791275188844530600/result.json`（当前版本重跑） |
| 真实 Agent 修复闭环 E04/E05（复现→最小修改→回归→干净重放） | 10 | 全 PASS | `temp/import-agent-repair-1791271255014440500/result.json`（Agent 编辑准入后重跑，专项记录见 `project-import-agent-edit-channel-verification.md`） |
| 文件夹任务/暂存/提交接口 | 55 | 全 PASS | `temp/import-evidence-20261006/verify-import-task-api.json` |
| 公开 Git 来源 | 23 | 全 PASS | `temp/import-evidence-20261006/verify-import-source.json` |
| 凭据存储、租约与撤销 | 42 | 全 PASS | `temp/import-evidence-20261006/verify-import-credentials.json` |
| 越权与凭据负例 | 19 | 全 PASS | `temp/import-evidence-20261006/verify-import-auth-negative.json` |
| 提交中断与恢复 | 37 | 全 PASS | `temp/import-evidence-20261006/publication-recovery-2.json` |
| 启动链与迁移 schema 回滚核对（15 个脚本） | — | 全 PASS | 见账本 `baseline-results.jsonl`、`baseline-results-2.jsonl` |

提交可靠性探针：连续 10 次真实导入全部成功（`scripts/probe-import-commit.py`）。

## 3. 仍未完成与残留风险

- **G02/G04 私库验收**：按用户决定暂缓，记 `NOT_RUN`；`scripts/verify-startup-remaining.py` 会创建真实私有仓库，本轮未运行。
- **发布失败观测**：本轮曾出现 4 次 `MOVE_WORKSPACE/AccessDeniedException` 与 2 次 `VERIFY_WORKSPACE/实际文件长度与清单不一致`，均发生在重并发窗口；加入改名重试后未再复现，**根因未完全确认**，保留为已知风险。
- **历史证据**：2026-10-05 及更早的通过记录未按本版本逐条复核，不作为当前 PASS。
- **子场景映射**：58 组已在本版本上执行，但逐条子场景到断言的映射仍需在收口时补齐，避免用组级结论替代子场景结论。

## 4. 运行与回放

```powershell
# 依赖：PostgreSQL、Redis、WSL + 受限卷、Agent 虚拟环境
powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -LogDirectory "temp/logs/backend"
.\agent\.venv\Scripts\python.exe scripts\verify-startup-lifetime-api.py
.\agent\.venv\Scripts\python.exe scripts\verify-startup-isolation-real.py
.\agent\.venv\Scripts\python.exe scripts\verify-import-secret-chain.py
.\agent\.venv\Scripts\python.exe scripts\verify-import-frameworks.py
.\agent\.venv\Scripts\python.exe scripts\verify-import-agent-repair.py      # 调用一次真实模型
.\agent\.venv\Scripts\python.exe scripts\verify-startup-model-supplement.py # 调用一次真实模型
```

回退：本轮改动集中在 `projectimport/` 与 `agent/AgentRunRequestFactory`，未改数据库迁移；回退按文件粒度还原即可，受限卷与暂存目录由业务删除入口清理。
