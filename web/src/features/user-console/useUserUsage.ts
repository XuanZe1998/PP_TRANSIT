import { computed, ref, type Ref } from 'vue';

import http, { getHttpErrorNotice } from '@/utils/http';
import { DEFAULT_PAGE_SIZE } from '@/utils/listPage';

export function useUserUsage(current: Ref<{ key: string }>) {
  const billingRows = ref<any[]>([])
  const billingSummary = ref<any[]>([])
  const billingPage = ref(1)
  const billingPageSize = ref(DEFAULT_PAGE_SIZE)
  const billingTotal = ref(0)
  const usageAnalytics = ref<any>({ daily: [], totals: {}, tokenComposition: [] })
  const billingLoading = ref(false)
  const billingDataError = ref('')
  const usageAnalyticsLoading = ref(false)
  const usageAnalyticsError = ref('')
  const overviewUsageAnalytics = ref<any>({ daily: [] })
  const overviewUsageError = ref('')
  const today = new Date()
  const monthAgo = new Date(today); monthAgo.setDate(today.getDate() - 29)
  const ninetyDaysAgo = new Date(today); ninetyDaysAgo.setDate(today.getDate() - 89)
  const usageRange = ref<[string, string]>([monthAgo.toISOString().slice(0, 10), today.toISOString().slice(0, 10)])
  const usageModel = ref('')
  async function loadBilling() {
    if (current.value.key !== 'logs') return
    billingLoading.value = true
    billingDataError.value = ''
    usageAnalyticsError.value = ''
    const [summaryResult, logsResult, analyticsResult] = await Promise.allSettled([
      http.get('/api/user/billing/summary', { params: { model: usageModel.value || undefined, startDate: usageRange.value?.[0], endDate: usageRange.value?.[1] } }),
      http.get('/api/user/billing/logs/page', { params: { page: billingPage.value, size: billingPageSize.value, model: usageModel.value || undefined, startDate: usageRange.value?.[0], endDate: usageRange.value?.[1] } }),
      http.get('/api/user/usage/analytics', { params: { from: usageRange.value?.[0], to: usageRange.value?.[1], model: usageModel.value || undefined } })
    ])
    const dataErrors: string[] = []
    if (summaryResult.status === 'fulfilled') billingSummary.value = summaryResult.value.data || []
    else dataErrors.push('账单汇总：' + getHttpErrorNotice(summaryResult.reason, '加载失败'))
    if (logsResult.status === 'fulfilled') {
      billingRows.value = logsResult.value.data?.items || []
      billingTotal.value = Number(logsResult.value.data?.total || 0)
    }
    else dataErrors.push('用量明细：' + getHttpErrorNotice(logsResult.reason, '加载失败'))
    billingDataError.value = dataErrors.join('；')
    if (analyticsResult.status === 'fulfilled') {
      usageAnalytics.value = analyticsResult.value.data || { daily: [], totals: {}, tokenComposition: [] }
    } else {
      usageAnalyticsError.value = getHttpErrorNotice(analyticsResult.reason, '用量图表加载失败')
    }
    billingLoading.value = false
  }

  function applyBillingFilters() {
    billingPage.value = 1
    void loadBilling()
  }

  function handleBillingPageSizeChange() {
    billingPage.value = 1
    void loadBilling()
  }

  async function loadUsageAnalytics() {
    if (current.value.key !== 'logs') return
    usageAnalyticsLoading.value = true
    usageAnalyticsError.value = ''
    try {
      const response = await http.get('/api/user/usage/analytics', {
        params: { from: usageRange.value?.[0], to: usageRange.value?.[1], model: usageModel.value || undefined }
      })
      usageAnalytics.value = response.data || { daily: [], totals: {}, tokenComposition: [] }
    } catch (error: unknown) {
      usageAnalyticsError.value = getHttpErrorNotice(error, '用量图表加载失败')
    } finally {
      usageAnalyticsLoading.value = false
    }
  }

  async function loadOverviewUsage() {
    if (current.value.key !== 'overview') return
    overviewUsageError.value = ''
    try {
      const response = await http.get('/api/user/usage/analytics', {
        params: { from: ninetyDaysAgo.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10) }
      })
      overviewUsageAnalytics.value = response.data || { daily: [] }
    } catch (error: unknown) {
      overviewUsageError.value = getHttpErrorNotice(error, '近 90 天用量加载失败')
    }
  }

  function usageMetric(row: any, key: string) {
    if (!row) return 0
    return Number(row[key] ?? row[key.toUpperCase()] ?? 0)
  }

  const usageDays = computed(() => {
    const rows = Array.isArray(usageAnalytics.value.daily) ? usageAnalytics.value.daily : []
    const max = Math.max(1, ...rows.map((row: any) => usageMetric(row, 'total_tokens')))
    return rows.map((row: any) => {
      const total = usageMetric(row, 'total_tokens')
      return {
        date: String(row.usage_day ?? row.USAGE_DAY ?? ''), total,
        height: Math.max(total ? 8 : 0, total / max * 100),
        parts: [
          { name: '输入未命中', className: 'miss', value: usageMetric(row, 'cache_miss_tokens') },
          { name: '缓存命中', className: 'hit', value: usageMetric(row, 'cache_read_tokens') },
          { name: '缓存写入', className: 'write', value: usageMetric(row, 'cache_write_tokens') },
          { name: '输出', className: 'output', value: usageMetric(row, 'completion_tokens') }
        ]
      }
    })
  })
  const usageComposition = computed(() => {
    const colors = ['#78ad30', '#35a7a0', '#e6a23c', '#6b7fd7']
    return (usageAnalytics.value.tokenComposition || []).map((slice: any, index: number) => ({
      name: slice.name, value: Number(slice.value || 0), color: colors[index % colors.length]
    }))
  })
  const usageTotalTokens = computed(() => usageComposition.value.reduce((sum: number, slice: any) => sum + slice.value, 0))
  const usagePieStyle = computed(() => {
    const total = Math.max(1, usageTotalTokens.value)
    let cursor = 0
    const stops = usageComposition.value.map((slice: any) => {
      const start = cursor; cursor += slice.value / total * 100
      return `${slice.color} ${start}% ${cursor}%`
    })
    return { background: `conic-gradient(${stops.length ? stops.join(',') : '#e9eeeb 0 100%'})` }
  })

  return {
    billingRows,
    billingSummary,
    billingPage,
    billingPageSize,
    billingTotal,
    usageAnalytics,
    billingLoading,
    billingDataError,
    usageAnalyticsLoading,
    usageAnalyticsError,
    overviewUsageAnalytics,
    overviewUsageError,
    usageRange,
    usageModel,
    loadBilling,
    applyBillingFilters,
    handleBillingPageSizeChange,
    loadUsageAnalytics,
    loadOverviewUsage,
    usageMetric,
    usageDays,
    usageComposition,
    usageTotalTokens,
    usagePieStyle,
  }
}
