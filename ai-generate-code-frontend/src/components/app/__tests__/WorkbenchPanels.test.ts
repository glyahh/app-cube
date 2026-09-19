import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import KnowledgePanel from '../KnowledgePanel.vue'
import RuntimeMetricsPanel from '../RuntimeMetricsPanel.vue'
import RuntimeLogsPanel from '../RuntimeLogsPanel.vue'

const api = vi.hoisted(() => ({
  status: vi.fn(),
  graph: vi.fn(),
  docs: vi.fn(),
  history: vi.fn(),
  ask: vi.fn(),
  logs: vi.fn(),
  metrics: vi.fn(),
}))
vi.mock('@/utils/appKnowledgeApi', () => ({
  loadKnowledgeStatus: api.status,
  loadKnowledgeGraph: api.graph,
  loadKnowledgeDocs: api.docs,
  loadKnowledgeQaHistory: api.history,
  askKnowledge: api.ask,
  cancelKnowledgeTask: vi.fn(),
  retryKnowledgeTask: vi.fn(),
  submitKnowledgeTask: vi.fn(),
}))
vi.mock('@/utils/appRuntimeApi', () => ({
  loadRuntimeLogs: api.logs,
  loadRuntimeMetrics: api.metrics,
}))
vi.mock('@/utils/metricChart', () => ({
  drawMetricChart: vi.fn(),
  formatBytes: () => '512 MB',
  formatMetricValue: (value: number | null) => (value == null ? null : String(value)),
}))
vi.mock('../KnowledgeGraph.vue', () => ({
  default: { template: '<div data-testid="graph">真实图谱入口</div>' },
}))

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe() {}
      disconnect() {}
    },
  )
  api.status.mockResolvedValue({ hasKnowledge: true, nodeCount: 3, edgeCount: 2, docCount: 1 })
  api.graph.mockResolvedValue({ nodes: [], edges: [] })
  api.docs.mockResolvedValue([
    {
      id: 1,
      title: '项目概览',
      content:
        '## 架构\n\n**真实内容**\n\n| 文件 | 职责 |\n| --- | --- |\n| main.js | 入口 |\n\n<script>alert(1)</script>',
    },
  ])
  api.history.mockResolvedValue([])
  api.ask.mockResolvedValue({})
  api.logs.mockResolvedValue({
    runNo: 2,
    content: '[web] ready\n[web] ERROR failed',
    truncated: false,
  })
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('V6 workbench panels', () => {
  it('opens graph first and renders safe documentation instead of raw Markdown', async () => {
    const wrapper = mount(KnowledgePanel, { props: { appId: '1' } })
    await flushPromises()
    expect(wrapper.find('[data-testid="graph"]').exists()).toBe(true)
    await wrapper
      .findAll('.wb-segments button')
      .find((button) => button.text() === '文档')!
      .trigger('click')
    expect(wrapper.find('.wb-markdown h2').text()).toBe('架构')
    expect(wrapper.find('.wb-markdown table').exists()).toBe(true)
    expect(wrapper.find('.wb-markdown script').exists()).toBe(false)
    expect(wrapper.find('.wb-markdown strong').text()).toBe('真实内容')
    wrapper.unmount()
  })

  it('keeps questions on the read-only knowledge endpoint', async () => {
    const wrapper = mount(KnowledgePanel, { props: { appId: '1' } })
    await flushPromises()
    await wrapper
      .findAll('.wb-segments button')
      .find((button) => button.text() === '问答')!
      .trigger('click')
    await wrapper.get('textarea').setValue('入口在哪？')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(api.ask).toHaveBeenCalledWith({ appId: '1', apiBaseUrl: '/api', question: '入口在哪？' })
    wrapper.unmount()
  })

  it('shows load failure rather than claiming an empty successful result', async () => {
    api.status.mockRejectedValue(new Error('接口暂不可用'))
    const wrapper = mount(KnowledgePanel, { props: { appId: '1' } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('接口暂不可用')
    wrapper.unmount()
  })

  it('does not fabricate zero metrics for an inactive app', async () => {
    const wrapper = mount(RuntimeMetricsPanel, { props: { appId: '1', running: false } })
    await flushPromises()
    expect(api.metrics).not.toHaveBeenCalled()
    expect(wrapper.findAll('.metric-value').every((card) => card.text() === '暂无数据')).toBe(true)
    await wrapper.findAll('button.metric')[1]!.trigger('click')
    expect(wrapper.get('.chart-title strong').text()).toBe('内存占用')
    wrapper.unmount()
  })

  it('filters logs as text and preserves raw content safety', async () => {
    api.logs.mockResolvedValue({
      runNo: 2,
      content: '[web] ready\nERROR <script>bad()</script>',
      truncated: true,
    })
    const wrapper = mount(RuntimeLogsPanel, { props: { appId: '1' } })
    await flushPromises()
    await wrapper.get('input[type="search"]').setValue('error')
    expect(wrapper.get('.log-body').text()).toBe('ERROR <script>bad()</script>')
    expect(wrapper.find('.log-body script').exists()).toBe(false)
    expect(wrapper.get('.console-footer').text()).toContain('1 行匹配')
    wrapper.unmount()
  })
})
