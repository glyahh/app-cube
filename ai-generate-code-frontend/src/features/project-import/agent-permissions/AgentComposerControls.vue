<script setup lang="ts">
import { computed } from 'vue'
import { Select } from 'ant-design-vue'
import type {
  AgentComposerSettings,
  AgentEffort,
  AgentModelOption,
  AgentPermissionMode,
} from './types'

const props = withDefaults(
  defineProps<{
    settings: AgentComposerSettings
    models: AgentModelOption[]
    busy?: boolean
    saving?: boolean
  }>(),
  { busy: false, saving: false },
)
const emit = defineEmits<{ change: [settings: AgentComposerSettings] }>()
const options = [
  { value: 'MANUAL', label: '手动批准' },
  { value: 'GUARDED_AUTO', label: '自动批准' },
  { value: 'AUTO', label: '自由发挥' },
]
const effortLabels = {
  low: { label: '低', description: '快速响应' },
  medium: { label: '中', description: '速度与深度均衡' },
  high: { label: '高', description: '更深入地思考' },
}
const locked = computed(() => props.busy || props.saving)
const modelOptions = computed(() =>
  props.models.map((model) => ({ value: model.id, label: model.name, provider: model.provider })),
)
const efforts = computed(
  () => props.models.find((model) => model.id === props.settings.modelId)?.efforts ?? [],
)
const effortOptions = computed(() =>
  efforts.value.length
    ? efforts.value.map((value) => ({ value, ...effortLabels[value] }))
    : [{ value: '', label: '不支持', description: '' }],
)

function changeMode(value: unknown) {
  if (locked.value || !options.some((option) => option.value === value)) return
  emit('change', { ...props.settings, mode: value as AgentPermissionMode })
}

function changeModel(value: unknown) {
  if (locked.value) return
  const model = props.models.find((model) => model.id === value)
  if (!model) return
  // 只提交候选设置；保存成功后由父组件更新显示值。
  const effort =
    props.settings.effort && model.efforts.includes(props.settings.effort)
      ? props.settings.effort
      : (model.efforts[0] ?? null)
  emit('change', { ...props.settings, modelId: model.id, effort })
}

function changeEffort(value: unknown) {
  if (locked.value || !efforts.value.includes(value as AgentEffort)) return
  emit('change', { ...props.settings, effort: value as AgentEffort })
}
</script>

<template>
  <div class="composer-controls">
    <div class="mode-control">
      <Select
        :value="settings.mode"
        :options="options"
        :disabled="locked"
        :loading="saving"
        :show-arrow="false"
        :bordered="false"
        :dropdown-match-select-width="false"
        aria-label="会话授权模式"
        data-testid="mode"
        @change="changeMode"
      />
    </div>
    <div class="model-controls">
      <Select
        :value="settings.modelId"
        class="model-select"
        :options="modelOptions"
        :disabled="locked || !models.length"
        :bordered="false"
        show-search
        option-filter-prop="label"
        placement="topLeft"
        :dropdown-match-select-width="false"
        :dropdown-style="{ minWidth: '260px', maxWidth: 'calc(100vw - 32px)' }"
        aria-label="当前模型"
        data-testid="model"
        @change="changeModel"
      >
        <template #option="item">
          <span class="model-option"
            ><span>{{ item.label }}</span
            ><span class="model-provider">
              {{ item.provider }}
              <svg
                v-if="item.value === settings.modelId"
                class="selected-check"
                width="14"
                height="14"
                viewBox="0 0 16 16"
                fill="none"
                aria-hidden="true"
              >
                <path
                  d="m3 8 3 3 7-7"
                  stroke="currentColor"
                  stroke-width="1.8"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg> </span
          ></span>
        </template>
      </Select>
      <span class="control-divider" aria-hidden="true"></span>
      <Select
        :value="efforts.length ? (settings.effort ?? undefined) : ''"
        class="effort-select"
        :options="effortOptions"
        :disabled="locked || !efforts.length"
        :bordered="false"
        placement="topLeft"
        :dropdown-match-select-width="false"
        :dropdown-style="{ minWidth: '190px' }"
        aria-label="思考力度"
        data-testid="effort"
        @change="changeEffort"
      >
        <template #option="item">
          <span class="effort-option"
            ><span>{{ item.label }}</span
            ><span class="effort-description">{{ item.description }}</span></span
          >
        </template>
        <template #suffixIcon>
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
            <path
              d="M3 12V9M8 12V6M13 12V3"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
            />
          </svg>
        </template>
      </Select>
    </div>
  </div>
</template>

<style scoped>
.composer-controls,
.model-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
}
.composer-controls {
  flex: 1;
}
.mode-control {
  flex: 0 0 auto;
}
.mode-control :deep(.ant-select) {
  width: auto;
}
.mode-control :deep(.ant-select-selector) {
  background: #f5f5f5;
  border-radius: 16px;
}
.model-select {
  width: auto;
  min-width: 104px;
  max-width: 100%;
}
.effort-select {
  width: auto;
}
.model-controls :deep(.ant-select-selector) {
  border-radius: 6px;
  color: #737373;
}
.model-controls :deep(.ant-select:not(.ant-select-disabled):hover .ant-select-selector) {
  background: #f5f5f5;
}
.composer-controls :deep(.ant-select-focused .ant-select-selector) {
  outline: 2px solid #2563eb;
  outline-offset: 1px;
}
.control-divider {
  height: 14px;
  width: 1px;
  background: var(--line, #e5e5e5);
  margin: 0 2px;
}
.model-option,
.effort-option {
  display: flex;
  gap: 16px;
  justify-content: space-between;
  align-items: center;
}
.model-provider,
.effort-description {
  font-size: 12px;
  color: #737373;
}
.selected-check {
  margin-left: 8px;
  color: #2563eb;
  vertical-align: middle;
}
@media (max-width: 767px) {
  .composer-controls {
    gap: 8px;
  }
  .model-controls {
    gap: 2px;
  }
}
</style>
