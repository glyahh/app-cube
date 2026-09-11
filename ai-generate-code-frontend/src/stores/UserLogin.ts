import { ref } from 'vue'
import { defineStore } from 'pinia'
import { type LoginUserVO, userGetLoginUsingGet } from '@/api'

//UserLoginStore 的本质：是 defineStore() 返回的一个普通函数（工厂函数）
/**
 * 用户登录信息存储
 */
export const UserLoginStore =
  defineStore('UserLogin', () => {

    const loggedOutUser: LoginUserVO = { userName: '未登录' }

    const userLogin = ref<LoginUserVO>({ ...loggedOutUser })

    let loginUserInitialized = false
    let loginUserRequest: Promise<void> | null = null
    let loginUserInitialization: Promise<void> | null = null
    let loginUserStateVersion = 0

    /**
     * 可以把它理解成一个 “承诺”：异步操作一开始，就创建一个 Promise 对
     * 它承诺会在未来某个时间告诉你操作是成功了还是失败了，并把结果交给你
     */
    //获取登录用户的信息
    function requestLoginUser(): Promise<void> {
      const requestVersion = loginUserStateVersion
      const request = userGetLoginUsingGet({})
        .then((res) => {
          const loginUser = res.data.data
          const loginSucceeded = (res.data.code === 0 || res.data.code === 20000) && loginUser
          if (requestVersion !== loginUserStateVersion) {
            return
          }
          if (loginSucceeded && loginUser) {
            userLogin.value = loginUser
          }
          if (res.data.code === 40100) {
            resetLoginUser()
          } else if (loginSucceeded) {
            loginUserInitialized = true
          }
        })
        .finally(() => {
          if (loginUserRequest === request) {
            loginUserRequest = null
          }
        })

      loginUserRequest = request
      return request
    }

    function ensureLoginUser(): Promise<void> {
      if (loginUserInitialized) {
        return Promise.resolve()
      }
      if (loginUserInitialization) {
        return loginUserInitialization
      }

      const initialization = (loginUserRequest ?? requestLoginUser())
        .catch(() => undefined)
        .finally(() => {
          if (loginUserInitialization === initialization) {
            loginUserInitialization = null
          }
        })
      loginUserInitialization = initialization
      return initialization
    }

    function refreshLoginUser(): Promise<void> {
      return loginUserRequest ?? requestLoginUser()
    }

    const fetchLoginUser = refreshLoginUser

    //直接设置登录用户的信息
    function setLoginUser(loginUser: any): void {
      userLogin.value = loginUser
      loginUserStateVersion += 1
      loginUserInitialized = true
    }

    function resetLoginUser(): void {
      loginUserStateVersion += 1
      userLogin.value = { ...loggedOutUser }
      loginUserInitialized = true
    }

    return {
      userLogin,
      ensureLoginUser,
      refreshLoginUser,
      fetchLoginUser,
      setLoginUser,
      resetLoginUser,
    }
  })
