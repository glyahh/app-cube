/**
 * 指标曲线绘制（Canvas 2D）。
 *
 * <p>移植自 V6 原型 `docs/assets/cosmic-code-workbench-v6.html` 的 `drawMetricChart`：
 * 4 条水平网格线、线性渐变填充、1.5px 折线，纵轴按 25% 余量取上下界。
 *
 * <p>与原型的差别只在数据来源：原型用正弦加噪声造演示数据，
 * 这里画的是真实采样点，因此要处理空数据与全等值这两种退化情况
 * （原型的数据永远有起伏，不会遇到）。
 */

export interface MetricChartOptions {
  /** 折线颜色，默认取 V6 的强调色 */
  lineColor?: string
  /** 渐变起色 */
  fillColor?: string
  /** 纵轴下界（真实单位），省略则按数据自适应 */
  min?: number
  /** 纵轴上界 */
  max?: number
}

const DEFAULT_LINE = 'rgba(157,160,255,.95)'
const DEFAULT_FILL = 'rgba(143,147,255,.16)'
const GRID_COLOR = 'rgba(255,255,255,.055)'
/** 上下各留 25% 余量，避免折线贴着边框 */
const PADDING_RATIO = 0.25
/** 折线占绘图区高度的比例，其余留给上下留白 */
const PLOT_RATIO = 0.82
const PLOT_OFFSET = 0.09

/**
 * 在 canvas 上画一条指标曲线。
 *
 * @param canvas 目标画布
 * @param values 采样值，按时间升序
 * @returns 是否真的画了东西（数据不足时返回 false，调用方据此显示空状态）
 */
export function drawMetricChart(
  canvas: HTMLCanvasElement,
  values: number[],
  options: MetricChartOptions = {},
): boolean {
  const rect = canvas.getBoundingClientRect()
  if (rect.width <= 0 || rect.height <= 0) {
    return false
  }

  // dpr 上限 1.5：指标图不需要更清晰，省下的算力留给图谱
  const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
  canvas.width = Math.round(rect.width * dpr)
  canvas.height = Math.round(rect.height * dpr)
  const ctx = canvas.getContext('2d')
  if (!ctx) {
    return false
  }
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

  const width = rect.width
  const height = rect.height
  ctx.clearRect(0, 0, width, height)

  // 网格先画：没有数据时它就是「这里本来该有曲线」的提示
  ctx.strokeStyle = document.documentElement.dataset.theme === 'dark' ? GRID_COLOR : '#e2e5eb'
  ctx.lineWidth = 1
  for (let i = 1; i < 5; i++) {
    const y = (height * i) / 5
    ctx.beginPath()
    ctx.moveTo(0, y + 0.5)
    ctx.lineTo(width, y + 0.5)
    ctx.stroke()
  }

  if (values.length < 2) {
    return false
  }

  let min = options.min ?? Math.min(...values)
  let max = options.max ?? Math.max(...values)
  if (options.min == null || options.max == null) {
    const span = max - min
    const padding = span * PADDING_RATIO || 1
    min -= padding
    max += padding
  }
  // 全等值时 max===min，除零会画出 NaN 坐标
  const range = max - min || 1

  const points = values.map((value, index) => ({
    x: (index / (values.length - 1)) * width,
    y: height - ((value - min) / range) * height * PLOT_RATIO - height * PLOT_OFFSET,
  }))

  const gradient = ctx.createLinearGradient(0, 0, 0, height)
  gradient.addColorStop(0, options.fillColor ?? DEFAULT_FILL)
  gradient.addColorStop(1, 'rgba(143,147,255,0)')

  ctx.beginPath()
  ctx.moveTo(points[0]!.x, height)
  for (const point of points) {
    ctx.lineTo(point.x, point.y)
  }
  ctx.lineTo(width, height)
  ctx.closePath()
  ctx.fillStyle = gradient
  ctx.fill()

  ctx.beginPath()
  points.forEach((point, index) => {
    if (index) {
      ctx.lineTo(point.x, point.y)
    } else {
      ctx.moveTo(point.x, point.y)
    }
  })
  ctx.strokeStyle = options.lineColor ?? (document.documentElement.dataset.theme === 'dark' ? DEFAULT_LINE : '#6266cf')
  ctx.lineWidth = 1.5
  ctx.lineJoin = 'round'
  ctx.stroke()

  return true
}

/**
 * 把指标数值格式化成卡片上的展示文本。
 *
 * @returns null 表示「暂无数据」—— 调用方要显示这几个字，而不是 0
 */
export function formatMetricValue(value: number | null, unit: 'percent' | 'bytes' | 'qps'): string | null {
  if (value == null || !Number.isFinite(value)) {
    return null
  }
  switch (unit) {
    case 'percent':
      return `${value.toFixed(2)}%`
    case 'qps':
      return value.toFixed(2)
    case 'bytes':
      return formatBytes(value)
    default:
      return String(value)
  }
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}
