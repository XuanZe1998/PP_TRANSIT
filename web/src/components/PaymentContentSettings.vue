<template>
  <el-form v-loading="loading" label-position="top" class="payment-content-settings">
    <el-alert title="这些设置适用于订阅、充值和商品结账。{method} 表示付款方式，邮件标题中的 {orderNo} 表示订单号。" type="info" :closable="false" />
    <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
      <el-input v-model="form[field.key]" :type="field.long ? 'textarea' : 'text'" :rows="3" :maxlength="field.long ? 3000 : 300" show-word-limit />
    </el-form-item>
    <el-button type="primary" :disabled="!loaded" :loading="saving" @click="save">保存支付文案</el-button>
  </el-form>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { loadPaymentContent, paymentContentDefaults, type PaymentContent } from '@/utils/paymentContent'
const form = reactive({ ...paymentContentDefaults })
const loading=ref(false), loaded=ref(false), saving=ref(false)
const fields: { key: keyof PaymentContent; label: string; long?: boolean }[] = [
  {key:'amountLabel',label:'应付金额标题'}, {key:'amountUnavailable',label:'金额缺失时的提示'},
  {key:'title',label:'支付弹窗标题'}, {key:'qrPrompt',label:'扫码说明'}, {key:'qrNotice',label:'扫码支付提示',long:true},
  {key:'schemePrompt',label:'手机唤起说明'}, {key:'openButton',label:'唤起支付按钮'},
  {key:'redirectPrompt',label:'收银台说明'}, {key:'redirectButton',label:'收银台按钮'},
  {key:'pendingNotice',label:'支付状态提示',long:true}, {key:'emptyText',label:'未生成支付信息时的提示'},
  {key:'alipayLabel',label:'支付宝名称'}, {key:'wxpayLabel',label:'微信支付名称'},
  {key:'websiteUrl',label:'账单下载站点（HTTPS 域名）'}, {key:'emailSubject',label:'付款成功邮件标题'},
  {key:'emailBody',label:'付款成功邮件说明',long:true}
]
onMounted(async()=>{loading.value=true;try{Object.assign(form,await loadPaymentContent());loaded.value=true}catch(e){ElMessage.error(getHttpErrorMessage(e,'加载失败'))}finally{loading.value=false}})
async function save(){saving.value=true;try{const {data}=await http.put('/api/admin/api/payment-content',form);Object.assign(form,data);ElMessage.success('保存成功')}catch(e){ElMessage.error(getHttpErrorMessage(e,'保存失败'))}finally{saving.value=false}}
</script>
<style scoped>.payment-content-settings{max-width:800px}.el-alert{margin-bottom:20px}</style>
