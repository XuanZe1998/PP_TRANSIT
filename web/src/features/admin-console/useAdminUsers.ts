import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import http, { getHttpErrorMessage } from '@/utils/http';

import { money } from './format';

export function useAdminUsers(load: () => Promise<void>) {
  const adjustVisible = ref(false)
  const adjustSaving = ref(false)
  const adjustTarget = ref<any | null>(null)
  const adjustForm = reactive({ amount: 0, reason: '' })
  function openAdjust(row: any) {
    adjustTarget.value = row
    adjustForm.amount = 0
    adjustForm.reason = ''
    adjustVisible.value = true
  }

  async function upgradeEnterprise(row:any){
    try{
      const result=await ElMessageBox.prompt('请输入企业名称','升级为企业用户',{inputValue:`${row.username} 的企业`,inputPattern:/^.{1,160}$/,inputErrorMessage:'企业名称需为 1–160 个字符',type:'warning'})
      await http.post(`/api/admin/api/users/${row.id}/upgrade-enterprise`,{companyName:result.value.trim(),contactName:row.username})
      ElMessage.success('用户已升级为企业用户');await load()
    }catch(error:any){if(error==='cancel'||error==='close')return;ElMessage.error(getHttpErrorMessage(error,'升级企业用户失败'))}
  }

  async function saveAdjust() {
    if (!adjustTarget.value) return
    if (!Number.isFinite(adjustForm.amount) || adjustForm.amount === 0) {
      ElMessage.warning('调整金额不能为 0')
      return
    }
    const reason = adjustForm.reason.trim()
    if (reason.length < 3 || reason.length > 500) {
      ElMessage.warning('调账原因必须为 3–500 个字符')
      return
    }
    try {
      await ElMessageBox.confirm(
        `确认将用户 ${adjustTarget.value.username} 的余额调整 ${adjustForm.amount > 0 ? '+' : ''}${adjustForm.amount.toLocaleString()} amount units（${money(adjustForm.amount)}）？`,
        '高风险财务操作',
        { type: 'warning', confirmButtonText: '确认调账' }
      )
    } catch {
      return
    }
    adjustSaving.value = true
    try {
      await http.post(`/api/admin/api/users/${adjustTarget.value.id}/adjust-balance`, {
        amount: adjustForm.amount,
        reason
      })
      ElMessage.success('调账成功，已写入财务流水')
      adjustVisible.value = false
      await load()
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, '调账失败'))
    } finally {
      adjustSaving.value = false
    }
  }

  return {
    adjustVisible,
    adjustSaving,
    adjustTarget,
    adjustForm,
    openAdjust,
    upgradeEnterprise,
    saveAdjust,
  }
}
