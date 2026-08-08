import './assets/base.css'
import './assets/theme.css'
import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from './App.vue'
import router from './router'
import './access/access'

// 全局引入 Ant Design Vue 组件
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import { NButton, NConfigProvider, NInput, NSpace, NTag, NTooltip } from 'naive-ui'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(Antd)
app.component('NConfigProvider', NConfigProvider)
app.component('NButton', NButton)
app.component('NInput', NInput)
app.component('NSpace', NSpace)
app.component('NTag', NTag)
app.component('NTooltip', NTooltip)
app.mount('#app')
