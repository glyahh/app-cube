<template>
  <section class="runtime-logs cosmic-workbench" aria-label="运行日志看板">
    <header class="log-header">
      <div>
        <div class="wb-eyebrow">Runtime / Console</div>
        <h2 class="wb-heading">运行日志</h2>
        <div class="wb-subline">
          <i class="wb-dot" :class="{ 'is-live': running }" />{{
            running ? '正在接收输出' : '应用未运行'
          }}<span v-if="runNo">· 第 {{ runNo }} 次运行</span>
        </div>
      </div>
      <div class="wb-actions">
        <button type="button" class="wb-button" :disabled="loading" @click="refresh">
          {{ loading ? '读取中…' : '刷新' }}</button
        ><button type="button" class="wb-button" :aria-pressed="paused" @click="togglePause">
          {{ paused ? '继续滚动' : '暂停滚动' }}</button
        ><button type="button" class="wb-button" :disabled="!content" @click="handleDownload">
          下载 ↗
        </button>
      </div>
    </header>
    <div class="console-shell">
      <div class="console-bar">
        <span class="console-label"><i /><i /><i /><span>stdout / stderr</span></span
        ><input v-model="search" type="search" aria-label="筛选日志" placeholder="筛选日志…" />
      </div>
      <div v-if="loadError" class="log-error" role="alert">{{ loadError }}</div>
      <pre ref="logRef" class="log-body" tabindex="0" aria-label="日志内容">{{
        content ? visibleContent || '没有匹配的日志' : emptyHint
      }}</pre>
      <footer class="console-footer">
        <span>{{ truncated ? '仅显示日志末尾部分' : '受管进程输出' }}</span
        ><span
          >{{ search ? `${lines.length} 行匹配 · ` : ''
          }}{{ paused ? '滚动已暂停' : '自动跟随' }}</span
        >
      </footer>
    </div>
    <p class="log-note">保留原始进程前缀与错误堆栈。下载包含完整已加载内容，不受筛选条件影响。</p>
  </section>
</template>

<script setup lang="ts">
import './workbench.css'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadRuntimeLogs } from '@/utils/appRuntimeApi'

const props = withDefaults(
  defineProps<{
    appId: string
    apiBaseUrl?: string
    running?: boolean
  }>(),
  { apiBaseUrl: '/api', running: false },
)

const content = ref('')
const search = ref('')
const loadError = ref('')
const lines = computed(() =>
  content.value
    .split('\n')
    .filter((line) => !search.value || line.toLowerCase().includes(search.value.toLowerCase())),
)
const visibleContent = computed(() => lines.value.join('\n'))
const runNo = ref(0)
const truncated = ref(false)
const loading = ref(false)
/** 暂停滚动：日志刷得快时用户要能停下来读 */
const paused = ref(false)
const logRef = ref<HTMLElement | null>(null)

let timer: number | null = null

const emptyHint = computed(() => (props.running ? '（暂无输出）' : '（应用未在运行）'))

async function refresh() {
  if (!props.appId) return
  if (loading.value) return
  loading.value = true
  loadError.value = ''
  try {
    const data = await loadRuntimeLogs({ appId: props.appId, apiBaseUrl: props.apiBaseUrl })
    content.value = data.content ?? ''
    runNo.value = data.runNo ?? 0
    truncated.value = data.truncated === true
    if (!paused.value) {
      await nextTick()
      const el = logRef.value
      if (el) {
        el.scrollTop = el.scrollHeight
      }
    }
  } catch {
    loadError.value = '日志读取失败，保留上次内容。请重试。'
  } finally {
    loading.value = false
  }
}

function togglePause() {
  paused.value = !paused.value
  if (!paused.value) {
    void refresh()
  }
}

function handleDownload() {
  // 诊断包内容只含运行日志与环境信息，不含平台凭据
  const header = `# 应用 ${props.appId} 运行日志（第 ${runNo.value} 次运行）\n`
  const blob = new Blob([header + content.value], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `runtime-log-${props.appId}-run${runNo.value}.txt`
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

function startPolling() {
  stopPolling()
  void refresh()
  timer = window.setInterval(() => void refresh(), 3000)
}

function stopPolling() {
  if (timer != null) {
    window.clearInterval(timer)
    timer = null
  }
}

onMounted(() => {
  if (props.running) startPolling()
  else void refresh()
})

watch(
  () => props.running,
  (running) => {
    if (running) {
      startPolling()
    } else {
      stopPolling()
      // 停止后再刷一次，把退出前的最后几行取回来
      void refresh()
    }
  },
)

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.runtime-logs {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 24px 16px;
  container-type: inline-size;
}
.log-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 24px;
}
.log-header .wb-actions {
  padding-top: 3px;
}
.console-shell {
  flex: 1;
  min-height: 240px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wb-line);
  border-radius: 12px;
  overflow: hidden;
  background: var(--wb-bg);
}
.console-bar {
  min-height: 43px;
  padding: 8px 14px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  border-bottom: 1px solid var(--wb-line);
  background: var(--wb-surface);
}
.console-label {
  display: flex;
  align-items: center;
  gap: 5px;
  color: var(--wb-muted);
  font:
    11px Consolas,
    monospace;
}
.console-label i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #3a3b42;
}
.console-label span {
  margin-left: 8px;
}
.console-bar input {
  min-width: 100px;
  width: 160px;
  border: 0;
  background: transparent;
  color: var(--wb-text);
  outline-offset: 2px;
  font-size: 11px;
}
.log-body {
  flex: 1;
  min-height: 0;
  margin: 0;
  padding: 20px 16px;
  overflow: auto;
  color: var(--wb-text);
  font:
    12px/1.9 Consolas,
    'Courier New',
    monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  tab-size: 2;
}
.console-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 9px 14px;
  border-top: 1px solid var(--wb-line);
  font:
    10px/1.5 Consolas,
    monospace;
  color: var(--wb-muted);
}
.log-note {
  font-size: 10px;
  color: var(--wb-muted);
  margin: 12px 0 0;
}
.log-error {
  padding: 8px 14px;
  color: var(--wb-warning);
  font-size: 12px;
}
</style>
