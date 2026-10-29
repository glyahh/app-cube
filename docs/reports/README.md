# 验收、性能与故障报告

> 2026-10-21 本地证据位置更新：先前 `temp/archive/cleanup-20261021/` 在后续盘点中已为空，本轮不声称恢复这些历史证据。按用户最新要求，现存闲置资料另归档至仓库外 `D:/codex/WorkDocument/2026-10-21/temp-archive/`，只覆盖该目录 `manifest.json` 列出的509个文件；保留原 temp 相对路径。正在写入的日志保持原位。报告中的历史命令与原证据路径仍按记录时点理解，不能认为原始证据都可读取；本轮未重新执行验收。详见[归档执行记录](../plans/temp-cleanup-20261021.md)。

[返回文档总索引](../README.md) · [当前状态](../status/current.md)

- [修复计划证据](evidence/README.md)：P0 基线、P2 沙箱检查与 P5 基准自检，按阶段记录命令与未验证项。

实际执行、测量、根因和验证边界；每份记录只对其代码版本和环境有效，不能代替整体验收。

## 近期重点

- [11 项缺陷修复与红绿验证](bugfixes/eleven-bugs-20261026.md)：F01–F11、真实回归和未通过的基线测试分开记录。

- [长对话与 Agent 量化提速](agent/chat-agent-performance-20261020.md)：原始样本、前后数据、真实任务失败与未确认原因。
- [批准后命令失败恢复](agent/terminal-command-recovery-20261020.md)：根因、红绿测试、新票据与真实链路证据。
- [导入安装超时修复](import/project-import-install-timeout-final-20261009.md)：失败现场与最终版本验证。
- [导入五组风险专项](import/project-import-five-risk-20261009.md)：当前最终范围中的专项证据。

## Agent 与聊天

- [长对话与 Agent 速度优化验收（2026-10-20）](agent/chat-agent-performance-20261020.md)
- [自动批准后命令失败：修复与验收记录](agent/terminal-command-recovery-20261020.md)

## 项目导入

- [导入应用的 Agent 编辑通道与真实修复闭环：验收记录](import/project-import-agent-edit-channel-verification.md)
- [导入后端第一步：清单校验验证记录](import/project-import-backend-step1-verification.md)
- [文件夹导入：真实暂存任务与长期偏好验证](import/project-import-backend-upload-verification.md)
- [导入凭据：后端阶段验证](import/project-import-credential-verification.md)
- [受限持久磁盘卷核心：局部验收](import/project-import-disk-volume-core-verification.md)
- [受限卷预留空间与暂停/恢复：局部验收](import/project-import-disk-volume-recovery-verification.md)
- [受限卷冻结文件复制：2026-10-06 局部验证](import/project-import-disk-volume-seed-verification.md)
- [第一项关键使用风险：5组专项交付](import/project-import-five-risk-20261009.md)
- [正式 Git／凭据页面联调证据](import/project-import-git-frontend-verification.md)
- [Git 导入：早期接入证据与约束（历史）](import/project-import-git-next-step.md)
- [Git 凭据读取：当前实现与局部证据](import/project-import-git-private-verification.md)
- [Git 公开来源：业务接入与验证证据](import/project-import-git-public-verification.md)
- [固定 Git 内容的范围重建](import/project-import-git-range-verification.md)
- [导入服务冻结健康路径：验收记录](import/project-import-health-path-verification.md)
- [安装超时修复：最终版本实施与验收报告](import/project-import-install-timeout-final-20261009.md)
- [文件夹导入：应用提交、恢复与工作台联调](import/project-import-publication-verification.md)
- [第一项运行状态修复与验证](import/project-import-runtime-quality-fixes.md)
- [导入服务持续日志与退出监督：局部验收](import/project-import-service-supervision-verification.md)
- [来源请求找回与 Windows Git 清理验证](import/project-import-source-recovery-verification.md)
- [启动 Agent 补充：内部只读核心](import/project-import-startup-agent-core-verification.md)
- [启动补充批准账本与回包校验：内部增量](import/project-import-startup-approval-core-verification.md)
- [识别补充批准业务流：步骤 1 增量](import/project-import-startup-approval-flow-verification.md)
- [启动后端：只读核心局部证据](import/project-import-startup-backend-verification.md)
- [启动只读检查：应用资格与写入协调](import/project-import-startup-business-verification.md)
- [识别补充候选发布与合法上下文：步骤 2 增量](import/project-import-startup-candidate-publish-verification.md)
- [应用级启动配置：局部验证](import/project-import-startup-configuration-verification.md)
- [冻结运行计划：步骤 3 增量](import/project-import-startup-freeze-plan-verification.md)
- [隔离执行副本：步骤 5 增量](import/project-import-startup-isolation-verification.md)
- [执行副本、槽位与分阶段网络](import/project-import-startup-lifetime-verification.md)
- [启动补充：封存文本与专属模型进程](import/project-import-startup-model-executor-verification.md)
- [启动补充执行租约：内部验证](import/project-import-startup-model-lease-verification.md)
- [安装确认与按计划运行：步骤 6–9](import/project-import-startup-preparation-verification.md)
- [Python 启动只读识别：局部验证](import/project-import-startup-python-verification.md)
- [启动识别完成证据：持久化与重启找回](import/project-import-startup-record-verification.md)
- [启动扫描恢复：局部实现与验证](import/project-import-startup-recovery-verification.md)
- [持久扫描流程：真实后台任务与取消](import/project-import-startup-scan-flow-verification.md)
- [持久扫描任务：状态与资源账本预检](import/project-import-startup-scan-ledger-verification.md)
- [正式工作台联调：步骤 4 增量](import/project-import-startup-workbench-verification.md)

## 工作台

- [Auto 工作台设计对齐记录（2026-09-19）](workbench/alignment-20260919.md)

- [scripts 分类整理与审查记录](workbench/scripts-layout-20261021.md)
