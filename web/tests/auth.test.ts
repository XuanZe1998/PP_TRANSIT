import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { clearAuth, getToken, getUser, initInactivityGuard, setAuth } from '../src/utils/auth'

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()

  get length() { return this.values.size }
  clear() { this.values.clear() }
  getItem(key: string) { return this.values.get(key) ?? null }
  key(index: number) { return [...this.values.keys()][index] ?? null }
  removeItem(key: string) { this.values.delete(key) }
  setItem(key: string, value: string) { this.values.set(key, value) }
}

function browserWindow() {
  const target = new EventTarget() as EventTarget & {
    setTimeout: typeof setTimeout
    clearTimeout: typeof clearTimeout
  }
  target.setTimeout = setTimeout
  target.clearTimeout = clearTimeout
  return target
}

describe('inactivity guard', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-12T00:00:00.000Z'))
    vi.stubGlobal('localStorage', new MemoryStorage())
    vi.stubGlobal('sessionStorage', new MemoryStorage())
    vi.stubGlobal('window', browserWindow())
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('keeps admin credentials only for the browser tab and clears persisted old credentials', () => {
    localStorage.setItem('admin_access_token', 'old-persistent-secret')
    localStorage.setItem('admin_info', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
    expect(getToken('admin')).toBeNull()
    expect(localStorage.getItem('admin_access_token')).toBeNull()

    setAuth('tab-only-token', { username: 'admin', role: 'ADMIN' })
    expect(getToken('admin')).toBe('tab-only-token')
    expect(getUser('admin')?.role).toBe('ADMIN')
    expect(localStorage.getItem('admin_access_token')).toBeNull()
    clearAuth('admin')
    expect(sessionStorage.getItem('admin_access_token')).toBeNull()
  })

  it('purges persisted admin tokens on public pages too', () => {
    localStorage.setItem('admin_access_token', 'old-persistent-secret')
    expect(getToken('user')).toBeNull()
    expect(localStorage.getItem('admin_access_token')).toBeNull()
  })

  it('does not revive an old unscoped administrator token', () => {
    localStorage.setItem('token', 'legacy-admin-token')
    localStorage.setItem('user', JSON.stringify({ username: 'admin', role: 'ADMIN' }))
    expect(getToken('admin')).toBeNull()
    expect(localStorage.getItem('token')).toBeNull()
  })

  it('never redirects a public visitor who has no authenticated session', () => {
    const onTimeout = vi.fn()
    const dispose = initInactivityGuard(30 * 60 * 1000, onTimeout)

    vi.advanceTimersByTime(31 * 60 * 1000)

    expect(onTimeout).not.toHaveBeenCalled()
    expect(getToken()).toBeNull()
    dispose()
  })

  it('clears an authenticated session after the configured idle period', () => {
    setAuth('session-token', { username: 'tester', role: 'USER' })
    const onTimeout = vi.fn()
    const dispose = initInactivityGuard(30 * 60 * 1000, onTimeout)

    vi.advanceTimersByTime(30 * 60 * 1000)

    expect(onTimeout).toHaveBeenCalledTimes(1)
    expect(getToken()).toBeNull()
    dispose()
  })
})
