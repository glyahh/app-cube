# 当前项目状态与开发主线

2026-10-06 当前版本验收收口：[第一项当前版本收口记录](PLANS/project-import-item1-current-closure.md)在 `code_board` 工作区（含未提交修复）上重跑全部可执行验收：安装/运行/磁盘 38 项、隔离执行 16 项、平台秘密哨兵 11 项、真实框架与双服务 18 项、真实 Agent 修复闭环 E04/E05 10 项、导入接口/来源/凭据/负例/恢复 176 项、启动链与迁移 schema 15 个脚本全部通过；连续 10 次真实导入提交无失败。本轮同时修复：执行日志落盘遮盖密钥形态、额度残留导致同一应用永久 `DISK_QUOTA`、发布改名对 Windows 瞬时拒绝的有界重试、[Agent 编辑准入](PLANS/project-import-agent-edit-channel-verification.md)（已发布副本可编辑，来源暂存与保护快照结构隔离）。Java 全量 698 项 0 失败、前端 222 项通过。验收残留（283 个测试账号应用、15 个受限卷、44 个沙箱容器、约 4.6GB 文件）经 `scripts/cleanup-import-test-residue.py` 清理并留报告。**第一项仍 NOT_COMPLETE**：私库验收 G02/G04 按用户决定暂缓记 `NOT_RUN`；发布失败的 `AccessDeniedException`/`VERIFY_WORKSPACE` 根因未完全确认，保留为已知风险；历史通过数字不计当前版本。

2026-10-06 最新前置增量：[受限卷冻结文件复制](PLANS/project-import-disk-volume-seed-verification.md)新增清单/逐文件摘要校验、SEEDING 未决登记和同身份复用不覆盖产物；21 项原生单元与 22 条真实卷/容器局部检查通过。**仍未接 Java 正式执行和业务资源账本，文件复制不等于完整属性/空目录语义复制**。第一项 NOT_COMPLETE；下方为历史节点。

2026-10-06 最新前置增量：[受限卷预留空间与恢复](PLANS/project-import-disk-volume-recovery-verification.md)升级 v2 journal/实际预分配，新增暂停→文件系统检查→重挂载，16 项原生单元、17 条真实局部检查通过并清理专用卷。**Java 业务尚未接入，正式执行仍无持久磁盘硬配额**；冻结复制、资源账本、权限/上下文与平台恢复继续待接，掉电恢复未验收。第一项 NOT_COMPLETE；下方均为历史节点。

2026-10-06 最新局部核心：[受限持久磁盘卷](PLANS/project-import-disk-volume-core-verification.md)新增独立 Linux helper，9 项原生单元与 11 条真实容器/文件系统检查通过，验证固定容量 ENOSPC、跨容器产物保留、双卷隔离及清理。**尚未接 Java 业务，正式接口仍无持久副本磁盘硬配额**；资源账本/冻结复制/恢复和宿主总量预算继续待开发。第一项 NOT_COMPLETE，不把卷核心验收当整项交付；其他三项不展开。下方保留历史节点。

2026-10-06 最新增量：[冻结健康路径](PLANS/project-import-health-path-verification.md)已接声明→候选→计划→执行，限定本机来源、禁止跳转、只认声明路径 2xx，并绑定具体服务身份/命令/端口。Java 437 项中 432 PASS/5 SKIP，标准重启后受控真实 API/容器 35 条局部检查 PASS，含根路径真实 404 但健康接口成功、健康失败不换查其他路径。旧规则需重新扫描确认。第一项 **NOT_COMPLETE**：持久副本磁盘硬保护、隔离 Agent 编辑/修复、日志脱敏全链路、其他资源/故障边界和完整矩阵仍待完成；私有仓库暂缓 NOT_RUN。下方为历史节点。

2026-10-05 最新增量：[持续日志与服务退出监督](PLANS/project-import-service-supervision-verification.md)已接后台有界采集、超量输出自动停止和冻结身份终态更新，并修复真实验收发现的查询/监督 CAS 竞争。最终 Java 433 项中 428 PASS/5 SKIP，标准重启后新测试应用真实 API/容器 29 条局部断言 PASS，保留首次失败证据。第一项 **NOT_COMPLETE**：健康路径、持久副本磁盘硬保护、隔离 Agent 编辑/修复、日志脱敏全链路与完整矩阵仍待完成；私有仓库暂缓 NOT_RUN。下方为各时点历史记录，不代表当前全部通过。

2026-10-05 审查后继续开发：第一项 **NOT_COMPLETE**。撤销下方按 57 组各一条核心断言宣布 COMPLETE 的当前效力，保留历史记录。[运行状态修复](PLANS/project-import-runtime-quality-fixes.md)已接停止重跑和实际容器状态核对：429 项单元中 424 PASS/5 SKIP，真实 API/容器 25 条局部断言 PASS。持续日志、健康路径、磁盘硬保护、隔离 Agent 开发与完整矩阵验收仍待完成。真实私有仓库暂缓验收记 NOT_RUN，不删除 Gitee 产品范围；历史通过数字不计当前版本通过。

2026-10-05 第一项 COMPLETE。完成判定以 [收口标准](PLANS/project-import-item1-closure.md) 为准：57 组各 1 条核心断言为 PASS，G04（Gitee 私有仓库）为 OUT_OF_SCOPE。`python scripts/verify-item1-closure.py` 退出码 0，证据 `temp/import-item1-closure-20261005200239/result.json`。已有 lifetime、gaps、remaining、公开来源、凭据和鉴权负例证据直接计入；本轮补跑了文件夹、公开分支、偏好、只读识别和恢复。旧矩阵的历史句子没有改写。以下保留历史节点。

2026-10-04 最新内部增量：[独占执行租约与资源 CAS](PLANS/project-import-startup-model-lease-verification.md)绑定批准版本/executor/PID/start，取消撤销资格但保留资源登记，清理后拒绝旧写入。V24已应用；Java424项中423通过/1跳过，真实回滚SQL5组，标准重启后已有API19组通过。批准/执行表均0行，正式批准/调度/取消恢复业务未接；第一项 NOT_COMPLETE，其他三项不展开。以下保留历史节点。


2026-10-04 最新内部增量：[封存文本与专属模型进程](PLANS/project-import-startup-model-executor-verification.md)已接同锁资格复核、批准摘要重校验、独占平台进程和严格 UTF-8/协议/到期门禁。Java416项中415通过、1跳过；Python350项、真实 helper 拒绝3组、标准重启后已有 API19组通过。V23未改，批准表0行。持久执行租约/取消恢复、正式批准接口/模型成功、工作台和隔离多服务仍待接；第一项 NOT_COMPLETE，其余三项不展开。下方是历史节点。


2026-10-04 最新内部增量：[启动批准账本/回包校验](PLANS/project-import-startup-approval-core-verification.md)保存决定、冻结身份、取消意图与CAS；V23已应用，但批准表0行，无正式批准/模型调度接口。Java395项中394通过/1跳过、Python350项通过、真实回滚SQL5组、重启后已有API19组通过。修正同目录损坏Node吞掉Java候选，规则v2撤销旧证据；同目录多个模型候选不减为一个。投影/执行租约/真实模型与正式UI仍待接，第一项NOT_COMPLETE，其他三项不展开。


2026-10-04 当前内部增量：[启动 Agent 只读核心](PLANS/project-import-startup-agent-core-verification.md)新增范围策略与封闭一次模型调用分支，不加载工作区、Shell、Git或历史。Python349项通过，Java367项中366通过/1跳过，真实Worker两条失败协议及重启后18组已有API回归通过。模型使用替身；持久批准、业务投影/回包资格、正式UI及真实模型成功未接/未验收，不能记S05通过。第一项继续开发，其余三项不展开；下方保留历史节点。


2026-10-04 当前恢复增量：[启动扫描恢复证据](PLANS/project-import-startup-recovery-verification.md)已接过期/重启资源核对、持久取消意图与 owner/revision 重新核对；V22 已应用。最终 Java 356 项中 355 通过、1 跳过，真实 API 19 组通过，包含真正扫描中断后标准重启、失败清理且不重跑。崩溃发生在 Python 前，运行中辅助进程故障与硬额度仍未验收。正式启动 UI、只读 Agent、冻结计划、安装/构建/隔离多服务继续待开发；第一项 NOT_COMPLETE，其他三项不展开。下面保留历史节点。


2026-10-04 当前增量：[持久后台扫描流程](PLANS/project-import-startup-scan-flow-verification.md)已接真实创建/状态/request 找回/取消，原同步检查也经过同一准入；V21 已应用。最终 344 项中 343 通过、1 未执行，真实 API 14 组通过，保留并修正重复请求锁冲突与测试签名失败。真实取消发生在 Python 前，不冒称辅助进程中取消已通过。任务重启/过期资源对账、停止故障、硬额度、只读 Agent、正式启动 UI、计划/安装/隔离多服务继续待开发；第一项未交付。下方是原节点。

2026-10-04 后续内部增量：[扫描任务账本预检](PLANS/project-import-startup-scan-ledger-verification.md)完成冻结身份、状态、请求幂等/准入与资源 CAS 核心；新增 13 项单元、5 组真实回滚 schema SQL 预检通过，组合 336 项中 335 通过、1 未执行。V21 未应用，尚未接任务 API/worker/取消/恢复，不是扫描流程交付；下面 V20 业务仍为当前运行能力。继续第一项，其余三项不展开。

2026-10-04 当前：应用级手动启动配置、版本 CAS 与上下文失效已接真实后端；已完成识别证据持久化，真实重启后按原会话找回，过期不返回候选。见 [配置证据](PLANS/project-import-startup-configuration-verification.md)、[完成证据](PLANS/project-import-startup-record-verification.md)：最终组合 323 项中 322 通过、1 未执行，真实 API 13 组通过，V19/V20 已应用。扫描过程任务/取消/资源恢复、正式启动 UI 联调、只读 Agent、计划授权及隔离多服务仍待开发；第一项未交付。下方同日记录保留各自节点，不覆盖本段当前边界。

2026-10-04 最新：[Python 只读识别局部证据](PLANS/project-import-startup-python-verification.md)，12 项标准库测试、6 项新 Reader 测试与真实 API 7 组通过；组合 302 项中 301 通过、1 未执行。异常工程只解析不 import，混合候选保留。持久扫描/上下文、手动配置、计划/授权及隔离运行尚未完成；第一项未交付。

2026-10-04 最新：启动同步只读检查与应用写入协调已接真实业务，见 [局部证据](PLANS/project-import-startup-business-verification.md)。真实 API 6 组与数据库锁 4 组通过；组合 296 项中 295 通过、1 未执行。不是持久扫描或可执行计划，前端未联调，Python、配置/授权、隔离运行继续待开发。

2026-10-04 当前启动节点：用户已确认识别/安装授权/多服务交互可以接后端。[只读核心局部证据](PLANS/project-import-startup-backend-verification.md)：33 项新用例通过，组合回归 276 项中 275 通过、1 未执行。仅实现内部识别/快照组件，尚无真实启动接口、业务协调、Python 识别、手动配置与执行计划；导入旧执行门禁仍关闭，第一项未交付。下方等待评审属于原时点记录。

2026-10-04 启动交互新增：[前端评审](PLANS/project-import-startup-frontend-preview.md)已复用原工作台提供可交互隔离样本，6 组真实浏览器交互、208 项已有前端回归及正式/隔离构建通过。等待用户确认识别/安装授权/多服务交互；相关后端尚未实现，不将模拟状态记为真实业务结果。

2026-10-04 当前增量：[来源请求恢复证据](./PLANS/project-import-source-recovery-verification.md)记录 owner 绑定的只读找回、刷新恢复与 Windows Git 只读对象清理；Java 225 项中 224 通过、1 未执行，前端 208 项通过。真实请求丢失/刷新/取消及已有 GitHub、文件夹回归通过；旧清理失败样本保留，完整进程树/硬资源限额及第一项整体验收仍未通过。识别/安装/服务选择下一步仅交互预览，后端待评审。

2026-10-04 正式前端增量：[Git/凭据页面联调](PLANS/project-import-git-frontend-verification.md)已接真实接口；双平台浏览器各 8 组、取消时序 3 组、文件夹 6 组局部检查通过，当前前端 203 单元测试与构建通过。不是整项交付：来源失联找回/恢复、硬隔离、识别/安装/多服务及第一项全面验收继续待开发；有效私有成功验收暂缓。旧执行门禁保持。

2026-10-04 后续增量：[私有凭据读取](PLANS/project-import-git-private-verification.md)已接来源与提交资格；无效 Token 的真实双平台负例及受控凭据任务联动通过，有效私有仓库成功验收按用户要求暂缓。[固定范围重建](PLANS/project-import-git-range-verification.md)已提供离线固定快照重读接口，正式 Git 前端仍待联调；214 项 Java 局部回归通过。一次文件夹提交故障未确认原始原因，失败证据保留，重跑通过不等于已经修复。识别/安装/多服务、第一项独立整体验收未完成；其余三项不展开。下方按原时点保留。

2026-10-04 Git 业务增量：公开来源已接入持久任务、固定 commit 对象读取、同 UUID 私有封存及最终应用提交；V16/V17 已执行。GitHub 1 文件、Gitee 394 文件的最终目录与哈希/归属各完成 32 项真实局部检查，见[公开 Git 业务证据](PLANS/project-import-git-public-verification.md)。私有认证、正式 Git 前端、过滤重建、识别/安装/多服务和第一项独立整体验收仍未完成，不解锁旧执行路径。下方统计属于各自历史时点。

2026-10-04 凭据增量：用户已批准精简后的 Git/凭据界面接后端。[凭据后端阶段证据](PLANS/project-import-credential-verification.md)记录多凭据加密存储、备注/替换/删除、共享提交资格及 34 项真实接口/数据库/任务局部检查；V15 已执行。Git 仓库读取、正式 Git 前端联调、识别/安装/多服务及整项仍未完成；以下“Git 界面待确认”保留为历史节点。

2026-10-04 后续增量：文件夹真实应用提交、保护快照、读取门禁、显式恢复和正式前端工作台跳转已实现并完成局部真实验证，见[应用提交与联调证据](PLANS/project-import-publication-verification.md)。当前 142 项 Java、4 项前端单元、55 项真实接口、37 项受控恢复检查及 6 组真实浏览器检查通过；不是完整矩阵通过。V14 已执行。导入应用的旧 Agent/运行/发布入口明确阻断，待隔离与授权实现；Git/凭据新界面按反馈精简后仍待确认，其后端、识别、安装、多服务及整项验收未完成。下方增量按原时点保留。

2026-10-04 增量：用户已确认文件夹前端并授权按设计开发。[后端测试计划](PLANS/project-import-backend-test-plan.md)覆盖 58 组；已实现清单校验、真实私有上传任务、内容封存、取消/超时及长期偏好。最终 134 项 Java 检查和 47 项真实 API/磁盘/重新登录检查通过，见[暂存与偏好证据](PLANS/project-import-backend-upload-verification.md)。V12/V13 已执行；最终应用提交、恢复门禁、前端业务联调、Git/安装/运行仍未完成，整项未交付。私有仓库验收按用户要求暂缓。历史“等待开发命令”仅对应原时点。

2026-10-03 增量：[文件夹导入正式前端接入](PLANS/project-import-frontend-integration-v1.md)已加入导航、首页及正式路由；与隔离预览共用表单。实际提交和偏好服务尚未开通，不代表真实导入业务完成。下文 2026-10-02 的源码核对按原时点保留。

核对日期：2026-10-02。源码基线：`code_board` / `5913979a15cb3b333f095143989f6572ec9db02d`。
本文是源码核对和后续计划，不是功能实施或运行验收报告。文档提交将产生新版本，不改变上述源码基线。

## 目标与边界

“应用魔方”围绕代码生成与调试，帮助开发者把 Demo 逐步改造成满足实际业务和部署要求的可交付项目。

- 保留新项目生成能力，优先增量修改已有代码。
- 生成完成、编译通过、接口成功、容器启动、业务验收与发布分别判断，不自动宣称“企业级”。
- 权限、校验、异常处理、数据一致性、幂等、资源、配置与可观测性按业务缺口补齐，不默认增加复杂组件。
- 并发、QPS 和性能测试按风险选择；图谱、文档和问答服务于代码理解和调试，海报等扩展场景暂缓。
- 本次只同步文档并提交本地 Git；功能开发等待用户后续命令。

## 当前源码链路

1. `AppChatView.vue` 使用 `agentStreamTransport.ts`，优先连接 `/api/ws/agent`，失败时降级为 `/api/chat/generate/stream`。
2. Java 的 `AgentRunRequestFactory` 校验应用归属、确定工作区；`AgentRunService` 使用应用级串行协调、事件过滤与运行持久化。
3. `AgentWorker` 以 stdio 驱动 `agent/app.py` 常驻进程，NDJSON 承载请求和事件。
4. `agent/core/runtime.py` 选择 Backend、任务权限与持久化，`agent/server.py` 装配 DeepAgents / LangGraph Agent。
5. 文件工具操作项目工作区；流式事件回到前端，运行与对话记录由 Java 落库。

主配置为 PostgreSQL + Redis。Python checkpointer / store 单独配置 PostgreSQL 连接，失败时存在内存降级路径，不应把配置存在当作持久化已生效。
本次只读监听检查未发现 `8124` 服务，不启动、重启或提交真实生成请求，因此没有本次运行链路验收。

## 源码已有、未验证与待开发

| 项目 | 源码状态 | 验证边界 / 差距 |
|---|---|---|
| 新项目生成 | 新应用统一 Auto；旧类型兼容 | 本次未运行模型生成 |
| 增量修改 | 已有产物进入 edit 模式，复用文件工具 | 尚需任务级修改范围、验收条件和补丁审查绑定 |
| 工作区 | 描述、文件树、读取、保存版本校验、下载 | 未发现完整的已有项目导入和接入基线入口 |
| 运行管理 | 规格识别、Docker 进程、就绪探测、日志、指标 | 本次未启动容器；依赖/测试识别不等于可复现运行基线 |
| 修复入口 | 异常记录、诊断提示词、前端转入原生成对话 | 未发现验证驱动的完整收尾；`markRepaired` 已定义但未发现调用方 |
| 工作区版本 | 路径、大小、修改时间组成指纹 | 适合版本落后提示，不是源码内容级快照或交付证据 |
| 知识系统 | 独立源码快照、异步分析、只读问答 | 本次未验收；不能用知识快照替代修复任务的工作副本 |
| 图谱 | 模型输出节点、关系及证据标签 | `CODE_EVIDENCE` / `AI_INFERENCE` 标签不等于确定性提取；需评估解析与校验能力 |
| 发布 | 静态制品发布与撤回 | 不支持需要常驻服务的后端/全栈长期发布 |

关键依据：

- `src/main/java/com/dbts/glyahhaigeneratecode/agent/AgentRunRequestFactory.java`
- `src/main/java/com/dbts/glyahhaigeneratecode/agent/AgentRunService.java`
- `src/main/java/com/dbts/glyahhaigeneratecode/runtime/RuntimeManager.java`
- `src/main/java/com/dbts/glyahhaigeneratecode/runtime/WorkspaceVersioner.java`
- `src/main/java/com/dbts/glyahhaigeneratecode/controller/AppRuntimeController.java`
- `src/main/java/com/dbts/glyahhaigeneratecode/runtime/RuntimeIncidentService.java`
- `src/main/java/com/dbts/glyahhaigeneratecode/knowledge/KnowledgeTaskService.java`
- `agent/core/runtime.py`、`agent/backends/docker_sandbox.py`

## 执行与结果隔离

当前生成沙箱和运行容器都有工作区读写挂载；容器限制不等于原项目保护。
后续需先建立独立副本，再执行导入项目的脚本。复用现有路径、归属、串行协调和资源约束，不绕过它们另开执行通道。

运行容器已有 CPU、内存、进程数、权限及回环端口限制；当前 bridge 网络不阻断出网，仍需按首阶段任务明确执行权限与网络范围。
结果需关联项目、任务、代码版本、输入和执行环境；知识、运行及修复证据不可串写或用新源码冒充旧版本。

## 分阶段计划（尚未实施）

1. **窄链路验证**：导入一个 Demo、创建隔离副本、记录环境与运行基线、复现真实问题、最小修复、回归验证、交付可回放证据。
2. **固化增量开发流程**：明确范围与验收条件，绑定任务和代码版本，支持补丁审查、失败恢复及回退，保留新项目生成。
3. **按业务补工程能力**：优先处理权限、校验、异常、事务与一致性、重复请求、资源释放、配置和可观测性；没有实际问题不堆复杂技术。
4. **完善验证与交付**：按风险选择单元、集成、端到端、故障、并发或性能测试；核对最终文件和业务状态，交付运行、部署、回退说明与剩余风险。
5. **辅助理解**：先评估确定性代码关系提取，再完善事实/推断标记、文档与只读问答，不占用首阶段修复主线。

## 第一阶段验收清单

- [ ] 固定 Demo 来源、代码版本、业务预期、一个真实问题及预期修复范围。
- [ ] 原项目保持不变，修复副本独立；过滤凭据与无关数据，明确执行权限、资源和网络范围。
- [ ] 记录目录结构、依赖与锁文件、启动/测试命令、环境及配置要求；无法识别或无法执行的明确标记。
- [ ] 建立可复跑基线，保存命令、输入、日志、退出码和实际结果。
- [ ] 同一输入稳定复现问题，明确预期与实际差异；基线可以包含已确认的失败，不要求假装全绿。
- [ ] 根因对应具体代码或运行证据；补丁最小，不重新生成整个项目。
- [ ] 同一失败用例修复后通过，相关业务回归通过；检查最终文件和业务状态，不只看端口或编译。
- [ ] 修复前后结果绑定项目、任务及代码版本；不能将模型自评作为通过依据。
- [ ] 交付补丁、复跑步骤、验证证据、剩余风险和回退方式；未验证项明确列出，不默认发布生产。
- [ ] 从原始基线重新创建副本并应用补丁，能够重复得到验证结果。

实施前还需确定 Demo 和具体问题；当前不自动选题或进入开发。

## 文档与证据口径

本次没有运行编译、自动化测试、模型、浏览器或容器任务。文档检查和 Git 提交不等于业务验收。
`AUTO_WORKBENCH_MIGRATION.md`、`AUTO_WORKBENCH_ALIGNMENT_20260919.md` 保留历史实施及测试记录，只对应当时版本与环境。
`PLAN.md` 的 Java 会话记忆 V4 已标记为历史计划；其他历史设计不在本轮全面重写。
