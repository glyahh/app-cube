<script setup lang="ts">
import { computed } from 'vue'
import type { ThinkingSegment } from '@/utils/thinkingSegments'
import { thinkingLines } from '@/utils/executionDisplay'

const props = defineProps<{ stage: ThinkingSegment }>()
const lines = computed(() => thinkingLines(props.stage.summary, props.stage.details))
</script>

<template>
  <div class="thinking-line">
    <div v-for="(line, index) in lines" :key="index">{{ line }}</div>
  </div>
</template>

<style scoped>
.thinking-line {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 6px 0;
  color: inherit;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
