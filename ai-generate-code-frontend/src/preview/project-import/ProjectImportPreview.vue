<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Alert as AAlert, Button as AButton,
  ConfigProvider as AConfigProvider, Modal as AModal,
} from 'ant-design-vue'
import ProjectImportForm from '@/features/project-import/ProjectImportForm.vue'
import GitSourcePreview from './GitSourcePreview.vue'
import StartupRunPreview from './StartupRunPreview.vue'
import { resolveThemeConfig } from '@/stores/appearance'
import {
  createManifest, defaultOptions, previewFileLimit, validateFiles,
  type FileMetadata, type ImportOptions,
} from './manifest'
import { setWorkbenchProject } from './workbenchTransport'

const AgentWorkbench = defineAsyncComponent(() => import('@/page/App/AppChatView.vue'))
const route = useRoute()
const router = useRouter()
const gitSource = ref(route.query.source === 'git')
const startupPreview = route.query.startup === '1'
function invalidateGitSource() {
  if (busy.value) cancelImport()
  files.value = []; folderName.value = ''; appName.value = ''; error.value = ''
}

type ImportState = 'select' | 'review' | 'importing' | 'succeeded' | 'failed' | 'cancelled'
// 仅由隔离入口的测试装配控制，不向用户展示测试开关。
const props = defineProps<{
  importOutcome?: 'success' | 'failure' | 'failure-once'
  installOutcome?: 'success' | 'failure' | 'failure-once'
}>()
const emit = defineEmits<{ taskState: [event: { taskId: string; name: string; status: string }] }>()
const dark = ref(false)
const themeConfig = computed(() => resolveThemeConfig({
  isDark: dark.value, primaryColor: '#1677ff', fontSize: 15, compactMode: false,
}))
const files = ref<FileMetadata[]>([])
const folderName = ref('')
const appName = ref('')
const options = ref<ImportOptions>(defaultOptions())
const savedOptions = ref<ImportOptions>(defaultOptions())
const notice = ref('')
const error = ref('')
const state = ref<ImportState>('select')
const manifest = computed(() => createManifest(files.value, options.value))
const busy = computed(() => state.value === 'importing')
const canSubmit = computed(() => files.value.length > 0 && manifest.value.included.length > 0 &&
  !!appName.value.trim() && appName.value.trim().length <= 80 && !error.value && !busy.value)
const steps = ['检查文件', '接入工程', '创建应用']
const phase = ref(0)
const taskId = ref('')
const result = ref<{ name: string; count: number; bytes: number; taskId: string; appId: string }>()
const inWorkbench = computed(() => route.name === 'app-chat' && !!result.value && String(route.params.id) === result.value.appId)
const attempts = ref<Array<{ taskId: string; name: string; status: string; detail: string }>>([])
const installPrompt = ref(false)
const installStatus = ref<'idle' | 'installing' | 'succeeded' | 'failed'>('idle')
const installFailedOnce = ref(false)
const installBusy = computed(() => installStatus.value === 'installing')
let timer: ReturnType<typeof setTimeout> | undefined
let installTimer: ReturnType<typeof setTimeout> | undefined
let generation = 0

function toggleTheme() {
  dark.value = !dark.value
  document.documentElement.dataset.theme = dark.value ? 'dark' : 'light'
}

function selectSource(metadata: FileMetadata[], name: string) {
  // 1. 先校验整份元数据，不用部分有效文件代替失败清单。
  error.value = validateFiles(metadata) ?? ''
  files.value = error.value ? [] : metadata
  folderName.value = error.value ? '' : name
  appName.value = folderName.value
  // 2. 新来源沿用预览内保存的偏好，本次调整不自动保存。
  options.value = { ...savedOptions.value }
  notice.value = ''
  state.value = error.value ? 'select' : 'review'
}

function selectFolder(event: Event) {
  const input = event.target as HTMLInputElement
  if (!input.files?.length) {
    selectSource([], '')
    input.value = ''
    return
  }
  if (input.files.length > previewFileLimit) {
    selectSource([], '')
    error.value = `最多选择 ${previewFileLimit} 个文件，请缩小范围后重试。`
    input.value = ''
    return
  }
  // 1. 浏览器只提供相对路径；本预览不读取内容、不上传 File。
  const selected = Array.from(input.files)
  const root = selected[0]!.webkitRelativePath.split('/')[0]!
  const paths = selected.map((file) => file.webkitRelativePath)
  if (!root || paths.some((path) => !path.startsWith(`${root}/`))) {
    selectSource([], '')
    error.value = '未取得一致的文件夹路径，请使用文件夹选择器重试。'
  } else {
    selectSource(selected.map((file) => ({
      path: file.webkitRelativePath.slice(root.length + 1), size: file.size,
    })), root)
  }
  input.value = ''
}

function savePreferences() {
  savedOptions.value = { ...options.value }
  notice.value = '偏好已保存。'
}

function finishAttempt(status: string, detail: string) {
  attempts.value.unshift({ taskId: taskId.value, name: appName.value.trim(), status, detail })
  emit('taskState', { taskId: taskId.value, name: appName.value.trim(), status })
}

function startImport() {
  if (!canSubmit.value) return
  // 1. 固定本次模拟输入，重复点击与迟到回调不得改写新任务。
  const current = ++generation
  const frozen = {
    name: appName.value.trim(), count: manifest.value.included.length,
    bytes: manifest.value.totalBytes, taskId: `preview-${crypto.randomUUID()}`, appId: `preview-app-${crypto.randomUUID()}`,
  }
  const includedFiles = manifest.value.included.map((file) => ({ ...file }))
  const shouldFail = props.importOutcome === 'failure' ||
    (props.importOutcome === 'failure-once' && !attempts.value.some((attempt) => attempt.status === 'failed'))
  taskId.value = frozen.taskId
  state.value = 'importing'
  phase.value = 0
  notice.value = ''
  result.value = undefined
  emit('taskState', { taskId: frozen.taskId, name: frozen.name, status: 'importing' })
  // 2. 只推进本页模拟阶段；不创建应用、副本、网络请求或安装任务。
  const advance = async () => {
    if (current !== generation || state.value !== 'importing') return
    if (phase.value < steps.length - 1) {
      phase.value++
      timer = setTimeout(advance, 650)
    } else if (shouldFail) {
      state.value = 'failed'
      finishAttempt('failed', '工程接入失败。')
    } else {
      result.value = frozen
      state.value = 'succeeded'
      finishAttempt('succeeded', '导入完成。')
      // 只有本次成功工程可以进入工作台，不携带 autoSend 或初始生成提示。
      setWorkbenchProject({ ...frozen, files: includedFiles })
      try {
        await router.push({ name: 'app-chat', params: { id: frozen.appId } })
        if (current === generation && inWorkbench.value) {
          document.title = `${frozen.name} · Agent 工作台`
          if(!startupPreview) installPrompt.value = true
        }
      } catch {
        error.value = '进入工作台失败，请重新导入。'
        state.value = 'failed'
        setWorkbenchProject()
        result.value = undefined
      }
    }
  }
  timer = setTimeout(advance, 650)
}

function cancelImport() {
  if (!busy.value) return
  generation++
  clearTimeout(timer)
  state.value = 'cancelled'
  finishAttempt('cancelled', '导入已取消。')
}

function resetImport() {
  generation++
  clearTimeout(timer)
  clearTimeout(installTimer)
  files.value = []
  folderName.value = ''
  appName.value = ''
  if (result.value) sessionStorage.removeItem(`genMode:${result.value.appId}`)
  result.value = undefined
  setWorkbenchProject()
  error.value = ''
  notice.value = ''
  installPrompt.value = false
  installStatus.value = 'idle'
  installFailedOnce.value = false
  options.value = { ...savedOptions.value }
  state.value = 'select'
  document.title = '导入项目 · 应用魔方'
}

function confirmInstall() {
  if (!inWorkbench.value || !result.value || installBusy.value || installStatus.value === 'succeeded') return
  // 1. 安装授权只绑定当前应用，不延续到新导入或自动启动。
  installPrompt.value = false
  installStatus.value = 'installing'
  notice.value = ''
  const current = generation
  const target = result.value.taskId
  const shouldFail = props.installOutcome === 'failure' ||
    (props.installOutcome === 'failure-once' && !installFailedOnce.value)
  // 2. 此入口只演示安装交互，不执行脚本或调用生产接口。
  installTimer = setTimeout(() => {
    if (current !== generation || result.value?.taskId !== target || !inWorkbench.value) return
    installStatus.value = shouldFail ? 'failed' : 'succeeded'
    if (shouldFail) installFailedOnce.value = true
  }, 1300)
}

watch(() => route.fullPath, () => {
  if (route.name === 'app-chat' && !inWorkbench.value) {
    resetImport()
    error.value = '工程会话已失效，请重新导入。'
    void router.replace({ name: 'import' })
  } else if (route.name === 'import' && result.value) {
    resetImport()
  }
}, { immediate: true })

onBeforeUnmount(() => {
  generation++
  clearTimeout(timer)
  clearTimeout(installTimer)
  setWorkbenchProject()
})
</script>

<template>
  <a-config-provider :theme="themeConfig">
    <div class="import-preview">
      <header class="preview-header">
        <div class="brand"><span class="brand-mark" aria-hidden="true">◇</span>应用魔方</div>
        <div class="header-actions">
          <a-button v-if="!inWorkbench" :disabled="busy" :type="!gitSource ? 'primary' : 'default'" @click="gitSource = false; resetImport()">文件夹</a-button>
          <a-button v-if="!inWorkbench" :disabled="busy" :type="gitSource ? 'primary' : 'default'" @click="gitSource = true; resetImport()">Git 仓库</a-button>
          <a-button @click="toggleTheme">{{ dark ? '浅色' : '深色' }}</a-button>
        </div>
      </header>

      <ProjectImportForm v-if="!inWorkbench" metadata-preview v-model:app-name="appName" v-model:options="options"
        :files="files" :folder-name="folderName" :manifest="manifest" :state="state" :busy="busy"
        :can-submit="canSubmit" :error="error" :notice="notice" :stage-label="steps[phase]"
        :progress="Math.round((phase + 1) / 4 * 100)" @select-folder="selectFolder"
        @save-preferences="savePreferences" @start-import="startImport" @cancel-import="cancelImport" @reset-import="resetImport">
        <template v-if="gitSource" #source><GitSourcePreview :busy="busy" @select="selectSource" @invalidate="invalidateGitSource" /></template>
      </ProjectImportForm>

      <section v-else class="agent-workspace" aria-label="Agent 工作台">
        <div class="workspace-toolbar">
          <a-button :disabled="installBusy" @click="router.push({ name: 'import' })">导入另一个工程</a-button>
          <div v-if="!startupPreview" class="install-controls">
            <div v-if="installBusy" class="install-status" role="status" aria-live="polite">
              正在安装依赖…
            </div>
            <a-alert v-else-if="installStatus === 'failed'" class="install-status" type="error" show-icon message="依赖安装失败，请重试。" role="alert" />
            <a-button :loading="installBusy" :disabled="installBusy || installStatus === 'succeeded'" @click="installPrompt = true">{{ installStatus === 'failed' ? '重试安装' : installStatus === 'succeeded' ? '已安装' : '安装依赖' }}</a-button>
          </div>
        </div>
        <AgentWorkbench :key="result?.appId" imported-project>
          <template v-if="startupPreview && result" #runtime-actions><StartupRunPreview :app-id="result.appId" /></template>
        </AgentWorkbench>
      </section>

      <a-modal v-model:open="installPrompt" title="是否安装依赖？" ok-text="安装依赖" cancel-text="暂不安装" @ok="confirmInstall">
        <p>单独安装不会启动项目。</p>
      </a-modal>
    </div>
  </a-config-provider>
</template>

<style scoped>
.import-preview { min-height: 100vh; background: var(--bg-soft); color: var(--text-base); font-family: var(--font-family-base); }
/* 主题只过渡颜色，避免窗口变窄时宽度和内边距动画产生临时溢出。 */
.import-preview, .import-preview :deep(:not([class*="ant-"])) { transition-property: color, background-color, border-color, box-shadow; }
.preview-header { min-height: 64px; padding: 12px max(24px, calc((100vw - 1024px) / 2 + 24px)); display: flex; justify-content: space-between; align-items: center; gap: 16px; background: var(--bg-card); border-bottom: 1px solid var(--border-color); }
.brand { display: flex; gap: 10px; align-items: center; font-weight: 650; white-space: nowrap; }
.brand-mark { display: grid; place-items: center; width: 32px; height: 32px; border-radius: var(--radius-md); color: white; background: var(--color-primary); font-size: 20px; }
.header-actions, .footer-actions, .result-actions { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.agent-workspace { padding: 24px; }
.agent-workspace :deep(.app-chat-page) { max-width: none; padding: 0; margin: 0; height: calc(100dvh - 168px); min-height: 520px; }
.workspace-toolbar, .install-controls { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.workspace-toolbar { margin-bottom: 16px; }
.install-status { max-width: 320px; }
.agent-workspace :deep(.top-bar), .agent-workspace :deep(.top-actions) { flex-wrap: wrap; gap: 12px; }
.agent-workspace :deep(.app-title-wrap) { max-width: 100%; flex-wrap: wrap; }
.agent-workspace :deep(.app-meta) { min-width: 0; flex: 1; }
.agent-workspace :deep(.app-name) { overflow-wrap: anywhere; }
@media (max-width: 992px) {
  .agent-workspace :deep(.app-chat-page) { height: auto; min-height: 0; }
  .agent-workspace :deep(.main-content) { height: auto; overflow: visible; flex: none; gap: 20px; }
  .agent-workspace :deep(.chat-panel) { height: 480px; flex: none; }
  .agent-workspace :deep(.preview-panel) { height: 340px; flex: none; }
}
@media (max-width: 760px) {
  .preview-header { padding: 14px 16px; flex-wrap: wrap; }
  .agent-workspace { padding: 16px; }
}
</style>
