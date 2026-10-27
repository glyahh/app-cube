import { shallowMount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import CodeGenerateEntry from '../CodeGenerateEntry.vue'

const mocks = vi.hoisted(() => ({
  push: vi.fn(),
  addApp: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
  useRoute: () => ({ fullPath: '/code/generate' }),
}))

vi.mock('@/api/appController', () => ({
  appAddUsingPost: mocks.addApp,
}))

vi.mock('@/stores/UserLogin', () => ({
  UserLoginStore: () => ({ userLogin: { id: '1', userName: 'tester' } }),
}))

vi.mock('@/stores/appearance', () => ({
  useAppearanceStore: () => ({ defaultCodeType: 'multi_file', workflowEnabled: false }),
}))

describe('CodeGenerateEntry', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('uses one immutable selection snapshot for app creation and navigation', async () => {
    let resolveCreate!: (value: unknown) => void
    mocks.addApp.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveCreate = resolve
      }),
    )
    const wrapper = shallowMount(CodeGenerateEntry)
    const vm = wrapper.vm as unknown as {
      prompt: string
      handleLoopChange: (info: { id: string } | null) => void
      handleCreate: () => Promise<void>
    }
    vm.prompt = 'build an app'
    vm.handleLoopChange({ id: '1001' })

    const creation = vm.handleCreate()
    vm.handleLoopChange({ id: '2002' })
    resolveCreate({ data: { code: 20000, data: '9001' } })
    await creation

    expect(mocks.addApp).toHaveBeenCalledWith(
      expect.objectContaining({
        body: expect.objectContaining({ loopIds: ['1001'] }),
      }),
    )
    expect(mocks.push).toHaveBeenCalledWith(
      expect.objectContaining({
        query: expect.objectContaining({ loopId: '1001' }),
      }),
    )
  })

  it('ignores repeated create attempts while a request is pending', async () => {
    let resolveCreate!: (value: unknown) => void
    mocks.addApp.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveCreate = resolve
      }),
    )
    const wrapper = shallowMount(CodeGenerateEntry)
    const vm = wrapper.vm as unknown as {
      prompt: string
      handleCreate: () => Promise<void>
    }
    vm.prompt = 'build an app'

    const first = vm.handleCreate()
    const second = vm.handleCreate()
    expect(mocks.addApp).toHaveBeenCalledOnce()

    resolveCreate({ data: { code: 20000, data: '9001' } })
    await Promise.all([first, second])
    expect(mocks.push).toHaveBeenCalledOnce()
  })
})
