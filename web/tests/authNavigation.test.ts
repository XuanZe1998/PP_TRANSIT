import { describe, expect, it } from 'vitest'
import { shouldPromptUserLogin } from '../src/utils/authNavigation'

describe('user login navigation', () => {
  it('does not replace administrator routes or their login page', () => {
    expect(shouldPromptUserLogin('/admin/other-services', undefined)).toBe(false)
    expect(shouldPromptUserLogin('/admin/login', undefined)).toBe(false)
  })

  it('does not wrap an existing login redirect inside another redirect', () => {
    expect(shouldPromptUserLogin('/', 'login')).toBe(false)
    expect(shouldPromptUserLogin('/', 'register')).toBe(false)
    expect(shouldPromptUserLogin('/login', undefined)).toBe(false)
  })

  it('still prompts once for an expired user session on a normal route', () => {
    expect(shouldPromptUserLogin('/console/wallet', undefined)).toBe(true)
  })
})
