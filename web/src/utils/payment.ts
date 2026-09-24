import type { PaymentAction } from '@/components/PaymentActionDialog.vue'

export type PaymentStart = {
  intent?: Record<string, any>
  action?: PaymentAction | null
  expiresAt?: string
  paymentIntent?: Record<string, any>
  paymentUrl?: string
  payType?: string
  order?: Record<string, any>
}

export function paymentActionOf(value?: PaymentStart | null): PaymentAction | null {
  const action = value?.action
  if (action?.url && ['REDIRECT', 'QRCODE', 'URL_SCHEME'].includes(action.type)) return action
  const intent = value?.intent || value?.paymentIntent
  const url = String(value?.paymentUrl || intent?.paymentUrl || '')
  const type = String(value?.payType || intent?.paymentActionType || '')
  return url && ['REDIRECT', 'QRCODE', 'URL_SCHEME'].includes(type)
    ? { type: type as PaymentAction['type'], url }
    : null
}
