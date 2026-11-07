import { expect, test, type Page } from '@playwright/test'

type CheckoutState = { ready: boolean; disclosureId: string; legalFails?: boolean; paymentFails?: boolean; agreementFails?: boolean; localTest?: boolean; termsVersion?: string; privacyVersion?: string }

async function prepareCheckout(page: Page, state: CheckoutState) {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'checkout-test-only')
    localStorage.setItem('user_info', JSON.stringify({ username: 'checkout-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  const order = { id: 41, orderNo: 'CHECKOUT-TEST-41', productName: '测试服务', amountCents: 125000,
    currency: 'CNY', paymentAmountCents: 125001, paymentCurrency: 'CNY', paymentMethod: 'wxpay',
    status: 'PENDING', createdAt: '2026-10-17 10:00:00' }
  const starts: Record<string, string>[] = []
  const acceptances: unknown[] = []
  let agreementAccepted = false
  const orderPages: URLSearchParams[] = []
  await page.route(url => url.origin === 'https://api.linknux.com' || url.pathname.startsWith('/api/'), async route => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/public/legal') return route.fulfill(state.legalFails
      ? { status: 503, json: { message: 'Legal disclosures unavailable' } }
      : { json: { checkout_ready: state.ready, payments_enabled: state.ready, publication_ready: false,
        terms_version: state.termsVersion ?? 'current-terms', privacy_version: state.privacyVersion ?? 'current-privacy',
        publication_review_required: false, checkout_disclosure_id: state.disclosureId, local_checkout_test_mode: state.localTest === true } })
    if (path === '/user/legal/accept') {
      expect(request.method()).toBe('POST')
      acceptances.push(request.postDataJSON())
      if (state.agreementFails) return route.fulfill({ status: 409, json: { message: '协议版本已更新，请重新阅读并接受' } })
      agreementAccepted = true
      return route.fulfill({ json: { accepted: true } })
    }
    if (path === '/service-orders/catalog') return route.fulfill({ json: { total: 0, page: 1, size: 10, items: [] } })
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
      return route.fulfill(state.paymentFails || !agreementAccepted
        ? { status: 409, json: { message: 'Checkout disclosures changed; review and accept the current terms' } }
        : { json: { order, action: { type: 'QRCODE', url: 'weixin://test-only-checkout' } } })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/services')
  await page.getByRole('button', { name: '去支付', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '确认支付', exact: true })
  await expect(dialog).toBeVisible()
  return { dialog, starts, orderPages, acceptances }
}

test('payment requires explicit consent and sends the current disclosure plus idempotency key', async ({ page }) => {
  const { dialog, starts, orderPages, acceptances } = await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure' })
  const confirm = dialog.getByRole('button', { name: '确认支付', exact: true })
  await expect(dialog.getByText('CHECKOUT-TEST-41', { exact: true })).toBeVisible()
  await expect(dialog.getByText('微信支付', { exact: true })).toBeVisible()
  await expect(dialog.getByText('¥1,250.00 CNY', { exact: true })).toBeVisible()
  await expect(dialog.getByText('¥1,250.01 CNY', { exact: true })).toBeVisible()
  await expect(confirm).toBeDisabled()
  expect(starts).toHaveLength(0)
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await expect(confirm).toBeEnabled()
  expect(acceptances).toEqual([])
  await page.screenshot({ path: 'test-results/service-payment-checkout.png', fullPage: true })
  await confirm.click()
  const payment = page.getByRole('dialog', { name: '完成支付', exact: true })
  await expect(payment).toBeVisible()
  await expect(payment.getByText('请使用微信支付扫码支付', { exact: true })).toBeVisible()
  await expect(payment.locator('.payment-amount')).toHaveText('应付金额¥1,250.01 CNY')
  await expect(payment.locator('.payment-amount')).not.toContainText('1,250.00')
  await payment.screenshot({ path: 'test-results/qr-payment-amount.png' })
  expect(starts).toHaveLength(1)
  expect(acceptances).toEqual([{ termsVersion: 'current-terms', privacyVersion: 'current-privacy' }])
  expect(starts[0]['x-checkout-disclosure']).toBe('current-disclosure')
  expect(starts[0]['idempotency-key']).toMatch(/^service-payment-41-/)
  expect(orderPages.length).toBeGreaterThanOrEqual(2)
  for (const params of orderPages) {
    expect(params.get('listPage')).toBe('true')
    expect(params.get('page')).toBe('1')
    expect(params.get('size')).toBe('10')
  }
})

test('agreement rejection blocks payment and requires renewed consent before retrying', async ({ page }) => {
  const state = { ready: true, disclosureId: 'current-disclosure', agreementFails: true, termsVersion: 'current-terms' }
  const { dialog, starts, acceptances } = await prepareCheckout(page, state)
  const checkbox = dialog.getByRole('checkbox')
  const confirm = dialog.getByRole('button', { name: '确认支付', exact: true })
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  state.termsVersion = 'updated-terms'
  state.disclosureId = 'updated-disclosure'
  await confirm.click()
  await expect(page.getByText('协议版本已更新，请重新阅读并接受', { exact: true })).toBeVisible()
  await expect(checkbox).not.toBeChecked()
  await expect(confirm).toBeDisabled()
  expect(starts).toHaveLength(0)
  expect(acceptances).toHaveLength(1)
  state.agreementFails = false
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await confirm.click()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).toBeVisible()
  expect(starts).toHaveLength(1)
  expect(acceptances).toEqual([
    { termsVersion: 'current-terms', privacyVersion: 'current-privacy' },
    { termsVersion: 'updated-terms', privacyVersion: 'current-privacy' }
  ])
  expect(starts[0]['x-checkout-disclosure']).toBe('updated-disclosure')
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

test('a rejected payment refreshes disclosures and requires renewed consent before retry', async ({ page }) => {
  const state = { ready: true, disclosureId: 'current-disclosure', paymentFails: true }
  const { dialog, starts } = await prepareCheckout(page, state)
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  const confirm = dialog.getByRole('button', { name: '确认支付', exact: true })
  await confirm.click()
  await expect(page.getByText('Checkout disclosures changed; review and accept the current terms', { exact: true })).toBeVisible()
  await expect(dialog).toBeVisible()
  await expect(confirm).toBeDisabled()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).not.toBeVisible()
  expect(starts).toHaveLength(1)
  state.paymentFails = false
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await confirm.click()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).toBeVisible()
  expect(starts).toHaveLength(2)
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


test('QR payment opens delivered keys even when the order is outside the current list page', async ({ page }) => {
  const { dialog } = await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure' })
  let fulfilled = false
  await page.route('**/service-orders/41', route => route.fulfill({ json: {
    id: 41, serviceId: 7, orderNo: 'CHECKOUT-TEST-41', status: fulfilled ? 'FULFILLED' : 'PENDING',
    fulfillmentStatus: fulfilled ? 'COMPLETED' : 'PENDING', deliveryItems: fulfilled ? ['TEST-KEY-ONLY'] : []
  } }))
  await dialog.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
  await dialog.getByRole('button', { name: '确认支付', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).toBeVisible()
  await page.route('**/service-orders?**', route => route.fulfill({ json: { total: 11, page: 1, size: 10, items: [] } }))
  fulfilled = true
  const detail = page.getByRole('dialog', { name: '卡密与订单详情', exact: true })
  await expect(detail).toBeVisible({ timeout: 10000 })
  await expect(detail).toContainText('TEST-KEY-ONLY')
  await expect(page.getByRole('dialog', { name: '完成支付', exact: true })).not.toBeVisible()
})

test('verified payment return opens delivery and waits for delayed keys', async ({ page }) => {
  await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure' })
  let delivered = false
  await page.route('**/payment-intents/9/query', route => route.fulfill({ json: {} }))
  await page.route('**/payment-intents/9', route => route.fulfill({ json: { id: 9, status: 'PAID', businessType: 'SERVICE_ORDER', businessId: 41 } }))
  await page.route('**/service-orders/41', route => route.fulfill({ json: {
    id: 41, serviceId: 7, orderNo: 'CHECKOUT-TEST-41', status: delivered ? 'FULFILLED' : 'PAID',
    fulfillmentStatus: delivered ? 'COMPLETED' : 'PROCESSING', deliveryItems: delivered ? ['RETURN-KEY-ONLY'] : []
  } }))
  await page.goto('/payment/result?intentId=9')
  await expect(page).toHaveURL(/services\?orderId=41/)
  const detail = page.getByRole('dialog', { name: '卡密与订单详情', exact: true })
  await expect(detail).toContainText('正在等待发货')
  delivered = true
  await expect(detail).toContainText('RETURN-KEY-ONLY', { timeout: 10000 })
})

test('historical paid orders do not continuously reload the paginated order list', async ({ page }) => {
  await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure' })
  const requests: URLSearchParams[] = []
  await page.route('**/service-orders?**', route => {
    const params = new URL(route.request().url()).searchParams
    requests.push(params)
    return route.fulfill({ json: { total: 11, page: Number(params.get('page')), size: 10, items: [{
      id: 41, orderNo: 'HISTORICAL-PAID', productName: '历史服务', status: 'PAID',
      fulfillmentStatus: 'RESERVED', fulfillmentMode: 'MANUAL', amountCents: 100, currency: 'CNY'
    }] } })
  })
  await page.goto('/services')
  await expect(page.getByText('HISTORICAL-PAID', { exact: true })).toBeVisible()
  await page.waitForTimeout(6500)
  expect(requests).toHaveLength(1)
  expect(requests[0].get('listPage')).toBe('true')
  expect(requests[0].get('size')).toBe('10')
  await expect(page.getByRole('dialog', { name: '卡密与订单详情', exact: true })).not.toBeVisible()
  await page.getByRole('button', { name: '刷新订单', exact: true }).click()
  await expect.poll(() => requests.length).toBe(2)
})

test('invoice downloads a PDF and displays the server reason if generation fails', async ({ page }) => {
  const { dialog } = await prepareCheckout(page, { ready: true, disclosureId: 'current-disclosure' })
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await page.route('**/service-orders/41/invoice', route => route.fulfill({
    contentType: 'application/pdf', body: '%PDF-1.4\n%%EOF'
  }))
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '下载账单', exact: true }).click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('invoice-41.pdf')
  expect(await download.failure()).toBeNull()
  await page.route('**/service-orders/41/invoice', route => route.fulfill({
    status: 503, json: { message: 'Billing configuration is incomplete' }
  }))
  await page.getByRole('button', { name: '下载账单', exact: true }).click()
  await expect(page.getByText('Billing configuration is incomplete', { exact: true })).toBeVisible()
})
