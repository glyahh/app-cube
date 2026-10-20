# 检查与验收脚本

[返回脚本入口](../README.md)

本目录保存仍有检查、验收或回归价值的工具，不是废弃命令。所有命令从仓库根目录执行，避免将相对 `.env`、源码和输出路径解释到错误目录。脚本由原 scripts 根目录迁入；目录变化不代表历史报告重新通过，也不恢复已移出范围的验收要求。

## 常用入口

```powershell
./agent/.venv/Scripts/python.exe scripts/checks/verify-item1-core-complete.py --help
./agent/.venv/Scripts/python.exe scripts/checks/verify-import-frameworks.py --help
./agent/.venv/Scripts/python.exe scripts/checks/init-item1-ledger.py --help
```

| 场景 | 入口与限制 |
|---|---|
| 门禁与账本 | `verify-item1-complete.py`、`verify-item1-core-complete.py`、`init-item1-ledger.py` 及 `item1_*requirements.py`；保留全部断言与固定分母，不因范围整理伪造通过 |
| 长对话回放汇总 | `verify-chat-performance.py` 读取原始报告；对应前端回放脚本仍在前端 tests/perf |
| 独立审查性能 | `verify-review-performance.py` 会调用真实模型，不是只读统计 |
| 导入与启动联调 | `verify-import-*`、`verify-startup-*`、`e2e_permission_live.py`；按脚本所需环境运行，可能写入测试数据或运行受控夹具 |
| 独立资源读回 | `verify_import_resource_cleanup.py` 只读取清理结果 |
| 性能测试账户清理 | `cleanup-performance-account.py` 会修改夹具账户状态；只接受脚本限定的报告与身份，不可泛用于真实用户 |
| 固定框架输入 | `import_framework_fixtures.py` 与 `fixtures/import-vue-package-lock.json` 配套保留，不能只移动或删除其中一份 |

`check-import-backend-test-plan.ps1` 仍保留原有局限：不接受原清单里的 `REMOVED_FROM_SCOPE`，且原矩阵缺少G12独立表格行。目录移动只修正仓库根路径，不放宽校验；现有说明见[测试资料](../../docs/tests/README.md)。

## 文件清单

- [check-import-backend-test-plan.ps1](check-import-backend-test-plan.ps1)
- [cleanup-performance-account.py](cleanup-performance-account.py)
- [e2e_permission_live.py](e2e_permission_live.py)
- [import_framework_fixtures.py](import_framework_fixtures.py)
- [init-item1-ledger.py](init-item1-ledger.py)
- [item1_core_requirements.py](item1_core_requirements.py)
- [item1_requirements.py](item1_requirements.py)
- [verify-agent-contract.py](verify-agent-contract.py)
- [verify-chat-performance.py](verify-chat-performance.py)
- [verify-import-agent-repair.py](verify-import-agent-repair.py)
- [verify-import-auth-negative.py](verify-import-auth-negative.py)
- [verify-import-credentials.py](verify-import-credentials.py)
- [verify-import-disk-volume.py](verify-import-disk-volume.py)
- [verify-import-framework-browser.mjs](verify-import-framework-browser.mjs)
- [verify-import-frameworks.py](verify-import-frameworks.py)
- [verify-import-publication-recovery.py](verify-import-publication-recovery.py)
- [verify-import-secret-chain.py](verify-import-secret-chain.py)
- [verify-import-source-request.py](verify-import-source-request.py)
- [verify-import-source.py](verify-import-source.py)
- [verify-import-task-api.py](verify-import-task-api.py)
- [verify-item1-complete.py](verify-item1-complete.py)
- [verify-item1-core-complete.py](verify-item1-core-complete.py)
- [verify-review-performance.py](verify-review-performance.py)
- [verify-startup-agent-protocol.py](verify-startup-agent-protocol.py)
- [verify-startup-approval-api.py](verify-startup-approval-api.py)
- [verify-startup-candidate-publish-api.py](verify-startup-candidate-publish-api.py)
- [verify-startup-inspection-api.py](verify-startup-inspection-api.py)
- [verify-startup-isolation-real.py](verify-startup-isolation-real.py)
- [verify-startup-lifetime-api.py](verify-startup-lifetime-api.py)
- [verify-startup-lifetime-gaps.py](verify-startup-lifetime-gaps.py)
- [verify-startup-model-helper.py](verify-startup-model-helper.py)
- [verify-startup-model-supplement.py](verify-startup-model-supplement.py)
- [verify-startup-plan-api.py](verify-startup-plan-api.py)
- [verify-startup-remaining.py](verify-startup-remaining.py)
- [verify-startup-workspace-lock.py](verify-startup-workspace-lock.py)
- [verify_import_resource_cleanup.py](verify_import_resource_cleanup.py)
- [固定Vue锁文件](fixtures/import-vue-package-lock.json)
