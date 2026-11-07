<template>
  <el-dialog :model-value="modelValue" :title="copy.title" width="min(440px, 94vw)" @update:model-value="emit('update:modelValue', $event)">
    <div v-if="action" class="payment-action">
      <div v-if="action.type === 'QRCODE'" class="payment-amount"><span>{{ copy.amountLabel }}</span><strong>{{ action.amountText || copy.amountUnavailable }}</strong></div>
      <div class="payment-expiry">
        <template v-if="action.expiresAt != null">
          <p>{{ copy.expiryLabel }}：<time :datetime="new Date(action.expiresAt).toISOString()">{{ deadline }}</time></p>
          <p v-if="!expired">{{ copy.remainingLabel }}：<strong class="payment-countdown">{{ remaining }}</strong></p>
        </template>
        <p v-else>{{ copy.expiryUnavailable }}</p>
      </div>
      <el-alert v-if="expired" :title="copy.expiredNotice" type="warning" :closable="false" show-icon />
      <template v-if="action.type === 'QRCODE'">
        <p v-if="!expired">{{ methodText(copy.qrPrompt) }}</p>
        <el-alert v-if="!expired" :title="copy.qrNotice" type="info" :closable="false" show-icon />
        <div v-if="!expired" class="qr-frame"><QrcodeVue :value="action.url" :size="240" level="M" /></div>
      </template>
      <template v-else-if="action.type === 'URL_SCHEME'">
        <p>{{ methodText(copy.schemePrompt) }}</p>
        <el-button type="primary" size="large" :disabled="expired" @click="openAction">{{ methodText(copy.openButton) }}</el-button>
      </template>
      <template v-else>
        <p>{{ copy.redirectPrompt }}</p>
        <el-button type="primary" size="large" :disabled="expired" @click="openAction">{{ copy.redirectButton }}</el-button>
      </template>
      <el-alert v-if="action.type !== 'QRCODE'" :title="copy.pendingNotice" type="info" :closable="false" show-icon />
    </div>
    <el-empty v-else :description="copy.emptyText" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { loadPaymentContent, paymentContentDefaults } from '@/utils/paymentContent'
import QrcodeVue from 'qrcode.vue'

export type PaymentAction = { type: 'REDIRECT' | 'QRCODE' | 'URL_SCHEME'; url: string; amountText?: string; expiresAt?: number }

const props = defineProps<{ modelValue: boolean; action: PaymentAction | null; paymentMethod?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
const now = ref(Date.now())
let timer: ReturnType<typeof setInterval> | undefined
watch(() => [props.modelValue, props.action?.expiresAt], () => {
  if (timer !== undefined) clearInterval(timer)
  timer = undefined
  now.value = Date.now()
  if (props.modelValue && props.action?.expiresAt != null) timer = setInterval(() => { now.value = Date.now() }, 1000)
}, { immediate: true })
onBeforeUnmount(() => { if (timer !== undefined) clearInterval(timer) })
const secondsLeft = computed(() => Math.max(0, Math.ceil(((props.action?.expiresAt ?? now.value) - now.value) / 1000)))
const expired = computed(() => props.action?.expiresAt != null && now.value >= props.action.expiresAt)
const remaining = computed(() => {
  const seconds = secondsLeft.value
  return `${Math.floor(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}`
})
const deadline = computed(() => props.action?.expiresAt == null ? '' : new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23'
}).format(props.action.expiresAt))
const copy = ref({ ...paymentContentDefaults })
watch(() => props.modelValue, async visible => {
  if (visible) { try { copy.value = await loadPaymentContent() } catch { /* keep usable default payment instructions */ } }
}, { immediate: true })
const methodText = (text: string) => text.replaceAll('{method}', paymentMethodLabel.value)
const paymentMethodLabel = computed(() => props.paymentMethod === 'wxpay' ? copy.value.wxpayLabel : copy.value.alipayLabel)

function openAction() {
  if (!props.action?.url || (props.action.expiresAt != null && Date.now() >= props.action.expiresAt)) return
  if (props.action.type === 'REDIRECT') {
    window.open(props.action.url, '_blank', 'noopener,noreferrer')
  } else {
    window.location.href = props.action.url
  }
}
</script>

<style scoped>
.payment-action { display:grid; justify-items:center; gap:18px; text-align:center; }
.payment-amount { display:grid; gap:6px; max-width:100%; overflow-wrap:anywhere; }
.payment-amount span { color:var(--lnx-text-regular); font-size:14px; }
.payment-amount strong { font-size:28px; line-height:1.3; color:var(--lnx-text-primary); font-variant-numeric:tabular-nums; }
.payment-action p { margin:0; color:var(--lnx-text-regular); }
.payment-action .el-alert { text-align:left; }
.payment-expiry { font-size:13px; line-height:1.8; overflow-wrap:anywhere; }
.payment-countdown { font-size:18px; font-variant-numeric:tabular-nums; }
.qr-frame { padding:14px; border:1px solid #dbe3ec; border-radius:14px; background:#fff; line-height:0; }
</style>
