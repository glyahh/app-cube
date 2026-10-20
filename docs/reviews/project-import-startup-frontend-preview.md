# 启动识别、授权与服务选择：前端评审

> 类型：前端预览、接入或评审记录；不代替完整业务验收。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

2026-10-04 最新确认：用户明确回复“可以接后端”。本版交互已获批准，后端按 [小步计划](../plans/project-import-startup-backend-steps.md)推进；内部只读识别与快照的局部测试见 [实际证据](../reports/import/project-import-startup-backend-verification.md)。下文等待评审为原时点状态，界面中的安装/运行仍是隔离样本，尚未联调真实执行。

2026-10-04。范围仅第一项已有项目导入，后端未实现，等待本版交互评审。使用隔离内存样本，不读取或执行导入工程、不调用生产 API 或模型。

## 可交互入口

`http://127.0.0.1:5175/import-preview.html#/?startup=1&source=git`

沿用原导入/Agent 工作台。填任一合法的对应平台仓库地址、读取分支、选分支、确认导入，即进入工作台。导入后可以拒绝安装；“运行”打开服务选择。这个入口的仓库、识别、安装、运行都是评审样本；正常用户文案不显示 Mock/待实现标语，真实边界记录于本文而非伪装为后端交付。

通过 `AppChatView` 的 `runtime-actions` 插槽复用既有工作台，不更改生产默认行为或后端执行门禁。新组件只从隔离预览入口导入，正式业务没有引用。已有 Git/凭据前端授权不扩展为这部分后端授权。

## 本版交互

- 服务初次不选中。名称、目录、启动命令直接显示；依据、识别来源、环境与必要构建按需展开。可修改已有候选或补充手动服务；保存不运行，也不把手动配置当成依赖已就绪。
- 支持手选工程内目录，未选择的服务不启动。安装展示服务、实际拟议安装命令及工程脚本风险；拒绝保留原清单和安装入口。独立安装不启动；发起运行后的批准只继续所选方案。
- 未识别部分可以授权 Agent 或手动填写。Agent 的读取范围与模型传输风险明确提示，拒绝不产生补充候选。扫描失败/不完整与未识别分开，不能自动转 Agent。
- 单项失败保留其他服务，显示部分失败，重试只针对失败项。每项可以停止；安装中的取消阻断迟到完成和自动启动。不能用这些内存状态证明真正的进程停止。
- 本版采用无自动勾选的默认值，作为本次前端评审内容，不冒充此前已确认的产品细节。

## 待实现契约，不是现有接口

沿用 `project-import-startup-plan-contract.md` 和共同版本契约；具体 URL/DTO 在后端小步骤中落地，不把旧 `/app/{appId}/runtime/start` 直接当本版服务选择接口。

| 操作 | 后端必须提供/核验 |
|---|---|
| 只读扫描与手选目录 | owner/app/import task/context epoch、完整内容身份、扫描规则、范围、分层状态、候选证据；扫描不执行脚本、不读敏感文件 |
| 手动配置与 Agent 补充 | 应用内 config revision、路径/命令/入口校验；明确模型授权范围，迟到结果不能覆盖新版或其他应用 |
| 确认所选服务 | 不可变 plan revision；安装、共享准备、必要构建、启动、环境、资源/权限、就绪步骤列明，选择和授权分别核验 |
| 安装与运行 | 独立执行副本，逐项资源/日志/结果和取消记录，真实依赖检查/安装凭据；拒绝、失败或撤销不得启动 |
| 部分失败/重试/停止 | 成功项保持运行；失败残留与版本重新核验，不重复成功项；未确认停止不能标为停止完成 |

## 测试要求

后端必须继续逐项拆分现有 S01–S07、D01–D08、R01–R08、E03/E06，不因前端通过改变 58 组计划：根/嵌套/聚合/库/干扰目录、损坏/歧义/超限、手动优先、模型拒绝/失败/迟到、安装生命周期/中断/版本变化、共享步骤、未选不运行、多服务真实交互、资源清理/停止失败及其他应用不受影响。必须留下单元、集成、沙箱/浏览器、故障与最终业务状态证据。

前端浏览器脚本 `ai-generate-code-frontend/scripts/verify-startup-preview.mjs` 禁止任何 `/api`、`/static` 和外部网络请求；覆盖多选/拒绝安装/部分失败/重试/独立停止、局部未知/拒绝 Agent/手动配置、批准 Agent/推断标签、扫描不完整/手选目录、安装失败重确认及安装中取消。1440/720 宽度实际检查溢出与布局，截图仅作证据，不替代可交互入口。

最新浏览器/构建结果与哈希记录在同一目录的评审证据 manifest，未记录结果的子项保持 NOT_RUN。此时新业务后端、真实安装/运行、停止失败、后端重启对账、代码变更后授权失效均未通过验收。不能将模拟“就绪”当作项目运行或交付通过。

## 本轮证据

- `node scripts/verify-startup-preview.mjs`：退出 0，6 组前端交互检查通过；`temp/import-startup-preview-1791104319941/result.json`，禁止请求与 pageerror 均为 0。未连接后端，不当作 S/D/R 整组业务验收。
- `npm run test:unit -- --run`：退出 0，原前端回归 208 项/28 文件通过，日志 `temp/import-backend-publication-20261004/startup-preview-frontend-unit-current.log`；既有测试装配的 Vue warning 保留，没有吞掉。
- `npm run build`、`node node_modules/vite/bin/vite.js build --config vite.import-preview.config.ts`：退出 0，正式与隔离构建通过，保留大 chunk 警告。对应 `startup-preview-frontend-build-current.log`、`startup-preview-isolated-build-current.log`。
- 哈希与范围：`temp/import-backend-publication-20261004/startup-preview-current-artifacts/manifest.json`。标准第一项矩阵索引校验退出 0，仍为 58 组待执行计划，不是业务通过数。
- 首轮暴露嵌套弹窗遮挡，已为授权/手动配置明确上层 z-index，并真实重测。另修正 Ant 两字按钮/Select 输入覆盖的测试定位，以及复选框被通用 label 样式误改为上下排列的问题；不用 force 点击掩盖遮挡。首次失败日志保留于同一日志根目录的 `startup-preview-browser-first.log`、`startup-preview-browser-selector-qualified.log`、`startup-preview-browser-current.log`、`startup-preview-browser-modal-qualified.log`。

## 运行与回退

已有预览 Vite 监听 5175，无需新依赖。需重新启动时，从前端目录执行 `npm run dev -- --config vite.import-preview.config.ts`；入口使用隔离 Vite 配置，不替换正式 5173。

回退本增量只移除预览组件与 `runtime-actions` 插槽接入，保留用户和已批准业务文件。无新数据库迁移、后端接口或外部服务。来源恢复 V18 属于独立已验证增量，不因撤回预览而编辑已应用迁移。
