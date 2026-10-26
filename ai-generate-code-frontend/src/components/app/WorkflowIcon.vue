<script setup lang="ts">
import { ref } from 'vue'

withDefaults(
  defineProps<{
    size?: number
  }>(),
  { size: 28 },
)

const animated = ref(false)
</script>

<template>
  <div class="workflow-icon" @mouseenter="animated = true" @mouseleave="animated = false">
    <svg
      :width="size"
      :height="size"
      viewBox="0 0 24 24"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      <rect
        class="workflow-icon__shape"
        :class="{ 'workflow-icon__shape--draw': animated }"
        x="3"
        y="3"
        width="8"
        height="8"
        rx="2"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path
        class="workflow-icon__shape workflow-icon__shape--delay"
        :class="{ 'workflow-icon__shape--draw': animated }"
        d="M7 11v4a2 2 0 0 0 2 2h4"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <rect
        class="workflow-icon__shape workflow-icon__shape--delay-2"
        :class="{ 'workflow-icon__shape--draw': animated }"
        x="13"
        y="13"
        width="8"
        height="8"
        rx="2"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
    </svg>
  </div>
</template>

<style scoped>
.workflow-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: inherit;
}

.workflow-icon__shape {
  stroke-dasharray: 48;
  stroke-dashoffset: 0;
  opacity: 1;
  transition:
    stroke-dashoffset 0.32s ease,
    opacity 0.28s ease;
}

.workflow-icon__shape--delay {
  transition-delay: 0.08s;
}

.workflow-icon__shape--delay-2 {
  transition-delay: 0.16s;
}

.workflow-icon__shape--draw {
  stroke-dashoffset: 48;
  opacity: 0.35;
  animation: workflow-draw 0.45s ease forwards;
}

.workflow-icon__shape--delay.workflow-icon__shape--draw {
  animation-delay: 0.1s;
}

.workflow-icon__shape--delay-2.workflow-icon__shape--draw {
  animation-delay: 0.2s;
}

@keyframes workflow-draw {
  from {
    stroke-dashoffset: 48;
    opacity: 0.2;
  }
  to {
    stroke-dashoffset: 0;
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .workflow-icon__shape,
  .workflow-icon__shape--draw {
    animation: none;
    transition: none;
    stroke-dashoffset: 0;
    opacity: 1;
  }
}
</style>
