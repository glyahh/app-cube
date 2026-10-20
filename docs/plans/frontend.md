# 前端设计说明（ai-generate-code-frontend）

> 类型：设计与实施方案；目标架构、阶段状态不等于当前交付结论。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

本文档由对仓库内本目录的并行检索汇总而成，用于 AI 协作与新人上手：**技术栈、目录职责、路由、数据流、有效文件清单、已知缺口**。

---

## 1. 定位与栈

- **定位：** Glyahh AI 代码生成平台的前端：应用列表与创建、应用内对话与流式生成（含工作流 SSE）、部署预览、用户/管理员后台。
- **运行时：** Vue 3 + Vite 7 + TypeScript；UI 为 **Ant Design Vue 4**（全量注册）；状态 **Pinia**；路由 **Vue Router 5**；HTTP **Axios**（`src/request.ts`）。
- **Node：** `package.json` 要求 `^20.19.0 || >=22.12.0`。
- **测试：** 未配置 Vitest / Playwright / Cypress；无 `*.spec.ts` / `*.test.ts`。

---

## 2. 工程与脚本

| 脚本 | 说明 |
|------|------|
| `npm run dev` | Vite 开发服务（默认端口一般为 5173） |
| `npm run build` | 并行：`vue-tsc --build` + `vite build` |
| `npm run openapi2ts` | 执行 `openapi`（`openapi-ts-request`），需后端已启动 |
| `npm run lint` | 顺序：`oxlint` → `eslint` |
| `npm run format` | Prettier 写回 `src/` |

**OpenAPI 生成：** `openapi-ts-request.config.ts` — `schemaPath: http://localhost:8124/api/v3/api-docs`，输出 `./src/api`，请求封装 `@/request`。

**开发代理：** `vite.config.ts` 将 `/api`、`/static` 代理到 `http://localhost:8124`，并对部分 `Location` 头做改写，避免预览 iframe 直连后端源。

**别名：** `@` → `src/`。

**环境变量（当前仓库）：** 仅见 `.env.development` — `VITE_DEPLOY_DOMAIN`、`VITE_API_BASE_URL`（一般为 `/api`）、`VITE_OSS_ORIGIN`。

---

## 3. 信息架构与路由

`src/router/index.ts` 使用 `createWebHistory`。

| path | name | 组件 |
|------|------|------|
| `/` | home | `page/HomeView.vue` |
| `/user/login` | user-login | `page/User/UserLogin.vue` |
| `/user/settings` | user-settings | `page/User/UserSettings.vue` |
| `/admin/users` | admin-users | `page/Admin/AdminHome.vue` |
| `/admin/apply` | admin-apply | `page/Admin/AdminApplyManage.vue` |
| `/admin/apps` | admin-apps | `page/Admin/AdminAppManage.vue` |
| `/admin/chats` | admin-chats | `page/Admin/AdminChatManage.vue` |
| `/code/generate` | code-generate | `page/App/CodeGenerateEntry.vue` |
| `/app/:id/chat` | app-chat | `page/App/AppChatView.vue`（`props: true`） |
| `/app/:id/edit` | app-edit | `page/App/AppEditView.vue`（`props: true`） |

---

## 4. 布局与核心页面职责

- **`layouts/BasicLayout.vue`：** 顶栏 + 内容区 + 底栏插槽式布局。
- **`components/GlobalHeader.vue`：** 导航、管理员菜单、登出；依赖 `UserLogin` store。
- **`components/GlobalFooter.vue`：** 页脚文案。
- **`components/CodeBlock.vue`：** 对话区代码块展示（高亮、复制、流式态、多文件标题等）。
- **`page/HomeView.vue`：** 首页与应用列表、创建、部署相关操作、`CodeGenTypeEnum` 与 workflow 相关开关（具体以后端能力为准）。
- **`page/App/AppChatView.vue`：** **核心对话页** — Markdown/围栏流式解析、工作流步骤过滤（`workflowChatFilters`）、工具输出块解析（`toolOutputBlockParsers`）、预览 iframe、`visualWebsiteEditor` 等。
- **`page/App/AppEditView.vue`：** 应用元数据编辑（用户/管理员接口分支）。
- **`page/App/CodeGenerateEntry.vue`：** 简短创建流程入口，跳转聊天等。
- **Admin / User：** 命名即职责（用户管理、申请审批、应用管理、聊天记录查询、登录与设置）。

---

## 5. 状态与请求

- **`stores/UserLogin.ts`：** 登录用户；`App.vue` 挂载时会 `fetchLoginUser()`。
- **`request.ts`：** `baseURL` 来自 `VITE_API_BASE_URL ?? '/api'`，`withCredentials: true`，超时 600000ms；响应里对大整数做 `transformLongToString`；`data.code === 40100` 时跳转登录（部分 URL 除外）。

---

## 6. API 层（生成物）

`src/api/` 由 OpenAPI 生成并手工聚合，**勿手改生成文件逻辑**（改后端后执行 `npm run openapi2ts`）：

- `appController.ts`、`chatHistoryController.ts`、`chatToGenCodeController.ts`、`staticResourceController.ts`、`userController.ts`、`test.ts`
- `index.ts`、`types.ts`

---

## 7. 工具模块（业务逻辑密集区）

| 文件 | 作用摘要 |
|------|----------|
| `utils/workflowChatFilters.ts` | `[workflow]` 步骤、阶段状态、步骤行合并与噪声过滤 |
| `utils/markdownParser.ts` | 流式 Markdown + 代码围栏对齐、多文件标题与缓冲闭合 |
| `utils/toolOutputAdapters/toolOutputBlockParsers.ts` | 从工具输出抽取「写入/修改文件」围栏 |
| `utils/visualWebsiteEditor.ts` | iframe 内点选元素、postMessage、拼进 prompt |
| `utils/deployUrl.ts` | 部署 URL 与 `deployKey` 拼接、本地兜底 |
| `utils/appApplyNotification.ts` | 申请审核结果的本地通知状态 |
| `utils/CodeGenTypeEnum.ts` | `html` / `multi_file` / `vue_project` 等与 UI 展示 |

---

## 8. 鉴权与已知缺口

- **`access/access.ts`：** 实现了 `beforeEach`（管理员路由需 `admin`；`/user/settings` 需登录），但 **`main.ts` / `router/index.ts` 未 `import` 该模块**，当前工程内无其它引用 — **路由级守卫可能未生效**；实际体验部分依赖 `request.ts` 的 40100 与各页自行校验。
- **`App.vue`：** 存在对 `testUsingGet` 的调用与 `console.log`（每次挂载打测试接口）— 生产前宜移除或加环境开关。

---

## 9. 静态资源与入口

- **`src/assets/base.css`、`src/assets/logo.svg`：** 全局样式与 Logo。
- **`index.html`：** 引用 `/favicon.ico`；仓库内**未检出** `public/` 目录或 `favicon` 文件 — 若浏览器报 404，可补 `public/favicon.ico` 或改链接。

---

## 10. 文档与其它

- **`../archive/notes/plan-index-20261020.md`：** 通用 Vite/Vue 说明。
- **`doc/后端接口文档.md`：** 后端接口说明（与 OpenAPI 并存时以运行中 `/v3/api-docs` 为准）。

---

## 11. 有效文件清单（本仓库快照）

下列路径均相对于 **`ai-generate-code-frontend/`**，含配置与源码；**不含** `node_modules`、构建产物 `dist`。统计以检索时仓库内容为准（约 60+ 项）。

### 根与配置

- `.editorconfig`
- `.env.development`
- `.gitattributes`
- `.gitignore`
- `.oxlintrc.json`
- `.prettierrc.json`
- `env.d.ts`
- `eslint.config.ts`
- `index.html`
- `openapi-ts-request.config.ts`
- `package.json`
- `package-lock.json`
- `../archive/notes/plan-index-20261020.md`
- `tsconfig.json`
- `tsconfig.app.json`
- `tsconfig.node.json`
- `vite.config.ts`

### 文档

- `doc/后端接口文档.md`
- `frontend.md`（本文件）

### `src/`

- `src/App.vue`
- `src/main.ts`
- `src/request.ts`
- `src/access/access.ts`
- `src/assets/base.css`
- `src/assets/logo.svg`
- `src/components/CodeBlock.vue`
- `src/components/GlobalFooter.vue`
- `src/components/GlobalHeader.vue`
- `src/layouts/BasicLayout.vue`
- `src/router/index.ts`
- `src/stores/UserLogin.ts`
- `src/api/appController.ts`
- `src/api/chatHistoryController.ts`
- `src/api/chatToGenCodeController.ts`
- `src/api/index.ts`
- `src/api/staticResourceController.ts`
- `src/api/test.ts`
- `src/api/types.ts`
- `src/api/userController.ts`
- `src/page/HomeView.vue`
- `src/page/Admin/AdminAppManage.vue`
- `src/page/Admin/AdminApplyManage.vue`
- `src/page/Admin/AdminChatManage.vue`
- `src/page/Admin/AdminHome.vue`
- `src/page/App/AppChatView.vue`
- `src/page/App/AppEditView.vue`
- `src/page/App/CodeGenerateEntry.vue`
- `src/page/User/UserLogin.vue`
- `src/page/User/UserSettings.vue`
- `src/utils/CodeGenTypeEnum.ts`
- `src/utils/appApplyNotification.ts`
- `src/utils/deployUrl.ts`
- `src/utils/markdownParser.ts`
- `src/utils/visualWebsiteEditor.ts`
- `src/utils/workflowChatFilters.ts`
- `src/utils/toolOutputAdapters/toolOutputBlockParsers.ts`

### 未纳入清单

- 本地 `node_modules/`、`dist/`、IDE 私有配置等（若存在由 `.gitignore` 管理）。

---

## 12. UI/UX 协作提示（给 Agent）

- 对话与生成体验的核心在 **`AppChatView.vue`** 与 **`utils/*`** 流式/工作流解析；改交互前先理清 SSE 事件形态与后端约定。
- 新增后台接口优先 **OpenAPI 生成** 再使用，保持 `types.ts` 与后端一致。
- 样式以 **Ant Design Vue** 为主，全局基底为 `base.css`；避免在业务组件中硬编码与现有布局冲突的魔法数。
- 修改路由权限时，建议 **显式在 `main.ts` 或 `router` 中挂载 `access`**，避免文档与行为不一致。

---

*生成方式：多路并行检索子工程目录后人工合并；若你新增文件，请同步更新第 11 节或改为「按 glob 维护」流程。*
