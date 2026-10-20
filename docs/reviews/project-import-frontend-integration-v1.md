# 第一项：文件夹导入正式前端接入

> 类型：前端预览、接入或评审记录；不代替完整业务验收。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

日期：2026-10-03。用户已确认此前文件夹导入及 Agent 工作台衔接的前端界面，授权正式前端开发。本轮只完成文件夹表单的正式入口与代码接入，不开发业务后端、不提前推进其他三项。

## 范围与真实状态

- 正式路由 `/project/import`，从共用导航和首页“导入项目”进入；复用 BasicLayout、外观设置和现有登录。未登录转登录页并保留目的地。
- 正式界面与独立预览共用 `features/project-import/ProjectImportForm.vue`；范围计算迁至同目录 `manifest.ts`，预览的旧模块只转导出公共计算及保留隔离样例。没有复制两套表单视觉，没有新增依赖或装饰介绍。
- 正式页面 `page/Import/ProjectImportView.vue`：文件夹选择、名称限制、默认依赖/产物保留、敏感配置默认排除、临时过滤、摘要/展开清单、空来源、失败重试、清空与取消。
- 真实 File 仅保留在当前页面内存供未来上传契约使用，不读内容、不上传、不写日志、不放浏览器持久存储；清空、换账号、退出或页面销毁释放引用。
- 提交冻结名称、范围和当前用户；本地代次与 AbortController 只用于前端迟到结果隔离，不冒充服务器任务或后端取消。换账号清空来源与偏好。
- **正式提交和默认偏好保存尚未连接后端。** `features/project-import/service.ts` 是待实现服务边界，直接明确报“导入服务尚未开通”或“偏好保存服务尚未开通”。没有猜测不存在的 API 路径、上传文件、虚构应用、伪造保存、定时成功或 Mock 回退。
- 待实现提交契约包含名称、选项、相对路径与 File，并接受取消信号；返回应用/任务标识只是最小类型草案，不是完整后端协议。`ownerId` 仅用于前端请求关联，服务器必须以会话鉴权，不信任前端路径、大小、用户标识或敏感判断；最终内容版本、提交资格、幂等及取消确认仍按工程契约实施。
- 成功路由条件仅用单元测试注入结果检查；真实导入结果、服务端提交资格/任务/内容版本、安装询问的正式工作台联调尚未实现。生产不会使用 `preview-*` 应用或任务 ID，不附加自动生成参数。
- 共用导航窄屏菜单恢复可伸缩，使用已有 Ant Design 的折叠菜单；不是隐藏页面溢出。安装、服务扫描、多服务选择、GitHub/Gitee、Token、默认偏好加载及另三项仍待后续小步骤，未伪造通过。

## 入口与运行

正式前端：[导入项目](http://127.0.0.1:5176/project/import)。本轮启动现有 Vite，`127.0.0.1:5176`，进程 PID `42644`；日志 `temp/import-frontend-integration-20261003-200139/vite-formal.log`。正式页面使用正常鉴权与 API 配置，没有演示身份；需要实际后端登录服务才能手动进入。核对时 `8124` 没有监听，本轮不启动后端。

交互评审仍可使用独立入口：[文件夹导入](http://127.0.0.1:5175/import-preview.html#/)。此入口继续使用隔离的演示任务与工作台，不混入正式路由；与正式页面共用同一表单。页面不展示开发标签，隔离边界在代码与本文说明。

在 `ai-generate-code-frontend` 目录运行，使用已有依赖：

```powershell
.\node_modules\.bin\vite.cmd --host 127.0.0.1 --port 5176 --strictPort
node scripts/verify-project-import-frontend.mjs
node scripts/verify-import-preview.mjs
```

## 版本、环境与验证证据

分支 `code_board`，HEAD `d4528044d36f38a91567cb5ceb138169af6a873d`，加未提交改动。未暂存、提交、推送或改变用户 Git 历史；保留品牌改名及此前工作台/文档改动。项目未获得正式导入应用或服务器任务，内容版本仅由源文件哈希记录；不拿 Git HEAD 代替未提交文件版本。

环境：Windows/PowerShell，现有 Node 22.21.0、Vue 3.5.27、Ant Design Vue 4.2.6、Vite 7.3.1、Vitest 3.2.7、Playwright 1.62.1；使用已有 Edge，不下载浏览器或安装依赖。

证据目录：`temp/import-frontend-integration-20261003-200139/`。最终源文件哈希、Git 状态和结果记录落在该目录，改前文件位于 `before/`。

| 实际命令/检查 | 结果 | 证据 |
|---|---|---|
| `vue-tsc --build` | 退出码 0 | `type-check-final.log` |
| `vitest run ... --maxWorkers=2`（导入页面、服务边界、权限和清单四文件） | 退出码 0，25 项 | `unit-targeted-first.log` |
| `vitest run --maxWorkers=2` | 退出码 0；25 文件、184 项通过 | `unit-regression-final.log` |
| `vite build --outDir ../temp/import-frontend-integration-build --emptyOutDir false` | 退出码 0；保留 500 kB 单块警告，不改阈值 | `build-production-final.log` |
| `node scripts/verify-project-import-frontend.mjs` | 退出码 0，8 组正式前端浏览器检查 | `formal-browser-final.log`、`temp/formal-import-browser-1791029641299/browser-results.json` 与截图 |
| `node scripts/verify-import-preview.mjs` | 退出码 0，15 组原独立预览回归 | `preview-browser-first.log`、`temp/import-frontend-preview/browser-1791029181969/browser-results.json` |
| 最终正式构建的隔离标识检查 | 无演示装配、身份、定时失败/成功或工作台替换标识 | `verify-build-isolation.mjs`、`build-isolation.log` |

正式浏览器检查从新会话开始，仅在 Playwright 测试上下文拦截现有身份及首页/Loop 列表读取；不替换正式代码里的 Axios/fetch，不连接真实账号或后端。检查匿名重定向、首页入口、默认范围、名称/敏感警告、真实“服务尚未开通”失败、重试、1440/1024/768/375 px 布局、键盘清单、清空/空目录/刷新；无导入/模型/安装/运行或外部请求，来源文件 SHA-256 不变。

单元测试注入成功、取消后迟到成功、换账号及伪造预览应用结果；只是组件边界，不是服务器授权或应用可用资格证明。已有组件 stub/VNode 警告留在日志；真实浏览器无页面/console 错误。

### 保留的失败

- 首轮正式浏览器退出 1：375 px 页宽为 471 px，根因是共用导航窄屏 `flex: none`；恢复菜单伸缩后同一布局断言通过。记录 `formal-browser-first.log`、`temp/formal-import-browser-1791029418420/browser-results.json`。
- 临时诊断脚本退出 1，等待表单超时；其拦截模式也拦住 `/src/api/` 模块，不能作为产品结果。脚本保留 `diagnose-layout-first.mjs`，未用其结果宣称通过。
- 第二轮退出 1：测试定位“清空”未匹配 Ant Design 双字按钮的自动空格；仅改为兼容空格定位，保留所有交互和边界断言后从新会话重跑。记录 `formal-browser-second.log`、`temp/formal-import-browser-1791029541802/browser-results.json`。

## 本项矩阵与跨项边界

F01–F06、U02/U04/U05/U06、C02 只验证了部分前端前置行为；原矩阵中的实际文件落盘、后端越权、长期偏好恢复、最终应用/版本资格、安装与运行仍为 `NOT_RUN`，不得将局部检查升格为整组通过。U01 未实现正式长期偏好，不以页面内存替代。

共用表单/导航/登录变动执行现有前端回归；对应跨功能身份、取消、版本检查只具备前端代次/账号清理证据，跨功能真实一致性矩阵仍未执行。第二、三、四项不宣称联动通过。

## 后续与回退

下一小步骤仍属于第一项：Git 来源及凭据界面需先前端评审；业务后端、真实上传、服务器任务、默认偏好和安装联调另行推进。不因正式路由接入宣称整个第一项已完成。

回退只撤销本次路由、入口、权限条件和共用表单接入差异，参考 `before/` 与差异，不整文件覆盖、不清理工作区、不改提交历史。正式前端 Vite 不依赖后端重启；如需停止，仅停止本轮 `5176` 的前端进程，不影响 `5175` 或已有其他服务。
