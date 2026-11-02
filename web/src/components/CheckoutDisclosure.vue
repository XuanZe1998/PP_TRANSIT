<template>
  <div class="checkout-legal">
    <el-alert v-if="localTestMode" type="warning" :closable="false"
      :title="en ? 'Local test: consent and real payment are skipped. Confirm to simulate a successful payment.' : '本地测试：已跳过协议确认与真实付款，确认后直接模拟支付成功。'" />
    <template v-else>
      <p>{{ en ? 'Review the order total, payment currency and any taxes before paying. See the published terms for the actual cancellation and refund conditions.' : '付款前请核对订单总价、支付币种及适用税费。退款与取消条件以公开政策为准。' }}</p>
      <p><router-link to="/terms" target="_blank">{{ en ? 'Terms' : '服务条款' }}</router-link> · <router-link to="/refund" target="_blank">{{ en ? 'Refunds and cancellation' : '退款与取消' }}</router-link> · <router-link to="/privacy" target="_blank">{{ en ? 'Privacy' : '隐私政策' }}</router-link> · <router-link to="/ai-data" target="_blank">{{ en ? 'AI data processing' : 'AI 数据处理' }}</router-link></p>
      <el-alert v-if="!published" type="warning" :closable="false" :title="en ? 'Checkout is closed: payment activation or reviewed terms are not ready.' : '收款未开启或公开信息尚未审核完成，暂不能结账。'" />
      <el-checkbox :model-value="modelValue" :disabled="!published" @update:model-value="emit('update:modelValue', Boolean($event))">{{ en ? 'I have read the transaction terms and refund policy' : '我已阅读交易条件和退款政策' }}</el-checkbox>
    </template>
  </div>
</template>
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http from '@/utils/http'
import { isEnglish } from '@/i18n/locale'
const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; 'update:disclosureId': [value: string] }>()
const en = isEnglish
const published = ref(false)
const localTestMode = ref(false)
onMounted(async () => {
  try {
    const legal = (await http.get('/api/public/legal')).data
    published.value = legal?.checkout_ready === true
    const disclosureId = published.value ? String(legal.checkout_disclosure_id || '') : ''
    localTestMode.value = published.value && !!disclosureId && legal?.local_checkout_test_mode === true
    emit('update:disclosureId', disclosureId)
    if (localTestMode.value) emit('update:modelValue', true)
  } catch {
    published.value = false
    localTestMode.value = false
    emit('update:disclosureId', '')
  }
  if (!published.value && props.modelValue) emit('update:modelValue', false)
})
</script>
<style scoped>.checkout-legal{margin:14px 0;padding:14px;border:1px solid var(--lnx-color-border);border-radius:8px;line-height:1.7}.checkout-legal p{margin:0 0 8px}.checkout-legal a{color:var(--lnx-text-link)}</style>
