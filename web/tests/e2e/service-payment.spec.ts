import { expect, test, type Page } from '@playwright/test'

type CheckoutState = { ready: boolean; disclosureId: string; legalFails?: boolean; paymentFails?: boolean; localTest?: boolean }

async function prepareCheckout(page: Page, state: CheckoutState) {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'checkout-test-only')
    localStorage.setItem('user_info', JSON.stringify({ username: 'checkout-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  const order = { id: 41, orderNo: 'CHECKOUT-TEST-41', productName: '测试服务', amountCents: 1,
    currency: 'CNY', paymentAmountCents: 1, paymentCurrency: 'CNY', paymentMethod: 'wxpay',
    status: 'PENDING', createdAt: '2026-10-17 10:00:00' }
  const starts: Record<string, string>[] = []
  const orderPages: URLSearchParams[] = []
  await page.route(url => url.origin === 'https://api.linknux.com' || url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/public/legal') return route.fulfill(state.legalFails
      ? { status: 503, json: { message: 'Legal disclosures unavailable' } }
      : { json: { checkout_ready: state.ready, payments_enabled: state.ready, publication_ready: false,
        publication_review_required: false, checkout_disclosure_id: state.disclosureId, local_checkout_test_mode: state.localTest === true } })
    if (path === '/public/other-services') return route.fulfill({ json: { total: 0, page: 1, size: 10, items: [] } })
    if (path === '/service-orders') {
      orderPages.push(url.searchParams)
      return route.fulfill({ json: { total: 1, page: 1, size: 10, items: [order] } })
    }
    if (path === '/service-orders/41/payment') {
      starts.push(request.headers())
      if (state.localTest && !state.paymentFails) {
        order.status = 'PAID'
        return route.fulfill({ json: { order, action: null } })
      }
      return route.fulfill(state.paymentFails
        ? { status: 409, json: { message: 'Checkout disclosures changed; review and accept the current terms' } }
        : { json: { order, action: { type: 'QRCODE', url: 'weixin://test-only-checkout' } } })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/services')
  await page.getByRole('button', { name: '去支付', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '确认支付', exact: true })
  await expect(dialog).toBeVisible()
  return { dialog, starts, orderPages }
}

test('payment requires explicit consent and sends the current disclosure plus idempotency key', async ({ page }) => {
  const { dialog, starts, orderPages } = await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure' })
  const confirm = dialog.getByRole('button', { name: '确认支付', exact: true })
  await expect(dialog.getByText('CHECKOUT-TEST-41', { exact: true })).toBeVisible()
  await expect(dialog.getByText('微信支付', { exact: true })).toBeVisible()
  await expect(confirm).toBeDisabled()
  expect(starts).toHaveLength(0)
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await expect(confirm).toBeEnabled()
  await page.screenshot({ path: 'test-results/service-payment-checkout.png', fullPage: true })
  await confirm.click()
  const payment = page.getByRole('dialog', { name: '完成支付', exact: true })
  await expect(payment).toBeVisible()
  await expect(payment.getByText('请使用微信支付扫码支付', { exact: true })).toBeVisible()
  expect(starts).toHaveLength(1)
  expect(starts[0]['x-checkout-disclosure']).toBe('current-disclosure')
  expect(starts[0]['idempotency-key']).toMatch(/^service-payment-41-/)
  expect(orderPages.length).toBeGreaterThanOrEqual(2)
  for (const params of orderPages) {
    expect(params.get('listPage')).toBe('true')
    expect(params.get('page')).toBe('1')
    expect(params.get('size')).toBe('10')
  }
})

for (const scenario of ['closed', 'unavailable', 'missing-version'] as const) {
  test(`payment stays blocked when checkout disclosures are ${scenario}`, async ({ page }) => {
    const { dialog, starts } = await prepareCheckout(page, {
      ready: scenario !== 'closed', disclosureId: scenario === 'missing-version' ? '' : 'current-disclosure',
      legalFails: scenario === 'unavailable'
    })
    if (scenario === 'closed') {
      await expect(dialog.getByText('在线收款开关已关闭，请联系平台客服。', { exact: true })).toBeVisible()
    }
    else await expect(dialog.getByText('交易条件暂不可用，请稍后重试。', { exact: true })).toBeVisible()
    await expect(dialog.getByRole('checkbox')).toBeDisabled()
    await expect(dialog.getByRole('button', { name: '确认支付', exact: true })).toBeDisabled()
    expect(starts).toHaveLength(0)
  })
}

test('reopening checkout clears consent and fetches the new disclosure version', async ({ page }) => {
  const state = { ready: true, disclosureId: 'old-disclosure' }
  const { dialog, starts } = await prepareCheckout(page, state)
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(dialog).not.toBeVisible()
  state.disclosureId = 'new-disclosure'
  await page.getByRole('button', { name: '去支付', exact: true }).click()
  await expect(dialog.getByRole('checkbox')).not.toBeChecked()
  await expect(dialog.getByRole('button', { name: '确认支付', exact: true })).toBeDisabled()
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await dialog.getByRole('button', { name: '确认支付', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).toBeVisible()
  expect(starts).toHaveLength(1)
  expect(starts[0]['x-checkout-disclosure']).toBe('new-disclosure')
})

test('a rejected payment stays in checkout and does not open a payment action', async ({ page }) => {
  const { dialog, starts } = await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure', paymentFails: true })
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  const confirm = dialog.getByRole('button', { name: '确认支付', exact: true })
  await confirm.click()
  await expect(page.getByText('Checkout disclosures changed; review and accept the current terms', { exact: true })).toBeVisible()
  await expect(dialog).toBeVisible()
  await expect(confirm).toBeEnabled()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).not.toBeVisible()
  expect(starts).toHaveLength(1)
})


test('local checkout skips consent and real payment without opening a QR dialog', async ({ page }) => {
  const { dialog, starts } = await prepareCheckout(page, {
    ready: true, disclosureId: 'local-test-disclosure', localTest: true
  })
  await expect(dialog.getByText('本地测试：已跳过协议确认与真实付款，确认后直接模拟支付成功。', { exact: true })).toBeVisible()
  await expect(dialog.getByRole('checkbox')).toHaveCount(0)
  const confirm = dialog.getByRole('button', { name: '确认支付', exact: true })
  await expect(confirm).toBeEnabled()
  await confirm.click()
  await expect(dialog).not.toBeVisible()
  await expect(page.getByText('支付成功', { exact: true })).toBeVisible()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).not.toBeVisible()
  expect(starts).toHaveLength(1)
  expect(starts[0]['x-checkout-disclosure']).toBe('local-test-disclosure')
})

for (const scenario of ['closed', 'missing-version'] as const) {
  test(`local test hint cannot bypass ${scenario} checkout`, async ({ page }) => {
    const { dialog, starts } = await prepareCheckout(page, {
      ready: scenario !== 'closed', disclosureId: scenario === 'missing-version' ? '' : 'local', localTest: true
    })
    await expect(dialog.getByRole('button', { name: '确认支付', exact: true })).toBeDisabled()
    expect(starts).toHaveLength(0)
  })
}
