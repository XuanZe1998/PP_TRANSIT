const TOKEN_KEY = 'token'
const USER_KEY = 'user'
const LAST_ACTIVE_KEY = 'last_active_at'

type UserInfo = { username: string; role: string }

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function getUser(): UserInfo | null {
  const u = localStorage.getItem(USER_KEY)
  if (!u) return null
  try {
    return JSON.parse(u)
  } catch {
    return null
  }
}

export function setAuth(token: string, user: UserInfo) {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_KEY, JSON.stringify(user))
  localStorage.setItem(LAST_ACTIVE_KEY, String(Date.now()))
  window.dispatchEvent(new Event('auth-changed'))
}

export function clearAuth() {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_KEY)
  localStorage.removeItem(LAST_ACTIVE_KEY)
  window.dispatchEvent(new Event('auth-changed'))
}

export function touchActivity() {
  if (!getToken()) return
  localStorage.setItem(LAST_ACTIVE_KEY, String(Date.now()))
}

export function initInactivityGuard(timeoutMs: number, onTimeout: () => void) {
  let timer: number | null = null
  let lastActivityWrite = 0
  const clearTimer = () => {
    if (timer !== null) window.clearTimeout(timer)
    timer = null
  }
  const expire = () => {
    clearTimer()
    if (!getToken()) return
    clearAuth()
    window.dispatchEvent(new Event('auth-timeout'))
    onTimeout()
  }
  const schedule = () => {
    clearTimer()
    if (!getToken()) return

    const stored = Number(localStorage.getItem(LAST_ACTIVE_KEY))
    const hasStoredActivity = Number.isFinite(stored) && stored > 0 && stored <= Date.now()
    const lastActiveAt = hasStoredActivity ? stored : Date.now()
    if (!hasStoredActivity) {
      localStorage.setItem(LAST_ACTIVE_KEY, String(lastActiveAt))
    }
    const remaining = timeoutMs - (Date.now() - lastActiveAt)
    if (remaining <= 0) {
      expire()
      return
    }
    timer = window.setTimeout(expire, remaining)
  }
  const reset = () => {
    if (!getToken()) {
      clearTimer()
      return
    }
    const now = Date.now()
    if (now - lastActivityWrite < 15_000) return
    lastActivityWrite = now
    touchActivity()
    schedule()
  }
  const events = ['click', 'mousemove', 'keydown', 'scroll', 'touchstart', 'visibilitychange']
  events.forEach(e => window.addEventListener(e, reset, { passive: true }))
  window.addEventListener('auth-changed', schedule)
  window.addEventListener('storage', schedule)
  schedule()
  return () => {
    clearTimer()
    events.forEach(e => window.removeEventListener(e, reset))
    window.removeEventListener('auth-changed', schedule)
    window.removeEventListener('storage', schedule)
  }
}
