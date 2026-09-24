<template>
  <el-dialog :model-value="modelValue" title="完成支付" width="min(440px, 94vw)" @update:model-value="emit('update:modelValue', $event)">
    <div v-if="action" class="payment-action">
      <template v-if="action.type === 'QRCODE'">
        <p>请使用{{ paymentMethodLabel }}扫码支付</p>
        <div class="qr-frame"><QrcodeVue :value="action.url" :size="240" level="M" /></div>
      </template>
      <template v-else-if="action.type === 'URL_SCHEME'">
        <p>请在移动设备上唤起{{ paymentMethodLabel }}完成付款。</p>
        <el-button type="primary" size="large" @click="openAction">打开{{ paymentMethodLabel }}</el-button>
      </template>
      <template v-else>
        <p>将在 MaPay 收银台完成付款，付款后可返回本页查看结果。</p>
        <el-button type="primary" size="large" @click="openAction">前往支付</el-button>
      </template>
      <el-alert title="支付状态以服务端回调为准，请勿重复创建订单。" type="info" :closable="false" show-icon />
    </div>
    <el-empty v-else description="支付动作尚未生成" />
  </el-dialog>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import QrcodeVue from 'qrcode.vue'

export type PaymentAction = { type: 'REDIRECT' | 'QRCODE' | 'URL_SCHEME'; url: string }

const props = defineProps<{ modelValue: boolean; action: PaymentAction | null; paymentMethod?: string }>()
const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
const paymentMethodLabel = computed(() => props.paymentMethod === 'wxpay' ? '微信支付' : '支付宝')

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
.payment-action p { margin:0; color:#475569; }
.payment-action .el-alert { text-align:left; }
.qr-frame { padding:14px; border:1px solid #dbe3ec; border-radius:14px; background:#fff; line-height:0; }
</style>
