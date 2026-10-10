<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Alert, Button, Input, ConfigProvider } from 'ant-design-vue'
import AgentComposerControls from '@/features/project-import/agent-permissions/AgentComposerControls.vue'
import AgentPermissionCard from '@/features/project-import/agent-permissions/AgentPermissionCard.vue'
import AgentChangeReview from '@/features/project-import/agent-permissions/AgentChangeReview.vue'
import type {
  AgentChangeCandidate,
  AgentComposerSettings,
  AgentFileDiff,
  AgentModelOption,
  AgentPermissionRequest,
  AgentReviewDecisionEvent,
  AgentReviewFileEvent,
  AgentToolDecisionEvent,
} from '@/features/project-import/agent-permissions/types'
type Phase = 'IDLE' | 'RUNNING' | 'ASK' | 'REVIEW' | 'MERGED' | 'STOPPED' | 'FAILED'
const props = withDefaults(
  defineProps<{
    saveOutcome?: 'success' | 'failure' | 'conflict'
    decisionOutcome?: 'success' | 'expired' | 'failure'
    publicationOutcome?: 'success' | 'failure'
    highRisk?: boolean
    forbidden?: boolean
    diffText?: string
  }>(),
  { highRisk: true },
)

// 模型与能力仅供页面演示，不读取真实配置，也不作为正式请求参数。
const modelOptions: AgentModelOption[] = [
  { id: 'gpt-5.2', name: 'GPT-5.2', provider: 'OpenAI', efforts: ['low', 'medium', 'high'] },
  {
    id: 'claude-sonnet-4-5',
    name: 'Claude Sonnet 4.5',
    provider: 'Anthropic',
    efforts: ['low', 'medium', 'high'],
  },
  { id: 'deepseek-chat', name: 'DeepSeek Chat', provider: 'DeepSeek', efforts: [] },
]
const settings = ref<AgentComposerSettings>({
  mode: 'MANUAL',
  modelId: 'gpt-5.2',
  effort: 'medium',
})
const mode = computed(() => settings.value.mode)
const round = ref(0)
const reviewDiffs = ref<Record<string, AgentFileDiff>>({})
const phase = ref<Phase>('IDLE')
const saving = ref(false)
const deciding = ref(false)
const error = ref('')
const input = ref('修复任务列表的空状态，并更新使用说明。')
const sent = ref('')
const step = ref(0)
const decisions = ref<string[]>([])
const sessionApprovals = ref(new Set<string>())
const diff = computed(
  () => props.diffText ?? '- <p>暂无内容</p>\n+ <p>还没有任务，创建一个试试。</p>',
)
const steps = computed(() => [
  {
    object: 'src/components/TaskList.vue',
    action: '修改文件',
    high: false,
    reason: '小范围业务源码修改',
    preview: diff.value,
  },
  ...(props.highRisk
    ? [
        {
          object: 'README.md',
          action: '修改文件',
          high: true,
          reason: '说明文件属于高风险修改',
          preview: '- 点击按钮添加\n+ 点击“新建任务”添加任务',
        },
        {
          object: 'npm run test:unit',
          action: '执行检查命令',
          high: true,
          reason: '工程脚本可能产生文件或其他副作用',
          preview:
            '工作目录：.\n命令：npm run test:unit\n绑定文件：package.json、package-lock.json',
        },
      ]
    : []),
])
const current = computed(() => steps.value[step.value])
const permission = computed<AgentPermissionRequest | null>(() =>
  current.value
    ? {
        id: `preview-${round.value}-${step.value}`,
        revision: '1',
        actionHash: `preview-action-${round.value}-${step.value}`,
        action: current.value.action,
        object: current.value.object,
        highRisk: current.value.high,
        reason: current.value.reason,
        detailTitle: current.value.action === '修改文件' ? '文件差异' : '命令详情',
        detailText: current.value.preview,
      }
    : null,
)
const candidate = computed<AgentChangeCandidate>(() => ({
  id: `preview-review-${round.value}`,
  revision: '1',
  reviewHash: `preview-review-${round.value}-${props.highRisk}`,
  highRisk: props.highRisk,
  files: steps.value
    .filter((item) => item.action === '修改文件')
    .map((item) => ({
      path: item.object,
      change: 'MODIFIED',
      highRisk: item.high,
      binary: false,
    })),
}))
const active = computed(() => ['RUNNING', 'ASK', 'REVIEW'].includes(phase.value))
const locked = computed(() => active.value || saving.value || deciding.value)
const status = computed(
  () =>
    ({
      IDLE: '等待输入',
      RUNNING: '执行中',
      ASK: '等待本次批准',
      REVIEW: '等待整批确认',
      MERGED: '代码已合入',
      STOPPED: '本轮已停止',
      FAILED: '本轮失败',
    })[phase.value],
)

async function saveSettings(value: AgentComposerSettings) {
  if (locked.value) return

  saving.value = true
  error.value = ''
  // 1. 仅演示保存回执；真实设置、revision 和刷新恢复留待后端接线。
  await nextTick()
  const field = value.mode !== settings.value.mode ? '模式' : '设置'
  if (props.saveOutcome === 'conflict')
    error.value = `其他页面已修改授权设置，请重新读取后再选择。当前${field}未更改。`
  else if (props.saveOutcome === 'failure')
    error.value = `授权设置保存失败，请重试。当前${field}未更改。`
  else settings.value = value
  saving.value = false
}

async function loadReviewFile(event: AgentReviewFileEvent) {
  if (event.reviewId !== candidate.value.id || event.reviewHash !== candidate.value.reviewHash)
    return
  const item = steps.value.find((item) => item.object === event.path)
  if (!item) return
  reviewDiffs.value[event.path] = { status: 'loading' }
  await nextTick()
  if (
    event.reviewId !== candidate.value.id ||
    event.reviewHash !== candidate.value.reviewHash ||
    phase.value !== 'REVIEW'
  )
    return
  const totalLines = item.preview.split('\n').length
  reviewDiffs.value[event.path] = {
    status: 'loaded',
    text: item.preview,
    startLine: 1,
    endLine: totalLines,
    totalLines,
  }
}

function decideTool(event: AgentToolDecisionEvent) {
  if (event.requestId !== permission.value?.id || event.actionHash !== permission.value.actionHash)
    return
  if (event.decision === 'REJECT') stop()
  else approve(event.decision === 'APPROVE_SIMILAR')
}

function decideReview(event: AgentReviewDecisionEvent) {
  if (event.reviewId !== candidate.value.id || event.reviewHash !== candidate.value.reviewHash)
    return
  if (event.decision === 'REJECT') stop()
  else approve()
}

function sessionApprovalKey(item: (typeof steps.value)[number]) {
  // 普通文件按目录匹配；高风险文件限于同一文件，命令限于相同命令详情。
  const target =
    item.action === '修改文件'
      ? item.high
        ? item.object
        : item.object.slice(0, item.object.lastIndexOf('/') + 1)
      : `${item.object}\n${item.preview}`
  return `${item.action}:${item.high}:${target}`
}

function advance() {
  // 1. 演示三种策略的询问位置，不执行文件或工程命令。
  while (step.value < steps.value.length) {
    const item = steps.value[step.value]!
    const sessionApproved = sessionApprovals.value.has(sessionApprovalKey(item))
    if (
      !sessionApproved &&
      (mode.value === 'MANUAL' || (mode.value === 'GUARDED_AUTO' && item.high))
    ) {
      phase.value = 'ASK'
      return
    }
    decisions.value.push(
      `${item.object} · ${sessionApproved ? '用户会话同类批准' : mode.value === 'GUARDED_AUTO' ? 'AI审查：非高风险 · 自动批准' : '策略自动批准'}`,
    )
    step.value++
  }
  // 2. 最终整批确认与工具批准是两个独立步骤。
  if (mode.value === 'MANUAL' || (mode.value === 'GUARDED_AUTO' && props.highRisk))
    phase.value = 'REVIEW'
  else {
    decisions.value.push(
      `最终候选 · ${mode.value === 'GUARDED_AUTO' ? 'AI审查：非高风险 · 自动批准' : '策略自动批准'}`,
    )
    publish()
  }
}

function publish() {
  if (props.publicationOutcome === 'failure') {
    phase.value = 'FAILED'
    error.value = '代码合入失败，源码未更新。请查看错误后重试。'
  } else phase.value = 'MERGED'
}

async function send() {
  if (locked.value || !input.value.trim()) return
  sent.value = input.value.trim()
  input.value = ''
  error.value = ''
  decisions.value = []
  step.value = 0
  round.value++
  reviewDiffs.value = {}
  phase.value = 'RUNNING'
  await nextTick()
  if (phase.value !== 'RUNNING') return
  if (props.forbidden) {
    phase.value = 'FAILED'
    error.value = '操作已禁止：目标为受保护路径。任何授权模式都不能解除此限制。'
    return
  }
  advance()
}

async function approve(similar = false) {
  if (deciding.value || !['ASK', 'REVIEW'].includes(phase.value)) return
  deciding.value = true
  error.value = ''
  await nextTick()
  if (props.decisionOutcome === 'failure')
    error.value = '批准提交失败，尚未批准。请重试或停止本轮。'
  else if (props.decisionOutcome === 'expired') {
    phase.value = 'STOPPED'
    error.value = '批准对象已失效，本轮已停止。请重新发起修改。'
  } else if (phase.value === 'ASK') {
    const item = current.value!
    if (similar) sessionApprovals.value.add(sessionApprovalKey(item))
    decisions.value.push(`${item.object} · ${similar ? '用户会话同类批准' : '用户仅批准本次'}`)
    step.value++
    advance()
  } else if (phase.value === 'REVIEW') {
    decisions.value.push('最终候选 · 用户整批确认')
    publish()
  }
  deciding.value = false
}

function stop() {
  if (deciding.value) return
  phase.value = 'STOPPED'
  error.value = ''
}
</script>

<template>
  <ConfigProvider :theme="{ token: { colorPrimary: '#2563eb', borderRadius: 6 } }">
    <div class="workspace">
      <header class="topbar">
        <a href="/permission-preview.html">应用魔方</a><span>已有项目 / 任务管理</span
        ><span class="account">开发者</span>
      </header>
      <main>
        <section class="chat" aria-label="项目会话">
          <header class="section-heading">
            <h1>任务管理</h1>
            <span class="status" role="status">{{ status }}</span>
          </header>
          <div class="conversation" aria-live="polite">
            <div v-if="!sent" class="intro">
              <h2>从一个调整开始</h2>
              <p>描述你想调整的内容。</p>
            </div>
            <p v-if="sent" class="user-message">{{ sent }}</p>
            <div v-if="sent" class="agent-message">
              <strong>Agent</strong>
              <p>将调整空状态，更新说明并检查修改。</p>
            </div>
            <ul v-if="decisions.length" class="audit">
              <li v-for="row in decisions" :key="row">{{ row }}</li>
            </ul>
            <AgentPermissionCard
              v-if="phase === 'ASK' && permission"
              :request="permission"
              :busy="deciding"
              @decision="decideTool"
            />
            <AgentChangeReview
              v-if="phase === 'REVIEW'"
              :candidate="candidate"
              :diffs="reviewDiffs"
              :busy="deciding"
              @decision="decideReview"
              @load-file="loadReviewFile"
            />
            <Alert
              v-if="phase === 'MERGED'"
              type="success"
              show-icon
              message="代码已合入"
              description="业务效果尚未验收，运行与部署仍需各自授权。"
            />
            <Alert
              v-if="phase === 'STOPPED'"
              type="info"
              show-icon
              message="本轮已停止，候选修改未合入。"
            />
            <Alert v-if="error" type="error" show-icon :message="error" role="alert" />
          </div>
          <footer class="composer">
            <Input.TextArea
              v-model:value="input"
              aria-label="描述修改"
              placeholder="描述你想调整的内容…"
              :rows="3"
              :disabled="active || saving"
            />
            <div class="composer-actions">
              <AgentComposerControls
                :settings="settings"
                :models="modelOptions"
                :busy="active || deciding"
                :saving="saving"
                @change="saveSettings"
              />
              <Button v-if="active" class="send-action" danger :disabled="deciding" @click="stop"
                >停止本轮</Button
              >
              <Button
                v-else
                type="primary"
                class="send-action"
                :disabled="saving || !input.trim()"
                data-testid="send"
                @click="send"
                >发送</Button
              >
            </div>
            <p v-if="active" class="muted lock-hint">本轮授权已固定；停止本轮后可更改模式。</p>
          </footer>
        </section>
        <aside class="project" aria-label="项目文件">
          <div class="section-heading">
            <h2>项目</h2>
            <span>源码</span>
          </div>
          <div class="file">src/components/TaskList.vue</div>
          <div class="file">README.md</div>
          <div class="file">package.json</div>
          <div class="project-note">运行与部署</div>
          <p class="muted">未启动服务</p>
        </aside>
      </main>
    </div>
  </ConfigProvider>
</template>

<style scoped>
.workspace {
  --line: #e5e5e5;
  --muted: #737373;
  min-height: 100vh;
  background: #fff;
  color: #262626;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif;
  font-size: 14px;
  line-height: 1.65;
}
.topbar {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 56px;
  padding: 0 24px;
  border-bottom: 1px solid var(--line);
}
.topbar a {
  color: #262626;
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
}
.topbar span,
.muted {
  color: var(--muted);
}
.topbar span {
  font-size: 13px;
}
.account {
  margin-left: auto;
}
main {
  max-width: 1360px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 256px;
  min-height: calc(100vh - 56px);
}
.chat,
.project {
  min-width: 0;
}
.chat {
  display: flex;
  flex-direction: column;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 16px 32px;
}
h1,
h2,
p {
  margin: 0;
}
h1 {
  font-size: 15px;
  font-weight: 600;
}
h2 {
  font-size: 14px;
  font-weight: 600;
}
.status {
  color: var(--muted);
  font-size: 12px;
}
.status::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 8px;
  border-radius: 50%;
  background: #2563eb;
  vertical-align: middle;
}
.conversation {
  flex: 1;
  width: 100%;
  max-width: 824px;
  box-sizing: border-box;
  margin: 0 auto;
  padding: 24px 32px 32px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 24px;
}
.intro {
  margin: auto 0;
  padding: 64px 0;
}
.intro h2 {
  font-size: 24px;
  font-weight: 500;
  margin-bottom: 8px;
}
.intro p {
  color: var(--muted);
}
.user-message {
  align-self: flex-end;
  background: #f5f5f5;
  padding: 10px 16px;
  border-radius: 16px;
  max-width: 85%;
  overflow-wrap: anywhere;
}
.agent-message strong {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}
.agent-message p {
  margin-top: 8px;
}
.audit {
  margin: 0;
  padding-left: 18px;
  color: var(--muted);
  font-size: 12px;
  overflow-wrap: anywhere;
}
.audit li + li {
  margin-top: 4px;
}
.composer {
  box-sizing: border-box;
  width: calc(100% - 64px);
  max-width: 760px;
  margin: 0 auto 24px;
  padding: 12px;
  border: 1px solid #d4d4d4;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 2px 6px #00000004;
}
.composer:focus-within {
  border-color: #a3a3a3;
}
.composer :deep(textarea.ant-input) {
  border: 0;
  padding: 0 4px;
  box-shadow: none;
  resize: vertical;
  background: transparent;
  min-height: 64px;
}
.composer :deep(textarea.ant-input:focus-visible) {
  outline: 2px solid #2563eb;
  outline-offset: 4px;
  border-radius: 2px;
}
.composer-actions {
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
  margin-top: 12px;
  align-items: center;
}
.send-action {
  flex-shrink: 0;
  margin-left: auto;
}
.lock-hint {
  margin-top: 12px;
  font-size: 12px;
}
.project {
  border-left: 1px solid var(--line);
  background: #fafafa;
}
.project .section-heading {
  padding: 16px 20px;
}
.project .section-heading span {
  font-size: 12px;
  color: var(--muted);
}
.file {
  padding: 8px 20px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  color: #525252;
  overflow-wrap: anywhere;
}
.project-note {
  margin-top: 24px;
  border-top: 1px solid var(--line);
  padding: 16px 20px 8px;
  font-size: 12px;
  font-weight: 600;
}
.project > p {
  padding: 0 20px;
  font-size: 12px;
}
:deep(.ant-btn) {
  min-height: 32px;
  height: auto;
  padding: 4px 12px;
  font-size: 13px;
  white-space: normal;
  box-shadow: none;
  transition:
    background-color 120ms ease-out,
    border-color 120ms ease-out,
    color 120ms ease-out;
}
:deep(.ant-btn:active:not(:disabled)) {
  transform: translateY(1px);
}
a:focus-visible,
:deep(.ant-btn:focus-visible) {
  outline: 2px solid #2563eb;
  outline-offset: 4px;
}
:deep(.ant-alert) {
  border-radius: 6px;
  font-size: 13px;
}
@media (max-width: 1023px) {
  main {
    grid-template-columns: minmax(0, 1fr) 208px;
  }
  .section-heading {
    padding: 16px 24px;
  }
  .conversation {
    padding: 24px;
  }
  .composer {
    width: calc(100% - 48px);
  }
}
@media (max-width: 767px) {
  .topbar {
    padding: 0 16px;
    gap: 16px;
    height: 52px;
  }
  .topbar > span:not(.account) {
    display: none;
  }
  main {
    grid-template-columns: minmax(0, 1fr);
    min-height: calc(100vh - 52px);
  }
  .project {
    display: none;
  }
  .conversation {
    padding: 24px 16px;
    gap: 20px;
  }
  .section-heading {
    padding: 16px;
  }
  .composer {
    width: calc(100% - 32px);
    margin-bottom: 16px;
  }
  .composer-actions > .send-action {
    min-height: 36px;
  }
}
@media (prefers-reduced-motion: reduce) {
  :deep(.ant-btn) {
    transition: none;
  }
  :deep(.ant-btn:active:not(:disabled)) {
    transform: none;
  }
}
</style>
