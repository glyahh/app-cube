<script setup lang="ts">
import {
  Layout as ALayout,
  LayoutContent as ALayoutContent,
  LayoutHeader as ALayoutHeader,
} from 'ant-design-vue'
import GlobalHeader from '@/components/GlobalHeader.vue'
import GlobalFooter from '@/components/GlobalFooter.vue'
</script>

<template>
  <a-layout class="basic-layout">
    <a-layout-header class="layout-header">
      <GlobalHeader />
    </a-layout-header>
    <a-layout-content class="layout-content">
      <slot />
    </a-layout-content>
    <GlobalFooter />
  </a-layout>
</template>

<style scoped>
.basic-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.layout-header {
  position: sticky;
  top: 0;
  z-index: 200;
  padding: 0;
  height: 64px;
  line-height: 64px;
  background: var(--bg-card, #fff);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.08);
}

.layout-content {
  flex: 1;
  padding: var(--spacing-lg, 24px);
  padding-bottom: 80px;
  /* 为固定底部 footer 留出空间 */
  min-height: calc(100vh - 64px - 48px);
  background: transparent;
}

@media (max-width: 768px) {
  .layout-header {
    height: 56px;
    line-height: 56px;
  }

  .layout-content {
    padding: 16px;
    padding-bottom: 72px;
    min-height: calc(100vh - 56px - 48px);
  }
}
/* 聊天工作台独占剩余视口，滚动留在面板内部。 */
.basic-layout:has(.app-chat-page) {
  height: 100dvh;
  min-height: 0;
  overflow: hidden;
}
.basic-layout:has(.app-chat-page) .layout-header {
  flex-shrink: 0;
}
.layout-content:has(.app-chat-page) {
  min-height: 0;
  padding: 12px 24px;
  margin-bottom: 48px;
  overflow: hidden;
}
@media (max-width: 768px) {
  .layout-content:has(.app-chat-page) {
    padding: 8px;
  }
}
</style>
