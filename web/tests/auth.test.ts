import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { getToken, initInactivityGuard, setAuth } from '../src/utils/auth'

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
    vi.stubGlobal('window', browserWindow())
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
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
