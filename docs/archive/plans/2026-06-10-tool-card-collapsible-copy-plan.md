# 工具调用卡片折叠 + Hover-Copy 交互 执行计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:super_run_plan to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 AppChatView 中的写入文件/修改文件工具卡片增加可折叠内容区 + hover 复制交互

**Architecture:** 新增 CollapsibleCodeBlock 组件，封装 ToolExecCardHeader 头部 + 可折叠的 CodeBlock 内容区。对 ToolExecCardHeader 做最小入侵改造（新增 2 个 named slot）以支持 label 和 path 后面的 hover 复制文本注入。AppChatView 两处渲染分支替换为新组件。

**Tech Stack:** Vue 3 (Composition API, `<script setup>`) + Ant Design Vue 4 + TypeScript + Streamline Icons (内联 SVG)

---

## 文件变更映射

| 文件 | 变更类型 | 职责 |
|------|---------|------|
| `src/components/ToolExecCardHeader.vue` | **小改** | 在 action-label 和 target-path 的 span 内各新增一个 named slot，供外部注入复制触发器 |
| `src/components/CollapsibleCodeBlock.vue` | **新增** | 核心组件：包裹 ToolExecCardHeader + 折叠内容区 + 眼睛图标切换 + hover 复制两区（代码/路径） |
| `src/page/App/AppChatView.vue` | **修改** | 两处 `v-else-if` 分支（写入文件 L3241-3254、修改文件 L3257-3289）替换为 `<CollapsibleCodeBlock>` 调用 |

## 设计决策说明

### 为什么拆成独立组件 CollapsibleCodeBlock？

- AppChatView.vue 已 ~4600 行，再堆 100+ 行折叠逻辑会进一步恶化可维护性
- 折叠状态管理是组件内部事务，不需要 AppChatView 知道每个卡片是否展开
- hover 复制涉及到每个卡片各自的 hover/click/success 状态，每个卡片应该独立管理
- CollapsibleCodeBlock 有清晰的 props 接口，可以独立测试和验证

### 为什么给 ToolExecCardHeader 加 slot 而不是直接操作 DOM？

- ToolExecCardHeader 使用 scoped styles，外部无法直接控制其内部元素的样式
- 从外部通过 querySelector 侵入内部 DOM 是脆弱的，组件内部重构就会 break
- 新增 named slot 是 Vue 标准扩展模式，侵入最小、向前兼容

### 为什么用 max-height 动画而不是 grid-template-rows？

- `grid-template-rows: 0fr` 在部分浏览器中动画不流畅
- `max-height` 方案兼容所有现代浏览器
- 代码块高度上限 60vh，设 2000px 足够容纳

---

### Task 1: 给 ToolExecCardHeader 增加 named slots

- [ ] **Step 1: 修改 ToolExecCardHeader.vue，给 action-label 增加 slot**

**设计原因：** CollapsibleCodeBlock 需要在 action-label 文字后面紧贴着插入"复制"文本，但标签文字在 ToolExecCardHeader 内部通过 scoped 样式渲染，外部无法控制。通过 named slot 让消费方注入内容，组件保持控制权但开放扩展点。

**涉及知识点：** Vue 3 named slots、scoped styles 隔离机制、组件扩展模式

打开 `src/components/ToolExecCardHeader.vue`，找到第 22 行：

```html
<span class="tool-exec-action-label">{{ actionLabel }}</span>
```

修改为：

```html
<span class="tool-exec-action-label">
  {{ actionLabel }}
  <slot name="action-label-after" />
</span>
```

- [ ] **Step 2: 给 ToolExecCardHeader 的 target-path 增加 slot**

找到第 24 行：

```html
<span v-if="targetPath" class="tool-exec-target-path">{{ targetPath }}</span>
```

修改为：

```html
<span v-if="targetPath" class="tool-exec-target-path">
  {{ targetPath }}
  <slot name="target-path-after" />
</span>
```

- [ ] **Step 3: 验证编译**

```bash
cd ai-generate-code-frontend
npx vue-tsc --noEmit 2>&1 | head -20
```

期望输出：类型检查通过，无报错。

---

### Task 2: 新增 CollapsibleCodeBlock.vue 组件

- [ ] **Step 1: 创建组件文件**

创建 `src/components/CollapsibleCodeBlock.vue`，写入完整的组件骨架，包含：

1. Props 接口定义（code, language, filePath, beforeCode, afterCode, isStreaming, beforeDone, afterDone, defaultCollapsed）
2. 内部折叠状态 `collapsed = ref(true)`
3. 复制状态管理：`copyCodeStatus = ref('idle')`（idle | copying | copied）、`copyPathStatus` 同理
4. 两个 inline SVG 图标常量（Visible / Invisible 1，来自 Core Line - Free）

```typescript
// CollapsibleCodeBlock.vue
<script setup lang="ts">
import { ref, computed } from 'vue'
import ToolExecCardHeader from '@/components/ToolExecCardHeader.vue'
import CodeBlock from '@/components/CodeBlock.vue'

interface Props {
  code?: string
  language?: string
  filePath?: string
  beforeCode?: string
  afterCode?: string
  isStreaming?: boolean
  beforeDone?: boolean
  afterDone?: boolean
  defaultCollapsed?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  code: '',
  language: 'text',
  filePath: '',
  beforeCode: '',
  afterCode: '',
  isStreaming: false,
  beforeDone: true,
  afterDone: true,
  defaultCollapsed: true,
})

const isWriteMode = computed(() => props.code !== undefined && props.code !== '')
const isModifyMode = computed(() => !isWriteMode.value)

const collapsed = ref(props.defaultCollapsed)

function toggleCollapse() {
  collapsed.value = !collapsed.value
}

// 复制状态
type CopyState = 'idle' | 'copied'
const copyCodeState = ref<CopyState>('idle')
const copyPathState = ref<CopyState>('idle')
let copyCodeTimer: ReturnType<typeof setTimeout> | null = null
let copyPathTimer: ReturnType<typeof setTimeout> | null = null

async function copyCodeContent() {
  const content = isWriteMode.value ? props.code : props.afterCode
  if (!content) return
  try {
    await navigator.clipboard.writeText(content)
    copyCodeState.value = 'copied'
    if (copyCodeTimer) clearTimeout(copyCodeTimer)
    copyCodeTimer = setTimeout(() => { copyCodeState.value = 'idle' }, 1500)
  } catch { /* 剪贴板权限不足等 */ }
}

async function copyFilePath() {
  if (!props.filePath) return
  try {
    await navigator.clipboard.writeText(props.filePath)
    copyPathState.value = 'copied'
    if (copyPathTimer) clearTimeout(copyPathTimer)
    copyPathTimer = setTimeout(() => { copyPathState.value = 'idle' }, 1500)
  } catch { /* 剪贴板权限不足等 */ }
}

// 眼睛图标 SVG —— 来自 streamline-mcp Core Line - Free 系列
const IconVisible = '<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 14 14"><g stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><path d="M13.23 6.2463c.1658.20672.2576.47529.2576.75376s-.0918.54704-.2576.75376c-1.05 1.27127-3.44003 3.74628-6.23003 3.74628s-5.18-2.47501-6.230002-3.74628c-.16584-.20672-.257639-.47529-.257639-.75376s.091799-.54704.257639-.75376C1.81997 4.97503 4.20997 2.5 6.99997 2.5S12.18 4.97503 13.23 6.2463Z"/><path d="M7 9c1.10457 0 2-.89543 2-2s-.89543-2-2-2-2 .89543-2 2 .89543 2 2 2Z"/></g></svg>'

const IconInvisible = '<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 14 14"><g stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><path d="M3.62914 3.6244C4.62188 2.9793 5.7722 2.5 6.99997 2.5c2.79 0 5.18003 2.47503 6.23003 3.7463.1658.20672.2576.47529.2576.75376s-.0918.54704-.2576.75376c-.5788.70075-1.5648 1.76726-2.8004 2.58338m-1.92963.9325c-.48238.1459-.98436.2304-1.5.2304-2.79 0-5.18-2.47501-6.230002-3.74628-.16584-.20672-.257639-.47529-.257639-.75376s.091799-.54704.257639-.75376c.332672-.40278.799852-.92639 1.371652-1.45383"/><path d="M8.41421 8.41427c.78105-.78105.78105-2.04738 0-2.82843-.78105-.78104-2.04737-.78104-2.82842 0"/><path d="M13.5 13.5.5.5"/></g></svg>'
</script>
```

- [ ] **Step 2: 实现组件模板**

```html
<template>
  <div class="collapsible-code-block">
    <!-- 卡片头部（始终可见） -->
    <ToolExecCardHeader
      :action-label="isWriteMode ? '写入文件' : '修改文件'"
      :target-path="filePath"
    >
      <!-- slot: action-label 后面的复制按钮 -->
      <template #action-label-after>
        <span
          class="copy-hint"
          :class="{ 'copy-hint--copied': copyCodeState === 'copied' }"
          @mouseenter="onLabelHover = true"
          @mouseleave="onLabelHover = false"
          @click.stop="copyCodeContent"
        >
          {{ copyCodeState === 'copied' ? '✓已复制' : '' }}
          <span v-if="copyCodeState === 'idle' && onLabelHover" class="copy-hint-text">复制</span>
        </span>
      </template>

      <!-- slot: target-path 后面的复制路径按钮 -->
      <template #target-path-after>
        <span
          class="copy-hint"
          :class="{ 'copy-hint--copied': copyPathState === 'copied' }"
          @mouseenter="onPathHover = true"
          @mouseleave="onPathHover = false"
          @click.stop="copyFilePath"
        >
          {{ copyPathState === 'copied' ? '✓已复制' : '' }}
          <span v-if="copyPathState === 'idle' && onPathHover" class="copy-hint-text">复制路径</span>
        </span>
      </template>

      <!-- 右侧：折叠切换眼睛图标 -->
      <template #actions>
        <button
          class="collapse-eye-btn"
          :aria-label="collapsed ? '展开代码内容' : '收起代码内容'"
          @click="toggleCollapse"
          v-html="collapsed ? IconVisible : IconInvisible"
        />
      </template>
    </ToolExecCardHeader>

    <!-- 可折叠内容区 -->
    <div
      class="collapse-body"
      :class="{ 'collapse-body--expanded': !collapsed }"
    >
      <!-- 写入文件模式：单个 CodeBlock -->
      <template v-if="isWriteMode">
        <CodeBlock
          :code="code"
          :language="language"
          :is-streaming="isStreaming && !beforeDone"
        />
      </template>

      <!-- 修改文件模式：替换前 + 替换后 -->
      <template v-else-if="isModifyMode">
        <div style="display: flex; flex-direction: column; gap: 8px">
          <div class="diff-title">替换前</div>
          <CodeBlock
            :code="beforeCode"
            :language="language"
            :is-streaming="isStreaming && !beforeDone"
          />
          <div class="diff-title">替换后</div>
          <CodeBlock
            :code="afterCode"
            :language="language"
            :is-streaming="isStreaming && !afterDone"
          />
        </div>
      </template>
    </div>
  </div>
</template>
```

等待，上面模板中我漏掉了 `onLabelHover` 和 `onPathHover` 的声明。让我修正——需要在 `<script setup>` 中增加：

```typescript
const onLabelHover = ref(false)
const onPathHover = ref(false)
```

但这个方案有问题——hover 状态是通过 `@mouseenter/@mouseleave` 在复制按钮上控制的，但用户需要的是 hover **整个 action-label 文字区域**时触发，而不是 hover 到复制按钮上才触发。

**方案修正：** 使用 CSS `:hover` 控制显隐，而不是 JS 的 onLabelHover。

copy-hint 文字默认透明隐藏，宿主 span（slot 所在的 span 父节点）hover 时显示。但父节点是 ToolExecCardHeader 内部的 `.tool-exec-action-label`，它控制着这个 slot。

**更好的方案：** 让 ToolExecCardHeader 的 label span 支持 `:hover` 触发内部 slot 元素的显隐。

修改 ToolExecCardHeader 的样式：

```css
.tool-exec-action-label:where(:hover) :slotted(.copy-hint) {
  opacity: 1;
  pointer-events: auto;
}
```

但 Vue 的 `::v-slotted()` 伪元素有限制。更简单的方式：**在 CollapsibleCodeBlock 中用 CSS 控制**：

由于 copy-hint 渲染在了 `.tool-exec-action-label` 内部，可以通过 CSS 从父级 hover 驱动子级显隐。但因为 ToolExecCardHeader 有 scoped styles，CollapsibleCodeBlock 的样式无法穿透。

所以最干净的方案是：**在 CollapsibleCodeBlock 中用包裹层 + CSS 兄弟选择器**。

实际上，有一种更简洁的方式。slot 内容会被渲染到 ToolExecCardHeader 的 `<span class="tool-exec-action-label">` 内部。在 CollapsibleCodeBlock 中，可以使用 `::v-deep` 来穿透到 ToolExecCardHeader 的作用域，但更好的方式是在 ToolExecCardHeader 中直接处理。

让我换一个思路——**不在 ToolExecCardHeader 加 slot，而是 CollapsibleCodeBlock 直接不依赖 ToolExecCardHeader 的内部结构，通过对外部容器的 hover 控制内部元素的显隐。**

但问题是，ToolExecCardHeader 内部渲染了 label 和 path，CollapsibleCodeBlock 无法控制其内部 hover。

**最干净的方案：在 ToolExecCardHeader 的样式表中添加支持。**

修改 ToolExecCardHeader.vue 的样式，加一条规则让 `.tool-exec-action-label` 内部 slot 出来的 `.copy-hint` 在 label hover 时显示：

```css
.tool-exec-action-label:where(:hover) .copy-hint {
  opacity: 1;
}
```

因为 `.copy-hint` 会由 slot 渲染到 `.tool-exec-action-label` 内部，所以父级 hover 可以控制子级显隐。但 scoped styles 限制了这个。

在 Vue 3 的 scoped styles 中，`:slotted()` 选择器可以选中 slot 内容：

```css
.tool-exec-action-label:hover :slotted(.copy-hint) {
  opacity: 1;
}
```

但 `:slotted()` 只能选中 slot 的直接子元素。如果 `.copy-hint` 是深层嵌套，就不能用了。

实际 slot 渲染后的 DOM 结构会是：

```html
<span class="tool-exec-action-label" data-v-xxx>
  写入文件
  <span class="copy-hint" data-v-yyy>复制</span>  <!-- <!-- 来自 CollapsibleCodeBlock 的 slot 内容 -->
</span>
```

这里的 `.copy-hint` 不是 `.tool-exec-action-label` 的直接子元素时，slot 内容会作为 direct children 插入吗？是的，Vue named slot 的内容会被渲染为父组件的直接子元素（在 slot 位置）。

好，那在 ToolExecCardHeader 的 scoped style 中加一条：

```css
.tool-exec-action-label:hover :slotted(.copy-hint) {
  opacity: 1;
  pointer-events: auto;
}
```

这样就对了。同样对 target-path 也加一条。

但更简单的方式是：**不在 ToolExecCardHeader 加任何样式，让 CollapsibleCodeBlock 使用全局样式或非 scoped style**。

实际上最简单的方式：**把 CollapsibleCodeBlock 的样式写成非 scoped（或者使用 module）**。但这也破坏了样式隔离。

**最终决定：在 ToolExecCardHeader 样式表中加两条 `:slotted()` 规则。**

这是在 ToolExecCardHeader 中做的最小改动，且完全合理——"头部元素的 hover 态影响其后置 slot 内容"是 ToolExecCardHeader 自身行为的一部分。

好，让我重新组织完整正确的实现。

对于 Task 1（修改 ToolExecCardHeader），增加 slots 和样式。

对于 Task 2（CollapsibleCodeBlock），正确的模板和样式。

让我把计划写得更完整。

- [ ] **Step 2（修正）：在 ToolExecCardHeader 样式表中增加 slot 内容 hover 控制**

在 ToolExecCardHeader.vue 的 `<style scoped>` 末尾增加：

```css
/* hover 时显示 label/path 后面的 slot 内容（如复制按钮） */
.tool-exec-action-label:hover :slotted(.copy-hint),
.tool-exec-target-path:hover :slotted(.copy-hint) {
  opacity: 1;
  pointer-events: auto;
}
```

- [ ] **Step 3：在 CollapsibleCodeBlock 中实现组件脚本和模板**

正确完整的 template 设计如下：

```html
<template>
  <div class="collapsible-code-block">
    <ToolExecCardHeader
      :action-label="actionLabel"
      :target-path="filePath"
    >
      <!-- 在 label 后注入复制按钮 -->
      <template #action-label-after>
        <span
          class="copy-hint"
          :class="{ 'copy-hint--copied': copyCodeState === 'copied' }"
          @click.stop="copyCodeContent"
        >{{ copyCodeState === 'copied' ? '✓已复制' : (copyCodeState === 'idle' ? '复制' : '') }}</span>
      </template>

      <!-- 在路径后注入复制路径按钮 -->
      <template #target-path-after>
        <span
          class="copy-hint"
          :class="{ 'copy-hint--copied': copyPathState === 'copied' }"
          @click.stop="copyFilePath"
        >{{ copyPathState === 'copied' ? '✓已复制' : (copyPathState === 'idle' ? '复制路径' : '') }}</span>
      </template>

      <template #actions>
        <button
          class="collapse-eye-btn"
          :aria-label="collapsed ? '展开代码内容' : '收起代码内容'"
          title="展开/收起代码"
          @click.stop="toggleCollapse"
          v-html="collapsed ? IconVisible : IconInvisible"
        />
      </template>
    </ToolExecCardHeader>

    <div
      class="collapse-body"
      :class="{ 'collapse-body--expanded': !collapsed }"
    >
      <template v-if="isWriteMode">
        <CodeBlock :code="code" :language="language" :is-streaming="isStreaming" />
      </template>
      <template v-else>
        <div class="diff-section">
          <div class="diff-title">替换前</div>
          <CodeBlock :code="beforeCode" :language="language" :is-streaming="isStreaming && !beforeDone" />
          <div class="diff-title">替换后</div>
          <CodeBlock :code="afterCode" :language="language" :is-streaming="isStreaming && !afterDone" />
        </div>
      </template>
    </div>
  </div>
</template>
```

这里注意 hover 的逻辑变化——不再用 JS 变量控制 copy-hint 显隐，而是纯 CSS 控制：`.copy-hint` 默认 `opacity: 0`，当父级 `.tool-exec-action-label:hover` 时变为 `opacity: 1`。

而对于 修改文件 模式，actionLabel 需要根据是否 writeMode 计算：

```typescript
const actionLabel = computed(() => isWriteMode.value ? '写入文件' : '修改文件')
```

- [ ] **Step 4: 编写 CollapsibleCodeBlock 样式**

```css
<style scoped>
.collapsible-code-block {
  display: flex;
  flex-direction: column;
  gap: 0;
}

/* 可折叠内容区：默认折叠 */
.collapse-body {
  max-height: 0;
  overflow: hidden;
  opacity: 0;
  transition: max-height 250ms ease, opacity 200ms ease;
}

.collapse-body--expanded {
  max-height: 3000px;
  opacity: 1;
}

/* hover 复制按钮 */
.copy-hint {
  opacity: 0;
  pointer-events: none;
  font-size: 11px;
  color: #2563eb;
  cursor: pointer;
  margin-left: 6px;
  white-space: nowrap;
  user-select: none;
  transition: opacity 150ms ease;
}

.copy-hint--copied {
  opacity: 1 !important;
  pointer-events: auto !important;
  color: #16a34a;
}

.copy-hint:hover {
  text-decoration: underline;
}

/* 眼睛折叠按钮 */
.collapse-eye-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: all 150ms ease;
}

.collapse-eye-btn:hover {
  background: #f1f5f9;
  color: #0f172a;
}

.collapse-eye-btn svg {
  width: 16px;
  height: 16px;
}

/* 修改文件的标题区 */
.diff-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.diff-title {
  font-size: 12px;
  font-weight: 600;
  color: #111827;
}

/* prefers-reduced-motion */
@media (prefers-reduced-motion: reduce) {
  .collapse-body {
    transition: none;
  }
  .collapse-body--expanded {
    max-height: none;
  }
}
</style>
```

- [ ] **Step 5: 下载两个 streamine 图标并验证 SVG**

从 `src/components/CollapsibleCodeBlock.vue` 中提取的 SVG 路径已经完整。使用前确认：

1. Visible（睁眼）SVG: 14×14 viewBox, 路径正确, 用 `currentColor` 着色
2. Invisible 1（闭眼斜线）SVG: 14×14 viewBox, 路径正确, 用 `currentColor` 着色

两个图标放在组件内部作为字符串常量直接 `v-html` 渲染。原因：
- 仅在此组件使用，无需抽取为独立组件
- 内联 SVG 零网络请求
- `currentColor` 确保跟随主题色

---

### Task 3: 修改 AppChatView.vue 渲染分支

- [ ] **Step 1: 替换写入文件卡片分支**

打开 `src/page/App/AppChatView.vue`，找到第 3241-3254 行的写入文件渲染分支：

```html
<!-- TOOL_EXECUTED：写入文件卡片（可流式追加） -->
<div v-else-if="segment.kind === 'tool_executed_write_file'" class="tool-exec-card">
  <ToolExecCardHeader action-label="写入文件" :target-path="segment.filePath">
    <template #actions>
      <a-button size="small" class="ghost-btn" @click="copyToClipboard(segment.content)">
        复制内容
      </a-button>
    </template>
  </ToolExecCardHeader>
  <CodeBlock
    :code="segment.content"
    :language="segment.language"
    :is-streaming="isStreamActiveForMessage(m) && !segment.done"
  />
</div>
```

替换为：

```html
<!-- TOOL_EXECUTED：写入文件卡片（可折叠+可复制） -->
<div v-else-if="segment.kind === 'tool_executed_write_file'" class="tool-exec-card">
  <CollapsibleCodeBlock
    :code="segment.content"
    :language="segment.language"
    :file-path="segment.filePath"
    :is-streaming="isStreamActiveForMessage(m) && !segment.done"
  />
</div>
```

- [ ] **Step 2: 替换修改文件卡片分支**

找到第 3257-3289 行的修改文件渲染分支：

```html
<!-- TOOL_EXECUTED：修改文件卡片（替换前/替换后） -->
<div v-else-if="segment.kind === 'tool_executed_modify_file'" class="tool-exec-card">
  <ToolExecCardHeader action-label="修改文件" :target-path="segment.filePath">
    <template #actions>
      <a-button size="small" class="ghost-btn" @click="copyToClipboard(segment.afterContent)">
        复制替换后
      </a-button>
    </template>
  </ToolExecCardHeader>
  <div style="display: flex; flex-direction: column; gap: 8px">
    <div class="tool-call-title" style="font-size: 12px; font-weight: 600; color: #111827">替换前</div>
    <CodeBlock :code="segment.beforeContent" :language="segment.language" ... />
    <div class="tool-call-title" style="font-size: 12px; font-weight: 600; color: #111827">替换后</div>
    <CodeBlock :code="segment.afterContent" :language="segment.language" ... />
  </div>
</div>
```

替换为：

```html
<!-- TOOL_EXECUTED：修改文件卡片（可折叠+可复制） -->
<div v-else-if="segment.kind === 'tool_executed_modify_file'" class="tool-exec-card">
  <CollapsibleCodeBlock
    :before-code="segment.beforeContent"
    :after-code="segment.afterContent"
    :language="segment.language"
    :file-path="segment.filePath"
    :is-streaming="isStreamActiveForMessage(m)"
    :before-done="segment.beforeDone"
    :after-done="segment.afterDone"
  />
</div>
```

- [ ] **Step 3: 在 AppChatView.vue 的 import 中增加 CollapsibleCodeBlock**

打开文件顶部，找到 `@/components/CodeBlock` 的 import 行附近，增加：

```typescript
import CollapsibleCodeBlock from '@/components/CollapsibleCodeBlock.vue'
```

- [ ] **Step 4: 清理 `copyToClipboard` 方法引用**

检查替换后的 AppChatView 中是否还有其他地方调用 `copyToClipboard`。如果有其他非工具卡片的地方使用则保留，否则可以移除该方法。

---

### Task 4: 编译验证

- [ ] **Step 1: 前端类型检查**

```bash
cd ai-generate-code-frontend
npx vue-tsc --noEmit
```

期望：0 errors

- [ ] **Step 2: 前端构建**

```bash
npm run build
```

期望：build 成功，无错误

---

## 执行安排

Build 验证成功后，进入 `/super_review_code` 做最终代码审查。

## 已知边界与风险

1. **SSE 流式场景**：CollapsibleCodeBlock 的 `isStreaming` prop 传递后，内部 CodeBlock 的流式行为不变
2. **历史消息回放**：折叠默认值 `true`，历史消息的代码块全部默认折叠
3. **无路径场景**：`filePath` 为空字符串时，传递空串给 ToolExecCardHeader，其 `v-if="targetPath"` 条件不满足，路径和连接器箭头不渲染
4. **无障碍**：`prefers-reduced-motion` 时跳过展开/折叠动画
