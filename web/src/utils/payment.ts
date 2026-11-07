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

export function paymentActionOf(value?: PaymentStart | null, fallback?: PaymentStart): PaymentAction | null {
  const action = value?.action
  const expiresAt = paymentExpiryOf(value) ?? paymentExpiryOf(fallback)
  const amountText = paymentAmountText(value) || paymentAmountText(fallback)
  if (action?.url && ['REDIRECT', 'QRCODE', 'URL_SCHEME'].includes(action.type)) return { ...action, amountText, expiresAt }
  const intent = value?.intent || value?.paymentIntent
  const url = String(value?.paymentUrl || intent?.paymentUrl || '')
  const type = String(value?.payType || intent?.paymentActionType || '')
  return url && ['REDIRECT', 'QRCODE', 'URL_SCHEME'].includes(type)
    ? { type: type as PaymentAction['type'], url, amountText, expiresAt }
    : null
}

// Payment displays must use the settlement currency, never the selected display currency or quoted source price.
export function paymentAmountText(value?: PaymentStart | null): string | undefined {
  const intent = value?.intent || value?.paymentIntent
  const cents = intent?.settlementAmountCents ?? value?.order?.paymentAmountCents
  const currency = intent?.settlementAmountCents != null ? intent.settlementCurrency : value?.order?.paymentCurrency
  if ((typeof cents !== 'number' && typeof cents !== 'string') || (typeof cents === 'number' && !Number.isSafeInteger(cents))
      || !/^[0-9]+$/.test(String(cents)) || typeof currency !== 'string' || !/^[A-Z]{3}$/.test(currency)) return undefined
  const amount = BigInt(cents)
  if (amount <= 0n) return undefined
  return `${currency === 'CNY' ? '¥' : ''}${(amount / 100n).toLocaleString('en-US')}.${String(amount % 100n).padStart(2, '0')} ${currency}`
}

// Backend LocalDateTime payment snapshots are stored in UTC; do not interpret them as browser-local time.
export function paymentExpiryOf(value?: PaymentStart | null): number | undefined {
  const raw = value?.expiresAt ?? value?.intent?.expiresAt ?? value?.paymentIntent?.expiresAt ?? value?.order?.reservationExpiresAt
  if (typeof raw !== 'string' || !raw.trim()) return undefined
  const text = raw.trim().replace(' ', 'T')
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?$/.test(text)) return undefined
  const utc = /(?:Z|[+-]\d{2}:\d{2})$/.test(text) ? text : `${text}Z`
  const ms = Date.parse(utc)
  return Number.isFinite(ms) ? ms : undefined
}
