# 导入应用的 Agent 编辑通道与真实修复闭环：验收记录

日期：2026-10-06。本轮补齐第一项缺口「隔离 Agent 编辑/修复」；其余边界在文末列出。

## 增量范围

导入应用此前被 `ImportWorkspaceGate.requireLegacyExecutionAllowed` 一刀切挡住 `/chat/generate/stream`（SSE）与 `/ws/agent`（WebSocket），真实修复闭环比不上。本轮把 Agent 编辑与旧执行入口分开：

- 新增 `ImportWorkspaceGate.requireAgentEditAllowed(appId)`：导入应用 publication 为 `READY` 时放行 Agent 编辑，未完成发布的工作区继续拒绝。
- `AgentRunRequestFactory.build` 改用该方法；`AppController.deployApp` 与 `AppRuntimeController.start` 旧入口维持原拦截不变。
- 放行的安全前提是结构隔离：Agent 的工作区是发布副本 `temp/code_output/project_{appId}`，来源原件（上传暂存）与保护快照在导入私有目录，Agent 工具不会写回去；运行验证仍走受限卷执行入口，不经过这条通道。

同步修正 `scripts/checks/verify-import-agent-repair.py` 两处没有核查力的断言：重放前显式核对重新导入的应用仍是缺陷原样；把原先恒真的「原导入工程未被回写」换成对来源暂存上传件的逐字节比对。

## 版本与验证

- 分支 code_board；改动文件：`ImportWorkspaceGate.java`、`AgentRunRequestFactory.java`、`ImportWorkspaceGateTest.java`、`scripts/checks/verify-import-agent-repair.py`。
- 门禁单测新增用例：非导入应用与 READY 导入应用放行、`PUBLISHING` 拒绝。定向测试退出码 0。
- 完整回归 `./mvnw.cmd test` 退出码 0：**698 项，0 失败、0 错误、6 跳过**。包含导入门禁、执行隔离、磁盘卷、日志遮盖等全部现有用例。
- 标准重启后跑真实闭环 `./agent/.venv/Scripts/python.exe scripts/checks/verify-import-agent-repair.py`，退出码 0，两轮均 PASS；最终证据 `temp/import-agent-repair-1791271255014440500/result.json`（10 条检查全 PASS）：
  1. 导入副本上真实复现缺陷（服务 READY，HTTP 返回 `{"reply":"pang"}`）；
  2. 停止后容器消失；
  3. 正式 SSE 通道交给真实模型，运行完成且无错误事件；
  4. Agent 只改缺陷字段值（`after.replace("pong","pang") == before.replace("pong","pang")`）；
  5. 源码变化使原运行计划 `STALE`；
  6. 重新确认计划运行后业务结果正确（200 / `{"reply":"pong"}`）；
  7. 重新导入的应用初始仍是缺陷原样（上一个应用的编辑未污染来源）；
  8. 干净来源重放后同样得到 pong；
  9. 重放验证后容器全部消失；
  10. 来源暂存上传件逐字节未变。
- 第一次运行（`temp/import-agent-repair-1791238836511520200`）暴露的失败是门禁拦截本身，修复后重跑；失败证据保留。

## 剩余边界与回退

- 该通道放行的是 Agent 的文件编辑与对话沙箱执行；导入应用的静态发布（`deployApp`）与旧运行入口（`runtime/start`）仍被拦截，各自等专项隔离能力。
- 本轮不覆盖：对话沙箱与受限卷执行两条隔离管线的统一、多服务组合下的 Agent 修复、以及完整矩阵其余组的重跑。私有仓库验收继续按用户决定暂缓。
- 回退：撤销 `requireAgentEditAllowed` 与工厂调用即恢复原拦截；已验证的导入应用工作区与保护快照不受影响，无需数据迁移。
