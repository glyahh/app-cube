# 原 PLANS 历史索引（整理前快照）

[全项目文档分类](../../README.md) · [验收、性能与故障报告](../../reports/README.md)

本页保留整理前的索引说明；其中状态只对原记录时点有效。文件现已按用途迁移，当前入口见[文档总索引](../../README.md)。

## 当前入口

- [当前项目状态](../../status/current.md)：只看顶部，历史节点不覆盖当前决定。
- [第一项最终范围与完成记录](../../status/import-scope-20261009.md)：已有项目导入 COMPLETE；已舍弃扩展不自动恢复为待办。
- [五组关键使用风险证据](../../reports/import/project-import-five-risk-20261009.md)：最终范围的专项验收记录。
- [权限模式计划](../../plans/import-agent-permission-modes-plan.md)、[前端评审记录](../../reviews/import-agent-permission-modes-frontend-review.md)：第一项完成后的新增能力；前端审核版与后端主体已在工作区实现，但未提交、未验收，状态见[项目状态顶部](../../status/current.md)。

## 历史证据与设计索引

以下说明按各文档记录时点保留；“待实现”“未完成”和旧测试数字不是当前第一项的完成门禁。功能验收证据保留在各专项报告，不在索引重复累计。

| 文档 | 说明 |
|------|------|
| [project-import-startup-scan-flow-verification.md](../../reports/import/project-import-startup-scan-flow-verification.md) | V21 后台扫描、请求找回、幂等修复和真实取消；扫描重启资源对账/硬额度未完成 |
| [project-import-startup-scan-ledger-verification.md](../../reports/import/project-import-startup-scan-ledger-verification.md) | 持久扫描身份/状态/资源账本与真实回滚 schema SQL 预检；V21 未应用，流程/API 未接入 |
| [project-import-startup-record-verification.md](../../reports/import/project-import-startup-record-verification.md) | 已完成识别证据持久化、真实重启找回、内容/配置/上下文过期拒绝；非扫描过程任务或执行计划 |
| [project-import-startup-configuration-verification.md](../../reports/import/project-import-startup-configuration-verification.md) | 应用级手动配置、版本 CAS、代码保存撤销 epoch 与真实数据库/API 局部证据 |
| [four-requirements-documentation.md](../plans/requirements-20261003.md) | 四项有效需求总览与阶段门禁；仅推进第一项 |
| [project-import-startup-python-verification.md](../../reports/import/project-import-startup-python-verification.md) | Python 标准库只读入口解析、输入身份、真实辅助进程与混合导入局部证据；非安装/运行 |
| [project-import-startup-business-verification.md](../../reports/import/project-import-startup-business-verification.md) | 启动同步只读检查、应用资格、PG 工作区锁和实际保存/删除边界；非持久扫描与执行计划 |
| [project-import-startup-backend-verification.md](../../reports/import/project-import-startup-backend-verification.md) | 用户已批准启动后端；只读识别/完整私有快照的局部测试与未接业务边界 |
| [project-import-startup-frontend-preview.md](../../reviews/project-import-startup-frontend-preview.md) | 原工作台的启动识别/安装授权/多服务交互评审；隔离样本，后端未实现 |
| [project-import-source-recovery-verification.md](../../reports/import/project-import-source-recovery-verification.md) | 请求丢失与刷新只读找回、owner 隔离、Windows Git 清理及保留失败证据 |
| [requirements-alignment-status.md](../../status/requirements.md) | 当前实现与验证边界；Git/凭据界面已批准接后端 |
| [project-import-git-private-verification.md](../../reports/import/project-import-git-private-verification.md) | 凭据固定资格、真实双平台认证负例、受管 askpass 与停止证明；有效私有成功验收暂缓 |
| [project-import-git-range-verification.md](../../reports/import/project-import-git-range-verification.md) | 离线固定快照范围重建、旧提交资格撤销与当前局部证据；保留未定位发布失败 |
| [project-import-git-frontend-verification.md](../../reports/import/project-import-git-frontend-verification.md) | 正式 Git/凭据双平台真实联调、取消时序和文件夹回归；非第一项完整验收 |
| [project-import-git-public-verification.md](../../reports/import/project-import-git-public-verification.md) | 双平台公开来源到最终应用局部证据、失败修复与剩余边界；不等于全项验收 |
| [project-import-design.md](../../plans/project-import-design.md) | 第一需求目标设计；不作为实现或验收证明 |
| [project-import-frontend-preview-v1.md](../../reviews/project-import-frontend-preview-v1.md) | 第一项文件夹前端可交互预览、待实现接口、真实浏览器局部证据与回退 |
| [project-import-test-matrix.md](../../tests/project-import-test-matrix.md) | 双入口、双平台与异常/安全/恢复的全面验收计划；待执行 |
| [project-import-backend-test-plan.md](../../tests/project-import-backend-test-plan.md) | 后端全面测试映射、58 组机器清单与逐步实施门禁；用户已授权文件夹后端开发 |
| [project-import-backend-step1-verification.md](../../reports/import/project-import-backend-step1-verification.md) | 首步纯清单校验：62 个新增测试及 35 个回归通过；真实上传/业务验收未执行 |
| [project-import-backend-upload-verification.md](../../reports/import/project-import-backend-upload-verification.md) | 私有上传任务与偏好：134 项 Java、47 项真实 API 局部检查；最终 App 提交及整项验收未完成 |
| [project-import-publication-verification.md](../../reports/import/project-import-publication-verification.md) | 后续文件夹提交/恢复/工作台真实联调证据；旧执行门禁与未完成范围 |
| [project-import-credential-verification.md](../../reports/import/project-import-credential-verification.md) | 已批准 Git/凭据界面的后端增量：加密/版本/撤销局部真实证据；Git 获取尚未完成 |
| [project-import-credential-task-contract.md](../../contracts/project-import-credential-task-contract.md) | 第一需求工程补充：Token 密文/版本、在途取消、受管 Git 执行及导入提交；研发建议未实现 |
| [project-import-startup-plan-contract.md](../../contracts/project-import-startup-plan-contract.md) | 第一需求工程补充：三层只读识别、服务选择、依赖/构建授权及多服务运行计划；研发建议未实现 |
| [project-acceptance-design.md](../../plans/project-acceptance-design.md) | 第二需求：统一聊天下的测试验收、隔离执行、版本化证据与报告；研发草案，未实现 |
| [project-acceptance-test-matrix.md](../../tests/project-acceptance-test-matrix.md) | 测试验收的分阶段与全面验收矩阵；全部待执行 |
| [project-acceptance-tooling-research.md](../../research/project-acceptance-tooling-research.md) | 官方工具/基准资料核对，区分标准、运行器与能力评测；候选未运行 |
| [project-development-workflow-design.md](../../plans/project-development-workflow-design.md) | 第三需求：统一聊天、授权、最新版本复现、最小修复和回归；设计未实现 |
| [project-development-workflow-test-matrix.md](../../tests/project-development-workflow-test-matrix.md) | 开发衔接验收矩阵；待执行 |
| [conversation-rollback-design.md](../../plans/conversation-rollback-design.md) | 回档专项：修改来源、三方合并、代码与上下文同步恢复及失败状态机 |
| [conversation-rollback-test-matrix.md](../../tests/conversation-rollback-test-matrix.md) | 回档及真正模型请求隔离验收；待执行 |
| [project-task-version-contract.md](../../contracts/project-task-version-contract.md) | 四项共同契约：版本、归属、权限、写入、取消与结果资格；未来目标 |
| [cross-feature-consistency-test-matrix.md](../../tests/cross-feature-consistency-test-matrix.md) | 四项联动与跨版本一致性验收；待执行 |
| [0001-rollback-clean-context-boundary.md](../../decisions/0001-rollback-clean-context-boundary.md) | 回档后严格建立纯净 AI 上下文的产品决策，实施待验证 |
| [conversation-memory-v4-current-architecture.md](../../plans/conversation-memory-v4-current-architecture.md) | 会话记忆 V4 **当前架构**（As-Is，含 TODO/缺口） |
| [conversation-memory-v4-target-architecture.md](../../plans/conversation-memory-v4-target-architecture.md) | 会话记忆 V4 **目标架构**（To-Be；方案文档，不代表实现完工） |
| [rag-v5-grep-glob-retrieval-plan.md](../plans/rag-v5-grep-glob-retrieval-plan.md) | 已归档的检索方案；不作为当前开发任务 |
| [code-knowledge-graph-design.md](../../plans/code-knowledge-graph-design.md) | 第五需求：统一图谱、通用解析器与 Agent 补全；主方案已确认，实现待验证 |
| [code-knowledge-graph-test-matrix.md](../../tests/code-knowledge-graph-test-matrix.md) | 图谱分阶段验收与 P0 验证设计；全部待执行 |
| [code-knowledge-graph-p0-contract.md](../../contracts/code-knowledge-graph-p0-contract.md) | P0 统一结果契约、人工样本与预期；未安装或执行候选 |

- [启动 Agent 只读内部核心](../../reports/import/project-import-startup-agent-core-verification.md)：不代表业务批准、真实模型成功或S05验收。

- [启动批准账本与回包校验内部证据](../../reports/import/project-import-startup-approval-core-verification.md)：V23、规则v2、严格冻结回包；批准API/模型执行尚未接。

- [独占执行租约与资源 CAS](../../reports/import/project-import-startup-model-lease-verification.md)、[封存文本与专属模型进程](../../reports/import/project-import-startup-model-executor-verification.md)、[启动扫描恢复证据](../../reports/import/project-import-startup-recovery-verification.md)：历史内部增量与原始验收边界。
