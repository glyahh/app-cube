# PR 自动化验收

## 流程

```text
Codex 开发 -> 创建 PR -> PR Checks + Codex Review
          -> 直接修改并 push -> 新 run 自动重跑 -> merge-gate 全绿 -> 合并
```

智能审查和确定性检查分开：Codex Review 找高价值设计/安全/并发问题；`merge-gate` 只根据 JUnit、pytest、Vitest、Playwright、Lint、类型检查和构建结果判定。

## 必需检查

| Job           | 触发路径                              | 内容                                                                                                             |
| ------------- | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| `backend`     | `pom.xml`、`mvnw*`、`src/**`、CI 脚本 | Java 编译、单测、安全/回归测试、打包                                                                             |
| `agent`       | `agent/**`、CI 脚本                   | 离线 pytest，网络 namespace 禁外网                                                                               |
| `frontend`    | `ai-generate-code-frontend/**`        | `vue-tsc`、Oxlint、ESLint（只读）、Prettier check、Vitest、首屏预算、生产构建                                    |
| `integration` | 后端路径                              | Testcontainers PostgreSQL + Redis，事务、权限和缓存                                                              |
| `e2e`         | 后端、Agent 或前端路径                | 真实 Spring Boot + PostgreSQL + Redis + Playwright：登录、建应用、离线模型替身生成、编辑文件、版本冲突和越权读取 |
| `merge-gate`  | 总是                                  | 核对路径命中的 job 全部 `success`；失败、取消或缺失都阻止合并                                                    |

`merge-gate` 是唯一固定必需状态名。路径未命中的 job 为 `skipped`；测试报告里的 skip 由结果检查器直接判失败。

ESLint 用仓库内 `eslint-suppressions.json` 固化已有 104 个存量违规（主要是旧组件的 `any` 与未使用变量）。这个文件只记录基线数量，新增违规仍会失败；清理某个文件的违规后运行 `--prune-suppressions` 缩减基线，不要关闭规则。

## 零真实 AI 费用

- PR 工作流没有模型密钥，`AGENT_MODEL_BASE_URL` 指向本地无效地址。
- Python 默认测试在 `unshare --net` 的隔离网络中运行；需要 HTTP 的用例只访问本地 mock server。
- `agent/tests/conftest.py` 将 `AGENT_ENV_FILE` 指向不存在文件，并清除数据库与模型环境变量；不要移除这个防护来“修测试”。
- Java 的 Agent 测试使用 `src/test/resources/agent-stub.py`；E2E 的生成与编辑均由该 stdio stub 提供，不调用任何模型。
- 真实模型验收只有 `.github/workflows/real-ai.yml`，必须手动输入 `RUN_REAL_AI`，在受保护的 `real-ai` environment 中运行 `agent/live_tests`。

## 确定性证据

每个测试 job 都运行：

```bash
python3 scripts/ci/check_test_results.py <报告>
```

检查器要求报告存在、至少有真实 case，且 failure/error/skipped 均为 0。测试报告和失败时 Playwright trace/screenshot 上传为 artifact。

## 本地命令

```bash
./mvnw.cmd test
cd agent && uv sync --frozen && .venv/Scripts/python.exe -I -m pytest tests -q -m "not external"
cd ai-generate-code-frontend && npm ci && npm run test:unit && npm run type-check && npm run build
```

E2E 需要后端 jar、隔离 PostgreSQL/Redis 和 Chromium：

```bash
./mvnw.cmd -DskipTests package
cd ai-generate-code-frontend && npx playwright install chromium && E2E_DB_URL='jdbc:postgresql://127.0.0.1:5432/app_cube_e2e?currentSchema=biz' E2E_DB_USER=... E2E_DB_PASSWORD=... E2E_REDIS_HOST=127.0.0.1 E2E_REDIS_PORT=6379 npm run test:e2e
```

E2E 会创建测试账户和数据库记录；只能使用一次性的测试数据库，禁止指向本地业务库。

## 分支保护

GitHub 设置的目标：

- `master` 和 `code_board` 禁止直接 push、force push、删除。
- PR 必需状态检查：`merge-gate`。
- 合并前要求分支与基线同步，使用 squash 或 merge 均可。
- 可按团队需要再开人工 approve；Codex Review 不是 approve。

如果 CLI 权限不足，需要在 Settings -> Branches（或 Rulesets）手动设置同名必需检查。

## Codex

- Review 走 ChatGPT 侧的 GitHub 集成，消耗 ChatGPT 套餐额度，不使用 OpenAI API key；仓库不配置 `OPENAI_API_KEY`，也没有 `CODEX_REVIEW_ENABLED` 开关。
- 启用：把仓库连接到 Codex，在 `chatgpt.com/settings/code-review` 为该仓库打开 Automatic review。单次审查直接在 PR 评论 `@codex review`。
- 审查规则写在根 `AGENTS.md` 的 `## Code Review Rules`，Codex 读取离改动最近的一份。
- 只报置信度 80 以上、会阻止合并的问题，分 Important / Nit / Pre-existing；不投 APPROVE 或 REQUEST CHANGES，结果是普通 PR 评论，不参与合并判定。
- `Real AI Tests`：仅在 `real-ai` environment 手动确认后运行。
