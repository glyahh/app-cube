## PR 验证与审查

- 合并条件只有一个固定状态检查：`merge-gate`。它只汇总确定性结果，不允许 AI Review 代替测试。
- `.github/workflows/pr-checks.yml` 按改动路径选择检查：Java 改动跑编译、单测、安全/回归、Testcontainers 集成和构建；Python 改动跑离线 pytest；前端改动跑类型、Lint、格式、Vitest、构建和 Playwright 核心流程。工作流、公共脚本和 `.github/**` 改动会触发全部检查。每周一完整跑一次。
- 路径未命中的 job 显示 `skipped` 是路径过滤，不是测试跳过。`merge-gate` 会核对被选中的 job 必须 `success`；任一 `failure`、`cancelled` 或缺少结果都阻止合并。
- 必需测试禁止真实模型调用：CI 不配置模型密钥，端点指向本地无效地址；Python 测试在无外网 namespace 内执行，模型交互只走 MockTransport 或本地 Mock Server。不要用空测试、无条件跳过、吞异常或伪造报告让门禁变绿。
- 带 `external` 标签的测试（真实数据库/Docker/外部服务的旧验收）不进入合并门禁，只能由维护者显式设置环境后运行。其未运行不算通过。
- 真实模型测试只存在于 `agent/live_tests/`，只能通过 `Real AI Tests` 手动工作流触发；工作流使用 `real-ai` environment，必须人工确认并自行承担费用。
- 每个测试 job 使用 `scripts/ci/check_test_results.py` 校验 JUnit/Playwright/pytest 报告：报告必须存在、包含真实 case、无 failure/error/skip。只会出报告而不检查的环境问题按失败处理。
- ESLint 存量违规由 `ai-generate-code-frontend/eslint-suppressions.json` 精确计数抑制；新增违规不自动进入基线，修完后用 `--prune-suppressions` 收缩。
- 已修复的重要缺陷必须保留或新增可复现的防复发测试；当前映射见 `docs/ci/regression-inventory.md`。修复先红后绿，不复用会被删掉的临时验证。
- Codex Review 走 ChatGPT 的 GitHub 集成，消耗套餐额度而不是 API key；仓库不保存模型密钥。只报告高置信度、可行动的 correctness / security / authorization / data loss / concurrency / migration / meaningful regression 缺口；不重复 lint、格式和类型检查，不运行构建。审查结果不是合并条件。
- Codex 的开关与触发在 `chatgpt.com/settings/code-review` 和 PR 评论 `@codex review` 中管理，不通过仓库 workflow 调用。任何需要 `OPENAI_API_KEY` 的 Codex 工作流都不要加回。
- 失败排查先看对应 job 的准确命令、日志和上传的测试报告；不要根据本地通过推断 CI 通过。CI 状态只看真实 run。
- `master` 与 `code_board` 的分支保护要求来自 PR、线性历史可选，唯一必需状态是 `merge-gate`，禁止直接 push/force push/删除分支。权限不足时在 GitHub Branch protection 或 Rulesets 中按此配置。
