<script setup lang="ts">
defineProps<{ command: string; output: string; status: string }>()
</script>

<template>
  <details class="execution-terminal">
    <summary>终端：执行命令 · {{ status }}</summary>
    <div class="execution-terminal__body">
      <pre v-if="command" class="execution-terminal__command">{{ command }}</pre>
      <pre v-if="output" class="execution-terminal__output">{{ output }}</pre>
      <span v-else>暂无可显示的输出</span>
    </div>
  </details>
</template>

<style scoped>
.execution-terminal { margin: 10px 0; min-width: 0; font-size: 13px; }
summary { cursor: pointer; padding: 5px 0; overflow-wrap: anywhere; }
summary:focus-visible { outline: 2px solid #2563eb; outline-offset: 3px; }
.execution-terminal__body { margin-top: 5px; border-radius: 6px; padding: 10px; background: var(--bg-secondary, #eef1f5); max-height: 280px; overflow: auto; }
pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.6 ui-monospace, Consolas, monospace; }
.execution-terminal__command { margin-bottom: 8px; color: var(--text-primary, #334155); }
</style>
