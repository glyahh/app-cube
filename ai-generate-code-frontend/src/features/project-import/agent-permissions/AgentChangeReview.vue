<script setup lang="ts">
import { computed, watch } from 'vue'
import { Button } from 'ant-design-vue'
import type {
  AgentChangeCandidate,
  AgentFileDiff,
  AgentReviewDecisionEvent,
  AgentReviewFile,
  AgentReviewFileEvent,
} from './types'

const props = withDefaults(
  defineProps<{
    candidate: AgentChangeCandidate
    diffs: Record<string, AgentFileDiff>
    busy?: boolean
  }>(),
  { busy: false },
)
const emit = defineEmits<{
  decision: [event: AgentReviewDecisionEvent]
  loadFile: [event: AgentReviewFileEvent]
}>()
const alreadyApplied = computed(() => props.candidate.applicationMode === 'APPLIED')
const changeLabels = { ADDED: '新增', MODIFIED: '修改', DELETED: '删除' }

function identity() {
  return {
    reviewId: props.candidate.id,
    revision: props.candidate.revision,
    reviewHash: props.candidate.reviewHash,
  }
}

function fileDiff(path: string): AgentFileDiff {
  return props.diffs[path] ?? { status: 'unloaded' }
}

function loadFile(file: AgentReviewFile, startLine = 1) {
  if (props.busy || file.binary || fileDiff(file.path).status === 'loading') return
  emit('loadFile', { ...identity(), path: file.path, startLine })
}

function openFile(event: Event, file: AgentReviewFile) {
  if ((event.target as HTMLDetailsElement).open && fileDiff(file.path).status === 'unloaded')
    loadFile(file)
}

function decide(decision: AgentReviewDecisionEvent['decision']) {
  if (props.busy) return
  emit('decision', { ...identity(), decision })
}

watch(
  () => [props.candidate.id, props.candidate.revision, props.candidate.reviewHash],
  () => {
    const first = props.candidate.files[0]
    if (first && fileDiff(first.path).status === 'unloaded') loadFile(first)
  },
  { immediate: true },
)
</script>

<template>
  <article class="approval" data-testid="review-card">
    <div class="card-heading">
      <h2>确认整批代码修改</h2>
      <span v-if="candidate.highRisk" class="risk">含高风险修改</span>
    </div>
    <p class="approval-reason">
      {{
        alreadyApplied
          ? '修改已写入工作区，请确认保留；拒绝将尝试回滚。'
          : '请核对以下候选修改，尚未合入源码。'
      }}
    </p>
    <p v-if="alreadyApplied && candidate.rollbackAvailable === false" role="alert" class="risk">
      部分原文未完整保留，不能自动回滚；拒绝可能失败，请先手动核对文件。
    </p>
    <details
      v-for="(file, index) in candidate.files"
      :key="`${candidate.id}:${candidate.revision}:${candidate.reviewHash}:${file.path}`"
      :open="index === 0"
      @toggle="openFile($event, file)"
    >
      <summary>
        {{ file.path }} · {{ changeLabels[file.change] }}{{ file.highRisk ? ' · 高风险' : '' }}
      </summary>
      <p v-if="file.binary" class="muted diff-status">二进制文件，不提供文本差异。</p>
      <template v-else>
        <template v-for="(diff, diffIndex) in [fileDiff(file.path)]" :key="diffIndex">
          <template v-if="diff.status === 'loaded'">
            <pre><span v-for="(line, lineIndex) in diff.text.split('\n')" :key="lineIndex" class="diff-line" :class="{ 'diff-added': line.startsWith('+'), 'diff-removed': line.startsWith('-') }">{{ line }}{{ lineIndex < diff.text.split('\n').length - 1 ? '\n' : '' }}</span></pre>
            <p v-if="diff.startLine > 1" class="muted diff-status">
              第 1–{{ diff.startLine - 1 }} 行尚未加载。
            </p>
            <Button v-if="diff.startLine > 1" :disabled="busy" @click="loadFile(file)"
              >加载前段差异</Button
            >
            <p v-if="diff.endLine < diff.totalLines" class="muted diff-status">
              第 {{ diff.endLine + 1 }}–{{ diff.totalLines }} 行尚未加载。
            </p>
            <Button
              v-if="diff.endLine < diff.totalLines"
              :disabled="busy"
              @click="loadFile(file, diff.endLine + 1)"
              >加载后续差异</Button
            >
          </template>
          <p v-else-if="diff.status === 'loading'" class="muted diff-status" role="status">
            差异加载中…
          </p>
          <template v-else>
            <p class="muted diff-status" :role="diff.status === 'error' ? 'alert' : undefined">
              {{ diff.status === 'error' ? diff.message : '文本差异尚未加载。' }}
            </p>
            <Button :disabled="busy" @click="loadFile(file)">{{
              diff.status === 'error' ? '重新加载差异' : '加载文件差异'
            }}</Button>
          </template>
        </template>
      </template>
    </details>
    <p class="muted approval-scope">
      {{
        alreadyApplied
          ? '确认后保留以上全部修改；拒绝不等于已经回滚，回滚结果以实际执行为准。'
          : '确认后合入以上全部修改；工具批准不能代替整批确认。'
      }}
    </p>
    <div class="buttons">
      <Button danger :disabled="busy" @click="decide('REJECT')">{{
        alreadyApplied ? '拒绝并尝试回滚' : '拒绝并停止本轮'
      }}</Button>
      <Button
        type="primary"
        :loading="busy"
        :disabled="busy"
        data-testid="approve"
        @click="decide('APPROVE')"
        >{{ alreadyApplied ? '确认整批保留' : '确认整批合入' }}</Button
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
.approval-reason {
  color: var(--muted, #737373);
  font-size: 13px;
}
.muted {
  color: var(--muted, #737373);
}
.approval-scope {
  font-size: 12px;
  margin-top: 4px;
}
.diff-status {
  font-size: 12px;
  margin: 4px 0;
  overflow-wrap: anywhere;
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
