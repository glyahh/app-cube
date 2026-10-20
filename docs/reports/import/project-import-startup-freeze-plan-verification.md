# 冻结运行计划：步骤 3 增量

日期：2026-10-05。分支 `code_board`，HEAD `d452804`；改动前 `git status --porcelain` 基线见 `temp/import-startup-freeze-plan-20261005/git-status-before.txt`，本步源码 SHA256 见同目录 `source-manifest.txt`。**第一项 NOT_COMPLETE；计划只是冻结与确认记录，`executable=false`，不是安装/构建/启动授权，本步未执行任何安装/构建/运行，S05 未声称通过。**

## 实现与复用

- 计划内容（`ImportStartupPlan`，纯函数）：以当前有效识别 + 锁内封存文本 + 手动配置 + 用户所选服务 ID（1–20 个、不重复、必须存在，不做回退）生成不可变内容：所选服务（目录/来源/依据/命令/展开/生命周期/构建/依赖/就绪/未知项）、依赖范围（按生态+目录，状态恒为 UNKNOWN，列声明与锁文件、Maven 父 pom、手动安装命令、install 生命周期钩子）、仅列候选已声明的必要构建（同目录同命令合并为共享步骤）、就绪方式（STATIC_ENTRY 或 PROCESS_ALIVE + `READINESS_PORT_UNDECLARED`）。`planRevision` = 规范 JSON（键排序）的 SHA-256；同时提供字段级 `diff`（列表按 id 对齐，最多 200 条）。
- 聚合脚本：npm 脚本按广度优先展开（含 pre/post 钩子，最多 20 条），识别 `MULTI_SCRIPT_RUNNER / WORKSPACE_RUNNER / BACKGROUND_PROCESS / DIRECTORY_CHANGE / COMPOSE / MAVEN_REACTOR / EXPANSION_LIMIT / REFERENCES_UNSELECTED`，并列出会被带上的未选服务。存在聚合项时确认必须带 `aggregateAcknowledged=true`，否则 40000。
- 授权分离：识别批准、手动配置、已发布补充、历史运行成功都不会生成或确认计划；`create` 只返回幂等 DRAFT，确认必须显式 `POST .../confirm` 且带当前 `planRevision`。视图恒为 `executable=false`，本步没有任何执行入口。
- 失效与差异：`get/current` 在工作区锁内重新生成内容比对；识别过期 `INSPECTION_STALE`、所选服务消失 `SELECTION_UNAVAILABLE`（差异列出消失的服务）、内容变化 `PLAN_CHANGED`（带差异）、导入任务变化 `IMPORT_TASK_CHANGED`、权限变化 `PERMISSION_CHANGED` 都使计划转 STALE，旧确认不能再用；新 DRAFT 显示相对上次确认的差异。确认时 revision 不符 409，内容已变则先转 STALE 再 409。
- 新增迁移 `V25__project_import_startup_plan.sql`（确认需要持久、可失效并保留差异，才新增）：`biz.project_import_startup_plan`，状态 DRAFT/CONFIRMED/STALE/SUPERSEDED；部分唯一索引保证同内容有效计划唯一、每应用最多一份 CONFIRMED；触发器冻结内容与身份、禁止离开 STALE/SUPERSEDED、禁止 CONFIRMED→DRAFT；STALE 必须有 `stale_json`。V23/V24 未改（哈希见 `migration-hashes.txt`）。
- 仓库 `ImportStartupPlanRepository`：插入 SQL 内含资格 EXISTS + ON CONFLICT DO NOTHING；确认 SQL 做 revision CAS 并核对识别/配置/发布/应用资格；`@Transactional confirm` 先把旧 CONFIRMED 置 SUPERSEDED；读取时列值与内容、revision 交叉校验。
- 复用：`ImportStartupService` 新增 `PlanInput`、`withPlanInput`（识别过期传 null）和 `planRevoked`（仅 NOT_FOUND/CONFLICT 视为撤销，其它异常照抛），其余入口不变。修改前副本在证据根 `before/`（其中 `ImportStartupService*.java.before` 是原有未跟踪文件；其余 `.before` 是本步新增文件的较早版本，不是原有文件）。
- 接口 `/project/import/apps/{appId}/startup/plans`：`POST`（ownerId/scanId/serviceIds）、`GET /current`、`GET /{planId}`、`POST /{planId}/confirm`（ownerId/planRevision/aggregateAcknowledged）。

## 命令与证据

```powershell
.\mvnw.cmd -q test '-Dtest=ImportStartupPlan*Test,ImportStartupServiceTest'
.\mvnw.cmd -q test '-Dtest=Import*Test,ProjectImport*Test,WorkspacePathResolverTest,RuntimeSpecResolverTest,WorkspaceVersionerTest,AppServiceSupportTest'
.\agent\.venv\Scripts\python.exe -m pytest agent/tests -q
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-approval-schema.py
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-model-lease-schema.py
.\agent\.venv\Scripts\python.exe scripts/archive/verify-startup-plan-schema.py
powershell -ExecutionPolicy Bypass -File scripts/restart-backend.ps1 -TimeoutSeconds 180 -LogDirectory temp/import-startup-freeze-plan-20261005/restart-final
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-inspection-api.py --python --config --async --cancel --recovery --rules
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-approval-api.py --restart
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-candidate-publish-api.py --restart
.\agent\.venv\Scripts\python.exe scripts/checks/verify-startup-plan-api.py --restart
```

证据根 `temp/import-startup-freeze-plan-20261005/`：

- `unit-first.log` 退出1（保留）：服务测试替身未桩 `plans.confirm` 导致测试自身 NPE，已修。`unit-2.log` 退出0：Controller 3、Repository 5、PlanService 9、Plan 9、ImportStartupService 32。
- `java-full-1.log` 与自审修复后 `java-final.log` 均退出0；最终 **49 类 489 项，0 FAIL / 0 ERROR / 1 SKIPPED**（`java-final-count.txt`，XML `java-xml-final/`），比步骤 2 基线（45/460）多 29 项。
- `python-final.log` 退出0：**357 项通过**。
- `schema-plan.log` 退出1（保留，发现 V25 CHECK 对 NULL 放行的缺陷，见自审）；`schema-plan-2.log` 与 `schema-plan-final.log` 退出0，6 组 PASS（事务私有 schema，全部回滚）。`schema-approval-final.log`、`schema-model-lease-final.log` 退出0。
- `flyway-v25-check.txt`：V23/V24/V25 均 success；V25 于 2026-10-05 01:41 由 `restart-1` 应用。`migration-hashes.txt`：V23 `6F001261…`、V24 `49642B5D…` 与改动前一致。
- 标准重启：`restart-1.log`（应用 V25）、`restart-final.log`（旧 PID 23436 → 新 PID 5048，8124 `/api`）；三套 `--restart` 脚本又各做了真实重启，最终监听 PID 34076。
- `inspection-api-final.log` 退出0：**19 组既有 API 回归通过**。
- `api-approval-final.log` 退出0：步骤 1 批准脚本 **9 组 PASS**，SUCCEEDED 记录 0。
- `api-candidate-publish-final.log` 退出0：步骤 2 发布脚本 **7 组 PASS、1 组 NOT_COVERED**（真实模型返回 `MODEL_RESPONSE_FAILED`，真实成功发布未覆盖）。
- `api-plan-1.log`（首轮）与 `api-plan-final.log`（`--restart`）退出0：**8 组 PASS**，最终结果 `api-1791136288355123800/result.json`，app `2106804411678146560`，planStates `{STALE:3, SUPERSEDED:1}`，`executionEntry=CLOSED`：
  - 手动配置与识别拒绝不产生计划；计划为 DRAFT、不可执行，列明命令/依赖范围（UNKNOWN）/就绪方式/共享依赖；同内容幂等；跨用户不可见。
  - 聚合启动脚本展开（含 pre 钩子与被带上的未选服务）；共享构建只列一次；未确认展开不能确认。
  - 错误 planRevision 拒绝；确认幂等、跨用户 404；新确认使旧确认 SUPERSEDED 且不能再确认。
  - [受控夹具] 新发布补充候选使已确认计划 STALE/PLAN_CHANGED，差异列出新增可选服务。
  - 真实标准重启后已确认计划复核仍为同一 planRevision；直接改写 `plan_json` 被数据库拒绝。
  - 外部磁盘改动后已确认计划 STALE/INSPECTION_STALE；还原原字节不恢复，旧识别不能再生成计划。
  - 启动脚本改变后重新识别，新计划以上次确认为基准展示 contentHash 与命令展开差异。
  - 手动配置变化使草稿 STALE；无 node_modules/dist 生成，保护快照未变，无模型执行租约，最终无已确认计划。

## 自审发现与修复

- V25 首版 STALE 约束写成 CHECK，`stale_json` 为 NULL 时 CHECK 结果为 NULL 被放行；schema 脚本实测发现，改为 `stale_json IS NOT NULL`（迁移应用前修复）。
- `node -r dotenv/config` 被误判为 WORKSPACE_RUNNER；改为 `-r/-w/--filter` 等只在 npm/pnpm/yarn/bun 命令内计入（补 `pnpm -r dev` 正例）。
- 脚本体内 `npm start/test/stop/restart` 原不跟踪展开；已跟踪。
- get/current 返回的 DRAFT 原不带相对上次确认的差异；已补。
- SELECTION_UNAVAILABLE 原不列出消失的服务；差异中列出（新增单测）。
- 以上修复后重跑全量 Java、Python、三份 schema、标准重启与四套 API 脚本，结果如上。

## 待完成与回退

- 就绪端口未推断（仅 PROCESS_ALIVE + 未知项）；聚合检测是静态启发式，误报只要求确认展开，运行期子进程仍未知；Node workspace 父级未向上追溯。
- 本步只记录 CONFIRMED；执行时校验确认（权限、revision、未失效）留给步骤 5–7；前端尚未接入（步骤 4）。PERMISSION_CHANGED/IMPORT_TASK_CHANGED 仅单测覆盖，API 脚本未覆盖。
- 发布补充导致的计划变化只用受控数据库夹具验证；未涉及真实模型成功。
- 回退：用证据根 `before/` 恢复 `ImportStartupService.java` 与 `ImportStartupServiceTest.java`，删除本步新增文件（见 `source-manifest.txt`）；V25 需手工 `DROP TABLE biz.project_import_startup_plan; DROP FUNCTION biz.project_import_startup_plan_frozen();` 并删除 `flyway_schema_history` 中 version 25 行，Flyway 不自动回滚。
