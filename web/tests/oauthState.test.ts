import { beforeEach, describe, expect, it, vi } from 'vitest'

import {
  OAUTH_STATE_TTL_MS,
  OAuthStateError,
  clearOAuthState,
  consumeOAuthState,
  peekOAuthState,
  saveOAuthState,
} from '../src/utils/oauthState'

class MemoryStorage implements Storage {
  private readonly values = new Map<string, string>()

  get length(): number {
    return this.values.size
  }

  clear(): void {
    this.values.clear()
  }

  getItem(key: string): string | null {
    return this.values.get(key) ?? null
  }

  key(index: number): string | null {
    return [...this.values.keys()][index] ?? null
  }

  removeItem(key: string): void {
    this.values.delete(key)
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value)
  }
}

function expectOAuthError(action: () => unknown, code: OAuthStateError['code']): void {
  try {
    action()
    throw new Error('Expected OAuthStateError')
  } catch (error) {
    expect(error).toBeInstanceOf(OAuthStateError)
    expect((error as OAuthStateError).code).toBe(code)
  }
}

describe('OAuth state session', () => {
  beforeEach(() => {
    vi.useRealTimers()
    vi.stubGlobal('sessionStorage', new MemoryStorage())
  })

  it('saves state with its provider and optional local redirect', () => {
    vi.setSystemTime(new Date('2026-07-11T00:00:00.000Z'))

    saveOAuthState('github', 'unpredictable-state', '/console?tab=keys')

    expect(peekOAuthState()).toEqual({
      provider: 'github',
      state: 'unpredictable-state',
      redirect: '/console?tab=keys',
      createdAt: Date.now(),
    })
  })

  it('consumes matching state exactly once', () => {
    saveOAuthState('google', 'single-use-state', '/console')

    expect(consumeOAuthState('google', 'single-use-state')).toEqual({
      provider: 'google',
      redirect: '/console',
    })
    expect(peekOAuthState()).toBeNull()
    expectOAuthError(
      () => consumeOAuthState('google', 'single-use-state'),
      'MISSING_STATE',
    )
  })

  it('binds state to the initiating provider and deletes it on provider mismatch', () => {
    saveOAuthState('github', 'provider-bound-state')

    expectOAuthError(
      () => consumeOAuthState('google', 'provider-bound-state'),
      'PROVIDER_MISMATCH',
    )
    expect(peekOAuthState()).toBeNull()
  })

  it('deletes state when the callback value does not match', () => {
    saveOAuthState('github', 'expected-state')

    expectOAuthError(
      () => consumeOAuthState('github', 'attacker-state'),
      'STATE_MISMATCH',
    )
    expect(peekOAuthState()).toBeNull()
  })

  it('rejects expired state and deletes it', () => {
    vi.setSystemTime(new Date('2026-07-11T00:00:00.000Z'))
    saveOAuthState('google', 'expired-state')
    vi.setSystemTime(Date.now() + OAUTH_STATE_TTL_MS + 1)

    expectOAuthError(
      () => consumeOAuthState('google', 'expired-state'),
      'EXPIRED_STATE',
    )
    expect(peekOAuthState()).toBeNull()
  })

  it('rejects external redirects before writing state', () => {
    expectOAuthError(
      () => saveOAuthState('github', 'state', 'https://attacker.example'),
      'INVALID_REDIRECT',
    )
    expect(peekOAuthState()).toBeNull()
  })

  it('can explicitly clear a pending state', () => {
    saveOAuthState('github', 'pending-state')
    clearOAuthState()
    expect(peekOAuthState()).toBeNull()
  })
})
