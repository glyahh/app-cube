<script setup lang="ts">
interface NavItem {
  label: string
}

const props = withDefaults(
  defineProps<{
    brandText?: string
    titleText?: string
    navItems?: NavItem[]
  }>(),
  {
    brandText: '应用魔方',
    titleText: '应用魔方',
    navItems: () => [],
  },
)
</script>

<template>
  <section class="hero-aura-shell" role="banner" aria-label="main index hero">
    <div class="hero-aura-overlay" aria-hidden="true" />

    <header class="hero-aura-topbar">
      <nav v-if="props.navItems.length" class="hero-aura-nav" aria-label="capability tags">
        <span v-for="item in props.navItems" :key="item.label" class="hero-aura-link">
          {{ item.label }}
        </span>
      </nav>
    </header>

    <h1 class="hero-aura-title">{{ props.titleText }}</h1>
  </section>
</template>

<style scoped>
.hero-aura-shell {
  --font-family-arizona-flare: 'ABC Arizona Flare Variable', 'Times New Roman', serif;
  --color-seed-100: #eae6df;
  position: relative;
  z-index: 1;
  width: 100%;
  min-height: 52vh;
  max-height: 72vh;
  height: min(62vh, 875px);
  padding: 24px 28px 22px;
  margin: 0;
  overflow: hidden;
  box-sizing: border-box;
  border: 1px solid rgba(42, 26, 29, 0.55);
  color: var(--color-seed-100);
  font-family: var(--font-family-arizona-flare);
  -webkit-font-smoothing: antialiased;
  user-select: none;
}

.hero-aura-overlay {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 78% 22%, rgba(38, 216, 98, 0.12), transparent 46%),
    linear-gradient(180deg, rgba(4, 12, 10, 0.35) 0%, rgba(6, 14, 12, 0.42) 100%);
  pointer-events: none;
  z-index: 1;
}

.hero-aura-topbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
}

.hero-aura-nav {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-aura-link {
  color: #d7f3dd;
  font-size: clamp(13px, 1.8vw, 20px);
  line-height: 1.2;
}

.hero-aura-title {
  position: relative;
  z-index: 2;
  margin: 0;
  margin-top: auto;
  max-width: 12ch;
  font-family: var(--font-family-arizona-flare);
  color: var(--color-seed-100);
  font-size: clamp(38px, 9vw, 112px);
  line-height: 0.88;
  letter-spacing: 0.02em;
  text-transform: uppercase;
}

@media (max-width: 768px) {
  .hero-aura-shell {
    min-height: 48vh;
    height: 52vh;
    padding: 18px 18px 16px;
  }
}
</style>
