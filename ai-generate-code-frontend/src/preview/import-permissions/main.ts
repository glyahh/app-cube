import 'ant-design-vue/dist/reset.css'
import { createApp } from 'vue'
import PermissionPreview from './PermissionPreview.vue'

// 审核入口不加载生产路由、登录态、授权存储或 Agent 传输。
const scenario = (
  window as Window & {
    __PERMISSION_PREVIEW_SCENARIO__?: {
      saveOutcome?: 'success' | 'failure' | 'conflict'
      decisionOutcome?: 'success' | 'expired' | 'failure'
      publicationOutcome?: 'success' | 'failure'
      highRisk?: boolean
      forbidden?: boolean
      diffText?: string
    }
  }
).__PERMISSION_PREVIEW_SCENARIO__

createApp(PermissionPreview, scenario ?? {}).mount('#app')
