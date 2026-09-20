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
// Chinese locale: the built-in pagination, date picker and empty-state strings
// would otherwise render in English next to Chinese content.
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
