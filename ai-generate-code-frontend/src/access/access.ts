import { UserLoginStore } from '@/stores/UserLogin'
import { message } from 'ant-design-vue'
import router from '@/router'

// 是否为首次获取登录用户
let firstFetchLoginUser = true

/**
 * 全局权限校验
 */
router.beforeEach(async (to, from, next) => {
  // 获取登录用户信息
  const loginUserStore = UserLoginStore()
  let loginUser = loginUserStore.userLogin

  // 确保页面刷新，首次加载时，能够等后端返回用户信息后再校验权限
  if (firstFetchLoginUser) {
    await loginUserStore.fetchLoginUser()
    loginUser = loginUserStore.userLogin
    firstFetchLoginUser = false
  }

  // 登录页只作为认证入口，已有会话或登录成功后统一进入主入口页。
  if (to.path === '/user/login' && loginUser?.id) {
    next('/main_index')
    return
  }

  // 获取目标url路径
  const toUrl = to.fullPath

  if (toUrl.startsWith('/admin')) {
    if (!loginUser || loginUser.userRole !== 'admin') {
      message.error('没有权限')
      next(`/user/login?redirect=${to.fullPath}`)
      return
    }
  }

  if (toUrl.startsWith('/user/settings')) {
    if (!loginUser || !loginUser.id) {
      message.warning('请先登录')
      next(`/user/login?redirect=${to.fullPath}`)
      return
    }
  }

  // 放行
  next()
})
