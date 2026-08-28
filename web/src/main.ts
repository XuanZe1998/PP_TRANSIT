import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import router from './router'
import { initInactivityGuard } from './utils/auth'

const app = createApp(App)

app.use(router)
app.mount('#app')

const configuredIdleTimeout = Number(import.meta.env.VITE_AUTH_IDLE_TIMEOUT_MS)
const idleTimeoutMs = Number.isFinite(configuredIdleTimeout) && configuredIdleTimeout >= 5 * 60 * 1000
  ? configuredIdleTimeout
  : 7 * 24 * 60 * 60 * 1000

initInactivityGuard(idleTimeoutMs, () => {
  const currentPath = router.currentRoute.value.fullPath
  const isAdmin = currentPath.startsWith('/admin')
  router.push({
    path: isAdmin ? '/admin/login' : '/',
    query: currentPath && !currentPath.startsWith('/login') && !currentPath.startsWith('/admin/login')
      ? (isAdmin ? { redirect: currentPath } : { auth: 'login', redirect: currentPath })
      : (isAdmin ? {} : { auth: 'login' })
  })
})

window.addEventListener('auth-timeout', async () => {
  const [{ ElMessage }] = await Promise.all([
    import('element-plus/es/components/message/index'),
    import('element-plus/theme-chalk/el-message.css'),
  ])
  ElMessage.warning('登录状态已失效，请重新登录')
})

window.addEventListener('user-auth-required', (event: Event) => {
  const redirect = (event as CustomEvent<{ redirect?: string }>).detail?.redirect
  router.push({ path: '/', query: { auth: 'login', ...(redirect ? { redirect } : {}) } })
})
