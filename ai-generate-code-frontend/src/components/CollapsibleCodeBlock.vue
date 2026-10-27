<script setup lang="ts">
import { ref, computed, onUnmounted } from 'vue'
import ToolExecCardHeader from '@/components/ToolExecCardHeader.vue'
import CodeBlock from '@/components/CodeBlock.vue'
import iconExpand from '@/assets/tool-status/icon-expand.png'
import iconCollapse from '@/assets/tool-status/icon-collapse.png'

/**
 * 可折叠/可复制的工具执行卡片。
 * 包裹 ToolExecCardHeader（始终可见）与可折叠的 CodeBlock 内容区。
 *
 * 交互：
 * - 右上角眼睛图标切换展开/折叠
 * - hover action-label（如"写入文件"）→ 显示"复制"，点击复制代码
 * - hover target-path（如"src/app.ts"）→ 显示"复制路径"，点击复制路径
 */
interface Props {
  /** 写入文件模式：代码内容 */
  code?: string
  /** 代码语言 */
  language?: string
  /** 文件路径 */
  filePath?: string

  /** 修改文件模式：替换前内容 */
  beforeCode?: string
  /** 修改文件模式：替换后内容 */
  afterCode?: string

  /** 是否正在流式输出中 */
  isStreaming?: boolean
  /** 修改文件的"替换前"是否已完成流式 */
  beforeDone?: boolean
  /** 修改文件的"替换后"是否已完成流式 */
  afterDone?: boolean

  /** 默认是否折叠，默认 true */
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

/* ---------- 模式判定 ---------- */
const isWriteMode = computed(() => props.code !== '')
const actionLabel = computed(() => (isWriteMode.value ? '写入文件' : '修改文件'))

/* ---------- 折叠 ---------- */
const collapsed = ref(props.defaultCollapsed)
function toggleCollapse() {
  collapsed.value = !collapsed.value
}

/* ---------- 复制代码 ---------- */
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
    copyCodeTimer = setTimeout(() => {
      copyCodeState.value = 'idle'
    }, 1500)
  } catch {
    // 剪贴板权限不足等静默失败
  }
}

async function copyFilePath() {
  if (!props.filePath) return
  try {
    await navigator.clipboard.writeText(props.filePath)
    copyPathState.value = 'copied'
    if (copyPathTimer) clearTimeout(copyPathTimer)
    copyPathTimer = setTimeout(() => {
      copyPathState.value = 'idle'
    }, 1500)
  } catch {
    // 剪贴板权限不足等静默失败
  }
}

/* 组件卸载时清除定时器，避免内存泄漏 */
onUnmounted(() => {
  if (copyCodeTimer) clearTimeout(copyCodeTimer)
  if (copyPathTimer) clearTimeout(copyPathTimer)
})
</script>

<template>
  <div class="collapsible-code-block">
    <!-- 卡片头部（始终可见） -->
    <ToolExecCardHeader :action-label="actionLabel" :target-path="filePath">
      <!-- slot：action-label 后的触发区，hover 显示"复制" -->
      <template #action-label-after>
        <span
          class="copy-hint"
          :class="{ 'copy-hint--copied': copyCodeState === 'copied' }"
          @click.stop="copyCodeContent"
        >
          {{ copyCodeState === 'copied' ? '✓已复制' : '复制' }}
        </span>
      </template>

      <!-- slot：target-path 后的触发区，hover 显示"复制路径" -->
      <template #target-path-after>
        <span
          class="copy-hint"
          :class="{ 'copy-hint--copied': copyPathState === 'copied' }"
          @click.stop="copyFilePath"
        >
          {{ copyPathState === 'copied' ? '✓已复制' : '复制路径' }}
        </span>
      </template>

      <!-- slot：头部右侧操作区——折叠/展开眼睛图标 -->
      <template #actions>
        <button
          class="collapse-eye-btn"
          :aria-label="collapsed ? '展开代码内容' : '收起代码内容'"
          :title="collapsed ? '展开代码' : '收起代码'"
          @click.stop="toggleCollapse"
        >
          <img :src="collapsed ? iconExpand : iconCollapse" class="collapse-eye-img" alt="" />
        </button>
      </template>
    </ToolExecCardHeader>

    <!-- 可折叠内容区：max-height 动画控制展开/收起 -->
    <div v-if="!collapsed" class="collapse-body" :class="{ 'collapse-body--expanded': true }">
      <!-- 写入文件模式 -->
      <template v-if="isWriteMode">
        <CodeBlock :code="code" :language="language" :is-streaming="isStreaming" />
      </template>

      <!-- 修改文件模式 -->
      <template v-else>
        <div class="diff-section">
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

<style scoped>
.collapsible-code-block {
  display: flex;
  flex-direction: column;
  gap: 0;
}

/* ---------- 折叠动画 ---------- */
.collapse-body {
  max-height: 0;
  overflow: hidden;
  opacity: 0;
  transition:
    max-height 250ms ease,
    opacity 200ms ease;
}

.collapse-body--expanded {
  max-height: 3000px; /* 足够容纳代码块（CodeBlock 本身限制 60vh） */
  opacity: 1;
}

/* ---------- 复制触发器 ---------- */
.copy-hint {
  /* 默认隐藏，父级 (.tool-exec-action-label / .tool-exec-target-path) hover 时显式 */
  opacity: 0;
  pointer-events: none;
  display: inline;
  font-size: 11px;
  font-weight: 500;
  color: #2563eb;
  cursor: pointer;
  margin-left: 4px;
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

/* ---------- 眼睛折叠按钮 ---------- */
.collapse-eye-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 3px;
  background: transparent;
  color: #64748b;
  cursor: pointer;
  transition: all 150ms ease;
}

.collapse-eye-btn:hover {
  background: var(--bg-mute, #f1f5f9);
}

.collapse-eye-img {
  width: 14px;
  height: 14px;
  display: block;
}

/* ---------- 修改文件的标题区 ---------- */
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

/* ---------- 无障碍：关闭动效 ---------- */
@media (prefers-reduced-motion: reduce) {
  .collapse-body {
    transition: none;
  }
  .collapse-body--expanded {
    max-height: none;
  }
}
</style>
