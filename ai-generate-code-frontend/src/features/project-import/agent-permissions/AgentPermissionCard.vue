<script setup lang="ts">
import { computed } from 'vue'
import { Button } from 'ant-design-vue'
import type { AgentPermissionRequest, AgentToolDecision, AgentToolDecisionEvent } from './types'

const props = withDefaults(
  defineProps<{
    request: AgentPermissionRequest
    busy?: boolean
  }>(),
  { busy: false },
)
const emit = defineEmits<{ decision: [event: AgentToolDecisionEvent] }>()
const lines = computed(() => props.request.detailText.split('\n'))

function decide(decision: AgentToolDecision) {
  if (props.busy) return
  emit('decision', {
    requestId: props.request.id,
    revision: props.request.revision,
    actionHash: props.request.actionHash,
    decision,
  })
}
</script>

<template>
  <article class="approval" data-testid="tool-card">
    <div class="card-heading">
      <h2>允许{{ request.action }}？</h2>
      <span class="object approval-object">{{ request.object }}</span>
      <span v-if="request.highRisk" class="risk">高风险操作</span>
    </div>
    <p v-if="request.highRisk && request.reason" class="approval-reason">{{ request.reason }}</p>
    <details open>
      <summary>{{ request.detailTitle }}</summary>
      <pre><span v-for="(line, index) in lines" :key="index" class="diff-line" :class="{ 'diff-added': line.startsWith('+'), 'diff-removed': line.startsWith('-') }">{{ line }}{{ index < lines.length - 1 ? '\n' : '' }}</span></pre>
    </details>
    <div class="buttons">
      <Button danger :disabled="busy" @click="decide('REJECT')">拒绝并停止本轮</Button>
      <Button
        type="primary"
        :loading="busy"
        :disabled="busy"
        data-testid="approve"
        @click="decide('APPROVE_ONCE')"
        >仅批准本次</Button
      >
      <Button
        :disabled="busy"
        data-testid="approve-similar"
        title="仅本会话内同风险、同目标范围的工具请求；不包含整批合入"
        @click="decide('APPROVE_SIMILAR')"
        >本会话批准同类请求</Button
      >
    </div>
  </article>
</template>

<style scoped>
.approval {
  border-top: 1px solid var(--line, #e5e5e5);
  border-bottom: 1px solid var(--line, #e5e5e5);
  padding: 16px 0;
  display: flex;
  flex-direction: column;
  gap: 8px;
  animation: enter 180ms cubic-bezier(0.16, 1, 0.3, 1);
}
h2,
p {
  margin: 0;
}
h2 {
  font-size: 14px;
  font-weight: 600;
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
  color: var(--muted, #737373);
  min-width: 0;
}
.approval-reason {
  color: var(--muted, #737373);
  font-size: 13px;
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
  border: 1px solid var(--line, #e5e5e5);
  border-radius: 4px;
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin: 4px 0 0;
  padding: 8px 0;
  /* 长 JSON 单行折行后会把卡片撑满整屏，按钮被挤出视口。
     限制高度并在卡片内滚动，保证决定按钮始终可见。 */
  max-height: 240px;
  overflow: auto;
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
:deep(.ant-btn:focus-visible) {
  outline: 2px solid #2563eb;
  outline-offset: 4px;
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
@media (max-width: 767px) {
  .buttons :deep(.ant-btn) {
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
