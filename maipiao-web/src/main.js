import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/global.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
// 中文语言包：否则分页、日期选择器、空状态这些内置文案会以英文出现在
// 中文内容旁边。
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
