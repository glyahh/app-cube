import { shallowMount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import UserLogin from '../UserLogin.vue'

const mocks = vi.hoisted(() => ({
  replace: vi.fn(),
  route: { query: { redirect: '/user/settings?tab=profile' } },
  login: vi.fn(),
  register: vi.fn(),
  setLoginUser: vi.fn(),
  userLogin: { userName: '未登录' } as Record<string, unknown>,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace }),
  useRoute: () => mocks.route,
}))

vi.mock('@/api/userController', () => ({
  userLoginUsingPost: mocks.login,
  userRegisterUsingPost: mocks.register,
}))

vi.mock('@/stores/UserLogin', () => ({
  UserLoginStore: () => ({
    userLogin: mocks.userLogin,
    setLoginUser: mocks.setLoginUser,
  }),
}))

describe('UserLogin', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    delete mocks.userLogin.id
    mocks.route.query.redirect = '/user/settings?tab=profile'
  })

  it('returns to the validated redirect after login', async () => {
    vi.useFakeTimers()
    mocks.login.mockResolvedValueOnce({
      data: { code: 20000, data: { id: '1', userName: 'tester' } },
    })
    const wrapper = shallowMount(UserLogin)
    const vm = wrapper.vm as unknown as {
      loginForm: { account: string; password: string }
      handleLogin: () => Promise<void>
    }
    vm.loginForm.account = 'tester'
    vm.loginForm.password = '12345678'

    await vm.handleLogin()
    await vi.advanceTimersByTimeAsync(2000)

    expect(mocks.replace).toHaveBeenCalledWith('/user/settings?tab=profile')
  })

  it('falls back to the main page for an external redirect', async () => {
    vi.useFakeTimers()
    mocks.route.query.redirect = 'https://example.com/account'
    mocks.login.mockResolvedValueOnce({
      data: { code: 20000, data: { id: '1', userName: 'tester' } },
    })
    const wrapper = shallowMount(UserLogin)
    const vm = wrapper.vm as unknown as {
      loginForm: { account: string; password: string }
      handleLogin: () => Promise<void>
    }
    vm.loginForm.account = 'tester'
    vm.loginForm.password = '12345678'

    await vm.handleLogin()
    await vi.advanceTimersByTimeAsync(2000)

    expect(mocks.replace).toHaveBeenCalledWith('/main_index')
  })
})
