# temp 深度清理计划（2026-10-21）

状态：ARCHIVED。2026-10-21 用户将执行方式改为“统一归档后审查”；已完成下方执行记录中的归档，未执行永久删除或服务重启。原删除方案保留为决策历史，不能再按原状态直接执行。范围只限仓库 `temp/`；不清理项目源码、依赖、配置、数据库、Redis、Docker 镜像或卷。原计划目的为保留实际业务数据和运行所需工作目录、删除闲置调试产物；用户最新指示改为归档到仓库外的单一目录。

[文档总索引](../README.md) · [当前项目状态](../status/current.md)


## 2026-10-21 执行记录：按用户最新要求统一归档

- 归档目录：`D:/codex/WorkDocument/2026-10-21/temp-archive/`，位于仓库外，归档内按原 temp 相对路径保存，完整移动与文件摘要清单为 `manifest.json`。
- 已移动162项（目录或文件），归档509个文件、约16.47 MiB。清单中的全部61个顶层散文件已移出；原空 `archive/` 和空测试目录 `code_output/html_999/` 一并归档。这里的文件数不包含新生成的归档清单。
- `temp` 顶层从101个目录、61个散文件收敛为15个目录、零散文件。保留14个业务/运行目录及 `_rtlogs/`；该额外目录目前只承载在用前端日志，不为凑目录数重启服务。
- 5份在用日志保持原位：`_rtlogs/frontend.stdout.log`、`frontend.stderr.log`，`logs/chat-performance-20261020/backend-final/backend.stdout.log`、`backend.stderr.log`，以及 `logs/wsl-dockerd.log`。
- 私有暂存第二阶段未执行：未证明可释放的数据完整保留，不更改数据库状态。
- 509个归档文件逐项大小与 SHA-256 读回一致；3167个受保护业务/配置文件摘要一致，受保护业务文件集合无增减。移动清单的原位置均已不存在、目标位置均存在。
- 服务未重启；8124接口返回结构化40100，5173返回HTTP200，5173到后端的API代理返回结构化40100。未登录40100是预期鉴权响应，不将其冒充业务验收。
- 本次为同盘移动归档，不声称释放整块磁盘空间。未重新执行模型、容器或业务全链路验收，也不声称此前已缺失的验收证据随本次归档恢复。
- 已按用户选择执行一次 Bugbot 范围审查。当前工具无 bugbot 专用子代理类型，依技能要求原地审查；结果 `Bugbot found no bugs`。范围仅本轮归档、清单与文档，完整记录见归档目录 `review.json`；不对既有分支其他源码改动做通过声明。

## 盘点依据（执行前快照）

- 当前 temp 顶层101个目录、61个文件。非运行白名单的候选为87个目录和全部61个顶层散文件，共487个文件、约15.83 MiB；其中 `_rtlogs` 有在用前端日志，必须先释放占用，不能直接删除整目录。数字为本次盘点，不是执行结果。
- `code_output/` 的155个目录中154个对应未删除应用；唯一未匹配项 `html_999/` 为空目录。数据库没有 app=999 的应用、Agent运行或知识快照记录；当前两个运行容器均未挂载它。
- `code_deploy/` 的两个目录均对应现存应用，必须保留；`knowledge_snapshots/` 的8个文件保留。
- 本次只读查询：进行中的 Agent 运行0，未关闭导入编辑0。真实数据库仍有读取中、等待确认和恢复未确认的导入数据，不能因为 Agent 空闲而清空私有暂存。
- 文件独占打开检查发现5个在用文件：后端 stdout/stderr、前端 stdout/stderr、Docker daemon 日志。8124、5173均在监听。该检查只用于识别占用，执行前还须复核，不能把“没有查到业务代码引用”当成删除充分条件。
- 活跃 Docker 挂载：原项目 `temp/code_output/project_469688129883688960` 和项目外的 `manual/user_data`，不在候选范围。
- `temp/com/` 只有 `LogProbe.class`；`tools/` 为本地探查脚本；`principle/`、`pelican-riding-bicycle/` 及同名顶层 HTML/PNG 为演示产物，均列入删除候选，不属于正式应用工作区。
- `temp/archive/` 本次盘点为空；不能沿用上一轮约190 MiB归档仍存在的历史数字来承诺释放空间。

## 保留范围

| 路径（相对 temp） | 保留内容与原因 |
|---|---|
| `code_output/` | 154个现存应用工作区；不按应用时间、文件扩展名或依赖类型批量删除内部文件 |
| `code_deploy/` | 两个正在使用的发布目录及全部发布资源 |
| `knowledge_snapshots/` | 现有知识快照，保持数据库引用可读 |
| `project_import_private/` | 全部尚未核实生命周期的数据；只清理第二阶段逐任务证明已可释放的子目录 |
| `logs/` | 当前服务日志；整合后保留 backend、frontend 的 stdout/stderr 及 `wsl-dockerd.log` |
| `runtime_logs/` | 业务运行日志及产品接口依赖，保持现有保留策略；不将其混入调试日志删除 |
| `project_import_exec/`、`project_edit_outputs/`、`project_edit_private/`、`project_edit_publish/` | 系统使用的隔离工作根目录；目前为空，保留空目录即可 |
| `project_startup_model/`、`project_startup_private/`、`project_startup_python/` | 系统创建和使用的工作根目录；目前为空，保留空目录即可 |
| `screenshots/` | 截图上传中转目录；目前为空，后续只保留在途截图 |

上述工作目录会被代码按固定名称创建，不为了减少目录数修改产品路径或把不同隔离区合并。

## 第一阶段：删除确定的闲置内容

1. 执行前生成本次精确目标清单；只处理下方已列出的现有项。重新检查是否被业务表、进程、容器挂载引用；新出现的文件不自动加入删除范围。
2. 删除附件中的历史验收、调试、探针、演示目录和全部61个顶层散文件，包括结果 JSON、PNG、HTML、console/error TXT、旧日志、指向旧证据的 current TXT。目录中的测试备份和截图随目录删除；不新建 temp 内归档副本。
3. 删除空的 `archive/` 及空的 `code_output/html_999/`。如执行时 archive 出现新内容，先重新列出并核对，不套用“空目录”结论递归删除。
4. 删除 `logs/backend-20261017*`、`logs/debug-*` 与其他未占用历史服务日志；保留第二区列明的在用文件。旧报告正文留在 docs，失去本地原始证据的报告要明确标注，不能仍宣称原证据可直接读取。
5. `_rtlogs/` 两份旧后端日志可删除；两份前端日志暂留，按下一节完成日志整合后再移除整个目录。其他候选如出现占用或归属不明，记录并停在该项，不强删、不停止不相关进程。

## 在用日志的处理顺序

| 当前在用位置 | 处理 |
|---|---|
| `logs/wsl-dockerd.log` | 原位保留，不重启 Docker daemon，不修改其句柄或日志路径 |
| `logs/chat-performance-20261020/backend-final/backend.stdout.log`、`backend.stderr.log` | 无在途任务时按标准 `scripts/restart-backend.ps1 -LogDirectory temp/logs/backend` 启动到统一目录；确认8124和结构化接口响应后，才删除旧日志目录 |
| `_rtlogs/frontend.stdout.log`、`frontend.stderr.log` | 只停止5173对应的本项目前端及其明确关联的启动器，使用已有 `scripts/start-frontend.ps1` 隐藏启动并重定向到 `temp/logs/frontend/`；确认5173页面与 `/api` 代理正常后，才删除 `_rtlogs/` |

日志整合会短暂重启前后端，应作为执行阶段的明确步骤，不能把端口监听或文件移动当成业务验收。新日志目标已存在时，先确认没有其他进程写入，禁止覆盖在用文件。日志统一不要求修改现有启动脚本或功能代码。

## 第二阶段：私有暂存按任务清理

不清空 `project_import_private/`。先建立磁盘任务目录、`.git` 副本与任务表、来源表、发布/应用和恢复资源之间的完整关联，再形成子目录级清单：

- 当前匹配到4个 `CANCELLED / DONE` 任务目录：仅在没有其他来源、应用或恢复流程引用、没有活跃进程和挂载时删除，相关 `.git` 副本独立核对。
- `READING_CONTENT`、`WAITING_CONFIRMATION`、`RECOVERY_REQUIRED`、`UNVERIFIED`、`cleanup=FAILED` 全部保留。本轮不为了删除文件而取消真实用户任务、修改数据库状态或伪造清理完成。
- `SUCCEEDED / NONE` 不作为可删除依据，先确认导入是否完成发布、后续是否仍需私有来源；查不到引用也不能直接视为无主数据。
- 无法证明来源和生命周期的孤立目录保持不变，列为未清理项，不计入预估释放空间。

本阶段只删已证明可释放的磁盘副本，不删除应用、对话、票据或历史任务记录。

## 执行保护与验收

- 删除前验证每个绝对路径均在本仓库 temp 内，使用精确路径；遇到链接、联接点或路径越界停止，不跨文件系统跟随删除。
- 执行时再次确认服务、任务与占用状态，不沿用本计划的快照结果；只停止日志整合必需的本项目前后端，不停止数据库、Redis或 Docker。
- 若永久删除被自动审批拦截，明确报告具体被拒动作并停止该动作，不擅自改成归档后声称完成删除，也不换工具绕过拦截。
- 比较保留应用工作区、发布资源和知识快照的文件清单/摘要；确保受保护字节不变，数据库引用未被破坏。
- 核对8124结构化响应、5173页面及API代理、现存项目预览/下载、两个发布入口；不调用模型或启动无关业务验收。
- 输出实际删除目录数、文件数、按文件长度计算的释放量、剩余目录和未清理项。文件长度总量不冒充磁盘簇实际释放量。
- 理想执行结果为 temp 顶层14个运行相关目录，零个调试散文件；私有暂存的保留数据不因目录整洁目标被删掉。执行时若存在新增在用数据或受阻项，以实际结果为准。

## 删除候选：精确目录清单

以下87个目录为本次快照，其中 `_rtlogs` 必须按在用日志流程处理；这里的清单不是可绕过状态核对的无条件删除命令。

- `temp/_rtlogs/`（4文件）
- `temp/admission-restart-final4/`（1文件）
- `temp/archive/`（0文件）
- `temp/backend-log/`（2文件）
- `temp/backend-restart-20261005/`（2文件）
- `temp/brand-rename-20261003-195226/`（35文件）
- `temp/business-final-backend/`（2文件）
- `temp/business-fix3/`（1文件）
- `temp/business-fix4/`（1文件）
- `temp/business-fix5/`（4文件）
- `temp/business-fix6/`（1文件）
- `temp/business-real-vue-approved/`（14文件）
- `temp/business-real-vue-final/`（2文件）
- `temp/cleanup-import-test-residue-1791271777687867400/`（1文件）
- `temp/cleanup-import-test-residue-1791272028692525500/`（1文件）
- `temp/cleanup-import-test-residue-1791274713796581100/`（1文件）
- `temp/cleanup-import-test-residue-1791275063362444900/`（1文件）
- `temp/com/`（1文件）
- `temp/core-resources-final4/`（4文件）
- `temp/core-shell-authority-20261008/`（7文件）
- `temp/folder-import-real-1791049566576/`（10文件）
- `temp/folder-import-real-1791049613888/`（10文件）
- `temp/folder-import-real-1791050069087/`（9文件）
- `temp/folder-import-real-1791051216803/`（9文件）
- `temp/folder-import-real-1791100715487/`（9文件）
- `temp/folder-import-real-1791101676992/`（9文件）
- `temp/folder-import-real-1791103134759/`（9文件）
- `temp/folder-import-real-1791229163740/`（9文件）
- `temp/folder-import-real-1791229201593/`（9文件）
- `temp/folder-import-real-1791229298472/`（10文件）
- `temp/folder-import-real-1791229500059/`（10文件）
- `temp/folder-import-real-1791229599869/`（12文件）
- `temp/folder-import-real-1791229820941/`（11文件）
- `temp/folder-import-real-1791277133706/`（11文件）
- `temp/formal-audit-final-20261008/`（3文件）
- `temp/formal-audit-final2-20261008/`（3文件）
- `temp/formal-current4/`（6文件）
- `temp/formal-import-browser-1791029418420/`（12文件）
- `temp/formal-import-browser-1791029541802/`（13文件）
- `temp/formal-import-browser-1791029641299/`（13文件）
- `temp/formal-import-browser-1791042565041/`（13文件）
- `temp/formal-import-browser-1791042706150/`（13文件）
- `temp/framework-static-current3/`（0文件）
- `temp/git-import-cancel-1791100892701/`（1文件）
- `temp/git-import-cancel-1791100951143/`（2文件）
- `temp/git-import-cancel-1791101683097/`（2文件）
- `temp/git-import-cancel-1791103107103/`（2文件）
- `temp/git-import-preview-1791050137958/`（1文件）
- `temp/git-import-preview-1791050227440/`（1文件）
- `temp/git-import-preview-1791050409643/`（4文件）
- `temp/git-import-preview-1791050755173/`（1文件）
- `temp/git-import-preview-1791051112406/`（4文件）
- `temp/git-import-preview-1791055834749/`（4文件）
- `temp/git-import-real-1791100517622/`（1文件）
- `temp/git-import-real-1791100575793/`（3文件）
- `temp/git-import-real-1791100619708/`（3文件）
- `temp/git-import-real-1791100668853/`（2文件）
- `temp/git-import-real-1791100991826/`（2文件）
- `temp/git-import-real-1791101147705/`（5文件）
- `temp/git-import-real-1791101321386/`（3文件）
- `temp/git-import-real-1791101656474/`（3文件）
- `temp/git-import-real-1791103113150/`（3文件）
- `temp/git-import-recovery-1791102871813/`（1文件）
- `temp/git-import-recovery-1791103091104/`（2文件）
- `temp/grokbot-step1-baseline-20261005-003406/`（4文件）
- `temp/home-white-screen-20261003-233206/`（4文件）
- `temp/idea-resource-trim-20261020/`（10文件）
- `temp/item1-read-audit-8004116e387d075cb6f5/`（2文件）
- `temp/item1-read-audit-8a3c3c5f0d21e5870534/`（2文件）
- `temp/negative-cancel-final4/`（2文件）
- `temp/negative-conflict-final4/`（2文件）
- `temp/p1-credentials-current2/`（1文件）
- `temp/p1-folder-current2/`（8文件）
- `temp/p12-source-current2/`（1文件）
- `temp/p5-backend-log/`（2文件）
- `temp/p5-product-browser-current3/`（2文件）
- `temp/p52-provider-current2/`（1文件）
- `temp/p52-schema-current2/`（1文件）
- `temp/pelican-riding-bicycle/`（8文件）
- `temp/pip-evidence-20261008-231239/`（4文件）
- `temp/pip-evidence2-20261008-231921/`（4文件）
- `temp/pip-evidence3-20261009-110422/`（4文件）
- `temp/principle/`（1文件）
- `temp/publication-final4/`（5文件）
- `temp/runtime-repair-20261008/`（3文件）
- `temp/runtime-repair-resume-20261008/`（2文件）
- `temp/tools/`（5文件）

## 删除候选：精确顶层文件清单

- `temp/_e2e_app_id.txt`
- `temp/_fix_verify_result.json`
- `temp/_r2_app_id.txt`
- `temp/_r2_knowledge_result.json`
- `temp/_r2_runtime2_result.json`
- `temp/_r2_suite_result.json`
- `temp/_r3_result.json`
- `temp/brand-rename-current.txt`
- `temp/business-real-vue-approved.log`
- `temp/business-real-vue-final.log`
- `temp/core-gate-final2-20261008.json`
- `temp/core-ledger-final-20261008.json`
- `temp/core-ledger-final2-20261008.json`
- `temp/debug-parser.json`
- `temp/home-white-screen-current.txt`
- `temp/import-frontend-integration-current.txt`
- `temp/import-help-copy-current.txt`
- `temp/import-vertical-align-current.txt`
- `temp/p2-core-current.log`
- `temp/p5-browser-first-cleanup.log`
- `temp/p5-browser-ordinary-fixed-2.log`
- `temp/p5-browser-ordinary-fixed.log`
- `temp/p5-browser-ordinary.log`
- `temp/p5-browser-real-retry.log`
- `temp/p5-browser-real.log`
- `temp/p5-console10.txt`
- `temp/p5-console11.txt`
- `temp/p5-console6.txt`
- `temp/p5-console7.txt`
- `temp/p5-console8.txt`
- `temp/p5-console9.txt`
- `temp/p5-direct-err.txt`
- `temp/p5-direct-out.txt`
- `temp/p5-error10.txt`
- `temp/p5-error11.txt`
- `temp/p5-error7.txt`
- `temp/p5-error8.txt`
- `temp/p5-error9.txt`
- `temp/p5-live-err.txt`
- `temp/p5-live-out.txt`
- `temp/p5-product-browser-after-mask.json`
- `temp/p5-product-browser-current.json`
- `temp/p5-product-browser-current10.json`
- `temp/p5-product-browser-current11.json`
- `temp/p5-product-browser-current2.json`
- `temp/p5-product-browser-current3.json`
- `temp/p5-product-browser-current4.json`
- `temp/p5-product-browser-current5.json`
- `temp/p5-product-browser-current6.json`
- `temp/p5-product-browser-current7.json`
- `temp/p5-product-browser-current8.json`
- `temp/p5-product-browser-current9.json`
- `temp/p5-product-browser-debug.json`
- `temp/p5-product-browser-final.json`
- `temp/p5-product-browser-timeoutfix.json`
- `temp/p5-run-console5.txt`
- `temp/pelican-a.png`
- `temp/pelican-b.png`
- `temp/pelican-bicycle.html`
- `temp/pelican-bike.png`
- `temp/pelican-c.png`

当前候选静态长度约15.83 MiB；未计私有暂存第二阶段及 logs 内的额外可删内容。计划不承诺删除仍在使用的文件。
