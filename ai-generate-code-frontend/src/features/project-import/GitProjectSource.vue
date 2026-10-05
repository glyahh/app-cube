<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Button as AButton, Input as AInput, InputPassword as AInputPassword, Select as ASelect, Modal as AModal, Alert as AAlert, Popconfirm as APopconfirm, Popover as APopover } from 'ant-design-vue'
import { UserLoginStore } from '@/stores/UserLogin'
import { loadCredentials, createCredential, renameCredential, replaceCredential, deleteCredential, type CredentialView, type CredentialContext } from './credentials'
import { prepareGit, selectGitBranch, getGitMain, getGitByRequest, getGitResult, rebuildGit, commitGit, cancelGit, waitGit, repository, type GitSourceView } from './git'
import { pendingSourceRequest, beginSourceRequest, finishSourceRequest } from './sourceRequest'
import type { FileMetadata, ImportOptions } from './manifest'

const props = defineProps<{ busy: boolean; options: ImportOptions }>()
const emit = defineEmits<{ select: [files: FileMetadata[], name: string, options?: ImportOptions]; completed: [result: {appId:string;taskId:string}]; invalidate: []; working: [busy: boolean, stage?: string]; failure: [message: string] }>()
const user = UserLoginStore()
const provider = ref<CredentialView['provider']>('GitHub'), url = ref(''), credential = ref<string | undefined>('public')
const task = ref<GitSourceView>(), loading = ref(false), error = ref(''), manager = ref(false), credentials = ref<CredentialView[]>([])
const credentialsReady = ref(false), credentialSaving = ref(false), editor = ref(false), editing = ref<CredentialView>()
const mode = ref<'add' | 'replace' | 'note'>('add'), name = ref(''), token = ref(''), credentialError = ref('')
const visibleCredentials = computed(() => credentials.value.filter(row => row.provider === provider.value))
const choices = computed(() => [{ value: 'public', label: '公开仓库' }, ...visibleCredentials.value.map(row => ({ value: row.id, label: row.name }))])
let generation = 0
let pending: AbortController | undefined
let credentialGeneration = 0
let credentialPending: AbortController | undefined
let creating: Promise<GitSourceView> | undefined
let creationUnconfirmed = false
let restored = false
const message = (cause: unknown) => cause instanceof Error ? cause.message : '操作失败，请重试。'

function sourceContext(): CredentialContext {
  const current = ++generation, ownerId = String(user.userLogin.id), controller = new AbortController()
  pending?.abort(); pending = controller
  return { ownerId, signal: controller.signal, isCurrent: () => current === generation && String(user.userLogin.id) === ownerId }
}
function credentialContext(): CredentialContext {
  const current = ++credentialGeneration, ownerId = String(user.userLogin.id), controller = new AbortController()
  credentialPending?.abort(); credentialPending = controller
  return { ownerId, signal: controller.signal, isCurrent: () => current === credentialGeneration && String(user.userLogin.id) === ownerId }
}
function working(value: boolean,stage?: string) { loading.value = value; emit('working', value,stage) }
function invalidate() { emit('invalidate') }

async function refreshCredentials() {
  const context = credentialContext(); credentialSaving.value = true; credentialError.value = ''
  try { credentials.value = await loadCredentials(context); credentialsReady.value = true }
  catch(cause) { if(context.isCurrent()) { credentialsReady.value = false; credentialError.value = message(cause) } }
  finally { if(context.isCurrent()) credentialSaving.value = false }
}
async function clear() {
  const ownerId = String(user.userLogin.id)
  // 创建结果到达前先等待已发出的请求，取得真实任务 ID 后才能向后端取消。
  if(creating) {
    try {
      const created = await creating
      if(String(user.userLogin.id) !== ownerId) throw new DOMException('登录状态已变化','AbortError')
      task.value = created; creationUnconfirmed = false
    } catch(cause) {
      if(String(user.userLogin.id) !== ownerId) throw new DOMException('登录状态已变化','AbortError')
      if(!(cause instanceof Error && cause.name === 'SourceCreationRejected')) creationUnconfirmed = true
    }
  }
  const context = sourceContext(); working(true,'正在取消…')
  try {
    const requestId = pendingSourceRequest(ownerId)
    if(!task.value && requestId) task.value = await getGitByRequest(context,requestId)
    if(creationUnconfirmed && !task.value) throw new Error('来源创建结果未确认，暂勿重复导入。')
    if(task.value && task.value.status !== 'SUCCEEDED') await cancelGit(context,task.value)
    if(requestId) finishSourceRequest(ownerId,requestId)
    creationUnconfirmed = false
    task.value = undefined; error.value = ''; invalidate()
  } catch(cause) { if(context.isCurrent()) { error.value = message(cause); emit('failure',error.value) } throw cause }
  finally { if(context.isCurrent()) working(false) }
}
async function changed() {
  if (!task.value) { invalidate(); return }
  try { await clear() } catch { /* 保留未确认停止的任务，不继续创建另一来源。 */ }
}
async function readBranches() {
  if(loading.value || !credentialsReady.value || !credential.value) return
  try { await clear() } catch { return }
  const context = sourceContext(); working(true,'正在读取分支…'); error.value = ''
  try {
    const selected = credential.value === 'public' ? undefined : credentials.value.find(row => row.id === credential.value)
    if(credential.value !== 'public' && !selected) throw new Error('凭据已变化，请重新选择。')
    repository(provider.value,url.value)
    const requestId = beginSourceRequest(context.ownerId)
    creationUnconfirmed = true
    const creation = prepareGit(context,provider.value,url.value,props.options,selected,requestId)
    creating = creation
    try { const created = await creation; if(!context.isCurrent()) return; task.value = created; creationUnconfirmed = false }
    catch(cause) {
      if(!context.isCurrent()) throw cause
      if(cause instanceof Error && cause.name === 'SourceCreationRejected') {
        creationUnconfirmed = false; finishSourceRequest(context.ownerId,requestId); throw cause
      }
      task.value = await getGitByRequest(context,requestId); creationUnconfirmed = false
    }
    finally { if(creating === creation) creating = undefined }
    task.value = await waitGit(context,task.value)
  } catch(cause) { if(context.isCurrent()) { error.value = creationUnconfirmed && !task.value ? '来源创建结果未确认，暂勿重复导入。' : message(cause); emit('failure',error.value) } }
  finally { if(context.isCurrent()) working(false) }
}
async function selectBranch(value: string) {
  if(!task.value || loading.value) return
  const context = sourceContext(); working(true,'正在读取文件…'); error.value = ''; invalidate()
  try {
    task.value = await selectGitBranch(context,task.value,value)
    task.value = await waitGit(context,task.value)
    const main = await getGitMain(context,task.value)
    emit('select',[...main.manifest.included,...main.manifest.excluded],main.appName)
  } catch(cause) { if(context.isCurrent()) { error.value = message(cause); emit('failure',error.value) } }
  finally { if(context.isCurrent()) working(false) }
}
async function submit(appName: string, scope: ImportOptions) {
  if(!task.value) throw new Error('请先选择分支。')
  const context = sourceContext(); working(true,'正在提交…'); error.value = ''
  try {
    if(task.value.definition.appName !== appName.trim() || Object.keys(scope).some(key => scope[key as keyof ImportOptions] !== task.value!.definition.options[key as keyof ImportOptions])) {
      task.value = await rebuildGit(context,task.value,appName,scope)
      working(true,'正在重建范围…')
      task.value = await waitGit(context,task.value)
    }
    working(true,'正在提交…')
    const result = await commitGit(context,task.value,appName,scope)
    task.value = { ...task.value,status: 'SUCCEEDED' }
    const requestId = pendingSourceRequest(context.ownerId)
    if(requestId) finishSourceRequest(context.ownerId,requestId)
    return result
  } catch(cause) { if(context.isCurrent()) error.value = message(cause); throw cause }
  finally { if(context.isCurrent()) working(false) }
}
function edit(row?: CredentialView, operation: 'replace' | 'note' = 'replace') {
  mode.value = row ? operation : 'add'; editing.value = row; name.value = row?.name ?? ''; token.value = ''; credentialError.value = ''; editor.value = true
}
async function saveCredential() {
  const context = credentialContext(); credentialSaving.value = true; credentialError.value = ''
  const secret = token.value; token.value = ''
  try {
    if(mode.value === 'add') credentials.value.push(await createCredential(context,provider.value,name.value,secret))
    else if(mode.value === 'note') {
      const updated = await renameCredential(context,editing.value!,name.value)
      credentials.value = credentials.value.map(row => row.id === updated.id ? updated : row)
    } else applyChange(await replaceCredential(context,editing.value!,secret))
    editor.value = false
  } catch(cause) { if(context.isCurrent()) credentialError.value = message(cause) }
  finally { if(context.isCurrent()) credentialSaving.value = false }
}
function applyChange(result: Awaited<ReturnType<typeof replaceCredential>>) {
  credentials.value = credentials.value.map(row => row.id === result.credential.id ? result.credential : row).filter(row => row.state === 'ACTIVE')
  const related = result.stops.find(stop => stop.taskId === task.value?.taskId)
  if(related) {
    generation++; pending?.abort(); working(false); invalidate()
    if(related.status === 'CANCELLED' && related.cleanup === 'DONE') {
      const requestId = pendingSourceRequest(String(user.userLogin.id))
      if(requestId) finishSourceRequest(String(user.userLogin.id),requestId)
      task.value = undefined
    }
    else { error.value = '凭据已撤销，任务停止或清理未确认。'; emit('failure',error.value) }
  }
  if(credential.value === result.credential.id && result.credential.state === 'DELETED') credential.value = undefined
  if(result.stops.some(stop => stop.status !== 'CANCELLED' || stop.cleanup !== 'DONE')) throw new Error('凭据已更新，部分任务停止或清理未确认。')
}
async function remove(row: CredentialView) {
  const context = credentialContext(); credentialSaving.value = true; credentialError.value = ''
  try { applyChange(await deleteCredential(context,row)) }
  catch(cause) { if(context.isCurrent()) credentialError.value = message(cause) }
  finally { if(context.isCurrent()) credentialSaving.value = false }
}
async function restoreSource() {
  if(restored || props.busy || !credentialsReady.value || !user.userLogin.id) return
  restored = true
  const context = sourceContext()
  try {
    const requestId = pendingSourceRequest(context.ownerId)
    if(!requestId) return
    working(true,'正在读取任务…')
    creationUnconfirmed = true
    task.value = await getGitByRequest(context,requestId); creationUnconfirmed = false
    provider.value = task.value.definition.source.provider; url.value = task.value.definition.source.cloneUrl
    credential.value = task.value.credentialId === null ? 'public' : credentials.value.find(row => row.id === task.value!.credentialId && row.version === task.value!.credentialVersion)?.id
    if(task.value.status === 'CANCELLED' && task.value.cleanup === 'DONE') {
      finishSourceRequest(context.ownerId,requestId); task.value = undefined; return
    }
    task.value = await waitGit(context,task.value)
    if(task.value.status === 'SUCCEEDED') {
      const result = await getGitResult(context,task.value)
      finishSourceRequest(context.ownerId,requestId); emit('completed',result)
    } else if(task.value.status === 'WAITING_CONFIRMATION') {
      const main = await getGitMain(context,task.value)
      emit('select',[...main.manifest.included,...main.manifest.excluded],main.appName,task.value.definition.options)
    }
  } catch(cause) { if(context.isCurrent()) { error.value = message(cause); emit('failure',error.value) } }
  finally { if(context.isCurrent()) working(false) }
}
watch([() => props.busy, credentialsReady], () => { void restoreSource() })
watch(() => user.userLogin.id, () => {
  generation++; credentialGeneration++; pending?.abort(); credentialPending?.abort(); task.value = undefined
  creating = undefined; creationUnconfirmed = false
  restored = false
  credentials.value = []; credentialsReady.value = false; token.value = ''; editor.value = false; manager.value = false; working(false); invalidate()
  if(user.userLogin.id) void refreshCredentials()
},{immediate:true})
onBeforeUnmount(() => {
  const previous = task.value, ownerId = String(user.userLogin.id)
  generation++; credentialGeneration++; pending?.abort(); credentialPending?.abort(); token.value = ''
  if(previous && previous.status !== 'SUCCEEDED') {
    const controller = new AbortController()
    const requestId = pendingSourceRequest(ownerId)
    void cancelGit({ ownerId,signal:controller.signal,isCurrent:()=>String(user.userLogin.id)===ownerId },previous)
      .then(() => { if(requestId) finishSourceRequest(ownerId,requestId) })
      .catch(() => emit('failure','离开页面时停止未确认，请查看导入任务。'))
  }
})
defineExpose({ submit,clear })
</script>

<template>
  <div class="git-source">
    <a-select v-model:value="provider" aria-label="仓库平台" :disabled="busy" :options="[{value:'GitHub'}, {value:'Gitee'}]" @change="credential = 'public'; changed()" />
    <a-input v-model:value="url" aria-label="仓库地址" placeholder="HTTPS 仓库地址" :disabled="busy" @change="changed" />
    <div class="credential-row"><a-select v-model:value="credential" aria-label="仓库凭据" placeholder="选择凭据" :disabled="busy" :options="choices" @change="changed" /><a-button @click="manager = true">管理凭据</a-button></div>
    <a-button :loading="loading" :disabled="!url.trim() || busy || !credentialsReady || !credential" @click="readBranches">读取分支</a-button>
    <a-select v-if="task?.branches.length" :value="task.branch ?? undefined" aria-label="导入分支" placeholder="选择分支" :disabled="busy || task.status !== 'WAITING_BRANCH'" :options="task.branches.map(item => ({value:item.name}))" @change="value => selectBranch(String(value))" />
    <a-alert v-if="credentialError && !manager && !editor" type="error" :message="credentialError" show-icon />
  </div>
  <a-modal v-model:open="manager" title="仓库凭据" :footer="null">
    <div class="credential-list">
      <a-alert v-if="credentialError" type="error" :message="credentialError" show-icon />
      <div v-for="row in visibleCredentials" :key="row.id" class="credential-item"><span>{{ row.name }}</span><div><a-button size="small" :disabled="credentialSaving" @click="edit(row,'note')">备注</a-button><a-button size="small" :disabled="credentialSaving" @click="edit(row)">替换</a-button><a-popconfirm title="删除并取消相关导入？" ok-text="删除" cancel-text="保留" @confirm="remove(row)"><a-button size="small" danger :disabled="credentialSaving">删除</a-button></a-popconfirm></div></div>
      <div class="credential-actions"><a-button type="primary" :disabled="credentialSaving" @click="edit()">添加</a-button><a-button :loading="credentialSaving" @click="refreshCredentials">刷新</a-button></div>
    </div>
  </a-modal>
  <a-modal v-model:open="editor" :title="mode === 'note' ? '修改备注' : mode === 'replace' ? '替换 Token' : '添加 Token'" :ok-text="mode === 'replace' ? '替换' : '保存'" cancel-text="取消" :confirm-loading="credentialSaving" :ok-button-props="{disabled: credentialSaving || !name.trim() || (mode !== 'note' && !token)}" :cancel-button-props="{disabled:credentialSaving}" :keyboard="!credentialSaving" :closable="!credentialSaving" :mask-closable="!credentialSaving" @ok="saveCredential" @cancel="token = ''">
    <div class="credential-editor">
      <a-alert v-if="credentialError" type="error" :message="credentialError" show-icon />
      <a-alert v-if="mode === 'replace'" type="warning" show-icon message="使用旧 Token 的未完成导入将停止。" />
      <label>备注<a-input v-model:value="name" :maxlength="40" :disabled="mode === 'replace' || credentialSaving" /></label>
      <div v-if="mode !== 'note'" class="token-field"><span class="token-label"><label for="import-credential-token">Token</label><a-popover trigger="click"><template #content><a :href="provider === 'GitHub' ? 'https://github.com/settings/personal-access-tokens/new' : 'https://gitee.com/profile/personal_access_tokens'" target="_blank" rel="noopener noreferrer">打开 {{ provider }}</a></template><button type="button" class="token-help" aria-label="获取 Token">?</button></a-popover></span><a-input-password id="import-credential-token" v-model:value="token" :maxlength="4096" :disabled="credentialSaving" autocomplete="off" /></div>
    </div>
  </a-modal>
</template>
<style scoped>
.git-source,.credential-list,.credential-editor { display:flex; flex-direction:column; gap:16px; }
.git-source { padding:4px 0 8px; }
.credential-row,.credential-item { display:flex; align-items:center; justify-content:space-between; gap:12px; }
.credential-row :deep(.ant-select) { flex:1; min-width:0; }
.credential-item { padding:12px 0; border-bottom:1px solid var(--border-color); }
.credential-item>div,.credential-actions { display:flex; gap:8px; flex-wrap:wrap; }
.credential-editor label,.token-field { display:flex; flex-direction:column; gap:8px; }
.token-label { display:flex; align-items:center; gap:8px; }
.token-help { width:18px; height:18px; padding:0; border:1px solid var(--border-color); border-radius:50%; background:transparent; color:var(--text-muted); font-size:12px; cursor:pointer; }
</style>
