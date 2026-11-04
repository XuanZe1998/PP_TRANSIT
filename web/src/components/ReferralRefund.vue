<template>
  <el-button :disabled="!['PAID','FULFILLED'].includes(order.status)" @click="open">登记已完成退款</el-button>
  <el-dialog v-model="visible" title="登记已完成退款" width="min(520px,95vw)" append-to-body>
    <p>订单 {{ order.orderNo }}。请填写已通过人工渠道完成的退款，登记后将扣回对应邀请佣金。</p>
    <el-form label-position="top">
      <el-form-item label="本次实际退款金额（人民币元）"><el-input-number v-model="amount" :precision="2" :min="0.01"/></el-form-item>
      <el-form-item label="其中商品退款金额（人民币元）"><el-input-number v-model="merchandise" :precision="2" :min="0" :max="amount"/></el-form-item>
      <p>商品退款不包含服务费和支付加价。仅按商品退款比例扣回佣金，全额退款时应填完整商品实付金额。</p>
      <el-form-item label="唯一退款凭证"><el-input v-model="reference" maxlength="160"/></el-form-item>
      <el-form-item label="实际退款时间"><el-date-picker v-model="date" type="datetime"/></el-form-item>
    </el-form>
    <template #footer><el-button :loading="saving" type="primary" @click="save">确认登记</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
const props = defineProps<{ order: { id: number; orderNo: string; status: string } }>()
const visible = ref(false), saving = ref(false), amount = ref(0.01), merchandise = ref(0), reference = ref(''), date = ref<Date>(new Date())
function open() { visible.value = true; amount.value = 0.01; merchandise.value = 0; reference.value = ''; date.value = new Date() }
async function save() {
  if (saving.value) return
  saving.value = true
  try {
    await ElMessageBox.confirm('确认退款已完成且凭证准确？', '核实退款', { confirmButtonText: '确定', cancelButtonText: '取消' })
    await http.post('/api/admin/api/referrals/orders/' + props.order.id + '/refunds', {
      amountCents: Math.round(amount.value * 100), merchandiseCents: Math.round(merchandise.value * 100), reference: reference.value, refundedAt: date.value.toISOString().slice(0,19)
    })
    visible.value = false
    ElMessage.success('退款已登记，相关佣金已同步调整')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(getHttpErrorMessage(e, '退款登记失败')) }
  finally { saving.value = false }
}
</script>
