import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import http from '../src/utils/http'
import { captureInvite, savedInvite } from '../src/utils/referralInvite'

vi.mock('../src/utils/http', () => ({ default: { get: vi.fn() } }))
beforeEach(() => {
  const values = new Map<string, string>()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value),
    removeItem: (key: string) => values.delete(key)
  })
  vi.mocked(http.get).mockReset().mockResolvedValue({ data: { valid: true } })
})
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

test('first valid invitation survives later links and expires after 30 days', async () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-01T00:00:00Z'))
  expect(await captureInvite('FIRST')).toBe('FIRST')
  vi.setSystemTime(new Date('2026-11-30T00:00:00Z'))
  expect(await captureInvite('SECOND')).toBe('FIRST')
  vi.setSystemTime(new Date('2026-12-02T00:00:00Z'))
  expect(savedInvite()).toBe('')
  expect(await captureInvite('SECOND')).toBe('SECOND')
})

test('a suspended invitation is cleared without blocking registration', async () => {
  await captureInvite('FIRST')
  vi.mocked(http.get).mockResolvedValue({ data: { valid: false } })
  expect(await captureInvite(undefined)).toBe('')
  expect(savedInvite()).toBe('')
})

test('concurrent captures preserve arrival order instead of network response order', async () => {
  let resolveFirst!: (value: any) => void
  vi.mocked(http.get).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve }))
  const first = captureInvite('FIRST'), second = captureInvite('SECOND')
  await vi.waitFor(() => expect(resolveFirst).toBeTypeOf('function'))
  resolveFirst({ data: { valid: true } })
  expect(await Promise.all([first, second])).toEqual(['FIRST', 'FIRST'])
})
