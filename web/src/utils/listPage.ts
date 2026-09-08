import { reactive, watch } from 'vue'
export function useListPage(key: string) {
  let saved: { size?: number; all?: boolean } = {}
  try { saved = JSON.parse(localStorage.getItem(`list-page:${key}`) || '{}') } catch { /* defaults */ }
  const paging = reactive({ page: 1, size: [10,20,50,100].includes(saved.size || 0) ? saved.size! : 20, all: !!saved.all, total: 0 })
  watch(() => [paging.size, paging.all], () => { try { localStorage.setItem(`list-page:${key}`, JSON.stringify({size:paging.size,all:paging.all})) } catch { /* unavailable */ } })
  return paging
}
export function pageSlice<T>(rows: T[], page: number, size: number, all = false): T[] { return all && rows.length <= 200 ? rows : rows.slice((page - 1) * size, page * size) }
export function boundedPage(total: number, page: number, size: number) { return Math.max(1, Math.min(page, Math.ceil(total / size) || 1)) }
