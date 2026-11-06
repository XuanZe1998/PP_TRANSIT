import { reactive, ref, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http, { getHttpErrorMessage, getHttpErrorNotice } from '@/utils/http';
import { DEFAULT_PAGE_SIZE } from '@/utils/listPage';

export function useAdminFinance(query: Ref<string>, load: () => Promise<void>) {
  const rows = ref<any[]>([])
  const summary = ref<Record<string, any>>({})
  const secondaryRows = ref<any[]>([])
  const rechargePlans = ref<any[]>([])
  type FinanceSection = 'summary' | 'transactions' | 'codes' | 'plans'
  const financeErrors = reactive<Record<FinanceSection, string>>({ summary: '', transactions: '', codes: '', plans: '' })
  const financePage = ref(1)
  const financePageSize = ref(DEFAULT_PAGE_SIZE)
  const financeTotal = ref(0)
  const rechargePlanVisible = ref(false)
  const rechargePlanSaving = ref(false)
  const rechargePlanForm = reactive({ id: null as number | null, name: '', amount: 500000, bonusPercent: 0, sortOrder: 100, enabled: true })
  async function loadFinanceSection(section: FinanceSection) {
    financeErrors[section] = ''
    const endpoints: Record<FinanceSection, string> = {
      summary: '/api/admin/api/finance/summary',
      transactions: '/api/admin/api/finance/transactions/page',
      codes: '/api/admin/api/finance/redeem-codes',
      plans: '/api/admin/api/finance/recharge-plans'
    }
    const labels: Record<FinanceSection, string> = {
      summary: '财务汇总', transactions: '钱包流水', codes: '兑换码', plans: '充值套餐'
    }
    try {
      const response = await http.get(endpoints[section], section === 'transactions' ? {
        params: { page: financePage.value, size: financePageSize.value, query: query.value.trim() || undefined }
      } : undefined)
      if (section === 'summary') summary.value = response.data || {}
      else if (section === 'transactions') {
        rows.value = response.data?.items || []
        financeTotal.value = Number(response.data?.total || 0)
      }
      else if (section === 'codes') secondaryRows.value = response.data || []
      else rechargePlans.value = response.data || []
    } catch (error: unknown) {
      financeErrors[section] = getHttpErrorNotice(error, `${labels[section]}加载失败`)
    }
  }

  function loadFinanceTransactions() {
    return loadFinanceSection('transactions')
  }

  function handleFinancePageSizeChange() {
    financePage.value = 1
    void loadFinanceTransactions()
  }

  async function loadFinance() {
    await Promise.all((['summary', 'transactions', 'codes', 'plans'] as FinanceSection[])
      .map(section => loadFinanceSection(section)))
  }

  function openRechargePlan(row?: any) {
    Object.assign(rechargePlanForm, row ? {
      id: row.id, name: row.name, amount: Number(row.amount), bonusPercent: Number(row.bonus_percent),
      sortOrder: Number(row.sort_order), enabled: Boolean(row.enabled)
    } : { id: null, name: '', amount: 500000, bonusPercent: 0, sortOrder: 100, enabled: true })
    rechargePlanVisible.value = true
  }

  async function saveRechargePlan() {
    if (!rechargePlanForm.name.trim()) return ElMessage.warning('请输入套餐名称')
    rechargePlanSaving.value = true
    try {
      const payload = { ...rechargePlanForm, id: undefined }
      if (rechargePlanForm.id) await http.put(`/api/admin/api/finance/recharge-plans/${rechargePlanForm.id}`, payload)
      else await http.post('/api/admin/api/finance/recharge-plans', payload)
      rechargePlanVisible.value = false
      ElMessage.success('充值套餐已保存')
      await load()
    } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '充值套餐保存失败')) }
    finally { rechargePlanSaving.value = false }
  }

  async function removeRechargePlan(row: any) {
    try {
      await ElMessageBox.confirm(`确认删除充值套餐“${row.name}”？历史订单不会受影响。`, '删除套餐', { type: 'warning' })
      await http.delete(`/api/admin/api/finance/recharge-plans/${row.id}`)
      ElMessage.success('充值套餐已删除')
      await load()
    } catch (error: any) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(getHttpErrorMessage(error, '充值套餐删除失败'))
    }
  }

  return {
    rows,
    summary,
    secondaryRows,
    rechargePlans,
    financeErrors,
    financePage,
    financePageSize,
    financeTotal,
    rechargePlanVisible,
    rechargePlanSaving,
    rechargePlanForm,
    loadFinanceSection,
    loadFinanceTransactions,
    handleFinancePageSizeChange,
    loadFinance,
    openRechargePlan,
    saveRechargePlan,
    removeRechargePlan,
  }
}
