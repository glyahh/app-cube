# 识别补充候选发布与合法上下文：步骤 2 增量

日期：2026-10-05。分支 `code_board`，HEAD `d452804`；工作区原有未提交改动基线见 `temp/grokbot-step1-baseline-20261005-003406/`，本步源码 SHA256 见 `temp/import-startup-candidate-publish-20261005/source-manifest.txt`。**第一项 NOT_COMPLETE；候选只是 AGENT/INFERRED 推断，不是安装/运行授权，S05 未声称通过。**

## 实现与复用

- 发布：模型回包不再直接写 SUCCEEDED。执行者退出、资源与私有输入确认清理后，`ImportStartupService.publishSupplement` 在同一工作区写入锁内：复用现有识别复核（owner/导入任务/规则/配置/epoch + 全量内容快照摘要），校验批准冻结身份，用锁内刚建立的完整副本重新投影文本并复核依据 SHA，确认目录仍可补充且不重复已有/已发布候选，然后同锁内提交 RUNNING→SUCCEEDED（原 SQL 再核对过期、取消与数据库资格）。数据库资格不变不作为工作区未变的证明。
- 作废码：内容/配置/代次/规则变化或识别已过期 `MODEL_RESULT_STALE`；执行时限已过 `APPROVAL_EXPIRED`；范围已被占用、重复已有候选或依据不符 `MODEL_RESULT_CONFLICT`；提交时数据库资格失效沿用 `MODEL_RESULT_REVOKED`；锁忙等复核未完成短暂重试 3 次后 `MODEL_RESULT_UNVERIFIED`，关停中同码。取消意图优先记 CANCELLED，从不发布。
- 读取：既有识别读取路径（`GET .../startup/inspections/{scanId}`、批准复核回调）在内容一致后逐条叠加本识别的 SUCCEEDED 补充，每条按当前封存文本重新校验；不符的记录转 `STALE/MODEL_RESULT_STALE`（回包随迁移清空），坏记录只记日志跳过，不阻断其他结果。识别因内容/上下文变化转 STALE 时，其已发布补充一并转 STALE；原字节还原也不复活。CANCELLED/FAILED/STALE 记录即使带回包也不显示。
- 同目录粒度：补充候选逐个独立列出（ID 由批准与服务信息生成），原扫描诊断全部保留，另按目录追加 `AGENT` 阶段诊断（IDENTIFIED 或 UNIDENTIFIED/AGENT_NO_ENTRY），不把同目录其他范围合并或改写。批准资格不再因同目录另一范围已被规则识别而拒绝（例：Node 已识别、Maven 未识别）；手动配置、已发布补充、同级/上级 FAILED/INCOMPLETE/AMBIGUOUS 仍阻断。批准粒度仍按 V23 冻结的目录。
- 合法上下文：模型文本投影只取批准目录内的文件（`.` 为全工程），原有敏感文件、依赖/构建/日志/.git 等排除与 16KiB/48KiB/256 文件限额不变；上下文新增逐条 `ranges`（目录/阶段/诊断）与 `known`（已有候选名称/目录/命令，只供排除）。Python 侧严格校验新字段、范围覆盖所有授权目录，并拒绝与 `known` 同目录同命令的候选。仍无工具、无 Shell、无历史会话/checkpoint；保护快照位于 `temp/project_import_private`，不在工作区投影内。
- 仓库仅新增 `published()`（单行 SQL，未新增文本块，租约 schema 脚本的文本块假设不变）。未新增迁移，V23/V24 哈希与改动前一致。
- 修改的均为未跟踪文件，修改前副本在证据根 `before/`（其中 `verify-startup-candidate-publish-api.py.before` 是本步首版脚本，不是原有文件）。

## 命令与证据

```powershell
.\mvnw.cmd -q test '-Dtest=ImportStartupAgentPolicyTest,ImportStartupApprovalServiceTest,ImportStartupServiceTest,ImportStartupSnapshotStoreTest,ImportStartupSupplementResultTest,ImportStartupModelExecutorTest'
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-model-helper.py
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-agent-protocol.py
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-approval-schema.py
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-model-lease-schema.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-candidate-publish-20261005/restart-final
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --async --cancel --recovery --rules
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-candidate-publish-api.py --restart
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-approval-api.py --restart
```

证据根 `temp/import-startup-candidate-publish-20261005/`：

- `unit-first.log` 退出0：6 类 110 项（`unit-first-count.txt`）。服务测试用真实临时工作区与快照；批准/模型回包为受控替身，不算真实模型通过。
- `java-full-1.log` 与自审修复后 `java-final.log` 均退出0：**45 类 460 项，0 FAIL / 0 ERROR / 1 SKIPPED**（`java-final-count.txt`，XML `java-xml-final/`）。比步骤 1 基线多 18 项。
- `python.log`、`python-final.log` 退出0：**357 项通过**（新增 7 项）。`helper.log`、`protocol.log` 退出0：两份既有真实 helper 负例脚本已同步新上下文字段，拒绝原因仍是应用串写/空消息，模型调用 0。
- `schema-approval*.log`、`schema-lease*.log` 退出0（事务私有 schema，全部回滚；本步未改已有 SQL 文本块）。
- 标准重启 `restart-1.log`（新 PID 58284）与最终 `restart-final.log`（旧 PID 31316 → 新 PID 36580，8124 `/api`）。
- `api-publish-final.log` 退出0，结果 `api-1791134702807055500/result.json`，app `2106797760552189952`，**7 组 PASS、1 组 NOT_COVERED**：
  - 真实批准（approval `d37fff00-2dd5-4a4c-ad5d-2c1144edf616`）：同目录 Node 已识别时，Maven 未识别范围可单独批准；真实执行器调用了真实模型，返回 `FAILED/MODEL_RESPONSE_FAILED`，失败回包不进入候选。真实模型成功发布本次未覆盖（NOT_COVERED）。
  - [受控夹具] 依据摘要不符的 SUCCEEDED 记录不显示并转 STALE；CANCELLED/过期 STALE/FAILED 带回包不显示；合法 SUCCEEDED 经全内容复核后作为独立 AGENT 候选显示、原诊断保留、跨用户 404。
  - 已发布目录再次申请返回 40000；原规则候选未被替换。
  - **真实标准重启**后已发布候选重新复核仍可见，夹具从未产生执行租约。
  - 绕过平台改写工作区文件：识别 STALE、已发布补充转 STALE/MODEL_RESULT_STALE；还原原字节不复活，旧识别再批准 40900；重新识别不携带旧回包；导入工程（还原后）与保护字节一致。
- `api-publish-1.log` 退出1（保留）：首轮运行中真实模型对 `app` 实际返回成功（`services: []`、`unidentified: ["app"]`），经发布复核写入 SUCCEEDED（approval `fc156b89-a7a9-401c-bcae-373dbe6793ed`，app `2106796245338902528`）；是脚本错误假设"成功必有候选"导致断言失败，不是产品失败。脚本已改为按 AGENT 阶段诊断核对，但之后两轮真实模型均返回 `MODEL_RESPONSE_FAILED`，修正后的成功分支没有被脚本再次走到，不记 PASS。`api-publish-2.log` 退出0 是修正后的同一套检查。
- `api-regression.log` 退出0，**19 组既有 API 回归通过**（`temp/import-startup-business-20261004/api-1791134666960453900/result.json`）。
- `api-approval.log` 退出0，步骤 1 批准脚本 **9 项检查全部 PASS**（含真实标准重启对账，`temp/import-startup-approval-flow-20261005/api-1791134742280462800/result.json`），SUCCEEDED 记录 0。

## 自审发现与修复

- 关停中的发布原先记 `MODEL_RESULT_STALE`，与内容变化混淆；改为 `MODEL_RESULT_UNVERIFIED`。
- 锁内复核期间批准到期原会落成 `MODEL_RESULT_CONFLICT`；锁内补查到期，记 `APPROVAL_EXPIRED`。
- 排队中的批准若同目录已被另一批准发布，取模型输入时抛出未包装的参数异常并记 `MODEL_INTERNAL_FAILURE`；改为冲突异常（记 `APPROVAL_CONTEXT_CHANGED`），不再调用模型。
- 已发布回包以 JSONB 存储，读回会规范化空白/键序；读取限额放宽到 64KiB，字段与依据规则不变，有单测覆盖。
- 以上修复后重跑全量 Java、Python、schema、标准重启与三套 API 脚本，结果如上。

## 待完成与回退

- 真实模型成功并带候选的发布路径未在最终运行中覆盖；首轮观察到一次真实空结果发布（见上）。`MODEL_RESPONSE_FAILED` 的具体原因按既有设计不回显，未诊断。S05 不改 PASS。
- 未覆盖：真实模型运行期间外部改写工作区（单测用真实文件覆盖了发布前改动被拒）；两个批准同时覆盖同一目录时都会调用模型，后发布者才被拒（浪费一次额度，不会重复发布）。
- 批准仍按目录粒度：同目录多范围时模型对目录整体给出候选或标未识别；目录一旦有补充结果，其中剩余的未识别范围保留原诊断但不能再次补充，只能走手动配置。同级 AMBIGUOUS（如多个启动脚本）仍阻断同目录其他范围。
- 回退：用证据根 `before/` 恢复 17 个修改文件，删除 `scripts/checks/verify-startup-candidate-publish-api.py`；本步未改数据库结构。
