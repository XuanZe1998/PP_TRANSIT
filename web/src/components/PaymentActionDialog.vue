<template>
  <el-dialog :model-value="modelValue" :title="copy.title" width="min(440px, 94vw)" @update:model-value="emit('update:modelValue', $event)">
    <div v-if="action" class="payment-action">
      <template v-if="action.type === 'QRCODE'">
        <div class="payment-amount" aria-live="polite"><span>{{ copy.amountLabel }}</span><strong>{{ action.amountText || copy.amountUnavailable }}</strong></div>
        <p>{{ methodText(copy.qrPrompt) }}</p>
        <el-alert :title="copy.qrNotice" type="info" :closable="false" show-icon />
        <div class="qr-frame"><QrcodeVue :value="action.url" :size="240" level="M" /></div>
      </template>
      <template v-else-if="action.type === 'URL_SCHEME'">
        <p>{{ methodText(copy.schemePrompt) }}</p>
        <el-button type="primary" size="large" @click="openAction">{{ methodText(copy.openButton) }}</el-button>
      </template>
      <template v-else>
        <p>{{ copy.redirectPrompt }}</p>
        <el-button type="primary" size="large" @click="openAction">{{ copy.redirectButton }}</el-button>
      </template>
      <el-alert v-if="action.type !== 'QRCODE'" :title="copy.pendingNotice" type="info" :closable="false" show-icon />
    </div>
    <el-empty v-else :description="copy.emptyText" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { loadPaymentContent, paymentContentDefaults } from '@/utils/paymentContent'
import QrcodeVue from 'qrcode.vue'

export type PaymentAction = { type: 'REDIRECT' | 'QRCODE' | 'URL_SCHEME'; url: string; amountText?: string }

const props = defineProps<{ modelValue: boolean; action: PaymentAction | null; paymentMethod?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
const copy = ref({ ...paymentContentDefaults })
watch(() => props.modelValue, async visible => {
  if (visible) { try { copy.value = await loadPaymentContent() } catch { /* keep usable default payment instructions */ } }
}, { immediate: true })
const methodText = (text: string) => text.replaceAll('{method}', paymentMethodLabel.value)
const paymentMethodLabel = computed(() => props.paymentMethod === 'wxpay' ? copy.value.wxpayLabel : copy.value.alipayLabel)

function openAction() {
  if (!props.action?.url) return
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
.qr-frame { padding:14px; border:1px solid #dbe3ec; border-radius:14px; background:#fff; line-height:0; }
</style>
