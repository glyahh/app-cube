# Java 全栈面试问答 — 简历「个人职责」对照版

> **适用项目**：`glyahh-ai-generate-code`（对话式 AI 应用生成平台）  
> **对照参考**：[yu-ai-code-mother](https://github.com/liyupi/yu-ai-code-mother)（鱼皮教学版，微服务/监控等模块的架构说明来源）  
> **表达原则**：先讲业务价值 → 再讲你负责的链路 → 最后讲技术取舍；没在本仓库落地的能力，面试里要说「教学项目/目标架构里怎么做」，不要说成已全部上线。

---

## 0. 面试开场：30 秒 + 2 分钟版本

### Q0.1 用 30 秒介绍你的项目

**答：**

> 我做的是一个 **AI 零代码应用生成平台**。用户用自然语言描述需求，后端通过 LangChain4j / LangGraph4j 生成单页 HTML、多文件前端或 Vue 3 工程，过程用 **SSE 流式**推给前端；代码会 **落盘、预览、发布**。相比普通 ChatBot，它把「模型输出 → 可运行制品 → 二次编辑 → 部署分享」串成完整闭环。

### Q0.2 你在项目里主要负责什么？

**答：**

> 我主要做 **后端核心链路 + 部分前端联调**：SSE 接口、会话历史与记忆治理、Parser/Saver 制品管线、LangGraph4j 工作流（图片收集、质检重试、构建）、工具调用适配、限流与安全审查。前端侧熟悉 Vue 3 聊天页 EventSource 解析、iframe 预览和 OpenAPI 类型生成流程。

---

## 1. AI 零代码平台架构（Spring Boot 3 + Java 21 + LangChain4j + LangGraph4j）

### Q1.1 你们的整体架构是怎么分层的？

**答：**

> 后端是典型的 **Controller → Service → Core 门面/执行器** 分层：
>
> - **Controller**：REST + SSE 入口，统一 `BaseResponse`，生成走 `Flux<ServerSentEvent>`
> - **Service**：App 权限、历史落库、发布、Loop 技能注入
> - **Core**：`AiCodeGeneratorFacade`（普通生成）、`WorkflowCodeGeneratorFacade`（工作流）、Parser/Saver、StreamHandler
> - **ai 包**：LangChain4j `AiServices` 工厂、工具注册、ChatMemory
> - **LangGraph4j 包**：多节点工作流编排
>
> 制品链路：`LLM 输出 → Parser/Tool 写盘 → temp/code_output → 预览/发布 → temp/code_deploy`

**追问：为什么 Facade 不直接写在 Controller？**

> Controller 只关心 HTTP/SSE 协议；Facade 关心「按 codeGenType 选策略、绑工具、开流」。职责分开后，工作流节点和普通接口都能复用同一套生成能力。

### Q1.2 LangGraph4j 工作流有哪些节点？和普通生成有什么区别？

**答：**

> 工作流在 `CodeGenWorkflow` 里定义，主路径是：
>
> `image_collector → prompt_enhancer → router → code_generator → code_quality_check → (retry | project_builder | END)`
>
> - **image_collector**：LLM 规划素材任务，并发拉取图片
> - **prompt_enhancer**：增强用户提示词
> - **router**：路由 HTML / MULTI_FILE / VUE
> - **code_generator**：复用原有 Facade 流式生成
> - **code_quality_check**：质检，失败最多重试 3 次
> - **project_builder**：Vue 项目 npm build
>
> 普通模式是一次 Facade 调用，适合简单页面；工作流适合 **可观测、可重试、可扩展** 的复杂任务。

### Q1.3 为什么选择 LangChain4j + LangGraph4j，而不是自己拼 HTTP 调模型？

**答：**

> 自己拼 HTTP 很快能跑 Demo，但生产里要处理：ChatMemory、Tool Calling 协议、Streaming Token、Guardrail、多模型 Bean 配置。LangChain4j 把这些抽象成 **AiService + @Tool + ChatMemoryStore**；LangGraph4j 把「收集素材 → 生成 → 质检 → 构建」做成 **有状态图**，比一个大方法里堆 if-else 更易维护和扩展。

### Q1.4 你们支持几种制品类型？怎么路由？

**答：**

> 三种：`HTML` 单页、`MULTI_FILE`（html+css+js）、`VUE` 工程脚手架。
>
> 创建 App 时可用户指定，也可调用 **routing 模型**判断。工作流里 `RouterNode` 会在增强后的 prompt 基础上做类型决策，再进入对应生成策略。

---

## 2. Prompt Engineering 优化（系统提示词、Few-shot、输出约束）

### Q2.1 你们 Prompt 是怎么组织的？

**答：**

> 分几层叠加，而不是一条超长 System Prompt：
>
> 1. **代码生成系统提示**：按 `CodeGenTypeEnum` 区分 HTML / 多文件 / Vue，约束输出格式（ fenced 代码块或工具调用）
> 2. **路由模型提示**：专门做类型判断，和生成模型分离（6 套独立模型配置：chat / streaming / reasoning / routing / code-exam / file-note）
> 3. **工作流增强**：`PromptEnhancerNode` 把用户短需求扩写为结构化需求
> 4. **会话注入**：个性化风格、Loop 技能、`[memory_index]` / `[memory_file_note]` 工程索引
> 5. **重试修复提示**：质检失败后拼接错误信息，要求 **只做针对性修复**，避免整项目重写
>
> 这是典型的 **角色设定 + 输出约束 + 分场景 Few-shot 示例** 组合，目的是提高格式稳定性和少幻觉。

### Q2.2 怎么减少模型「说一堆废话、不写代码」？

**答：**

> 三路约束：
>
> - **格式约束**：首轮 HTML/多文件要求 fenced 代码块；Vue 首轮只允许 `writeFile` 工具，强制走结构化写盘
> - **Output Guardrail**：`OpenAiOutputGuardrailsConfig` + `RetryOutputGuardrail` 对输出做清洗/重试
> - **工具替代纯文本**：后续编辑轮用 `readFile/modifyFile`，减少模型输出整份代码的概率

### Q2.3 Few-shot 在你们项目里具体怎么用？

**答：**

> 不是每次塞很多例子，而是 **按场景嵌入**：
>
> - 路由模型：内置「输入需求 → 输出类型」示例
> - 图片收集计划：`ImageCollectionPlanService` 让模型输出 JSON 计划（content/illustration/diagram/logo 任务）
> - 代码质检：`CodeExamService` / `CodeQualityCheckService` 用专门模型按 checklist 审查
>
> 好处是 **小模型 + 小任务 + 结构化输出**，比一个大模型一次干所有事更稳。

### Q2.4 多轮对话 Prompt 会不会越来越乱？

**答：**

> 会，所以我们做了 **记忆分层**（见第 4 节）：MySQL 存全量历史，Redis 存模型热窗口，摘要写 `memory_shrink`，工程态用 `changedFiles` + snapshot，而不是每轮把全部源码塞进 Prompt。

---

## 3. AI Agent Tool Calling（LangChain4j @Tool + AiService）

### Q3.1 你们有哪些工具？怎么注册？

**答：**

> 通过 `ToolManager` 统一注册 `BaseTool` 实现，常见工具：
>
> - `writeFile` / `readFile` / `readDir` / `modifyFile` / `deleteFile` / `exit`
>
> `aiCodeGeneratorServiceFactory` 用 `AiServices.builder()` 绑定工具和 `MessageWindowChatMemory`，`appId` 作为 `@ToolMemoryId` 对齐会话目录。

### Q3.2 首轮和后续轮为什么工具集不同？

**答：**

> **首轮**没有现成文件：
>
> - Vue：只允许 `writeFile`，避免 read/modify 不存在的文件
> - HTML/多文件：首轮走流式 fenced 代码 + Parser 落盘，不开放写文件工具
>
> **后续编辑轮**开放 `readFile/modifyFile` 等，做局部修改，降低覆盖整站风险。这是 **工具白名单** 策略，比「一次开放全部工具」更安全。

### Q3.3 怎么提高 Tool Calling 成功率、减少幻觉调用？

**答：**

> 几招组合拳：
>
> 1. **工具描述清晰**：`@Tool` 注解写清参数含义和边界
> 2. **同轮去重**：`FileModifyTool_Assist` 记录本轮已成功 modify 的参数，防重复调用
> 3. **重要文件守卫**：如 `index.html`、入口文件在特定类型下禁止误改
> 4. **结构化回传**：工具结果写回 ChatMemory，前端展示工具卡片，用户可感知模型在做什么
> 5. **流式适配**：`VueTokenStreamAdapter` 把 tool request/result 转成前端可解析协议
>
> 简历里「99.9% 成功率」面试时建议改成：**「通过白名单、去重和守卫，线上几乎没出现工具名乱调或写错目录」**，更可信。

### Q3.4 Tool Calling 和 Function Calling 有什么区别？

**答：**

> 概念上是一类能力：模型输出结构化「要调哪个函数、参数是什么」。LangChain4j 用 `@Tool` 把 Java 方法暴露给模型；底层仍走 OpenAI/DashScope 的 tool_calls 协议。我们还在 `OpenAiStreamingResponseBuilder` 本地补丁里处理 **流式 tool call 分片索引合并**。

### Q3.5 模型不调工具、只输出 markdown 怎么办？

**答：**

> HTML/多文件有 **Parser 兜底**：从 fenced 代码块解析落盘。Vue 首轮如果一直不调 `writeFile`，流结束后会发现目录空或文件不全，前端/日志可感知。质检节点和工作流重试也会推动模型回到正确路径。

---

## 4. 聊天系统开发（时间戳游标分页 + MySQL 复合索引）

### Q4.1 聊天历史怎么分页？为什么不用 offset 深分页？

**答：**

> App 内聊天记录用 **时间戳游标**：
>
> - 首页：`lastCreateTime = null`，取最新 N 条
> - 上拉加载：`lastCreateTime = 当前页最早一条的 createTime`，查 `createTime <= 游标` 的记录
>
> 对应 `ChatHistoryServiceImpl.listAppChatHistoryByPage` 和 `QueryWrapper.le(ChatHistory::getCreateTime, lastCreateTime)`。
>
> **不用 OFFSET 深分页**的原因：对话越长，`LIMIT offset, size` 越慢，还要扫描跳过大量行。游标分页只走索引范围扫描，性能更稳定。

### Q4.2 你们建了哪些索引？为什么 `(appId, createTime)` 是核心？

**答：**

> `chat_history` 表（见 `docs/sql/create_table.sql`）：
>
> - `idx_appId (appId)`
> - `idx_createTime (createTime)`
> - **`idx_appId_createTime (appId, createTime)`** — 游标查询核心索引
>
> 查询模式是 **固定 appId + 按 createTime 倒序 + 时间上限过滤**，复合索引最左前缀匹配 appId，再在 createTime 上排序/过滤，避免 filesort 全表扫。

### Q4.3 Redis 和 MySQL 在聊天里各干什么？

**答：**

> - **MySQL `chat_history`**：真相源，用户/AI 消息全量持久化
> - **Redis echo 缓存**：前端回显加速，miss 回源 DB 再回填
> - **Redis ChatMemoryStore**：模型上下文热窗口，不是唯一数据源
>
> Redis 丢了可以从 MySQL + `memory_shrink` 重建，见 `ChatHistoryAiMemoryRebuildSupport`。

### Q4.4 前端历史列表和后端分页怎么配合？

**答：**

> 前端聊天页上拉加载时带上 **最后一条消息的 createTime** 作为游标；后端返回 `AppChatHistoryPageVO`，包含是否还有更早消息。管理端用户聊天记录列表则用传统 `pageNum/pageSize` + 可选 `lastCreateTime` 组合。

### Q4.5 为什么说复合索引带来约 3 倍提升？

**答（面试话术）：**

> 优化前只有单列 `appId` 索引，过滤后要大量回表排序；加上 `(appId, createTime)` 后，`EXPLAIN` 显示走索引范围扫描，深分页延迟明显下降。具体倍数和表数据量有关，我本地压测/App 长对话场景下大约 **2～3 倍**，重点是 **查询计划从不稳定 filesort 变成稳定索引扫描**。

---

## 5. 异步任务调度（Java 21 虚拟线程）

### Q5.1 项目里哪些场景用了虚拟线程？

**答：**

> 两类 **I/O 密集、无需 CPU 绑核** 的任务：
>
> 1. **Vue 项目构建**：`vueProjectBuilder.BuildVirtualThreadForBuildVue` 用 `Thread.ofVirtual()` 异步 `npm build`，不阻塞 SSE 主链路
> 2. **应用封面截图**：`AppServiceImpl.generateAppScreenshotAsync` 用 `Thread.startVirtualThread` 调截图服务上传 OSS
>
> 发布主流程只等必要校验，耗时构建/截图放后台，提升接口响应体验。

### Q5.2 为什么用虚拟线程而不是传统线程池？

**答：**

> 构建和截图主要是 **等子进程/网络 I/O**，线程大部分时间在阻塞。平台线程（Platform Thread）数量宝贵，线程池配太大会有内存和调度开销；**虚拟线程**轻量，适合大量「发起 I/O → 等待 → 回调」任务。Java 21 起虚拟线程已是标准方案，代码也比自己维护固定大小线程池简洁。

### Q5.3 虚拟线程和 CompletableFuture 怎么选？

**答：**

> - **虚拟线程**：逻辑上是一段顺序代码（如 `buildProject` 整个方法），只是不想阻塞 HTTP 线程
> - **CompletableFuture**：需要 **多任务并发聚合**，如 `ImageCollectorNode` 里多个图片搜索并行，用 `supplyAsync + allOf`
>
> 图片收集目前用的是默认 ForkJoinPool 的 `supplyAsync`；构建/截图用虚拟线程。如果图片任务极多，可以改为 **专用 Executor + 虚拟线程工厂**（教学项目 yu-ai-code-mother 里也有类似优化讨论）。

### Q5.4 虚拟线程里抛异常怎么处理？

**答：**

> `BuildVirtualThreadForBuildVue` 里 catch 后转 `MyException` 并打日志。虚拟线程未捕获异常会走 **UncaughtExceptionHandler**，生产里应接入统一日志/告警。截图异步任务失败时不应影响发布成功的主状态，封面可降级为默认图。

---

## 6. AI 素材收集模块（LLM 规划 + CompletableFuture 并发）

### Q6.1 素材收集链路是怎样的？

**答：**

> 在工作流 `ImageCollectorNode` 中分两步：
>
> 1. **LLM 规划**：`ImageCollectionPlanService` 根据用户需求生成 `ImageCollectionPlan`（内容图、插画、架构图、Logo 等任务列表）
> 2. **并发执行**：每类任务 `CompletableFuture.supplyAsync` 调用对应 Tool：
>    - `ImageSearchTool`（内容图）
>    - `UndrawIllustrationTool`（插画）
>    - `MermaidDiagramTool`（架构图）
>    - `LogoGeneratorTool`（Logo）
>
> `allOf().join()` 等待全部完成，再按类别 **配额截断**（如 pixel 类最多 7 张），写入 `WorkflowContext.imageList` 供后续 Prompt 增强和代码生成使用。

### Q6.2 为什么先让 LLM 做计划，而不是写死规则？

**答：**

> 用户需求差异大：企业官网要 Logo+架构图，活动页要大量内容图。LLM 计划可以把自然语言映射到 **结构化任务列表**，比 if-else 规则可扩展。计划输出是 JSON 结构，下游并发节点只关心任务数组，职责清晰。

### Q6.3 并发收集怎么保证不会拖垮系统？

**答：**

> - 计划阶段限制任务数量
> - 结果阶段 `limitSize` 按类别封顶
> - 单任务失败 catch 后记录日志，不阻断整个工作流（降级为无图生成）
> - 后续可 Accelerate：专用线程池、超时 `orTimeout`、熔断外部图库 API

### Q6.4 素材收集带来的业务价值？

**答：**

> 生成页面不再是纯 CSS 占位，而是带 **真实图片、Logo、架构图**，预览更接近可交付成品。并发把「串行搜 10 张图」缩到 **接近最慢单次 API 的耗时**，用户等待明显缩短。

---

## 7. AI 接口稳定性（三级限流：用户 / IP / 接口）

### Q7.1 你们的限流是怎么做的？

**答：**

> 自定义 `@RateLimit` + AOP `rateLimiterAspect` + **Redisson `RRateLimiter` 令牌桶**：
>
> - **USER**：`rate_limit:user:{userId}`，生成接口典型配置 60 秒 5 次
> - **IP**：未登录或拿不到用户时降级为 IP 限流
> - **API**：按 `类名.方法名` 限制接口总 QPS
>
> `tryAcquire(1)` 失败抛 `TOO_MANY_REQUEST`。这是 **分布式限流**，多实例共享 Redis 计数。

### Q7.2 为什么用 Redisson 令牌桶，而不是 Guava RateLimiter？

**答：**

> Guava 限流只在 **单 JVM 内**有效，Spring Boot 多实例部署时每个节点各算各的，等于限额放大 N 倍。Redisson 基于 Redis，**全集群统一计数**，更适合 AI 接口这种要控成本、防刷的场景。

### Q7.3 除了限流，还有哪些稳定性手段？

**答：**

> - **业务层重复请求拦截**：`ChatToGenCodeImpl` 12 秒内相同 appId+userId+消息 hash 拒绝重复提交
> - **SSE 异常兜底**：`onErrorResume` + 必发 `done` 事件
> - **取消流回滚**：CANCEL 时删用户消息、清 ChatMemory，避免脏上下文
> - **Caffeine + Redis 双缓存**：AI Service 实例缓存，Redis 过期主动失效重建
> - **ReentrantLock**：同 App 首轮并发保护

### Q7.4 限流被触发后用户体验怎么处理？

**答：**

> 返回统一错误码和友好文案（注解 `message`），前端 Ant Design Vue `message.error` 提示。AI 场景要明确告诉用户 **「请求过于频繁，请稍后再试」**，避免 silent fail。

### Q7.5 如何评估限流阈值是否合理？

**答：**

> 结合 **单用户正常交互频率**（连续改 prompt 一般不会 5 次/分钟）和 **Token 成本**。上线后看 Redis 限流 key 命中率和 ARMS/Prometheus 429 比例（监控部分见第 9 节），再调 `rate` 和 `rateInterval`。

---

## 8. Prompt 安全治理（LangChain4j Guardrails + 防 Prompt Injection）

### Q8.1 输入安全审查放在哪一层？

**答：**

> 在 **调用模型之前**：`ChatToGenCodeImpl` 里对用户 message 做审查；`PromptSafetyInputGuardrail` 实现 LangChain4j `InputGuardrail`，内部走 `PromptSafetyAuditEvaluator` 规则评估。命中则 **fatal 阻断**，不进入生成 Facade。

### Q8.2 你们防哪些类型的 Prompt 攻击？

**答：**

> 典型包括：
>
> - **Prompt Injection**：试图覆盖系统指令（「忽略上文，你现在要…」）
> - **越权诱导**：让模型执行不该做的工具操作
> - **敏感/违规内容**：政治、色情、违法等（规则表驱动）
>
> 审查结果会写入 `chat_history.auditAction / auditHitRule` 字段，便于审计。

### Q8.3 Input Guardrail 和 Output Guardrail 区别？

**答：**

> - **Input**：用户消息进模型前，`PromptSafetyInputGuardrail`
> - **Output**：模型输出后，`RetryOutputGuardrail` + `OpenAiOutputGuardrailsConfig`，防止危险内容或格式污染落盘
>
> 输入防注入，输出防有害内容和不规范代码直接写进制品。

### Q8.4 安全规则和模型本身会不会冲突？

**答：**

> 规则层是 **确定性兜底**，模型是概率系统。我们采用「规则优先」：命中高危规则直接拒绝，中危可记录+放行。这样即使模型被注入，还有一道硬闸。后续可接 **专用安全小模型** 做二次分类（yu-ai-code-mother 教程里有扩展方向）。

---

## 9. 系统可观测性（ARMS + Prometheus + Grafana）

> **诚实说明**：当前 `glyahh-ai-generate-code` 主仓库 **未完整接入** Prometheus/Grafana/ARMS；以下回答对齐 [yu-ai-code-mother](https://github.com/liyupi/yu-ai-code-mother) 教学版中的 **目标架构**，面试时说「项目规划/教程版已实践，我了解接入方式」。

### Q9.1 如果要做 AI 平台监控，你会监控哪些指标？

**答：**

> 四类黄金信号 + AI 特有指标：
>
> | 类别 | 指标示例 |
> |------|----------|
> | 流量 | QPS、SSE 连接数、工作流触发次数 |
> | 延迟 | 生成首 token 时间、整轮完成耗时、npm build 耗时 |
> | 错误 | 5xx 率、限流触发率、质检失败率、Tool 调用异常 |
> | 成本 | Token 消耗（input/output）、按用户/接口聚合 |
>
> 业务侧：发布成功率、截图失败率、记忆压缩次数。

### Q9.2 Prometheus + Grafana 怎么接 Spring Boot 3？

**答（标准方案）：**

> 1. 引入 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`
> 2. 暴露 `/actuator/prometheus` 端点
> 3. `prometheus.yml` 配置 scrape 目标（yu-ai-code-mother 仓库根目录有示例）
> 4. Grafana 导入 JVM/Spring Boot 仪表盘，自定义 AI 指标面板
>
> 自定义指标可用 `MeterRegistry.counter()` 记录 Token 用量、工作流节点耗时。

### Q9.3 ARMS 在这种项目里的价值？

**答：**

> 阿里云 ARMS 适合 **分布式链路追踪**：一次生成请求经过 Controller → Service → Facade → 多次 LLM HTTP → Tool 写盘 → Handler 落库。ARMS 能看到哪一段慢、哪次外部 API 超时。和日志、Prometheus 告警组合：**Metrics 发现问题 → Traces 定位节点 → Logs 看细节**。

### Q9.4 本地开发没接监控，你怎么证明稳定性？

**答：**

> 诚实说生产级监控还在演进，但我做了 **JUnit 集成测试**（SSE 事件、Workflow、记忆重建、限流、Guardrail），并有 **结构化日志**（appId、roundId、workflow step）。线上会优先补 **生成链路耗时 + 错误率 + Token 计数** 三个指标。

---

## 10. 微服务架构（Spring Cloud Alibaba + Dubbo + Nacos + Higress）

> **诚实说明**：当前仓库是 **单体 Spring Boot**（端口 8124，`context-path /api`）。微服务拆分见 yu-ai-code-mother 的 `yu-ai-code-mother-microservice` 模块。面试答「我了解拆分方案，当前实战版本是模块化单体，便于迭代」。

### Q10.1 为什么 AI 代码平台要微服务化？

**答：**

> 单体适合教学和小团队；流量上来后，**AI 生成、用户管理、截图/部署** 的资源特征不同：
>
> - 生成服务：CPU/GPU、长连接 SSE、调外部 LLM
> - 用户服务：典型 CRUD、鉴权
> - 截图服务：无头浏览器、重 I/O
>
> 拆分后可 **独立扩缩容**，AI 节点挂掉不拖垮登录。

### Q10.2 yu-ai-code-mother 里一般怎么拆？

**答（参照教学版）：**

> - **Nacos**：注册中心 + 配置中心
> - **Dubbo**：RPC 调用（用户服务 ↔ AI 服务）
> - **Spring Cloud Gateway / Higress**：统一网关、限流、路由
> - 独立模块：user-service、ai-service、screenshot-service 等
>
> 前端仍通过网关访问 `/api`，对浏览器透明。

### Q10.3 单体改微服务，你最先拆哪一块？

**答：**

> 我会先拆 **截图/构建** 这种慢 I/O、资源隔离需求强的能力，因为和 SSE 主链路耦合最松。AI 生成核心仍可先留在单体，等接口边界稳定再拆。

### Q10.4 Dubbo 和 OpenFeign 怎么选？

**答：**

> Dubbo 性能更好、服务治理能力强，适合 **内部高频 RPC**；OpenFeign 基于 HTTP，简单但 overhead 大。AI 平台内部服务间若传递大 payload（如制品元数据），Dubbo + 压缩/对象存储引用更合适。

---

## 11. 前端工程（Vue 3 + TypeScript + OpenAPI）

### Q11.1 前端技术栈和职责？

**答：**

> - **Vue 3 + TypeScript + Vite 7 + Pinia + Vue Router 5 + Ant Design Vue 4**
> - 核心页：`AppChatView`（SSE 聊天 + iframe 预览）、`HomeView`、Loop 技能市场等
> - 开发 proxy：`/api` → `localhost:8124`，保证 iframe 预览同源

### Q11.2 OpenAPI 自动生成客户端怎么做的？

**答：**

> 后端 SpringDoc 暴露 `/api/v3/api-docs`；前端 `npm run openapi2ts`（`openapi-ts-request`）生成 `src/api/*.ts` 类型和请求函数。**约定：不手改生成文件业务逻辑**，接口变更以后端为准重新生成。

### Q11.3 SSE 在前端怎么接？难点是什么？

**答：**

> 用浏览器 **`EventSource`** 订阅 `/api/chat/gen/code` 或 `/workflow`。
>
> 难点不是接流，而是 **解析**：chunk 可能是普通 Markdown、代码围栏、工具请求/结果、工作流步骤。项目用 **流式状态机**（buffer + stage）拆成 UI segment，分别渲染文本、代码块、工具卡片、步骤条。

### Q11.4 为什么 iframe 预览而不是 innerHTML？

**答：**

> 用户生成的 HTML/JS **不可信**，直接 `v-html` 有 XSS 风险。iframe 沙箱 + 独立 origin（经后端静态资源 Controller）隔离执行环境。静态资源响应设 `Cache-Control: no-cache` 避免看到旧版本。

### Q11.5 ESLint + Prettier 怎么保证质量？

**答：**

> `npm run lint` 跑 oxlint + eslint；`npm run format` 跑 prettier。TS 严格模式 + `vue-tsc` 构建前类型检查。团队约定组件、API、状态管理目录结构，减少联调摩擦。

---

## 12. AI 研发 toolchain（MCP + Cursor / Claude Code）

### Q12.1 MCP 是什么？和项目有什么关系？

**答：**

> **Model Context Protocol** 让 AI IDE/Agent 以标准协议连接外部工具（Figma、浏览器、文件系统等）。
>
> 在本项目 **业务代码** 里，核心仍是 LangChain4j `@Tool`；MCP 更多用在 **研发效率**：
>
> - Cursor 接 MCP 读文档、跑浏览器、查 API
> - 教学版还提到 Figma/Streamline 等设计资产 MCP
>
> 面试区分：**运行时 Tool（给用户生成应用）** vs **开发时 MCP（给开发者提效）**。

### Q12.2 你用 Cursor / AI 编程工具怎么提效？

**答：**

> - 按分层规范生成 Controller/Service 样板，人工 review 边界
> - 写 JUnit 测试、重构 Parser/Saver
> - 前端组件和 SSE 解析 bug 定位
>
> 关键：**AI 写草稿，人做架构决策和安全审查**，尤其是 Prompt、权限、落盘路径不能盲信生成。

### Q12.3 如果要把 MCP 接进产品怎么办？

**答：**

> 可以新增 MCP Server 暴露「读生成目录、跑 preview E2E、查 deploy 状态」等能力，供运营/Agent 调用。要和现有 REST/SSE API 对齐权限，避免 Agent 越权读用户代码。

---

## 13. AI 自动化测试（Playwright + Web-to-MCP）

### Q13.1 你们测试策略是什么？

**答：**

> AI 输出 **非确定性**，断言「每次生成相同 HTML」不现实。测试重点是 **协议和状态机**：
>
> - 后端 JUnit 5：SSE 事件格式、Workflow 路由、Parser/Saver、记忆重建、限流、Guardrail（约 40+ 测试类）
> - 前端 E2E：`docs/superpowers/test-reports/` 下有 Playwright 浏览器测试报告（聊天流、预览等）
>
> 模型层用 **集成测试 + mock** 或标记 `@Disabled` 的 live 测试，CI 不依赖真实 API Key。

### Q13.2 Playwright 测什么不测什么？

**答：**

> **测**：页面能打开、登录、发起生成、SSE 有响应、预览 iframe 加载、工具卡片渲染  
> **不测**：模型创意内容是否优美（改 snapshot 阈值或只测 DOM 结构存在）
>
> 截图对比可用于 **回归 UI 布局**，不是回归 AI 文案。

### Q13.3 Web-to-MCP 是什么场景？

**答：**

> 把浏览器可操作能力通过 MCP 暴露给 Agent，用于 **自动点页面、抓控制台错误、截图**」。适合「生成完 → 自动打开预览 → 看 console 有没有报错」的闭环。属于研发/QA 增强，和 Playwright 类似但交互式更强。

### Q13.4 你怎么测 SSE 完整性？

**答：**

> 参考 `ChatToGenCodeControllerSseEventTest`：断言事件类型、`done` 必达、异常时也有失败文案。Handler 层测 `AtomicBoolean` 只收口一次，cancel 时回滚用户消息。

---

## 14. 综合高频追问（Java / Spring / 中间件 / 设计）

### Q14.1 SSE 和 WebSocket 怎么选？

**答：**

> 生成场景是 **服务端单向流**，SSE + `EventSource` 足够，且与 Spring WebFlux `Flux<ServerSentEvent>` 天然契合。WebSocket 适合双向高频（协同编辑、实时中断控制）。我们取消生成走 HTTP 另发请求 + 断 EventSource。

### Q14.2 长对话上下文爆炸怎么解决？

**答：**

> 三层：
>
> 1. Redis ChatMemory 窗口限制
> 2. `trySummarizeOldestRoundsIfNeeded` 摘要进 `memory_shrink`
> 3. 工程态用 snapshot + changedFiles + `[memory_index]`，不全量塞源码
>
> MySQL 始终保留完整历史供前端展示。

### Q14.3 Redis 缓存穿透/雪崩你们怎么处理？

**答：**

> Loop 编译缓存 `loop:compiled:{id}`：**cache-aside**，miss 查 DB；DB 无数据写短 TTL 空值防穿透；正常 key TTL 加随机抖动防雪崩。ChatMemory 过期则从 MySQL 重建，不靠 Redis 单点。

### Q14.4 事务用在哪？生成链路为什么不大事务？

**答：**

> 用户消息落库、发布更新等 **短事务**。SSE 生成可能几分钟，不能包在一个 `@Transactional` 里。策略是 **分阶段提交**：消息先落库 → 流式生成 → `doFinally` 收口 AI 消息和 memory_state，失败分支有补偿（删用户消息、清 memory）。

### Q14.5 你怎么理解「制品交付」？

**答：**

> 模型输出必须变成 **可运行文件**：`temp/code_output` → 预览 → `deployApp` 复制到 `temp/code_deploy` 并生成 deployKey。Vue 还要 npm build 出 dist。这是和普通 ChatBot 的本质区别。

### Q14.6 项目最难的点是什么？

**答：**

> **流式链路一致性**：同一轮生成同时涉及 SSE 展示、ChatMemory、MySQL、磁盘、snapshot。任何一环失败都可能状态不一致。所以用 Handler 统一收口、`AtomicBoolean` 防重复、`onRoundCompleted` 与主链路解耦（记忆失败不拖垮 SSE）。

### Q14.7 如果继续优化你会做什么？

**答：**

> 1. memory soft/hard summary 更稳定注入模型  
> 2. 大文件 ref 分页读回  
> 3. npm build 错误结构化喂给工作流自动修复  
> 4. 接 Prometheus 做 Token 与耗时看板  
> 5. 图片收集专用线程池 + 超时熔断

### Q14.8 和普通 CRUD 项目比，你学到了什么？

**答：**

> CRUD 的状态主要在数据库；这个项目状态分布在 **流、文件、缓存、模型上下文** 四处，且模型输出不确定。需要 Parser、工具协议、重试、Guardrail、记忆治理，更接近 **AI 工程化** 而不是调 API。

---

## 15. 简历职责 ↔ 项目实现对照表

| # | 简历职责 | 本项目实现 | 备注 |
|---|----------|------------|------|
| 1 | AI 零代码平台架构 | ✅ LangChain4j + LangGraph4j 工作流 | 单体模块化，非微服务 |
| 2 | Prompt 优化 | ✅ 分模型/分场景 System Prompt + 增强节点 | |
| 3 | Tool Calling | ✅ ToolManager + AiServices + 流式适配 | |
| 4 | 聊天游标分页 | ✅ lastCreateTime + idx_appId_createTime | |
| 5 | 虚拟线程 | ✅ Vue 构建、截图异步 | |
| 6 | 素材收集 | ✅ ImageCollectorNode + CompletableFuture | |
| 7 | 三级限流 | ✅ @RateLimit USER/IP/API + Redisson | |
| 8 | Prompt 安全 | ✅ Input/Output Guardrail | |
| 9 | ARMS/Prometheus/Grafana | ⚠️ 教学版有示例 | 答架构认知 + 接入步骤 |
| 10 | 微服务 | ⚠️ yu-ai-code-mother-microservice | 当前仓库为单体 |
| 11 | Vue3 + OpenAPI | ✅ 前端全栈 + openapi2ts | |
| 12 | MCP + AI IDE | ⚠️ 研发提效为主 | 区分运行时 Tool |
| 13 | Playwright 测试 | ✅ 部分 E2E 报告 + 大量 JUnit | |

---

## 16. 面试前 30 分钟复习清单

1. 画一遍工作流节点图（6 个节点 + 条件边 retry/vue/skip）
2. 讲清 **SSE 全链路**（Controller → Impl → Facade → Handler → memory）
3. 讲清 **三轮记忆**（MySQL / Redis ChatMemory / snapshot+changedFiles）
4. 打开 `rateLimiterAspect` 讲三级 key 生成
5. 打开 `create_table.sql` 讲 `idx_appId_createTime`
6. 准备 **诚实边界**：微服务、监控、MCP 进产品 的说法
7. 准备一个 **踩坑故事**：如流式工具卡片缺失、取消流脏数据、Redis 过期重建

---

## 17. 参考链接

- 本项目架构说明：`docs/interview/项目面试准备.md`、`docs/plans/conversation-memory-v4-current-architecture.md`
- 鱼皮教学仓库：[liyupi/yu-ai-code-mother](https://github.com/liyupi/yu-ai-code-mother)
- LangChain4j 文档：https://docs.langchain4j.dev/
- Java 21 虚拟线程：https://openjdk.org/jeps/444

---

*文档生成说明：问答基于 `glyahh-ai-generate-code` 源码与 SQL 整理；第 9、10、12 节部分能力参照 yu-ai-code-mother 目标架构，面试时请与简历表述保持一致，避免过度包装。*
