import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import http from '../src/utils/http'
import { ElMessage } from 'element-plus'
import { useGatewayData } from '../src/features/model-gateway/useGatewayData'
import { useGatewayJobs } from '../src/features/model-gateway/useGatewayJobs'
import { useGatewaySettings } from '../src/features/model-gateway/useGatewaySettings'

const lifecycle = vi.hoisted(() => ({ cleanup: [] as (() => void)[] }))
vi.mock('vue', async original => ({ ...await original<typeof import('vue')>(),
  onBeforeUnmount: (fn: () => void) => lifecycle.cleanup.push(fn) }))
vi.mock('../src/utils/http', async original => ({ ...await original<typeof import('../src/utils/http')>(),
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() } }))
vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn() } }))
const page = (id: number) => ({ data: { items: [{ id }], total: 30, page: 2, size: 10 } })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
beforeEach(() => { vi.resetAllMocks(); lifecycle.cleanup = [] })
afterEach(() => { lifecycle.cleanup.forEach(fn => fn()); vi.useRealTimers() })

it('keeps the latest site and server page when an older list request finishes last', async () => {
  const stale = deferred<ReturnType<typeof page>>()
  vi.mocked(http.get).mockImplementation(async (url, config) => {
    if (url.endsWith('/summary')) return { data: { models: 20 } }
    return config?.params.siteId === 1 ? stale.promise : page(2)
  })
  const data = useGatewayData(ref('models'))
  data.siteId.value = 1
  const old = data.load()
  data.siteId.value = 2
  data.paging.page = 2
  data.query.value = 'model-b'
  await data.load()
  stale.resolve(page(1))
  await old
  expect(data.rows.value).toEqual([{ id: 2 }])
  expect(data.summary.value).toEqual({ models: 20 })
  expect(data.loading.value).toBe(false)
  expect(http.get).toHaveBeenCalledWith('/api/admin/api/gateway/models', {
    params: { siteId: 2, groupId: undefined, query: 'model-b', state: '', page: 2, size: 10, all: false }
  })
})

it('does not let a stale summary change the current site totals', async () => {
  const stale = deferred<{ data: { models: number } }>()
  vi.mocked(http.get).mockImplementation(async (url, config) => {
    if (!url.endsWith('/summary')) return page(2)
    return config?.params.siteId === 1 ? stale.promise : { data: { models: 20 } }
  })
  const data = useGatewayData(ref('models'))
  data.siteId.value = 1
  const old = data.load()
  await vi.waitFor(() => expect(http.get).toHaveBeenCalledTimes(2))
  data.siteId.value = 2
  await data.load()
  stale.resolve({ data: { models: 999 } })
  await old
  expect(data.summary.value.models).toBe(20)
})

it('keeps valid list totals when summary fails and falls back from all to a bounded server page', async () => {
  vi.mocked(http.get).mockImplementation(async (url, config) => {
    if (url.endsWith('/summary')) throw new Error('summary unavailable')
    if (config?.params.all) throw new Error('all exceeds 200')
    return page(3)
  })
  const data = useGatewayData(ref('models'))
  data.paging.all = true
  await data.load()
  expect(data.paging.all).toBe(false)
  expect(data.paging.total).toBe(30)
  expect(data.rows.value).toEqual([{ id: 3 }])
  expect(data.error.value).toContain('summary unavailable')
})

it('retries the same selector page after failure instead of skipping results', async () => {
  vi.mocked(http.get).mockResolvedValueOnce(page(1)).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(page(2))
  const data = useGatewayData(ref('channels'))
  await data.searchGroups('g')
  await data.searchGroups('g', true)
  await data.searchGroups('g', true)
  expect(vi.mocked(http.get).mock.calls.map(call => call[1]?.params.page)).toEqual([1, 2, 2])
  expect(data.groupOptions.value).toEqual([{ id: 1 }, { id: 2 }])
})

it('discards in-flight lists and never restarts loading after unmount', async () => {
  const stale = deferred<ReturnType<typeof page>>()
  vi.mocked(http.get).mockReturnValue(stale.promise)
  const data = useGatewayData(ref('models'))
  const request = data.load()
  lifecycle.cleanup.forEach(fn => fn())
  stale.resolve(page(1))
  await request
  await data.reloadAll()
  expect(data.rows.value).toEqual([])
  expect(http.get).toHaveBeenCalledTimes(1)
})

it('deduplicates job IDs, serializes slow polling, retries transient errors and refreshes once', async () => {
  vi.useFakeTimers()
  const slow = deferred<{ data: { status: string } }>()
  vi.mocked(http.get).mockReturnValueOnce(slow.promise).mockRejectedValueOnce(new Error('offline'))
    .mockResolvedValueOnce({ data: { status: 'SUCCESS', message: 'done' } })
  const reload = vi.fn().mockResolvedValue(undefined)
  const jobs = useGatewayJobs(ref([]), ref(false), vi.fn(), reload)
  jobs.trackJob('job-1'); jobs.trackJob('job-1'); jobs.startPolling()
  await vi.advanceTimersByTimeAsync(12000)
  expect(http.get).toHaveBeenCalledTimes(1)
  slow.resolve({ data: { status: 'RUNNING' } })
  await vi.advanceTimersByTimeAsync(0)
  await vi.advanceTimersByTimeAsync(6000)
  expect(http.get).toHaveBeenCalledTimes(3)
  expect(reload).toHaveBeenCalledTimes(1)
  expect(ElMessage.success).toHaveBeenCalledWith('done')
  await vi.advanceTimersByTimeAsync(3000)
  expect(http.get).toHaveBeenCalledTimes(3)
})

it('ignores job completion after unmount and cannot restart the timer', async () => {
  vi.useFakeTimers()
  const slow = deferred<{ data: { status: string } }>()
  vi.mocked(http.get).mockReturnValue(slow.promise)
  const reload = vi.fn()
  const jobs = useGatewayJobs(ref([]), ref(false), vi.fn(), reload)
  jobs.trackJob('job-1'); jobs.startPolling()
  await vi.advanceTimersByTimeAsync(3000)
  lifecycle.cleanup.forEach(fn => fn())
  slow.resolve({ data: { status: 'SUCCESS' } })
  jobs.startPolling()
  await vi.advanceTimersByTimeAsync(6000)
  expect(reload).not.toHaveBeenCalled()
  expect(ElMessage.success).not.toHaveBeenCalled()
  expect(http.get).toHaveBeenCalledTimes(1)
})

it('keeps site identity distinct from group ID and clears credentials on teardown', async () => {
  const settings = useGatewaySettings(ref(1), ref([{ id: 1, name: 'site-a' }]), ref(false), vi.fn(), vi.fn())
  settings.editSite({ id: 99, site_id: 1, site_name: 'site-a', site_public_name: 'public-a' })
  expect(settings.settingsId.value).toBe(1)
  expect(settings.settings.publicName).toBe('public-a')
  settings.settings.apiKey = 'test-key'
  settings.accountAuth.password = 'test-password'
  settings.accountAuth.totpCode = '123456'
  lifecycle.cleanup.forEach(fn => fn())
  expect(settings.settings.apiKey).toBe('')
  expect(settings.accountAuth.password).toBe('')
  expect(settings.accountAuth.totpCode).toBe('')
})

it('guards duplicate authorization submissions and clears secrets even on failure', async () => {
  const request = deferred<any>()
  vi.mocked(http.post).mockReturnValue(request.promise)
  const working = ref(false)
  const settings = useGatewaySettings(ref(7), ref([{ id: 7, name: 'site-b' }]), working, vi.fn(), vi.fn())
  settings.editSite({ type: 'click' })
  expect(settings.settingsId.value).toBe(7)
  settings.accountAuth.email = 'test@example.com'
  settings.accountAuth.password = 'test-password'
  settings.accountAuth.totpCode = '123456'
  const authorization = settings.authorizeAiApiBank()
  await settings.authorizeAiApiBank()
  expect(http.post).toHaveBeenCalledTimes(1)
  request.resolve(Promise.reject(new Error('authorization unavailable')))
  await authorization
  expect(settings.settingsError.value).toContain('authorization unavailable')
  expect(working.value).toBe(false)
  expect(settings.accountAuth.password).toBe('')
  expect(settings.accountAuth.totpCode).toBe('')
})
