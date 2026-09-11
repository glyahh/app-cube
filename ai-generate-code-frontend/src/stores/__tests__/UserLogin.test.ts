import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

const getLogin = vi.hoisted(() => vi.fn())

vi.mock('@/api', () => ({
  userGetLoginUsingGet: getLogin,
}))

import { UserLoginStore } from '../UserLogin'

const loginResponse = (id: string) => ({
  data: {
    code: 20000,
    data: { id, userName: `user-${id}` },
  },
})

describe('UserLoginStore', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getLogin.mockReset()
  })

  it('reuses one promise for concurrent initialization and skips later probes after success', async () => {
    let resolveRequest!: (value: ReturnType<typeof loginResponse>) => void
    getLogin.mockReturnValueOnce(
      new Promise<ReturnType<typeof loginResponse>>((resolve) => {
        resolveRequest = resolve
      }),
    )
    const store = UserLoginStore()

    const first = store.ensureLoginUser()
    const second = store.ensureLoginUser()

    expect(getLogin).toHaveBeenCalledOnce()

    resolveRequest(loginResponse('1'))
    await Promise.all([first, second])
    await store.ensureLoginUser()

    expect(getLogin).toHaveBeenCalledOnce()
    expect(store.userLogin.id).toBe('1')
  })

  it('clears a failed initialization so the next call can retry', async () => {
    const failure = new Error('offline')
    getLogin.mockRejectedValueOnce(failure).mockResolvedValueOnce(loginResponse('2'))
    const store = UserLoginStore()

    await expect(store.ensureLoginUser()).resolves.toBeUndefined()
    await expect(store.ensureLoginUser()).resolves.toBeUndefined()

    expect(getLogin).toHaveBeenCalledTimes(2)
    expect(store.userLogin.id).toBe('2')
  })

  it('retries after a resolved response contains a business failure code', async () => {
    getLogin
      .mockResolvedValueOnce({ data: { code: 50000, message: 'system error' } })
      .mockResolvedValueOnce(loginResponse('5'))
    const store = UserLoginStore()

    await store.ensureLoginUser()
    await store.ensureLoginUser()

    expect(getLogin).toHaveBeenCalledTimes(2)
    expect(store.userLogin.id).toBe('5')
  })

  it('retries after a success code omits the login user', async () => {
    getLogin
      .mockResolvedValueOnce({ data: { code: 20000, data: null } })
      .mockResolvedValueOnce(loginResponse('6'))
    const store = UserLoginStore()

    await store.ensureLoginUser()
    await store.ensureLoginUser()

    expect(getLogin).toHaveBeenCalledTimes(2)
    expect(store.userLogin.id).toBe('6')
  })

  it('treats a confirmed logged-out response as initialized', async () => {
    getLogin.mockResolvedValueOnce({ data: { code: 40100, message: 'not logged in' } })
    const store = UserLoginStore()

    await store.ensureLoginUser()
    await store.ensureLoginUser()

    expect(getLogin).toHaveBeenCalledOnce()
  })

  it('clears the previous identity when a refresh confirms the session expired', async () => {
    const store = UserLoginStore()
    store.setLoginUser({ id: 'admin', userRole: 'admin', userName: 'administrator' })

    getLogin.mockResolvedValueOnce({ data: { code: 40100, message: 'not logged in' } })
    await store.refreshLoginUser()

    expect(store.userLogin).toEqual({ userName: '未登录' })
  })

  it('does not let an in-flight probe restore identity after it is cleared', async () => {
    let resolveRequest!: (value: ReturnType<typeof loginResponse>) => void
    getLogin.mockReturnValueOnce(
      new Promise<ReturnType<typeof loginResponse>>((resolve) => {
        resolveRequest = resolve
      }),
    )
    const store = UserLoginStore()
    store.setLoginUser({ id: 'admin', userRole: 'admin' })
    const refresh = store.refreshLoginUser()

    store.resetLoginUser()
    resolveRequest(loginResponse('stale'))
    await refresh

    expect(store.userLogin).toEqual({ userName: '未登录' })
  })

  it('refreshes after initialization and reuses the current refresh promise', async () => {
    getLogin.mockResolvedValueOnce(loginResponse('3'))
    const store = UserLoginStore()
    await store.ensureLoginUser()

    let resolveRefresh!: (value: ReturnType<typeof loginResponse>) => void
    getLogin.mockReturnValueOnce(
      new Promise<ReturnType<typeof loginResponse>>((resolve) => {
        resolveRefresh = resolve
      }),
    )

    const first = store.refreshLoginUser()
    const second = store.refreshLoginUser()

    expect(getLogin).toHaveBeenCalledTimes(2)

    resolveRefresh(loginResponse('4'))
    await Promise.all([first, second])

    expect(store.userLogin.id).toBe('4')
  })
})
