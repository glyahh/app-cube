# 正式工作台联调：步骤 4 增量

日期：2026-10-05。分支 `code_board`，HEAD `d452804`；改动前 `git status --porcelain` 基线见 `temp/import-startup-workbench-20261005/git-status-before.txt`，本步源码 SHA256 见同目录 `source-manifest.txt`。**第一项 NOT_COMPLETE；安装/运行按钮保留但不可执行（隔离执行与运行授权属步骤 5–6），本步未安装、构建或运行任何导入工程，S05 未声称通过。**

## 实现与复用

- 正式入口：生产路由 `/app/:id/chat`（`AppChatView.vue`）原先对导入工程也显示旧“运行”按钮，点击走 `runtime/start` 并提示“应用已启动”，是未经计划/授权的占位成功路径。现在页面用只读 `GET .../startup/configuration` 判断当前用户的导入应用（非导入或无权限返回 40400，保持原按钮不变），导入工程在 `runtime-actions` 插槽中改用 `StartupRunPreview.vue`；`handleStartRuntime` 对导入工程直接返回，旧运行入口不可达。组件按需异步加载，不进入首屏包。原 `importedProject` 文案判断改为“属性或真实资格”。
- `StartupRunPreview.vue` 去除全部内存样本与定时成功，改接真实接口（新增 `features/project-import/startup.ts`，复用 `@/request` 的 Axios 实例、导入模块的会话 CSRF `X-Project-Import-CSRF` 与同源 Origin，沿用原组件样式）：
  - 识别：读取配置版本/上下文代次后 `POST .../scans`（异步扫描，requestId 幂等），轮询真实状态，可取消；仅 SUCCEEDED 且 `GET .../inspections/{scanId}` 也为 SUCCEEDED 时列出候选。FAILED、CANCELLED、STALE、STOP_FAILED/RECOVERY_REQUIRED 只显示状态与提示，不给候选。
  - Agent 识别（步骤 1）：只对原规则阶段未识别、且无 Agent 阶段结果的目录提供；“暂不使用”提交真实拒绝（REJECTED），“识别”提交批准并轮询，可取消；批准号存会话存储，刷新后找回真实状态。到期 `STALE/APPROVAL_EXPIRED`、失败、取消都只提示“未产生候选”。
  - 已发布补充（步骤 2）：批准 SUCCEEDED 后重读识别，补充候选标“Agent 推断”，诊断行显示 Agent 阶段标签；仍需进入计划确认。
  - 手动配置：带 configRevision/contextEpoch 保存，保存后清空旧识别并要求重新识别；保存不代表可运行。
  - 计划（步骤 3）：初次不勾选服务；生成草稿后展示命令展开、生命周期、就绪方式与未知项、依赖（未检查 UNKNOWN）、构建/共享步骤、聚合脚本及会带上的未选服务、相对上次确认的差异、失效原因。存在聚合项时必须勾选“我已核对展开范围”才能确认；409 冲突时重读计划显示 STALE 与差异，不自动改用新计划；计划因识别/导入/权限失效时收起旧候选，必须重新识别。
  - 门禁：`executionAllowed` 需要 CONFIRMED + 服务端 `executable=true` + 隔离授权就绪，当前恒为 false；“安装依赖”“运行所选”可见但禁用，并提示“隔离执行与运行授权尚未开通”。
  - 迟到结果：切换应用/账号、卸载即换代并清理定时器，旧响应不写入当前界面；轮询中断不锁死界面。
- 隔离预览 `ProjectImportPreview.vue` 只去掉已删除的样本参数（scenario/failure/promptInstall）；其内存传输不放行启动接口，预览中打开运行面板会显示“当前无法完成此操作”，不再有模拟成功。旧隔离走查脚本 `scripts/verify-startup-preview.mjs` 依赖已删除的样本，本步未运行、视为被正式走查取代。
- 未改后端、未新增迁移或 npm 依赖。修改前副本：`before/AppChatView.vue.before`（已跟踪文件，原已有未提交改动）、`before/StartupRunPreview.vue.before`、`before/ProjectImportPreview.vue.before`（未跟踪文件）；`before/` 中其余 `.before` 是本步新增文件的较早版本，不是原有文件。

## 命令与证据

在 `ai-generate-code-frontend` 目录（lint 脚本带 `--fix` 会改动无关文件，本步对改动文件直接运行且不加 `--fix`）：

```powershell
.\node_modules\.bin\vue-tsc.cmd --build
.\node_modules\.bin\oxlint.cmd <7 个改动文件>
.\node_modules\.bin\eslint.cmd <改动文件>
.\node_modules\.bin\vitest.cmd run
node node_modules/vite/bin/vite.js build --outDir ../temp/import-startup-workbench-20261005/build-production --emptyOutDir
node node_modules/vite/bin/vite.js build --config vite.import-preview.config.ts --outDir ../temp/import-startup-workbench-20261005/build-preview --emptyOutDir
node --test scripts/__tests__/initial-load-budget.test.mjs
node node_modules/vite/bin/vite.js --port 5173 --strictPort   # 对接已运行的真实后端 8124
node scripts/verify-startup-workbench-real.mjs
```

证据根 `temp/import-startup-workbench-20261005/`：

- `type-check-final.log` 退出0。
- `lint-oxlint-final.log` 退出0（0 警告 0 错误）；`lint-eslint-final.log` 6 个文件退出0。`AppChatView.vue` 的 eslint 退出1，12 个错误全部是原有未使用变量/any（改前同文件 13 个，见 `lint-eslint-appchat-before.log`；本步用到原未使用的 `watch`，少一个），不是本步引入。首轮 oxlint 指出走查脚本一处正则写法，已改（`lint-oxlint.log`）。
- `unit-first.log` 退出1（保留）：适配器同步抛错未转为 rejected Promise，已改为 async。`unit-full-final.log` 退出0：**30 文件 221 项通过**（此前文档记录 28 文件 208 项；新增 `startup.test.ts` 6 项、`StartupRunPreview.test.ts` 7 项）。`AppChatThinking` 既有 tooltip stub 警告保留。
- `build-production-final.log`、`build-preview-final.log` 退出0（保留原大 chunk 警告）；`initial-load-budget-final.log` 2/2 通过。
- 真实浏览器（现有 Playwright 1.62.1 + 本机 Edge 无头，未安装新工具）：`browser-final.log` 退出0，`browser-1791137663079/result.json`，**10 组 PASS**，pageerror/console error 0，安装/运行/生成/Agent 流请求 0：
  - 桌面 1440：导入工程显示启动入口、安装禁用且旧运行按钮不出现；真实异步扫描列出候选与 `worker · 未识别`；拒绝 Agent 真实落库 REJECTED、不产生候选；[受控夹具] 已过期未认领批准刷新找回显示已过期，后端 `STALE/APPROVAL_EXPIRED`、无执行租约；选择 web 生成草稿，聚合须核对后确认，服务端 CONFIRMED 且 `executable=false`，运行/安装仍禁用；外部改动后刷新计划显示已失效/识别结果已过期、候选收起、不能确认或运行，导入工程无 node_modules/dist。
  - 窄屏 390：扫描/拒绝/过期三段同样通过，页面与弹窗无横向溢出（scrollWidth=390，弹窗左右边界均在视口内）。
  - 截图：`desktop-workbench/scan/reject/expired/plan/confirmed/stale.png`、`narrow-workbench/scan/reject/expired.png`。首轮 `browser-1.log` 也是 10 组 PASS；查看截图后发现来源标签错误等问题（见自审），修复后重跑为最终结果。
- 应用：桌面 `2106810179206873088`、窄屏 `2106810218868211712`（脚本新注册用户与夹具工程）。
- 后端未改动；走查前后 8124 由同一进程（PID 34076）服务，`/api` 正常响应。走查用的 Vite 开发服务（5173）结束后已停止。

## 自审发现与修复

- 适配器校验失败时同步抛错，调用方得到异常而非 rejected Promise；接口函数统一改为 async（单测首轮发现）。
- 候选来源 `STATIC` 是“静态规则分析”，首版误标“静态入口”；改为“规则识别”（截图发现）。
- 计划因识别过期失效后，旧候选仍可勾选并可再次“生成运行计划”（后端会拒绝，但界面误导）；失效原因为识别/导入/权限变化时收起候选，必须重新识别（新增单测与走查断言）。
- 聚合项原显示内部 ID 与原因码；改为服务名与中文原因。
- 轮询遇网络错误时“识别中”状态会一直禁用按钮；改为仅在真实轮询进行时视为忙碌。
- 以上修复后重跑类型检查、lint、全部单测、两种构建与真实浏览器走查，结果如上。

## 待完成与回退

- 安装/运行执行入口、隔离副本、运行授权与就绪/停止状态属步骤 5–7，按钮仅占位禁用；`executable` 未来变为 true 也还需前端隔离授权开关。
- 过期批准只用受控账本夹具验证；真实 Agent 批准与真实模型成功未在浏览器中走（避免模型调用），已发布补充显示仅有单测和步骤 2 的 API 证据。权限变化/导入任务变化的计划失效只在后端单测覆盖。
- 管理员查看他人导入应用时资格读取为 404，仍显示原运行按钮（原有管理员路径，未改）；扫描范围下拉只列已知目录，其他目录需通过手动配置；诊断码原样显示为辅助信息。
- 回退：用证据根 `before/` 恢复 3 个修改文件，删除 `features/project-import/startup.ts`、两个新增测试与 `scripts/verify-startup-workbench-real.mjs`；无数据库或后端变化。
