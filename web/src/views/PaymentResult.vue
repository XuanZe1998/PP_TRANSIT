<template>
  <main class="payment-result">
    <el-card shadow="never">
      <el-result :icon="resultIcon" :title="title" :sub-title="subtitle">
        <template #extra>
          <el-button :loading="refreshing" @click="refresh">刷新状态</el-button>
          <el-button type="primary" @click="goBack">返回业务页面</el-button>
        </template>
      </el-result>
    </el-card>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http, { getHttpErrorMessage } from '@/utils/http'

const route = useRoute()
const router = useRouter()
const intent = ref<Record<string, any> | null>(null)
const order = ref<Record<string, any> | null>(null)
const error = ref('')
const refreshing = ref(false)
let timer: number | undefined

const intentId = computed(() => {
  const direct = Number(route.query.intentId || route.query.intent)
  if (Number.isInteger(direct) && direct > 0) return direct
  const matched = String(route.query.param || '').match(/^payment-intent:(\d+)$/)
  return matched ? Number(matched[1]) : 0
})
const reviewRequired = computed(() => order.value?.fulfillmentStatus === 'REVIEW_REQUIRED')
const resultIcon = computed(() => reviewRequired.value ? 'warning' : intent.value?.status === 'PAID' ? 'success' : error.value ? 'error' : 'info')
const title = computed(() => reviewRequired.value ? '支付成功，履约待处理'
  : intent.value?.status === 'PAID' ? '支付成功'
    : intent.value?.status === 'EXPIRED' ? '订单已过期'
      : error.value ? '暂时无法确认支付结果' : '支付结果处理中')
const subtitle = computed(() => reviewRequired.value ? '付款事实已记录，请等待管理员补充交付。'
  : error.value || (intent.value?.status === 'PAID' ? '账款已核验，请返回查看订单。'
    : intent.value?.status === 'EXPIRED' ? '若您已实际付款，后续回调到达后仍会正常记账。'
      : '正在等待 MaPay 回调，无需重复付款。'))

async function loadLocal() {
  if (!intentId.value) throw new Error('返回链接缺少支付意图标识')
  const response = await http.get(`/api/payment-intents/${intentId.value}`)
  intent.value = response.data
  if (intent.value?.businessType === 'SERVICE_ORDER' && intent.value?.businessId) {
    const serviceOrder = await http.get(`/api/service-orders/${intent.value.businessId}`)
    order.value = serviceOrder.data
  }
  if (['PAID', 'EXPIRED', 'CANCELLED'].includes(String(intent.value?.status))) stopPolling()
}

async function refresh() {
  refreshing.value = true
  error.value = ''
  try {
    if (!intentId.value) throw new Error('返回链接缺少支付意图标识')
    await http.post(`/api/payment-intents/${intentId.value}/query`)
    await loadLocal()
  } catch (cause) {
    error.value = getHttpErrorMessage(cause, cause instanceof Error ? cause.message : '查单失败')
  } finally {
    refreshing.value = false
  }
}

function stopPolling() { if (timer !== undefined) window.clearInterval(timer); timer = undefined }
function startPolling() {
  stopPolling()
  timer = window.setInterval(() => void loadLocal().catch(() => undefined), 2500)
}
function goBack() {
  void router.push(intent.value?.businessType === 'WALLET_RECHARGE' ? '/console/wallet' : '/services')
}

onMounted(async () => { await refresh(); if (!['PAID', 'EXPIRED', 'CANCELLED'].includes(String(intent.value?.status))) startPolling() })
onBeforeUnmount(stopPolling)
</script>

<style scoped>
.payment-result { min-height:70vh; display:grid; place-items:center; padding:32px 16px; background:#f6f8fb; }
.payment-result .el-card { width:min(680px, 100%); }
</style>
