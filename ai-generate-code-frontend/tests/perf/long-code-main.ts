import { createApp } from 'vue'
import LongCodeHarness from './LongCodeHarness.vue'

declare global {
  interface Window {
    __perf?: {
      longTasks: number[]
      layoutShifts: Array<{
        value: number
        startTime: number
        sources: string[]
      }>
    }
  }
}

const longTasks: number[] = []
const layoutShifts: NonNullable<Window['__perf']>['layoutShifts'] = []
new PerformanceObserver((list) => {
  list.getEntries().forEach((entry) => longTasks.push(entry.duration))
}).observe({ type: 'longtask', buffered: true })

new PerformanceObserver((list) => {
  list.getEntries().forEach((entry) => {
    const shift = entry as PerformanceEntry & {
      value: number
      hadRecentInput: boolean
      sources?: Array<{ node?: Node | null }>
    }
    if (shift.hadRecentInput) return
    layoutShifts.push({
      value: shift.value,
      startTime: shift.startTime,
      sources: (shift.sources ?? []).map(({ node }) => {
        if (!(node instanceof Element)) return node?.nodeName ?? 'unknown'
        return node.getAttribute('data-cls-source') || node.className || node.tagName
      }),
    })
  })
}).observe({ type: 'layout-shift', buffered: true })

window.__perf = { longTasks, layoutShifts }

const targetBytes = Number(new URLSearchParams(location.search).get('bytes') || 100_000)
createApp(LongCodeHarness, { targetBytes }).mount('#app')
