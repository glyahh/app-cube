# 外观设置系统实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 ai-generate-code-frontend 中新增 GlobalHeader 下拉「外观设置」入口、独立设置页、Pinia 全局外观偏好状态，持久化到 localStorage

**Architecture:** Pinia store 作为唯一可信源，App.vue 注入 ConfigProvider + 写 CSS 变量，外观设置页双向绑定 store，其余组件（HomeView/AppChatView/CodeBlock）读取 store 初值

**Tech Stack:** Vue 3.5 + Pinia 3 + Vue Router 5 + Ant Design Vue 4 + TypeScript

---

## 文件结构

| 操作 | 文件 | 职责 |
|------|------|------|
| 新增 | `src/stores/appearance.ts` | 类型定义、默认值、load/save/applyToDocument、$subscribe 自动持久化 |
| 新增 | `src/assets/theme.css` | `:root`/`[data-theme]`/`[data-compact]`/`[data-code-theme]` CSS 变量 |
| 新增 | `src/page/User/UserAppearanceSettings.vue` | 外观设置页面（4 个分组、12 项控件） |
| 修改 | `src/main.ts` | import theme.css |
| 修改 | `src/App.vue` | 包裹 a-config-provider，onMounted 调 store.load() |
| 修改 | `src/components/GlobalHeader.vue` | 在「个人设置」下插「外观设置」菜单项 |
| 修改 | `src/router/index.ts` | 注册 `/user/appearance` 路由 |
| 修改 | `src/assets/base.css` | `@media prefers-color-scheme` → `[data-theme]`，新增紧凑/动画变量 |
| 修改 | `src/components/CodeBlock.vue` | 高亮色硬编码 → CSS 变量 |
| 修改 | `src/page/HomeView.vue` | workflowBetaEnabled/selectedCodeType 读取 store 初值 |
| 修改 | `src/page/App/AppChatView.vue` | genMode/smoothScroll/previewExpanded 读取 store 初值 |
| 修改 | `src/components/CollapsibleCodeBlock.vue` | defaultCollapsed 绑定 store |

---

### Task 1: 创建外观设置 Store

**文件：**
- 创建: `src/stores/appearance.ts`

**设计说明：**
- 用 Pinia setup 语法（composition API），TypeScript 类型推导最优
- `$subscribe` 监听整个 state，任何变动自动 `save()` + `applyToDocument()`，组件无需手动触发
- `load()` 启动时从 localStorage 恢复，缺失字段合并默认值（应对后续新增字段）
- `applyToDocument()` 写 `data-theme`/`data-compact`/`data-code-theme` + CSS 变量
- `resetSection()` 重置分组的全部字段，传 section key 避免一次全量重置

- [ ] **Step 1.1: 创建 store 文件，定义类型和默认值**

```typescript
// src/stores/appearance.ts
import { defineStore } from 'pinia'
import { theme } from 'ant-design-vue'
import type { GlobalToken } from 'ant-design-vue/es/theme'

/** localStorage key */
const STORAGE_KEY = 'glyahh:appearance:v1'

/** 代码字体族预设 */
export const CODE_FONT_OPTIONS = [
  { label: '系统默认', value: 'system', css: 'Consolas, Monaco, "Courier New", monospace' },
  { label: 'JetBrains Mono', value: 'jetbrains-mono', css: '"JetBrains Mono", Consolas, monospace' },
  { label: 'Fira Code', value: 'fira-code', css: '"Fira Code", Consolas, monospace' },
  { label: 'Consolas', value: 'consolas', css: 'Consolas, Monaco, monospace' },
] as const

/** 代码块主题 */
export const CODE_THEME_OPTIONS = [
  { label: '默认', value: 'default' },
  { label: '高对比', value: 'high-contrast' },
  { label: '柔和', value: 'soft' },
] as const

/** 默认代码类型 */
export const DEFAULT_CODE_TYPE_OPTIONS = [
  { label: '自动识别', value: 'auto' },
  { label: 'HTML', value: 'html' },
  { label: '多文件', value: 'multi_file' },
  { label: 'Vue 项目', value: 'vue_project' },
] as const

/** 外观设置状态接口 */
export interface AppearanceSettings {
  // A. 基础颜色
  colorMode: 'system' | 'light' | 'dark'
  primaryColor: string

  // B. 字体
  fontSize: number
  codeFontSize: number
  codeFontFamily: string

  // C. 界面密度
  compactMode: boolean
  reducedMotion: boolean

  // D. 本项目专属
  defaultCodeType: 'auto' | 'html' | 'multi_file' | 'vue_project'
  workflowEnabled: boolean
  chatGenMode: 'legacy' | 'workflow'
  codeTheme: 'default' | 'high-contrast' | 'soft'
  toolCardCollapsed: boolean
  previewExpanded: boolean
  smoothScroll: boolean
}

/** 按分组索引的 key 列表，用于 resetSection */
export const SECTION_KEYS: Record<string, Array<keyof AppearanceSettings>> = {
  colors: ['colorMode', 'primaryColor'],
  fonts: ['fontSize', 'codeFontSize', 'codeFontFamily'],
  density: ['compactMode', 'reducedMotion'],
  preferences: ['defaultCodeType', 'workflowEnabled', 'chatGenMode', 'codeTheme', 'toolCardCollapsed', 'previewExpanded', 'smoothScroll'],
}

/** 默认值：对齐项目现有代码的初值 */
const DEFAULTS: AppearanceSettings = {
  colorMode: 'system',
  primaryColor: '#1677ff',
  fontSize: 15,
  codeFontSize: 13,
  codeFontFamily: 'system',
  compactMode: false,
  reducedMotion: false,
  defaultCodeType: 'auto',
  workflowEnabled: false,
  chatGenMode: 'legacy',
  codeTheme: 'default',
  toolCardCollapsed: true,
  previewExpanded: true,
  smoothScroll: true,
}
```

- [ ] **Step 1.2: 实现 load/save/applyToDocument**

```typescript
/** 从 localStorage 加载，缺失字段合并默认值 */
function loadFromStorage(): AppearanceSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...DEFAULTS }
    const parsed = JSON.parse(raw)
    // 合并确保新增字段有默认值（v1→v2 兼容）
    return { ...DEFAULTS, ...parsed }
  } catch {
    return { ...DEFAULTS }
  }
}

/** 持久化到 localStorage */
function saveToStorage(state: AppearanceSettings): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch (e) {
    console.warn('[appearance] 写入 localStorage 失败', e)
  }
}

/**
 * 将设置应用到 DOM
 * - data-theme / data-compact / data-code-theme 属性
 * - CSS 自定义属性（字号、字体族、强调色）
 * - 跟随系统时注册 matchMedia 监听
 */
let mediaQueryList: MediaQueryList | null = null
let mediaChangeHandler: (() => void) | null = null

export function applyToDocument(settings: AppearanceSettings): void {
  const root = document.documentElement

  // 1. 主题模式
  if (settings.colorMode === 'system') {
    const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
    root.dataset.theme = prefersDark ? 'dark' : 'light'
    // 注册系统主题变化监听
    if (!mediaQueryList) {
      mediaQueryList = window.matchMedia('(prefers-color-scheme: dark)')
      mediaChangeHandler = () => {
        // 从 store 重新读取 colorMode（期间可能用户已手动切换）
        const raw = localStorage.getItem(STORAGE_KEY)
        if (raw) {
          try {
            const saved = JSON.parse(raw)
            if (saved.colorMode === 'system') {
              root.dataset.theme = mediaQueryList!.matches ? 'dark' : 'light'
            }
          } catch { /* ignore */ }
        }
      }
      mediaQueryList.addEventListener('change', mediaChangeHandler)
    }
  } else {
    root.dataset.theme = settings.colorMode
    // 移除系统主题监听
    if (mediaQueryList && mediaChangeHandler) {
      mediaQueryList.removeEventListener('change', mediaChangeHandler)
      mediaQueryList = null
      mediaChangeHandler = null
    }
  }

  // 2. 紧凑模式
  root.dataset.compact = settings.compactMode ? 'true' : 'false'

  // 3. 代码块主题
  root.dataset.codeTheme = settings.codeTheme

  // 4. CSS 变量
  root.style.setProperty('--font-size-base', `${settings.fontSize}px`)
  root.style.setProperty('--code-font-size', `${settings.codeFontSize}px`)
  const fontCss = CODE_FONT_OPTIONS.find(f => f.value === settings.codeFontFamily)?.css ?? CODE_FONT_OPTIONS[0].css
  root.style.setProperty('--code-font-family', fontCss)
  root.style.setProperty('--color-primary', settings.primaryColor)

  // 5. 动画减弱
  if (settings.reducedMotion) {
    root.style.setProperty('--transition-duration', '0s')
  } else {
    root.style.removeProperty('--transition-duration')
  }
}

/** 根据当前设置生成 Ant Design ConfigProvider theme 对象 */
export function resolveThemeConfig(settings: AppearanceSettings): { token: Partial<GlobalToken>; algorithm: any } {
  const isDark = settings.colorMode === 'dark' ||
    (settings.colorMode === 'system' && window.matchMedia('(prefers-color-scheme: dark)').matches)
  return {
    token: { colorPrimary: settings.primaryColor },
    algorithm: isDark ? theme.darkAlgorithm : theme.defaultAlgorithm,
  }
}
```

- [ ] **Step 1.3: 定义 Pinia store 并导出**

```typescript
export const useAppearanceStore = defineStore('appearance', () => {
  // 从 localStorage 加载初始状态
  const saved = loadFromStorage()

  const state = reactive<AppearanceSettings>({ ...saved })

  /** 保存到 localStorage 并应用到 DOM */
  function persistAndApply() {
    saveToStorage(state)
    applyToDocument(state)
  }

  /** 重置某个分组的全部字段到默认值 */
  function resetSection(section: keyof typeof SECTION_KEYS) {
    const keys = SECTION_KEYS[section]
    if (!keys) return
    keys.forEach((k) => {
      (state as any)[k] = DEFAULTS[k]
    })
    persistAndApply()
  }

  /** 启动时加载一次 */
  function init() {
    persistAndApply()
  }

  // 自动 watch：任何 state 变化 → 持久化 + 应用
  watch(
    () => ({ ...state }),
    () => persistAndApply(),
    { deep: true },
  )

  return {
    ...toRefs(state),
    state,
    init,
    resetSection,
    DEFAULTS,
  }
})
```

- [ ] **Step 1.4: 验证类型正确** — 确保 TypeScript 无报错

```bash
cd ai-generate-code-frontend
npx vue-tsc --noEmit --strict src/stores/appearance.ts 2>&1 | head -20
```

预期输出：无类型错误。

- [ ] **Step 1.5: commit**

```bash
git add ai-generate-code-frontend/src/stores/appearance.ts
git commit -m "feat: 新增外观设置 Pinia store，含类型/默认值/持久化/DOM 应用"
```

---

### Task 2: 创建主题 CSS 变量文件

**文件：**
- 创建: `src/assets/theme.css`

**设计说明：**
- 全部主题控制集中在一个文件，便于维护
- `:root` 放浅色默认变量；`[data-theme="dark"]` 放深色覆盖
- `[data-compact="true"]` 放紧凑模式覆盖
- `[data-code-theme="high-contrast"]` / `[data-code-theme="soft"]` 放代码高亮覆盖
- `--transition-duration` 透传动画减弱控制

- [ ] **Step 2.1: 创建 theme.css**

```css
/* ============================================================
   theme.css — 全局主题 CSS 变量
   control by: stores/appearance.ts → applyToDocument()
   ============================================================ */

/* ---------- 浅色模式（默认） ---------- */
:root {
  /* 主色 */
  --color-primary: #1677ff;
  --color-primary-light: #e6f4ff;
  --color-primary-bg: #f0f5ff;

  /* 背景色 */
  --bg-base: #ffffff;
  --bg-soft: #f5f7fa;
  --bg-mute: #f0f0f0;
  --bg-card: #ffffff;
  --bg-section-header: #f0f5ff;

  /* 文字色 */
  --text-base: #1f1f1f;
  --text-secondary: #666666;
  --text-muted: #999999;
  --text-on-dark: #ffffff;

  /* 边框与阴影 */
  --border-color: #e8e8e8;
  --border-color-hover: #d9d9d9;
  --shadow-card: 0 2px 8px rgba(0, 0, 0, 0.06), 0 1px 2px rgba(0, 0, 0, 0.04);
  --shadow-popup: 0 4px 12px rgba(0, 0, 0, 0.12);

  /* 字号 */
  --font-size-base: 15px;
  --code-font-size: 13px;
  --code-font-family: Consolas, Monaco, "Courier New", monospace;
  --font-family-base: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, "Fira Sans", "Droid Sans", "Helvetica Neue", sans-serif;

  /* 圆角 */
  --radius-sm: 6px;
  --radius-md: 8px;
  --radius-lg: 12px;

  /* 动画 */
  --transition-duration: 0.2s;
  --transition-timing: ease;

  /* 代码高亮色 */
  --code-keyword: #569cd6;
  --code-string: #e6b450;
  --code-comment: #7f8ea3;
  --code-property: #7dcfff;
  --code-value: #f9a8d4;
  --code-selector: #8be9fd;
  --code-tag: #ff7aa2;
  --code-attr: #9ccfd8;
  --code-number: #c4b5fd;
  --code-operator: #f38ba8;
  --code-function: #7ee787;

  /* 代码块背景 */
  --code-bg: #1e1e1e;
  --code-header-bg: #2d2d2d;
  --code-border: #3e3e3e;
  --code-text: #d4d4d4;
}

/* ---------- 深色模式 ---------- */
[data-theme="dark"] {
  --color-primary-bg: #111d2c;

  --bg-base: #1a1a2e;
  --bg-soft: #16213e;
  --bg-mute: #0f3460;
  --bg-card: #1e1e36;
  --bg-section-header: #1a1a2e;

  --text-base: #e0e0e0;
  --text-secondary: #a0a0a0;
  --text-muted: #707070;

  --border-color: #2a2a4a;
  --border-color-hover: #3a3a5a;
  --shadow-card: 0 2px 8px rgba(0, 0, 0, 0.3);
  --shadow-popup: 0 4px 12px rgba(0, 0, 0, 0.4);

  /* 深色模式下代码块不变，保持现有 deep dark 风格 */
}

/* ---------- 紧凑模式 ---------- */
[data-compact="true"] {
  --font-size-base: 13px;
  --code-font-size: 12px;
}

[data-compact="true"] .global-header {
  --header-height: 48px;
  --header-padding: 0 16px;
}

/* ---------- 代码块高对比主题 ---------- */
[data-code-theme="high-contrast"] {
  --code-keyword: #ff79c6;
  --code-string: #f1fa8c;
  --code-comment: #6272a4;
  --code-property: #8be9fd;
  --code-value: #ffb86c;
  --code-selector: #50fa7b;
  --code-tag: #ff5555;
  --code-attr: #f8f8f2;
  --code-number: #bd93f9;
  --code-operator: #ff79c6;
  --code-function: #50fa7b;
  --code-bg: #000000;
  --code-text: #f8f8f2;
}

/* ---------- 代码块柔和主题 ---------- */
[data-code-theme="soft"] {
  --code-keyword: #5c8ec4;
  --code-string: #c9a85e;
  --code-comment: #8c9aa8;
  --code-property: #6ab0c4;
  --code-value: #d492b0;
  --code-selector: #73c4b8;
  --code-tag: #c46a7a;
  --code-attr: #8cb4c4;
  --code-number: #a894c4;
  --code-operator: #c48494;
  --code-function: #6cb87a;
  --code-bg: #2a2a2a;
  --code-text: #c8c8c8;
}

/* ---------- 减少动画 ---------- */
* {
  transition-duration: var(--transition-duration) !important;
  animation-duration: var(--transition-duration) !important;
}
```

- [ ] **Step 2.2: commit**

```bash
git add ai-generate-code-frontend/src/assets/theme.css
git commit -m "feat: 新增 theme.css 全局主题变量（浅色/深色/紧凑/代码主题）"
```

---

### Task 3: 更新 base.css

**文件：**
- 修改: `src/assets/base.css`

**设计说明：**
- 现有 `@media (prefers-color-scheme: dark)` 改写为 `[data-theme="dark"]`，避免系统级暗色与应用级暗色冲突
- body font-size 改为 `var(--font-size-base)`，让 CSS 变量控制
- `--color-*` 变量保留但改为引用 theme.css 的值

- [ ] **Step 3.1: 改写 base.css 的暗色模式 + 字号变量化**

将 `@media (prefers-color-scheme: dark)` 块替换为 `[data-theme="dark"]`:

```css
/* 原第 39-51 行，替换 */
[data-theme="dark"] {
  --color-background: var(--vt-c-black);
  --color-background-soft: var(--vt-c-black-soft);
  --color-background-mute: var(--vt-c-black-mute);

  --color-border: var(--vt-c-divider-dark-2);
  --color-border-hover: var(--vt-c-divider-dark-1);

  --color-heading: var(--vt-c-text-dark-1);
  --color-text: var(--vt-c-text-dark-2);
}
```

将 body 字号改为 CSS 变量：

```css
body {
  /* ... 保留其他属性不变，只改 font-size 行 */
  font-size: var(--font-size-base, 15px);
  /* ... */
}
```

同时补充 Ant Design reset 后的全局背景：

```css
body {
  background: var(--bg-base); /* 覆盖 Ant Design reset 的默认白色 */
  color: var(--text-base);
}
```

- [ ] **Step 3.2: commit**

```bash
git add ai-generate-code-frontend/src/assets/base.css
git commit -m "refactor: base.css 暗色模式由 media query 改为 data-theme 选择器，字号改用 CSS 变量"
```

---

### Task 4: 更新 main.ts 引入 theme.css

**文件：**
- 修改: `src/main.ts`

**设计说明：**
- 在 `import base.css` 下方新增 `import theme.css`

- [ ] **Step 4.1: 添加 import**

```typescript
import './assets/base.css'
import './assets/theme.css'    // ← 新增
```

- [ ] **Step 4.2: commit**

```bash
git add ai-generate-code-frontend/src/main.ts
git commit -m "chore: main.ts 引入 theme.css"
```

---

### Task 5: 更新 App.vue — ConfigProvider + Store 初始化

**文件：**
- 修改: `src/App.vue`

**设计说明：**
- 用 `a-config-provider` 包裹 `RouterView`，传入动态 `theme` 对象
- `onMounted` 调 `appearanceStore.init()` 完成首次 hydration + DOM 应用
- 监听 store 的 `colorMode` / `primaryColor` 变化动态更新 ConfigProvider 的 `theme`

- [ ] **Step 5.1: 修改 template + script**

从：

```vue
<script setup lang="ts">
import BasicLayout from '@/layouts/BasicLayout.vue'
import { RouterView } from 'vue-router'
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import { testUsingGet } from '@/api/test.ts'
import { UserLoginStore } from './stores/UserLogin'

testUsingGet({}).then((res) => {
  console.log(res)
})

const userLoginStore = UserLoginStore();
userLoginStore.fetchLoginUser();

const route = useRoute()
const noLayout = computed(() => Boolean(route.meta?.noLayout))
</script>

<template>
  <RouterView v-if="noLayout" />
  <BasicLayout v-else>
    <RouterView />
  </BasicLayout>
</template>
```

改为：

```vue
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { ConfigProvider, theme } from 'ant-design-vue'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { UserLoginStore } from './stores/UserLogin'
import { useAppearanceStore, applyToDocument, resolveThemeConfig } from './stores/appearance'

import { testUsingGet } from '@/api/test.ts'

// 开发环境测试调用
testUsingGet({}).then((res) => {
  console.log(res)
})

// 登录态
const userLoginStore = UserLoginStore()
userLoginStore.fetchLoginUser()

// 外观设置
const appearanceStore = useAppearanceStore()
onMounted(() => {
  appearanceStore.init()
})

// ConfigProvider 主题配置：监听 store 变化动态计算
const configProviderTheme = computed(() => {
  const settings = {
    colorMode: appearanceStore.colorMode,
    primaryColor: appearanceStore.primaryColor,
  }
  return resolveThemeConfig(settings)
})

// 路由布局
const route = useRoute()
const noLayout = computed(() => Boolean(route.meta?.noLayout))
</script>

<template>
  <a-config-provider :theme="configProviderTheme">
    <RouterView v-if="noLayout" />
    <BasicLayout v-else>
      <RouterView />
    </BasicLayout>
  </a-config-provider>
</template>
```

- [ ] **Step 5.2: 验证 build**

```bash
cd ai-generate-code-frontend
npm run build 2>&1 | tail -5
```

预期：`✓ built in ...s`

- [ ] **Step 5.3: commit**

```bash
git add ai-generate-code-frontend/src/App.vue
git commit -m "feat: App.vue 包裹 a-config-provider，挂载时初始化外观 store"
```

---

### Task 6: 注册 /user/appearance 路由 + GlobalHeader 菜单项

**文件：**
- 修改: `src/router/index.ts`
- 修改: `src/components/GlobalHeader.vue`

**设计说明：**
- 路由 path 遵循 `/user/*` 命名空间，name 用 `user-appearance`
- GlobalHeader 图标用 `PaletteOutlined`（Ant Design 图标，与现有的 `SettingOutlined` 同族）
- 菜单项插在个人设置与退出登录之间，DOM 顺序即视觉顺序

- [ ] **Step 6.1: router 添加路由**

在 `UserSettings` import 下方新增：

```typescript
// src/router/index.ts — 在 import UserSettings 行后
import UserAppearanceSettings from '@/page/User/UserAppearanceSettings.vue'
```

在 `/user/settings` 路由下方新增：

```typescript
    {
      path: '/user/appearance',
      name: 'user-appearance',
      component: UserAppearanceSettings,
    },
```

- [ ] **Step 6.2: GlobalHeader 添加菜单项**

```vue
<script setup lang="ts">
// 在 import 区域新增 PaletteOutlined
import { RightOutlined, SettingOutlined, PaletteOutlined } from '@ant-design/icons-vue'
```

在 template 的 settings-item 与 logout-item 之间新增：

```vue
              <div class="menu-item settings-item" @click="handleSettings">
                <SettingOutlined class="menu-icon" />
                <span class="menu-text">个人设置</span>
              </div>
              <!-- ↓ 新增 ↓ -->
              <div class="menu-item settings-item" @click="handleAppearance">
                <PaletteOutlined class="menu-icon" />
                <span class="menu-text">外观设置</span>
              </div>
              <!-- ↑ 新增 ↑ -->
              <div class="menu-item logout-item" @click="handleLogout" :class="{ 'loading': isLoggingOut }">
                <RightOutlined class="menu-icon" />
                <span class="menu-text">退出登录</span>
              </div>
```

在 script 的 `handleSettings` 下方新增：

```typescript
function handleAppearance() {
  router.push('/user/appearance')
  showUserMenu.value = false
}
```

- [ ] **Step 6.3: 验证 build**

```bash
cd ai-generate-code-frontend
npm run build 2>&1 | tail -5
```

预期：`✓ built in ...s`

- [ ] **Step 6.4: commit**

```bash
git add ai-generate-code-frontend/src/router/index.ts ai-generate-code-frontend/src/components/GlobalHeader.vue
git commit -m "feat: 注册 /user/appearance 路由，GlobalHeader 下拉新增外观设置入口"
```

---

### Task 7: 创建 UserAppearanceSettings 页面组件

**文件：**
- 创建: `src/page/User/UserAppearanceSettings.vue`

**设计说明：**
- Obsidian 式分组列表布局：`appearance-section` > `appearance-row`
- 4 个分组：基础颜色（2 项）、字体（3 项）、界面密度（2 项）、生成与聊天偏好（7 项）
- 每行左标题+描述、右控件；分组标题深色背景（`var(--bg-section-header)`）
- 控件用 Ant Design 组件：a-select / a-switch / a-slider / a-color-picker
- 顶部「返回」按钮用 router.back()
- 所有控件双向绑定 store 的对应字段，store $subscribe 自动持久化

- [ ] **Step 7.1: 创建完整设置页面**

```vue
<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ArrowLeftOutlined } from '@ant-design/icons-vue'
import { useAppearanceStore, CODE_FONT_OPTIONS, CODE_THEME_OPTIONS, DEFAULT_CODE_TYPE_OPTIONS } from '@/stores/appearance'

const router = useRouter()
const store = useAppearanceStore()

function goBack() {
  router.back()
}

/** 重置某分组到默认值 */
function handleReset(section: keyof typeof store.DEFAULTS | 'colors' | 'fonts' | 'density' | 'preferences') {
  store.resetSection(section as any)
}
</script>

<template>
  <div class="appearance-page">
    <!-- 顶部栏 -->
    <div class="page-header">
      <button class="back-btn" @click="goBack">
        <ArrowLeftOutlined />
        <span>返回</span>
      </button>
      <h1 class="page-title">外观设置</h1>
      <p class="page-desc">自定义界面颜色、字体、布局和生成偏好</p>
    </div>

    <!-- A. 基础颜色 -->
    <section class="appearance-section">
      <div class="section-header">基础颜色</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">外观模式</span>
          <span class="row-desc">选择浅色、深色或跟随系统</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.colorMode"
            style="width: 140px"
            @change="(v: string) => store.colorMode = v"
          >
            <a-select-option value="system">跟随系统</a-select-option>
            <a-select-option value="light">浅色</a-select-option>
            <a-select-option value="dark">深色</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">主题强调色</span>
          <span class="row-desc">应用于按钮、链接和选中项</span>
        </div>
        <div class="row-control row-control--color">
          <a-color-picker
            :value="store.primaryColor"
            :preset-colors="['#1677ff','#52c41a','#fa8c16','#eb2f96','#722ed1','#13c2c2','#f5222d','#faad14']"
            :show-text="false"
            @update:value="(v: string) => store.primaryColor = v"
          />
          <a-button size="small" @click="store.primaryColor = store.DEFAULTS.primaryColor">重置</a-button>
        </div>
      </div>
    </section>

    <!-- B. 字体 -->
    <section class="appearance-section">
      <div class="section-header">字体</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">界面字号</span>
          <span class="row-desc">{{ store.fontSize }}px — 控制全局正文字体大小</span>
        </div>
        <div class="row-control row-control--slider">
          <a-slider
            :min="12"
            :max="18"
            :step="1"
            :value="store.fontSize"
            style="width: 160px"
            @update:value="(v: number) => store.fontSize = v"
          />
          <span class="slider-value">{{ store.fontSize }}px</span>
          <a-button size="small" @click="store.fontSize = store.DEFAULTS.fontSize">重置</a-button>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码字号</span>
          <span class="row-desc">{{ store.codeFontSize }}px — 代码块和终端字体大小</span>
        </div>
        <div class="row-control row-control--slider">
          <a-slider
            :min="11"
            :max="16"
            :step="1"
            :value="store.codeFontSize"
            style="width: 160px"
            @update:value="(v: number) => store.codeFontSize = v"
          />
          <span class="slider-value">{{ store.codeFontSize }}px</span>
          <a-button size="small" @click="store.codeFontSize = store.DEFAULTS.codeFontSize">重置</a-button>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码字体</span>
          <span class="row-desc">代码块的等宽字体</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.codeFontFamily"
            style="width: 160px"
            @change="(v: string) => store.codeFontFamily = v"
          >
            <a-select-option
              v-for="opt in CODE_FONT_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>
    </section>

    <!-- C. 界面密度 -->
    <section class="appearance-section">
      <div class="section-header">界面密度</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">紧凑模式</span>
          <span class="row-desc">减小内边距和间距，显示更多内容</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.compactMode" @change="(v: boolean) => store.compactMode = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">减少动画</span>
          <span class="row-desc">关闭界面切换和内容加载的过渡动画</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.reducedMotion" @change="(v: boolean) => store.reducedMotion = v" />
        </div>
      </div>
    </section>

    <!-- D. 生成与聊天偏好 -->
    <section class="appearance-section">
      <div class="section-header">生成与聊天偏好</div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">默认代码类型</span>
          <span class="row-desc">创建新应用时的默认生成类型</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.defaultCodeType"
            style="width: 140px"
            @change="(v: string) => store.defaultCodeType = v"
          >
            <a-select-option
              v-for="opt in DEFAULT_CODE_TYPE_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">启用工作流生成</span>
          <span class="row-desc">BETA — 逻辑更严谨、图片更贴切</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.workflowEnabled" @change="(v: boolean) => store.workflowEnabled = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">聊天默认生成模式</span>
          <span class="row-desc">新对话的生成方式</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.chatGenMode"
            style="width: 120px"
            @change="(v: string) => store.chatGenMode = v"
          >
            <a-select-option value="legacy">传统</a-select-option>
            <a-select-option value="workflow">工作流</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">代码块主题</span>
          <span class="row-desc">代码语法高亮的配色方案</span>
        </div>
        <div class="row-control">
          <a-select
            :value="store.codeTheme"
            style="width: 120px"
            @change="(v: string) => store.codeTheme = v"
          >
            <a-select-option
              v-for="opt in CODE_THEME_OPTIONS"
              :key="opt.value"
              :value="opt.value"
            >{{ opt.label }}</a-select-option>
          </a-select>
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">工具卡片默认折叠</span>
          <span class="row-desc">写入/修改文件卡片默认收起</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.toolCardCollapsed" @change="(v: boolean) => store.toolCardCollapsed = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">预览面板默认展开</span>
          <span class="row-desc">打开对话时显示 iframe 预览</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.previewExpanded" @change="(v: boolean) => store.previewExpanded = v" />
        </div>
      </div>

      <div class="appearance-row">
        <div class="row-label">
          <span class="row-title">流式输出平滑滚动</span>
          <span class="row-desc">新内容自动滚动到底部</span>
        </div>
        <div class="row-control">
          <a-switch :checked="store.smoothScroll" @change="(v: boolean) => store.smoothScroll = v" />
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.appearance-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px;
}

/* 顶部栏 */
.page-header {
  margin-bottom: 24px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 0;
  border: none;
  background: none;
  color: var(--color-primary, #1677ff);
  cursor: pointer;
  font-size: 14px;
  margin-bottom: 12px;
}

.back-btn:hover {
  opacity: 0.8;
}

.page-title {
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 4px;
  color: var(--text-base, #1f1f1f);
}

.page-desc {
  margin: 0;
  color: var(--text-secondary, #666);
  font-size: 14px;
}

/* 分组卡片 */
.appearance-section {
  background: var(--bg-card, #fff);
  border-radius: var(--radius-lg, 12px);
  box-shadow: var(--shadow-card, 0 2px 8px rgba(0,0,0,0.06));
  overflow: hidden;
  margin-bottom: 16px;
}

.section-header {
  padding: 10px 16px;
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  color: var(--text-secondary, #666);
  background: var(--bg-section-header, #f0f5ff);
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

/* 设置行 */
.appearance-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border-color, #e8e8e8);
}

.appearance-row:last-child {
  border-bottom: none;
}

.row-label {
  flex: 1;
  min-width: 0;
  padding-right: 16px;
}

.row-title {
  display: block;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-base, #1f1f1f);
  margin-bottom: 2px;
}

.row-desc {
  display: block;
  font-size: 12px;
  color: var(--text-secondary, #666);
  line-height: 1.4;
}

.row-control {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-control--slider {
  min-width: 220px;
}

.row-control--color {
  gap: 6px;
}

.slider-value {
  min-width: 32px;
  font-size: 13px;
  color: var(--text-secondary, #666);
  text-align: center;
}

/* 响应式 */
@media (max-width: 576px) {
  .appearance-page {
    padding: 16px;
  }

  .appearance-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 8px;
  }

  .row-label {
    padding-right: 0;
  }

  .row-control {
    width: 100%;
  }

  .row-control--slider {
    min-width: unset;
    width: 100%;
  }
}
</style>
```

- [ ] **Step 7.2: 验证 build**

```bash
cd ai-generate-code-frontend
npm run build 2>&1 | tail -5
```

预期：`✓ built in ...s`

- [ ] **Step 7.3: commit**

```bash
git add ai-generate-code-frontend/src/page/User/UserAppearanceSettings.vue
git commit -m "feat: 新增外观设置页面 UserAppearanceSettings（4 分组 12 项）"
```

---

### Task 8: 更新 CodeBlock.vue — 颜色硬编码 → CSS 变量

**文件：**
- 修改: `src/components/CodeBlock.vue`

**设计说明：**
- 将 `:deep(.code-keyword)` 等高亮色的 `color` 值改为 `var(--code-keyword)` 变量
- 代码块背景/文本色同变量化
- `font-size` / `font-family` 改为 `var(--code-font-size)` / `var(--code-font-family)`
- 不改组件结构，只改 style 块

- [ ] **Step 8.1: 替换 scoped style 中的硬编码颜色**

找到以下行并逐一替换（只在 scoped style 内改，不改 template）：

```css
/* 原第 293 行 */
.code-block {
  /* ... 保留其他属性 ... */
  font-size: var(--code-font-size, 13px);      /* 原 13px */
  font-family: var(--code-font-family, Consolas, Monaco, "Courier New", monospace); /* 原硬编码 */
  background: var(--code-bg, #1e1e1e);          /* 原 #1e1e1e */
  color: var(--code-text, #d4d4d4);             /* 原 #d4d4d4 */
}

/* 原第 237 行 */
.code-block-header {
  background: var(--code-header-bg, #2d2d2d);   /* 原 #2d2d2d */
}

/* 原第 243 行 */
.code-block-container {
  background: var(--code-bg, #1e1e1e);           /* 原 #1e1e1e */
}

/* 高亮色（原第 369-413 行）全部替换 */
:deep(.code-keyword) {
  color: var(--code-keyword, #569cd6);
  font-weight: 500;
}

:deep(.code-string) {
  color: var(--code-string, #e6b450);
}

:deep(.code-comment) {
  color: var(--code-comment, #7f8ea3);
  font-style: italic;
}

:deep(.code-property) {
  color: var(--code-property, #7dcfff);
}

:deep(.code-value) {
  color: var(--code-value, #f9a8d4);
}

:deep(.code-selector) {
  color: var(--code-selector, #8be9fd);
}

:deep(.code-tag) {
  color: var(--code-tag, #ff7aa2);
}

:deep(.code-attr) {
  color: var(--code-attr, #9ccfd8);
}

:deep(.code-number) {
  color: var(--code-number, #c4b5fd);
}

:deep(.code-operator) {
  color: var(--code-operator, #f38ba8);
}

:deep(.code-function) {
  color: var(--code-function, #7ee787);
}
```

注意：`background: #1e1e1e` 也有在 `.code-block-container`（第 226 行）。如果容器背景也用变量可能会有视觉问题（header + container 背景应该一致）。保留容器背景不变或用同一个变量：

```css
.code-block-container {
  background: var(--code-bg, #1e1e1e);
  border: 1px solid var(--code-border, #3e3e3e);
}
```

- [ ] **Step 8.2: 验证 build**

```bash
cd ai-generate-code-frontend
npm run build 2>&1 | tail -5
```

预期：`✓ built in ...s`

- [ ] **Step 8.3: commit**

```bash
git add ai-generate-code-frontend/src/components/CodeBlock.vue
git commit -m "refactor: CodeBlock 高亮色改为 CSS 变量，支持主题切换"
```

---

### Task 9: 更新 HomeView.vue — 从 Store 读取初值

**文件：**
- 修改: `src/page/HomeView.vue`

**设计说明：**
- 首页的 `workflowBetaEnabled`、`autoCodeTypeEnabled`、`selectedCodeType` 当前是 `ref()` 写死
- 改为从 `appearanceStore` 读取初值：`workflowEnabled` → `workflowBetaEnabled`，`defaultCodeType` → `autoCodeTypeEnabled` + `selectedCodeType`
- 用户交互后仍可本地切换（不写回 store），store 只是提供初始默认值
- 最小侵入改动：只在 `ref()` 初始化时读取 store，不改 template

- [ ] **Step 9.1: 修改 HomeView.vue 的初始化**

在 import 区域新增：

```typescript
import { useAppearanceStore } from '@/stores/appearance'
```

在第 37-39 行替换：

```typescript
// 原：
const selectedCodeType = ref<string>(CodeGenTypeEnum.MULTI_FILE)
const autoCodeTypeEnabled = ref(true)
const workflowBetaEnabled = ref(false)

// 改为：
const appearanceStore = useAppearanceStore()
const selectedCodeType = ref<string>(
  appearanceStore.defaultCodeType === 'auto'
    ? CodeGenTypeEnum.MULTI_FILE
    : appearanceStore.defaultCodeType as string,
)
const autoCodeTypeEnabled = ref(appearanceStore.defaultCodeType === 'auto')
const workflowBetaEnabled = ref(appearanceStore.workflowEnabled)
```

- [ ] **Step 9.2: 验证 build**

```bash
cd ai-generate-code-frontend
npm run build 2>&1 | tail -5
```

预期：`✓ built in ...s`

- [ ] **Step 9.3: commit**

```bash
git add ai-generate-code-frontend/src/page/HomeView.vue
git commit -m "feat: HomeView 从外观 store 读取默认代码类型和工作流开关"
```

---

### Task 10: 更新 AppChatView.vue + CollapsibleCodeBlock.vue

**文件：**
- 修改: `src/page/App/AppChatView.vue`
- 修改: `src/components/CollapsibleCodeBlock.vue`

**设计说明：**
- AppChatView 的 `genMode` 初值从 store 的 `chatGenMode` 读取（仅首次，sessionStorage 写入后以 session 为准）
- 读取 `smoothScroll` 和 `previewExpanded` 作为对应状态的初值
- CollapsibleCodeBlock 的 `defaultCollapsed` prop 绑定 store 的 `toolCardCollapsed`

- [ ] **Step 10.1: AppChatView.vue 引入 store 初值**

在 script 区域 import：

```typescript
import { useAppearanceStore } from '@/stores/appearance'
```

在 `const genMode = ref<'legacy' | 'workflow'>('legacy')` 行（84 行附近），改为：

```typescript
const appearanceStore = useAppearanceStore()
const genMode = ref<'legacy' | 'workflow'>(appearanceStore.chatGenMode)
```

查找 `previewExpanded` 对应的变量（需确认源码中的实际变量名）。大致逻辑：

```typescript
/* 在预览相关区域，找到控制 iframe 显隐的变量，改为从 store 读取初值 */
const shouldShowWebsite = ref(false)
/* 或类似名称，如没有独立变量则绑定到 store.previewExpanded */

/* 流式滚动控制 */
const enableSmoothScroll = ref(appearanceStore.smoothScroll)
```

**注意：** 具体变量名需阅读 AppChatView.vue 的实际代码来确定。当前已知：
- `genMode` 已确认（第 84 行）
- `shouldShowWebsite`（第 463 行）控制预览显隐
- 滚动相关变量需查找

- [ ] **Step 10.2: CollapsibleCodeBlock.vue 绑定 defaultCollapsed**

在 script 区域：

```typescript
import { useAppearanceStore } from '@/stores/appearance'
const appearanceStore = useAppearanceStore()
```

在 template 中找到 `<collapsible-code-block>` 或类似的使用处，传入 prop：

```vue
<collapsible-code-block
  :default-collapsed="appearanceStore.toolCardCollapsed"
  ...
>
```

- [ ] **Step 10.3: 验证 build**

```bash
cd ai-generate-code-frontend
npm run build 2>&1 | tail -5
```

预期：`✓ built in ...s`

- [ ] **Step 10.4: commit**

```bash
git add ai-generate-code-frontend/src/page/App/AppChatView.vue ai-generate-code-frontend/src/components/CollapsibleCodeBlock.vue
git commit -m "feat: AppChatView/CollapsibleCodeBlock 读取外观 store 初值（genMode/滚动/折叠）"
```

---

### Task 11: 最终验证

- [ ] **Step 11.1: 构建验证**

```bash
cd ai-generate-code-frontend
npm run build 2>&1
```

预期：`✓ built in ...s`，无 error。

- [ ] **Step 11.2: Lint 验证**

```bash
cd ai-generate-code-frontend
npm run lint 2>&1
```

预期：无 error。

- [ ] **Step 11.3: 代码检查清单**

- [ ] GlobalHeader 下拉顺序：个人设置 → 外观设置 → 退出登录
- [ ] `/user/appearance` 可访问，4 个分组显示正确
- [ ] 外观模式切换（浅色/深色/跟随系统）即时生效
- [ ] 主题色修改即时影响 Ant Design 组件
- [ ] 字号修改即时生效（全局 + 代码块）
- [ ] 紧凑模式 / 减少动画开关生效
- [ ] 刷新页面后所有设置保留
- [ ] 移动端 375px 无横向滚动
- [ ] 暗色模式下文字可读
- [ ] `/user/settings` 未受影响

- [ ] **Step 11.4: 最终 commit**

```bash
git add ai-generate-code-frontend/
git commit -m "chore: 外观设置系统全部实现"
```
