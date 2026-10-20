# 外观设置缺陷修复 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复前端外观设置页 3 个功能缺陷（字体无效 / 深色顶栏不统一 / 预览开关无效）+ 删除一行设置

**Architecture:** 4 个独立模块（A/B/C/D）可并行开发。模块 A 改造 appearance store 的 `resolveThemeConfig` 和 App.vue 的 ConfigProvider 传参；模块 B 替换顶栏组件的写死色值为 CSS 变量；模块 C 在 AppChatView 接入 `previewExpanded`；模块 D 删除 chatGenMode 全线代码。

**Tech Stack:** Vue 3 + TypeScript + Pinia + Ant Design Vue 4

---

### 任务 A: 字体修复 + store 清理（appearance.ts + App.vue）

**设计原因：** `resolveThemeConfig` 是 Ant Design ConfigProvider 的主题入口，不传 `fontSize` 导致 ant 组件字号不跟随滑块。同时在此任务中一并清理 `chatGenMode` 的 store 级定义，避免重复改动。

**涉及知识点：** Ant Design Vue ConfigProvider token 机制、Pinia 响应式 computed

**文件：**
- Modify: `src/stores/appearance.ts`
- Modify: `src/App.vue`

- [ ] **Step 1: 修改 `resolveThemeConfig` 签名加入 fontSize**

修改 `appearance.ts` 第 142-148 行：
```typescript
export function resolveThemeConfig(settings: {
  isDark: boolean
  primaryColor: string
  fontSize: number
}): { token: Record<string, any>; algorithm: any } {
  return {
    token: {
      colorPrimary: settings.primaryColor,
      fontSize: settings.fontSize,
    },
    algorithm: settings.isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
  }
}
```

- [ ] **Step 2: 从 `AppearanceSettings` 接口中删除 `chatGenMode`**

修改 `appearance.ts` 第 47-53 行，去掉 `chatGenMode: 'legacy' | 'workflow'` 一行：
```typescript
  // D. 本项目专属
  defaultCodeType: 'auto' | 'html' | 'multi_file' | 'vue_project'
  workflowEnabled: boolean
  codeTheme: 'default' | 'high-contrast' | 'soft'
  toolCardCollapsed: boolean
  previewExpanded: boolean
  smoothScroll: boolean
```

- [ ] **Step 3: 从 `SECTION_KEYS.preferences` 中删除 `chatGenMode`**

修改 `appearance.ts` 第 61 行：
```typescript
  preferences: ['defaultCodeType', 'workflowEnabled', 'codeTheme', 'toolCardCollapsed', 'previewExpanded', 'smoothScroll'],
```

- [ ] **Step 4: 从 `DEFAULTS` 中删除 `chatGenMode`**

修改 `appearance.ts` 第 73-79 行，去掉 `chatGenMode: 'legacy',`：
```typescript
  defaultCodeType: 'auto',
  workflowEnabled: false,
  codeTheme: 'default',
  toolCardCollapsed: true,
  previewExpanded: true,
  smoothScroll: true,
```

- [ ] **Step 5: App.vue 调用 `resolveThemeConfig` 时传入 `fontSize`**

修改 `App.vue` 第 28-33 行：
```typescript
const configProviderTheme = computed(() => {
  return resolveThemeConfig({
    isDark: appearanceStore.effectiveColorMode === 'dark',
    primaryColor: appearanceStore.primaryColor,
    fontSize: appearanceStore.fontSize,
  })
})
```

- [ ] **Step 6: 运行 `npm run build` 确认无编译错误**

---

### 任务 B: 深色模式顶栏适配（BasicLayout + GlobalHeader）

**设计原因：** 顶栏组件在 `[data-theme="dark"]` 下未做适配，白块和白细线问题来自写死色值。`theme.css` 已定义全套暗色变量，直接接入即可。

**涉及知识点：** CSS 自定义属性（变量）层叠、scoped style + deep 穿透

**可替代方案：** 在 GlobalHeader 内单独定义 `[data-theme="dark"]` 覆盖。**方案取舍：** 复用 `theme.css` 已有变量，避免重复定义。一旦日后修改变量值只需改一处。

**文件：**
- Modify: `src/layouts/BasicLayout.vue`
- Modify: `src/components/GlobalHeader.vue`

- [ ] **Step 1: BasicLayout.vue — `.layout-header` 替换写死 `background: #fff`**

修改 `BasicLayout.vue` 第 32-34 行：
```css
.layout-header {
  position: sticky;
  top: 0;
  z-index: 200;
  padding: 0;
  height: 64px;
  line-height: 64px;
  background: var(--bg-card, #fff);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}
```

- [ ] **Step 2: GlobalHeader.vue — 替换浅色写死色值**

逐步替换以下行：

第 240 行：
```css
.user-area:hover .user-info {
  background-color: var(--bg-mute, rgba(0, 0, 0, 0.04));
}
```

第 245 行：
```css
.user-name {
  font-size: 14px;
  color: var(--text-base, rgba(0, 0, 0, 0.85));
  font-weight: 500;
}
```

第 255-259 行：
```css
.user-menu {
  position: absolute;
  top: 100%;
  right: 0;
  min-width: 120px;
  background: var(--bg-card, #fff);
  border-radius: 6px;
  box-shadow:
    0 4px 12px var(--shadow-popup, rgba(0, 0, 0, 0.15)),
    0 0 0 1px rgba(0, 0, 0, 0.06);
  overflow: hidden;
  z-index: 1000;
  padding: 0;
}
```

第 274 行：
```css
.menu-item {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 34px;
  padding: 0 12px;
  line-height: 34px;
  cursor: pointer;
  transition: background-color 0.2s ease;
  color: var(--text-base, rgba(0, 0, 0, 0.85));
  font-size: 14px;
}
```

第 279 行：
```css
.menu-item:hover {
  background-color: var(--bg-mute, rgba(0, 0, 0, 0.06));
}
```

第 283 行：
```css
.menu-item.settings-item .menu-icon {
  color: var(--text-secondary, rgba(0, 0, 0, 0.65));
}
```

第 291 行：
```css
.menu-item.logout-item:hover {
  background-color: rgba(255, 77, 79, 0.1);
}
```

- [ ] **Step 3: 运行 `npm run build` 确认无编译错误**

---

### 任务 C: 预览面板开关接入（AppChatView）

**设计原因：** `previewExpanded` 已存在于 store 但 AppChatView 未读取。采用 `v-if` 控制预览区 DOM 渲染/销毁，配合动态 grid 布局实现单/双栏切换。

**涉及知识点：** Vue 条件渲染、CSS grid 动态 class 绑定

**可替代方案：** `v-show` 仅隐藏不销毁 > 不释放 iframe 资源，不采用。

**文件：**
- Modify: `src/page/App/AppChatView.vue`

- [ ] **Step 1: 修改 `genMode` 初始化，去掉对 `chatGenMode` 的引用**

修改第 86 行：
```typescript
const genMode = ref<'legacy' | 'workflow'>('legacy')
```

- [ ] **Step 2: 预览面板加 `v-if` 条件渲染**

第 3362 行：
```html
<div v-if="appearanceStore.previewExpanded" class="preview-panel">
```

- [ ] **Step 3: `main-content` 加动态 class**

第 3020 行：
```html
<section class="main-content" :class="{ 'main-content--single': !appearanceStore.previewExpanded }">
```

- [ ] **Step 4: 添加单栏布局样式**

在 `@media` 之前，style 末尾追加：
```css
.main-content--single {
  grid-template-columns: minmax(0, 1fr);
}

.main-content--single .preview-panel {
  display: none;
}
```

- [ ] **Step 5: 运行 `npm run build` 确认无编译错误**

---

### 任务 D: 删除「聊天默认生成模式」设置行（UserAppearanceSettings）

**设计原因：** 纯 UI 删除。只需移除 template 中对应行，store 字段清理已在任务 A 完成。

**涉及知识点：** Vue template 结构

**文件：**
- Modify: `src/page/User/UserAppearanceSettings.vue`

- [ ] **Step 1: 删除第 188-203 行整块 template 代码**

删除内容（`<!-- 聊天默认生成模式 -->` 整行）：
```html
      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">聊天默认生成模式</span>
          <span class="row-desc">新对话的生成方式</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.chatGenMode"
            style="width: 120px"
            @change="(v: string) => store.chatGenMode = v as 'legacy' | 'workflow'"
          >
            <a-select-option value="legacy">传统</a-select-option>
            <a-select-option value="workflow">工作流</a-select-option>
          </a-select>
        </div>
      </div>
```

- [ ] **Step 2: 移除 `store.chatGenMode` 不再使用后，确认 import 不需要调整**

检查：settings.vue 中 `import { useAppearanceStore, CODE_FONT_OPTIONS, CODE_THEME_OPTIONS, DEFAULT_CODE_TYPE_OPTIONS } from '@/stores/appearance'` 这行不受影响（`chatGenMode` 不在 import 列表中）。

- [ ] **Step 3: 运行 `npm run build` 确认无编译错误**

---

## 执行方案

**计划已保存。两个执行选项：**

### 选项 1: 并行 Agent 执行（推荐）

4 个模块无依赖关系、无共享状态，可使用 `subagent-driven-development` 一次性启动 4 个 Agent 并行修改：

| Agent | 任务 | 涉及文件 |
|-------|------|---------|
| Agent A | 任务 A | appearance.ts, App.vue |
| Agent B | 任务 B | BasicLayout.vue, GlobalHeader.vue |
| Agent C | 任务 C | AppChatView.vue |
| Agent D | 任务 D | UserAppearanceSettings.vue |

全部完成后统一 `npm run build` 验证 + 分场景测试。

### 选项 2: 串行执行

按 A → B → C → D 顺序逐任务执行，每完成一个运行 `npm run build`。
