import axios, { AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { clearAuth, getRefreshToken, getToken, getUser, setAuth, type AuthScope } from './auth'

const productionApiBaseUrl = typeof window !== 'undefined' && window.location.hostname === 'linknux.com'
  ? 'https://api.linknux.com' : ''
const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL || productionApiBaseUrl
const apiBaseUrl = configuredApiBaseUrl.replace(/\/$/, '')
const http = axios.create({ baseURL: apiBaseUrl, timeout: 30_000 })

type ScopedConfig = InternalAxiosRequestConfig & { _authScope?: AuthScope | 'public'; _retriedAfterRefresh?: boolean }

export function createIdempotencyKey(scope = 'web') {
  const normalizedScope = scope.replace(/[^A-Za-z0-9._:-]/g, '-').slice(0, 48) || 'web'
  return `${normalizedScope}-${crypto.randomUUID()}`
}

export function getHttpErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as { message?: string; error?: string | { message?: string } } | undefined
    const serverMessage = typeof payload?.message === 'string'
      ? payload.message.trim()
      : typeof payload?.error === 'string'
        ? payload.error.trim()
        : typeof payload?.error === 'object'
          ? String(payload.error?.message || '').trim()
          : ''
    if (serverMessage && !/^resource not found$/i.test(serverMessage)) return serverMessage
    if (error.code === AxiosError.ETIMEDOUT || error.code === 'ECONNABORTED') return '请求超时，请检查网络后重试'
    if (!error.response) return '无法连接到服务，请检查网络或服务状态'
    if (error.response.status === 404) return '当前功能接口暂不可用，请确认后端已更新并重启后再试'
    if (error.response.status === 403) return '当前账号没有执行此操作的权限'
    if (error.response.status === 429) return '请求过于频繁，请稍后重试'
    if (error.response.status >= 500) return '服务暂时不可用，请稍后重试'
  }
  if (error instanceof Error && error.message.trim()) return error.message
  return fallback
}

export function getHttpRequestId(error: unknown) {
  if (!axios.isAxiosError(error)) return ''
  const value = (error.response?.data as { requestId?: unknown } | undefined)?.requestId
  return typeof value === 'string' ? value.trim() : ''
}

export function getHttpErrorNotice(error: unknown, fallback = '请求失败，请稍后重试') {
  const message = getHttpErrorMessage(error, fallback)
  const requestId = getHttpRequestId(error)
  return requestId ? `${message}（请求 ID：${requestId}）` : message
}

function authScope(url = ''): AuthScope | 'public' {
  if (/^\/(public\/|ops\/catalog|auth\/(login|register|refresh|validate)|oauth\/|admin\/auth\/login)/.test(url)) return 'public'
  if (/^\/(admin\/|platform\/admin\/|service-orders\/admin\/|channels\/|tokens\/|mappings\/)/.test(url)) return 'admin'
  return 'user'
}

function normalizeUrl(config: ScopedConfig) {
  if (apiBaseUrl && config.url?.startsWith('/api/')) config.url = config.url.replace(/^\/api/, '')
}

http.interceptors.request.use(config => {
  const scoped = config as ScopedConfig
  normalizeUrl(scoped)
  scoped._authScope = authScope(scoped.url || '')
  if (scoped._authScope !== 'public') {
    const token = getToken(scoped._authScope)
    if (token) {
      scoped.headers = scoped.headers || {}
      scoped.headers.Authorization = `Bearer ${token}`
    }
  }
  return scoped
})

let refreshPromise: Promise<string> | null = null
async function refreshUserAccessToken() {
  if (!refreshPromise) {
    const refreshToken = getRefreshToken()
    if (!refreshToken) throw new Error('No refresh token')
    const refreshUrl = `${apiBaseUrl}${apiBaseUrl ? '/auth/refresh' : '/api/auth/refresh'}`
    refreshPromise = axios.post(refreshUrl, { refreshToken }, { timeout: 30_000 }).then(response => {
      const payload = response.data || {}
      if (!payload.access_token) throw new Error('Refresh response has no access token')
      const user = getUser('user') || { username: payload.username || '', role: 'USER' }
      setAuth(payload.access_token, user, payload.refresh_token)
      return payload.access_token as string
    }).finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

http.interceptors.response.use(response => response, async error => {
  const config = error?.config as ScopedConfig | undefined
  const scope = config?._authScope || authScope(config?.url || '')
  if (error?.response?.status !== 401 || scope === 'public') return Promise.reject(error)
  if (scope === 'user' && config && !config._retriedAfterRefresh && getRefreshToken()) {
    try {
      const token = await refreshUserAccessToken()
      config._retriedAfterRefresh = true
      config.headers.Authorization = `Bearer ${token}`
      return http.request(config)
    } catch { /* clear only the expired user session below */ }
  }
  clearAuth(scope)
  const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
  if (scope === 'user') {
    window.dispatchEvent(new CustomEvent('user-auth-required', { detail: { redirect: currentPath } }))
    return Promise.reject(error)
  }
  const loginPath = scope === 'admin' ? '/admin/login' : '/login'
  const alreadyOnAuthPage = window.location.pathname === loginPath || window.location.pathname === '/register'
    || window.location.pathname.startsWith('/oauth/callback/')
  if (!alreadyOnAuthPage) {
    window.location.assign(`${loginPath}?redirect=${encodeURIComponent(currentPath)}`)
  }
  return Promise.reject(error)
})

export default http
