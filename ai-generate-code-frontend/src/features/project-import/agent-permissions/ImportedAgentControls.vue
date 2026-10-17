<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Alert, Button, message } from 'ant-design-vue'
import AgentComposerControls from './AgentComposerControls.vue'
import AgentPermissionCard from './AgentPermissionCard.vue'
import AgentChangeReview from './AgentChangeReview.vue'
import * as api from './api'
import type { AgentChangeCandidate, AgentComposerSettings, AgentFileDiff, AgentModelOption, AgentReviewDecisionEvent, AgentToolDecisionEvent } from './types'

const props = defineProps<{ appId: string; streaming: boolean }>()
const emit = defineEmits<{ blocked: [value: boolean]; merged: [] }>()
const session = ref<api.SessionSettings | null>(null)
const options = ref<AgentModelOption[]>([])
const tool = ref<api.ToolPermission | null>(null)
const review = ref<api.CandidateReview | null>(null)
const state = ref('LOADING')
const saving = ref(false)
const deciding = ref(false)
const error = ref('')
let disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
const settings = computed<AgentComposerSettings>(() => ({ mode: session.value?.mode ?? 'MANUAL', modelId: session.value?.profileId ?? '', effort: session.value?.effort ?? null }))
const candidate = computed<AgentChangeCandidate | null>(() => review.value ? ({
  id: review.value.editId, revision: String(review.value.revision), reviewHash: review.value.hash,
  highRisk: review.value.risk === 'HIGH', files: review.value.body.changes.map(change => ({
    path: change.path, change: change.before === null ? 'ADDED' : change.after === null ? 'DELETED' : 'MODIFIED',
    highRisk: review.value?.risk === 'HIGH', binary: false,
  })),
}) : null)
const diffs = computed<Record<string, AgentFileDiff>>(() => Object.fromEntries((review.value?.body.changes ?? []).map(change => {
  const text = [...(change.beforeText ?? '').split('\n').map(line => `- ${line}`), ...(change.afterText ?? '').split('\n').map(line => `+ ${line}`)].join('\n')
  return [change.path, { status: 'loaded', text, startLine: 1, endLine: text.split('\n').length, totalLines: text.split('\n').length }]
})))
const toolRequest = computed(() => tool.value ? ({ ...tool.value, action: tool.value.display.title, object: tool.value.display.object,
  reason: tool.value.display.reason, detailTitle: '实际操作', revision: String(tool.value.revision) }) : null)

function reportBlocked() {
  emit('blocked', !session.value || !!error.value || state.value !== 'IDLE' || saving.value || deciding.value)
}
async function refresh() {
  const current = await api.active(props.appId)
  const pending = current.permissionId ? await api.permission(props.appId, current.permissionId) : null
  if (disposed) return
  const wasWaiting = state.value === 'WAITING_REVIEW'
  state.value = current.state
  tool.value = pending
  review.value = current.review ?? null
  if (wasWaiting && current.state === 'IDLE' && current.outcome === 'SUCCEEDED') emit('merged')
  reportBlocked()
}
async function poll() {
  try { await refresh() } catch (cause) { if (!disposed) { error.value = (cause as Error).message; reportBlocked() } }
  if (!disposed && !error.value) timer = setTimeout(poll, 2000)
}
async function load() {
  error.value = ''
  emit('blocked', true)
  try {
    const configured = await api.models(props.appId)
    const restored = await api.settings(props.appId)
    if (disposed) return
    options.value = configured.filter(model => model.configured)
    session.value = restored
    await poll()
  } catch (cause) { if (!disposed) { error.value = (cause as Error).message; reportBlocked() } }
}
async function saveSettings(next: AgentComposerSettings) {
  if (!session.value || saving.value || props.streaming || state.value !== 'IDLE') return
  saving.value = true; reportBlocked()
  try { const result = await api.save(props.appId, session.value, next); if (!disposed) session.value = result }
  catch (cause) { message.error((cause as Error).message) }
  finally { saving.value = false; reportBlocked() }
}
async function decideTool(event: AgentToolDecisionEvent) {
  if (!tool.value || deciding.value || event.requestId !== tool.value.id || event.actionHash !== tool.value.actionHash) return
  deciding.value = true; reportBlocked()
  try { await api.decideTool(props.appId, tool.value, event.decision); await refresh() }
  catch (cause) { message.error((cause as Error).message) }
  finally { deciding.value = false; reportBlocked() }
}
async function decideReview(event: AgentReviewDecisionEvent) {
  if (!review.value || deciding.value || event.reviewId !== review.value.editId || event.reviewHash !== review.value.hash) return
  deciding.value = true; reportBlocked()
  try { await api.decideReview(review.value, event.decision); await refresh() }
  catch (cause) { message.error((cause as Error).message) }
  finally { deciding.value = false; reportBlocked() }
}
onMounted(load)
onBeforeUnmount(() => { disposed = true; if (timer) clearTimeout(timer) })
</script>

<template>
  <div class="import-agent-controls">
    <Alert v-if="error" type="error" :message="error" show-icon />
    <Button v-if="error" size="small" @click="load">重新读取</Button>
    <AgentPermissionCard v-if="toolRequest" :request="toolRequest" :busy="deciding" @decision="decideTool" />
    <AgentChangeReview v-if="candidate" :candidate="candidate" :diffs="diffs" :busy="deciding" @decision="decideReview" />
    <AgentComposerControls :settings="settings" :models="options" :busy="streaming || state !== 'IDLE' || !session || !!error" :saving="saving" @change="saveSettings" />
  </div>
</template>

<style scoped>
.import-agent-controls { display: grid; gap: 12px; margin-bottom: 10px; }
</style>
