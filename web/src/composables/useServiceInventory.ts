import { computed, onScopeDispose, ref, watch, type Ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

export type InventoryService = { id: number; name: string; supplierType?: string; fulfillmentMode?: string }
export type InventoryRow = {
  id: number; secretPreview: string; status: string; reservedOrderId?: number
  orderNo?: string; buyerUserId?: number; createdAt?: string; reservedUntil?: string; deliveredAt?: string
}
export const inventoryStatuses = [
  { value: 'AVAILABLE', label: '未售' },
  { value: 'RESERVED', label: '预留中' },
  { value: 'DELIVERED', label: '已售／已发货' }
]

export function useServiceInventory(service: Ref<InventoryService | null>) {
  const rows = ref<InventoryRow[]>([]), stats = ref<Record<string, number>>({})
  const page = ref(1), size = ref(10), total = ref(0), status = ref(''), searchText = ref(''), query = ref('')
  const loading = ref(false), busy = ref(false), error = ref(''), importText = ref('')
  const selectedIds = ref<number[]>([]), selectionKey = ref(0)
  const revealedId = ref<number>(), revealedContent = ref('')
  const isLocal = computed(() => !!service.value && (!service.value.supplierType || service.value.supplierType === 'LOCAL_INVENTORY'))
  const tokens = computed(() => importText.value.split(/[\s,，、]+/u).map(s => s.trim()).filter(Boolean))
  const recognized = computed(() => new Set(tokens.value).size)
  let context = 0, request = 0, disposed = false, clearTimer: ReturnType<typeof setTimeout> | undefined
  const sameContext = (version: number) => !disposed && version === context
  function clearSecret() {
    if (clearTimer) clearTimeout(clearTimer)
    clearTimer = undefined; revealedId.value = undefined; revealedContent.value = ''
  }
  function clearSelection() { selectedIds.value = []; selectionKey.value++ }
  function selectRows(items: InventoryRow[]) { selectedIds.value = items.filter(item => item.status === 'AVAILABLE').map(item => item.id) }
  const base = (id: number) => `/api/admin/api/other-services/${id}/inventory`

  async function load() {
    if (!service.value || !isLocal.value || disposed) return
    const id = service.value.id, version = context, sequence = ++request
    loading.value = true; error.value = ''; clearSelection(); clearSecret()
    try {
      const [list, counts] = await Promise.all([
        http.get(base(id), { params: { listPage: true, page: page.value, size: size.value, status: status.value || undefined, q: query.value || undefined } }),
        http.get(`${base(id)}/stats`)
      ])
      if (!sameContext(version) || sequence !== request) return
      total.value = Number(list.data.total || 0); stats.value = counts.data || {}
      const lastPage = Math.max(1, Math.ceil(total.value / size.value))
      if (page.value > lastPage) { page.value = lastPage; await load(); return }
      rows.value = list.data.items || []
    } catch (failure) {
      if (sameContext(version) && sequence === request) { rows.value = []; total.value = 0; stats.value = {}; error.value = getHttpErrorMessage(failure, '卡密库存加载失败') }
    } finally { if (sameContext(version) && sequence === request) loading.value = false }
  }
  function setStatus(value: string) { status.value = value; page.value = 1; return load() }
  function search() { query.value = searchText.value.trim(); page.value = 1; return load() }
  function changePage() { return load() }
  const cancelled = (failure: unknown) => failure === 'cancel' || failure === 'close'

  async function importCards() {
    if (!service.value || !isLocal.value || busy.value || !recognized.value) return
    if (tokens.value.length > 10000 || tokens.value.some(item => item.length > 10000)) { ElMessage.warning('每批最多 10000 条，每条最多 10000 个字符'); return }
    const version = context, id = service.value.id, content = importText.value
    busy.value = true
    try {
      const response = await http.post(`${base(id)}/import`, { content })
      if (!sameContext(version)) return
      const report = response.data
      ElMessage.success(`识别 ${report.received} 条，成功导入 ${report.imported} 条，输入内重复 ${report.duplicateInInput} 条，库存重复 ${report.duplicateInStock} 条`)
      importText.value = ''; await load()
    } catch (failure) { if (sameContext(version)) ElMessage.error(getHttpErrorMessage(failure, '卡密导入失败，输入已保留')) }
    finally { if (sameContext(version)) busy.value = false }
  }

  async function deleteCards(ids: number[]) {
    if (!service.value || !isLocal.value || busy.value || !ids.length) return
    const version = context, id = service.value.id, submitted = [...ids]
    busy.value = true
    try {
      await ElMessageBox.confirm(`确认永久删除 ${submitted.length} 条未售卡密？删除后无法恢复。预留中或已售卡密不能删除。`, '删除卡密确认', { type: 'warning' })
      if (!sameContext(version)) return
      if (submitted.length === 1) await http.delete(`${base(id)}/${submitted[0]}`)
      else await http.post(`${base(id)}/batch-delete`, { ids: submitted })
      if (!sameContext(version)) return
      ElMessage.success(`已删除 ${submitted.length} 条未售卡密`); await load()
    } catch (failure) {
      if (sameContext(version) && !cancelled(failure)) { ElMessage.error(getHttpErrorMessage(failure, '删除失败，卡密可能已被订单占用')); await load() }
    } finally { if (sameContext(version)) busy.value = false }
  }

  async function replaceCard(row: InventoryRow) {
    if (!service.value || !isLocal.value || busy.value || row.status !== 'AVAILABLE') return
    const version = context, id = service.value.id
    busy.value = true
    try {
      const result = await ElMessageBox.prompt('仅能替换未售卡密；请输入一条完整卡密，保存后列表仍显示掩码。', '替换未售卡密', {
        inputType: 'password', inputValidator: (value: string) => !!value?.trim() && value.trim().length <= 10000 || '请输入 1–10000 个字符的卡密'
      })
      if (!sameContext(version)) return
      await http.put(`${base(id)}/${row.id}`, { content: result.value })
      if (!sameContext(version)) return
      ElMessage.success('卡密已安全替换'); await load()
    } catch (failure) { if (sameContext(version) && !cancelled(failure)) { ElMessage.error(getHttpErrorMessage(failure, '替换失败')); await load() } }
    finally { if (sameContext(version)) busy.value = false }
  }

  async function revealCard(row: InventoryRow, copy = false) {
    if (!service.value || !isLocal.value || busy.value) return
    const version = context, id = service.value.id
    busy.value = true; clearSecret()
    try {
      const response = await http.post(`${base(id)}/${row.id}/reveal`, null, { params: { purpose: copy ? 'COPY' : 'VIEW' } })
      if (!sameContext(version)) return
      if (copy) {
        await navigator.clipboard.writeText(response.data.content)
        if (sameContext(version)) ElMessage.success('卡密已复制，请注意剪贴板安全')
      } else {
        revealedId.value = row.id; revealedContent.value = response.data.content
        clearTimer = setTimeout(clearSecret, 30000)
      }
    } catch { if (sameContext(version)) ElMessage.error(copy ? '复制失败，请检查权限、加密配置及浏览器剪贴板权限' : '卡密读取失败，请检查权限或加密配置') }
    finally { if (sameContext(version)) busy.value = false }
  }

  watch(() => [service.value?.id, service.value?.supplierType], () => {
    context++; request++; clearSecret(); clearSelection()
    rows.value = []; stats.value = {}; total.value = 0; page.value = 1; size.value = 10
    status.value = ''; query.value = ''; searchText.value = ''; importText.value = ''; error.value = ''; busy.value = false; loading.value = false
    void load()
  }, { immediate: true })
  onScopeDispose(() => { disposed = true; context++; request++; clearSecret(); clearSelection(); importText.value = '' })
  return { rows, stats, page, size, total, status, searchText, loading, busy, error, importText, recognized, tokens,
    selectedIds, selectionKey, revealedId, revealedContent, isLocal, clearSecret, selectRows, load, setStatus, search, changePage, importCards, deleteCards, replaceCard, revealCard }
}
