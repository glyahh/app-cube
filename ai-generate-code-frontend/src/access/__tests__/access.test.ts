import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  registerGuard: vi.fn(),
  ensureLoginUser: vi.fn(),
  userLogin: { userName: '未登录' } as Record<string, unknown>,
}))

vi.mock('@/router', () => ({
  default: { beforeEach: mocks.registerGuard },
}))

vi.mock('@/stores/UserLogin', () => ({
  UserLoginStore: () => ({
    userLogin: mocks.userLogin,
    ensureLoginUser: mocks.ensureLoginUser,
  }),
}))

vi.mock('ant-design-vue', () => ({
  message: { error: vi.fn(), warning: vi.fn() },
}))

describe('access guard', () => {
  beforeEach(() => {
    vi.resetModules()
    mocks.registerGuard.mockClear()
    mocks.ensureLoginUser.mockReset()
    delete mocks.userLogin.id
  })

  it('waits for login initialization before continuing as logged out', async () => {
    mocks.ensureLoginUser.mockResolvedValueOnce(undefined)
    await import('../access')

    const guard = mocks.registerGuard.mock.calls[0]?.[0]
    const next = vi.fn()

    await expect(guard({ path: '/', fullPath: '/' }, {}, next)).resolves.toBeUndefined()
    expect(mocks.ensureLoginUser).toHaveBeenCalledOnce()
    expect(next).toHaveBeenCalledOnce()
    expect(next).toHaveBeenCalledWith()
  })

  it('does not allow a stale admin identity after session invalidation', async () => {
    mocks.userLogin.id = 'admin'
    mocks.userLogin.userRole = 'admin'
    mocks.ensureLoginUser.mockImplementationOnce(async () => {
      delete mocks.userLogin.id
      delete mocks.userLogin.userRole
    })
    await import('../access')

    const guard = mocks.registerGuard.mock.calls[0]?.[0]
    const next = vi.fn()

    await guard({ path: '/admin/users', fullPath: '/admin/users' }, {}, next)

    expect(next).toHaveBeenCalledWith('/user/login?redirect=/admin/users')
  })
})
