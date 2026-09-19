<template>
  <a-modal
    :open="open"
    title="项目文件"
    :footer="null"
    width="1180px"
    :mask-closable="false"
    @cancel="requestClose"
  >
    <!-- 未保存草稿的确认条：关闭前必须显式选择保存 / 放弃 / 取消 -->
    <div v-if="pendingClose" class="pf-confirm-bar">
      <span class="pf-confirm-text">有未保存的修改</span>
      <a-button size="small" type="primary" :loading="saving" @click="saveThenClose">保存并关闭</a-button>
      <a-button size="small" danger @click="discardThenClose">放弃修改</a-button>
      <a-button size="small" @click="pendingClose = false">取消</a-button>
    </div>

    <div class="pf-body">
      <!-- 左：按需展开的文件树 -->
      <div class="pf-tree">
        <div class="pf-tree-head">
          <span class="pf-root">项目根</span>
          <a-button size="small" type="link" :loading="loadingRoot" @click="reloadTree">刷新</a-button>
        </div>
        <a-spin :spinning="loadingRoot">
          <a-tree
            v-if="treeData.length > 0"
            :tree-data="treeData"
            :load-data="onLoadData"
            :selected-keys="selectedKeys"
            @select="onSelectNode"
          />
          <a-empty v-else description="暂无可展示的文件" />
        </a-spin>
      </div>

      <!-- 右：查看 / 编辑 -->
      <div class="pf-viewer">
        <template v-if="activeEntry">
          <div class="pf-viewer-head">
            <span class="pf-path" :title="activeEntry.path">{{ activeEntry.path }}</span>
            <span v-if="dirty" class="pf-dirty">未保存</span>
            <a-button
              size="small"
              type="primary"
              :disabled="!dirty || !canEdit"
              :loading="saving"
              @click="handleSave"
            >
              保存
            </a-button>
          </div>

          <a-spin :spinning="loadingFile">
            <div v-if="fileError" class="pf-hint pf-hint--error">{{ fileError }}</div>

            <template v-else-if="fileInfo">
              <template v-if="fileInfo.isText">
                <a-textarea
                  v-model:value="draft"
                  class="pf-editor"
                  :disabled="!canEdit"
                  :rows="20"
                  spellcheck="false"
                />
                <div v-if="generating" class="pf-hint">AI 正在写入该项目，暂不能保存</div>
                <div v-else-if="readOnly" class="pf-hint">当前为只读访问，不能保存</div>
              </template>

              <!-- 二进制文件不强行作为文本打开，只给基本信息与下载 -->
              <div v-else class="pf-binary">
                <p class="pf-binary-name">{{ activeEntry.name }}</p>
                <p class="pf-binary-meta">二进制文件 · {{ formatSize(fileInfo.size) }}</p>
                <a-button type="primary" @click="handleDownload">下载</a-button>
              </div>
            </template>
          </a-spin>
        </template>

        <a-empty v-else class="pf-empty" description="从左侧选择一个文件" />
      </div>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
// 项目没有全局注册 ant-design-vue 组件（main.ts 只 use(pinia/router)），
// 模板里的 <a-xxx> 必须靠这里的命名 import 才能解析，否则会被当成未知 HTML 标签原样输出
import {
  Button as AButton,
  Empty as AEmpty,
  Modal as AModal,
  Spin as ASpin,
  Textarea as ATextarea,
  Tree as ATree,
  message,
} from 'ant-design-vue'
import {
  buildProjectFileDownloadUrl,
  isProjectFileConflict,
  loadProjectFileContent,
  loadProjectTree,
  saveProjectFile,
  type ProjectFileContent,
  type ProjectTreeEntry,
} from '@/utils/projectFilesApi'

const props = withDefaults(
  defineProps<{
    open: boolean
    appId: string
    apiBaseUrl?: string
    /** 只读访问（访客） */
    readOnly?: boolean
    /** AI 正在写入该项目，期间禁止保存 */
    generating?: boolean
  }>(),
  { apiBaseUrl: '/api', readOnly: false, generating: false },
)

const emit = defineEmits<{
  (e: 'update:open', value: boolean): void
}>()

interface TreeNodeData {
  key: string
  /** 树组件使用的标题 */
  title: string
  /** 条目名，与 title 相同，供查看区展示 */
  name: string
  path: string
  type: 'dir' | 'file'
  isLeaf: boolean
  children?: TreeNodeData[]
}

const treeData = ref<TreeNodeData[]>([])
const selectedKeys = ref<string[]>([])
const loadingRoot = ref(false)
const loadingFile = ref(false)
const saving = ref(false)

const activeEntry = ref<TreeNodeData | null>(null)
const fileInfo = ref<ProjectFileContent | null>(null)
const fileError = ref('')
const draft = ref('')
const dirty = ref(false)
const pendingClose = ref(false)

const canEdit = computed(
  () => !props.readOnly && !props.generating && !!fileInfo.value?.isText,
)

/** 草稿与磁盘内容不一致才算脏，改回原样会自动清掉标记 */
watch(draft, () => {
  if (!fileInfo.value?.isText) return
  dirty.value = (fileInfo.value.content ?? '') !== draft.value
})

watch(
  () => props.open,
  (opened) => {
    if (opened) {
      pendingClose.value = false
      resetViewer()
      void loadRoot()
    }
  },
  // 挂载时就处于打开状态也要加载，否则树一直是空的
  { immediate: true },
)

function toNode(entry: ProjectTreeEntry): TreeNodeData {
  return {
    key: entry.path,
    title: entry.name,
    name: entry.name,
    path: entry.path,
    type: entry.type,
    isLeaf: entry.type !== 'dir',
  }
}

function resetViewer() {
  selectedKeys.value = []
  activeEntry.value = null
  fileInfo.value = null
  fileError.value = ''
  draft.value = ''
  dirty.value = false
}

async function loadRoot() {
  loadingRoot.value = true
  try {
    const entries = await loadProjectTree({ appId: props.appId, apiBaseUrl: props.apiBaseUrl })
    treeData.value = entries.map(toNode)
  } catch (e) {
    message.error((e as Error).message || '读取文件树失败')
    treeData.value = []
  } finally {
    loadingRoot.value = false
  }
}

/** 展开目录时才取子项 —— 依赖、构建产物这类大目录因此不会在打开窗口时被一次性加载 */
async function onLoadData(node: unknown): Promise<void> {
  const data = (node as { dataRef?: TreeNodeData } | undefined)?.dataRef
  if (!data || data.type !== 'dir' || data.children) return
  const entries = await loadProjectTree({
    appId: props.appId,
    path: data.path,
    apiBaseUrl: props.apiBaseUrl,
  })
  data.children = entries.map(toNode)
}

function onSelectNode(keys: (string | number)[], info: unknown): void {
  const data = (info as { node?: { dataRef?: TreeNodeData } } | undefined)?.node?.dataRef
  if (!data || data.type === 'dir') return
  if (dirty.value) {
    message.warning('当前文件有未保存的修改，请先保存或放弃后再切换')
    return
  }
  selectedKeys.value = keys.map(String)
  void openFile(data)
}

async function openFile(entry: TreeNodeData) {
  loadingFile.value = true
  fileError.value = ''
  fileInfo.value = null
  activeEntry.value = entry
  try {
    const info = await loadProjectFileContent({
      appId: props.appId,
      path: entry.path,
      apiBaseUrl: props.apiBaseUrl,
    })
    fileInfo.value = info
    draft.value = info.content ?? ''
    dirty.value = false
  } catch (e) {
    fileError.value = (e as Error).message || '读取文件失败'
  } finally {
    loadingFile.value = false
  }
}

async function handleSave(): Promise<boolean> {
  if (!activeEntry.value || !canEdit.value || !dirty.value) return false
  saving.value = true
  try {
    const saved = await saveProjectFile({
      appId: props.appId,
      path: activeEntry.value.path,
      content: draft.value,
      baseVersion: fileInfo.value?.version,
      apiBaseUrl: props.apiBaseUrl,
    })
    fileInfo.value = saved
    draft.value = saved.content ?? draft.value
    dirty.value = false
    message.success('已保存')
    return true
  } catch (e) {
    if (isProjectFileConflict(e)) {
      // 保留草稿不覆盖：让用户重新读取后再决定怎么合
      message.error('文件已被修改（可能是 AI 写入），请重新打开该文件读取最新内容后再保存')
    } else {
      message.error((e as Error).message || '保存失败')
    }
    return false
  } finally {
    saving.value = false
  }
}

function handleDownload() {
  if (!activeEntry.value) return
  const link = document.createElement('a')
  link.href = buildProjectFileDownloadUrl({
    appId: props.appId,
    path: activeEntry.value.path,
    apiBaseUrl: props.apiBaseUrl,
  })
  link.download = activeEntry.value.name
  document.body.appendChild(link)
  link.click()
  link.remove()
}

function requestClose() {
  if (dirty.value) {
    pendingClose.value = true
    return
  }
  closeNow()
}

function closeNow() {
  pendingClose.value = false
  emit('update:open', false)
}

async function saveThenClose() {
  const ok = await handleSave()
  if (ok) closeNow()
}

function discardThenClose() {
  dirty.value = false
  closeNow()
}

function reloadTree() {
  treeData.value = []
  void loadRoot()
}

function formatSize(size?: number | null): string {
  if (size == null) return '-'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/** 供父组件在关闭窗口、发起 AI 改码前判断并处理未保存草稿 */
defineExpose({
  hasUnsavedChanges: () => dirty.value,
  /** 保存当前草稿；返回是否保存成功（版本冲突时为 false） */
  save: handleSave,
  /** 丢弃草稿，编辑器回到磁盘上的内容 */
  discardDraft: () => {
    draft.value = fileInfo.value?.content ?? ''
    dirty.value = false
  },
  /** 显示「保存 / 放弃 / 取消」确认条（父组件在发起 AI 改码前调用） */
  promptUnsavedChoice: () => {
    emit('update:open', true)
    pendingClose.value = true
  },
  /**
   * 按路径打开某个文件（图谱里点「打开源码」走这里）。
   *
   * 直接按路径读内容，不依赖文件树是否已展开 —— 树是惰性加载的，
   * 深层文件这时多半还没出现在树里。树上的选中态因此可能不同步，
   * 但内容是对的，这比「点了没反应」好。
   *
   * 有未保存草稿时不会静默覆盖：提示后返回 false。
   */
  openFileByPath: async (path: string): Promise<boolean> => {
    if (dirty.value) {
      message.warning('当前文件有未保存的修改，请先保存或放弃后再跳转')
      return false
    }
    const name = path.replace(/\\/g, '/').split('/').pop() ?? path
    selectedKeys.value = [path]
    await openFile({ key: path, title: name, name, path, type: 'file', isLeaf: true })
    return true
  },
})
</script>

<style scoped>
.pf-confirm-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  margin-bottom: 12px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 6px;
}

.pf-confirm-text {
  flex: 1;
  color: #874d00;
  font-size: 13px;
}

.pf-body {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 12px;
  height: max(720px, 82dvh);
}

.pf-tree {
  border: 1px solid var(--border-color, #f0f0f0);
  border-radius: 6px;
  padding: 8px;
  overflow: auto;
  min-height: 0;
}

.pf-tree-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
  margin-bottom: 6px;
}

.pf-root {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-base, #262626);
}

.pf-viewer {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--border-color, #f0f0f0);
  border-radius: 6px;
  padding: 8px;
  min-width: 0;
}

.pf-viewer-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-bottom: 6px;
  border-bottom: 1px solid var(--border-color, #f0f0f0);
  margin-bottom: 8px;
}

.pf-path {
  flex: 1;
  font-size: 13px;
  color: var(--text-secondary, #595959);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pf-dirty {
  font-size: 12px;
  color: #d46b08;
  background: #fff7e6;
  border: 1px solid #ffd591;
  border-radius: 4px;
  padding: 0 6px;
}

.pf-viewer > :deep(.ant-spin-nested-loading) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pf-viewer :deep(.ant-spin-container) {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.pf-editor {
  flex: 1;
  min-height: 0;
  resize: none;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
}

.pf-hint {
  margin-top: 8px;
  font-size: 12px;
  color: #8c8c8c;
}

.pf-hint--error {
  color: #cf1322;
}

.pf-binary {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 320px;
}

.pf-binary-name {
  font-size: 14px;
  font-weight: 600;
  margin: 0;
}

.pf-binary-meta {
  font-size: 12px;
  color: #8c8c8c;
  margin: 0;
}

.pf-empty {
  margin-top: 160px;
}
</style>
