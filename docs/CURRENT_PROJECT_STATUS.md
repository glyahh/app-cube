# 当前项目状态与开发主线

2026-10-09 用户批准最终范围收口：**第一项“已有项目导入”彻底完成，状态 COMPLETE**。最终范围为已通过的核心能力＋5组关键使用风险，18/18检查点、50/50断言、6/6交付包以及52＋4项专项断言全部通过，源码摘要 `e9302662502a8cf3fbdd38bbf3150bd93cb9d9e120f96f95c6f23416d47ba3d9`。未完成验证的扩展框架、多浏览器/系统、长期压力、远端私库权限矩阵及原856条全量验收要求已按用户决定从当前要求、待办和延期清单删除，不再阻塞本项；Vue＋Java/Maven已验收保留。保留秘密隔离、权限、资源限额、Java独立校验和恢复保护，不把删除项记为PASS。当前范围无剩余任务；重新加入被舍弃内容须另立需求。详见[最终范围与完成记录](PLANS/project-import-item1-final-scope-20261009.md)。本次仅文档收口，未改源码、未运行新验收。

## 历史节点（不再决定第一项当前状态）

2026-10-09 第一项5组关键使用风险完成：当前源码摘要 `e9302662502a8cf3fbdd38bbf3150bd93cb9d9e120f96f95c6f23416d47ba3d9`。Vue＋Java/Maven真实浏览器写入读回与停止再运行、依赖就绪及package/lock/pom失效、手动服务保存恢复/正式拒绝Agent、失败单项重试、重复运行与启停竞态全部PASS；主体52项、浏览器补充4项。真实修复启动中停止误报NOT_RUNNING、迟到启动恢复READY；新增运行互斥、取消及最终提交检查，停止未确认仍保留资源。受影响Java55、验收器6通过；同版本核心Java117/14类零跳过，Python秘密/持久化49、前端定向46及类型检查通过。当前核心门禁 **COMPLETE（核心交付范围）**：18/18检查点、50/50断言、6/6交付包，错误为空；正式SSE与WS各5次模型调用取得真实发布及业务读回，标准重启后8124就绪。专项主体及浏览器补充252项独立清理读回通过，核心保留夹具另按确切身份清理。原856条完整矩阵仍 **FULL_ACCEPTANCE_PENDING**，第一项完整验收仍 **NOT_COMPLETE**；多浏览器/系统、长期压力、远端私库权限矩阵及其它扩展框架未由本轮完成。Vue＋Java/Maven不再属于未验证延期项。第3组正式拒绝使用空Python声明的明确受控夹具变换，未放宽Agent准入。证据目录 `D:\codex\WorkDocument\2026-10-09\item1-five-risk`。见[专项报告](PLANS/project-import-five-risk-20261009.md)。以下为历史节点。

2026-10-09 第一项核心交付范围收口：同一最终源码摘要 `e7fb2cf812f95bc2e5478eca65d339af47547120c5853115478c562f6888a546`，运行编号 `item1-core-final-20261009`。`scripts/verify-item1-core-complete.py` 返回 **COMPLETE（核心交付范围）**，18/18 检查点、50/50 断言、6/6 交付包，错误为空。该版本上的正式 SSE（6 次模型调用）和 WebSocket（5 次模型调用）完成真实编辑、Java 独立发布、秘密隔离、产物与源码分离；Vue＋Flask 缺陷经模型修复后旧计划失效，重新批准并完成安装/构建、双服务运行和浏览器业务读回。拒绝或失败的安装终态可以进入“不启动服务”的运行解释，进行中的安装和取消结果仍然挡住运行。原 856 条矩阵仍 **FULL_ACCEPTANCE_PENDING**，第一项完整验收仍 **NOT_COMPLETE**；扩展框架、Vue+Java/Maven、多浏览器/系统、长期压力和远端私库权限矩阵保持延期。证据目录 `D:\codex\WorkDocument\2026-10-09\item1-core-closure`，门禁结果 `core-gate-final.json`。回退只反向应用 `source-delivery\round.patch`，不要用含基线脏改动的 `HEAD-to-current.patch` 回退。以下为历史节点。

2026-10-09 用户确认删除开发指标：**“原pip HTTPS正文读取超时的底层原因调查/针对性修复”已从第一项开发指标、验收条件、待办和延期清单中删除（REMOVED_FROM_SCOPE_BY_USER）**，不是DEFERRED，不再作为第一项完成或Bug关闭的阻塞项，也不自动恢复为后续任务。安装超时机制修复保持COMPLETE / 可以关闭；pip正常安装、安装失败明确终态、超时/取消及安全清理仍属于原有功能验收，不删减。原失败证据保留，仅作历史事实，不声称传输故障已修复。第一项其余有效指标与整体状态不因本次删除自动改变。当前范围见[测试矩阵](PLANS/project-import-test-matrix.md)，实施证据见[最终报告](PLANS/project-import-install-timeout-final-20261009.md)。以下为历史节点，旧pip根因阻塞/暂缓口径均不再生效。

2026-10-09 安装超时最终版本收口：源码摘要 `6711e80c644dd9cd9b630dbaaee9cd74fd9e5aa9704e68852cb058d91a8c4d81`。持久安装任务、准入无ledger窗口取消/删除保护、同requestId/approved幂等、原请求刷新恢复、真实失败及重启中断终态已加固；V39已应用。最终同版本8份专项收据PASS，其中两次新应用Vue＋Flask安装/构建/双服务/真实浏览器读回/停止完整闭环通过；65秒夹具长任务在客户端直接断连后128.344秒SUCCEEDED，注入等待不冒充自然慢点。Java68通过/4跳过，前端18通过、typecheck/build通过，脚本2通过、真实PG约束4通过，标准后端重启及8124就绪确认；这些是本轮局部结果，不沿用下方旧全仓数字。原pip read timeout底层传输原因仍未确认，因此Bug不能关闭；原失败应用、副本、卷与日志保留。第一项仍NOT_COMPLETE，本轮未调用模型、不计WS/真实模型编辑验收。以下较早条目只作历史（其中“需要模型才能验双服务”的旧限制已由本轮真实无模型业务验收纠正）。当前事实见[最终报告](PLANS/project-import-install-timeout-final-20261009.md)。

2026-10-09 安装任务异步化增量：安装从同步执行改为受理返回任务身份、后台单线程执行、前端 2 秒轮询；取消、删除保护与启动恢复对账已接；V38 已应用。三次真实 Vue＋Flask 安装（每次新应用、新计划）全部 SUCCEEDED，npm 13.4–35.2 秒、pip 8.8 与 22.9 秒，受理请求 0.063 秒返回；**2026-10-08 记录的 pip 正文读取超时未复现，底层原因仍未确认，不声称网络根因已修复**。本轮另修三个诊断缺陷：`failureStage` 把容器删除后的 inspect 失败误当安装失败阶段、阶段汇出过宽（280 条，含执行引擎内部 span）、外层命令阶段标签错位把 pip 标成 `NPM_INSTALL`。后端 821 项 0 失败/10 跳过，前端 227 项 0 失败加类型检查与构建通过，标准重启正常。**真实双服务闭环验收（计划第六节）未做，需要真实模型与浏览器环境；第一项仍 NOT_COMPLETE。** 证据与重放见[本轮记录](PLANS/project-import-install-timeout-fix-20261009.md)。以下为历史节点。

2026-10-08 审查修复后版本：空秘密值污染隔离源码、启动前失败无法收口 Worker 两处缺陷已修复；Python79、Java18（含真实 PostgreSQL）、WSL4 共101项通过，标准重启/就绪通过。当前源码摘要 `baa554170c1a29d952c587dd970f725e3d742449bd7f35cef2d1bd1a5da31841`；正式SSE真实模型/Java发布/秘密与记忆核对/产物保留通过，但额外 Vue＋Flask 回归安装请求60秒超时、WS回归 `JAVA_SUCCESS_MISSING`，两轮独立清理均确认。**当前版本完整核心复验未通过，不能沿用下方旧版本 COMPLETE；原完整矩阵仍 FULL_ACCEPTANCE_PENDING。** 本轮证据见原交付目录同级 `review-fixes-20261008/review-fixes.md`，失败原始记录均保留。

2026-10-08 第一项核心范围收口：正式审计事件、Vue＋Flask 业务修复、文件夹/GitHub 来源与凭据、安装/多服务、SSE/WS Agent 编辑、秘密隔离、取消/冲突、发布中断恢复、Worker 重启回收、真实 Docker、正式 Chromium 和非导入回归均有当前版本证据。核心账本 `item1-real-vue-approved-20261008`：50/50 断言、18/18 检查点、6/6 包；严格门禁 `core-gate-final.json` 返回 **COMPLETE（核心交付范围）**，源码摘要 `d4fb76a17cdd328b20e85719abd1334172768580774fe809d7378dfe9295c25a`。原完整 856 条矩阵仍 **FULL_ACCEPTANCE_PENDING**，延期真实远端私库权限矩阵、扩展框架、多浏览器/系统、长期压力等，不冒充核心完成。交付目录：`D:\codex\WorkDocument\app-cube-item1-implementation-20261006\tonight-core-delivery-20261007\two-hour-core-20261008\core-closure-current`。原始浏览器 runner 的 `FAIL/1` 保留为验收脚本选择器边界；规范化收据仅引用其五项实际 PASS 子断言和独立普通项目 PASS。

2026-10-08 当前版本复核：已修复正式 SSE/WebSocket 审计事件被展示层过滤的问题，并修复 Vue＋Flask 夹具服务目录映射错误。当前源码摘要 `54bd10cac2d5f420b29ad94183f250032736a8029e1ad4c80b422b789951db53`；正式 SSE/WS、真实模型、Java 发布、浏览器业务回读、取消保护、发布中断恢复和 Docker 硬资源证据均为 PASS。当前核心账本实销 `4/18` 检查点、`18/50` 断言、`0/6` 包，门禁仍为 `NOT_COMPLETE`；其余导入/运行/兼容/重启检查尚未用当前版本证据销账，不能标记 COMPLETE。证据：`two-hour-core-20261008/business-clean`、`cancel-current`、`publication-current`、`resources-current`、`core-gate-final.json`。

2026-10-08 正式修复与发布恢复增量：本轮 `business-60` 已完成固定 Vue＋Flask 浏览器缺陷复现、正式 SSE 真实模型修复、Java 发布、旧计划失效、重新批准双服务运行及浏览器业务读回，WebSocket 合法编辑同时通过，共12次真实模型调用，脚本退出0。`publication-61` 实际在旧目录已移动、数据库阶段尚未提交时中断本轮后端；标准重启恢复完整原目录，编辑账本 CANCELLED/DONE、卷释放，未提前发送成功，退出0。以上夹具产品清理确认；已新增独立资源读回探针，仍需在最终版重放核验。固定核心门禁已建立，18检查点、6交付包不缩减；缺证据/旧版本/删范围/虚构清理拒绝测试通过。V37正在补执行产物独立登记与保留，正式模型产物验收进行中。**第一项仍 NOT_COMPLETE；18项未逐项销账，不能将上述局部闭环写成18/18。原856项仍 FULL_ACCEPTANCE_PENDING。** 见[本轮记录](PLANS/project-import-formal-business-recovery-20261008.md)。以下为历史节点。

2026-10-07 登记容器真实启动增量：平台绑定原Docker ID及完整身份/隔离配置，Java实际启动和内核硬CPU/内存/进程/swap核验后才CAS EDITING；WSL MemorySwap记录不可靠已定位，实际强制并读回swap=0。当前Java67、Linux编辑23/卷30、修复后实际链14、重启后正式入口7通过。第42轮恢复三个初始夹具并单独确认清理；最终版第四个干净夹具第43轮标准重启及全部本轮编辑任务清理通过，PID52860。**正式Worker/持久连接/模型、授权工具、最终发布及未决发布恢复仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-container-start-20261007.md)。以下为历史节点。

2026-10-07 当前执行者封存增量：生产Java编辑/封存CAS及实际停止→持久停止证明→SEALING→独立候选验证已贯通真实数据库/Docker，候选实际VERIFIED、发布仍NONE；正式取消清理先停容器再释放，停止失败保留资源。真实Java1（包含于链路14）、Java65、重启后正式入口7项通过；第41轮标准重启已释放未发布候选/1GiB卷/loop/view，本轮清理确认，PID27700。**候选为未编辑基线，正式Worker/真实模型、授权文件修改、最终发布及未决发布恢复仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-java-seal-20261007.md)。以下为历史节点。

2026-10-07 Java实际创建增量：生产冻结准备/业务门禁/路径/数据库应用锁/创建服务已贯通真实数据库与Docker，无账本或平台替身；实际1GiB卷、隔离视图、容器意图及ID绑定，重复拒绝不误取消。Java测试真实执行者退出后，正式重启自动停止清理并保留审计。当前真实Java1（包含于链路13）、Java回归60、最终正式入口7项通过，第40轮后PID4700，本轮资源清理确认。**正式Worker仍未调用此创建链，实际模型编辑、最终发布及未决发布恢复仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-java-create-20261007.md)。以下为历史节点。

2026-10-07 长期容器审计增量：V34已正式执行，容器创建前登记/实际ID不可替换/停止证明长期保留；未确认停止禁止封存、释放或关任务。正式恢复先保存真实停止证明再释放卷，实际卷及本轮应用删除后审计仍保留。新增Java当前执行者创建准入服务，正常创建目前平台替身，正式Worker尚未调用。当前Java60、Linux编辑17、真实审计恢复12、最终正式入口7项通过，第39轮后PID56832，本轮夹具清理确认。**Java创建服务真实平台联调、正式Worker/模型、最终发布及未决发布恢复仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-container-audit-20261007.md)。以下为历史节点。

2026-10-07 平台容器自动停止增量：已实现私有卷内创建前PLANNED登记及实际容器ID绑定、完整身份/挂载核对、STOPPING→实际退出/删除确认→REMOVED；正式启动恢复先停止已登记容器再释放。实际第37轮自动停止11、Java57、Linux编辑17/卷30、重启后正式入口7项通过，本轮夹具清理确认，PID50648。**Java独立容器ID审计/正式Worker创建与结束、正式模型、最终发布及未决发布恢复仍未完成**；平台私有登记随卷释放消失，不等于业务长期审计。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-container-stop-20261007.md)。以下为历史节点。

2026-10-07 真实遗留容量卷恢复增量：已实际证明正式启动撤销旧任务后，残留运行容器阻止释放且保留卷/私有视图及应用槽位；精确删除本轮容器后，第二次正式启动释放实际卷/loop/视图并持久CANCELLED/DONE，原内容不变。修正WSL验收losetup路径后完整重跑，当前真实恢复11、Java56、最终正式入口7项通过，第36轮后PID14328，本轮夹具清理确认。**生产自动停止旧容器、Worker/正式模型、候选最终发布和未决发布恢复仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-volume-restart-20261007.md)。以下为历史节点。

2026-10-07 正式启动恢复增量：已接旧编辑资格CAS撤销、应用锁内重读和无发布意图资源恢复；活进程、未决发布和释放失败保留占用，不续跑。实际重启发现并修复无资源取消误记FAILED，真实启动无资源旧任务已收口CANCELLED。当前Java56、修复后第32轮标准重启恢复8项及重启后正式入口7项通过，本轮夹具清理确认，PID4728。**真实存量容器/卷故障恢复、未决发布恢复、Worker/模型和最终发布仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-edit-recovery-20261007.md)。以下为历史节点。

2026-10-07 真实Java持久验证增量：新增独占事务schema的PostgreSQL联调，生产Java账本实际写入VERIFIED并拒绝摘要错配、旧revision、上下文/所有者撤销和重复提交；从干净身份贯通验证服务→真实Java账本，核验后才写入，父任务仍SEALING/NONE。平台/业务门禁/协调锁仍为替身，不是正式模型或Docker全链路。当前Java52、第30轮标准重启后正式入口7项通过，临时schema回滚消失及本轮夹具清理确认，PID25648。**旧执行者主动恢复、Worker结束/模型、发布仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-verified-jdbc-20261007.md)。以下为历史节点。

2026-10-07 当前执行者增量：验证服务已核对当前PID＋启动时刻，旧执行者/PID复用、提交/终态/恢复/取消不得重入或借验证失败改状态；失败只处理当前SEALING/CANCELLING。新增完整正常服务单元（实际Java源快照，平台/账本替身），核验后才持久验证且顺序正确。当前Java51、第29轮重启后正式入口7项通过，本轮夹具清理确认，PID26612。**真实业务正常写入、Worker结束/模型、发布和主动重启恢复仍未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-current-executor-20261007.md)。以下为历史节点。

2026-10-07 验证服务增量：已新增应用协调锁内完整Java源版本/归属复核→平台封存→实际Frozen/候选验证→持久VERIFIED服务及CAS准入；失败记录RECOVERY_REQUIRED保留资源，不发布。修正平台声明额度与实际额度混淆，实际限额不扩大。当前Java48、真实PG约束88、真实卷13（含生产Java读验证1）、第28轮重启后正式入口7项通过，本轮4卷及容器清理确认，PID56636。**新服务正常完整业务/Java持久写入尚未联调、Worker结束尚未调用；旧执行者恢复、正式模型与发布恢复仍待接**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-verified-service-20261007.md)。以下为历史节点。

2026-10-07 真实来源/视图增量：封存固定完整视图摘要，平台source/view库存及原文件读通道、Java FilesAccess桥接已实现。生产Java Engine真实读取私有状态/来源/视图并构建Frozen、核验实际候选字节，联调1项通过（合成身份/资格，不是正式业务准入）。当前Java单元43、Linux14、真实卷13、第27轮重启后正式入口7项通过，本轮4卷及容器清理确认，PID37172。**正式Java原版本/业务资格复核、freeze/verify→VERIFIED、正式模型与发布恢复尚未接**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-source-view-read-20261007.md)。以下为历史节点。

2026-10-07 Frozen构建增量：Java已严格解析私有状态、核对独立来源/实际授权视图，重建基线并复用独立恢复器生成Frozen；未授权源码差异拒绝，产物不提升为授权，末尾重读范围/资格。当前Java43、第26轮标准重启后正式入口7项通过，本轮夹具清理确认，PID26992。**核心来源/视图仍为字节夹具；实际平台桥接、Java原版本复核及生产freeze/verify/VERIFIED、正式模型与发布恢复尚未接**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-frozen-core-20261007.md)。以下为历史节点。

2026-10-07 秘密恢复增量：候选已修正占位符数量不变但秘密字段被换名/注释仍放行的缺口，重新核对冻结源、基线、绑定及秘密所在完整行。Java独立原内容/授权字节恢复摘要核心已实现，未从manifest反填。当前Java38、Python全量469（含真实Docker、零跳过）、Linux13、真实卷11、第25轮重启后正式入口7项通过，本轮4卷及容器清理确认，PID24544。**完整Frozen构建、独立源读取接入、正式校验/VERIFIED、正式模型与发布恢复尚未接**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-secret-restore-20261007.md)。以下为历史节点。

2026-10-07 实际候选目录增量：平台inventory读取已实际遍历文件/目录和字节，不从manifest反填；Java FilesAccess桥接已接严格实际目录解析、独立完整摘要及文件流。额外文件、额外空目录、同长度字节篡改均拒绝。当前Java33、Linux13、真实卷10、第24轮标准重启后正式入口7项通过，本轮3卷及容器清理确认，PID26440。**尚未接正式校验调用、独立秘密恢复、VERIFIED、正式模型与发布恢复**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-candidate-inventory-20261007.md)。以下为历史节点。

2026-10-07 候选私有读取增量：受限平台candidate-read及Java文档/文件分块客户端已实现，绑定已封存身份、三项摘要及每块执行资格，私有文档独立SHA256验证，单块256KiB且不进入模型或公共接口。当前Java31、Linux候选12/既有卷30、真实卷9、第23轮标准重启后正式入口7项通过，本轮3卷及容器清理确认，PID31288。**完整实际目录桥接、独立秘密恢复、VERIFIED、正式模型与发布恢复尚未接；文件完整摘要仍须由独立校验器计算**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-candidate-read-20261007.md)。以下为历史节点。

2026-10-07 Java候选校验核心增量：已实现严格清单/身份/独立授权差异校验、Python规范完整摘要重算及读取前后范围/真实字节流校验。当前受影响Java28、第22轮重启后正式入口7项通过，本轮夹具清理确认，PID47768。**生产平台读取桥接、独立秘密恢复、VERIFIED写入、正式模型与最终发布恢复尚未接；核心字节流夹具不等于正式候选验证**。检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-candidate-verifier-20261007.md)。以下为历史节点。

2026-10-07 候选账本增量：V33已实际应用，Java候选PLANNED/SEALED/VERIFIED/RELEASED持久基础及回执身份/授权摘要校验已实现；数据库阻止平台SEALED直接提交、候选未释放关闭任务，正式清理已核对候选占用。当前PostgreSQL88、Java20、第21轮重启后正式入口7项通过，本轮夹具清理确认，PID29244。**正式模型结束接入、Java独立内容校验、候选搬运及最终发布恢复仍未完成**。检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-candidate-ledger-20261007.md)。以下为历史节点。

2026-10-07 候选封存增量：受限卷edit-candidate已接真实容器消失确认、SEALING意图、授权候选/原秘密恢复、私有manifest及sync后SEALED摘要；封存后禁止继续文件编辑。Java候选回执身份/路径/摘要客户端已实现。当前Linux文件系统39、Java18、真实卷/候选8、第20轮重启后正式入口6项通过，本轮3卷及容器清理确认，PID19440。**Java候选持久登记、独立内容校验、正式模型、最终发布恢复仍未接**。检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-edit-candidate-20261007.md)。以下为历史节点。

2026-10-07 Linux运行环境增量：现有uv.lock生产依赖已在独立WSL root环境实际安装并导入，原生Docker硬swap准入已接本机Unix socket和PID/cgroup读回校验。当前Python466、原生swap30（含3项真实Docker）、第19轮标准重启后正式入口6条通过，夹具清理确认，PID29056。**原生数据库连接失败，专用规则尚未应用；正式模型工具、Java候选发布和恢复仍未接**。检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-linux-worker-20261007.md)。以下为历史节点。

2026-10-07 授权工具增量：受限卷平台file-edit已复用SecretFreeView完成结构化修改、持久授权及私有映射恢复；真实候选恢复原秘密，Shell差异不能补授权或合入。当前Python462、Linux30、真实卷/工具9、第18轮标准重启后正式安全准备6条通过，夹具清理确认。**模型文件工具和Java最终候选发布仍未接**，检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-edit-tools-20261007.md)。以下为历史节点。

2026-10-07 正式隔离视图增量：Java SSE/WS已经调用edit-view并在复制前登记视图与私有映射，复核完整来源和资格后封存；V32已执行，清理先核对卷消失再释放子账本。当前Java19、PostgreSQL64、第17轮标准重启后正式双通道6条通过，夹具清理确认，PID27280。**正式模型仍拒绝，文件工具、授权候选、合入和恢复未接**。检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-formal-view-20261007.md)。以下为历史节点。

2026-10-07 秘密隔离副本增量：平台edit-view已可在已登记受限卷内复用SecretFreeView创建去秘密副本，只把view子目录交给UID65534容器；原值映射仍在同卷root私有文件，不进入receipt或模型挂载。当前Python全量460、Linux29、真实容器边界6条通过，标准重启后正式安全拒绝5条通过，PID54460。**Java尚未调用edit-view，正式模型编辑、授权候选、原子发布和恢复未完成**。固定检查点1/38、整包0/6，第一项NOT_COMPLETE。见[本轮记录](PLANS/project-import-edit-view-20261007.md)。以下为历史节点。

2026-10-07 正式硬卷增量：SSE/WS编辑准备已接run独占1GiB容量卷、创建前持久登记及失败释放；V31已执行。当前受影响Java19、Linux26、数据库49、真实Docker8、标准重启后正式双通道5条全部通过，最新后端PID28740。秘密隔离副本、实际模型编辑、候选原子合入与重启恢复仍未接；卷为空、Worker继续明确拒绝。完整门禁856条仍待逐行验收，固定检查点1/38、整包0/6，**第一项NOT_COMPLETE**。见[本轮记录](PLANS/project-import-edit-volume-20261007.md)。以下为历史节点。

2026-10-07 正式冻结准备增量：完整验收分母已固定为57组、345个子场景、856条要求层，门禁拒绝删项后重算摘要；A1销项，固定检查点1/38、整包0/6。正式SSE/WS已在应用锁及数据库工作区锁内冻结完整内容并实际写入V30编辑账本；真实双通道4项验收确认两条准备记录失败收口、源码未变及本轮资源清理。当前Java全量734项中727通过、7跳过；跳过不算必测通过。**实际受限隔离卷、授权候选、Java原子合入及重启恢复仍未完成，Worker继续明确拒绝编辑；第一项NOT_COMPLETE**。证据及重放见[本轮记录](PLANS/project-import-frozen-preparation-20261007.md)。以下为历史节点。

2026-10-07 下一项实施增量：新增V30导入Agent编辑账本与冻结身份/CAS撤销基础；正式SSE/WebSocket请求标记当前导入来源，Worker明确拒绝原发布目录直挂，不走占位成功或普通Shell。Java当前606项中600通过、6跳过，Python457项全部通过；真实PostgreSQL约束26项与标准重启后的真实文件夹导入→SSE/WebSocket安全拒绝验收通过，测试资源清理确认、历史审计保留。**正式隔离副本准备尚未接账本，实际编辑、候选合入与发布恢复仍未完成**，此增量不代表修复成功。见[实施记录](PLANS/project-import-implementation-20261007.md)。第一项仍 **NOT_COMPLETE**；下方是历史节点。

2026-10-07 本轮继续开发：主 Agent Docker 已新增实际内核硬 swap 准入、WSL 身份校验补救、每次命令复核、停止/删除未确认保留资格及旧引用拒绝。修正新增运行时反例的环境污染后，当前 Python 全量452项通过（零失败、零跳过），其中3项真实 Docker 验证包含正式 Python入口拒绝且不装配模型，测试容器删除已确认。第9轮标准重启后端 PID47236，正式API第6轮11项通过且自有夹具删除确认。证据见[实施记录](PLANS/project-import-implementation-20261007.md)。以上停止失败保护仅在进程内；正式Java隔离合入、硬磁盘/持久恢复、Vue＋Java、私库与完整验收仍待完成。**第一项 NOT_COMPLETE**，下方是历史节点。

2026-10-07 第一项收口计划实施中：已按用户确认接入 GitHub-only 正式界面，保留 Gitee 历史解析/密文并拒绝新导入与凭据修改；V29 已通过标准后端启动应用，正式 API、浏览器与 GitHub 公开来源局部验收通过。新增文件工具防止 Shell 差异转为授权补丁，当前 Python 全量 426 项通过；硬 swap 准入已去除宿主无 swap 放行，含真实 Docker 的 24 项验证全部通过（零跳过），第7轮标准重启后正式 API 复验通过。严格完成门禁仍返回非零；本轮清理预览发现未确认终结记录，没有强制删除。正式 Agent 隔离合入、硬资源、Vue+Java、真实私库和完整子场景验收尚未完成。见 [本轮实施记录](PLANS/project-import-implementation-20261007.md)。**第一项 NOT_COMPLETE**，下方均为历史节点。

2026-10-06 本轮完整收口：自动回归、文件夹导入、GitHub/Gitee 公开来源、静态/Flask/Express/双服务/Vue/Spring Boot/Vue+Python、多种秘密边界、日志安全、Agent E04/E05 均以当前源码重跑通过；证据见 `PLANS/project-import-item1-current-run-20261006.md`。Vue+Java 组合因受控 Maven 依赖下载超过 905 秒记 `FAIL/TIMED_OUT`，私有仓库 G02/G04 按用户决定记 `BLOCKED/NOT_RUN`；Docker 仍报告无 swap limit support。后端最终重启脚本等待超时但随后已在 8124 启动完成。**第一项仍 NOT_COMPLETE**。

2026-10-06 已知秘密输出防护增量：[配置实际值与工具输出证据](PLANS/project-import-secret-values-verification.md)。Python 392 项通过；工作区独立登记、复制值/常见编码/异步/截断保护已接入，真实 Docker 裸值与单层编码泄漏反例复测通过。但双层编码仍真实进入本机模型 HTTP 请求，证明仅输出遮盖不能关闭任意脚本读取根因；编码 Agent 的秘密不可见执行视图、批准/独立副本/硬磁盘与完整矩阵继续待接。标准重启后端 PID 51060，真实外部模型修复/版本失效/干净重放 10 条通过，两个新应用业务与容器/卷清理确认成功。**第一项仍 NOT_COMPLETE**，下方为各节点历史。

2026-10-06 后续安全/真实框架增量：[文件工具、框架与删除清理复核](PLANS/project-import-security-frameworks-v2-verification.md)。Python 文件工具补齐敏感读取/搜索/模型下载，372 项回归通过；真实 Docker 与本机模型 HTTP 协议输入的文件工具检查通过，但 **Shell 间接读取虚构秘密仍进入模型 HTTP 输入**，F04/C05 未闭环。Spring 真实安装/构建/运行/接口与 Vue+Python 浏览器业务已通过；Vue+Java 修正验收地址身份后继续复测。主 Agent 会话容器删除已接最小清理与生成互斥，真实首次清理失败发现 WSL 引号问题，修正后仍需新应用复测。**第一项仍 NOT_COMPLETE**。以下数字为各自历史节点，不代表最新源码整体验收通过。

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
