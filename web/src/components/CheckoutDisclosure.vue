<template>
  <div class="checkout-legal">
    <el-alert v-if="localTestMode" type="warning" :closable="false"
      :title="en ? 'Local test: consent and real payment are skipped. Confirm to simulate a successful payment.' : '本地测试：已跳过协议确认与真实付款，确认后直接模拟支付成功。'" />
    <template v-else>
      <p>{{ en ? 'Review the order total, payment currency and any taxes before paying. See the published terms for the actual cancellation and refund conditions.' : '付款前请核对订单总价、支付币种及适用税费。退款与取消条件以公开政策为准。' }}</p>
      <p><router-link to="/terms" target="_blank">{{ en ? 'Terms' : '服务条款' }}</router-link> · <router-link to="/refund" target="_blank">{{ en ? 'Refunds and cancellation' : '退款与取消' }}</router-link> · <router-link to="/privacy" target="_blank">{{ en ? 'Privacy' : '隐私政策' }}</router-link> · <router-link to="/ai-data" target="_blank">{{ en ? 'AI data processing' : 'AI 数据处理' }}</router-link></p>
      <el-alert v-if="loading" type="info" :closable="false" :title="en ? 'Loading transaction terms…' : '正在加载交易条件…'" />
      <el-alert v-else-if="!disclosureAvailable" type="warning" :closable="false" :title="en ? 'Transaction terms are unavailable. Please try again later.' : '交易条件暂不可用，请稍后重试。'" />
      <el-alert v-else-if="!published && purpose === 'order'" type="warning" :closable="false" :title="en ? 'Online payments are disabled. You can still accept the terms and create an unpaid order.' : '在线收款开关已关闭，你仍可确认条款并创建待支付订单。'" />
      <el-alert v-else-if="!published" type="warning" :closable="false" :title="en ? 'Online payments are disabled. Please contact support.' : '在线收款开关已关闭，请联系平台客服。'" />
      <el-checkbox :model-value="modelValue" :disabled="!canAcknowledge" @update:model-value="emit('update:modelValue', Boolean($event))">{{ en ? 'I have read the transaction terms and refund policy' : '我已阅读交易条件和退款政策' }}</el-checkbox>
    </template>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import http from '@/utils/http'
import { isEnglish } from '@/i18n/locale'
const props = withDefaults(defineProps<{ modelValue: boolean; purpose?: 'order' | 'payment' }>(), { purpose: 'payment' })
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; 'update:disclosureId': [value: string] }>()
const en = isEnglish
const published = ref(false)
const loading = ref(true)
const disclosureAvailable = ref(false)
const canAcknowledge = computed(() => disclosureAvailable.value && (props.purpose === 'order' || published.value))
const localTestMode = ref(false)
onMounted(async () => {
  try {
    const legal = (await http.get('/api/public/legal')).data
    published.value = legal?.checkout_ready === true
    const disclosureId = typeof legal?.checkout_disclosure_id === 'string' ? legal.checkout_disclosure_id.trim() : ''
    disclosureAvailable.value = !!disclosureId
    localTestMode.value = published.value && !!disclosureId && legal?.local_checkout_test_mode === true
    emit('update:disclosureId', disclosureId)
    if (localTestMode.value) emit('update:modelValue', true)
  } catch {
    published.value = false
    localTestMode.value = false
    emit('update:disclosureId', '')
  } finally {
    loading.value = false
  }
  if (!canAcknowledge.value && props.modelValue) emit('update:modelValue', false)
})
</script>
<style scoped>.checkout-legal{margin:14px 0;padding:14px;border:1px solid var(--lnx-color-border);border-radius:8px;line-height:1.7}.checkout-legal p{margin:0 0 8px}.checkout-legal a{color:var(--lnx-text-link)}</style>
