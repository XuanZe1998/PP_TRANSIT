import { beforeEach, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import http from '../src/utils/http'
import { ElMessageBox } from 'element-plus'
import { useAdminFinance } from '../src/features/admin-console/useAdminFinance'
import { useAdminSettings } from '../src/features/admin-console/useAdminSettings'
import { useAdminUsers } from '../src/features/admin-console/useAdminUsers'
import { createAdminConfigs } from '../src/features/admin-console/modules'
import { useUserUsage } from '../src/features/user-console/useUserUsage'

vi.mock('element-plus/es/components/message/style/css', () => ({}))
vi.mock('element-plus/es/components/message-box/style/css', () => ({}))
vi.mock('../src/utils/http', async importOriginal => ({
  ...await importOriginal<typeof import('../src/utils/http')>(),
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))
vi.mock('element-plus', () => ({
  ElMessage: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() }
}))
beforeEach(() => vi.resetAllMocks())

it('keeps finance sections independent and sends the selected server page and search', async () => {
  const finance = useAdminFinance(ref(' alice '), vi.fn())
  finance.financePage.value = 2
  finance.financePageSize.value = 20
  vi.mocked(http.get).mockImplementation(async url => {
    if (url.endsWith('/summary')) throw new Error('summary unavailable')
    if (url.endsWith('/transactions/page')) return { data: { items: [{ id: 21 }], total: 31 } }
    return { data: [] }
  })
  await finance.loadFinance()
  expect(finance.rows.value).toEqual([{ id: 21 }])
  expect(finance.financeTotal.value).toBe(31)
  expect(finance.financeErrors.summary).toContain('summary unavailable')
  expect(finance.financeErrors.transactions).toBe('')
  expect(http.get).toHaveBeenCalledWith('/api/admin/api/finance/transactions/page', {
    params: { page: 2, size: 20, query: 'alice' }
  })
  const other = useAdminFinance(ref(''), vi.fn())
  expect(other.rows.value).toEqual([])
  expect(other.financeErrors.summary).toBe('')
})

it('keeps user billing rows available when only analytics fails and does not fetch on other pages', async () => {
  const current = ref({ key: 'profile' })
  const usage = useUserUsage(current)
  await usage.loadBilling()
  expect(http.get).not.toHaveBeenCalled()
  current.value.key = 'logs'
  vi.mocked(http.get).mockImplementation(async url => {
    if (url.endsWith('/analytics')) throw new Error('analytics unavailable')
    return { data: url.endsWith('/summary') ? [{ model: 'test' }] : { items: [{ id: 1 }], total: 15 } }
  })
  await usage.loadBilling()
  expect(usage.billingRows.value).toEqual([{ id: 1 }])
  expect(usage.billingSummary.value).toEqual([{ model: 'test' }])
  expect(usage.billingTotal.value).toBe(15)
  expect(usage.billingDataError.value).toBe('')
  expect(usage.usageAnalyticsError.value).toContain('analytics unavailable')
  expect(usage.billingLoading.value).toBe(false)
})

it('keeps a saved setting when the subsequent public-status refresh fails', async () => {
  const settings = useAdminSettings(ref('test.setting'))
  vi.mocked(http.get).mockRejectedValue(new Error('offline'))
  await settings.handleLegalFieldSaved('test.setting', 'saved', 'description')
  expect(settings.filteredRows.value).toEqual([
    { setting_key: 'test.setting', setting_value: 'saved', description: 'description' }
  ])
  expect(settings.legalApprovalStatusLoaded.value).toBe(false)
})

it('requires confirmation before a balance adjustment and preserves the dedicated endpoint', async () => {
  const reload = vi.fn()
  const users = useAdminUsers(reload)
  users.openAdjust({ id: 42, username: 'test-user', balance: 100 })
  users.adjustForm.amount = 10000
  users.adjustForm.reason = ' manual correction '
  vi.mocked(ElMessageBox.confirm).mockRejectedValueOnce('cancel')
  await users.saveAdjust()
  expect(http.post).not.toHaveBeenCalled()
  expect(users.adjustVisible.value).toBe(true)
  vi.mocked(ElMessageBox.confirm).mockResolvedValueOnce('confirm')
  vi.mocked(http.post).mockResolvedValueOnce({ data: {} })
  await users.saveAdjust()
  expect(http.post).toHaveBeenCalledWith('/api/admin/api/users/42/adjust-balance', {
    amount: 10000, reason: 'manual correction'
  })
  expect(users.adjustVisible.value).toBe(false)
  expect(reload).toHaveBeenCalledOnce()
})

it('does not share mutable editor options across administrator console instances', () => {
  const first = createAdminConfigs()
  const second = createAdminConfigs()
  first.channels.fields.find(field => field.prop === 'type')!.options = [{ label: 'private', value: 'private' }]
  expect(second.channels.fields.find(field => field.prop === 'type')!.options).not.toEqual([
    { label: 'private', value: 'private' }
  ])
  expect(first.users.fields.map(field => field.prop)).not.toContain('balance')
})
