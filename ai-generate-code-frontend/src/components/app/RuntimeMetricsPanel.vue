<template>
  <section class="runtime-metrics cosmic-workbench" aria-label="运行监控看板">
    <header class="monitor-head">
      <div>
        <div class="wb-eyebrow">Runtime</div>
        <h2 class="wb-heading">运行监控</h2>
        <div class="wb-subline">
          <i class="wb-dot" :class="{ 'is-live': running && !paused }" />{{
            !running ? '应用未运行' : paused ? '采样显示已暂停' : '实时采样'
          }}<span>·</span><span>受管容器</span>
        </div>
      </div>
      <div class="wb-actions">
        <span class="monitor-window">最近 {{ windowMinutes }} 分钟</span
        ><button
          type="button"
          class="wb-button"
          :disabled="!running"
          :aria-pressed="paused"
          @click="paused = !paused"
        >
          {{ paused ? '继续' : '暂停' }}
        </button>
      </div>
    </header>
    <div class="metrics">
      <button
        v-for="card in cards"
        :key="card.id"
        type="button"
        class="metric"
        :class="{ active: activeMetric === card.id }"
        :aria-pressed="activeMetric === card.id"
        @click="activeMetric = card.id"
      >
        <span class="metric-label">{{ card.label }}</span
        ><strong class="metric-value">{{ card.value }}</strong
        ><span class="metric-sub">{{ card.sub }}</span>
      </button>
      <div class="metric metric-summary">
        <span class="metric-label">错误率</span
        ><strong class="metric-value">{{
          errorRate == null ? '暂无数据' : `${errorRate.toFixed(1)}%`
        }}</strong
        ><span class="metric-sub">平台入口的 5xx 占比</span>
      </div>
    </div>
    <section class="chart-wrap">
      <div class="chart-title">
        <strong>{{ activeTitle }}</strong
        ><span class="chart-legend">{{ legend }}</span>
      </div>
      <div class="chart-canvas-wrap">
        <canvas ref="chartRef" class="metric-chart" :aria-label="activeTitle" role="img" />
        <div v-if="!hasChartData" class="chart-empty">
          <strong>{{ running ? '等待采样数据' : '还没有运行数据' }}</strong
          ><span>{{
            running ? '收到足够采样点后将在这里绘制曲线' : '启动应用后，查看真实的资源使用情况'
          }}</span>
        </div>
      </div>
      <div class="chart-axis">
        <span>{{ activeMetric === 'qps' ? '当前统计值（非历史曲线）' : '历史采样' }}</span
        ><span>{{ paused ? '已暂停' : '最近采样' }}</span>
      </div>
    </section>
    <div class="monitor-facts">
      <div>
        <span class="wb-eyebrow">Application</span
        ><strong
          ><i class="wb-dot" :class="{ 'is-live': running }" />{{
            running ? '运行中' : '未运行'
          }}</strong
        >
      </div>
      <div>
        <span class="wb-eyebrow">Samples</span
        ><strong>{{ points.length }} <small>采样点</small></strong>
      </div>
      <div>
        <span class="wb-eyebrow">Retention</span
        ><strong>{{ windowMinutes }} <small>分钟窗口</small></strong>
      </div>
    </div>
    <details class="metric-details">
      <summary>统计口径</summary>
      <p>
        CPU 占单核百分比；内存是容器实际占用。QPS
        与错误率只统计经平台入口的请求，直连预览不计入。QPS 当前仅有统计值，不代表历史变化。
      </p>
      <p>
        服务端每 2 秒采集，面板每 5
        秒刷新。仅保留内存窗口，平台重启后历史不保留；缺失数据不会显示为零。
      </p>
    </details>
  </section>
</template>

<script setup lang="ts">
import './workbench.css'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { loadRuntimeMetrics, type MetricPoint } from '@/utils/appRuntimeApi'
import { drawMetricChart, formatBytes, formatMetricValue } from '@/utils/metricChart'

const props = withDefaults(
  defineProps<{
    appId: string
    apiBaseUrl?: string
    running?: boolean
  }>(),
  { apiBaseUrl: '/api', running: false },
)

const points = ref<MetricPoint[]>([])
const windowMinutes = ref(30)
const qps = ref<number | null>(null)
const errorRate = ref<number | null>(null)
const activeMetric = ref<'cpu' | 'memory' | 'qps'>('cpu')
const paused = ref(false)
const chartRef = ref<HTMLCanvasElement | null>(null)

let timer: number | null = null
let resizeObserver: ResizeObserver | null = null

/**
 * 采集失败的采样点值为 null —— 过滤掉而不是当成 0，
 * 否则曲线会凭空画出一段贴底的「零负载」，那是假的。
 */
const cpuValues = computed(() =>
  points.value.map((p) => p.cpuPercent).filter((v): v is number => v != null),
)
const memoryValues = computed(() =>
  points.value.map((p) => p.memoryBytes).filter((v): v is number => v != null),
)
/** QPS 只有一个当前值，没有时间序列 —— 用一条水平线表示，不伪造波动 */
const qpsValues = computed(() => (qps.value == null ? [] : [qps.value, qps.value]))

const latest = computed(() =>
  points.value.length > 0 ? points.value[points.value.length - 1] : null,
)

const cards = computed(() => [
  {
    id: 'cpu' as const,
    label: 'CPU',
    value: formatMetricValue(latest.value?.cpuPercent ?? null, 'percent') ?? '暂无数据',
    sub: '占单核百分比',
  },
  {
    id: 'memory' as const,
    label: '内存',
    value: formatMetricValue(latest.value?.memoryBytes ?? null, 'bytes') ?? '暂无数据',
    sub: latest.value?.memoryLimitBytes
      ? `上限 ${formatBytes(latest.value.memoryLimitBytes)}`
      : '容器占用',
  },
  {
    id: 'qps' as const,
    label: 'QPS',
    value: formatMetricValue(qps.value, 'qps') ?? '暂无数据',
    // 预览是直达容器端口的（跨源，应用拿不到平台凭据），不经过平台入口，
    // 所以这里的计数天然为 0。说清口径，比让用户以为指标坏了强。
    sub:
      errorRate.value == null
        ? '经平台入口的请求（预览直达不计入）'
        : `错误率 ${errorRate.value.toFixed(1)}%`,
  },
])

const activeTitle = computed(
  () => ({ cpu: 'CPU 使用率', memory: '内存占用', qps: '每秒请求数' })[activeMetric.value],
)

const legend = computed(() => {
  const dot = paused.value ? '已暂停' : '实时'
  if (activeMetric.value === 'cpu') {
    return `${dot} · ${formatMetricValue(latest.value?.cpuPercent ?? null, 'percent') ?? '暂无数据'}`
  }
  if (activeMetric.value === 'memory') {
    return `${dot} · ${formatMetricValue(latest.value?.memoryBytes ?? null, 'bytes') ?? '暂无数据'}`
  }
  return `${dot} · ${formatMetricValue(qps.value, 'qps') ?? '暂无数据'}`
})

const activeValues = computed(() => {
  if (activeMetric.value === 'cpu') return cpuValues.value
  if (activeMetric.value === 'memory') return memoryValues.value
  return qpsValues.value
})

const hasChartData = computed(() => activeValues.value.length >= 2)

function render(): void {
  if (!chartRef.value) {
    return
  }
  drawMetricChart(chartRef.value, activeValues.value, {
    // 内存从 0 起画：截断纵轴会让小波动看起来像剧烈震荡
    min: activeMetric.value === 'memory' ? 0 : undefined,
  })
}

async function refresh() {
  if (!props.appId) return
  if (!props.running) {
    points.value = []
    qps.value = null
    errorRate.value = null
    render()
    return
  }
  if (paused.value) {
    return
  }
  try {
    const data = await loadRuntimeMetrics({ appId: props.appId, apiBaseUrl: props.apiBaseUrl })
    points.value = data.points ?? []
    windowMinutes.value = data.windowMinutes ?? 30
    qps.value = data.qps ?? null
    errorRate.value = data.errorRatePercent ?? null
    render()
  } catch {
    // 采集失败保留上一批点，避免界面闪烁
  }
}

function startPolling() {
  stopPolling()
  void refresh()
  timer = window.setInterval(() => void refresh(), 5000)
}

function stopPolling() {
  if (timer != null) {
    window.clearInterval(timer)
    timer = null
  }
}

watch(activeMetric, () => render())
watch(paused, (value) => {
  if (!value) {
    void refresh()
  }
})

const themeObserver = new MutationObserver(() => {
  render()
})
onMounted(() => {
  themeObserver.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ['data-theme'],
  })
  if (props.running) {
    startPolling()
  } else {
    render()
  }
  if (chartRef.value?.parentElement) {
    resizeObserver = new ResizeObserver(() => render())
    resizeObserver.observe(chartRef.value.parentElement)
  }
})

watch(
  () => props.running,
  (running) => {
    if (running) {
      startPolling()
    } else {
      stopPolling()
      points.value = []
      qps.value = null
      errorRate.value = null
      render()
    }
  },
)

onBeforeUnmount(() => {
  themeObserver.disconnect()
  stopPolling()
  resizeObserver?.disconnect()
  resizeObserver = null
})
</script>

<style scoped>
.runtime-metrics {
  height: 100%;
  min-height: 0;
  overflow: auto;
  padding: 28px 26px;
  container-type: inline-size;
}
.monitor-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 28px;
}
.monitor-head .wb-actions {
  padding-top: 3px;
}
.monitor-window {
  padding: 6px 10px;
  border: 1px solid var(--wb-line);
  border-radius: 9px;
  color: var(--wb-text);
  font-size: 11px;
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--wb-line);
  border-bottom: 1px solid var(--wb-line);
  margin-bottom: 24px;
}
.metric {
  position: relative;
  border: 0;
  text-align: left;
  background: none;
  padding: 19px 14px;
  min-width: 0;
  cursor: pointer;
}
.metric + .metric {
  border-left: 1px solid var(--wb-line);
}
.metric-summary {
  cursor: default;
}
.metric.active:after {
  content: '';
  position: absolute;
  inset: auto 0 -1px;
  height: 1px;
  background: var(--wb-accent);
}
.metric-label {
  display: block;
  color: var(--wb-muted);
  font-size: 11px;
  margin-bottom: 8px;
}
.metric-value {
  display: block;
  font-size: 23px;
  font-weight: 650;
  letter-spacing: -0.04em;
  color: var(--wb-text);
  overflow-wrap: anywhere;
}
.metric-sub {
  display: block;
  color: var(--wb-muted);
  font-size: 10px;
  margin-top: 5px;
}
.chart-wrap {
  border-bottom: 1px solid var(--wb-line);
  padding-bottom: 18px;
}
.chart-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.chart-title strong {
  color: var(--wb-text);
  font-size: 13px;
  font-weight: 550;
}
.chart-legend {
  color: var(--wb-muted);
  font-size: 11px;
}
.chart-canvas-wrap {
  position: relative;
  height: 260px;
}
.metric-chart {
  width: 100%;
  height: 100%;
  display: block;
}
.chart-empty {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--wb-muted);
  font-size: 12px;
}
.chart-empty strong {
  color: var(--wb-text);
  font-weight: 500;
}
.chart-axis {
  display: flex;
  justify-content: space-between;
  color: var(--wb-muted);
  font-size: 10px;
  margin-top: 10px;
}
.monitor-facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  margin-top: 22px;
  border: 1px solid var(--wb-line);
  border-radius: 12px;
  overflow: hidden;
}
.monitor-facts > div {
  padding: 16px 14px;
}
.monitor-facts > div + div {
  border-left: 1px solid var(--wb-line);
}
.monitor-facts strong {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 7px;
  font-weight: 500;
  font-size: 13px;
}
.monitor-facts small {
  color: var(--wb-muted);
  font-weight: 400;
}
.metric-details {
  margin-top: 20px;
  color: var(--wb-muted);
  font-size: 12px;
}
.metric-details summary {
  cursor: pointer;
  color: var(--wb-text);
  border-bottom: 1px solid var(--wb-line);
  padding-bottom: 12px;
}
.metric-details p {
  line-height: 1.8;
}
@container (max-width:560px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .metric:nth-child(3) {
    border-left: 0;
    border-top: 1px solid var(--wb-line);
  }
  .metric:nth-child(4) {
    border-top: 1px solid var(--wb-line);
  }
  .metric-value {
    font-size: 21px;
  }
  .chart-canvas-wrap {
    height: 210px;
  }
  .monitor-facts > div {
    padding: 12px 8px;
  }
}
</style>
