<template>
  <main class="billing-downloads" v-loading="loading">
    <h1>账单与收据</h1>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-descriptions v-if="order" :column="1" border>
      <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
      <el-descriptions-item label="商品">{{ order.productName || order.planName }}</el-descriptions-item>
      <el-descriptions-item label="订单状态">{{ order.status }}</el-descriptions-item>
    </el-descriptions>
    <p v-if="order && !invoiceAvailable">此充值订单未申请账单，可下载支付收据。</p>
    <div v-if="order" class="actions">
      <el-button v-if="invoiceAvailable" :loading="downloading === 'invoice'" :disabled="!!downloading" @click="download('invoice')">下载账单</el-button>
      <el-button type="primary" :disabled="!receiptAvailable || !!downloading" :loading="downloading === 'receipt'" @click="download('receipt')">下载收据</el-button>
    </div>
    <p v-if="order && !receiptAvailable">支付确认后可下载收据。</p>
    <router-link :to="recharge ? '/console/wallet' : '/services'">返回订单页面</router-link>
  </main>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage, getHttpDownloadErrorMessage } from '@/utils/http'
import { downloadFile } from '@/utils/download'
const route=useRoute(), order=ref<any>(null), loading=ref(false), error=ref(''), downloading=ref('')
const recharge=computed(()=>route.params.type==='recharge')
const endpoint=computed(()=>`/api/${recharge.value?'platform/user/recharge-orders':'service-orders'}/${route.params.id}`)
const invoiceAvailable=computed(()=>!recharge.value || order.value?.invoiceRequested===true)
const receiptAvailable=computed(()=>['PAID','FULFILLED','REFUNDED'].includes(order.value?.status))
let generation=0
watch(()=>[route.params.type,route.params.id],async()=>{
  const current=++generation;order.value=null;error.value='';loading.value=true
  try{
    if(!['service','recharge'].includes(String(route.params.type)) || !/^[1-9][0-9]*$/.test(String(route.params.id))) throw new Error('订单地址无效')
    const {data}=await http.get(endpoint.value);if(current===generation) order.value=data
  }catch(e){if(current===generation)error.value=getHttpErrorMessage(e,'订单不存在或无权访问')}
  finally{if(current===generation)loading.value=false}
},{immediate:true})
async function download(type:'invoice'|'receipt'){
  downloading.value=type
  try{await downloadFile(`${endpoint.value}/${type}`,`${type}-${order.value.orderNo}.pdf`)}
  catch(e){ElMessage.error(await getHttpDownloadErrorMessage(e,'下载失败'))}finally{downloading.value=''}
}
</script>
<style scoped>.billing-downloads{max-width:760px;margin:48px auto;padding:24px}.actions{display:flex;gap:12px;margin:24px 0}h1{margin-bottom:24px}</style>
