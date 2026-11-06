import { afterEach, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import http from '../src/utils/http'
import { usePlayground } from '../src/features/playground/usePlayground'
import { extractPlaygroundResult, extractPlaygroundUsage, formatPlaygroundRawResponse } from '../src/features/playground/response'
import { normalizeModelCatalog } from '../src/utils/modelCatalog'
const lifecycle = vi.hoisted(() => ({ cleanup: [] as (() => void)[] }))
vi.mock('vue', async original => ({ ...await original<typeof import('vue')>(), onBeforeUnmount: (fn: () => void) => lifecycle.cleanup.push(fn) }))
vi.mock('../src/utils/http', async original => ({ ...await original<typeof import('../src/utils/http')>(), default: { post: vi.fn() } }))
vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus', () => ({ ElMessage: { warning: vi.fn() } }))
afterEach(() => { lifecycle.cleanup.forEach(fn => fn()); lifecycle.cleanup = []; vi.clearAllMocks() })
const response = { choices: [{ message: { content: 'Answer' } }], usage: { prompt_tokens: 12 }, billing: { total_amount: '123', cache_read_tokens: 4 } }
function setup(refresh = vi.fn().mockResolvedValue(undefined)) {
  const dashboard = ref({ tokens: [{ id: 1, enabled: true, allowedModels: 'a' }, { id: 2, enabled: true, allowedModels: 'b' }], models: ['a', 'b'], modelCatalog: normalizeModelCatalog([], ['a', 'b']) })
  const current = ref({ key: 'playground' })
  const state = usePlayground(dashboard, current, refresh)
  state.syncSelection()
  state.playground.value.prompt = 'Hello'
  return { state, current, dashboard, refresh }
}
function deferred() {
  let resolve!: (value: any) => void
  const promise = new Promise<any>(done => { resolve = done })
  return { promise, resolve }
}
it('deduplicates submission and sends a stable request snapshot', async () => {
  const pending = deferred(); vi.mocked(http.post).mockReturnValue(pending.promise)
  const { state } = setup()
  const request = state.sendPlaygroundRequest()
  state.playground.value.prompt = 'edited'
  await state.sendPlaygroundRequest()
  expect(http.post).toHaveBeenCalledTimes(1)
  expect(vi.mocked(http.post).mock.calls[0][1]).toEqual({ tokenId: 1, model: 'a', prompt: 'Hello' })
  pending.resolve({ data: response }); await request
  expect(state.playgroundResponse.value).toBe('Answer')
})
it('aborts on navigation and ignores the old response after returning and sending another request', async () => {
  const old = deferred(); vi.mocked(http.post).mockReturnValueOnce(old.promise).mockResolvedValueOnce({ data: response })
  const { state, current, refresh } = setup()
  const request = state.sendPlaygroundRequest()
  const signal = vi.mocked(http.post).mock.calls[0][2]?.signal
  current.value.key = 'wallet'
  expect(signal?.aborted).toBe(true)
  current.value.key = 'playground'
  await state.sendPlaygroundRequest()
  old.resolve({ data: { choices: [{ message: { content: 'stale' } }] } }); await request
  expect(state.playgroundResponse.value).toBe('Answer')
  expect(refresh).toHaveBeenCalledTimes(1)
})
it('does not refresh or update after unmount', async () => {
  const pending = deferred(); vi.mocked(http.post).mockReturnValue(pending.promise)
  const { state, refresh } = setup()
  const request = state.sendPlaygroundRequest()
  lifecycle.cleanup.forEach(fn => fn())
  pending.resolve({ data: response }); await request
  await state.sendPlaygroundRequest()
  expect(state.playgroundResponse.value).toBe('')
  expect(refresh).not.toHaveBeenCalled()
  expect(http.post).toHaveBeenCalledTimes(1)
})
it('retains settled answer and usage when dashboard refresh fails', async () => {
  vi.mocked(http.post).mockResolvedValue({ data: response })
  const { state } = setup(vi.fn().mockRejectedValue(new Error('offline')))
  await state.sendPlaygroundRequest()
  expect(state.playgroundResponse.value).toBe('Answer')
  expect(state.playgroundUsage.value?.totalAmount).toBe('123')
  expect(state.playgroundError.value).toBe('')
  expect(state.playgroundLoading.value).toBe(false)
})
it('chooses an authorized key for a linked model and rejects disabled keys or out-of-scope models', async () => {
  const { state, dashboard } = setup()
  state.syncSelection('b')
  expect(state.playground.value.tokenId).toBe(2)
  expect(state.playground.value.model).toBe('b')
  state.playground.value.model = 'a'
  await state.sendPlaygroundRequest()
  state.playground.value.model = 'b'; dashboard.value.tokens[1].enabled = false
  await state.sendPlaygroundRequest()
  expect(http.post).not.toHaveBeenCalled()
})
it('preserves text fallbacks and displays only server-provided billing', () => {
  expect(extractPlaygroundResult({ choices: [{ message: { content: [], reasoning_content: 'reasoning' } }] })).toBe('reasoning')
  expect(extractPlaygroundResult({ output_text: [{ text: 'a' }, 'b'] })).toBe('a\nb')
  expect(extractPlaygroundUsage({ usage: {} })).toBeNull()
  expect(extractPlaygroundUsage(response)).toMatchObject({ cacheReadTokens: 4, totalAmount: '123' })
  expect(JSON.parse(formatPlaygroundRawResponse(response))).not.toHaveProperty('billing')
  expect(response).toHaveProperty('billing')
})
