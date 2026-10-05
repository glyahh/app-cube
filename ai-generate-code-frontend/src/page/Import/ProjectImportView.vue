<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import ProjectImportForm from '@/features/project-import/ProjectImportForm.vue'
import GitProjectSource from '@/features/project-import/GitProjectSource.vue'
import { RadioGroup as ARadioGroup, RadioButton as ARadioButton } from 'ant-design-vue'
import { createManifest, defaultOptions, importFileLimit, validateFiles, type FileMetadata, type ImportOptions } from '@/features/project-import/manifest'
import { submitFolderImport, saveImportPreferences, loadImportPreferences } from '@/features/project-import/service'
import { UserLoginStore } from '@/stores/UserLogin'
import { pendingSourceRequest } from '@/features/project-import/sourceRequest'

const router = useRouter()
const user = UserLoginStore()
const sourceKind = ref<'folder' | 'git'>('folder')
const gitSource = ref<InstanceType<typeof GitProjectSource>>()
const gitWorking = ref(false)
const gitStageLabel = ref('')
const files = ref<FileMetadata[]>([])
const folderName = ref('')
const appName = ref('')
const options = ref<ImportOptions>(defaultOptions())
const savedOptions = ref<ImportOptions>(defaultOptions())
const state = ref<'select' | 'review' | 'importing' | 'failed' | 'cancelled'>('select')
const error = ref('')
const notice = ref('')
const saving = ref(false)
const preferenceReady = ref(false)
const manifest = computed(() => createManifest(files.value, options.value))
const busy = computed(() => state.value === 'importing' || saving.value || gitWorking.value)
const canSubmit = computed(() => preferenceReady.value && !!user.userLogin.id && files.value.length > 0 && manifest.value.included.length > 0 &&
  !!appName.value.trim() && appName.value.trim().length <= 80 && !busy.value)
// File 只保留于当前页面内存，确认后才上传；离开页面、退出或换账号即释放。
let selectedFiles = new Map<string, File>()
let generation = 0
let pending: AbortController | undefined

function resetImport() {
  generation++
  pending?.abort()
  pending = undefined
  selectedFiles.clear()
  files.value = []
  folderName.value = ''
  appName.value = ''
  options.value = { ...savedOptions.value }
  state.value = 'select'
  saving.value = false
  error.value = ''
  notice.value = ''
}

function selectFolder(event: Event) {
  const input = event.target as HTMLInputElement
  if (busy.value) { input.value = ''; return }
  resetImport()
  // 1. 完整校验文件夹清单，失败不保留上一来源或部分文件。
  const selected = input.files
  if (!selected?.length) error.value = '未发现文件，请选择非空文件夹。'
  else if (selected.length > importFileLimit) error.value = `最多选择 ${importFileLimit} 个文件，请缩小范围后重试。`
  else {
    const root = selected[0]!.webkitRelativePath.split('/')[0]!
    const entries = Array.from(selected)
    if (!root || entries.some(file => !file.webkitRelativePath.startsWith(`${root}/`))) {
      error.value = '未取得一致的文件夹路径，请使用文件夹选择器重试。'
    } else {
      const metadata = entries.map(file => ({ path: file.webkitRelativePath.slice(root.length + 1), size: file.size }))
      error.value = validateFiles(metadata) ?? ''
      // 2. 仅合法清单进入确认范围，真实文件不进入持久化存储或日志。
      if (!error.value) {
        files.value = metadata
        folderName.value = root
        appName.value = root
        selectedFiles = new Map(entries.map(file => [file.webkitRelativePath.slice(root.length + 1), file]))
        state.value = 'review'
      }
    }
  }
  input.value = ''
}

async function startImport() {
  if (!canSubmit.value) return
  // 1. 冻结本次名称、范围和身份，不把本地请求代次当成服务器任务。
  const current = ++generation
  const ownerId = String(user.userLogin.id)
  const controller = new AbortController()
  pending = controller
  const request = {
    ownerId, appName: appName.value.trim(), options: { ...options.value },
    files: files.value.map(metadata => ({ metadata: { ...metadata }, file: selectedFiles.get(metadata.path)! })),
  }
  state.value = 'importing'
  error.value = ''
  notice.value = ''
  try {
    const result = sourceKind.value === 'git'
      ? await gitSource.value!.submit(request.appName, request.options)
      : await submitFolderImport(request, controller.signal)
    // 2. 只有仍有效的真实结果能进入对应工作台，不自动发送生成请求。
    if (current !== generation || String(user.userLogin.id) !== ownerId) return
    if(controller.signal.aborted && !('cancellationOutcome' in result && result.cancellationOutcome === 'ALREADY_SUCCEEDED')) throw new DOMException('导入已取消','AbortError')
    if (!result.appId || !result.taskId || /^preview-/i.test(result.appId) || /^preview-/i.test(result.taskId)) {
      throw new Error('导入结果无效，请重新导入。')
    }
    await router.push({ name: 'app-chat', params: { id: result.appId } })
  } catch (cause) {
    if (current !== generation) return
    const cancelled = cause instanceof Error && cause.name === 'AbortError'
    state.value = cancelled ? 'cancelled' : 'failed'
    notice.value = ''
    error.value = cancelled ? '' : cause instanceof Error ? cause.message : '导入失败，请重试。'
  } finally {
    if (current === generation) pending = undefined
  }
}

function cancelImport() {
  if (!busy.value) return
  if(sourceKind.value === 'git' && !saving.value) {
    void gitSource.value?.clear().then(() => { resetImport(); state.value = 'cancelled' })
      .catch(cause => { error.value = cause instanceof Error ? cause.message : '停止未确认。' })
    return
  }
  pending?.abort()
  notice.value = saving.value ? '保存结果未确认，请重新读取偏好。' : '正在取消…'
}

function selectGit(filesFromSource: FileMetadata[], name: string, restoredOptions?: ImportOptions) {
  files.value = filesFromSource; selectedFiles.clear(); appName.value = name; folderName.value = name
  state.value = 'review'; error.value = ''; notice.value = ''
  if(restoredOptions) options.value = { ...restoredOptions }
}
function completedGit(result: { appId: string; taskId: string }) {
  if (!user.userLogin.id || !/^[1-9]\d*$/.test(result.appId)) return
  void router.push({ name: 'app-chat', params: { id: result.appId } })
}
function invalidateGit() {
  files.value = []; selectedFiles.clear(); folderName.value = ''; appName.value = ''; state.value = 'select'
  error.value = ''; notice.value = ''
}
async function resetSelection() {
  try { if(sourceKind.value === 'git') await gitSource.value?.clear(); resetImport() }
  catch(cause) { error.value = cause instanceof Error ? cause.message : '停止未确认。' }
}
async function switchSource(event: { target?: { value?: unknown } }) {
  const next=event.target?.value
  if((next !== 'folder' && next !== 'git') || busy.value || sourceKind.value === next) return
  try { if(sourceKind.value === 'git') await gitSource.value?.clear(); resetImport(); sourceKind.value = next }
  catch(cause) { error.value = cause instanceof Error ? cause.message : '停止未确认。' }
}

async function savePreferences() {
  if (busy.value || !user.userLogin.id) return
  const current = ++generation
  const ownerId = String(user.userLogin.id)
  const controller = new AbortController()
  const value = { ...options.value }
  pending = controller
  saving.value = true
  notice.value = ''
  error.value = ''
  try {
    await saveImportPreferences(value, controller.signal, ownerId)
    if (current !== generation || controller.signal.aborted || String(user.userLogin.id) !== ownerId) return
    savedOptions.value = value
    notice.value = '偏好已保存。'
  } catch (cause) {
    if (current === generation && !controller.signal.aborted) error.value = cause instanceof Error ? cause.message : '偏好保存失败，请重试。'
  } finally {
    if (current === generation) { saving.value = false; pending = undefined }
  }
}

watch(() => user.userLogin.id, (id, previous) => {
  if (id === previous) return
  savedOptions.value = defaultOptions()
  resetImport()
  if (!id) void router.replace({ path: '/user/login', query: { redirect: '/project/import' } })
  else {
    try { sourceKind.value = pendingSourceRequest(String(id)) ? 'git' : 'folder' }
    catch(cause) { error.value = cause instanceof Error ? cause.message : '来源记录无法读取。' }
    void loadPreferences(String(id))
  }
}, { immediate: true })

async function loadPreferences(ownerId: string) {
  const current = ++generation
  const controller = new AbortController()
  pending = controller
  saving.value = true
  preferenceReady.value = false
  try {
    const saved = await loadImportPreferences(controller.signal)
    if (current !== generation || String(user.userLogin.id) !== ownerId) return
    savedOptions.value = { ...saved }
    options.value = { ...saved }
    preferenceReady.value = true
  } catch {
    if (current === generation) error.value = '无法读取导入偏好，请刷新后重试。'
  } finally {
    if (current === generation) { saving.value = false; pending = undefined }
  }
}
onBeforeUnmount(resetImport)
</script>

<template>
  <ProjectImportForm v-model:app-name="appName" v-model:options="options" :files="files"
    :folder-name="folderName" :manifest="manifest" :state="state" :busy="busy" :can-submit="canSubmit"
    :error="error" :notice="notice" :stage-label="saving ? '正在保存…' : sourceKind === 'git' ? gitStageLabel : '正在提交…'"
    @select-folder="selectFolder" @save-preferences="savePreferences" @start-import="startImport"
    @cancel-import="cancelImport" @reset-import="resetSelection">
    <template #source-switch>
      <a-radio-group :value="sourceKind" :disabled="busy" @change="switchSource"><a-radio-button value="folder">文件夹</a-radio-button><a-radio-button value="git">Git 仓库</a-radio-button></a-radio-group>
    </template>
    <template v-if="sourceKind === 'git'" #source>
      <GitProjectSource ref="gitSource" :busy="busy" :options="options" @select="selectGit" @completed="completedGit" @invalidate="invalidateGit" @working="(value, stage) => {gitWorking = value; gitStageLabel = stage ?? ''}" @failure="value => error = value" />
    </template>
  </ProjectImportForm>
</template>

<style scoped>
.project-import-form { padding: 8px 0 32px; }
</style>
