<template>
  <section class="knowledge-panel cosmic-workbench" aria-label="项目知识看板">
    <header class="kn-header">
      <div>
        <div class="wb-eyebrow">Code intelligence</div>
        <h2 class="wb-heading">项目知识</h2>
        <div class="wb-subline">
          <i class="wb-dot" :class="{ 'is-live': status?.hasKnowledge && !status?.outdated }" />
          <span>{{ loading && !status ? '正在读取知识…' : statusText }}</span>
          <span v-if="status?.hasKnowledge"
            >· {{ status.nodeCount || 0 }} 节点 · {{ status.edgeCount || 0 }} 关系</span
          >
        </div>
      </div>
      <div class="wb-actions">
        <button
          v-if="canSubmit"
          type="button"
          class="wb-button is-primary"
          :disabled="submitting || loading"
          @click="handleSubmit"
        >
          {{ submitting ? '正在提交…' : status?.hasKnowledge ? '更新知识' : '生成知识' }}
        </button>
        <button v-if="canCancel" type="button" class="wb-button" @click="handleCancel">
          取消任务
        </button>
        <button v-if="status?.retryable" type="button" class="wb-button" @click="handleRetry">
          重试
        </button>
        <button type="button" class="wb-button" :disabled="loading" @click="refreshAll">
          {{ loading ? '读取中…' : '刷新' }}
        </button>
      </div>
    </header>
    <div v-if="loadError" class="kn-alert" role="alert">{{ loadError }}</div>
    <div v-if="status?.outdated" class="kn-alert">
      知识待更新
    </div>
    <div v-if="taskRunning" class="kn-progress" role="status">
      <span>{{ status?.phase || '正在分析源码' }}</span
      ><span>{{ status?.progress || 0 }}%</span>
      <progress :value="status?.progress || 0" max="100" aria-label="知识生成进度" />
    </div>
    <div v-if="taskFailed" class="kn-alert" role="alert">
      {{ taskFailedText }}<span v-if="status?.retryable">，可点击重试。</span>
    </div>
    <div class="kn-navigation">
      <nav class="wb-segments" aria-label="知识视图">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ 'is-active': activeTab === tab.key }"
          :aria-pressed="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
        </button>
      </nav>
      <span class="kn-version">{{
        activeTab === 'graph'
          ? '源码关系'
          : activeTab === 'docs'
            ? `${docs.length} 篇项目文档`
            : '只读问答'
      }}</span>
    </div>
    <div class="kn-body">
      <div v-if="activeTab === 'graph'" class="kn-graph-wrap">
        <KnowledgeGraph
          :nodes="graph.nodes"
          :edges="graph.edges"
          @open-source="handleOpenSource"
          @explain="handleExplainNode"
        />
      </div>
      <template v-else-if="activeTab === 'docs'">
        <div v-if="!docs.length" class="wb-empty">
          <strong>{{ loading ? '正在读取文档' : '还没有项目文档' }}</strong
          ><span>点击「生成知识」开始</span>
        </div>
        <div v-else class="kn-doc-layout">
          <nav class="kn-doc-index" aria-label="文档目录">

            <button
              v-for="(doc, index) in docs"
              :key="doc.id"
              type="button"
              :class="{ 'is-active': selectedDoc?.id === doc.id }"
              :aria-pressed="selectedDoc?.id === doc.id"
              @click="selectedDocId = doc.id"
            >
              <span>{{ String(index + 1).padStart(2, '0') }}</span
              >{{ doc.title || '未命名文档' }}
            </button>
          </nav>
          <article v-if="selectedDoc" class="kn-doc">

            <h3 v-if="!/^#{1,6}\s/.test((selectedDoc.content || '').trimStart())">{{ selectedDoc.title }}</h3>
            <div class="wb-markdown" v-html="renderSafeMarkdown(selectedDoc.content || '')" />
          </article>
        </div>
      </template>
      <div v-else class="kn-qa">
        <div class="kn-qa-history" aria-live="polite">
          <div v-if="qaHistory.length === 0" class="wb-empty">
            <span class="kn-spark">✦</span><strong>询问项目</strong
            ><span>基于项目源码回答</span>
            <div class="kn-suggestions">
              <button type="button" class="wb-button" @click="question = '这个项目的入口在哪？'">
                项目从哪里开始？</button
              ><button
                type="button"
                class="wb-button"
                @click="question = '请解释项目的模块与依赖关系。'"
              >
                模块如何协作？
              </button>
            </div>
          </div>
          <article
            v-for="item in qaHistory"
            :key="item.id"
            class="kn-qa-item"
            :class="{ 'is-user': item.role === 'user' }"
          >
            <div class="wb-eyebrow">
              {{ item.role === 'user' ? 'You / 你的问题' : 'Code assistant / 源码解读' }}
            </div>
            <div
              v-if="item.role === 'assistant'"
              class="wb-markdown"
              v-html="renderSafeMarkdown(item.content || '')"
            />
            <p v-else>{{ item.content }}</p>
            <div v-if="item.role === 'assistant'" class="kn-version">
              {{
                item.stale
                  ? '资料可能已过期'
                  : item.snapshotVersion
                    ? `基于版本 ${item.snapshotVersion}`
                    : ''
              }}
            </div>
          </article>
          <div v-if="asking" class="wb-subline" role="status">正在阅读源码，整理回答…</div>
        </div>
        <form class="kn-qa-input" @submit.prevent="handleAsk">
          <textarea
            v-model="question"
            rows="2"
            aria-label="知识问题"
            placeholder="询问这个项目的架构、实现或依赖关系…"
            :disabled="asking"
            @keydown="handleQuestionKeydown"
          />
          <div class="kn-composer-footer">
            <span>Ctrl / ⌘ + Enter 发送</span
            ><button
              type="submit"
              class="wb-button is-primary"
              :disabled="asking || !question.trim()"
            >
              {{ asking ? '分析中…' : '发送问题 ↗' }}
            </button>
          </div>
        </form>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
// 项目没有全局注册 ant-design-vue 组件（main.ts 只 use(pinia/router)），
// 模板里的 <a-xxx> 必须靠这里的命名 import 才能解析
import { message } from 'ant-design-vue'
import KnowledgeGraph from './KnowledgeGraph.vue'
import './workbench.css'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'
import {
  askKnowledge,
  cancelKnowledgeTask,
  loadKnowledgeDocs,
  loadKnowledgeGraph,
  loadKnowledgeQaHistory,
  loadKnowledgeStatus,
  retryKnowledgeTask,
  submitKnowledgeTask,
  type GraphNode,
  type KnowledgeDoc,
  type KnowledgeGraph as KnowledgeGraphData,
  type KnowledgeQaMessage,
  type KnowledgeStatus,
} from '@/utils/appKnowledgeApi'

const props = withDefaults(
  defineProps<{
    appId: string
    apiBaseUrl?: string
  }>(),
  { apiBaseUrl: '/api' },
)

const emit = defineEmits<{
  /** 图谱里点「打开源码」时把引用抛给上层（由它决定跳去哪） */
  (e: 'open-source', sourceRef: string): void
}>()

const status = ref<KnowledgeStatus | null>(null)
const graph = ref<KnowledgeGraphData>({ nodes: [], edges: [] })
const docs = ref<KnowledgeDoc[]>([])
const qaHistory = ref<KnowledgeQaMessage[]>([])
const question = ref('')
const loading = ref(false)
const submitting = ref(false)
const asking = ref(false)
const activeTab = ref<'docs' | 'graph' | 'qa'>('graph')

let pollTimer: number | null = null

const tabs = [
  { key: 'docs' as const, label: '文档' },
  { key: 'graph' as const, label: '图谱' },
  { key: 'qa' as const, label: '问答' },
]

const selectedDocId = ref<number | null>(null)
const selectedDoc = computed(
  () => docs.value.find((doc) => doc.id === selectedDocId.value) ?? docs.value[0],
)
const loadError = ref('')

const opts = () => ({ appId: props.appId, apiBaseUrl: props.apiBaseUrl })

const taskRunning = computed(
  () => status.value?.taskStatus === 'RUNNING' || status.value?.taskStatus === 'PENDING',
)
const canCancel = computed(() => taskRunning.value)
const canSubmit = computed(() => !taskRunning.value)

const taskFailed = computed(() =>
  ['FAILED', 'INTERRUPTED', 'CANCELLED'].includes(status.value?.taskStatus ?? ''),
)

const taskFailedText = computed(() => {
  const map: Record<string, string> = {
    FAILED: '知识生成失败',
    INTERRUPTED: '平台重启导致任务中断',
    CANCELLED: '任务已取消',
  }
  const head = map[status.value?.taskStatus ?? ''] ?? '任务未完成'
  return status.value?.errorMessage ? `${head}：${status.value.errorMessage}` : head
})

const statusText = computed(() => {
  if (taskRunning.value) return '生成中'
  if (!status.value?.hasKnowledge) return '尚未生成'
  if (status.value?.outdated) return '待更新'
  return '已是当前版本'
})

const statusClass = computed(() => ({
  'is-running': taskRunning.value,
  'is-outdated': !taskRunning.value && status.value?.outdated === true,
  'is-fresh': !taskRunning.value && status.value?.hasKnowledge === true && !status.value?.outdated,
}))

/* ---------- 图谱交互 ---------- */

/** 打开源码：交给上层跳到项目文件窗口，这层不知道怎么定位文件。 */
function handleOpenSource(sourceRef: string): void {
  emit('open-source', sourceRef)
}

/** 让 AI 解释：切到问答页并预填问题，不在这里直接调模型。 */
function handleExplainNode(node: { name: string }): void {
  activeTab.value = 'qa'
  question.value = `请解释 ${node.name} 的职责，以及它在项目里被谁使用、依赖了什么。`
}

/* ---------- 数据加载 ---------- */

async function refreshAll() {
  if (!props.appId) return
  loading.value = true
  loadError.value = ''
  try {
    const [statusData, graphData, docsData, qaData] = await Promise.all([
      loadKnowledgeStatus(opts()),
      loadKnowledgeGraph(opts()),
      loadKnowledgeDocs(opts()),
      loadKnowledgeQaHistory(opts()),
    ])
    status.value = statusData
    graph.value = graphData
    docs.value = docsData
    qaHistory.value = qaData
  } catch (e) {
    loadError.value = (e as Error).message || '读取知识失败'
  } finally {
    loading.value = false
    syncPolling()
  }
}

/** 只在任务进行中轮询：闲着的时候不必反复打接口。 */
function syncPolling() {
  if (taskRunning.value) {
    if (pollTimer == null) {
      pollTimer = window.setInterval(() => void refreshAll(), 4000)
    }
  } else if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
}

async function handleSubmit() {
  if (!props.appId || submitting.value) return
  submitting.value = true
  try {
    await submitKnowledgeTask(opts())
    message.success('已开始生成项目知识，可以继续做别的事')
    await refreshAll()
  } catch (e) {
    message.error((e as Error).message || '提交失败')
  } finally {
    submitting.value = false
  }
}

async function handleCancel() {
  const taskId = status.value?.taskId
  if (!taskId) return
  try {
    await cancelKnowledgeTask({ ...opts(), taskId })
    message.success('已取消')
    await refreshAll()
  } catch (e) {
    message.error((e as Error).message || '取消失败')
  }
}

async function handleRetry() {
  const taskId = status.value?.taskId
  if (!taskId) return
  try {
    await retryKnowledgeTask({ ...opts(), taskId })
    message.success('已重新开始')
    await refreshAll()
  } catch (e) {
    message.error((e as Error).message || '重试失败')
  }
}

async function handleAsk() {
  const text = question.value.trim()
  if (!text || asking.value) return
  asking.value = true
  try {
    await askKnowledge({ ...opts(), question: text })
    question.value = ''
    qaHistory.value = await loadKnowledgeQaHistory(opts())
  } catch (e) {
    message.error((e as Error).message || '提问失败')
  } finally {
    asking.value = false
  }
}

function handleQuestionKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    void handleAsk()
  }
}

onMounted(() => void refreshAll())

watch(
  () => props.appId,
  () => {
    status.value = null
    graph.value = { nodes: [], edges: [] }
    docs.value = []
    qaHistory.value = []
    selectedDocId.value = null
    void refreshAll()
  },
)

onBeforeUnmount(() => {
  if (pollTimer != null) {
    window.clearInterval(pollTimer)
    pollTimer = null
  }
})
</script>

<style scoped>
.knowledge-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  container-type: inline-size;
}
.kn-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  padding: 10px 16px;
  flex-wrap: wrap;
}
.kn-header .wb-actions {
  padding-top: 5px;
}
.kn-navigation {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 0 16px 8px;
  border-bottom: 1px solid var(--wb-line);
  flex-wrap: wrap;
}
.kn-version {
  color: var(--wb-muted);
  font:
    10px/1.6 ui-monospace,
    Consolas,
    monospace;
  overflow-wrap: anywhere;
}
.kn-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
}
.kn-graph-wrap {
  flex: 1;
  min-height: 180px;
}
.kn-alert {
  margin: 0 24px 14px;
  padding: 10px 12px;
  border: 1px solid #d5b87630;
  border-radius: 8px;
  color: var(--wb-warning);
  background: #d5b87608;
  font-size: 12px;
}
.kn-progress {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: 6px;
  margin: 0 24px 14px;
  color: var(--wb-muted);
  font-size: 12px;
}
.kn-progress progress {
  width: 100%;
  height: 4px;
  accent-color: var(--wb-accent);
}
.kn-doc-layout {
  display: grid;
  grid-template-columns: 160px minmax(0, 1fr);
  min-height: 100%;
}
.kn-doc-index {
  padding: 24px 12px;
  border-right: 1px solid var(--wb-line);
}
.kn-doc-index .wb-eyebrow {
  padding: 0 10px 14px;
}
.kn-doc-index button {
  width: 100%;
  display: flex;
  gap: 9px;
  text-align: left;
  padding: 10px;
  margin-bottom: 3px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
  font-size: 12px;
}
.kn-doc-index button span {
  color: var(--wb-muted);
  font:
    10px/1.8 Consolas,
    monospace;
}
.kn-doc-index button.is-active {
  color: var(--wb-text);
  background: #8f93ff13;
}
.kn-doc {
  min-width: 0;
  padding: 26px 28px;
}
.kn-doc h3 {
  font-size: 21px;
  font-weight: 600;
  margin: 8px 0 24px;
  color: var(--wb-text);
}
.kn-qa {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 0 24px 20px;
}
.kn-qa-history {
  overflow: auto;
  flex: 1;
  padding: 20px 0;
  min-height: 220px;
}
.kn-qa-history .wb-empty {
  min-height: 280px;
  max-width: 460px;
  margin: auto;
}
.kn-spark {
  color: #a4a7ff;
  font-size: 26px;
  margin-bottom: 12px;
}
.kn-suggestions {
  display: flex;
  justify-content: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 16px;
}
.kn-qa-item {
  padding: 18px 0;
  border-bottom: 1px solid var(--wb-line);
}
.kn-qa-item.is-user {
  border: 1px solid var(--wb-line);
  background: var(--wb-hover);
  padding: 16px;
  border-radius: 10px;
  margin-bottom: 10px;
}
.kn-qa-item p {
  margin: 8px 0 0;
  white-space: pre-wrap;
}
.kn-qa-input {
  border: 1px solid var(--wb-line);
  border-radius: 12px;
  padding: 12px;
  background: var(--wb-surface);
}
.kn-qa-input:focus-within {
  border-color: #8f93ff66;
}
.kn-qa-input textarea {
  width: 100%;
  resize: vertical;
  min-height: 60px;
  max-height: 180px;
  background: transparent;
  border: 0;
  outline: none;
  color: var(--wb-text);
  line-height: 1.7;
}
.kn-composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.kn-composer-footer span {
  font-size: 10px;
  color: var(--wb-muted);
}
@container (max-width:540px) {
  .kn-header {
    padding: 10px 16px;
  }
  .kn-navigation {
    padding: 0 16px 12px;
  }
  .kn-doc-layout {
    grid-template-columns: 1fr;
  }
  .kn-doc-index {
    display: flex;
    overflow: auto;
    padding: 12px;
    border-right: 0;
    border-bottom: 1px solid var(--wb-line);
  }
  .kn-doc-index .wb-eyebrow {
    display: none;
  }
  .kn-doc-index button {
    width: auto;
    white-space: nowrap;
  }
  .kn-doc {
    padding: 20px 16px;
  }
  .kn-qa {
    padding: 0 16px 16px;
  }
  .kn-composer-footer span {
    max-width: 120px;
  }
  .kn-navigation > .kn-version {
    display: none;
  }
}


.kn-header .wb-eyebrow { display:none; }
.kn-header .wb-heading { font-size:16px; margin:0 0 3px; }
</style>
