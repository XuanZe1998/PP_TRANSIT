import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { initInactivityGuard, getToken, clearAuth } from './utils/auth'
import { ElMessage } from 'element-plus'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(router)
app.use(ElementPlus)
app.mount('#app')

initInactivityGuard(3 * 60 * 1000, () => {
  ElMessage.warning('长时间未操作，登录状态已自动退出')
  router.push('/login')
})

window.addEventListener('auth-timeout', () => {
  ElMessage.warning('登录状态已失效，请重新登录')
})

window.addEventListener('beforeunload', () => {
  const token = getToken()
  if (token) {
    clearAuth()
    fetch('http://localhost:8080/auth/logout', {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/json'
      }
    }).catch(() => {})
  }
})
