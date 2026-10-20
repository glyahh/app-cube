# iframe 预览与可视化编辑（技术说明）

> 类型：技术说明与学习复盘；旧架构、类名和数字按原记录时点理解。 当前事实以[项目状态](../status/current.md)及对应版本源码、验收证据为准。

说明对象：`AppChatView.vue`（预览 iframe）、`StaticResourceController.java`（静态预览路径）、`visualWebsiteEditor.ts`（可视化编辑）。

---

## 1. 预览 iframe：是不是「轮询」？

- **iframe 内页面**：不是轮询。`<iframe :src="previewUrl">` 由浏览器按普通文档方式加载 `index.html` 及静态资源。
- **父页面**：生成结束后用 `fetch` 探测预览入口是否就绪，并用 `setTimeout` 递归重试（约 10 次上限），就绪后增大 `iframeKey` 刷新 iframe；属于「等 dist 就绪」的探测，不是 iframe 内业务轮询。
- **聊天流**：使用 SSE（`EventSource`），不是轮询拉消息。

---

## 2. 预览 URL 与本地目录

- 前端默认：`PREVIEW_BASE_URL` → `/api/static` 下拼接 `/{codeGenType}_{appId}/dist/index.html` 等。
- 后端：`StaticResourceController` 将 `/api/static/{deployKey}/**` 映射为磁盘路径：
  - 根目录：`{user.dir}/temp/code_output`
  - 文件：`PREVIEW_ROOT_DIR + "/" + deployKey + resourcePath`
- 示例：`deployKey = vue_project_xxx`，`resourcePath = /dist/index.html` → `temp/code_output/vue_project_xxx/dist/index.html`。
- 机制：**HTTP 进入 Spring，控制器拼接 `File` 路径读盘**，并非操作系统层面的 URL 自动映射。

---

## 3. `ResponseEntity<Resource>`

- **`ResponseEntity`**：封装完整 HTTP 响应（状态码、头、体）。
- **`Resource`**：可读资源抽象，此处多为 `FileSystemResource` 读本地文件。
- **用途**：在受控的头与状态码下返回文件流，适合静态预览与文件下载。

---

## 4. `index.html` 如何出现在 iframe 中

- 父页 Vue **不会**把 HTML 字符串挂进自身组件树来「渲染预览」。
- 浏览器根据 `iframe.src` 在 **子文档**中导航、解析 HTML、执行脚本并拉取资源；打包后的子应用运行在 **iframe 内**，与外层聊天应用相互独立。
- `probePreviewReady` 的 `fetch` 仅用于探测 URL 是否可访问，**不**负责把响应写入 iframe；展示依赖 **`src` 导航**。

---

## 5. iframe 模板属性简表

| 属性 | 作用 |
|------|------|
| `v-else` | 与「未生成」空状态互斥，有预览时才挂载 iframe。 |
| `ref="previewIframeRef"` | 在脚本中获取该 iframe 的 DOM 引用，供可视化编辑、样式恢复等使用。 |
| `:key="iframeKey"` | 供 Vue 复用判断；变更时销毁并重建 iframe，强制按 `src` 重载，常与 `?v=` 配合缓解缓存。 |
| `:src="previewUrl"` | **决定加载地址**；预览内容主要由此决定。 |
| `@load` | 子文档加载完成回调；编辑模式下可重新 attach，非编辑模式可清理残留覆盖层。 |

---

## 6. `ref`、`key`、`src` 分工

- **`src`**：加载目标 URL。
- **`key`**：面向 Vue 的实例标识；变更导致 iframe 节点重建，从而重新加载。
- **`ref`**：面向脚本的 DOM 引用；便于调用需 DOM 入参的 API；不决定 URL。无 `ref` 时也可用 `id` 或 `querySelector` 定位同一节点。

---

## 7. DOM 与父子文档

- **DOM**：浏览器将文档解析为内存中的元素树。
- **父文档**：树中包含 `<iframe>` 元素节点。
- **子文档**：iframe 内加载的页面拥有 **另一棵** DOM 树，位于独立浏览上下文。
- **Vue `ref()`**：运行时在 JS 中的响应式引用容器（`.value` 指向节点等），不属于 DOM 树本身的结构。

---

## 8. 可视化编辑：父页 shield 与子页注入

### 8.1 子页鼠标监听在 shield 下为何不明显

- 父页在 iframe 区域上方放置 **`parentShield`**（透明、`pointer-events: auto`），指针事件由父层优先接收。
- 子文档内注入的鼠标类监听依赖事件进入子文档；被遮挡时常表现为子页监听不触发。
- 主路径：父页 shield 监听 → 坐标换算后用子文档 **`elementFromPoint`** → 在**父页**绘制 hover / 选中框。

### 8.2 仅依赖子页注入时的常见不稳定因素

- **时机**：`about:blank` 或未完成导航时注入易被后续导航覆盖。
- **SPA**：预览应用自身路由与点击处理与注入逻辑竞争同一事件链路。
- **跨域 / CSP**：无法访问子文档或禁止脚本注入时，子页方案无法成立。

### 8.3 子页注入的剩余职责（与父页协同）

| 职责 | 说明 |
|------|------|
| 子文档样式 | 在预览页内注入编辑相关 CSS。 |
| 双击与 `WIN_PASS_THROUGH` | 子页默认拦截点击以防误触；父页双击导航时通过标志位临时放行。 |
| 路由监听与清理 | 子窗口 URL / 路由变化时清除父页选中态与框线。 |
| `detach` / `clearSelection` | 退出编辑或卸载时清理子文档内残留高亮与状态。 |
| `postMessage` | 在事件可进入子文档的场景下可作为选中数据通道；与 shield + `elementFromPoint` 主路径并存。 |

### 8.4 结构归纳

- **视觉框线**：主要在父页绘制。
- **指针主路径**：父页 shield + 读子文档几何与命中测试。
- **子页注入**：样式、双击放行、路由相关清理、卸载清理，以及与父页约定的消息/标志位协同。

---

## 9. 相关源码路径

- 预览与聊天页：`ai-generate-code-frontend/src/page/App/AppChatView.vue`
- 可视化编辑：`ai-generate-code-frontend/src/utils/visualWebsiteEditor.ts`
- 静态文件映射：`src/main/java/com/dbts/glyahhaigeneratecode/controller/StaticResourceController.java`
- 开发代理：`ai-generate-code-frontend/vite.config.ts`（`/api`、`/static`）

---

## 10. 总结

1. **预览加载**：由 `iframe.src` 触发浏览器子文档导航；父页用探测与 `iframeKey` 处理构建时延与缓存，聊天使用 SSE。
2. **静态地址**：`/api/static/{deployKey}/...` 由控制器映射到 `temp/code_output` 下同名目录文件。
3. **Vue 与 iframe**：外层 Vue 只负责 URL 与生命周期；预览应用在 iframe 内独立运行。
4. **模板 API**：`src` 定资源；`key` 控重建；`ref` 供脚本持有 DOM 引用。
5. **DOM**：父、子各一棵文档树；`ref` 为 JS 侧引用，非 DOM 内嵌字段。
6. **可视化编辑**：父页透明层拦截指针并用子文档 `elementFromPoint` 做命中，框线画在父页；子页注入承担样式、导航协同、状态清理及在受控场景下放行双击等行为。
