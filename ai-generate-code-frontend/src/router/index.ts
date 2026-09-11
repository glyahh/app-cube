import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  // 去掉地址栏默认的 # 哈希符号，实现真实的 URL
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // 首页
    {
      path: '/',
      name: 'home',
      component: () => import('../page/HomeView.vue'),
    },
    {
      path: '/main_index',
      name: 'main-index',
      component: () => import('@/page/MainIndex/MainIndexView.vue'),
      meta: { noLayout: true },
    },
    // 用户登录界面
    {
      path: '/user/login',
      name: 'user-login',
      component: () => import('@/page/User/UserLogin.vue'),
    },
    // 用户设置界面
    {
      path: '/user/settings',
      name: 'user-settings',
      component: () => import('@/page/User/UserSettings.vue'),
    },
    // 用户外观设置界面
    {
      path: '/user/appearance',
      name: 'user-appearance',
      component: () => import('@/page/User/UserAppearanceSettings.vue'),
    },
    // 用户查看历史对话
    {
      path: '/user/chats',
      name: 'user-chats',
      component: () => import('@/page/User/UserChatHistory.vue'),
    },
    // 管理员管理用户界面
    {
      path: '/admin/users',
      name: 'admin-users',
      component: () => import('@/page/Admin/AdminHome.vue'),
    },
    // 管理员处理申请
    {
      path: '/admin/apply',
      name: 'admin-apply',
      component: () => import('@/page/Admin/AdminApplyManage.vue'),
    },
    // 管理员管理应用界面
    {
      path: '/admin/apps',
      name: 'admin-apps',
      component: () => import('@/page/Admin/AdminAppManage.vue'),
    },
    // 管理员对话管理界面
    {
      path: '/admin/chats',
      name: 'admin-chats',
      component: () => import('@/page/Admin/AdminChatManage.vue'),
    },
    // 管理员 Loop 管理界面
    {
      path: '/admin/loops',
      name: 'admin-loops',
      component: () => import('@/page/Admin/AdminLoopManage.vue'),
    },
    // 应用ai交互界面 (用户查看创建的应用)
    {
      path: '/code/generate',
      name: 'code-generate',
      component: () => import('@/page/App/CodeGenerateEntry.vue'),
    },
    // 应用ai交互界面 (用户首次通过导航创建应用)
    {
      path: '/app/:id/chat',
      name: 'app-chat',
      component: () => import('@/page/App/AppChatView.vue'),
      props: true,
    },
    // 用户编辑应用界面
    {
      path: '/app/:id/edit',
      name: 'app-edit',
      component: () => import('@/page/App/AppEditView.vue'),
      props: true,
    },
    // Loop 技能市场
    {
      path: '/loop',
      name: 'loop-market',
      component: () => import('@/page/Loop/LoopMarketView.vue'),
    },
    // 我的Loop
    {
      path: '/user/loops',
      name: 'my-loop',
      component: () => import('@/page/Loop/MyLoopView.vue'),
    },
    // 创建Loop
    {
      path: '/loop/create',
      name: 'loop-create',
      component: () => import('@/page/Loop/LoopCreateView.vue'),
    },
    // 编辑loop
    {
      // 动态路由id到不同的loop上
      path: '/loop/:id/edit',
      name: 'loop-edit',
      component: () => import('@/page/Loop/LoopEditView.vue'),
      // 将路由 params 参数自动转为组件 props,可以直接通过 props.id 拿到id
      props: true,
    },
  ],
})

export default router
