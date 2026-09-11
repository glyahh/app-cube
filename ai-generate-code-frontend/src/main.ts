import './assets/base.css'
import './assets/theme.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import './access/access'

// 全局引入 Ant Design Vue 组件
import 'ant-design-vue/dist/reset.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.mount('#app')
