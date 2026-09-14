<script setup lang="ts">
import { computed, ref } from 'vue'

const props = defineProps<{ active: boolean }>()
const manualExpanded = ref<boolean>()
const expanded = computed(() => manualExpanded.value ?? props.active)
</script>

<template>
  <section class="execution-process">
    <button type="button" class="execution-process__toggle" :aria-expanded="expanded"
      @click="manualExpanded = !expanded">
      <span>{{ active ? '正在思考与执行' : '执行过程' }}</span>
      <span aria-hidden="true">{{ expanded ? '⌄' : '›' }}</span>
      <span v-if="active" class="execution-process__pulse" aria-hidden="true" />
    </button>
    <div v-show="expanded" class="execution-process__timeline"><slot /></div>
  </section>
</template>

<style scoped>
.execution-process { margin: 6px 0 14px; color: var(--text-secondary, #64748b); min-width: 0; }
.execution-process__toggle { display: flex; align-items: center; gap: 10px; border: 0; padding: 6px 0; background: transparent; color: inherit; font: inherit; font-size: 13px; cursor: pointer; text-align: left; }
.execution-process__toggle:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; }
.execution-process__timeline { margin: 8px 0 0 8px; padding-left: 18px; border-left: 1px solid var(--border-color, #cbd5e1); min-width: 0; overflow-wrap: anywhere; }
.execution-process__pulse { width: 6px; height: 6px; border-radius: 50%; background: currentColor; animation: process-pulse 1.5s ease-in-out infinite; }
@keyframes process-pulse { 50% { opacity: .25; } }
@media (prefers-reduced-motion: reduce) { .execution-process__pulse { animation: none; } }
</style>
