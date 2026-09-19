<template>
  <div class="graph-root cosmic-workbench">
    <!-- 空状态：没节点时不画布，直接说明 -->
    <div v-if="noData" class="graph-empty">
      <p>暂无图谱</p>
    </div>

    <template v-else>
      <div ref="shellRef" class="graph-shell">
        <canvas
          ref="canvasRef"
          class="graph-canvas"
          role="img"
          aria-label="项目源码关系图，可通过搜索框选择节点"
          :class="{ 'is-dragging': dragging }"
          @pointerdown="handlePointerDown"
          @pointermove="handlePointerMove"
          @pointerup="handlePointerUp"
          @pointercancel="handlePointerUp"
          @wheel="handleWheel"
        />
        <!-- 集群标签用 DOM 而不是画在 canvas 里：大字号文字用 canvas 渲染会糊 -->
        <div class="cluster-labels">
          <span
            v-for="label in clusterLabels"
            :key="label.id"
            class="cluster-label"
            :style="{
              transform: `translate3d(${px(label.x)}px, ${px(label.y)}px, 0) translate(-50%, -50%)`,
              opacity: label.opacity,
            }"
            >{{ label.label }}</span
          >
        </div>

        <!-- 工具栏 -->
        <div class="graph-toolbar">
          <div class="segmented">
            <button type="button" :class="{ active: mode === 'local' }" @click="setMode('local')">
              局部
            </button>
            <button type="button" :class="{ active: mode === 'global' }" @click="setMode('global')">
              全局
            </button>
          </div>
          <div class="toolbox">
            <button type="button" title="放大" @click="engine?.zoomBy(1.18); startLoop()">＋</button>
            <button type="button" title="缩小" @click="engine?.zoomBy(0.84); startLoop()">−</button>
            <button type="button" title="适应视图" @click="engine?.fit(); startLoop()">⌗</button>
            <button type="button" title="重置布局" @click="handleReset">↺</button>
          </div>
        </div>

        <!-- 搜索 -->
        <div class="graph-search">
          <div class="searchbox">
            <span aria-hidden="true">⌕</span>
            <input
              ref="searchRef"
              v-model="query"
              placeholder="搜索文件、类、接口…"
              autocomplete="off"
              @input="handleSearchInput"
              @focus="handleSearchInput"
            />
            <kbd>Ctrl K</kbd>
          </div>
          <div v-if="hits.length > 0" class="search-results">
            <button
              type="button"
              v-for="hit in hits"
              :key="hit.key"
              class="search-result"
              @click="handlePickHit(hit)"
            >
              <span>{{ hit.name }}</span>
              <small>{{ hit.kind }}</small>
            </button>
          </div>
        </div>

        <!-- Trace：静态分析出来的路径，不是实时追踪 -->
        <div v-if="tracePaths.length > 0" class="trace-panel">
          <select
            v-model="traceKey"
            class="trace-select"
            aria-label="静态追踪入口"
            @change="handleTraceChange"
          >
            <option value="">选择入口…</option>
            <option v-for="path in tracePaths" :key="path.key" :value="path.key">
              {{ path.label }}
            </option>
          </select>
          <button
            type="button"
            class="mini-btn"
            :class="{ active: traceActive }"
            @click="toggleTrace"
          >
            追踪路径
          </button>
          <span class="trace-note">静态分析</span>
        </div>

        <!-- HUD -->
        <div class="graph-hud">
          <span class="pulse-live" />
          <b>{{ visibleCount }} / {{ nodeCount }} 节点</b>
        </div>

        <!-- 抽屉 -->
        <aside
          class="drawer"
          :class="{ open: drawerOpen }"
          :inert="!drawerOpen"
          aria-label="节点详情"
        >
          <template v-if="drawer">
            <div class="drawer-head">
              <div>
                <div class="eyebrow">{{ drawer.kind }}</div>
                <h2>{{ drawer.name }}</h2>
              </div>
              <button type="button" class="icon-btn" title="关闭" @click="closeDrawer">×</button>
            </div>
            <div class="drawer-body">
              <div v-if="drawer.path" class="drawer-path">{{ drawer.path }}</div>
              <div class="stat-strip">
                <div class="dstat">
                  <b>{{ drawer.incoming }}</b
                  ><small>被依赖</small>
                </div>
                <div class="dstat">
                  <b>{{ drawer.outgoing }}</b
                  ><small>依赖</small>
                </div>
                <div class="dstat">
                  <b>{{ drawer.usedBy.length + drawer.dependsOn.length }}</b
                  ><small>关联</small>
                </div>
              </div>

              <div class="evidence-line">
                <span
                  class="kn-evidence"
                  :class="{ 'is-inferred': drawer.evidence !== 'CODE_EVIDENCE' }"
                >
                  {{ drawer.evidence === 'CODE_EVIDENCE' ? '源码证据' : 'AI 推断' }}
                </span>
              </div>
              <p v-if="drawer.summary" class="drawer-summary">{{ drawer.summary }}</p>

              <div v-if="drawer.dependsOn.length" class="detail-group">
                <div class="eyebrow">依赖</div>
                <div class="chip-list">
                  <span v-for="name in drawer.dependsOn" :key="name" class="chip">{{ name }}</span>
                </div>
              </div>
              <div v-if="drawer.usedBy.length" class="detail-group">
                <div class="eyebrow">被依赖</div>
                <div class="chip-list">
                  <span v-for="name in drawer.usedBy" :key="name" class="chip">{{ name }}</span>
                </div>
              </div>

              <div class="drawer-actions">
                <button type="button" :disabled="!drawer.sourceRef" @click="handleOpenSource">
                  ↗ 打开源码
                </button>
                <button type="button" @click="handleExplain">✦ 让 AI 解释</button>
                <button type="button" @click="engine?.focusSelected()">◎ 聚焦</button>
              </div>
            </div>
          </template>
        </aside>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import './workbench.css'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import {
  KnowledgeGraphEngine,
  type DrawerInfo,
  type GraphSearchHit,
  type TracePath,
} from '@/utils/knowledgeGraphEngine'
import type { GraphEdge, GraphNode } from '@/utils/appKnowledgeApi'

const props = defineProps<{
  nodes: GraphNode[]
  edges: GraphEdge[]
}>()

const emit = defineEmits<{
  (e: 'open-source', sourceRef: string): void
  (e: 'explain', node: { name: string; summary: string }): void
}>()

const shellRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const searchRef = ref<HTMLInputElement | null>(null)

// 引擎持有大量可变物理状态，放进 shallowRef：交给它自己管，不让 Vue 深度代理
const engine = shallowRef<KnowledgeGraphEngine | null>(null)

const nodeCount = ref(0)
const visibleCount = ref(0)
const mode = ref<'local' | 'global'>('local')
const dragging = ref(false)
const clusterLabels = ref<
  Array<{ id: string; label: string; x: number; y: number; opacity: number }>
>([])

const query = ref('')
const hits = ref<GraphSearchHit[]>([])
const tracePaths = ref<TracePath[]>([])
const traceKey = ref('')
const traceActive = ref(false)

const drawerOpen = ref(false)
const drawer = ref<DrawerInfo | null>(null)

let frame: number | null = null
let resizeObserver: ResizeObserver | null = null
let lastLabelPaint = 0
let pointerDownAt = { x: 0, y: 0, moved: false }

/**
 * rAF 的所有权在这里，不在引擎里。
 *
 * 引擎的 tick 返回「还需不需要下一帧」——静止时返回 false，循环就自己停下，
 * 页面不可见时也停。这样静止的图谱完全不占 CPU，而不是空转刷帧。
 */
function loop(time: number): void {
  frame = null
  const current = engine.value
  if (!current) {
    return
  }
  const needsMore = current.tick(time)

  // 集群标签节流更新：每帧写 DOM 会拖慢绘制
  if (time - lastLabelPaint > 180) {
    lastLabelPaint = time
    clusterLabels.value = current.clusterLabelState()
    visibleCount.value = current.stats().visible
  }

  if (needsMore && !document.hidden) {
    frame = requestAnimationFrame(loop)
  }
}

/** 取整再写进 transform：小数会让浏览器每帧重算亚像素布局 */
function px(value: number): number {
  return Math.round(value)
}

function requestDraw(): void {
  if (frame == null && !document.hidden) {
    frame = requestAnimationFrame(loop)
  }
}

function startLoop(): void {
  requestDraw()
}

function stopLoop(): void {
  if (frame != null) {
    cancelAnimationFrame(frame)
    frame = null
  }
}

function handleResize(): void {
  const current = engine.value
  if (!current) {
    return
  }
  current.resize()
  current.fit()
  clusterLabels.value = current.clusterLabelState()
  startLoop()
}

function setMode(next: 'local' | 'global'): void {
  const current = engine.value
  if (!current) {
    return
  }
  mode.value = next
  current.setMode(next)
  clusterLabels.value = current.clusterLabelState()
  startLoop()
}

function handleReset(): void {
  const current = engine.value
  if (!current) {
    return
  }
  current.reset()
  drawerOpen.value = false
  drawer.value = null
  traceKey.value = ''
  traceActive.value = false
  clusterLabels.value = current.clusterLabelState()
  startLoop()
}

/* ---------------- 指针交互 ---------------- */

function localPoint(event: PointerEvent | WheelEvent): { x: number; y: number } {
  const rect = canvasRef.value!.getBoundingClientRect()
  return { x: event.clientX - rect.left, y: event.clientY - rect.top }
}

function handlePointerDown(event: PointerEvent): void {
  const current = engine.value
  if (!current) {
    return
  }
  canvasRef.value?.setPointerCapture?.(event.pointerId)
  const point = localPoint(event)
  pointerDownAt = { x: event.clientX, y: event.clientY, moved: false }
  const hit = current.pointerDown(point.x, point.y)
  dragging.value = true
  if (hit === 'node') {
    startLoop()
  }
}

function handlePointerMove(event: PointerEvent): void {
  const current = engine.value
  if (!current) {
    return
  }
  const point = localPoint(event)
  const delta = { x: event.movementX, y: event.movementY }
  if (
    Math.abs(event.clientX - pointerDownAt.x) > 4 ||
    Math.abs(event.clientY - pointerDownAt.y) > 4
  ) {
    pointerDownAt.moved = true
  }
  current.pointerMove(point.x, point.y, delta)
  canvasRef.value!.style.cursor = current.hitTest(point.x, point.y) ? 'pointer' : 'grab'
  // 只在真的按住时才推进动画。光标扫过画布不该把已经停下来的渲染循环重新拉起来 ——
  // 「空闲停帧」是这套引擎省电的前提，让它随时被鼠标移动唤醒就白做了。
  if (dragging.value) {
    startLoop()
  }
}

function handlePointerUp(event: PointerEvent): void {
  const current = engine.value
  if (!current) {
    return
  }
  canvasRef.value?.releasePointerCapture?.(event.pointerId)
  const wasDragging = dragging.value
  const moved = pointerDownAt.moved
  current.pointerUp()
  dragging.value = false

  // 没拖动才算点击选中 —— 否则拖完松手会误选
  if (wasDragging && !moved) {
    const point = localPoint(event)
    const node = current.hitTest(point.x, point.y)
    if (node) {
      current.selectNode(node)
      drawer.value = current.describe(node.id)
      drawerOpen.value = true
      traceActive.value = false
      clusterLabels.value = current.clusterLabelState()
    }
  }
  startLoop()
}

function handleWheel(event: WheelEvent): void {
  const current = engine.value
  if (!current) {
    return
  }
  event.preventDefault()
  const point = localPoint(event)
  current.wheel(event.deltaY, point.x, point.y)
  startLoop()
}

/* ---------------- 搜索 ---------------- */

function handleSearchInput(): void {
  hits.value = engine.value?.search(query.value) ?? []
}

function handlePickHit(hit: GraphSearchHit): void {
  const current = engine.value
  if (!current) {
    return
  }
  if (current.selectByKey(hit.key, 1.42)) {
    drawer.value = current.describe(hit.key)
    drawerOpen.value = true
    traceActive.value = false
  }
  query.value = ''
  hits.value = []
  clusterLabels.value = current.clusterLabelState()
  startLoop()
}

/* ---------------- Trace ---------------- */

function toggleTrace(): void {
  const current = engine.value
  if (!current) {
    return
  }
  if (traceActive.value || !traceKey.value) {
    traceActive.value = false
    traceKey.value = ''
    current.setTrace(null)
  } else {
    current.setTrace(traceKey.value)
    traceActive.value = true
    drawerOpen.value = false
  }
  clusterLabels.value = current.clusterLabelState()
  startLoop()
}

function handleTraceChange(): void {
  const current = engine.value
  if (!current) {
    return
  }
  if (!traceKey.value) {
    traceActive.value = false
    current.setTrace(null)
  } else {
    current.setTrace(traceKey.value)
    traceActive.value = true
    drawerOpen.value = false
  }
  clusterLabels.value = current.clusterLabelState()
  startLoop()
}

/* ---------------- 抽屉动作 ---------------- */

function closeDrawer(): void {
  drawerOpen.value = false
  engine.value?.clearSelection()
  clusterLabels.value = engine.value?.clusterLabelState() ?? []
  startLoop()
}

/**
 * 打开源码：把源码引用抛给上层。
 *
 * 这里不自己弹窗 —— 上层（知识面板）才知道怎么把它变成一次跳转。
 */
function handleOpenSource(): void {
  if (drawer.value?.sourceRef) {
    emit('open-source', drawer.value.sourceRef)
  }
}

/** 让 AI 解释：交给上层的问答面板，不在这里直接调模型。 */
function handleExplain(): void {
  if (drawer.value) {
    emit('explain', { name: drawer.value.name, summary: drawer.value.summary })
  }
}

/* ---------------- 生命周期 ---------------- */

function handleKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchRef.value?.focus()
    return
  }
  if (event.key === 'Escape') {
    hits.value = []
    if (drawerOpen.value) {
      closeDrawer()
    }
  }
}

function handleVisibility(): void {
  if (document.hidden) {
    stopLoop()
  } else {
    startLoop()
  }
}

function mountGraph(): void {
  if (!canvasRef.value || !shellRef.value) {
    return
  }
  const instance = new KnowledgeGraphEngine(canvasRef.value, shellRef.value)
  instance.onNeedsFrame = requestDraw
  instance.load(props.nodes, props.edges)
  instance.resize()
  instance.fit()
  engine.value = instance

  nodeCount.value = instance.nodeCount()
  visibleCount.value = instance.stats().visible
  tracePaths.value = instance.listTracePaths()
  clusterLabels.value = instance.clusterLabelState()
  startLoop()
}

const themeObserver = new MutationObserver(() => { startLoop() })
onMounted(() => {
  themeObserver.observe(document.documentElement, { attributes:true, attributeFilter:['data-theme'] })
  mountGraph()
  if (shellRef.value) {
    resizeObserver = new ResizeObserver(() => handleResize())
    resizeObserver.observe(shellRef.value)
  }
  document.addEventListener('visibilitychange', handleVisibility)
  window.addEventListener('keydown', handleKeydown)
})

/**
 * 数据是异步到的：挂载时通常还没拿到节点。
 *
 * 空数据时模板不渲染 canvas，`mountGraph()` 就无从初始化 ——
 * 所以这里必须等数据到了再初始化一次，否则会一直停在空状态。
 */
watch(
  () => props.nodes,
  async (nodes) => {
    if (nodes.length === 0) {
      return
    }
    await nextTick()
    const current = engine.value
    if (!current) {
      mountGraph()
      if (shellRef.value && !resizeObserver) {
        resizeObserver = new ResizeObserver(() => handleResize())
        resizeObserver.observe(shellRef.value)
      }
      return
    }
    // 图数据变了（例如重新生成知识）：整张图重载
    current.load(props.nodes, props.edges)
    current.resize()
    current.fit()
    nodeCount.value = current.nodeCount()
    tracePaths.value = current.listTracePaths()
    clusterLabels.value = current.clusterLabelState()
    startLoop()
  },
)

const noData = computed(() => props.nodes.length === 0)

onBeforeUnmount(() => {
  themeObserver.disconnect()
  stopLoop()
  resizeObserver?.disconnect()
  resizeObserver = null
  document.removeEventListener('visibilitychange', handleVisibility)
  window.removeEventListener('keydown', handleKeydown)
  engine.value = null
})
</script>

<style scoped>
.graph-root {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
  /* 让下面的 @container 断点按本面板宽度判断，而不是视口 */
  container-type: inline-size;
}

.graph-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  gap: 6px;
  color: var(--wb-text);
  font-size: 13px;
}

.graph-empty-hint {
  font-size: 12px;
  color: var(--wb-text);
}

/*
 * 深色画布与 V6 原型一致：图谱区自成一块视觉区域，
 * 不跟随平台主题 —— 点阵底纹与发光节点在浅色底上看不清。
 */
.graph-shell {
  position: absolute;
  inset: 0;
  overflow: hidden;
  contain: strict;
  overscroll-behavior: none;
  background: var(--wb-bg);
  border-radius: 8px;
}

.graph-shell::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  opacity: 0.035;
  background-image: radial-gradient(var(--wb-line) 0.42px, transparent 0.58px);
  background-size: 32px 32px;
}

.graph-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  cursor: grab;
  z-index: 1;
  touch-action: none;
  background: transparent;
}

.graph-canvas.is-dragging {
  cursor: grabbing;
}

.cluster-labels {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 3;
}

.cluster-label {
  position: absolute;
  left: 0;
  top: 0;
  color: rgba(228, 230, 238, 0.06);
  font-size: 22px;
  letter-spacing: 0.17em;
  font-weight: 620;
  white-space: nowrap;
  will-change: transform;
}

.graph-toolbar {
  position: absolute;
  left: 12px;
  top: 12px;
  display: flex;
  gap: 6px;
  z-index: 8;
}

.segmented {
  display: flex;
  padding: 3px;
  background: var(--wb-surface);
  border: 1px solid var(--wb-line);
  border-radius: 9px;
}

.segmented button {
  border: 0;
  background: transparent;
  color: var(--wb-muted);
  height: 27px;
  padding: 0 10px;
  border-radius: 7px;
  cursor: pointer;
  font-size: 12px;
}

.segmented button.active {
  background: var(--wb-line);
  color: var(--wb-text);
}

.toolbox {
  display: flex;
  gap: 2px;
  padding: 3px;
  background: var(--wb-surface);
  border: 1px solid var(--wb-line);
  border-radius: 10px;
}

.toolbox button {
  width: 27px;
  height: 27px;
  border: 0;
  background: transparent;
  color: var(--wb-muted);
  border-radius: 7px;
  cursor: pointer;
}

.toolbox button:hover {
  background: var(--wb-line);
  color: var(--wb-text);
}

.graph-search {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 9;
  width: min(340px, 42vw);
}

.searchbox {
  height: 34px;
  background: var(--wb-surface);
  border: 1px solid var(--wb-line);
  border-radius: 9px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 10px;
  color: var(--wb-muted);
}

.searchbox input {
  flex: 1;
  min-width: 0;
  background: none;
  border: 0;
  outline: 0;
  color: var(--wb-text);
  font-size: 12px;
}

.searchbox kbd {
  font-size: 10px;
  color: var(--wb-muted);
  border: 1px solid var(--wb-line);
  border-radius: 5px;
  padding: 1px 5px;
}

.search-results {
  margin-top: 6px;
  border: 1px solid var(--wb-line);
  background: var(--wb-surface);
  border-radius: 9px;
  overflow: hidden;
}

.search-result {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 8px 11px;
  cursor: pointer;
  color: var(--wb-text);
  font-size: 12px;
}

.search-result:hover {
  background: var(--wb-line);
}

.search-result small {
  color: var(--wb-muted);
}

.trace-panel {
  position: absolute;
  right: 12px;
  top: 12px;
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 6px;
  border: 1px solid var(--wb-line);
  background: var(--wb-surface);
  border-radius: 9px;
}

.trace-select {
  height: 28px;
  max-width: 140px;
  border: 0;
  background: transparent;
  color: var(--wb-text);
  outline: 0;
  font-size: 12px;
}

.mini-btn {
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--wb-line);
  background: transparent;
  border-radius: 7px;
  color: var(--wb-text);
  cursor: pointer;
  font-size: 12px;
}

.mini-btn.active {
  background: rgba(143, 147, 255, 0.13);
  border-color: rgba(143, 147, 255, 0.22);
  color: var(--wb-text);
}

.trace-note {
  font-size: 10px;
  color: var(--wb-muted);
  padding-right: 4px;
}

.graph-hud {
  position: absolute;
  left: 14px;
  bottom: 14px;
  z-index: 8;
  padding: 7px 10px;
  border: 1px solid var(--wb-line);
  background: var(--wb-surface);
  border-radius: 9px;
  color: var(--wb-muted);
  font-size: 10px;
}

.graph-hud b {
  color: var(--wb-text);
  font-weight: 560;
}

.pulse-live {
  display: inline-block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #8f93ff;
  margin: 0 6px 1px 1px;
}

.drawer {
  position: absolute;
  right: 0;
  top: 0;
  bottom: 0;
  width: 340px;
  max-width: 88%;
  background: var(--wb-surface);
  border-left: 1px solid var(--wb-line);
  z-index: 12;
  transform: translateX(100%);
  transition: transform 0.24s cubic-bezier(0.2, 0.75, 0.2, 1);
  display: flex;
  flex-direction: column;
}

.drawer.open {
  transform: translateX(0);
}

.drawer-head {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 16px;
  border-bottom: 1px solid var(--wb-line);
}

.drawer-head h2 {
  margin: 3px 0 0;
  font-size: 18px;
  color: var(--wb-text);
  letter-spacing: -0.03em;
  word-break: break-all;
}

.eyebrow {
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-size: 10px;
  color: var(--wb-muted);
  font-weight: 650;
}

.icon-btn {
  width: 30px;
  height: 30px;
  border: 1px solid var(--wb-line);
  background: transparent;
  border-radius: 8px;
  color: var(--wb-text);
  cursor: pointer;
}

.drawer-body {
  padding: 16px;
  overflow: auto;
  color: var(--wb-text);
}

.drawer-path {
  font-family: Consolas, 'Courier New', monospace;
  font-size: 11px;
  color: var(--wb-muted);
  margin-bottom: 16px;
  word-break: break-all;
}

.stat-strip {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  border-top: 1px solid var(--wb-line);
  border-bottom: 1px solid var(--wb-line);
  margin-bottom: 14px;
}

.dstat {
  padding: 12px 6px;
}

.dstat + .dstat {
  border-left: 1px solid var(--wb-line);
}

.dstat b {
  display: block;
  font-size: 17px;
  color: var(--wb-text);
}

.dstat small {
  color: var(--wb-muted);
  font-size: 11px;
}

.evidence-line {
  margin-bottom: 8px;
}

.kn-evidence {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 4px;
  color: #a9b4ff;
  background: rgba(143, 147, 255, 0.13);
  border: 1px solid rgba(143, 147, 255, 0.22);
}

.kn-evidence.is-inferred {
  color: var(--wb-muted);
  background: var(--wb-line);
  border-color: var(--wb-line);
}

.drawer-summary {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--wb-text);
}

.detail-group {
  margin-top: 14px;
}

.detail-group .eyebrow {
  margin-bottom: 6px;
}

.chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  padding: 4px 8px;
  border-radius: 7px;
  border: 1px solid var(--wb-line);
  background: var(--wb-line);
  font-size: 11px;
  color: var(--wb-text);
}

.drawer-actions {
  display: grid;
  gap: 8px;
  margin-top: 20px;
}

.drawer-actions button {
  text-align: left;
  height: 36px;
  padding: 0 11px;
  border: 1px solid var(--wb-line);
  background: var(--wb-line);
  border-radius: 9px;
  color: var(--wb-text);
  cursor: pointer;
  font-size: 12px;
}

.drawer-actions button:hover:not(:disabled) {
  background: var(--wb-line);
}

.drawer-actions button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 移动端：抽屉改底部弹层，工具栏让位 */
/*
 * 断点用**容器宽度**而不是视口宽度。
 *
 * 图谱活在右侧面板里：视口 1400px 时面板也可能只有 300 多像素，
 * 按视口判断会让工具栏、搜索框、抽屉挤成一团（实测就是这样）。
 */
@container (max-width: 560px) {
  .graph-search {
    top: 50px;
    left: 10px;
    right: 10px;
    width: auto;
    transform: none;
  }

  .trace-panel {
    top: auto;
    bottom: 10px;
    right: 10px;
    left: 10px;
    justify-content: space-between;
  }

  .graph-hud {
    display: none;
  }

  /* 面板本来就窄，抽屉铺满而不是挤掉一半 */
  .drawer {
    width: auto;
    max-width: none;
    border-left: 0;
  }

  .cluster-label {
    font-size: 18px;
  }
}

/* 减少动态效果：关掉过渡，动画本身由引擎的 heat 衰减自然停下 */
@media (prefers-reduced-motion: reduce) {
  .drawer,
  .cluster-label,
  .segmented button,
  .toolbox button {
    transition: none !important;
  }
}

.graph-shell {
  border-radius: 0;
}
.graph-empty {
  background: radial-gradient(ellipse at center, #15162466, transparent 65%), var(--wb-bg);
  color: var(--wb-text);
  min-height: 380px;
}
.graph-empty::before {
  content: '◎';
  color: #8f93ff66;
  font-size: 52px;
  font-weight: 200;
  margin-bottom: 10px;
}
.graph-empty-hint {
  color: var(--wb-muted);
}
.search-result {
  width: 100%;
  text-align: left;
  background: transparent;
  border: 0;
}
.drawer.open {
  box-shadow: -20px 0 60px #0006;
}
@container (max-width:1050px) {
  .graph-search {
    top: 60px;
    left: auto;
    right: 14px;
    width: min(300px, calc(100% - 28px));
    transform: none;
  }
  .trace-panel {
    top: auto;
    bottom: 14px;
    max-width: calc(100% - 28px);
  }
  .graph-hud {
    max-width: calc(100% - 28px);
    bottom: 60px;
  }
}
@container (max-width:560px) {
  .graph-search {
    left: 10px;
    right: 10px;
    width: auto;
    top: 56px;
  }
  .drawer {
    width: 100%;
    height: 100%;
    max-width: 100%;
  }
}
</style>
