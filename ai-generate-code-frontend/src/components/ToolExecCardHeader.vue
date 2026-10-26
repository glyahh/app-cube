<script setup lang="ts">
import ToolFlashlightGlyph from '@/components/ToolFlashlightGlyph.vue'

/**
 * 工具执行卡片头部：badge + 工具名 + 手电衔接 + 路径，保留 actions 插槽。
 */
interface Props {
  actionLabel: string
  targetPath?: string
}

withDefaults(defineProps<Props>(), {
  targetPath: '',
})
</script>

<template>
  <div class="tool-exec-header">
    <div class="tool-call-badge">使用工具</div>
    <div class="tool-exec-meta">
      <div class="tool-exec-inline">
        <span class="tool-exec-action-label">
          {{ actionLabel }}
          <slot name="action-label-after" />
        </span>
        <ToolFlashlightGlyph v-if="targetPath" class="tool-exec-header-connector" :size="14" />
        <span v-if="targetPath" class="tool-exec-target-path">
          {{ targetPath }}
          <slot name="target-path-after" />
        </span>
      </div>
    </div>
    <div v-if="$slots.actions" class="tool-exec-actions">
      <slot name="actions" />
    </div>
  </div>
</template>

<style scoped>
.tool-exec-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 28px;
}

.tool-exec-meta {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  align-items: center;
}

.tool-exec-inline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0;
  min-width: 0;
  padding-top: 2px;
}

.tool-call-badge {
  flex: 0 0 auto;
  padding: 4px 8px;
  border-radius: 999px;
  background: #0f172a;
  color: #f9fafb;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.tool-exec-action-label {
  font-size: 13px;
  font-weight: 600;
  color: #111827;
  letter-spacing: 0.02em;
  line-height: 1.3;
}

.tool-exec-header-connector {
  margin: 0 6px;
}

.tool-exec-target-path {
  font-size: 11px;
  color: #64748b;
  font-family:
    ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New',
    monospace;
  word-break: break-all;
  line-height: 1.3;
}

.tool-exec-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
}

/* hover 父标签时显式 slot 内 .copy-hint 元素 */
.tool-exec-action-label:hover :slotted(.copy-hint),
.tool-exec-target-path:hover :slotted(.copy-hint) {
  opacity: 1 !important;
  pointer-events: auto !important;
}
</style>
