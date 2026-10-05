import '@/assets/base.css'
import '@/assets/theme.css'
import 'ant-design-vue/dist/reset.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import { UserLoginStore } from '@/stores/UserLogin'
import ProjectImportPreview from './ProjectImportPreview.vue'
import { installWorkbenchTransport } from './workbenchTransport'

// 独立路由与传输，只复用工作台 UI，不引入生产鉴权、真实会话或 Agent。
const scenario = (window as Window & {
  __IMPORT_PREVIEW_SCENARIO__?: {
    importOutcome?: 'success' | 'failure' | 'failure-once'
    installOutcome?: 'success' | 'failure' | 'failure-once'
    onTaskState?: (event: { taskId: string; name: string; status: string }) => void
  }
}).__IMPORT_PREVIEW_SCENARIO__

// 故障场景由浏览器测试在装配前注入；默认用户界面没有测试控件。
const app = createApp(ProjectImportPreview, {
  importOutcome: scenario?.importOutcome,
  installOutcome: scenario?.installOutcome,
  onTaskState: scenario?.onTaskState,
})
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'import', component: ProjectImportPreview },
    { path: '/app/:id/chat', name: 'app-chat', component: ProjectImportPreview },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})
installWorkbenchTransport()
app.use(createPinia()).use(router)
UserLoginStore().setLoginUser({ id: 'preview-user', userName: '开发者', userRole: 'user' })
router.isReady().then(() => app.mount('#app')).catch((error: unknown) => {
  document.getElementById('app')!.textContent = '页面加载失败，请刷新重试。'
  console.error(error)
})
