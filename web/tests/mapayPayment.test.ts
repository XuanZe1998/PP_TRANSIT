import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { paymentActionOf } from '../src/utils/payment'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('MaPay checkout contracts', () => {
  it('normalizes redirect, QR code and URL Scheme actions', () => {
    for (const type of ['REDIRECT', 'QRCODE', 'URL_SCHEME'] as const) {
      expect(paymentActionOf({ action: { type, url: `${type.toLowerCase()}://payment` } }))
        .toEqual({ type, url: `${type.toLowerCase()}://payment` })
    }
    expect(paymentActionOf({ payType: 'QRCODE', paymentUrl: 'weixin://payment' }))
      .toEqual({ type: 'QRCODE', url: 'weixin://payment' })
    expect(paymentActionOf({ action: { type: 'REDIRECT', url: '' } })).toBeNull()
  })

  it('renders QR locally and only opens redirect or scheme actions from a user click', () => {
    const dialog = source('src/components/PaymentActionDialog.vue')
    expect(dialog).toContain("import QrcodeVue from 'qrcode.vue'")
    expect(dialog).toContain("action.type === 'QRCODE'")
    expect(dialog).toContain("action.type === 'URL_SCHEME'")
    expect(dialog).toContain("window.open(props.action.url, '_blank', 'noopener,noreferrer')")
    expect(dialog).toContain('window.location.href = props.action.url')
  })

  it('offers only Alipay and WeChat and uses idempotency keys when starting payment', () => {
    const services = source('src/views/OtherServices.vue')
    const wallet = source('src/views/UserConsole.vue')
    for (const view of [services, wallet]) {
      expect(view).toContain('alipay')
      expect(view).toContain('wxpay')
      expect(view).toContain('Idempotency-Key')
    }
  })

  it('actively queries once on return, then polls only local state and stops on terminal status', () => {
    const result = source('src/views/PaymentResult.vue')
    expect(result).toContain("http.post(`/api/payment-intents/${intentId.value}/query`)")
    expect(result).toContain("http.get(`/api/payment-intents/${intentId.value}`)")
    expect(result).toContain('window.setInterval(() => void loadLocal()')
    expect(result).toContain("['PAID', 'EXPIRED', 'CANCELLED']")
    expect(result).toContain('REVIEW_REQUIRED')
  })

  it('keeps administrator payment records server-paged and contains no refund action', () => {
    const payments = source('src/views/AdminPaymentIntents.vue')
    const serviceOrders = source('src/views/AdminServiceOrders.vue')
    expect(payments).toContain("http.get('/api/admin/payment-intents'")
    expect(payments).toContain('ListPagination')
    expect(payments).toContain('手动查单')
    expect(payments).not.toContain('/refund')
    expect(serviceOrders).not.toContain('/refund')
  })
})
