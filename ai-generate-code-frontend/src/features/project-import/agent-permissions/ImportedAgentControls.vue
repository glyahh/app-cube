<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, toRef, watch } from 'vue'
import { Alert, Button } from 'ant-design-vue'
import AgentComposerControls from './AgentComposerControls.vue'
import AgentPermissionCard from './AgentPermissionCard.vue'
import AgentChangeReview from './AgentChangeReview.vue'
import { useAgentSession } from './useAgentSession'

const props = defineProps<{ appId: string; streaming: boolean }>()
const emit = defineEmits<{ blocked: [value: boolean]; merged: []; waiting: [value: boolean] }>()

const agent = useAgentSession({
  appId: toRef(props, 'appId'),
  streaming: toRef(props, 'streaming'),
})
const {
  options,
  toolRequest,
  candidate,
  diffs,
  state,
  saving,
  deciding,
  error,
  settings,
  isImportApp,
} = agent

watch(agent.blocked, (value) => emit('blocked', value), { immediate: true })
watch(agent.merged, () => emit('merged'))
// 有卡片要用户处理时隐藏输入框：此时发送没有意义，留着只会分散注意力。
watch(
  () => !!(toolRequest.value || candidate.value),
  (value) => emit('waiting', value),
  {
    immediate: true,
  },
)

onMounted(agent.load)
onBeforeUnmount(agent.dispose)

// 三档授权模式对普通应用与导入应用都可用，区别只在候选机制，不影响这个选择器。
const showMode = true
const busy = computed(() => props.streaming || state.value !== 'IDLE' || !settings.value.modelId)
</script>

<template>
  <!-- 单一根节点：卡片在上、控件在下，避免在 flex 行里被拆成多个 item。 -->
  <div class="agent-controls">
    <div v-if="toolRequest || candidate || error" class="agent-cards">
      <Alert v-if="error" type="error" :message="error" show-icon />
      <Button v-if="error" size="small" @click="agent.load">重新读取</Button>
      <AgentPermissionCard
        v-if="toolRequest"
        :request="toolRequest"
        :busy="deciding"
        @decision="agent.decideTool"
      />
      <AgentChangeReview
        v-if="candidate"
        :candidate="candidate"
        :diffs="diffs"
        :busy="deciding"
        @decision="agent.decideReview"
      />
    </div>
    <AgentComposerControls
      :settings="settings"
      :models="options"
      :busy="busy"
      :saving="saving"
      :show-mode="showMode"
      @change="agent.saveSettings"
    />
  </div>
</template>

<style scoped>
.agent-controls {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.agent-cards {
  display: grid;
  gap: 12px;
}
</style>
