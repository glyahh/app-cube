# AI software synthesis and verifiable artifact delivery

面向智能软件合成的证据驱动型可验证制品交付平台。项目将模型输出接入有状态工作流、分层记忆、混合检索、构建验证和部署制品链路，目标不是单纯“生成一段代码”，而是让生成结果能够被持续修改、验证、追踪和交付。

> 当前仓库是个人研究与求职项目。文档中的“已实现”表示代码已存在；“代码级验证”表示已通过源码、提交记录或局部运行路径确认；吞吐量、P95/P99 和生产可用性仍需单独压测，不在这里虚构。

## Why this project exists

通用 AI Coding 工具解决了“如何生成代码”，但真实应用还会遇到另一组问题：长对话如何保持上下文、用户偏好如何贯穿多个 AI Service、工具调用如何稳定地流式展示、生成代码如何构建验证，以及结果如何形成可访问的发布制品。

这个项目把这些问题放在同一条交付链路中研究：

```text
用户需求
  -> 有状态工作流与模型路由
  -> 工具调用 / SSE 流式输出
  -> 解析、保存和制品组织
  -> Memory / RAG 补充上下文
  -> Vue SFC 检查与构建验证
  -> deployKey 对应的可访问制品
```

## What is implemented

| 能力 | 项目中的实现 | 证据状态 |
| --- | --- | --- |
| SSE 代码生成 | Reactor 流式输出，支持传统链路与 LangGraph4j 工作流 | 已实现，待系统压测 |
| 多模型与工具调用 | LangChain4j、OpenAI-Compatible 接入、文件工具、Vue SFC 检查 | 已实现，待补统一指标 |
| 制品交付 | Parser/Saver 管线、`deployKey`、静态资源访问与撤回 | 已实现，待部署演练 |
| 分层会话记忆 | MySQL 历史、Redis 模型窗口、摘要/快照和 Memory Version | 已实现，需继续验证 TTL 边界 |
| 个性化一致性 | MySQL 配置、Redis 回填、`AiService` 重建与 Memory 同步 | 已实现，见工程案例 02 |
| 代码制品检索 | ES、KNN + BM25、切块、Hash 去重和增量索引 | 已实现，待检索质量评测 |
| 前端可视化编辑 | iframe 内注入路径 + 父页面 overlay 兜底 | 已实现，见工程案例 01 |
| 构建验证 | Vue SFC 预解析、修复、二次检查和 `npm run build` | 已实现，待补失败样本集 |

## Engineering cases

每个案例都按“现象 → 根因 → 选择 → 验证 → 遗留风险”记录，避免只留下技术名词而忘记真实决策。

- [工程记忆系统说明](docs/engineering-journal/README.md)
- [案例面试卡](docs/engineering-journal/interview-cards.md)
- [Linux 真实项目实验路线](docs/engineering-journal/linux-lab.md)
- [01：iframe 可视化编辑兜底](docs/engineering-journal/cases/01-iframe-visual-editor-fallback.md)
- [02：个性化配置与 Memory 一致性](docs/engineering-journal/cases/02-personalization-memory-consistency.md)
- [03：LangChain4j 工具流扩展](docs/engineering-journal/cases/03-langchain4j-tool-stream-extension.md)
- [04：流式工具状态机](docs/engineering-journal/cases/04-streaming-tool-state-machine.md)
- [05：分层 Memory 生命周期](docs/engineering-journal/cases/05-layered-memory-lifecycle.md)
- [06：代码制品 RAG](docs/engineering-journal/cases/06-rag-artifact-retrieval.md)
- [07：Vue 构建验证](docs/engineering-journal/cases/07-vue-build-validation.md)

已确认的代码证据包括提交 `5b7a5f8`、`58b827f`、`e4c9f1b`、`2700f85`、`02d1053`、`96ed098`、`615eecc` 和 `ca73b4f`。这些短 SHA 只用于定位历史证据，不代表项目已经完成生产部署。

## Technical architecture

| 层次 | 主要技术 | 关注点 |
| --- | --- | --- |
| API 与工作流 | Spring Boot、Reactor、LangGraph4j | SSE、工作流状态、错误边界 |
| AI 编排 | LangChain4j、OpenAI-Compatible API | 模型路由、Tools、结构化输出 |
| 记忆与检索 | MySQL、Redis、Caffeine、Elasticsearch | 分层存储、版本、TTL、混合检索 |
| 制品处理 | Parser、Saver、Vue SFC、部署控制器 | 可构建、可访问、可撤回 |
| 前端交互 | Vue 3、iframe、postMessage | 编辑、hover、坐标转换、兼容性 |
| 运维基础 | Docker、Linux、Nginx（学习实验路线） | 进程、端口、日志、资源和恢复 |

## Getting started

服务端需要 Java 21、MySQL、Redis 和可用的模型端点。配置文件位于 `src/main/resources/application.yml`，请使用本地环境变量或未提交的配置文件，不要把真实密钥写入 Git。

```bash
./mvnw spring-boot:run
```

默认端口为 `8124`，上下文路径为 `/api`。前端控制台位于 `ai-generate-code-frontend/`，具体启动命令以其中的 `package.json` 为准。

可重点观察的接口：

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/api/chat/gen/code` | 传统 SSE 代码生成 |
| GET | `/api/chat/gen/workflow` | LangGraph4j 工作流生成 |
| POST | `/api/app/add` | 创建应用 |
| POST | `/api/app/deploy` | 发布制品 |
| GET | `/api/static/{deployKey}/**` | 访问已发布资源 |

## Known boundaries

- 尚未提供可信的并发吞吐、P95/P99、成本和错误率基线。
- iframe 兜底依赖同源预览和父子页面坐标换算；跨域场景需要单独设计通信协议。
- Caffeine `AiService` 缓存与 Redis TTL、Memory Version 和用户个性化配置存在生命周期耦合，仍需继续压测和故障演练。
- RAG 已有混合检索实现，但检索质量、过期内容识别和答案引用评测尚未形成完整数据集。
- 文档中的 Linux、Docker 和 Nginx 路线是本地实验目标，不等同于线上运维经验。

## Next milestones

1. 为 Memory 一致性和 SSE 工具状态机补最小可重复测试。
2. 用本地 Docker 实验完成 Linux 进程、日志、Redis 和 MySQL 故障排查记录。
3. 增加一套不依赖真实模型的构建验证和流式事件回放样本。
4. 再考虑 AI Gateway：统一模型接口、限流、成本统计、Prompt 版本、审计和降级。

## License

本项目按 [MIT License](LICENSE) 发布。
