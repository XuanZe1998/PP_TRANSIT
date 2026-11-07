import http from './http'
export const paymentContentDefaults = {
  amountLabel: '应付金额', amountUnavailable: '金额待确认',
  title: '完成支付', qrPrompt: '请使用{method}扫码支付',
  qrNotice: '支付完成后，状态更新可能稍有延迟。请稍等片刻并刷新页面查看订单状态，请勿重复支付。',
  schemePrompt: '请在移动设备上唤起{method}完成付款。', openButton: '打开{method}',
  redirectPrompt: '将在 MaPay 收银台完成付款，付款后可返回本页查看结果。', redirectButton: '前往支付',
  pendingNotice: '支付状态以服务端回调为准，请勿重复创建订单。', emptyText: '支付动作尚未生成',
  alipayLabel: '支付宝', wxpayLabel: '微信支付', websiteUrl: 'https://linknux.com',
  emailSubject: '付款成功：订单 {orderNo}', emailBody: '您的付款已确认。账单与收据见附件，也可登录订单页面下载。请勿重复付款。'
}
export type PaymentContent = typeof paymentContentDefaults
export async function loadPaymentContent(): Promise<PaymentContent> {
  const { data } = await http.get('/api/public/payment-content')
  const result = { ...paymentContentDefaults }
  for (const key of Object.keys(result) as (keyof PaymentContent)[]) {
    if (typeof data?.[key] === 'string' && data[key].trim()) result[key] = data[key]
  }
  return result
}
