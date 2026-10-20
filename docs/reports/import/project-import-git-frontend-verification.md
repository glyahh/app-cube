# 正式 Git／凭据页面联调证据

2026-10-04 当前增量：[来源请求恢复证据](project-import-source-recovery-verification.md)记录 owner 绑定的只读找回、刷新恢复与 Windows Git 只读对象清理；Java 225 项中 224 通过、1 未执行，前端 208 项通过。真实请求丢失/刷新/取消及已有 GitHub、文件夹回归通过；旧清理失败样本保留，完整进程树/硬资源限额及第一项整体验收仍未通过。识别/安装/服务选择下一步仅交互预览，后端待评审。

2026-10-04。第一项内部增量；正式入口 `http://localhost:5173/project/import`，选择“Git 仓库”。沿用已批准界面和共享导入表单，没有把隔离预览或 Mock 逻辑引入生产路径。

## 当前实现

- GitHub/Gitee 平台、HTTPS 仓库、公开身份或单个已保存凭据；读取真实全部 heads，单次选择一个分支，实际内容进入确认。
- 多凭据真实列表、添加、备注、替换、删除；只接收元数据白名单，Token 仅在当前输入和请求正文。保存即清空输入，错误不传播 Axios 配置，帮助按问号显示。
- 删除当前凭据后要求显式选择身份，不自动退回公开读取。备注不撤销任务；替换/删除根据真实停止结果撤销界面资格，停止失败不冒充取消成功。
- 名称/范围改变后使用固定快照重建接口；提交前重新核对来源/主任务、SHA、内容 hash、范围和名称，只有真实应用结果能进入本次工作台，不自动生成、安装或运行。
- 请求绑定登录用户与页面代次；离开页面尝试取消已知来源。取消等待尚未到达的真实创建响应取得 ID，再核对后端停止与清理，迟到 heads 不能恢复导入资格。
- 创建响应丢失时明确报“来源创建结果未确认”，阻止当前页面继续创建，不宣称已停止。**归属内任务列表/显式恢复尚未实现**，刷新/断网后找回创建结果仍是下一步待补业务，不把当前取消测试当成所有断网恢复通过。
- 文件夹入口保留。取消后的晚到普通结果不跳转；只有适配器重读服务器 SUCCEEDED 与实际应用结果，才标记 ALREADY_SUCCEEDED，不把实际完成伪装成取消。

## 版本与环境

`code_board` / `d4528044d36f38a91567cb5ceb138169af6a873d` + 未提交增量；本机 Windows，真实 PostgreSQL/Redis、Java 后端、Vite 5173、Edge headless。复用既有依赖；没有新迁移、提交/推送、生产发布或外部付费服务。

命令在前端目录执行，退出码均为 0（失败历史单列）：

```powershell
npm run test:unit
npm run build
node scripts/verify-git-import-real.mjs
$env:IMPORT_GIT_PLATFORM='Gitee'; node scripts/verify-git-import-real.mjs
node scripts/verify-git-import-cancel-real.mjs
node scripts/verify-folder-import-real.mjs
```

日志根 `temp/import-backend-publication-20261004/`：

- `git-frontend-full-unit-qualified.log`：27 文件、203 测试，全部通过。不是最终业务验收；新 Git 适配器 10 项、导入服务 5 项、凭据 6 项、正式文件夹视图 7 项及其他现有前端回归。
- `git-frontend-build-current.log`：type-check + Vite 构建通过，保留既有大 chunk 警告，没有提高警告阈值或删除功能制造成功。
- `git-frontend-browser-current.log` 对应 `temp/git-import-real-1791100991826/result.json`：GitHub 8 组真实浏览器流程通过，最终 App/任务/SHA/字节一致，无自动执行；这是当时版本，后续取消判定增量需要最终版本再跑。
- `git-frontend-gitee-timeout-qualified.log` 对应 `temp/git-import-real-1791101321386/result.json`：当前业务版本 Gitee 8 组通过；394 个最终文件逐一比对保护字节、大小与 SHA-256，结果包含完整文件证据。1440/720 宽度检查无横向溢出，截图只辅助真实交互证据。
- `git-frontend-cancel-qualified.log` 对应 `temp/git-import-cancel-1791100951143/result.json`：3 组取消时序通过；明确注入的是**真实创建完成后的响应延迟**，没有伪造任务/后端结果；真实 CANCELLED/DONE、目录不存在、迟到结果不恢复资格。不是所有中断恢复验收。
- `folder-frontend-git-regression.log` 对应 `temp/folder-import-real-1791100715487/result.json`：6 组真实文件夹回归通过；最终字节、归属、敏感过滤、浏览器权限隔离及无自动执行。后续取消语义增量需当前版本再跑。

### 最终版本重启后重跑

按项目标准执行 `powershell -ExecutionPolicy Bypass -File '.\scripts\restart-backend.ps1' -TimeoutSeconds 180 -LogDirectory temp/import-backend-publication-20261004/runtime-git-frontend`，`restart-git-frontend.log` 与 runtime 日志确认 PID 48508、8124 `/api` 就绪。不是仅用端口证明业务：随后实际执行并通过：

- `git-frontend-after-restart.log` / `temp/git-import-real-1791101656474/result.json`：GitHub 8 组真实业务与最终应用/完整文件证据。
- `folder-frontend-after-restart.log` / `temp/folder-import-real-1791101676992/result.json`：文件夹 6 组真实业务回归。
- `git-frontend-cancel-after-restart.log` / `temp/git-import-cancel-1791101683097/result.json`：3 组真实创建响应延迟/取消/清理/迟到资格检查。

本阶段源码 hash、Git 状态与结果汇总见 `temp/import-backend-publication-20261004/git-frontend-current-artifacts/manifest.json`。Gitee 8 组发生在同一 Java/前端业务源码、重启前 PID 824；不能把它写成 PID 48508 的 Gitee 重跑。

## 失败保留与修正

1. `git-frontend-typecheck-qualified.log` 保留 Radio 事件 target.value 可选的类型失败；改为边界校验，不通过忽略 TypeScript 修复。
2. 首轮浏览器定位失败由 Ant Design 双汉字自动空格、虚拟选项隐藏节点造成；保留 `git-frontend-browser-first/diagnostic/button-qualified.log` 及对应失败截图/报告，改用准确可见控件定位，不改变产品来适配测试。
3. 新适配器最初把服务器排除项当作扁平文件；核对实际 `ExcludedFile(file,reason)` 契约后修正，单位测试同步真实 wire shape，没有只让 Mock 通过。
4. `git-frontend-full-unit.log` 保留 5 个旧正式视图测试失败：旧 Mock 缺少真实偏好加载门禁，且错误期望清单不包含排除文件。补真实接口契约后还核实了取消晚到结果，修正客户端资格并新增真实 SUCCEEDED 重读测试；203 项重跑通过。
5. Gitee 首轮确认观察超时：脚本默认 15 秒等待提交响应，不能覆盖前置 394 文件重建。保留 `git-frontend-browser-gitee.log` 及失败报告；观察窗口改为覆盖现有有界读取流程，仍须检查实际最终事务/全部文件，不以延长时间代替成功标准。
6. 首轮取消检查在终态仍查“确认导入”，实际是“重新导入”；保留失败，修正控件定位后 3 组通过，没有降低清理断言。

## 剩余边界

有效私有仓库真实成功验收按用户要求暂缓，G02/G04 保持 DEFERRED/NOT_RUN；本次浏览器仅用明确无效的虚构 Token 验证负例和元数据管理。

来源创建响应丢失/重启恢复、故障清理/硬资源边界、特殊 Git 资源、识别/安装授权/多服务运行、独立执行副本与第一项全部矩阵仍需继续。旧 Agent/运行/发布入口的导入保护门禁不提前放开。之前一次文件夹发布失败原始原因仍未确认，见范围重建证据，不以此次重跑冒充已修复。

回退：核实在途来源实际停止，保留任务、密钥和已完成应用；仅移除本阶段 Git 槽位/组件/适配器的精确增量，保留真实文件夹页面和已有改动。没有数据库回退步骤，不清理工作区或改 Git 历史。
