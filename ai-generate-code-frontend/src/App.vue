<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import { ConfigProvider as AConfigProvider } from 'ant-design-vue'
import BasicLayout from '@/layouts/BasicLayout.vue'
import { useAppearanceStore, resolveThemeConfig } from './stores/appearance'

// 外观设置
const appearanceStore = useAppearanceStore()
onMounted(() => {
  appearanceStore.init()
})

// ConfigProvider 主题配置：监听 store 变化动态计算
// effectiveColorMode 是响应式 computed，跟随系统主题变化时自动触发重算
const configProviderTheme = computed(() => {
  return resolveThemeConfig({
    isDark: appearanceStore.effectiveColorMode === 'dark',
    primaryColor: appearanceStore.primaryColor,
    fontSize: appearanceStore.fontSize,
    compactMode: appearanceStore.compactMode,
  })
})

// 路由布局
const route = useRoute()
const noLayout = computed(() => Boolean(route.meta?.noLayout))
</script>

<template>
  <a-config-provider :theme="configProviderTheme">
    <RouterView v-if="noLayout" />
    <BasicLayout v-else>
      <RouterView />
    </BasicLayout>
  </a-config-provider>
</template>
