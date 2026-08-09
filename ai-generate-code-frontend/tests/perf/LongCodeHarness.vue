<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import CodeBlock from '@/components/CodeBlock.vue'
import '@/assets/stream-layout.css'
import { scrollElementToEnd } from '@/utils/scrollElement'

const props = defineProps<{ targetBytes: number }>()
const code = ref('')
const streaming = ref(true)
const generatedFiles = ref(0)
const workflowSteps = ref<string[]>([])
const chatMessagesRef = ref<HTMLElement | null>(null)
const workflowListRef = ref<HTMLElement | null>(null)
let frame: number | null = null
let generatedBytes = 0
let renderedFrames = 0

function scrollToBottom() {
  void nextTick(() => {
    const element = chatMessagesRef.value
    if (element) element.scrollTop = element.scrollHeight
  })
}

onMounted(() => {
  const append = () => {
    const remaining = props.targetBytes - generatedBytes
    if (remaining <= 0) {
      streaming.value = false
      generatedFiles.value = 3
      void nextTick(() => {
        document.body.dataset.done = 'true'
      })
      return
    }
    const line = `const value${code.value.length} = ${code.value.length};\n`
    const repeat = Math.max(1, Math.min(16, Math.ceil(remaining / line.length)))
    const addition = line.repeat(repeat)
    code.value += addition
    generatedBytes += new TextEncoder().encode(addition).byteLength
    renderedFrames += 1
    if (renderedFrames % 24 === 0 && workflowSteps.value.length < 8) {
      workflowSteps.value.push(`Workflow step ${workflowSteps.value.length + 1}`)
      void nextTick(() => scrollElementToEnd(workflowListRef.value))
    }
    scrollToBottom()
    frame = requestAnimationFrame(append)
  }
  frame = requestAnimationFrame(append)
})

onBeforeUnmount(() => {
  if (frame != null) cancelAnimationFrame(frame)
})
</script>

<template>
  <main class="chat-shell">
    <div class="stream-file-slot" :class="{ 'stream-file-slot--empty': generatedFiles === 0 }">
      <section v-if="generatedFiles > 0" class="generated-files-bar" data-cls-source="generated-files-bar">
        <span>Generated {{ generatedFiles }} files</span>
        <button type="button">View files</button>
      </section>
    </div>
    <section ref="chatMessagesRef" class="chat-messages">
      <div class="workflow-steps-shell stream-workflow-viewport" data-cls-source="workflow-card">
        <div class="workflow-steps-card">
          <strong>Workflow progress</strong>
          <div ref="workflowListRef" class="workflow-steps-list">
            <div v-for="step in workflowSteps" :key="step" class="workflow-row">{{ step }}</div>
          </div>
        </div>
      </div>
      <CodeBlock :code="code" language="typescript" :is-streaming="streaming" />
      <div class="bottom-sentinel" />
    </section>
    <footer class="composer" data-cls-source="composer">
      <textarea id="input" rows="2" placeholder="Type while code streams" />
      <button type="button">Stop</button>
    </footer>
  </main>
</template>

<style scoped>
:global(*) { box-sizing: border-box; }
:global(body) { margin: 0; background: #f4f5f7; font-family: Arial, sans-serif; }
.chat-shell { width: min(760px, calc(100vw - 24px)); height: calc(100vh - 24px); margin: 12px auto; padding: 16px; display: flex; flex-direction: column; overflow: hidden; background: #fff; border: 1px solid #d9dde5; border-radius: 8px; }
.generated-files-bar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border-radius: 8px; background: #e8f7ee; }
.chat-messages { flex: 1; min-height: 0; overflow: auto; }
.workflow-steps-shell { margin-bottom: 6px; }
.workflow-steps-card { padding: 10px 12px; border: 1px solid #b7d7ef; border-radius: 8px; background: #f7fbff; }
.workflow-row { min-height: 30px; margin-top: 6px; padding: 6px 8px; border: 1px solid #dce3ec; border-radius: 6px; }
.composer { display: flex; align-items: flex-end; gap: 8px; padding-top: 10px; background: #fff; }
.composer textarea { flex: 1; resize: none; }
.bottom-sentinel { height: 1px; }
</style>
