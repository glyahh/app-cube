# 自动批准后命令失败：修复与验收记录

日期：2026-10-20。仅覆盖本次故障链，不代表权限新增能力已整体验收。

## 现场与根因

- 用户应用 `469903321221492736` 的请求 `2112331302166085632` 已由 USER 批准并消费许可；`python .scratch/check.py` 实际退出 127，日志为 `python: command not found`。
- 未设置 `AGENT_SANDBOX_IMAGE` 时默认使用纯 `ubuntu:24.04`，现场没有 Python 3 或 Node；已构建的运行时镜像没有被选用。
- 普通 Docker 应用仍收到 Windows/cmd 提示词。Docker 非零退出被转为沙箱异常；许可层即使拿到明确的失败结果，也会停止整轮。
- `GUARDED_AUTO` 对应界面的“自动批准”，不等于所有动作自动放行；本次人工批准已生效。

## 实现

- 默认镜像统一为 `glyahh-sandbox-runtime:24.04`；本地 `.env` 仅修改镜像键，保留其它配置。启动预检验证镜像与 Node/npm、Python 3、Java/Maven、SQLite；容器获取时再次检查。禁止隐式拉取、安装和镜像降级。
- 提示词通过实际执行后端选择 Linux 或 Windows；导入的文件修改、安装与启动授权限制独立保留。
- 普通与导入 Docker 命令共用随机执行回执：只有 Shell 结束并返回匹配退出码，才确认命令结果。无回执、传输超时、Docker 与安全保护异常继续停止，不伪称命令已经结束。导入路径保留原有 `timeout -k 2` 限制。
- 已确认的命令失败上报 `RESULT_CONFIRMED, succeeded=false`，携带脱敏输出与退出码返回模型。修正执行仍使用新票据；不重放旧消费证明。文件工具失败的停止策略保留。
- 异常中间件直接传播普通/导入许可停止及沙箱异常，不把它们转成可重试工具消息。
- `tool.finished.exitCode` 来自 DeepAgents 的可信执行 artifact，经 Java 安全投影和前端映射保留；非零退出正确显示失败。不新增 REST API 或数据库迁移。

## 红绿测试

先新增 `agent/tests/test_terminal_recovery.py`：初始运行退出 1，11 项失败、1 项通过；四个根因均复现。Java 安全投影补充测试先得到 `expected 127, actual -1`，修复后通过。导入许可停止被中间件吞掉也先由回归测试复现，再修复。

最终源码基于 `e16c649965de2d8d4120edab7664fa14846f6a19` 的未提交工作区；核心源码摘要 `4962717f854fa1fc65eb357d4f6056bc7dcefcd495a9f07f6f14a62ca8d72215`，文件清单与逐文件摘要见 `temp/logs/terminal-recovery-20261020/source.json`。摘要不包含 `.env`。浏览器恢复链路三轮基于该文件中保留的前一摘要 `ce4744bdbcf5deec373a1947fbc88b5f461ed523ad481572cdd485a6ed322c41`；收尾增补了执行状态不明时的容器停止确认及预检目录隔离，另有回归与真实 Docker 证据。

| 检查 | 实际结果 |
|---|---|
| Python 相关 15 个测试文件，见下方命令 | 213 通过、3 跳过，退出 0；跳过项为显式启用的真实 Docker 检查 |
| 前端事件映射与权限组件 | 47 通过，退出 0 |
| `npm run type-check` | 退出 0 |
| Java 展示投影、请求身份、错误消息 | 17 通过，退出 0 |
| 显式启用真实 Docker 的硬限制、拒绝与清理检查 | 3 通过、29 未选取，退出 0 |
| 真实运行时预检 | 镜像与全部要求的运行时通过，退出 0 |
| 最终源码的真实终端探测 | Python 3 成功 0、明确失败 1、缺失命令 127；容器关闭及清理确认，退出 0 |
| 未知超时的真实故障注入 | 退出状态不明时确认容器停止；停止后重新获取与执行成功，清理确认，退出 0 |
| 运行时预检的工程隔离 | 工程内放置已知无效 `.mvn/jvm.config`，仍能从容器根目录通过版本预检并执行 Python，清理确认，退出 0 |

Python 命令（仓库根目录）：

```powershell
& .\agent\.venv\Scripts\python.exe -m pytest agent/tests/test_terminal_recovery.py agent/tests/test_sandbox_startup.py agent/tests/test_docker_sandbox.py agent/tests/test_docker_hard_swap.py agent/tests/test_tool_permissions.py agent/tests/test_permission_gate_timeout.py agent/tests/test_streaming_runtime.py agent/tests/test_import_command_policy.py agent/tests/test_import_edit_wiring.py agent/tests/test_import_edit_stop.py agent/tests/test_import_edit_runtime.py agent/tests/test_import_edit_admission.py agent/tests/test_read_only_backend.py agent/tests/test_tool_error_hints.py agent/tests/test_secret_free_docker.py -q
```

其它定向命令：

```powershell
./mvnw.cmd -q test '-Dtest=UserFacingOutputHandlerTest,ImportAgentRequestIdentityTest,AgentErrorMessageTest'
# 前端目录
npm run test:unit -- src/utils/__tests__/agentEventMapping.test.ts src/features/project-import/agent-permissions/__tests__/AgentPermissions.test.ts
npm run type-check
# 仓库根目录，显式真实 Docker 检查
$env:AGENT_TEST_REAL_DOCKER='1'
& .\agent\.venv\Scripts\python.exe -m pytest agent/tests/test_docker_hard_swap.py -k real -q
```

为运行相关测试，仅补齐两个已有夹具缺失的依赖：Java 请求工厂的 `ChatHistoryService` mock、导入后端记录的 `reused` 字段。未删除或弱化既有断言。

## 正式浏览器与数据库证据

使用隔离浏览器会话创建专用测试用户及应用 `469943627581198336`；通过正式 Vue 页面发送请求、点击“仅批准本次”，走真实 Java、Python、Docker 和模型，不手工插票或伪造模型输出。

1. `2112335728251600896`：`node --version` 的许可由 AI 自动批准。高风险脚本创建由 USER 批准；Python 语法错误实际返回 1。模型读到 `SyntaxError` 后提出编辑，编辑经 USER 批准；再次执行同一脚本使用另一张 USER 票据，输出 `TERMINAL_RECOVERY_OK`、退出 0。整批候选另行确认。
2. `2112337338763694080`：文件不存在实际返回 2，模型仍继续执行已有脚本并返回 0。浏览器 WebSocket 明确收到 `tool.finished.exitCode=2/0`，展示失败/成功；模型终稿如实区分两次结果。
3. `2112338505518407680`：共享执行回执版本重启后再走相同的真实错误 2 → 成功 0 链路，模型未中断，前端保留失败/成功状态及数值退出码。源码增补的停止确认分支随后独立验证。最终冒烟与清理结果见下方完成记录。

首轮失败/成功两张票据分别为 `779f9a72-0545-42d2-8809-7d39c4eaf655`、`193d1a8f-ba83-46a6-89a3-04581e3ef155`，均已消费并进入 `RESULT_CONFIRMED`，不是复用旧批准。原始安全投影证据保存于 `temp/logs/terminal-recovery-20261020/browser-first.txt` 与 `browser-exit-code.txt`，数据库投影保存于同目录 `database.json`。

## 最终完成记录

- 最终源码摘要 `4962717f854fa1fc65eb357d4f6056bc7dcefcd495a9f07f6f14a62ca8d72215` 的标准重启命令为 `powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1" -LogDirectory "temp/logs/terminal-recovery-20261020-final-accepted"`，退出 0。端口 8124 与预期结构化响应均通过；Python Worker 日志确认 PostgreSQL checkpointer/store 就绪。
- 最终源码正式浏览器冒烟请求 `2112340345089708032`：`node --version` 由 AI 自动批准，实际输出 `v22.23.2`，前端收到 `exitCode=0`，请求成功。证据保存于同目录 `browser-smoke.txt`。
- 本轮 4 个真实模型请求均为 SUCCEEDED，10 张工具许可均为 RESULT_CONFIRMED，没有未关闭编辑会话；首轮整批审阅为 USER/APPROVED。
- 仅清理本轮专用账户 `469943627417620480` 与应用 `469943627581198336`：通过正式接口逻辑删除并退出登录；精确核对容器标签和挂载目录后删除本轮容器，读回确认不存在；应用工作目录不存在。保留正常审计记录及验收证据，不修改用户原应用。
- `real-terminal.json` 记录真实 0/1/127、未知超时后停止确认与安全恢复。最终版本真实 Docker 硬保护检查 3 项通过，退出 0。纯 Ubuntu 缺少运行时的现场不再作为可用生成环境。
- `git diff --check` 通过，源码摘要复核一致，工作区原有改动保留，未执行 Git 提交。

## 验收边界

本轮未运行全量测试、SSE 单独验收、导入项目真实模型整链路或历史扩展矩阵。不覆盖任意用户脚本的业务正确性；命令成功、模型完成、整批批准和业务验收继续分别记录。保留用户原有工作区改动，不提交 Git。
