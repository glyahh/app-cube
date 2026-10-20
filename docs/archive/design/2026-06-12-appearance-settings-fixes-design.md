# 外观设置缺陷修复方案

## 背景

修复外观设置页 `/user/appearance` 的三个功能缺陷，并删除一行无效设置。

## 改动清单

### 模块 A — 字体设置修复

**根因：** `resolveThemeConfig()` 未向 Ant Design ConfigProvider 传递 `fontSize`，ant 组件无感知。

**方案：** `resolveThemeConfig()` 加入 `fontSize` 参数，写入 `token.fontSize`；`App.vue` 调用时传入 `appearanceStore.fontSize`。

**涉及文件：**
- `src/stores/appearance.ts` — `resolveThemeConfig` 签名扩展
- `src/App.vue` — 调用处补传 `fontSize`

### 模块 B — 深色模式顶栏适配

**根因：** `BasicLayout.vue` `.layout-header` 写死 `background: #fff`；`GlobalHeader.vue` 约 10 处硬编码浅色色值未随 `[data-theme="dark"]` 变化。

**方案：** 将写死色值替换为 `theme.css` 中已定义的 CSS 变量（`--bg-card`、`--text-base`、`--border-color`、`--text-secondary`）。

**涉及文件：**
- `src/layouts/BasicLayout.vue` — `.layout-header` 改用 `var(--bg-card)`
- `src/components/GlobalHeader.vue` — 10 处色值替换为 CSS 变量

### 模块 C — 预览面板默认展开开关接入

**根因：** `store.previewExpanded` 在 `AppChatView.vue` 中未读取，预览面板始终显示。

**方案：** 模板 `.preview-panel` 加 `v-if="appearanceStore.previewExpanded"`；动态 grid 布局（预览隐藏时自动单栏）。

**涉及文件：**
- `src/page/App/AppChatView.vue` — 模板 + 样式

### 模块 D — 删除「聊天默认生成模式」

**删除内容：** 设置页该行 UI、store 接口/默认值/SECTION_KEYS、AppChatView 中对该字段的引用改为硬编码 `'legacy'`。

**涉及文件：**
- `src/page/User/UserAppearanceSettings.vue`
- `src/stores/appearance.ts`
- `src/page/App/AppChatView.vue`

## 验收条件

见主任务需求 2 中的分场景列表。
