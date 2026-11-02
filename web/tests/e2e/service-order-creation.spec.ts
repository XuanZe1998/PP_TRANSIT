import { expect, test, type Page } from '@playwright/test'

async function prepare(page: Page, state: 'closed-payment' | 'missing-terms' | 'missing-version') {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'order-regression-only')
    localStorage.setItem('user_info', JSON.stringify({ username: 'order-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  const creates: Array<{ body: any; headers: Record<string, string> }> = []
  const payments: string[] = []
  const order = { id: 71, orderNo: 'UNPAID-ORDER-71', serviceId: 7, productName: '测试商品',
    amountCents: 100, currency: 'CNY', status: 'PENDING', createdAt: '2026-11-02 10:00:00' }
  await page.route(url => url.origin === 'https://api.linknux.com' || url.pathname.startsWith('/api/'), async route => {
    const req = route.request(), path = new URL(req.url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/public/legal') return route.fulfill(state === 'missing-terms'
      ? { status: 503, json: {} }
      : { json: { payments_enabled: true, publication_ready: false, checkout_ready: false,
        checkout_disclosure_id: state === 'missing-version' ? '' : 'current-terms', local_checkout_test_mode: false } })
    if (path === '/public/other-services') return route.fulfill({ json: { total: 1, page: 1, size: 10,
      items: [{ id: 7, name: '测试商品', orderEnabled: true, priceCents: 100, amountCents: 100, currency: 'CNY', maxPurchaseQuantity: 1 }] } })
    if (path === '/service-orders/quote') return route.fulfill({ json: { available: true, amountCents: 100,
      merchandiseSubtotalCents: 100, serviceFeeCents: 0, wholesaleDiscountCents: 0, couponDiscountCents: 0, currency: 'CNY', inputFields: [] } })
    if (path === '/service-orders' && req.method() === 'POST') {
      creates.push({ body: req.postDataJSON(), headers: req.headers() })
      return route.fulfill({ json: { order, message: '订单已创建' } })
    }
    if (path === '/service-orders') return route.fulfill({ json: { total: creates.length, page: 1, size: 10, items: creates.length ? [order] : [] } })
    if (path.endsWith('/payment')) payments.push(path)
    return route.fulfill({ json: {} })
  })
  await page.goto('/services')
  await page.getByRole('button', { name: '立即购买', exact: true }).click()
  return { dialog: page.getByRole('dialog', { name: '填写账单信息', exact: true }), creates, payments }
}

test('payment readiness does not disable consent or creation of an unpaid service order', async ({ page }) => {
  const { dialog, creates, payments } = await prepare(page, 'closed-payment')
  await expect(dialog.getByText('当前在线付款暂未开放，你仍可确认条款并创建待支付订单。')).toBeVisible()
  const consent = dialog.getByRole('checkbox')
  await expect(consent).toBeEnabled()
  await expect(dialog.getByRole('button', { name: '创建订单', exact: true })).toBeDisabled()
  for (const [placeholder, value] of [['例如：张三', '测试买家'], ['name@example.com', 'buyer@example.com'],
    ['例如：广东省', '广东省'], ['例如：深圳市', '深圳市'], ['例如：南山区', '南山区'],
    ['中国大陆地址填写 6 位邮编', '518000'], ['街道、道路、门牌号、楼栋和房间号', '测试路1号']]) {
    await dialog.getByPlaceholder(placeholder, { exact: true }).fill(value)
  }
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await expect(consent).toBeChecked()
  await dialog.getByRole('button', { name: '创建订单', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByText('UNPAID-ORDER-71', { exact: true })).toBeVisible()
  expect(creates).toHaveLength(1)
  expect(creates[0].body).toMatchObject({ serviceId: 7, billingCountry: 'China', quantity: 1 })
  expect(creates[0].headers['idempotency-key']).toMatch(/^service-order-/)
  expect(payments).toEqual([])
  await page.getByRole('button', { name: '去支付', exact: true }).click()
  const payment = page.getByRole('dialog', { name: '确认支付', exact: true })
  await expect(payment.getByRole('checkbox')).toBeDisabled()
  await expect(payment.getByRole('button', { name: '确认支付', exact: true })).toBeDisabled()
  expect(payments).toEqual([])
})

for (const state of ['missing-terms', 'missing-version'] as const) {
  test(`order consent stays blocked for ${state}`, async ({ page }) => {
    const { dialog, creates } = await prepare(page, state)
    await expect(dialog.getByText('交易条件暂不可用，请稍后重试。')).toBeVisible()
    await expect(dialog.getByRole('checkbox')).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '创建订单', exact: true })).toBeDisabled()
    expect(creates).toEqual([])
  })
}
