import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const getLogin = vi.hoisted(() => vi.fn())

vi.mock('@/api', () => ({
  userGetLoginUsingGet: getLogin,
}))

describe('UserLogin initialization', () => {
  beforeEach(() => {
    vi.resetModules()
    getLogin.mockReset()
    setActivePinia(createPinia())
  })

  it('shares one in-flight request between concurrent callers', async () => {
    let resolveRequest!: (value: unknown) => void
    getLogin.mockReturnValueOnce(
      new Promise((resolve) => {
        resolveRequest = resolve
      }),
    )
    const { UserLoginStore } = await import('../UserLogin')
    const store = UserLoginStore()

    const first = store.ensureLoginUser()
    const second = store.ensureLoginUser()
    expect(getLogin).toHaveBeenCalledOnce()

    resolveRequest({ data: { code: 20000, data: { id: '1', userName: 'tester' } } })
    await Promise.all([first, second])
    expect(store.userLogin.id).toBe('1')
  })

  it('allows a failed initialization to be retried', async () => {
    getLogin.mockRejectedValueOnce(new Error('offline'))
    getLogin.mockResolvedValueOnce({ data: { code: 20000, data: { id: '2' } } })
    const { UserLoginStore } = await import('../UserLogin')
    const store = UserLoginStore()

    await store.ensureLoginUser()
    await store.ensureLoginUser()
    expect(getLogin).toHaveBeenCalledTimes(2)
    expect(store.userLogin.id).toBe('2')
  })

  it('refreshes only when explicitly requested', async () => {
    getLogin.mockResolvedValue({ data: { code: 20000, data: { id: '3' } } })
    const { UserLoginStore } = await import('../UserLogin')
    const store = UserLoginStore()

    await store.ensureLoginUser()
    await store.ensureLoginUser()
    await store.refreshLoginUser()
    expect(getLogin).toHaveBeenCalledTimes(2)
  })
})
