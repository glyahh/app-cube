# 项目文档分类总索引

文档按用途实际分目录，目录名统一使用小写英文。根目录与模块 README 保留为开发入口；历史资料保留原事实，迁移只调整位置和引用。当前能力只看 [status/current.md](status/current.md)，第一项范围以 [最终范围](status/import-scope-20261009.md) 为准。

## 快速定位

| 要找什么 | 入口 |
|---|---|
| 最新能力、已知问题、验证边界 | [当前状态](status/current.md) |
| 量化性能、故障根因、真实验收 | [报告索引](reports/README.md) |
| Java/Python 协议与事件 | [Agent 契约](contracts/agent.md) |
| 授权模式方案 | [导入 Agent 会话授权](plans/import-agent-permission-modes-plan.md) |
| 安装、启动、构建与重启 | [操作指南](guides/README.md) |
| 新文档应该放哪里 | [维护规则](rules/README.md) |

## 目录与分类

```text
docs/
  README.md     文档总入口
  status/       当前状态与范围
  rules/        开发与界面规范
  contracts/    接口与领域契约
  plans/        设计与实施方案
  tests/        测试计划与机器清单
  reports/      真实验收、性能与故障报告
    agent/      Agent 与聊天
    import/     项目导入
    workbench/  工作台
  reviews/      前端预览与评审
  guides/       启动与操作指南
  research/     技术调研
  notes/        技术说明与学习复盘
  decisions/    架构决策
  assets/       文档附件与原型
  archive/      历史资料
```

| 目录 | Markdown 数量（含目录索引） | 用途 |
|---|---:|---|
| [status/](status/README.md) | 7 | 当前状态与范围 |
| [rules/](rules/README.md) | 3 | 开发与界面规范 |
| [contracts/](contracts/README.md) | 6 | 接口与领域契约 |
| [plans/](plans/README.md) | 17 | 设计与实施方案 |
| [tests/](tests/README.md) | 8 | 测试计划与机器清单 |
| [reports/](reports/README.md) | 43 | 验收、性能与故障报告 |
| [reviews/](reviews/README.md) | 5 | 前端预览与评审 |
| [guides/](guides/README.md) | 3 | 启动与操作指南 |
| [research/](research/README.md) | 4 | 技术调研 |
| [notes/](notes/README.md) | 9 | 技术说明与学习复盘 |
| [decisions/](decisions/README.md) | 2 | 架构决策 |
| [assets/](assets/README.md) | 1 | 文档附件与原型 |
| [archive/](archive/README.md) | 37 | 历史资料 |

## 仓库与模块入口

- [项目脚本分类与启动入口](../scripts/README.md)
- [检查与验收脚本](../scripts/checks/README.md)

- [项目介绍](../README.md)
- 本地领域术语与约束：`CONTEXT.md`（不随仓库分发）
- [Python Agent](../agent/README.md)
- [前端开发](../ai-generate-code-frontend/README.md)
- 本地后端设计与编码规范：`.cursor/skills/backend-design/SKILL.md`（不随仓库分发）

旧 `Platform-Utils/` 与 `scripts/archive/` 已从工作区移除，不作为当前工具入口；历史报告中的命令仅供追溯。

本地 `AGENTS.md`、`CLAUDE.md` 保持根目录约定位置，并同步本文档路径。它们由本地维护，不纳入 Git 文档数量。`src/main/resources/Prompt/*.txt` 是运行提示词，`requirements.txt` 是依赖清单；两者保持代码约定位置，不混入人工文档目录。

## 逐文档分类

下列清单覆盖 docs 内全部 Markdown 正文；各目录 README 由上表进入，正文不复制到索引。历史目录按用途继续分组，历史中的目标、旧数字和未完成描述均不决定当前状态。

### 当前状态与范围

- [当前项目状态与开发主线](status/current.md)
- [第一项验收](status/import-acceptance.md)
- [第一项收口标准](status/import-closure.md)
- [第一项：最终范围与完成记录](status/import-scope-20261009.md)
- [第一项当前收口记录](status/import-summary.md)
- [后续开发需求对齐状态](status/requirements.md)

### 开发与界面规范

- [应用魔方：品牌与仓库命名](rules/branding.md)
- [项目界面文案规范](rules/ui-copy.md)

### 接口与领域契约

- [Java ↔ Python 接口契约](contracts/agent.md)
- [图谱 P0：统一结果契约与首轮样本](contracts/code-knowledge-graph-p0-contract.md)
- [项目导入：凭据版本与任务协调契约](contracts/project-import-credential-task-contract.md)
- [项目导入：启动识别与运行计划契约](contracts/project-import-startup-plan-contract.md)
- [四项功能共同契约：项目、任务、版本与上下文资格](contracts/project-task-version-contract.md)

### 设计与实施方案

- [temp 清理方案与归档执行记录](plans/temp-cleanup-20261021.md)

- [Agent 权限门禁：沙箱边界 + 审批策略](plans/agent-permission-gate-design.md)
- [全语言代码知识图谱：需求与设计](plans/code-knowledge-graph-design.md)
- [上下文压缩机制 —— 设计说明](plans/context-design.md)
- [上下文压缩机制 —— 实现设计 Prompt](plans/context-implementation-prompt.md)
- [上下文压缩分层策略](plans/context-layers.md)
- [会话记忆 V4：当前架构（As-Is）](plans/conversation-memory-v4-current-architecture.md)
- [会话记忆 V4：目标架构（To-Be / 完全完工）](plans/conversation-memory-v4-target-architecture.md)
- [会话回档：代码、用户修改与纯净上下文](plans/conversation-rollback-design.md)
- [前端设计说明（ai-generate-code-frontend）](plans/frontend.md)
- [导入 Agent 编辑：改动清单人工确认计划](plans/import-agent-change-review-plan.md)
- [导入 Agent 编辑：会话授权模式与统一门禁计划](plans/import-agent-permission-modes-plan.md)
- [项目测试验收：专项开发设计草案](plans/project-acceptance-design.md)
- [开发、测试与验证衔接：专项设计](plans/project-development-workflow-design.md)
- [已有项目导入：开发设计草案](plans/project-import-design.md)
- [启动后端的小步实施与测试](plans/project-import-startup-backend-steps.md)
- [修复计划：从自证交付转向可复跑证据](plans/remediation-plan-20261029.md)

### 测试计划与机器清单

- [全语言代码知识图谱：验证计划](tests/code-knowledge-graph-test-matrix.md)
- [会话回档：来源、合并、上下文与失败恢复验收](tests/conversation-rollback-test-matrix.md)
- [四项功能：跨功能一致性验收](tests/cross-feature-consistency-test-matrix.md)
- [项目测试验收：分阶段验收矩阵](tests/project-acceptance-test-matrix.md)
- [开发、测试与验证衔接：验收矩阵](tests/project-development-workflow-test-matrix.md)
- [导入后端测试与实施基线](tests/project-import-backend-test-plan.md)
- [已有项目导入：全面测试矩阵与交付门槛](tests/project-import-test-matrix.md)

### 验收、性能与故障报告

- [scripts 分类整理与审查记录](reports/workbench/scripts-layout-20261021.md)

- [长对话与 Agent 速度优化验收（2026-10-20）](reports/agent/chat-agent-performance-20261020.md)
- [自动批准后命令失败：修复与验收记录](reports/agent/terminal-command-recovery-20261020.md)
- [导入应用的 Agent 编辑通道与真实修复闭环：验收记录](reports/import/project-import-agent-edit-channel-verification.md)
- [导入后端第一步：清单校验验证记录](reports/import/project-import-backend-step1-verification.md)
- [文件夹导入：真实暂存任务与长期偏好验证](reports/import/project-import-backend-upload-verification.md)
- [导入凭据：后端阶段验证](reports/import/project-import-credential-verification.md)
- [受限持久磁盘卷核心：局部验收](reports/import/project-import-disk-volume-core-verification.md)
- [受限卷预留空间与暂停/恢复：局部验收](reports/import/project-import-disk-volume-recovery-verification.md)
- [受限卷冻结文件复制：2026-10-06 局部验证](reports/import/project-import-disk-volume-seed-verification.md)
- [第一项关键使用风险：5组专项交付](reports/import/project-import-five-risk-20261009.md)
- [正式 Git／凭据页面联调证据](reports/import/project-import-git-frontend-verification.md)
- [Git 导入：早期接入证据与约束（历史）](reports/import/project-import-git-next-step.md)
- [Git 凭据读取：当前实现与局部证据](reports/import/project-import-git-private-verification.md)
- [Git 公开来源：业务接入与验证证据](reports/import/project-import-git-public-verification.md)
- [固定 Git 内容的范围重建](reports/import/project-import-git-range-verification.md)
- [导入服务冻结健康路径：验收记录](reports/import/project-import-health-path-verification.md)
- [安装超时修复：最终版本实施与验收报告](reports/import/project-import-install-timeout-final-20261009.md)
- [文件夹导入：应用提交、恢复与工作台联调](reports/import/project-import-publication-verification.md)
- [第一项运行状态修复与验证](reports/import/project-import-runtime-quality-fixes.md)
- [导入服务持续日志与退出监督：局部验收](reports/import/project-import-service-supervision-verification.md)
- [来源请求找回与 Windows Git 清理验证](reports/import/project-import-source-recovery-verification.md)
- [启动 Agent 补充：内部只读核心](reports/import/project-import-startup-agent-core-verification.md)
- [启动补充批准账本与回包校验：内部增量](reports/import/project-import-startup-approval-core-verification.md)
- [识别补充批准业务流：步骤 1 增量](reports/import/project-import-startup-approval-flow-verification.md)
- [启动后端：只读核心局部证据](reports/import/project-import-startup-backend-verification.md)
- [启动只读检查：应用资格与写入协调](reports/import/project-import-startup-business-verification.md)
- [识别补充候选发布与合法上下文：步骤 2 增量](reports/import/project-import-startup-candidate-publish-verification.md)
- [应用级启动配置：局部验证](reports/import/project-import-startup-configuration-verification.md)
- [冻结运行计划：步骤 3 增量](reports/import/project-import-startup-freeze-plan-verification.md)
- [隔离执行副本：步骤 5 增量](reports/import/project-import-startup-isolation-verification.md)
- [执行副本、槽位与分阶段网络](reports/import/project-import-startup-lifetime-verification.md)
- [启动补充：封存文本与专属模型进程](reports/import/project-import-startup-model-executor-verification.md)
- [启动补充执行租约：内部验证](reports/import/project-import-startup-model-lease-verification.md)
- [安装确认与按计划运行：步骤 6–9](reports/import/project-import-startup-preparation-verification.md)
- [Python 启动只读识别：局部验证](reports/import/project-import-startup-python-verification.md)
- [启动识别完成证据：持久化与重启找回](reports/import/project-import-startup-record-verification.md)
- [启动扫描恢复：局部实现与验证](reports/import/project-import-startup-recovery-verification.md)
- [持久扫描流程：真实后台任务与取消](reports/import/project-import-startup-scan-flow-verification.md)
- [持久扫描任务：状态与资源账本预检](reports/import/project-import-startup-scan-ledger-verification.md)
- [正式工作台联调：步骤 4 增量](reports/import/project-import-startup-workbench-verification.md)
- [Auto 工作台设计对齐记录（2026-09-19）](reports/workbench/alignment-20260919.md)

### 前端预览与评审

- [导入 Agent 会话授权：前端审核记录](reviews/import-agent-permission-modes-frontend-review.md)
- [第一项：文件夹导入正式前端接入](reviews/project-import-frontend-integration-v1.md)
- [第一项：文件夹导入前端第一版评审](reviews/project-import-frontend-preview-v1.md)
- [启动识别、授权与服务选择：前端评审](reviews/project-import-startup-frontend-preview.md)

### 启动与操作指南

- [项目编译与启动命令速查](guides/commands.md)
- [Auto 通用项目工作台改造 —— 实施与运维说明](guides/workbench.md)

### 技术调研

- [上下文压缩机制调研（精简版）](research/context-summary.md)
- [测试验收：工具与基准复用调研](research/project-acceptance-tooling-research.md)
- [Coding Agent 流式协议选型：SSE 还是 WebSocket](research/stream-protocol.md)

### 技术说明与学习复盘

- [数据库表结构图（Markdown 预览）](notes/database-diagram.md)
- [iframe 预览与可视化编辑（技术说明）](notes/iframe-preview.md)
- [会话记忆完整架构图](notes/memory-diagram.md)
- [会话记忆指南（会话记忆重构 V4 学习复盘）](notes/memory-guide.md)
- [会话记忆精简图](notes/memory-overview.md)
- [Parser & Saver 重构模式总结](notes/parser-saver.md)
- [纯函数 + 数据驱动状态切换 — 项目实战分析](notes/pure-functions.md)
- [流式工具调用卡片缺失问题复盘](notes/tool-card-review.md)

### 架构决策

- [回档隔离错误上下文，而不是在原上下文追加“已回档”](decisions/0001-rollback-clean-context-boundary.md)

### 文档附件与原型


### 历史资料

- [OpenAPI definition](archive/api/backend-api.md)
- [后端潜在问题清单](archive/bugs/potential-issues.md)
- [工具调用卡片折叠 + Hover-Copy 交互设计](archive/design/2026-06-10-tool-card-collapsible-copy-design.md)
- [我的应用筛选项 + 查看历史应用名称搜索 设计文档](archive/design/2026-06-11-home-filters-and-chat-search-design.md)
- [工具卡片状态切换阈值随机化](archive/design/2026-06-11-tool-status-random-threshold-design.md)
- [普通用户历史记录 AI 输出内容屏蔽设计](archive/design/2026-06-11-user-history-content-masking-design.md)
- [UserChatHistory 列表页 AI 消息摘要展示设计](archive/design/2026-06-11-userchat-history-masking-design.md)
- [外观设置系统设计文档](archive/design/2026-06-12-appearance-settings-design.md)
- [外观设置缺陷修复方案](archive/design/2026-06-12-appearance-settings-fixes-design.md)
- [用户个性化配置设计文档](archive/design/2026-06-16-user-personalization-design.md)
- [Loop Skill 市场设计文档](archive/design/2026-06-17-loop-skill-market-design.md)
- [AI 记忆分层注入方案设计](archive/design/2026-06-20-ai-memory-tiered-injection-design.md)
- [Java 全栈面试问答 — 简历「个人职责」对照版](archive/interview/java-interview.md)
- [glyahh-ai-generate-code 项目面试准备](archive/interview/project-interview.md)
- [上下文压缩机制调研](archive/notes/context-research.md)
- [原 PLANS 历史索引（整理前快照）](archive/notes/plan-index-20261020.md)
- [项目状态历史记录（整理于2026-10-20）](archive/notes/status-history-20261020.md)
- [会话总结](archive/notes/summarize_learn_2026-05-05_02-41.md)
- [工具调用卡片折叠 + Hover-Copy 交互 执行计划](archive/plans/2026-06-10-tool-card-collapsible-copy-plan.md)
- [首页筛选项 + 查看历史搜索 Implementation Plan](archive/plans/2026-06-11-home-filters-and-chat-search.md)
- [工具卡片状态切换阈值随机化 — 实现计划](archive/plans/2026-06-11-tool-status-random-threshold-plan.md)
- [普通用户历史记录 AI 输出屏蔽 — 实现计划](archive/plans/2026-06-11-user-history-content-masking-plan.md)
- [UserChatHistory 列表页 AI 消息摘要展示 — 实现计划](archive/plans/2026-06-11-userchat-history-masking-plan.md)
- [外观设置缺陷修复 实现计划](archive/plans/2026-06-12-appearance-settings-fixes-plan.md)
- [外观设置系统实现计划](archive/plans/2026-06-12-appearance-settings-plan.md)
- [用户个性化配置 Implementation Plan](archive/plans/2026-06-16-user-personalization.md)
- [Loop Skill 市场 Implementation Plan](archive/plans/2026-06-17-loop-skill-market-plan.md)
- [AI 记忆分层注入 Implementation Plan](archive/plans/2026-06-20-ai-memory-tiered-injection-plan.md)
- [agent_next 迁移说明](archive/plans/agent-next-migration.md)
- [修复 `/gen/code` 工具请求时机漂移](archive/plans/Codex_plan_1.md)
- [历史计划：Java 会话记忆 V4](archive/plans/java-memory-v4.md)
- [Loop Skill 市场实施计划审查意见](archive/plans/loop-skill-plan-review.md)
- [RAG text-embedding-v4 上线步骤](archive/plans/rag-v4-rollout.md)
- [RAG V5 Grep/Glob 检索更新计划](archive/plans/rag-v5-grep-glob-retrieval-plan.md)
- [四项需求：开发文档交付总览](archive/plans/requirements-20261003.md)
- [浏览器端到端测试报告 — AI 记忆分层注入](archive/reports/2026-06-20-browser-e2e-test-report.md)

## 其他文件与原始证据

- [tests/](tests/README.md) 中的 JSON 是工具使用的机器清单，与测试矩阵一起维护，不是验收结果。
- [assets/](assets/README.md) 保留当前文档原型；[archive/](archive/README.md) 中的 SQL、演示和截图仅作历史资料。当前数据库迁移以 `src/main/resources/db/migration/` 为准。
- `temp/logs/` 是被忽略的本地日志、截图与样本目录，各报告记录具体证据位置；整理文档不迁移原始证据，也不将本地临时文件计入项目文档。
- 新增和移动文档遵守[维护规则](rules/README.md)，同时更新链接与脚本引用。
