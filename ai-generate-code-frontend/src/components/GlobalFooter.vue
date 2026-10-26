<script setup lang="ts"></script>

<template>
  <div class="global-footer">
    <span class="footer-text">应用魔方 · glyahh</span>
  </div>
</template>

<style scoped>
.global-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--bg-card, rgba(255, 255, 255, 0.95));
  border-top: 1px solid var(--border-color, rgba(0, 0, 0, 0.06));
  z-index: 100;
}

.footer-text {
  font-size: 14px;
  color: var(--text-secondary, rgba(0, 0, 0, 0.65));
}
</style>
