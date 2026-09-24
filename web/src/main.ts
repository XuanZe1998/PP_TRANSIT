import { createApp } from 'vue'
import './style.css'
import './styles/tokens.css'
import './styles/foundation.css'
import './styles/components.css'
import App from './App.vue'
import router from './router'
import { initInactivityGuard } from './utils/auth'
import { shouldPromptUserLogin } from './utils/authNavigation'
import { loadSiteConfig } from './config/site'
import { initLocale, installDomLocalization } from './i18n/locale'

const app = createApp(App)

initLocale()
app.use(router)
app.mount('#app')
installDomLocalization()
void loadSiteConfig()

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
  if (!shouldPromptUserLogin(router.currentRoute.value.path, router.currentRoute.value.query.auth)) return
  const redirect = (event as CustomEvent<{ redirect?: string }>).detail?.redirect
  router.push({ path: '/', query: { auth: 'login', ...(redirect ? { redirect } : {}) } })
})
