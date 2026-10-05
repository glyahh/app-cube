<script setup lang="ts">
import HeroAuraBannerModule from './components/HeroAuraBannerModule.vue'
import MainIndexWebGLBackground from './components/MainIndexWebGLBackground.vue'
import ReferenceFooterBackdropModule from './components/ReferenceFooterBackdropModule.vue'
</script>

<template>
  <main class="main-index-page">
    <MainIndexWebGLBackground />
    <ReferenceFooterBackdropModule />

    <section class="main-index-content">
      <HeroAuraBannerModule />

      <section class="project-panel" aria-label="project intro">
        <div class="project-panel-aurora project-panel-aurora--left" aria-hidden="true"></div>
        <div class="project-panel-aurora project-panel-aurora--right" aria-hidden="true"></div>
        <div class="project-panel-gridline" aria-hidden="true"></div>

        <header class="project-heading">
          <div class="project-kicker-row">
            <span class="project-kicker">Build Smarter, Ship Faster</span>
            <span class="project-status-pill">Creative Engineering</span>
          </div>
          <div class="project-title-row">
            <h2 class="project-title">应用魔方</h2>
            <p class="project-title-side">Workflow-native · Stream-first · Production-ready</p>
          </div>
          <p class="project-desc">
            一个面向高效开发的 AI 代码生成平台，支持多种工作流与代码模式，帮助你从需求到可运行项目快速闭环。
          </p>
        </header>

        <section class="metric-strip" aria-label="platform metrics">
          <article class="metric-item">
            <p class="metric-value">3+</p>
            <p class="metric-label">代码生成模式</p>
          </article>
          <article class="metric-item">
            <p class="metric-value">Workflow</p>
            <p class="metric-label">流程化稳定输出</p>
          </article>
          <article class="metric-item">
            <p class="metric-value">SSE</p>
            <p class="metric-label">实时流式可见</p>
          </article>
        </section>

        <div class="capability-grid">
          <article class="capability-card">
            <p class="card-tag">Flow</p>
            <h3>工作流生成</h3>
            <p>节点化链路保证可追踪、可回放，降低长对话场景下的波动。</p>
          </article>
          <article class="capability-card">
            <p class="card-tag">Type</p>
            <h3>多类型代码</h3>
            <p>覆盖单文件、多文件、Vue 工程，让不同复杂度需求都可高效落地。</p>
          </article>
          <article class="capability-card">
            <p class="card-tag">Release</p>
            <h3>预览与部署</h3>
            <p>从生成到预览再到上线一体化串联，减少跨工具切换成本。</p>
          </article>
        </div>

        <section class="flow-timeline" aria-label="generation flow">
          <article class="timeline-step">
            <span class="step-index">01</span>
            <h4>输入需求</h4>
            <p>用自然语言描述目标页面、交互和数据需求。</p>
          </article>
          <article class="timeline-step">
            <span class="step-index">02</span>
            <h4>智能生成</h4>
            <p>基于工作流进行代码拆分与质量校验，确保输出稳定。</p>
          </article>
          <article class="timeline-step">
            <span class="step-index">03</span>
            <h4>预览迭代</h4>
            <p>在同一链路下快速预览、微调并部署到可访问地址。</p>
          </article>
        </section>

        <section class="cta-row" aria-label="actions">
          <a class="enter-button" href="http://localhost:5173/" aria-label="进入主站">
            进入主站
          </a>
        </section>
      </section>
    </section>
  </main>
</template>

<style scoped>
.main-index-page {
  position: relative;
  min-height: 100vh;
  overflow: hidden;
  background: #030a08;
  padding: 0 0 36px;
}

.main-index-content {
  position: relative;
  z-index: 2;
  width: 100%;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.project-panel {
  position: relative;
  isolation: isolate;
  width: min(1120px, calc(100% - 40px));
  margin: 0 auto;
  border-radius: 28px;
  background:
    radial-gradient(circle at 0% 0%, rgba(16, 185, 129, 0.22), transparent 42%),
    radial-gradient(circle at 100% 0%, rgba(74, 222, 128, 0.2), transparent 40%),
    linear-gradient(145deg, rgba(7, 18, 14, 0.94), rgba(5, 14, 11, 0.9));
  border: 1px solid rgba(71, 85, 105, 0.52);
  box-shadow:
    0 32px 72px rgba(2, 6, 23, 0.62),
    0 12px 24px rgba(2, 6, 23, 0.36),
    0 0 0 1px rgba(148, 163, 184, 0.16) inset,
    0 1px 0 rgba(255, 255, 255, 0.08) inset;
  padding: 30px;
  backdrop-filter: blur(14px) saturate(132%);
  -webkit-backdrop-filter: blur(14px) saturate(132%);
}

.project-panel::before {
  content: '';
  position: absolute;
  inset: -1px;
  border-radius: inherit;
  pointer-events: none;
  background: conic-gradient(
    from 20deg,
    rgba(16, 185, 129, 0.28),
    rgba(74, 222, 128, 0.22),
    rgba(34, 197, 94, 0.3),
    rgba(16, 185, 129, 0.28)
  );
  opacity: 0.38;
  z-index: -2;
  filter: blur(10px);
  animation: none;
}

.project-panel-aurora {
  position: absolute;
  pointer-events: none;
  z-index: -1;
  filter: blur(34px);
  animation: auroraFloat 9s ease-in-out infinite;
}

.project-panel-aurora--left {
  width: min(44vw, 460px);
  height: min(34vw, 320px);
  left: -11%;
  top: -16%;
  background:
    radial-gradient(circle at 28% 38%, rgba(16, 185, 129, 0.5), rgba(16, 185, 129, 0) 64%),
    radial-gradient(circle at 62% 52%, rgba(74, 222, 128, 0.38), rgba(74, 222, 128, 0) 62%);
}

.project-panel-aurora--right {
  width: min(44vw, 460px);
  height: min(34vw, 320px);
  right: -10%;
  bottom: -16%;
  background:
    radial-gradient(circle at 68% 52%, rgba(52, 211, 153, 0.44), rgba(52, 211, 153, 0) 60%),
    radial-gradient(circle at 38% 44%, rgba(110, 231, 183, 0.3), rgba(110, 231, 183, 0) 62%);
  animation-delay: -3s;
}

.project-panel-gridline {
  position: absolute;
  inset: 0;
  border-radius: inherit;
  pointer-events: none;
  z-index: 0;
  opacity: 0.28;
  background-image:
    linear-gradient(rgba(148, 163, 184, 0.24) 1px, transparent 1px),
    linear-gradient(90deg, rgba(148, 163, 184, 0.2) 1px, transparent 1px);
  background-size: 36px 36px;
  mask-image: linear-gradient(to bottom, rgba(0, 0, 0, 0.56), rgba(0, 0, 0, 0.12));
}

.project-heading {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 12px;
}

.project-kicker-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.project-kicker {
  width: fit-content;
  padding: 7px 13px;
  border-radius: 999px;
  background: rgba(16, 185, 129, 0.22);
  color: #bbf7d0;
  font-size: 12px;
  letter-spacing: 0.08em;
  font-weight: 700;
  text-transform: uppercase;
  box-shadow: 0 8px 18px rgba(6, 95, 70, 0.34);
}

.project-status-pill {
  display: inline-flex;
  align-items: center;
  min-height: 30px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid rgba(74, 222, 128, 0.38);
  color: #bbf7d0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  background:
    radial-gradient(circle at 0 50%, rgba(45, 212, 191, 0.16), transparent 42%),
    rgba(6, 78, 59, 0.48);
}

.project-title-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.project-title {
  margin: 0;
  font-size: clamp(27px, 3.1vw, 44px);
  color: #e2e8f0;
  letter-spacing: -0.02em;
  background: linear-gradient(110deg, #d1fae5, #f0fdf4 34%, #86efac 62%, #ecfeff 84%);
  background-size: 210% 100%;
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: titleShine 7s ease-in-out infinite;
}

.project-title-side {
  margin: 0;
  color: #86efac;
  font-size: 13px;
  line-height: 1.5;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  font-weight: 600;
}

.project-desc {
  margin: 0;
  max-width: 74ch;
  color: #cbd5e1;
  font-size: 16px;
  line-height: 1.75;
}

.metric-strip {
  margin-top: 18px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.metric-item {
  position: relative;
  overflow: hidden;
  border-radius: 14px;
  background: rgba(8, 22, 17, 0.78);
  border: 1px solid rgba(71, 85, 105, 0.45);
  padding: 15px;
  transition: transform 260ms ease, box-shadow 260ms ease, border-color 260ms ease;
}

.metric-item::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background: linear-gradient(120deg, transparent 22%, rgba(255, 255, 255, 0.4) 45%, transparent 66%);
  transform: translateX(-130%);
  animation: cardShimmer 5s ease-in-out infinite;
}

.metric-item:nth-child(2)::before {
  animation-delay: 1.2s;
}

.metric-item:nth-child(3)::before {
  animation-delay: 2.2s;
}

.metric-item:hover {
  transform: translateY(-4px);
  border-color: rgba(74, 222, 128, 0.42);
  box-shadow: 0 20px 36px rgba(2, 6, 23, 0.52);
}

.metric-value {
  margin: 0;
  font-size: clamp(20px, 2.2vw, 28px);
  color: #f8fafc;
  font-weight: 700;
  line-height: 1.2;
}

.metric-label {
  margin: 6px 0 0;
  color: #94a3b8;
  font-size: 14px;
}

.capability-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.capability-card {
  position: relative;
  overflow: hidden;
  border-radius: 16px;
  border: 1px solid rgba(71, 85, 105, 0.4);
  background:
    linear-gradient(160deg, rgba(8, 22, 17, 0.92), rgba(6, 17, 13, 0.88)),
    rgba(8, 22, 17, 0.9);
  padding: 16px;
  min-height: 152px;
  transition: transform 260ms ease, border-color 260ms ease, box-shadow 260ms ease;
}

.capability-card:hover {
  transform: translateY(-4px);
  border-color: rgba(74, 222, 128, 0.4);
  box-shadow: 0 18px 34px rgba(2, 6, 23, 0.5);
}

.card-tag {
  margin: 0;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #86efac;
}

.capability-card h3 {
  margin: 8px 0 0;
  color: #f8fafc;
}

.capability-card p {
  margin: 8px 0 0;
  color: #94a3b8;
  line-height: 1.65;
}

.flow-timeline {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.timeline-step {
  position: relative;
  border-radius: 16px;
  border: 1px solid rgba(71, 85, 105, 0.44);
  background:
    linear-gradient(180deg, rgba(8, 22, 17, 0.92), rgba(6, 16, 12, 0.86)),
    rgba(8, 22, 17, 0.9);
  padding: 16px;
  overflow: hidden;
}

.timeline-step::after {
  content: '';
  position: absolute;
  inset: auto 0 0 0;
  height: 3px;
  background: linear-gradient(90deg, rgba(5, 150, 105, 0.55), rgba(34, 197, 94, 0.52));
  opacity: 0.5;
}

.step-index {
  display: inline-flex;
  min-width: 44px;
  justify-content: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: #fff;
  background: linear-gradient(135deg, #059669, #22c55e);
}

.timeline-step h4 {
  margin: 10px 0 0;
  font-size: 17px;
  color: #f8fafc;
}

.timeline-step p {
  margin: 8px 0 0;
  color: #94a3b8;
  line-height: 1.6;
}

.cta-row {
  margin-top: 20px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.enter-button {
  width: fit-content;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 11px 26px;
  border-radius: 999px;
  text-decoration: none;
  font-weight: 600;
  letter-spacing: 0.01em;
  color: rgba(236, 253, 245, 0.96);
  background: linear-gradient(180deg, rgba(9, 17, 14, 0.9), rgba(5, 11, 9, 0.86));
  border: 1px solid rgba(167, 243, 208, 0.34);
  box-shadow:
    inset 0 1px 0 rgba(240, 253, 250, 0.2),
    0 12px 24px rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(10px);
  transition:
    transform 220ms ease,
    box-shadow 220ms ease,
    border-color 220ms ease,
    color 220ms ease;
  cursor: pointer;
  position: relative;
  overflow: hidden;
  isolation: isolate;
}

.enter-button::before {
  content: '';
  position: absolute;
  inset: 0;
  border-radius: inherit;
  z-index: -1;
  pointer-events: none;
  background:
    linear-gradient(105deg, transparent 34%, rgba(167, 243, 208, 0.34) 47%, transparent 58%),
    linear-gradient(180deg, rgba(11, 20, 16, 0.9), rgba(6, 12, 10, 0.88));
  background-size: 210% 100%, 100% 100%;
  background-position: 120% 0, 0 0;
  animation: enterButtonBeam 4.2s linear infinite;
}

.enter-button::after {
  content: '';
  position: absolute;
  inset: 1px;
  border-radius: inherit;
  pointer-events: none;
  border: 1px solid rgba(236, 253, 245, 0.08);
  box-shadow: inset 0 0 0 1px rgba(16, 185, 129, 0.08);
}

.enter-button:hover {
  transform: translateY(-1px);
  border-color: rgba(167, 243, 208, 0.54);
  color: #f0fdf4;
  box-shadow:
    inset 0 1px 0 rgba(240, 253, 250, 0.26),
    0 14px 30px rgba(0, 0, 0, 0.5);
}

.enter-button:focus-visible {
  outline: 3px solid rgba(52, 211, 153, 0.36);
  outline-offset: 2px;
}

@keyframes enterButtonBeam {
  0% {
    background-position: 120% 0, 0 0;
  }
  100% {
    background-position: -120% 0, 0 0;
  }
}

@keyframes auroraFloat {
  0%,
  100% {
    transform: translate3d(0, 0, 0);
  }
  50% {
    transform: translate3d(0, -10px, 0);
  }
}

@keyframes titleShine {
  0%,
  100% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
}

@keyframes cardShimmer {
  0%,
  100% {
    transform: translateX(-130%);
    opacity: 0;
  }
  14% {
    opacity: 0.8;
  }
  34% {
    transform: translateX(130%);
    opacity: 0;
  }
}

@media (max-width: 900px) {
  .project-panel {
    width: calc(100% - 24px);
    padding: 20px;
  }

  .project-title-side {
    width: 100%;
  }

  .metric-strip,
  .capability-grid,
  .flow-timeline {
    grid-template-columns: 1fr;
  }
}

@media (prefers-reduced-motion: reduce) {
  .metric-item,
  .capability-card,
  .enter-button {
    transition: none;
  }

  .project-panel::before,
  .project-panel-aurora,
  .project-title,
  .metric-item::before {
    animation: none;
  }

  .project-panel-gridline {
    background-image: none;
  }

  .metric-item:hover,
  .capability-card:hover,
  .enter-button:hover {
    transform: none;
    box-shadow: none;
    filter: none;
  }
}
</style>
