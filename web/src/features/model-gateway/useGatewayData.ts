import { onBeforeUnmount, ref, type Ref } from 'vue'
import http, { getHttpErrorMessage } from '@/utils/http'
import { useListPage } from '@/utils/listPage'

/** Server filtering and pagination; only the latest request may publish its result. */
export function useGatewayData(tab: Ref<string>) {
  const rows = ref<any[]>([]), selected = ref<any[]>([])
  const siteId = ref<number>(), groupId = ref<number>()
  const query = ref(''), modelState = ref(''), recordKind = ref('jobs')
  const summary = ref<Record<string, number>>({})
  const loading = ref(false), error = ref('')
  const paging = useListPage('gateway-' + tab.value)
  let generation = 0, disposed = false

  function optionSearch(path: string, filters: () => Record<string, unknown>) {
    const items = ref<any[]>([]), total = ref(0), query = ref('')
    let page = 0, version = 0, pending = false
    async function search(text: string, more = false) {
      if (disposed || (more && pending)) return
      const current = ++version
      const nextPage = more ? page + 1 : 1
      query.value = text
      pending = true
      try {
        const { data } = await http.get('/api/admin/api/gateway/' + path, {
          params: { ...filters(), query: text, page: nextPage, size: 100 }
        })
        if (disposed || current !== version) return
        items.value = more ? [...items.value, ...data.items] : data.items
        total.value = data.total
        page = nextPage
      } catch (cause) {
        if (!disposed && current === version) error.value = getHttpErrorMessage(cause, '读取失败')
      } finally {
        if (current === version) pending = false
      }
    }
    return { items, total, query, search }
  }
  const sites = optionSearch('sites', () => ({}))
  const groups = optionSearch('groups', () => ({ siteId: siteId.value }))

  async function load() {
    if (disposed) return
    const current = ++generation
    const isCurrent = () => !disposed && current === generation
    const path = tab.value === 'channels' ? 'groups' : tab.value === 'records' ? recordKind.value : 'models'
    const params = { siteId: siteId.value, groupId: groupId.value, query: query.value,
      state: modelState.value, page: paging.page, size: paging.size, all: paging.all }
    loading.value = true
    error.value = ''
    selected.value = []
    try {
      let data
      try {
        data = (await http.get('/api/admin/api/gateway/' + path, { params })).data
      } catch (cause) {
        // Retain the existing bounded "all" fallback, using the same filter snapshot.
        if (!isCurrent()) return
        if (!params.all) throw cause
        paging.all = false
        data = (await http.get('/api/admin/api/gateway/' + path, {
          params: { ...params, all: false }
        })).data
      }
      if (!isCurrent()) return
      rows.value = data.items
      paging.total = data.total
      paging.page = data.page
      summary.value = {}
      const response = await http.get('/api/admin/api/gateway/summary', {
        params: { siteId: params.siteId, groupId: params.groupId }
      })
      if (isCurrent()) summary.value = response.data
    } catch (cause) {
      if (isCurrent()) error.value = getHttpErrorMessage(cause, '读取失败')
    } finally {
      if (isCurrent()) loading.value = false
    }
  }

  async function reloadAll() {
    await Promise.all([sites.search(''), groups.search('')])
    await load()
  }
  function filterChanged() { paging.page = 1; return load() }
  async function siteChanged() {
    groupId.value = undefined
    // Invalidate the old site's table immediately, before loading selector options.
    await Promise.all([groups.search(''), filterChanged()])
  }
  onBeforeUnmount(() => { disposed = true; generation++ })
  return { rows, selected, siteId, groupId, query, modelState, recordKind, summary, loading, error, paging,
    siteOptions: sites.items, siteTotal: sites.total, siteQuery: sites.query, searchSites: sites.search,
    groupOptions: groups.items, groupTotal: groups.total, groupQuery: groups.query, searchGroups: groups.search,
    load, reloadAll, filterChanged, siteChanged }
}
