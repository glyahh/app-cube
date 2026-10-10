<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { Alert, Button, Input, Select, ConfigProvider } from 'ant-design-vue'

type Mode = 'MANUAL' | 'GUARDED_AUTO' | 'AUTO'
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

const options = [
  { value: 'MANUAL', label: '手动批准' },
  { value: 'GUARDED_AUTO', label: '自动批准' },
  { value: 'AUTO', label: '自由发挥' },
]

// 审核页的模型与能力示例，不读取真实配置，也不作为正式请求参数。
const modelOptions = [
  { value: 'gpt-5.2', label: 'GPT-5.2', provider: 'OpenAI', supportsEffort: true },
  {
    value: 'claude-sonnet-4-5',
    label: 'Claude Sonnet 4.5',
    provider: 'Anthropic',
    supportsEffort: true,
  },
  { value: 'deepseek-chat', label: 'DeepSeek Chat', provider: 'DeepSeek', supportsEffort: false },
]
const effortOptions = [
  { value: 'low', label: '低', description: '快速响应' },
  { value: 'medium', label: '中', description: '速度与深度均衡' },
  { value: 'high', label: '高', description: '更深入地思考' },
]
const selectedModel = ref('gpt-5.2')
const effort = ref('medium')
const supportsEffort = computed(
  () => modelOptions.find((model) => model.value === selectedModel.value)?.supportsEffort === true,
)
const mode = ref<Mode>('MANUAL')
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

async function saveMode(value: Mode) {
  if (locked.value) return

  saving.value = true
  error.value = ''
  // 1. 仅演示保存回执；真实服务端策略、revision 和刷新恢复留待后端接线。
  await nextTick()
  if (props.saveOutcome === 'conflict')
    error.value = '其他页面已修改授权模式，请重新读取后再选择。当前模式未更改。'
  else if (props.saveOutcome === 'failure')
    error.value = '授权模式保存失败，请重试。当前模式未更改。'
  else {
    mode.value = value
  }
  saving.value = false
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
            <article v-if="phase === 'ASK' && current" class="approval" data-testid="tool-card">
              <div class="card-heading">
                <h2>允许{{ current.action }}？</h2>
                <span class="object approval-object">{{ current.object }}</span>
                <span v-if="current.high" class="risk">高风险操作</span>
              </div>
              <p v-if="current.high" class="approval-reason">{{ current.reason }}</p>
              <details open>
                <summary>{{ current.action === '修改文件' ? '文件差异' : '命令详情' }}</summary>
                <pre><span v-for="(line, index) in current.preview.split('\n')" :key="index" class="diff-line" :class="{ 'diff-added': line.startsWith('+'), 'diff-removed': line.startsWith('-') }">{{ line }}{{ index < current.preview.split('\n').length - 1 ? '\n' : '' }}</span></pre>
              </details>
              <div class="buttons">
                <Button danger :disabled="deciding" @click="stop">拒绝并停止本轮</Button
                ><Button type="primary" :loading="deciding" data-testid="approve" @click="approve()"
                  >仅批准本次</Button
                >
                <Button
                  :disabled="deciding"
                  data-testid="approve-similar"
                  title="仅本会话内同风险、同目标范围的工具请求；不包含整批合入"
                  @click="approve(true)"
                  >本会话批准同类请求</Button
                >
              </div>
            </article>
            <article v-if="phase === 'REVIEW'" class="approval" data-testid="review-card">
              <div class="card-heading">
                <h2>确认整批代码修改</h2>
                <span v-if="highRisk" class="risk">含高风险修改</span>
              </div>
              <p class="approval-reason">请核对以下候选修改，尚未合入源码。</p>
              <details open>
                <summary>src/components/TaskList.vue · 修改</summary>
                <pre><span v-for="(line, index) in diff.split('\n')" :key="index" class="diff-line" :class="{ 'diff-added': line.startsWith('+'), 'diff-removed': line.startsWith('-') }">{{ line }}{{ index < diff.split('\n').length - 1 ? '\n' : '' }}</span></pre>
              </details>
              <details v-if="highRisk">
                <summary>README.md · 修改 · 高风险</summary>
                <pre><span v-for="(line, index) in steps[1]?.preview.split('\n')" :key="index" class="diff-line" :class="{ 'diff-added': line.startsWith('+'), 'diff-removed': line.startsWith('-') }">{{ line }}{{ index < (steps[1]?.preview.split('\n').length ?? 0) - 1 ? '\n' : '' }}</span></pre>
              </details>
              <p class="muted approval-scope">确认后合入以上全部修改；工具批准不能代替整批确认。</p>
              <div class="buttons">
                <Button danger :disabled="deciding" @click="stop">拒绝并停止本轮</Button
                ><Button type="primary" :loading="deciding" data-testid="approve" @click="approve()"
                  >确认整批合入</Button
                >
              </div>
            </article>
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
              <div class="composer-controls">
                <div class="mode-control">
                  <label class="sr-only" for="permission-mode">会话授权模式</label>
                  <Select
                    id="permission-mode"
                    :value="mode"
                    :options="options"
                    :disabled="locked"
                    :loading="saving"
                    :show-arrow="false"
                    :bordered="false"
                    :dropdown-match-select-width="false"
                    aria-label="会话授权模式"
                    data-testid="mode"
                    @change="(value) => saveMode(value as Mode)"
                  />
                </div>
                <div class="model-controls">
                  <label class="sr-only" for="chat-model">当前模型</label>
                  <Select
                    id="chat-model"
                    v-model:value="selectedModel"
                    class="model-select"
                    :options="modelOptions"
                    :disabled="locked"
                    :bordered="false"
                    show-search
                    option-filter-prop="label"
                    placement="topLeft"
                    :dropdown-match-select-width="false"
                    :dropdown-style="{ minWidth: '260px', maxWidth: 'calc(100vw - 32px)' }"
                    aria-label="当前模型"
                    data-testid="model"
                  >
                    <template #option="item">
                      <span class="model-option"
                        ><span>{{ item.label }}</span
                        ><span class="model-provider"
                          >{{ item.provider }}
                          <svg
                            v-if="item.value === selectedModel"
                            class="selected-check"
                            width="14"
                            height="14"
                            viewBox="0 0 16 16"
                            fill="none"
                            aria-hidden="true"
                          >
                            <path
                              d="m3 8 3 3 7-7"
                              stroke="currentColor"
                              stroke-width="1.8"
                              stroke-linecap="round"
                              stroke-linejoin="round"
                            />
                          </svg> </span
                      ></span>
                    </template>
                  </Select>
                  <span class="control-divider" aria-hidden="true"></span>
                  <label class="sr-only" for="thinking-effort">思考力度</label>
                  <Select
                    id="thinking-effort"
                    v-model:value="effort"
                    class="effort-select"
                    :options="supportsEffort ? effortOptions : [{ value: effort, label: '不支持' }]"
                    :disabled="locked || !supportsEffort"
                    :bordered="false"
                    placement="topLeft"
                    :dropdown-match-select-width="false"
                    :dropdown-style="{ minWidth: '190px' }"
                    aria-label="思考力度"
                    data-testid="effort"
                  >
                    <template #option="item">
                      <span class="effort-option"
                        ><span>{{ item.label }}</span
                        ><span class="effort-description">{{ item.description }}</span></span
                      >
                    </template>
                    <template #suffixIcon>
                      <svg
                        width="14"
                        height="14"
                        viewBox="0 0 16 16"
                        fill="none"
                        aria-hidden="true"
                      >
                        <path
                          d="M3 12V9M8 12V6M13 12V3"
                          stroke="currentColor"
                          stroke-width="2"
                          stroke-linecap="round"
                        />
                      </svg>
                    </template>
                  </Select>
                </div>
              </div>
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
.approval {
  border-top: 1px solid var(--line);
  border-bottom: 1px solid var(--line);
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  animation: enter 180ms cubic-bezier(0.16, 1, 0.3, 1);
}
.card-heading {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 12px;
  align-items: center;
}
.risk {
  color: #896642;
  font-size: 12px;
}
.object {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 13px;
  overflow-wrap: anywhere;
}
.approval-object {
  color: var(--muted);
  min-width: 0;
}
.approval-reason {
  color: var(--muted);
  font-size: 13px;
}
.approval-scope {
  font-size: 12px;
  margin-top: 4px;
}
details {
  min-width: 0;
  margin-top: 4px;
}
summary {
  cursor: pointer;
  color: #525252;
  font-size: 12px;
  padding: 4px 0;
  overflow-wrap: anywhere;
}
summary:hover {
  color: #262626;
}
pre {
  background: #fafafa;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 4px 0 0;
  padding: 8px 0;
}
.diff-line {
  display: block;
  padding: 0 12px;
}
.diff-added {
  background: #edf6ee;
  color: #285b36;
}
.diff-removed {
  background: #fbefee;
  color: #92463e;
}
.buttons {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-start;
  gap: 8px;
  margin-top: 8px;
}
.buttons :deep(.ant-btn-dangerous:not(:hover):not(:disabled)) {
  color: #525252;
  border-color: #d4d4d4;
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
.composer-controls,
.model-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.composer-controls {
  flex: 1;
}
.mode-control {
  flex: 0 0 auto;
}
.mode-control :deep(.ant-select) {
  width: auto;
}
.mode-control :deep(.ant-select-selector) {
  background: #f5f5f5;
  border-radius: 16px;
}
.model-select {
  width: auto;
  min-width: 104px;
}
.effort-select {
  width: auto;
}
.model-controls :deep(.ant-select-selector) {
  border-radius: 6px;
  color: #737373;
}
.model-controls :deep(.ant-select:not(.ant-select-disabled):hover .ant-select-selector) {
  background: #f5f5f5;
}
.composer-controls :deep(.ant-select-focused .ant-select-selector) {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.control-divider {
  height: 14px;
  width: 1px;
  background: var(--line);
  margin: 0 2px;
}
.model-option,
.effort-option {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  align-items: center;
}
.model-provider,
.effort-description {
  font-size: 12px;
  color: #737373;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip-path: inset(50%);
  white-space: nowrap;
}
.selected-check {
  margin-left: 8px;
  color: #2563eb;
  vertical-align: middle;
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
summary:focus-visible,
a:focus-visible,
:deep(.ant-btn:focus-visible) {
  outline: 2px solid #2563eb;
  outline-offset: 4px;
}
:deep(.ant-alert) {
  border-radius: 6px;
  font-size: 13px;
}
@keyframes enter {
  from {
    opacity: 0;
    transform: translateY(4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
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
  .composer-controls {
    gap: 8px;
  }
  .model-controls {
    gap: 2px;
  }
  .buttons :deep(.ant-btn) {
    min-height: 36px;
  }
  .composer-actions > .send-action {
    min-height: 36px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .approval {
    animation: none;
  }
  :deep(.ant-btn) {
    transition: none;
  }
  :deep(.ant-btn:active:not(:disabled)) {
    transform: none;
  }
}
</style>
