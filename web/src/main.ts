import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import { initInactivityGuard } from './utils/auth'
import { ElMessage } from 'element-plus'

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(router)
app.use(ElementPlus)
app.mount('#app')

const configuredIdleTimeout = Number(import.meta.env.VITE_AUTH_IDLE_TIMEOUT_MS)
const idleTimeoutMs = Number.isFinite(configuredIdleTimeout) && configuredIdleTimeout >= 5 * 60 * 1000
  ? configuredIdleTimeout
  : 30 * 60 * 1000

initInactivityGuard(idleTimeoutMs, () => {
  const currentPath = router.currentRoute.value.fullPath
  const isAdmin = currentPath.startsWith('/admin')
  router.push({
    path: isAdmin ? '/admin/login' : '/login',
    query: currentPath && !currentPath.startsWith('/login') && !currentPath.startsWith('/admin/login')
      ? { redirect: currentPath }
      : {}
  })
})

window.addEventListener('auth-timeout', () => {
  ElMessage.warning('登录状态已失效，请重新登录')
})
