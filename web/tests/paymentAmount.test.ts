import { describe, expect, it } from 'vitest'
import { paymentActionOf, paymentAmountText } from '../src/utils/payment'
describe('checkout settlement amount', () => {
  it('uses the extra cent from the payment intent rather than the original order amount', () => {
    expect(paymentAmountText({paymentIntent:{settlementAmountCents:125001,settlementCurrency:'CNY'},order:{amountCents:125000,currency:'CNY'}})).toBe('¥1,250.01 CNY')
  })
  it('uses CNY settlement for a USD recharge or product quote', () => {
    expect(paymentAmountText({intent:{sourceAmount:100,sourceCurrency:'USD',settlementAmountCents:677,settlementCurrency:'CNY'}})).toBe('¥6.77 CNY')
  })
  it('can use a server-created payment snapshot when the start response omits it', () => {
    expect(paymentActionOf({action:{type:'QRCODE',url:'weixin://test'}},{paymentIntent:{settlementAmountCents:'125001',settlementCurrency:'CNY'}})?.amountText).toBe('¥1,250.01 CNY')
  })
  it('never substitutes source amount or zero when settlement data is missing or invalid', () => {
    expect(paymentAmountText({order:{amountCents:125000,currency:'CNY'}})).toBeUndefined()
    for(const amount of [null,NaN,Infinity,-1,1.1,'',0,'unsafe',Number.MAX_SAFE_INTEGER+1])expect(paymentAmountText({intent:{settlementAmountCents:amount,settlementCurrency:'CNY'}})).toBeUndefined()
  })
  it('formats integer strings without losing a cent', () => {
    expect(paymentAmountText({intent:{settlementAmountCents:'9007199254740993',settlementCurrency:'CNY'}})).toBe('¥90,071,992,547,409.93 CNY')
  })
})
