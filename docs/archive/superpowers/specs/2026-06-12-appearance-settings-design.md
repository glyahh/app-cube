# 外观设置系统设计文档

> 版本：v1 | 日期：2026-06-12 | 状态：待实施

## 1. 概述

在 `ai-generate-code-frontend` 中新增外观设置能力，包括：GlobalHeader 下拉菜单「外观设置」入口、独立设置页面（Obsidian 式分组列表 UI）、Pinia 驱动的全局主题/偏好状态，持久化到 localStorage。

**影响范围：** GlobalHeader.vue、App.vue、main.ts、router、base.css、CodeBlock.vue、HomeView.vue、AppChatView.vue、CollapsibleCodeBlock.vue

## 2. 架构决策

### 2.1 状态管理：Pinia Store `stores/appearance.ts`

```typescript
// 核心类型
interface AppearanceSettings {
  // A. 基础颜色
  colorMode: 'system' | 'light' | 'dark'        // 外观模式
  primaryColor: string                           // 主题强调色（hex）

  // B. 字体
  fontSize: number                               // 界面字号 12-18
  codeFontSize: number                           // 代码字号 11-16
  codeFontFamily: string                         // 代码字体族

  // C. 界面密度
  compactMode: boolean                           // 紧凑模式
  reducedMotion: boolean                         // 动画减弱

  // D. 本项目专属
  defaultCodeType: 'auto' | 'html' | 'multi_file' | 'vue_project'
  workflowEnabled: boolean                       // 默认启用工作流
  chatGenMode: 'legacy' | 'workflow'            // 聊天默认生成模式
  codeTheme: 'default' | 'high-contrast' | 'soft'
  toolCardCollapsed: boolean                     // 工具卡片默认折叠
  previewExpanded: boolean                       // 预览面板默认展开
  smoothScroll: boolean                          // 流式平滑滚动
}
```

**方法：**
- `load()` — 从 localStorage 反序列化，合并缺失字段的默认值
- `save()` — 序列化到 localStorage
- `applyToDocument()` — 将设置写入 DOM：`data-theme`、`data-compact`、`data-code-theme`、CSS 变量、`prefers-reduced-motion` 覆盖
- `resetSection(section)` — 重置某分组到默认值
- `$subscribe` — watch 整个 state，任何变更自动调 save() + applyToDocument()

**为什么选 store 而非 composable：** 外观设置需要在 GlobalHeader（入口）、HomeView（初始值）、AppChatView（初始值）、CodeBlock（CSS 变量）等多个不相关的组件中读取。Pinia store 在任意组件中 `useAppearanceStore()` 即可访问，无需 prop 透传或 provide/inject。符合「高内聚低耦合」。

### 2.2 主题系统：Ant Design darkAlgorithm + CSS 变量 + `data-*` 混合方案

| 层 | 机制 | 控制内容 |
|----|------|---------|
| 1. Ant Design 主题 | `<a-config-provider :theme="themeConfig">`，`algorithm` 动态切换 `theme.darkAlgorithm` / `theme.defaultAlgorithm` | 组件库色板、Button/Card/Menu 等自动暗色 |
| 2. CSS 变量 | `[data-theme="dark"] { --color-bg: #1a1a2e; ... }` | 自定义组件背景、阴影、文字色 |
| 3. 属性选择器 | `[data-compact="true"]` / `[data-code-theme="high-contrast"]` | 密度、代码高亮色 |

加载顺序：`main.ts` → `appearanceStore.load()` → `applyToDocument()` → `App.vue` 挂载 ConfigProvider。

关键行为：
- `applyToDocument()` 写入 `document.documentElement.dataset.theme`，同时更新 `configProviderTheme`（传给 App.vue 的 ConfigProvider）
- 暗色 → `theme.darkAlgorithm`，浅色 → `theme.defaultAlgorithm`
- 跟随系统模式：`colorMode === 'system'` 时注册 `matchMedia('(prefers-color-scheme: dark)')` 监听；用户手动选浅/深色时移除监听
- 现有 `base.css` 的 `@media (prefers-color-scheme: dark)` 改写成 `[data-theme="dark"]`，保持兼容

### 2.3 路由与页面

- **路由：** `/user/appearance`，name `user-appearance`，组件 `UserAppearanceSettings`
- **页面布局：** 独立全宽页（非 sidebar/workbench），内容区顶部有标题 + 说明，下方为分组列表。与 UserSettings 并列，不共用 layout。
- **GlobalHeader 接入：** 在「个人设置」下方插入菜单项，图标用 `SettingOutlined`（Ant Design 图标，与现有风格一致），点击 `router.push('/user/appearance')`。

### 2.4 文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| **新增** | `src/stores/appearance.ts` | 状态 store |
| **新增** | `src/assets/theme.css` | 主题 CSS 变量（light + dark 两套） |
| **新增** | `src/page/User/UserAppearanceSettings.vue` | 外观设置页面 |
| **修改** | `src/main.ts` | import theme.css |
| **修改** | `src/App.vue` | 包裹 a-config-provider，onMounted 初始化 store |
| **修改** | `src/components/GlobalHeader.vue` | 新增「外观设置」菜单项 |
| **修改** | `src/router/index.ts` | 注册 `/user/appearance` |
| **修改** | `src/assets/base.css` | 补充紧凑模式、动画减弱的全局样式 |
| **修改** | `src/components/CodeBlock.vue` | 颜色硬编码 → CSS 变量 |
| **修改** | `src/page/HomeView.vue` | 外观 store 值作初始值 |
| **修改** | `src/page/App/AppChatView.vue` | 外观 store 值作 genMode/smoothScroll 初始值 |
| **修改** | `src/components/CollapsibleCodeBlock.vue` | defaultCollapsed 绑定 store |

## 3. UI 设计

### 3.1 GlobalHeader 下拉菜单

```
┌─────────────────────┐
│  ⚙ 个人设置         │
│  🎨 外观设置         │  ← 新增，图标用 PaletteOutlined
│  ➡ 退出登录         │
└─────────────────────┘
```

样式与现有一致：白底阴影、hover 灰底、左图标右文字。

### 3.2 外观设置页

Obsidian 式分组列表布局，适配本项目浅蓝简约风：

```
┌──────────────────────────────────────────────┐
│  ← 返回         外观设置                      │
│                                               │
│  ┌─ 基础颜色 ────────────────────────────┐    │
│  │  外观模式              [ 浅色 ▼ ]      │    │
│  │  主题强调色            [■ 色板] [重置]  │    │
│  │  卡片透明度            [====●====]     │    │
│  └─────────────────────────────────────────┘    │
│                                               │
│  ┌─ 字体 ────────────────────────────────┐    │
│  │  界面字号              [====●====] 15   │    │
│  │  代码字号              [====●====] 13   │    │
│  │  代码字体              [Consolas ▼]     │    │
│  └─────────────────────────────────────────┘    │
│  ...                                           │
└──────────────────────────────────────────────┘
```

控件映射：

| 设置项 | 控件 | Ant Design 组件 |
|--------|------|----------------|
| 外观模式 | 下拉选择 | `<a-select>` |
| 主题强调色 | 色板 + 重置按钮 | `<a-color-picker>` |
| 界面/代码字号 | 滑块 + 数值显示 + 重置 | `<a-slider>` |
| 代码字体族 | 下拉选择 | `<a-select>` |
| 紧凑模式/动画减弱/... | 开关 | `<a-switch>` |

分组标题样式：深色背景（`#f0f5ff` 浅色模式 / `#1a1a2e` 深色模式）、大写字母间距。

每行布局：`display: flex`，左侧标题 + 灰色描述文字，右侧控件。

### 3.3 暗色模式设计

`data-theme` 属性 + Ant Design `darkAlgorithm` 混合方案：

- `applyToDocument()` 写入 `document.documentElement.dataset.theme='dark'`，同时 App.vue 的 ConfigProvider 切换 `algorithm: theme.darkAlgorithm` → 组件库自动变暗
- 浅色模式写入 `dataset.theme='light'`，ConfigProvider 切回 `theme.defaultAlgorithm`
- 跟随系统模式：`colorMode === 'system'` 时注册 `matchMedia('(prefers-color-scheme: dark)')` 的 `change` 监听，系统切换时自动调 `applyToDocument()`；用户手动选浅/深色时移除监听
- 自定义组件（非 Ant Design 控制的部分）通过 `[data-theme="dark"] { --color-bg: #1a1a2e; }` 变量覆盖
- 文字对比度：深色模式下浅色文字 `#e0e0e0` 背景 `#1a1a2e`，对比度 > 7:1（达标 AAA）

## 4. 数据流

```
┌─────────────┐    hydrate    ┌──────────────┐    applyToDocument()    ┌──────────────────┐
│  main.ts    │ ──────────►   │ appearance   │ ───────────────────►   │  document.document  │
│  (启动时)    │              │   Store      │                        │   Element          │
└─────────────┘              │              │                        │  data-theme="dark" │
                             │  state       │                        │  data-compact      │
┌─────────────┐              │  +defaults   │                        └──────────────────┘
│  GlobalHeader│   user        │  +watch      │
│  点击跳转    │──── click ──►│  auto-save   │                        ┌──────────────────┐
└─────────────┘              │              │                        │  ConfigProvider   │
                             │  save()      │  ──── localStorage ──►│  theme.token      │
┌─────────────┐              │  load()      │  ◄─── localStorage ────┘                  │
│  UserAppear │  mutate       │              │                        └──────────────────┘
│  anceSettings│─────►        │              │
│  .vue        │              └──────────────┘
└─────────────┘
```

**为什么选这个方案：** 单向数据流，store 是唯一可信源。组件只从 store 读、通过 UI 控件写 store。store 的 `$subscribe` 自动触发持久化和 DOM 应用，组件无需关心存盘细节。

## 5. 组件依赖关系

```
App.vue
 ├── a-config-provider (theme from appearanceStore)
 │    └── BasicLayout
 │         ├── GlobalHeader
 │         │    └── user-menu: "外观设置" → /user/appearance
 │         └── RouterView
 │              ├── HomeView (reads defaultCodeType, workflowEnabled)
 │              ├── AppChatView (reads chatGenMode, smoothScroll, previewExpanded)
 │              │    └── CollapsibleCodeBlock (reads toolCardCollapsed)
 │              │         └── CodeBlock (reads codeFontSize, codeFontFamily, codeTheme)
 │              └── UserAppearanceSettings (全量控件 → 写 store)
```

## 6. 默认值

```typescript
const DEFAULTS: AppearanceSettings = {
  // A
  colorMode: 'system',
  primaryColor: '#1677ff',        // Ant Design 默认蓝色
  // B
  fontSize: 15,                   // 对齐 base.css body font-size
  codeFontSize: 13,               // 对齐 CodeBlock 当前值
  codeFontFamily: 'system',       // 系统默认
  // C
  compactMode: false,
  reducedMotion: false,
  // D
  defaultCodeType: 'auto',
  workflowEnabled: false,
  chatGenMode: 'legacy',
  codeTheme: 'default',
  toolCardCollapsed: true,        // 对齐 CollapsibleCodeBlock 当前行为
  previewExpanded: true,
  smoothScroll: true,
}
```

## 7. 现有限制与兼容性

- **GlobalFooter 现有 `.dark` / `[data-theme='dark']` CSS：** 与本方案的 `data-theme` 属性兼容，无需修改
- **access.ts 未注册的路由守卫：** 本功能不需路由权限，不影响
- **App.vue `testUsingGet` 调用：** 不动，保持现状
- **npm run build/lint：** 最终必须通过

## 8. 里程碑

| 步骤 | 产出 |
|------|------|
| 1. 创建 store + 默认值 | `stores/appearance.ts` |
| 2. theme.css + base.css 补充 | 全局 CSS 变量 |
| 3. App.vue ConfigProvider | 主题注入 |
| 4. router + GlobalHeader 入口 | 路由注册 + 菜单项 |
| 5. UserAppearanceSettings 页面 | 全量设置 UI |
| 6. HomeView / AppChatView / CodeBlock 接入 | 偏好生效 |
| 7. 自测 + lint + build | 验收通过 |
