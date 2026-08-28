export type AuthScope = 'user' | 'admin'
export type UserInfo = { username: string; role: string; displayName?: string; avatarPath?: string; accountComplete?: boolean; accountType?: 'PERSONAL'|'ENTERPRISE' }

const keys = {
  user: { token: 'user_access_token', refresh: 'user_refresh_token', user: 'user_info', active: 'user_last_active_at' },
  admin: { token: 'admin_access_token', refresh: 'admin_refresh_token', user: 'admin_info', active: 'admin_last_active_at' }
} as const

function inferredScope(): AuthScope {
  return typeof window !== 'undefined' && window.location?.pathname?.startsWith('/admin') ? 'admin' : 'user'
}

function migrateLegacy() {
  const token = localStorage.getItem('token')
  const rawUser = localStorage.getItem('user')
  if (!token || !rawUser) return
  try {
    const user = JSON.parse(rawUser) as UserInfo
    const scope: AuthScope = user.role === 'ADMIN' ? 'admin' : 'user'
    if (!localStorage.getItem(keys[scope].token)) {
      localStorage.setItem(keys[scope].token, token)
      localStorage.setItem(keys[scope].user, rawUser)
    }
  } finally {
    localStorage.removeItem('token')
    localStorage.removeItem('user')
    localStorage.removeItem('last_active_at')
  }
}

export function getToken(scope: AuthScope = inferredScope()): string | null {
  migrateLegacy()
  return localStorage.getItem(keys[scope].token)
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(keys.user.refresh)
}

export function getUser(scope: AuthScope = inferredScope()): UserInfo | null {
  migrateLegacy()
  const raw = localStorage.getItem(keys[scope].user)
  if (!raw) return null
  try { return JSON.parse(raw) } catch { return null }
}

export function setAuth(token: string, user: UserInfo, refreshToken?: string) {
  const scope: AuthScope = user.role === 'ADMIN' ? 'admin' : 'user'
  localStorage.setItem(keys[scope].token, token)
  localStorage.setItem(keys[scope].user, JSON.stringify(user))
  if (refreshToken) localStorage.setItem(keys[scope].refresh, refreshToken)
  localStorage.setItem(keys[scope].active, String(Date.now()))
  window.dispatchEvent(new CustomEvent('auth-changed', { detail: { scope } }))
}

export function clearAuth(scope: AuthScope = inferredScope()) {
  Object.values(keys[scope]).forEach(key => localStorage.removeItem(key))
  window.dispatchEvent(new CustomEvent('auth-changed', { detail: { scope } }))
}

export function touchActivity(scope: AuthScope = inferredScope()) {
  if (!getToken(scope)) return
  localStorage.setItem(keys[scope].active, String(Date.now()))
}

export function initInactivityGuard(timeoutMs: number, onTimeout: () => void) {
  let timer: number | null = null
  let lastActivityWrite = 0
  const clearTimer = () => { if (timer !== null) window.clearTimeout(timer); timer = null }
  const schedule = () => {
    clearTimer()
    const scope = inferredScope()
    if (!getToken(scope)) return
    const stored = Number(localStorage.getItem(keys[scope].active))
    const lastActiveAt = Number.isFinite(stored) && stored > 0 ? stored : Date.now()
    const remaining = timeoutMs - (Date.now() - lastActiveAt)
    if (remaining <= 0) {
      clearAuth(scope)
      window.dispatchEvent(new Event('auth-timeout'))
      onTimeout()
      return
    }
    timer = window.setTimeout(schedule, remaining)
  }
  const reset = () => {
    const scope = inferredScope()
    if (!getToken(scope)) return clearTimer()
    const now = Date.now()
    if (now - lastActivityWrite < 15_000) return
    lastActivityWrite = now
    touchActivity(scope)
    schedule()
  }
  const events = ['click', 'mousemove', 'keydown', 'scroll', 'touchstart', 'visibilitychange']
  events.forEach(event => window.addEventListener(event, reset, { passive: true }))
  window.addEventListener('auth-changed', schedule)
  window.addEventListener('storage', schedule)
  schedule()
  return () => {
    clearTimer()
    events.forEach(event => window.removeEventListener(event, reset))
    window.removeEventListener('auth-changed', schedule)
    window.removeEventListener('storage', schedule)
  }
}
