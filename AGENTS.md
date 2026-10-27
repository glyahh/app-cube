# AGENTS.md — glyahh-ai-generate-code

## 一句话

“应用魔方”：围绕代码生成与调试，帮助开发者把 Demo 逐步改造成满足业务与部署要求的可交付项目；保留新项目生成，已有项目导入与验证闭环已按用户批准的最终范围完成。

第一项**已有项目导入**（`projectimport/`）状态 **COMPLETE**；最终范围为已验收核心能力＋5组专项，未交付扩展已按用户决定移出要求，不自动恢复旧待办。权威范围见 `docs/status/import-scope-20261009.md`，当前状态只看 `docs/status/current.md` 顶部。下方历史数字和旧状态不能覆盖当前决定；保留所有安全与恢复约束。

第一项完成后的新增开发是**导入 Agent 会话授权模式**（MANUAL / GUARDED_AUTO / AUTO，含单次工具许可与候选整批审阅）。后端主体已在工作区实现但未提交、未验收；整体验收状态与数据库现况以 `docs/status/current.md` 顶部为准，不沿用历史 V40 未应用的环境结论。不要把它当作已交付能力。

仅在用户明确要求时推进开发。生成完成、编译通过、接口成功、容器启动、业务验收、发布是六件独立的事，不互相代替。

## 技术栈

| 层      | 选型                                                                      |
| ------- | ------------------------------------------------------------------------- |
| 语言    | Java 21                                                                   |
| 框架    | Spring Boot 3.5.10, Spring Web, Spring AOP                                |
| ORM     | MyBatis-Flex 1.11.1（含 codegen）                                         |
| DB/缓存 | PostgreSQL + Flyway + Redis (Lettuce) + Caffeine                          |
| AI      | Python 3.12–3.14, DeepAgents 0.7.13, LangGraph 1.2.11, LangChain 1.4.0    |
| 执行    | Python 常驻子进程 NDJSON；Docker 生成沙箱与 Java 管理的运行容器           |
| 前端    | Vue 3 + Vite 7 + TypeScript 5.9 + Ant Design Vue 4 + Pinia + Vue Router 5 |

## 关键命令

```bash
# 后端启动（端口 8124，context-path /api）
./mvnw.cmd spring-boot:run

# 编译（跳过测试）
./mvnw.cmd -q -DskipTests compile

# 运行测试（单个）
./mvnw.cmd test -Dtest=WorkspacePathResolverTest

# Python Agent（仓库根目录）
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q

# 前端
cd ai-generate-code-frontend
npm run dev           # 开发（proxy /api -> localhost:8124）
npm run build         # 构建（type-check + vite build）
npm run type-check    # 只做 vue-tsc 类型检查
npm run test:unit     # Vitest
npm run test:perf     # Playwright（需真实环境）
npm run lint          # oxlint + eslint，会自动修改文件
npm run format        # prettier，会修改文件
```

环境与服务的 PowerShell 脚本（全部在仓库根目录执行）：

```powershell
.\scripts\start-backend.ps1     # 启动前预检 Agent 环境与沙箱
.\scripts\restart-backend.ps1   # 停掉 8124 后重启，并等待端口监听
.\scripts\start-all.ps1 / .\scripts\stop-all.ps1 / .\scripts\restart-all.ps1
.\scripts\start-postgresql.ps1 / .\scripts\start-redis.ps1
```

## 包结构

```
src/main/java/com/dbts/glyahhaigeneratecode/
├── projectimport/        # 导入主线与新增权限闭环（来源、凭据、启动、卷、编辑账本、工具许可、候选审阅）
├── agent/                # Python Worker 池、请求、事件、运行持久化与串行协调
├── controller/           # REST 控制器
├── service/ + service/support/   # 业务逻辑；路径校验走 WorkspacePathResolver，不要手拼路径
├── runtime/              # Docker 应用运行、日志、指标、异常与版本指纹
├── knowledge/            # 独立快照、异步分析与只读问答
├── core/                 # 制品合成管线（门面 + 执行器 + 解析器/模板，历史产物）
├── ai/                   # AI 编排
├── config/ model/ mapper/ utils/ common/ constant/ exception/
├── rateLimiter/ annotation/ + AOP/ manage/ monitor/
└── src/main/resources/db/migration/   # Flyway（版本以迁移目录及真实数据库为准）

agent/                    # Python 执行面（不是 Java 包）
├── app.py                # stdio 主循环与 stdout 协议保护
├── native_import_worker.py # 导入编辑专用入口（持久 PG、import_only）
├── server.py             # DeepAgents 装配
├── core/                 # 生命周期、模型、上下文压缩、持久化、事件、settings
│                         # 另有 import_permissions / permission_review / model_profiles
├── core/middleware/      # compaction / run_limits / tool_error
├── backends/             # docker_sandbox / local_shell / read_only / sensitive_paths
│                         # 另有 secret_free_docker / secret_free_view / import_action
└── retrieval/            # 检索记录、上下文投影、证据与 Git 版本
```

## 架构要点

- **启动前**：准备 PostgreSQL（主配置数据库 `gly_ai_generate_code`、业务 schema `biz`）、Redis（localhost:6379）、Agent 虚拟环境、模型配置和所需 Docker 环境。只读或文档任务不启动服务，也不执行迁移。
- **依赖安装**：Python 侧 `cd agent && uv sync`；Docker 运行镜像 `powershell -ExecutionPolicy Bypass -File '.\deploy\sandbox\build.ps1'`；前端 `npm ci`。
- **配置**：Java 与 Python 共用根目录 `.env`，参考 `.env.example`；已有 `.env` 不要覆盖。Java 的本地 profile 可覆盖主配置，当前 `spring.profiles.active` 是 `local-b`。
- **凭据边界**：密钥只保留在本地 `.env`，不进入仓库、日志或对话内容；`.env.example` 保持占位符。
- **生成传输**：WebSocket `/api/ws/agent` 优先，`GET /api/chat/generate/stream` 降级；Java/Python 契约见 `docs/contracts/agent.md`。ID 跨语言保持 string。
- **持久化**：Java 业务与对话落 PostgreSQL，Redis 承载会话与缓存。Python checkpointer/store 读 `AGENT_POSTGRES_DSN`，配置缺失或初始化失败可能降级内存，必须看实际日志。
- **工作区**：新 Auto 项目为 `temp/code_output/project_{appId}/`，旧类型目录兼容保留；静态发布至 `temp/code_deploy/{deployKey}/`。路径复用 `WorkspacePathResolver`。
- **副本与权限**：当前容器直接读写挂载工作区，不等于独立调试副本。导入项目先复制、确认权限与资源范围，再执行脚本；不在原项目直接运行未知代码。
- **运行与修复**：运行规格由 `.glyahh/run.json` 或实际文件推断并校验。已有日志与修复入口不等于回归闭环，不按模型自评判定成功。
- **版本与知识**：运行版本指纹不是内容快照；知识基于独立副本，事实/推断标签由模型输出，不能替代确定性关系提取或修复证据。
- **Profile**：通过 `spring.profiles.active` 切 local-a/b，不按固定行号定位。
- **devtools**：仍未启用；旧 LangChain4j 补丁已移除，当前需保护 Agent 子进程池的生命周期。

## 后端编码规范

继承 `.cursor/skills/backend-design/SKILL.md`：

- **分层**：`controller → service 接口 → serviceImpl → mapper/entity/DTO/VO`；复杂流程用 `core/` 的门面 + 执行器模式。
- **统一响应**：所有控制器返回 `BaseResponse<T>`，用 `ResultUtils.success` / `ResultUtils.error` 构建，不在 controller 里写提前 return。
- **校验**：统一 `ThrowUtils.throwIf(condition, ErrorCode.XXX, "msg")`，不随意写 try-catch，`GlobalExceptionHandler` 兜底。
- **注入**：构造器注入 + `@RequiredArgsConstructor` + `@Slf4j`，不用 `@Autowired` 字段注入。
- **工具库**：统一 Hutool（`StrUtil`、`BeanUtil`）。
- **DTO/VO**：请求用 DTO，返回用 VO，不把 Entity 暴露给前端。
- **最小改动**：优先提取新私有方法并让旧方法委托，保留旧实现以备回滚，不推倒重写大段结构。

## 代码精简与可维护性

目标：以必要复杂度实现正确、清晰、易维护的代码，不以行数最少衡量质量。

- 范围：只做当前任务必要的修改。清理与重构保持既有行为、对外接口及数据契约不变；功能变更须来自明确需求。
- 复用：先阅读项目规范和相关实现，搜索并复用已有能力。新增抽象须解决当前重复、职责或可读性问题，不为假想需求增加配置、扩展层和兼容机制。
- 简化：减少重复逻辑、无效中间层、过深嵌套和显而易见的注释；保留有用抽象。避免密集单行、职责混杂和无收益的批量改名、格式化。
- 删除：删除代码、文件、配置或依赖必须有依据。按实际语言和框架核对相关调用、动态注册、配置及外部入口；搜索不到引用不等于无用。证据不足则保留。
- 错误处理：保留真实边界、业务约束、安全校验及应急保护；不重复校验已有保证，不添加不可能场景的兜底，不吞异常或伪造成功。
- 验证：优先运行能覆盖改动的最小现有测试、类型检查或构建。仅因明确影响风险或项目门禁扩大验证；不为低风险改动堆检查脚本，不通过删除有效测试、弱化断言或硬编码掩盖失败。
- 收尾：删除本次任务产生且无持续价值的临时文件；保留有效回归测试和必要文档。区分既有失败与新增回归，不顺手修复无关问题。
- 工作区：尊重用户现有修改，不覆盖或回滚。完成目标并获得充分验证后停止，不继续无收益优化。

### 项目执行约束

- 修复前先定位根因和影响范围，遵循现有风格；最小改动不等于用补丁掩盖根因。无关问题只提一句。
- 新增防御代码必须对应明确契约、真实输入边界或可达失败路径；保留必要的权限、数据完整性校验及降级开关等保护逻辑，不为假想需求添加依赖、重试或兜底。
- 收尾前简化本次改动，不写嵌套三元。清理或重构单独成任务，每次一个主题、小步提交、行为不变，每步只跑最小验证。
- 涉及共享逻辑、公共接口、依赖、数据库、安全或影响范围不明时，按实际风险扩大验证，必要时全量；不绕过项目明确要求的 CI 检查。
- 新增测试验证真实行为或回归，不照抄实现、不为简单常量制造测试。同一代码状态下，检查已通过且没有新证据，不重复运行，也不逐级扩大到全量。
- 当前需求完成、相关检查通过、变更已审查，即结束。只报告：改了什么、验证了什么、哪些尚未验证。

## 改动敏感区前先读的文档

| 场景                                | 文档                                                                                 |
| ----------------------------------- | ------------------------------------------------------------------------------------ |
| 当前能力边界与开发主线              | `docs/status/current.md`（只看顶部）                                                 |
| 项目导入领域的术语与严格区分        | `CONTEXT.md`（`已有项目导入`、`导入凭据租约`、`确定关系` vs `推断关系`、`验证证据`） |
| Java ↔ Python 协议                  | `docs/contracts/agent.md`（含导入工具许可与候选审阅事件）                            |
| 导入 Agent 权限、工具许可、候选审阅 | `docs/plans/import-agent-permission-modes-plan.md`、`CONTEXT.md`                     |
| 设计、验收矩阵、逐节点证据          | `docs/plans/`（方案）、`docs/tests/`（测试）、`docs/reports/`（证据）                |
| 界面文案                            | `docs/rules/ui-copy.md`                                                              |
| 架构决策                            | `docs/decisions/`                                                                    |

## 前端注意

- 页面文案采用 `ux-writing` 原则，遵守 `docs/rules/ui-copy.md`；新增或修改界面时同样执行，不只用于本次预览。

- Vite proxy 将 `/api`（含 WebSocket 升级）和 `/static` 转发到 `localhost:8124`。静态预览与运行容器预览的隔离方式不同，不一概认作同源。
- 类型定义由 `openapi-ts-request` 从后端接口生成：`npm run openapi2ts`。

## 测试

- Java：JUnit 5 / Spring Boot Test，集中在 `src/test/java/com/dbts/glyahhaigeneratecode/`；外部依赖以各测试类为准。
- Python：`agent/tests/`；前端：Vitest 与 Playwright，命令以 `package.json` 为准。
- 局部真实验收脚本在 `scripts/checks/verify-*.py`（项目导入主线），多数需要后端、数据库或 Docker 已就绪；先看具体脚本头部说明。
- 真实模型、Docker、PostgreSQL、Redis 与浏览器验收需对应环境。记录实际命令、退出码、结果和代码版本，不沿用历史通过数字。

## Debug 完成规则

- 每次 debug 修复完成并通过必要测试后，必须执行 `powershell -ExecutionPolicy Bypass -File ".\scripts\restart-backend.ps1"`，重启后端并确认端口 `8124` 已正常监听。

## 项目注释规范（优先遵循）

- 注释的首要目标是帮助读者快速看懂代码，不为了显得专业而堆砌术语。
- 注释应简洁、自然、逻辑清楚，优先使用直接易懂的中文。
- 方法内的主要执行流程使用 `// 1. ...`、`// 2. ...` 依次编号；每个方法单独从 `1` 开始。
- `if` 分支、异常处理、中断处理、参数校验等局部说明不编号。
- 注释说明代码的目的、关键流程或不明显的原因，不逐行复述代码本身。
- 能通过清晰命名直接理解的代码不强行添加注释；删除过时、重复和无实际帮助的注释。
- JavaDoc 保持简短，重点说明方法职责、重要输入输出和必要的降级行为。
- 不给没改过的代码补注释或类型。
- 修改项目任何源码时，新增或调整的注释都优先遵循以上规则，除非相关模块已有更具体的约定。

## 交流与思考语言规范

- 全程使用中文与用户进行沟通、交互、解释与方案汇报。
- 内部思考过程（thinking / thought）必须全程使用中文进行分析、推理与构思，不得使用英文。

## .gitignore 中不被跟踪的本地配置

- `application-local.yml` / `application-local-a.yml` / `application-local-b.yml`
- `AGENTS.md`, `CLAUDE.md`, `.claude/`, `.agents/`, `.cursor/commands/`, `.cursor/plans/`, `.cursor/rules/`
- `temp/`, `bugs/`, `.worktrees/`

## 失败处理与降级原则（全项目）

- 项目不以降级作为默认容错策略。先解决真实问题，不通过省略步骤、缩减能力、降低校验标准或静默替换结果来掩盖失败。
- 发生错误必须明确提示用户，保留脱敏错误、失败阶段与验证依据；不能只写日志、不通知用户，或把部分完成显示为全部成功。
- Agent 优先依据真实错误定位原因，并遵循具体功能的重试规则；重试不得绕过权限、取消、版本隔离或资源保护，不无限重试，也不盲目重复可能产生副作用的操作。
- 只有正常路径确实无法恢复、其他合理恢复方式已无效或不可行，并有具体依据时，才考虑不得已的降级。先说明必要性、能力损失和风险，由用户决定，不擅自启用。
- 降级结果、过期结果、部分结果或未验证结果，必须如实标记，不能冒充原目标已经达成。
- 图谱 Agent 补全专项：失败时先提示用户，再让 Agent 额外重试一次。重试仍失败时保持失败状态，不自动只发布静态图作为新版；保留已有合法旧图并标明是否过期，无旧图时显示失败。重试与发布仍须校验当前项目、快照、任务资格和上下文代次。
- 本节是后续设计与开发的约束，不代表现有代码中的回退路径已经移除。修改相关模块前核对实际行为，逐项明确保留、修正或移除，不在文档任务中直接改源码。

## PR 验证与审查

- 合并门禁是固定状态检查 `merge-gate`。它只汇总确定性结果，不用 AI Review 代替测试。
- `.github/workflows/pr-checks.yml` 按改动路径运行：Java 编译/单测/安全回归/Testcontainers/构建，Python 离线 pytest，前端类型/Lint/格式/Vitest/构建/Playwright。工作流、公共脚本和 `.github/**` 改动触发全部检查；每周一完整运行。
- 路径未命中的 job 是 `skipped`，不是测试跳过。`merge-gate` 要求被选中的 job 必须 `success`；失败、取消或缺失都阻止合并。
- 必需测试禁止真实模型调用。CI 不配置模型密钥，Python 在无外网 namespace 运行，模型交互只用本地 Mock Server 或 stdio stub。禁止用空测试、无条件跳过、吞异常或伪造报告让检查变绿。
- `external` 标签用于真实数据库、Docker 或外部服务的旧验收，不进入合并门禁；未运行不等于通过。真实模型测试只在 `agent/live_tests/`，仅由 `Real AI Tests` 手动工作流触发并产生费用。
- 每个测试 job 使用 `scripts/ci/check_test_results.py` 检查报告：必须有真实 case，failure/error/skipped 必须为 0。报告缺失按失败处理。
- 前端 ESLint 存量违规记录在 `ai-generate-code-frontend/eslint-suppressions.json`，按规则计数；新增违规仍失败。修复后运行 `npx eslint --prune-suppressions` 收缩基线，不关闭规则。
- 已修复缺陷保留可复现的防复发测试；映射见 `docs/ci/regression-inventory.md`。先红后绿，不复制实现、不制造常量测试。
- Codex Review 走 ChatGPT 的 GitHub 集成，消耗套餐额度而不是 API key；仓库不保存模型密钥。只报高置信度 correctness/security/authorization/data loss/concurrency/migration/meaningful regression; 不重复 lint、格式和类型检查，不运行构建。审查不是合并条件。
- Codex 的开关与触发在 `chatgpt.com/settings/code-review` 和 PR 评论 `@codex review` 中管理，不通过仓库 workflow 调用。任何需要 `OPENAI_API_KEY` 的 Codex 工作流都不要加回。
- 分支保护要求：从 PR 合并、必需检查 `merge-gate`、禁止直接 push/force push/删除 `master` 与 `code_board`。权限不足时在 GitHub Branch protection 或 Rulesets 手动配置。

## Code Review Rules

### 只报会阻止合并的缺陷

只报告 correctness、security/authorization、data loss、concurrency、事务、迁移，以及真实行为缺少回归覆盖。lint、格式、命名、类型检查由 CI 负责，不要重复。

### 给可执行的证据

每条问题包含 `path:line`、具体失败场景和最小修复。没有这类问题就明确说没有，不要凑数，也不要写总结或赞美。
