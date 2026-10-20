# 测试计划与机器清单

[返回文档总索引](../README.md) · [当前状态](../status/current.md)

测试矩阵、待执行用例和机器输入；实际执行结果放 reports/，不将 NOT_RUN 解释为通过。

## 文档清单

- [全语言代码知识图谱：验证计划](code-knowledge-graph-test-matrix.md)
- [会话回档：来源、合并、上下文与失败恢复验收](conversation-rollback-test-matrix.md)
- [四项功能：跨功能一致性验收](cross-feature-consistency-test-matrix.md)
- [项目测试验收：分阶段验收矩阵](project-acceptance-test-matrix.md)
- [开发、测试与验证衔接：验收矩阵](project-development-workflow-test-matrix.md)
- [project-import-backend-test-cases.json](project-import-backend-test-cases.json)
- [导入后端测试与实施基线](project-import-backend-test-plan.md)
- [project-import-core-checkpoints.json](project-import-core-checkpoints.json)
- [已有项目导入：全面测试矩阵与交付门槛](project-import-test-matrix.md)

## 工具状态说明（2026-10-20 文档整理）

`check-import-backend-test-plan.ps1` 已更新默认清单位置，但现有脚本只接受 PASS / FAIL / NOT_RUN / BLOCKED / NOT_APPLICABLE，无法识别原清单中 G03 的 `REMOVED_FROM_SCOPE`。整理前 HEAD 脚本与清单同样退出 1；本次不修改状态或放宽校验。原 JSON 保持59条用例，原矩阵保持58行用例；G12 在原矩阵正文有说明、无独立表格行，这是既有差异。移除范围不计作通过。

路径调整后的 `scripts/tests/test_item1_complete_gate.py` 与 `test_item1_core_gate.py` 本轮共45项通过。这里只证明门禁脚本回归，不代表重新执行了业务验收。
