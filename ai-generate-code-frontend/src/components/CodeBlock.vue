<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch, type ComponentPublicInstance } from 'vue'
import {
  codeByteLength,
  getCachedHighlight,
  HIGHLIGHT_BYTE_THRESHOLD,
  highlightCodeLines,
  setCachedHighlight,
  VIRTUAL_LINE_THRESHOLD,
  type HighlightedCode,
} from '@/utils/codeHighlighter'

interface Props {
  code: string
  language?: string
  isStreaming?: boolean
  fileLabel?: string
}

const props = withDefaults(defineProps<Props>(), { language: 'text', isStreaming: false })
const codeRef = ref<HTMLElement | null>(null)
const highlighted = ref<HighlightedCode | null>(null)
const workerBusy = ref(false)
const viewportTop = ref(0)
const viewportHeight = ref(0)
const hadStreaming = ref(props.isStreaming)
let worker: Worker | null = null
let requestId = 0
let resizeObserver: ResizeObserver | null = null

const rawCode = computed(() => props.code ?? '')
const byteLength = computed(() => codeByteLength(rawCode.value))
const plainLines = ref<string[]>([])
let plainSource = ''
function updatePlainLines(code: string) {
  if (code.startsWith(plainSource) && plainLines.value.length > 0) {
    const suffix = code.slice(plainSource.length)
    if (suffix) {
      const additions = suffix.split(/\r?\n/)
      const tail = plainLines.value[plainLines.value.length - 1] ?? ''
      plainLines.value[plainLines.value.length - 1] = tail + (additions.shift() ?? '')
      plainLines.value.push(...additions)
    }
  } else {
    plainLines.value = code.split(/\r?\n/)
  }
  plainSource = code
}
const lines = computed(() => highlighted.value?.lines ?? plainLines.value)
const virtualized = computed(
  () => byteLength.value > HIGHLIGHT_BYTE_THRESHOLD || lines.value.length > VIRTUAL_LINE_THRESHOLD,
)
const lineHeight = 20.8
const overscan = 30
const firstLine = computed(() => Math.max(0, Math.floor(viewportTop.value / lineHeight) - overscan))
const visibleCount = computed(
  () => Math.ceil((viewportHeight.value || 400) / lineHeight) + overscan * 2,
)
const lastLine = computed(() => Math.min(lines.value.length, firstLine.value + visibleCount.value))
const visibleLines = computed(() => lines.value.slice(firstLine.value, lastLine.value))
const topSpacer = computed(() => `${firstLine.value * lineHeight}px`)
const bottomSpacer = computed(
  () => `${Math.max(0, lines.value.length - lastLine.value) * lineHeight}px`,
)

function ensureWorker() {
  if (worker || typeof Worker === 'undefined') return worker
  worker = new Worker(new URL('../workers/codeHighlight.worker.ts', import.meta.url), {
    type: 'module',
  })
  worker.onmessage = (
    event: MessageEvent<HighlightedCode & { type: string; requestId: number }>,
  ) => {
    if (event.data.requestId !== requestId) return
    workerBusy.value = false
    if (event.data.type === 'result') {
      highlighted.value = { html: event.data.lines.join('\n'), lines: event.data.lines }
      setCachedHighlight(rawCode.value, props.language, highlighted.value)
      updateViewport()
    }
  }
  worker.onerror = () => {
    workerBusy.value = false
    highlighted.value = null
  }
  return worker
}

function updateViewport() {
  if (!codeRef.value) return
  viewportTop.value = codeRef.value.scrollTop
  viewportHeight.value = codeRef.value.clientHeight
}

function refreshHighlight() {
  const code = rawCode.value
  if (props.isStreaming) hadStreaming.value = true
  updatePlainLines(code)
  highlighted.value = null
  if (props.isStreaming || !code) {
    if (workerBusy.value && worker) worker.postMessage({ type: 'cancel', requestId })
    requestId += 1
    workerBusy.value = false
    updateViewport()
    return
  }
  const cached = getCachedHighlight(code, props.language)
  if (cached) {
    highlighted.value = cached
    updateViewport()
    return
  }
  if (codeByteLength(code) > HIGHLIGHT_BYTE_THRESHOLD) {
    const current = ensureWorker()
    if (!current) return
    const id = ++requestId
    workerBusy.value = true
    current.postMessage({ type: 'highlight', requestId: id, code, language: props.language })
    return
  }
  highlighted.value = highlightCodeLines(code, props.language)
  setCachedHighlight(code, props.language, highlighted.value)
  updateViewport()
}

watch(() => [props.code, props.language, props.isStreaming], refreshHighlight, { immediate: true })

function copyCode() {
  void navigator.clipboard?.writeText(rawCode.value)
}

function scrollToTop() {
  codeRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
}
function scrollToBottom() {
  if (codeRef.value) codeRef.value.scrollTo({ top: codeRef.value.scrollHeight, behavior: 'smooth' })
}

onBeforeUnmount(() => {
  if (worker) worker.terminate()
  resizeObserver?.disconnect()
})

function attachCodeRef(el: Element | ComponentPublicInstance | null) {
  if (!(el instanceof HTMLElement)) return
  codeRef.value = el
  resizeObserver?.disconnect()
  resizeObserver = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(updateViewport)
  resizeObserver?.observe(el)
  nextTick(updateViewport)
}
</script>

<template>
  <div class="code-block-container">
    <div class="code-block-header">
      <div class="code-block-header-left">
        <span v-if="fileLabel" class="code-file-label">{{ fileLabel }}</span>
        <span class="code-language">{{ language || 'text' }}</span>
      </div>
      <button class="copy-button" @click="copyCode" title="复制代码">📋</button>
    </div>
    <pre
      :ref="attachCodeRef"
      class="code-block"
      :class="{ 'code-block--stable-viewport': hadStreaming || virtualized }"
      @scroll="updateViewport"
    ><code v-if="virtualized" class="code-virtual"><span class="code-spacer" :style="{ height: topSpacer }" aria-hidden="true" /><span v-for="(line, index) in visibleLines" :key="firstLine + index" class="code-line"><span v-if="highlighted" v-html="line" /><template v-else>{{ line }}</template></span><span class="code-spacer" :style="{ height: bottomSpacer }" aria-hidden="true" /></code><code v-else-if="highlighted" v-html="highlighted.html" /><code v-else class="code-plain">{{ code }}</code></pre>
    <button class="scroll-button scroll-button-top" @click="scrollToTop" title="回到顶部">↑</button>
    <button class="scroll-button scroll-button-bottom" @click="scrollToBottom" title="跳到底部">
      ↓
    </button>
    <span v-if="workerBusy" class="highlight-status" aria-live="polite">Highlighting…</span>
  </div>
</template>

<style scoped>
.code-block-container {
  margin: 12px 0;
  border-radius: 8px;
  overflow: hidden;
  background: var(--code-bg, #1e1e1e);
  border: 1px solid var(--code-border, #3e3e3e);
  width: 100%;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
  position: relative;
}
.code-block-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: var(--code-header-bg, #2d2d2d);
  border-bottom: 1px solid var(--code-border, #3e3e3e);
}
.code-block-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}
.code-file-label {
  font-size: 12px;
  color: #c8c8c8;
  font-weight: 600;
  font-family: Consolas, Monaco, 'Courier New', monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 42vw;
}
.code-language {
  font-size: 12px;
  color: #858585;
  font-weight: 500;
  text-transform: uppercase;
}
.copy-button {
  background: transparent;
  border: none;
  color: #858585;
  cursor: pointer;
  font-size: 14px;
  padding: 4px 8px;
  border-radius: 4px;
}
.copy-button:hover {
  background: #3e3e3e;
  color: #fff;
}
.code-block {
  margin: 0;
  padding: 16px;
  background: var(--code-bg, #1e1e1e);
  color: var(--code-text, #d4d4d4);
  font-family: var(--code-font-family, Consolas, Monaco, 'Courier New', monospace);
  font-size: var(--code-font-size, 13px);
  line-height: 1.6;
  white-space: pre;
  word-wrap: normal;
  overflow: auto;
  max-height: 60vh;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.2) transparent;
  scrollbar-gutter: stable both-edges;
}
.code-block--stable-viewport {
  block-size: clamp(220px, 44dvh, 480px);
  min-block-size: clamp(220px, 44dvh, 480px);
  max-block-size: clamp(220px, 44dvh, 480px);
}
.code-block code {
  display: block;
  width: 100%;
  min-width: min-content;
}
.code-plain {
  contain: content;
}
.code-virtual {
  position: relative;
  min-height: 100%;
}
.code-line,
.code-spacer {
  display: block;
  height: 20.8px;
  line-height: 20.8px;
}
.code-spacer {
  pointer-events: none;
}
.scroll-button {
  position: absolute;
  right: 6px;
  width: 22px;
  height: 22px;
  border-radius: 999px;
  border: none;
  background: rgba(15, 23, 42, 0.65);
  color: #f9fafb;
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.18s ease;
}
.scroll-button-top {
  top: 48px;
}
.scroll-button-bottom {
  bottom: 10px;
}
.code-block-container:hover .scroll-button {
  opacity: 1;
}
.highlight-status {
  position: absolute;
  right: 10px;
  bottom: 8px;
  color: #858585;
  font-size: 11px;
}
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
</style>
