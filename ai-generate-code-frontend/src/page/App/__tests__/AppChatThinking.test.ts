import { shallowMount, flushPromises } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AppChatView from '../AppChatView.vue'
import type { AgentStreamHandlers } from '@/utils/agentStreamTransport'

const mocks = vi.hoisted(() => ({
  history: vi.fn(), app: vi.fn(), stream: vi.fn(), preference: {} as { expanded: boolean },
}))
vi.mock('ant-design-vue', async () => {
  const { defineComponent, h } = await import('vue')
  const shell = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) })
  return {
    message: { info: vi.fn(), warning: vi.fn(), error: vi.fn(), success: vi.fn(), loading: vi.fn(() => vi.fn()), destroy: vi.fn() },
    Button: shell, Input: shell, Spin: shell, Empty: shell, Modal: shell,
    Tooltip: shell, Alert: shell, Textarea: shell,
  }
})
vi.mock('@/components/loop/AppLoopInjectBar.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/CodeBlock.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/CollapsibleCodeBlock.vue', () => ({ default: { template: '<div />' } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '1' }, query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
vi.mock('@/api/appController', () => ({ appGetVoUsingGet: mocks.app, appApplyUsingPost: vi.fn() }))
vi.mock('@/api/chatHistoryController', () => ({
  chatHistoryAppAppIdUsingGet: mocks.history,
  chatHistoryOpenApiExportAppIdUsingGet: vi.fn(),
  chatHistoryRoundCountAppIdUsingGet: vi.fn().mockResolvedValue({ data: { code: 0, data: 1 } }),
}))
vi.mock('@/stores/UserLogin', () => ({ UserLoginStore: () => ({ userLogin: { id: '7' } }) }))
vi.mock('@/stores/appearance', () => ({ useAppearanceStore: () => ({ workflowEnabled: false }) }))
vi.mock('@/stores/executionPreference', () => ({ useExecutionPreference: () => mocks.preference }))
vi.mock('@/utils/agentStreamTransport', () => ({ openAgentStream: mocks.stream }))
vi.mock('@/utils/projectFilesEcho', () => ({ loadProjectFilesEchoFromDisk: vi.fn().mockResolvedValue([]), filterLockFiles: (items: unknown) => items }))

const mountPage = () => shallowMount(AppChatView, {
  global: { renderStubDefaultSlot: true, stubs: { ThinkingStage: false, ExecutionProcess: false, ExecutionTerminal: false } },
})

describe('AppChatView thinking pipeline', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.preference = reactive({ expanded: false })
    mocks.app.mockResolvedValue({ data: { code: 0, data: { id: '1', userId: '7', codeGenType: 'html' } } })
    mocks.history.mockResolvedValue({ data: { code: 0, data: { records: [
      { id: 10, messageType: 'ai', message: '旧回答', createTime: '2026-09-13 10:00:00' },
      { id: 11, messageType: 'ai', message: '校徽已完成', createTime: '2026-09-14 10:00:00',
        thinkingSegments: JSON.stringify([{ thinkingId: 'run:1', summary: '构思星空校徽', details: ['采用环绕构图'], status: 'completed' }]) },
    ], totalRow: 2 } } })
    mocks.stream.mockReturnValue({ close: vi.fn(), transport: () => 'websocket' })
  })

  it('history defaults closed, ignores the old preference and keeps answers outside', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const process = wrapper.get('.execution-process')
    expect(process.get('button').attributes('aria-expanded')).toBe('false')
    expect(wrapper.text()).toContain('校徽已完成')
    expect(process.text()).not.toContain('校徽已完成')
    mocks.preference.expanded = true
    await nextTick()
    expect(process.get('button').attributes('aria-expanded')).toBe('false')
    await process.get('button').trigger('click')
    expect(process.get('button').attributes('aria-expanded')).toBe('true')
    expect(process.text()).toContain('采用环绕构图')
    expect(process.find('.thinking-line__toggle').exists()).toBe(false)
    wrapper.unmount()
  })

  it('streams a timeline, preserves terminal identity and manual state, hides placeholders', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('制作星空校徽')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('thinking.started', { thinkingId: 'new:1', summary: '构思校徽', details: '比较构图' })
    await nextTick()
    const process = wrapper.findAll('.execution-process').at(-1)!
    expect(process.get('button').attributes('aria-expanded')).toBe('true')
    handlers.onEvent('thinking.delta', { thinkingId: 'new:1', summary: '确定蓝紫配色', details: '确定蓝紫配色' })
    handlers.onEvent('tool.started', { callId: 'cmd:1', toolName: 'execute', input: { command: 'node --version' } })
    await nextTick()
    const terminal = process.get('details')
    ;(terminal.element as HTMLDetailsElement).open = true
    await process.get('button').trigger('click')
    handlers.onEvent('tool.finished', { callId: 'cmd:1', toolName: 'execute', status: 'succeeded', outputPreview: 'v22.0.0' })
    handlers.onEvent('thinking.completed', { thinkingId: 'new:1', details: '部分内部执行信息已省略' })
    await nextTick()
    expect(process.get('button').attributes('aria-expanded')).toBe('false')
    expect(process.get('details').element).toBe(terminal.element)
    expect((terminal.element as HTMLDetailsElement).open).toBe(true)
    expect(process.get('details').text()).toContain('node --version')
    expect(process.get('details').text()).toContain('v22.0.0')
    expect(process.get('details').text()).toContain('成功')
    expect(wrapper.text()).not.toContain('部分内部执行信息已省略')
    expect(process.get('.thinking-line').text().match(/确定蓝紫配色/g)).toHaveLength(1)
    await process.get('button').trigger('click')
    handlers.onEvent('done', {})
    await flushPromises()
    expect(process.get('button').attributes('aria-expanded')).toBe('true')
    wrapper.unmount()
  })

  it('automatically closes an untouched process when the stream ends', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('检查')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('thinking.started', { thinkingId: 'new:2', summary: '检查页面', details: '' })
    await nextTick()
    const process = wrapper.findAll('.execution-process').at(-1)!
    expect(process.get('button').attributes('aria-expanded')).toBe('true')
    handlers.onEvent('done', {})
    await flushPromises()
    expect(process.get('button').attributes('aria-expanded')).toBe('false')
    wrapper.unmount()
  })

  it('omits empty historical thinking and removes placeholders from answers without changing records', async () => {
    const record = { id: 12, messageType: 'ai', message: '完成\n部分内部执行信息已省略\n正常内容',
      thinkingSegments: JSON.stringify([{ thinkingId: 'empty', summary: '部分内部执行信息已省略', details: ['部分内部执行信息已省略'], status: 'completed' }]) }
    mocks.history.mockResolvedValue({ data: { code: 0, data: { records: [record], totalRow: 1 } } })
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.find('.execution-process').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('部分内部执行信息已省略')
    expect(wrapper.text()).toContain('正常内容')
    expect(record.message).toContain('部分内部执行信息已省略')
    wrapper.unmount()
  })
  it.each(['failed', 'cancelled'])('keeps terminal %s visible as a status with no fabricated output', async (status) => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('检查')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('tool.started', { callId: 'failure:1', toolName: 'execute' })
    handlers.onEvent('tool.finished', { callId: 'failure:1', toolName: 'execute', status })
    await nextTick()
    const terminal = wrapper.get('details')
    expect(terminal.attributes('open')).toBeUndefined()
    expect(terminal.text()).toContain(status === 'failed' ? '执行失败，无可显示的错误详情' : '已取消')
    expect(terminal.text()).toContain(status === 'failed' ? '执行失败，无可显示的错误详情' : '暂无可显示的输出')
    expect(terminal.find('pre').exists()).toBe(false)
    wrapper.unmount()
  })

  it('shows 执行成功，无输出 when succeeded with empty output', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('测试空输出')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('tool.started', { callId: 'empty:1', toolName: 'execute', input: { command: 'true' } })
    handlers.onEvent('tool.finished', { callId: 'empty:1', toolName: 'execute', status: 'succeeded', outputPreview: '' })
    await nextTick()
    const terminal = wrapper.get('details')
    expect(terminal.text()).toContain('成功')
    expect(terminal.text()).toContain('执行成功，无输出')
    expect(terminal.text()).toContain('true')
    wrapper.unmount()
  })

  it('shows error summary when tool fails with outputPreview', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('测试失败输出')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('tool.started', { callId: 'err:1', toolName: 'execute', input: { command: 'cat /not/exist' } })
    handlers.onEvent('tool.finished', { callId: 'err:1', toolName: 'execute', status: 'failed', outputPreview: 'No such file or directory' })
    await nextTick()
    const terminal = wrapper.get('details')
    expect(terminal.text()).toContain('失败')
    expect(terminal.text()).toContain('cat /not/exist')
    expect(terminal.text()).toContain('No such file or directory')
    wrapper.unmount()
  })

  it('shows placeholder when content is filtered by backend', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('测试过滤')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('tool.started', { callId: 'filt:1', toolName: 'execute', input: { command: 'env' } })
    handlers.onEvent('tool.finished', { callId: 'filt:1', toolName: 'execute', status: 'succeeded', outputPreview: '部分内部执行信息已省略' })
    await nextTick()
    const terminal = wrapper.get('details')
    expect(terminal.text()).toContain('成功')
    expect(terminal.text()).toContain('（内容已被安全过滤）')
    wrapper.unmount()
  })

  it('shows 已结束（未收到结果） when stream completes without tool.finished', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('测试未收到结果')
    const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers
    handlers.onEvent('tool.started', { callId: 'pend:1', toolName: 'execute', input: { command: 'sleep 10' } })
    await nextTick()
    handlers.onEvent('done', {})
    await flushPromises()
    const terminal = wrapper.get('details')
    expect(terminal.text()).toContain('已结束（未收到结果）')
    expect(terminal.text()).toContain('sleep 10')
    wrapper.unmount()
  })
})
