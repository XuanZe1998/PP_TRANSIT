import axios, { AxiosError } from 'axios'
import { getToken, clearAuth } from './auth'

const configuredApiBaseUrl = import.meta.env.VITE_API_BASE_URL || ''
const apiBaseUrl = configuredApiBaseUrl.replace(/\/$/, '')
const http = axios.create({
  baseURL: apiBaseUrl,
  timeout: 30_000
})

export function getHttpErrorMessage(error: unknown, fallback = '请求失败，请稍后重试') {
  if (axios.isAxiosError(error)) {
    const payload = error.response?.data as { message?: string; error?: string | { message?: string } } | undefined
    if (typeof payload?.message === 'string' && payload.message.trim()) return payload.message
    if (typeof payload?.error === 'string' && payload.error.trim()) return payload.error
    if (typeof payload?.error === 'object' && payload.error?.message) return payload.error.message
    if (error.code === AxiosError.ETIMEDOUT || error.code === 'ECONNABORTED') return '请求超时，请检查网络后重试'
    if (!error.response) return '无法连接到服务，请检查网络或服务状态'
    if (error.response.status === 403) return '当前账号没有执行此操作的权限'
    if (error.response.status === 429) return '请求过于频繁，请稍后重试'
    if (error.response.status >= 500) return '服务暂时不可用，请稍后重试'
  }
  if (error instanceof Error && error.message.trim()) return error.message
  return fallback
}

http.interceptors.request.use(config => {
  if (apiBaseUrl && config.url?.startsWith('/api/')) {
    config.url = config.url.replace(/^\/api/, '')
  }
  const token = getToken()
  if (token) {
    config.headers = config.headers || {}
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  res => res,
  err => {
    const status = err?.response?.status
    if (status === 401) {
      clearAuth()
      const isAdminPath = window.location.pathname.startsWith('/admin')
      const loginPath = isAdminPath ? '/admin/login' : '/login'
      const alreadyOnAuthPage = window.location.pathname === loginPath
        || window.location.pathname === '/register'
        || window.location.pathname.startsWith('/oauth/callback/')
      if (!alreadyOnAuthPage) {
        const currentPath = `${window.location.pathname}${window.location.search}${window.location.hash}`
        window.location.href = `${loginPath}?redirect=${encodeURIComponent(currentPath)}`
      }
    }
    return Promise.reject(err)
  }
)

export default http
