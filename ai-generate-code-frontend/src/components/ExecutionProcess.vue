<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{ active: boolean }>()
const manualExpanded = ref<boolean>()
const expanded = computed(() => manualExpanded.value ?? props.active)
</script>

<template>
  <section class="execution-process">
    <button
      type="button"
      class="execution-process__toggle"
      :aria-expanded="expanded"
      @click="manualExpanded = !expanded"
    >
      <span class="execution-process__title">{{ active ? '正在思考与执行' : '执行过程' }}</span>
      <span
        class="execution-process__chevron"
        :class="{ 'execution-process__chevron--expanded': expanded }"
        aria-hidden="true"
      >
        <svg
          viewBox="0 0 24 24"
          width="12"
          height="12"
          stroke="currentColor"
          stroke-width="2.5"
          fill="none"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <polyline points="6 9 12 15 18 9" />
        </svg>
      </span>
    </button>
    <div v-show="expanded" class="execution-process__timeline"><slot /></div>
  </section>
</template>

<style scoped>
.execution-process {
  margin: 6px 0 14px;
  color: var(--text-secondary, #64748b);
  min-width: 0;
}
.execution-process__toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 0;
  padding: 4px 0;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  line-height: 1;
}
.execution-process__toggle:focus-visible {
  outline: 2px solid #2563eb;
  outline-offset: 3px;
}
.execution-process__title {
  display: inline-block;
  line-height: 1;
}
.execution-process__chevron {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 14px;
  height: 14px;
  color: #8c8c8c;
  transition:
    transform 0.22s cubic-bezier(0.4, 0, 0.2, 1),
    color 0.15s ease;
}
.execution-process__toggle:hover .execution-process__chevron {
  color: #1677ff;
}
.execution-process__chevron--expanded {
  transform: rotate(180deg);
}
.execution-process__timeline {
  margin: 8px 0 0 8px;
  padding-left: 18px;
  border-left: 1px solid var(--border-color, #cbd5e1);
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
