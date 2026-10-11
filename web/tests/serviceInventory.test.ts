import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref, type EffectScope } from 'vue'
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), confirm: vi.fn(), prompt: vi.fn(), success: vi.fn(), error: vi.fn(), warning: vi.fn() }))
vi.mock('../src/utils/http', () => ({ default: mocks, getHttpErrorMessage: (_: unknown, fallback: string) => fallback }))
vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus/es/components/message-box/style/css', () => ({}))
vi.mock('element-plus', () => ({ ElMessage: { success: mocks.success, error: mocks.error, warning: mocks.warning }, ElMessageBox: { confirm: mocks.confirm, prompt: mocks.prompt } }))
import { useServiceInventory, type InventoryService } from '../src/composables/useServiceInventory'
const available = { id: 11, status: 'AVAILABLE', secretPreview: '****1234' }
const scopes: EffectScope[] = []
const flush = async () => { await nextTick(); for (let i = 0; i < 8; i++) await Promise.resolve() }
function setup(id = 21) {
  const service = ref<InventoryService | null>({ id, name: `service-${id}`, supplierType: 'LOCAL_INVENTORY' })
  const scope = effectScope(); scopes.push(scope)
  const state = scope.run(() => useServiceInventory(service))!
  return { state, service, scope }
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.get.mockImplementation(async (url: string) => ({ data: url.endsWith('/stats') ? { AVAILABLE: 1, RESERVED: 2, DELIVERED: 3 } : { items: [available], total: 1 } }))
  mocks.confirm.mockResolvedValue('confirm'); mocks.prompt.mockResolvedValue({ value: 'replacement-secret' })
  mocks.post.mockResolvedValue({ data: { content: 'private-card', received: 3, unique: 2, imported: 1, duplicateInInput: 1, duplicateInStock: 1 } })
  mocks.put.mockResolvedValue({}); mocks.delete.mockResolvedValue({})
})
afterEach(() => { scopes.splice(0).forEach(scope => scope.stop()); vi.useRealTimers(); vi.unstubAllGlobals() })
describe('service inventory manager', () => {
  it('binds the exact service independently of the catalog page and resets filters and selection when switching', async () => {
    const { state, service } = setup(101); await flush()
    expect(mocks.get).toHaveBeenCalledWith('/api/admin/api/other-services/101/inventory', expect.objectContaining({ params: expect.objectContaining({ listPage: true, page: 1, size: 10 }) }))
    state.selectRows([available, { ...available, id: 12, status: 'RESERVED' }]); expect(state.selectedIds.value).toEqual([11])
    state.page.value = 3; state.searchText.value = 'order'; await state.search()
    expect(state.page.value).toBe(1); expect(state.selectedIds.value).toEqual([])
    state.importText.value = 'private-input'; service.value = { id: 102, name: 'next' }; await flush()
    expect(state.importText.value).toBe(''); expect(state.searchText.value).toBe(''); expect(state.status.value).toBe('')
    expect(mocks.get).toHaveBeenLastCalledWith('/api/admin/api/other-services/102/inventory/stats')
  })
  it('sends status and search to the server, clearing selection on pagination', async () => {
    const { state } = setup(); await flush()
    await state.setStatus('DELIVERED'); state.searchText.value = '1234'; await state.search()
    expect(mocks.get).toHaveBeenCalledWith(expect.stringContaining('/inventory'), { params: { listPage: true, page: 1, size: 10, status: 'DELIVERED', q: '1234' } })
    state.selectRows([available]); state.page.value = 2; await state.changePage()
    expect(state.selectedIds.value).toEqual([]); expect(state.stats.value).toEqual({ AVAILABLE: 1, RESERVED: 2, DELIVERED: 3 })
  })
  it('keeps input on failed import and reports both types of duplicates on success', async () => {
    const { state } = setup(); await flush(); state.importText.value = 'one，one two'
    expect(state.tokens.value).toHaveLength(3); expect(state.recognized.value).toBe(2)
    mocks.post.mockRejectedValueOnce(new Error('database failure')); await state.importCards()
    expect(state.importText.value).toBe('one，one two'); expect(mocks.error).toHaveBeenCalled()
    await state.importCards(); expect(state.importText.value).toBe('')
    expect(mocks.success).toHaveBeenCalledWith('识别 3 条，成功导入 1 条，输入内重复 1 条，库存重复 1 条')
  })
  it('never deletes after cancellation and submits exact selected IDs after confirmation', async () => {
    const { state } = setup(); await flush(); mocks.confirm.mockRejectedValueOnce('cancel')
    await state.deleteCards([11]); expect(mocks.delete).not.toHaveBeenCalled(); expect(mocks.post).not.toHaveBeenCalled()
    await state.deleteCards([11, 12]); expect(mocks.post).toHaveBeenCalledWith('/api/admin/api/other-services/21/inventory/batch-delete', { ids: [11, 12] })
  })
  it('returns to the last valid page after deletion', async () => {
    const { state } = setup(); await flush(); state.page.value = 2
    mocks.get.mockImplementation(async (url: string) => ({ data: url.endsWith('/stats') ? {} : { items: [], total: 10 } }))
    await state.deleteCards([11]); expect(state.page.value).toBe(1)
    expect(mocks.get).toHaveBeenCalledWith(expect.stringContaining('/inventory'), expect.objectContaining({ params: expect.objectContaining({ page: 1 }) }))
  })
  it('clears revealed secrets after 30 seconds and on scope disposal', async () => {
    vi.useFakeTimers(); const { state, scope } = setup(); await flush()
    await state.revealCard(available); expect(state.revealedContent.value).toBe('private-card')
    await vi.advanceTimersByTimeAsync(30000); expect(state.revealedContent.value).toBe('')
    await state.revealCard(available); scope.stop(); expect(state.revealedContent.value).toBe('')
  })
  it('only writes the clipboard after an explicit copy and does not display the copied content', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined); vi.stubGlobal('navigator', { clipboard: { writeText } })
    const { state } = setup(); await flush(); await state.revealCard(available)
    expect(writeText).not.toHaveBeenCalled(); await state.revealCard(available, true)
    expect(writeText).toHaveBeenCalledWith('private-card'); expect(state.revealedContent.value).toBe('')
    expect(mocks.post).toHaveBeenLastCalledWith('/api/admin/api/other-services/21/inventory/11/reveal', null, { params: { purpose: 'COPY' } })
  })
  it('does not show a late secret or stale list after changing services', async () => {
    const { state, service } = setup(); await flush()
    let finish!: (value: any) => void
    mocks.post.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const pending = state.revealCard(available); service.value = { id: 22, name: 'next' }; await flush()
    finish({ data: { content: 'old-service-secret' } }); await pending
    expect(state.revealedContent.value).toBe('')
    let finishList!: (value: any) => void
    mocks.get.mockImplementationOnce(() => new Promise(resolve => { finishList = resolve }))
    const old = state.load(); service.value = { id: 23, name: 'third' }; await flush()
    finishList({ data: { items: [{ ...available, id: 999 }], total: 1 } }); await old
    expect(state.rows.value).toEqual([available])
  })
  it('does not submit a confirmation belonging to a service that has since changed', async () => {
    const { state, service } = setup(); await flush()
    let finish!: (value: any) => void; mocks.confirm.mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
    const pending = state.deleteCards([11]); service.value = { id: 22, name: 'next' }; await flush()
    finish('confirm'); await pending; expect(mocks.delete).not.toHaveBeenCalled()
  })
  it('does not call local inventory endpoints for upstream services', async () => {
    const { state, service } = setup(); await flush(); mocks.get.mockClear()
    service.value = { id: 30, name: 'upstream', supplierType: 'DUJIAO_NEXT' }; await flush()
    expect(state.isLocal.value).toBe(false); expect(mocks.get).not.toHaveBeenCalled()
    state.importText.value = 'one'; await state.importCards(); expect(mocks.post).not.toHaveBeenCalled()
  })
})
