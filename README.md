# 应用魔方

Agent 驱动的应用开发工作台。

[GitHub](https://github.com/glyahh/app-cube) · [命名与改名说明](docs/rules/branding.md) · [文档分类索引](docs/README.md)

围绕代码生成与调试，帮助开发者把 Demo 逐步改造成满足实际业务与部署要求的可交付项目。
保留新项目生成能力；已有项目导入、受限安装运行与模型编辑发布闭环已完成本期交付。支持范围见[第一项最终范围](docs/status/import-scope-20261009.md)。

> 本文按当前源码整理。能力状态、差距及第一阶段验收见 [当前项目状态与开发主线](docs/status/current.md)。本次文档同步未运行服务或测试，不代表新的运行验收。

## 当前架构

```text
Vue 3 对话与项目工作台
  WebSocket /api/ws/agent（优先）
  SSE /api/chat/generate/stream（降级）
Spring Boot：鉴权、限流、应用级串行、运行台账、业务持久化
  常驻 Python 子进程，stdin/stdout NDJSON
DeepAgents / LangGraph：模型、工具、上下文压缩、任务权限
  Docker 或显式配置的 local_shell Backend
项目工作区、流式事件、对话历史
```

- **业务与会话**：主配置使用 PostgreSQL，业务表位于 `biz` schema；Redis 承载 Spring Session、缓存与限流相关能力。
- **Agent 持久化**：LangGraph checkpointer / store 使用 `AGENT_POSTGRES_DSN`；缺失或初始化失败时可能降级为内存，需检查实际日志，不能默认跨进程保留状态。
- **项目工作区**：新应用统一为 Auto，产物位于 `temp/code_output/project_{appId}/`；旧 HTML、多文件、Vue 目录兼容保留。
- **生成与运行分离**：Python Backend 执行生成工具；Java 运行管理使用独立运行镜像，管理应用进程、日志、就绪状态和预览。
- **静态发布**：沿用 `deployKey`，制品同步至 `temp/code_deploy/{deployKey}/`；不承诺常驻后端或全栈服务发布。

接口协议见 [Java ↔ Python 契约](docs/contracts/agent.md)。旧 Java/LangChain4j 文档只作历史参考，不作为当前生成主链路说明。

## 当前源码已有能力

| 能力 | 范围与边界 |
|---|---|
| 新项目生成与增量修改 | Auto 创建；已有产物进入 edit 模式，文件工具读写工作区 |
| 流式对话 | WebSocket 优先、SSE 降级；取消、事件回显与历史持久化 |
| 项目工作台 | 工作区描述、文件树、文件读取与编辑、保存版本校验、下载 |
| 运行管理 | 运行规格识别、Docker 进程管理、就绪探测、日志、指标、异常记录 |
| 修复入口 | 用户确认后将诊断信息送入原生成对话；尚不是验证驱动的完整修复闭环 |
| 辅助理解 | 独立知识快照、异步分析、只读问答；图谱区分源码证据与模型推断标签 |
| 静态交付 | 静态预览、发布、撤回；需要常驻服务的项目不支持长期发布 |
| 权限与运营 | 用户/管理员权限、应用归属校验、申请审核、Loop 技能管理 |

“源码已有”不等于本版本已通过完整用户流程验收。图谱中的证据标签由模型输出，不等于确定性源码关系提取。

## 技术栈

版本以 `pom.xml`、`agent/pyproject.toml`、锁文件和前端 `package.json` 为准。

| 层 | 当前选型 |
|---|---|
| 控制面 | Java 21、Spring Boot 3.5.10、Web/WebSocket/AOP、Reactor |
| 数据与会话 | PostgreSQL、Flyway、MyBatis-Flex 1.11.1、Redis、Caffeine、Redisson |
| Agent | Python 3.12–3.14、DeepAgents 0.7.13、LangGraph 1.2.11、LangChain 1.4.0、OpenAI-compatible 模型接口 |
| 前端 | Vue 3、Vite 7、TypeScript 5.9、Ant Design Vue 4、Pinia、Vue Router 5 |
| 执行 | Docker；运行镜像提供 Node.js、Python、Java/Maven、SQLite |
| 验证 | JUnit 5、pytest、Vitest、Playwright |

`pom.xml` 仍保留 MySQL 驱动，不表示当前主配置使用 MySQL，也不表示数据迁移可通过改驱动自动完成。

## 本地准备与启动

以下是操作说明，不是本次已执行的命令。

1. 准备 Java 21、Python、Node.js，以及 PostgreSQL、Redis。配置以 `.env.example` 为模板，已有 `.env` 不要覆盖。
2. 在 `.env` 配置模型与 PostgreSQL 连接；Java 主配置和 Python 共享根目录 `.env`，`application-local-a/b.yml` 仍可覆盖 Java 设置。凭据不要提交。
3. 安装 Agent 与前端依赖：

   ```powershell
   Push-Location agent
   uv sync
   Pop-Location
   Push-Location ai-generate-code-frontend
   npm ci
   Pop-Location
   ```

4. 使用 Docker 执行时，确认 Docker Engine 可用、Backend 设置为 `docker`，并准备运行镜像：

   ```powershell
   powershell -ExecutionPolicy Bypass -File '.\deploy\sandbox\build.ps1'
   ```

   `local_shell` 会在宿主机执行，不作为导入不可信项目的默认执行方式。容器直接读写挂载工作区，不等于独立修复副本；当前 bridge 网络也不阻断出网。

5. 启动后端和前端（分别在仓库根目录与前端目录执行）：

   ```powershell
   # 后端：8124，context-path /api
   .\mvnw.cmd spring-boot:run

   # 前端
   Push-Location ai-generate-code-frontend
   npm run dev
   Pop-Location
   ```

`scripts/start-backend.ps1` 会预检 Agent 环境和沙箱；Flyway 迁移随后端启动执行。不要为了修文档启动服务或执行迁移。

开发时 Vite 将 `/api`（含 WebSocket）和 `/static` 代理到后端。静态预览与运行容器预览的隔离方式不同，不应一概视为同源。

## 常用验证命令

```powershell
# Java 编译与定向测试
.\mvnw.cmd -q '-DskipTests' compile
.\mvnw.cmd test '-Dtest=WorkspacePathResolverTest'

# Python（仓库根目录）
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q

# 前端
Push-Location ai-generate-code-frontend
npm run test:unit
npm run build
Pop-Location
```

测试的外部依赖以各测试类为准；真实模型、Docker、数据库和浏览器验证需要相应环境。不要把历史测试数字当作当前提交的结果。
`npm run lint` 含自动修复，`npm run format` 会写文件，不适合只读检查。

## 核心接口

| 方法 | 路径 | 用途 |
|---|---|---|
| WebSocket | `/api/ws/agent` | 生成事件与取消 |
| GET | `/api/chat/generate/stream` | SSE 降级生成 |
| POST | `/api/app/add` | 创建 Auto 应用 |
| GET | `/api/app/{appId}/workspace` | 工作区描述 |
| GET / POST | `/api/app/{appId}/files/content` | 读取 / 保存文件 |
| POST | `/api/app/{appId}/runtime/start` | 启动运行容器 |
| GET | `/api/app/{appId}/runtime/status` | 运行状态 |
| POST | `/api/app/{appId}/runtime/incidents/{incidentId}/repair` | 准备修复提示词 |
| POST | `/api/app/{appId}/knowledge/tasks` | 创建知识分析任务 |
| POST | `/api/app/{appId}/knowledge/qa` | 只读问答 |
| GET | `/api/chatHistory/app/{appId}` | 对话历史 |
| POST | `/api/app/deploy`、`/api/app/undeploy` | 静态发布 / 撤回 |
| GET | `/api/deploy/{deployKey}/**` | 已发布资源 |

详细参数以控制器与运行时 OpenAPI 为准。工作台说明见 [Auto 工作台实施记录](docs/guides/workbench.md)、[2026-09-19 对齐记录](docs/reports/workbench/alignment-20260919.md)；其中验证记录仅对应当时的版本与环境。

## 后续主线

先跑通一条窄链路：导入一个 Demo、建立运行基线、复现真实问题、最小修复、回归验证、交付可回放证据。
之后再按实际业务补权限、校验、异常处理、数据一致性、幂等、资源管理、配置和可观测性。
当前新增开发是导入 Agent 会话授权模式（手动批准 / 自动批准 / 自由发挥）与候选整批审阅；主体已在工作区实现，尚未验收，状态见[当前项目状态与开发主线](docs/status/current.md)顶部。
并发或性能测试按风险选择；图谱、文档与问答服务于代码理解和调试，海报等扩展场景暂缓。

## 许可证

本项目以 [MIT 许可证](LICENSE) 发布。再分发时保留版权声明与许可证全文。
