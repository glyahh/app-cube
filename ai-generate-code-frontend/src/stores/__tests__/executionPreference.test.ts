import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { UserLoginStore } from '../UserLogin'
import { useExecutionPreference } from '../executionPreference'

const mocks = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn() }))
vi.mock('@/request', () => ({ default: mocks }))
vi.mock('@/api', () => ({ userGetLoginUsingGet: vi.fn() }))

describe('executionPreference', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })
  it('defaults collapsed and loads account preference', async () => {
    mocks.get.mockResolvedValue({ data: { code: 20000, data: { expandExecutionProcess: true } } })
    const pref = useExecutionPreference()
    expect(pref.expanded).toBe(false)
    UserLoginStore().setLoginUser({ id: '1' })
    await flushPromises()
    expect(pref.expanded).toBe(true)
    UserLoginStore().resetLoginUser()
    await nextTick()
    expect(pref.expanded).toBe(false)
  })
  it('failed save preserves old value', async () => {
    const pref = useExecutionPreference()
    mocks.put.mockRejectedValue(new Error('offline'))
    await expect(pref.save(true)).rejects.toThrow('offline')
    expect(pref.expanded).toBe(false)
    expect(pref.saving).toBe(false)
  })
  it('ignores old account response after logout', async () => {
    let complete!: (result: unknown) => void
    mocks.get.mockReturnValue(new Promise(resolve => { complete = resolve }))
    const pref = useExecutionPreference()
    UserLoginStore().setLoginUser({ id: '1' })
    await nextTick()
    UserLoginStore().resetLoginUser()
    await nextTick()
    complete({ data: { code: 0, data: { expandExecutionProcess: true } } })
    await flushPromises()
    expect(pref.expanded).toBe(false)
  })
})
