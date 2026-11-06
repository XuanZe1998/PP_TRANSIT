import { computed, ref, type Ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http, { getHttpErrorNotice } from '@/utils/http';


export function useAdminSettings(query: Ref<string>) {
  const rows = ref<any[]>([])
  const report = ref<Record<string, any>>({})
  const legalStatus = ref<any>(null)
  const paymentSwitchSaving = ref(false)
  const legalApprovalSaving = ref(false)
  const legalApprovalStatusLoaded = computed(() => typeof legalStatus.value?.legalReviewRequired === 'boolean' && Array.isArray(legalStatus.value?.missing_fields))
  async function setLegalPublicationApproved(approved: boolean) {
    if (legalApprovalSaving.value || !legalApprovalStatusLoaded.value) return
    if (approved && legalStatus.value.missing_fields.length > 0) {
      ElMessage.warning('请先补齐公开信息必填字段')
      return
    }
    legalApprovalSaving.value = true
    try {
      await ElMessageBox.confirm(
        approved ? '确认已核实真实经营信息、服务商、处理地区、保留期限及退款能力，并完成中英文政策与法律审核？批准后将设置 legal.publication_approved=true，但不会开启收款。' : '确认撤销政策审核批准？将设置 legal.publication_approved=false，新付款会继续受保护；已发出的支付链接及回调仍可能完成。',
        approved ? '批准政策发布' : '撤销政策批准', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
      )
      const key = 'legal.publication_approved', value = String(approved), description = '运营方显式确认公开信息与政策已审核；与收款开关独立'
      await http.put('/api/admin/api/settings', { key, value, description })
      await handleLegalFieldSaved(key, value, description)
      ElMessage.success(approved ? '政策审核批准已保存，收款开关未改变' : '政策审核批准已撤销，收款开关未改变')
    } catch (error: unknown) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(getHttpErrorNotice(error, '保存政策审核开关失败'))
    } finally { legalApprovalSaving.value = false }
  }
  async function handleLegalFieldSaved(key: string, value: string, description: string) {
    const row = rows.value.find(item => item.setting_key === key)
    if (row) { row.setting_value = value; row.description = description }
    else rows.value.push({ setting_key: key, setting_value: value, description })
    try { legalStatus.value = (await http.get('/api/public/legal')).data }
    catch (error: unknown) { ElMessage.warning(getHttpErrorNotice(error, '已保存，但状态刷新失败，请手动刷新')) }
  }
  async function setPaymentsEnabled(enabled: boolean) {
    try {
      await ElMessageBox.confirm(
        enabled ? '确认允许发起新的线上付款？请先核对经营主体、地区、支付渠道及公开条款。此操作不能替代合规审核。' : '确认关闭新付款？已经发出的支付链接和回调仍可能完成。',
        enabled ? '开启收款总开关' : '关闭收款总开关', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
      )
      paymentSwitchSaving.value = true
      await http.put('/api/admin/api/settings', { key: 'commerce.payments_enabled', value: String(enabled), description: '运营方显式控制新付款发起；默认关闭' })
      legalStatus.value = (await http.get('/api/public/legal')).data
      ElMessage.success(enabled ? '已开启人工收款开关（仍受公开信息门禁约束）' : '已关闭新付款')
    } catch (error: any) {
      if (error !== 'cancel' && error !== 'close') ElMessage.error(getHttpErrorNotice(error, '保存支付开关失败'))
    } finally { paymentSwitchSaving.value = false }
  }
  async function loadSettings() {
    legalStatus.value = null
    const [settings, reports, legal] = await Promise.all([
      http.get('/api/admin/api/settings'), http.get('/api/admin/api/reports'), http.get('/api/public/legal')
    ])
    rows.value = settings.data
    report.value = reports.data
    legalStatus.value = legal.data
  }
  const filteredRows = computed(() => {
    const needle = query.value.trim().toLowerCase()
    return rows.value.filter(row => !needle || JSON.stringify(row).toLowerCase().includes(needle))
  })

  return {
    rows,
    report,
    legalStatus,
    paymentSwitchSaving,
    legalApprovalSaving,
    legalApprovalStatusLoaded,
    setLegalPublicationApproved,
    handleLegalFieldSaved,
    setPaymentsEnabled,
    loadSettings,
    filteredRows,
  }
}
