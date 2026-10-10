<script setup lang="ts">
import { computed, ref } from 'vue'
import { Button as AButton, Input as AInput, InputPassword as AInputPassword, Select as ASelect, Modal as AModal, Alert as AAlert, Popconfirm as APopconfirm, Popover as APopover } from 'ant-design-vue'
import type { FileMetadata } from './manifest'
defineProps<{ busy: boolean }>()
const emit = defineEmits<{ select: [files: FileMetadata[], name: string]; invalidate: [] }>()
const provider = ref('GitHub'), url = ref(''), credential = ref('public'), branch = ref<string>()
const branches = ref<string[]>([]), loading = ref(false), error = ref(''), manager = ref(false)
const credentials = ref<Array<{ id: string; provider: string; name: string; version: number }>>([])
const editor = ref(false), editing = ref<string>(), name = ref(''), token = ref('')
const editorMode = ref<'add' | 'replace' | 'note'>('add')
let sourceGeneration = 0
const visibleCredentials = computed(() => credentials.value.filter(row => row.provider === provider.value))
const credentialOptions = computed(() => [{ value: 'public', label: '公开仓库' }, ...visibleCredentials.value.map(row => ({ value: row.id, label: row.name }))])
const repositoryName = ref('')
function invalidate() { sourceGeneration++; branch.value = undefined; branches.value = []; loading.value = false; emit('invalidate') }
async function readBranches() {
  error.value = ''; invalidate()
  const current = sourceGeneration
  try {
    const parsed = new URL(url.value)
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || parsed.search || parsed.hash ||
      parsed.hostname !== 'github.com' || !/^\/[\w.-]+\/[\w.-]+(?:\.git)?\/?$/.test(parsed.pathname)) throw new Error()
    repositoryName.value = parsed.pathname.split('/')[2]!.replace(/\.git$/, '')
  } catch { error.value = '请输入对应平台的 HTTPS 仓库地址。'; return }
  loading.value = true
  await new Promise(resolve => setTimeout(resolve, 350))
  if (current !== sourceGeneration) return
  branches.value = ['main', 'develop', 'feature/customer']
  loading.value = false
}
function selectBranch(value: string) {
  branch.value = value
  emit('select', [{ path: 'package.json', size: 804 }, { path: 'src/App.vue', size: 1260 }, { path: 'README.md', size: 412 },
    { path: 'node_modules/pkg/index.js', size: 1358 }, { path: 'dist/index.html', size: 882 }, { path: '.env', size: 124 }, { path: '.env.example', size: 52 }], repositoryName.value)
}
function edit(id?: string, mode: 'replace' | 'note' = 'replace') {
  editorMode.value = id ? mode : 'add'
  editing.value = id; name.value = id ? credentials.value.find(row => row.id === id)!.name : ''
  token.value = ''; editor.value = true
}
function save() {
  if (!name.value.trim() || (editorMode.value !== 'note' && !token.value.trim())) return
  if (editing.value) {
    const row = credentials.value.find(row => row.id === editing.value)!
    if (editorMode.value === 'note') row.name = name.value.trim()
    else { row.version++; if (credential.value === row.id) invalidate() }
  } else credentials.value.push({ id: crypto.randomUUID(), provider: provider.value, name: name.value.trim(), version: 1 })
  // 隔离预览只保留配置状态，不保存秘密、不请求平台。
  token.value = ''; editor.value = false
}
function remove(id: string) {
  credentials.value = credentials.value.filter(row => row.id !== id)
  if (credential.value === id) { credential.value = 'public'; invalidate() }
}
</script>
<template>
  <div class="git-source">
    <span aria-label="仓库平台">GitHub</span>
    <a-input v-model:value="url" aria-label="仓库地址" placeholder="HTTPS 仓库地址" :disabled="busy" @change="invalidate" />
    <div class="credential-row"><a-select v-model:value="credential" aria-label="仓库凭据" :disabled="busy" :options="credentialOptions" @change="invalidate" /><a-button @click="manager = true">管理凭据</a-button></div>
    <a-button :loading="loading" :disabled="!url.trim() || busy" @click="readBranches">读取分支</a-button>
    <a-select v-if="branches.length" :value="branch" aria-label="导入分支" placeholder="选择分支" :disabled="busy" :options="branches.map(value => ({value}))" @change="value => selectBranch(String(value))" />
    <a-alert v-if="error" type="error" :message="error" show-icon />
  </div>
  <a-modal v-model:open="manager" title="仓库凭据" :footer="null">
    <div class="credential-list">
      <div v-for="row in visibleCredentials" :key="row.id" class="credential-item"><span>{{ row.name }}</span><div><a-button size="small" @click="edit(row.id, 'note')">备注</a-button><a-button size="small" @click="edit(row.id)">替换</a-button><a-popconfirm title="删除并取消相关导入？" ok-text="删除" cancel-text="保留" @confirm="remove(row.id)"><a-button size="small" danger>删除</a-button></a-popconfirm></div></div>
      <a-button type="primary" @click="edit()">添加</a-button>
    </div>
  </a-modal>
  <a-modal v-model:open="editor" :title="editorMode === 'note' ? '修改备注' : editing ? '替换 Token' : '添加 Token'" :ok-text="editorMode === 'replace' ? '替换' : '保存'" cancel-text="取消" :ok-button-props="{disabled: !name.trim() || (editorMode !== 'note' && !token.trim())}" @ok="save" @cancel="token = ''">
    <div class="credential-editor">
      <a-alert v-if="editorMode === 'replace'" type="warning" show-icon message="使用旧 Token 的未完成导入将停止。" />
      <label>备注<a-input v-model:value="name" :maxlength="40" :disabled="editorMode === 'replace'" /></label>
      <div v-if="editorMode !== 'note'" class="token-field"><span class="token-label"><label for="preview-credential-token">Token</label><a-popover trigger="click"><template #content><a href="https://github.com/settings/personal-access-tokens/new" target="_blank" rel="noopener noreferrer">打开 GitHub</a></template><button type="button" class="token-help" aria-label="获取 Token">?</button></a-popover></span><a-input-password id="preview-credential-token" v-model:value="token" autocomplete="off" /></div>
    </div>
  </a-modal>
</template>
<style scoped>
.git-source,.credential-list,.credential-editor { display:flex; flex-direction:column; gap:16px; }
.git-source { padding:4px 0 8px; }
.credential-row,.credential-item { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.credential-row :deep(.ant-select) { flex:1; min-width:0; }
.credential-item { padding:12px 0; border-bottom:1px solid var(--border-color); }
.credential-item>div { display:flex; gap:8px; }
.credential-editor label { display:flex; flex-direction:column; gap:8px; }
  .token-label { display:flex; align-items:center; gap:8px; }
  .token-field { display:flex; flex-direction:column; gap:8px; }
.token-help { width:18px; height:18px; padding:0; border:1px solid var(--border-color); border-radius:50%; background:transparent; color:var(--text-muted); font-size:12px; cursor:pointer; }
</style>
