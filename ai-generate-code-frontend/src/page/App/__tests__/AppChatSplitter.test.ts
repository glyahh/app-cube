import { shallowMount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'
import AppChatView from '../AppChatView.vue'

const appearanceState = {
  workflowEnabled: false,
  smoothScroll: true,
  previewExpanded: true,
}

vi.mock('ant-design-vue', async () => {
  const { defineComponent, h } = await import('vue')
  const shell = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  })
  return {
    message: {
      info: vi.fn(),
      warning: vi.fn(),
      error: vi.fn(),
      success: vi.fn(),
      loading: vi.fn(() => vi.fn()),
      destroy: vi.fn(),
    },
    Button: shell,
    Input: shell,
    Spin: shell,
    Empty: shell,
    Modal: shell,
    Tooltip: shell,
    Alert: shell,
    Textarea: shell,
    Checkbox: shell,
    CheckboxGroup: shell,
  }
})
vi.mock('@/components/loop/AppLoopInjectBar.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/CodeBlock.vue', () => ({ default: { template: '<div />' } }))
vi.mock('@/components/CollapsibleCodeBlock.vue', () => ({ default: { template: '<div />' } }))
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
vi.mock('@/api/appController', () => ({
  appGetVoUsingGet: vi
    .fn()
    .mockResolvedValue({ data: { code: 0, data: { id: '1', codeGenType: 'html' } } }),
  appApplyUsingPost: vi.fn(),
}))
vi.mock('@/api/chatHistoryController', () => ({
  chatHistoryAppAppIdUsingGet: vi.fn().mockResolvedValue({ data: { code: 0, data: [] } }),
  chatHistoryOpenApiExportAppIdUsingGet: vi.fn(),
  chatHistoryRoundCountAppIdUsingGet: vi.fn().mockResolvedValue({ data: { code: 0, data: 1 } }),
}))
vi.mock('@/stores/UserLogin', () => ({ UserLoginStore: () => ({ userLogin: { id: '7' } }) }))
vi.mock('@/stores/appearance', () => ({ useAppearanceStore: () => appearanceState }))
vi.mock('@/stores/executionPreference', () => ({ useExecutionPreference: () => ({}) }))
vi.mock('@/utils/agentStreamTransport', () => ({ openAgentStream: vi.fn() }))
vi.mock('@/utils/projectFilesEcho', () => ({
  loadProjectFilesEchoFromDisk: vi.fn().mockResolvedValue([]),
  filterLockFiles: (items: unknown) => items,
}))

describe('AppChatView main-splitter 控制按钮', () => {
  it('上方按钮放大预览区（▶），中间恢复等分（⛶），下方按钮放大聊天区（◀）', async () => {
    const wrapper = shallowMount(AppChatView, {
      global: {
        stubs: {
          ATooltip: false,
          Transition: false,
        },
      },
    })
    await flushPromises()

    expect(wrapper.find('.preview-header-left').exists()).toBe(false)
    expect(wrapper.find('.preview-header .preview-header-right').exists()).toBe(true)
    expect(wrapper.find('.runtime-status-pill').exists()).toBe(false)

    const splitter = wrapper.find('.main-splitter')
    expect(splitter.exists()).toBe(true)

    const buttons = splitter.findAll('.splitter-btn')
    expect(buttons).toHaveLength(3)

    // 上方按钮：放大预览框
    const topBtn = buttons[0]!
    expect(topBtn.text()).toBe('▶')
    expect(topBtn.attributes('title')).toBe('放大预览区')
    expect(topBtn.attributes('aria-label')).toBe('放大预览区')

    // 中间按钮：恢复等分
    const midBtn = buttons[1]!
    expect(midBtn.text()).toBe('⛶')
    expect(midBtn.attributes('title')).toBe('恢复等分')
    expect(midBtn.attributes('aria-label')).toBe('恢复等分')

    // 下方按钮：放大聊天框
    const bottomBtn = buttons[2]!
    expect(bottomBtn.text()).toBe('◀')
    expect(bottomBtn.attributes('title')).toBe('放大聊天区')
    expect(bottomBtn.attributes('aria-label')).toBe('放大聊天区')

    const vm = wrapper.vm as unknown as {
      focusedSide: 'none' | 'chat' | 'preview'
      toggleFocusSide: (side: 'chat' | 'preview') => void
    }
    expect(vm.focusedSide).toBe('none')

    // 点击上方按钮触发放大预览
    await topBtn.trigger('click')
    expect(vm.focusedSide).toBe('preview')

    // 再次点击上方按钮退出放大，退回 none
    await topBtn.trigger('click')
    expect(vm.focusedSide).toBe('none')

    // 点击下方按钮触发放大聊天
    await bottomBtn.trigger('click')
    expect(vm.focusedSide).toBe('chat')

    // 再次点击下方按钮退出放大，退回 none
    await bottomBtn.trigger('click')
    expect(vm.focusedSide).toBe('none')

    // 点击中间按钮将 focusedSide 置为 none
    vm.focusedSide = 'preview'
    await midBtn.trigger('click')
    expect(vm.focusedSide).toBe('none')

    wrapper.unmount()
  })
})
