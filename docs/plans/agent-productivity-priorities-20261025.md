# Coding Agent 下一步重点优化
日期：2026-10-25（Asia/Shanghai）。状态：待实施；本次仅同步计划，不代表已开发或验收通过。

## 基线与边界
- 仓库：glyahh/app-cube；开发基线 code_board / b71ad916bb72be8bc0dc92b2da4389073257ae9b，不采用旧 master 或 agent_next 的差距判断。
- 保留 Python、DeepAgents、LangGraph、PostgreSQL、Spring Boot 和 Vue，不推倒重做。
- 已有工具循环、错误反馈、上下文压缩、检查点、沙箱、审批、取消及文件回滚，优化这些能力的衔接，不重复造模块。
- 以下是用户确认记录的下一步优化方向，不是五项已确认缺陷。已有性能及终端恢复报告继续有效，本轮未重跑测试；尚未进行与 Pi 同条件的真实任务对照。
- 保留现有安全与权限边界；已有缺陷清单另行保留，本计划不将其关闭，也不自动授权实施或放宽权限。

## 五项优化清单
- [ ] **1. 自动加载项目规范与 Skills。** 沿用 DeepAgents 原生 memory/skills 机制，明确工作区范围、优先级、按需加载与来源。项目内容不得改变执行权限。主要涉及 agent/server.py 及资源发现模块。验收：无需重复交代即可遵守项目规范，缺失/冲突/越界规则有明确处理。
- [ ] **2. 用真实任务建立基线。** 选择约10项常见任务（搜索功能、修错、接接口、跨文件修改等），固定模型、仓库初始版本、权限、预算和验收方式；与 Pi 对比成功率、人工介入、耗时及成本。不得把模型自评或工具结束当任务通过。正式改动前先保存原始基线。
- [ ] **3. 减少无效操作。** 根据基线定位重复读取、无效重试、冗长工具前说明和其他往返。保留必要审批及验证；只有测量支持才优化，不机械禁止必要重读。主要涉及 Python prompt/middleware/工具契约，展示变化再配套 Vue。验收：同任务无效往返减少，成功率与安全边界不退化。
- [ ] **4. 打磨中断后继续。** 复用已有 PG 检查点、取消与文件回滚；核对代码版本、已确认操作、未完成事项和不确定副作用后续跑，不盲目重放写操作。检查点续聊与进程恢复分开。涉及 Python runtime/persistence、Java任务协议及Vue入口。验收：中断/重启后不丢已确认修改、不重复执行未知结果动作，冲突明确报告。
- [ ] **5. 完成必须有验收证据。** 区分运行终态与产物验收状态，记录改动、代码版本、构建/测试结果、未测项和阻塞。复用已有验收设计，而非另造平行体系。验收：未执行检查不能显示通过，运行结束不冒充项目可用。

## 实施顺序
先做任务基线，再接项目规则/Skills并对比；依据证据逐项推进第3—5项。五项编号为沟通清单，不要求未经测量同时重构。每次只改一个明确短板。

## 对标证据
- [当前Agent装配](https://github.com/glyahh/app-cube/blob/b71ad916bb72be8bc0dc92b2da4389073257ae9b/agent/server.py)：未显式传入 memory/skills。
- [锁定版本DeepAgents接线](https://github.com/langchain-ai/deepagents/blob/deepagents==0.7.13/libs/deepagents/deepagents/graph.py)：默认参数None，仅提供时安装相应中间件；不等于模型不能主动读取规则文件。
- [Pi资源加载](https://github.com/earendil-works/pi/blob/38ff99177fba21e9c57a670f4e95adcf407c99c7/packages/coding-agent/src/core/resource-loader.ts)。
- [Pi会话机制](https://github.com/earendil-works/pi/blob/38ff99177fba21e9c57a670f4e95adcf407c99c7/packages/coding-agent/src/core/session-manager.ts)：借鉴而非整体照搬；Pi也不默认保证生产验收。
- [已有性能记录](../reports/agent/chat-agent-performance-20261020.md)；[已有终端恢复记录](../reports/agent/terminal-command-recovery-20261020.md)。
- [已有验收设计](project-acceptance-design.md)；[已有开发验证流程](project-development-workflow-design.md)。
