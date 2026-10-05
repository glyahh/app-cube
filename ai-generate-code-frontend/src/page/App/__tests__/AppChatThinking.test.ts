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
    Tooltip: shell, Alert: shell, Textarea: shell, Checkbox: shell, CheckboxGroup: shell,
  }
})
vi.mock('@/components/loop/AppLoopInjectBar.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/CodeBlock.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/CollapsibleCodeBlock.vue', () => ({ default: { template: '<div />' } }))
// shallowMount 会 stub 掉子组件，但父组件会调用它 defineExpose 的方法，stub 需要提供
vi.mock('@/components/app/ProjectFilesModal.vue', () => ({
  default: {
    name: 'ProjectFilesModal',
    template: '<div />',
    methods: {
      hasUnsavedChanges: () => false,
      save: () => Promise.resolve(true),
      discardDraft: () => {},
      promptUnsavedChoice: () => {},
    },
  },
}))
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
vi.mock('@/stores/appearance', () => ({ useAppearanceStore: () => ({ workflowEnabled: false, smoothScroll: true }) }))
vi.mock('@/stores/executionPreference', () => ({ useExecutionPreference: () => mocks.preference }))
vi.mock('@/utils/agentStreamTransport', () => ({ openAgentStream: mocks.stream }))
vi.mock('@/utils/projectFilesEcho', () => ({ loadProjectFilesEchoFromDisk: vi.fn().mockResolvedValue([]), filterLockFiles: (items: unknown) => items }))

if (typeof HTMLElement !== 'undefined' && !HTMLElement.prototype.scrollTo) {
  HTMLElement.prototype.scrollTo = () => {}
}
if (typeof Element !== 'undefined' && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

const mountPage = (props: { importedProject?: boolean } = {}) => shallowMount(AppChatView, {
  props,
  global: {
    renderStubDefaultSlot: true,
    stubs: {
      ThinkingStage: false,
      ExecutionProcess: false,
      ExecutionTerminal: false,
      // shallowMount 会把 ProjectFilesModal 替换成空 stub，defineExpose 的方法就没了，
      // 而父组件在发送消息前会调用它们
      ProjectFilesModal: {
        template: '<div />',
        methods: {
          hasUnsavedChanges: () => false,
          save: () => Promise.resolve(true),
          discardDraft: () => {},
          promptUnsavedChoice: () => {},
        },
      },
    },
  },
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

  it('导入工程不显示新应用生成标语，原入口保持不变', async () => {
    const original = mountPage()
    await flushPromises()
    expect(original.find('.app-subtitle').exists()).toBe(true)
    original.unmount()
    const imported = mountPage({ importedProject: true })
    await flushPromises()
    expect(imported.find('.app-subtitle').exists()).toBe(false)
    imported.unmount()
  })

  it('does not guess preview from legacy type and keeps starting runtime stoppable', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      workspace: unknown
      previewUrl: string
      runtimeStatus: unknown
      runtimeActive: boolean
      runtimeRunning: boolean
    }
    vm.workspace = null
    expect(vm.previewUrl).toBe('')
    vm.runtimeStatus = { status: 'STARTING', running: false }
    expect(vm.runtimeActive).toBe(true)
    expect(vm.runtimeRunning).toBe(false)
    wrapper.unmount()
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

  describe('chat container scrolling during thinking', () => {
    let originalIntersectionObserver: unknown
    let observerCallback: ((entries: Array<{ isIntersecting: boolean }>) => void) | null = null

    beforeEach(() => {
      if (!Element.prototype.scrollIntoView) {
        Element.prototype.scrollIntoView = () => {}
      }
      if (!HTMLElement.prototype.scrollTo) {
        HTMLElement.prototype.scrollTo = () => {}
      }
      originalIntersectionObserver = (globalThis as unknown as { IntersectionObserver?: unknown }).IntersectionObserver
      observerCallback = null
      class MockIntersectionObserver {
        constructor(cb: (entries: Array<{ isIntersecting: boolean }>) => void) {
          observerCallback = cb
        }
        observe = vi.fn()
        unobserve = vi.fn()
        disconnect = vi.fn()
      }
      ;(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = MockIntersectionObserver
    })

    const restoreObserver = () => {
      if (originalIntersectionObserver !== undefined) {
        ;(globalThis as unknown as { IntersectionObserver: unknown }).IntersectionObserver = originalIntersectionObserver
      } else {
        delete (globalThis as unknown as { IntersectionObserver?: unknown }).IntersectionObserver
      }
    }

    const waitForAutoScroll = async () => {
      await nextTick()
      await new Promise((r) => setTimeout(r, 50))
      await nextTick()
      await flushPromises()
    }

    it('thinking events only scroll chat container with behavior auto and never scrollIntoView or window', async () => {
      const scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
      const windowScrollToSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})

      const wrapper = mountPage()
      await flushPromises()

      const container = wrapper.get('.chat-messages').element as HTMLElement
      Object.defineProperty(container, 'scrollHeight', { value: 2400, configurable: true, writable: true })
      const containerScrollToSpy = vi.spyOn(container, 'scrollTo').mockImplementation(() => {})

      await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('开始思考测试')
      const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers

      // 1. 思考开始
      handlers.onEvent('thinking.started', { thinkingId: 'think:1', summary: '开始构思', details: '分析结构' })
      await waitForAutoScroll()
      expect(containerScrollToSpy).toHaveBeenCalledWith({ top: 2400, behavior: 'auto' })
      expect(scrollIntoViewSpy).not.toHaveBeenCalled()
      expect(windowScrollToSpy).not.toHaveBeenCalled()

      containerScrollToSpy.mockClear()
      scrollIntoViewSpy.mockClear()
      windowScrollToSpy.mockClear()

      // 2. 思考更新
      handlers.onEvent('thinking.delta', { thinkingId: 'think:1', summary: '正在思考', details: '调整布局' })
      await waitForAutoScroll()
      expect(containerScrollToSpy).toHaveBeenCalledWith({ top: 2400, behavior: 'auto' })
      expect(scrollIntoViewSpy).not.toHaveBeenCalled()
      expect(windowScrollToSpy).not.toHaveBeenCalled()

      containerScrollToSpy.mockClear()
      scrollIntoViewSpy.mockClear()
      windowScrollToSpy.mockClear()

      // 3. 思考结束
      handlers.onEvent('thinking.completed', { thinkingId: 'think:1', details: '构思完成' })
      await waitForAutoScroll()
      expect(containerScrollToSpy).toHaveBeenCalledWith({ top: 2400, behavior: 'auto' })
      expect(scrollIntoViewSpy).not.toHaveBeenCalled()
      expect(windowScrollToSpy).not.toHaveBeenCalled()

      scrollIntoViewSpy.mockRestore()
      windowScrollToSpy.mockRestore()
      restoreObserver()
      wrapper.unmount()
    })

    it('does not auto scroll when bottom has left the viewport', async () => {
      const scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
      const windowScrollToSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})

      const wrapper = mountPage()
      await flushPromises()

      const container = wrapper.get('.chat-messages').element as HTMLElement
      Object.defineProperty(container, 'scrollHeight', { value: 2400, configurable: true, writable: true })
      const containerScrollToSpy = vi.spyOn(container, 'scrollTo').mockImplementation(() => {})

      await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('上翻测试')
      const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers

      // 先触发一次以初始化 observer
      handlers.onEvent('thinking.started', { thinkingId: 'think:2', summary: '初始化', details: '' })
      await waitForAutoScroll()
      containerScrollToSpy.mockClear()

      // 模拟底部离开可视区域
      expect(observerCallback).not.toBeNull()
      observerCallback!([{ isIntersecting: false }])
      await nextTick()

      // 再次产生思考输出
      handlers.onEvent('thinking.delta', { thinkingId: 'think:2', summary: '继续思考', details: '新内容' })
      await waitForAutoScroll()

      expect(containerScrollToSpy).not.toHaveBeenCalled()
      expect(scrollIntoViewSpy).not.toHaveBeenCalled()
      expect(windowScrollToSpy).not.toHaveBeenCalled()

      scrollIntoViewSpy.mockRestore()
      windowScrollToSpy.mockRestore()
      restoreObserver()
      wrapper.unmount()
    })

    it('clicking scroll-to-bottom button resumes auto-scroll and uses smooth behavior', async () => {
      const scrollIntoViewSpy = vi.spyOn(Element.prototype, 'scrollIntoView').mockImplementation(() => {})
      const windowScrollToSpy = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})

      const wrapper = mountPage()
      await flushPromises()

      const container = wrapper.get('.chat-messages').element as HTMLElement
      Object.defineProperty(container, 'scrollHeight', { value: 3000, configurable: true, writable: true })
      const containerScrollToSpy = vi.spyOn(container, 'scrollTo').mockImplementation(() => {})

      await (wrapper.vm as unknown as { sendMessage(text: string): Promise<void> }).sendMessage('回到底部测试')
      const handlers = mocks.stream.mock.calls[0]![2] as AgentStreamHandlers

      // 产生消息并模拟底部离开可视区域
      handlers.onEvent('thinking.started', { thinkingId: 'think:3', summary: '思考中', details: '' })
      await waitForAutoScroll()
      observerCallback!([{ isIntersecting: false }])
      await nextTick()
      containerScrollToSpy.mockClear()

      // 此时“回到底部”按钮应该可见
      const scrollBottomBtn = wrapper.find('.scroll-bottom-btn')
      expect(scrollBottomBtn.exists()).toBe(true)

      // 点击“回到底部”按钮
      await scrollBottomBtn.trigger('click')
      await waitForAutoScroll()

      expect(containerScrollToSpy).toHaveBeenCalledWith({ top: 3000, behavior: 'smooth' })
      expect(scrollIntoViewSpy).not.toHaveBeenCalled()
      expect(windowScrollToSpy).not.toHaveBeenCalled()

      scrollIntoViewSpy.mockRestore()
      windowScrollToSpy.mockRestore()
      restoreObserver()
      wrapper.unmount()
    })

    it('does not throw when scrollToBottom is invoked without mounted container', () => {
      const vm = {
        scrollToBottom: (AppChatView as unknown as { setup: () => { scrollToBottom: (opts?: { smooth?: boolean }) => void } })
      }
      const wrapper = mountPage()
      const comp = wrapper.vm as unknown as {
        chatMessagesRef: { value: HTMLElement | null }
        scrollToBottom: (opts?: { smooth?: boolean }) => void
      }
      comp.chatMessagesRef.value = null

      expect(() => {
        comp.scrollToBottom()
        comp.scrollToBottom({ smooth: true })
      }).not.toThrow()

      restoreObserver()
      wrapper.unmount()
    })
  })
})
