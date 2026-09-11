import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LoopPickerTrigger from '../LoopPickerTrigger.vue'

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}))

vi.mock('@/api/loopController', () => ({
  loopMyListPageVoUsingPost: mocks.list,
  loopGetVoUsingGet: mocks.get,
}))

describe('LoopPickerTrigger', () => {
  beforeEach(() => {
    mocks.list.mockReset()
    mocks.get.mockReset()
  })

  it('emits the selected Loop id before its detail request finishes', async () => {
    mocks.list.mockResolvedValueOnce({ data: { code: 20000, data: [] } })
    let resolveDetail!: (value: unknown) => void
    mocks.get.mockReturnValueOnce(new Promise((resolve) => { resolveDetail = resolve }))
    const wrapper = shallowMount(LoopPickerTrigger, { props: { singleSelect: true } })
    await flushPromises()

    const selection = (wrapper.vm as unknown as {
      onSingleSelect: (loop: { id: string; loopName: string }) => Promise<void>
    }).onSingleSelect({ id: '42', loopName: 'Fast Loop' })
    await nextTick()

    expect(wrapper.emitted('singleChange')?.at(-1)?.[0]).toMatchObject({
      id: '42',
      loopName: 'Fast Loop',
    })

    resolveDetail({ data: { code: 20000, data: { compiledPrompt: 'detail' } } })
    await selection
  })

  it('ignores a stale detail response after another Loop is selected', async () => {
    mocks.list.mockResolvedValueOnce({ data: { code: 20000, data: [] } })
    let resolveFirst!: (value: unknown) => void
    let resolveSecond!: (value: unknown) => void
    mocks.get
      .mockReturnValueOnce(new Promise((resolve) => { resolveFirst = resolve }))
      .mockReturnValueOnce(new Promise((resolve) => { resolveSecond = resolve }))
    const wrapper = shallowMount(LoopPickerTrigger, { props: { singleSelect: true } })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      onSingleSelect: (loop: { id: string; loopName: string }) => Promise<void>
    }

    const first = vm.onSingleSelect({ id: '1', loopName: 'First' })
    const second = vm.onSingleSelect({ id: '2', loopName: 'Second' })
    resolveSecond({ data: { code: 20000, data: { compiledPrompt: 'second detail' } } })
    await second
    resolveFirst({ data: { code: 20000, data: { compiledPrompt: 'stale detail' } } })
    await first
    await nextTick()

    expect(wrapper.emitted('singleChange')?.at(-1)?.[0]).toMatchObject({
      id: '2',
      compiledPrompt: 'second detail',
    })
  })

  it('cancels a numeric Loop selection before its detail response finishes', async () => {
    mocks.list.mockResolvedValueOnce({ data: { code: 20000, data: [] } })
    let resolveDetail!: (value: unknown) => void
    mocks.get.mockReturnValueOnce(new Promise((resolve) => { resolveDetail = resolve }))
    const wrapper = shallowMount(LoopPickerTrigger, { props: { singleSelect: true } })
    await flushPromises()
    const vm = wrapper.vm as unknown as {
      onSingleSelect: (loop: { id: string | number; loopName: string }) => Promise<void>
    }

    const selection = vm.onSingleSelect({ id: 42, loopName: 'Numeric Loop' })
    await vm.onSingleSelect({ id: 42, loopName: 'Numeric Loop' })
    expect(wrapper.emitted('singleChange')?.at(-1)?.[0]).toBeNull()

    resolveDetail({ data: { code: 20000, data: { compiledPrompt: 'stale detail' } } })
    await selection
    expect(wrapper.emitted('singleChange')?.at(-1)?.[0]).toBeNull()
  })
})
