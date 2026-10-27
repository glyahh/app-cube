<template>
  <LoopCreateView :edit-id="id" />
</template>

<script setup lang="ts">
import LoopCreateView from './LoopCreateView.vue'

defineProps<{ id: string }>()
</script>

<style scoped>
/* LoopEditView 完全委托给 LoopCreateView */
</style>
