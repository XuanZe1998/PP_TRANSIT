import { expect, test } from '@playwright/test'

for (const outcome of ['callback', 'already-paid', 'expired'] as const) {
  test(`existing recharge resumes its own payment and handles ${outcome}`, async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('linknux.locale', 'zh-CN')
      localStorage.setItem('user_access_token', 'wallet-test-token')
      localStorage.setItem('user_info', JSON.stringify({ username: 'wallet-test', role: 'USER' }))
      localStorage.setItem('user_last_active_at', String(Date.now()))
    })
    let status = 'PENDING'
    let creates = 0
    let starts = 0
    let receiptReads = 0
    const expiresAt = new Date(Date.now() + 600000).toISOString()
    const order = () => ({ id: 3, orderNo: 'RCG-EXISTING', status, paymentMethod: 'wxpay',
      paymentIntent: { id: 29, status, expiresAt, settlementMoney: { amount: 100, scale: 100, currency: 'CNY' } } })
    await page.route('**/api/**', route => {
      const request = route.request()
      const url = new URL(request.url())
      const path = url.pathname
      if (path.endsWith('/public/legal')) return route.fulfill({ json: { checkout_ready: true,
        checkout_disclosure_id: 'resume-v1', terms_version: 't1', privacy_version: 'p1' } })
      if (path.endsWith('/user/legal/accept')) return route.fulfill({ json: {} })
      if (path.endsWith('/user/wallet')) return route.fulfill({ json: { plans: [], balance: status === 'PAID' ? 10000 : 0 } })
      if (path.endsWith('/user/recharge-orders')) {
        if (request.method() === 'POST') { creates++; return route.fulfill({ status: 500, json: {} }) }
        expect(url.searchParams.get('listPage')).toBe('true')
        expect(url.searchParams.get('size')).toBe('10')
        return route.fulfill({ json: { items: url.searchParams.get('page') === '2' ? [order()] : [], total: 11, page: Number(url.searchParams.get('page')), size: 10 } })
      }
      if (path.endsWith('/recharge-orders/3')) return route.fulfill({ json: order() })
      if (path.endsWith('/payment-intents/29/start')) {
        starts++
        expect(request.headers()['x-checkout-disclosure']).toBe('resume-v1')
        expect(request.headers()['idempotency-key']).toBeTruthy()
        if (outcome === 'expired') {
          status = 'EXPIRED'
          return route.fulfill({ status: 409, json: { message: 'Payment intent has expired' } })
        }
        if (outcome === 'already-paid') status = 'PAID'
        return route.fulfill({ json: { intent: order().paymentIntent,
          action: { type: 'QRCODE', url: 'weixin://existing-order' }, expiresAt } })
      }
      if (path.endsWith('/payment-intents/29')) return route.fulfill({ json: { status } })
      if (path.endsWith('/recharge-orders/3/receipt')) {
        receiptReads++
        return route.fulfill({ contentType: 'application/pdf', body: '%PDF-1.4 receipt' })
      }
      return route.fulfill({ json: {} })
    })
    await page.goto('/console/wallet')
    await page.locator('.console-panel').filter({ has: page.getByRole('heading', { name: '充值订单', exact: true }) })
      .getByRole('button', { name: 'Go to next page' }).click()
    const row = page.getByRole('row').filter({ hasText: 'RCG-EXISTING' })
    await row.getByRole('button', { name: '去支付' }).click()
    const confirmation = page.getByRole('dialog', { name: '继续充值支付' })
    const pay = confirmation.getByRole('button', { name: '去支付', exact: true })
    await expect(pay).toBeDisabled()
    await confirmation.getByText('我已阅读交易条件和退款政策', { exact: true }).click()
    await pay.click()
    if (outcome === 'expired') {
      await expect(page.getByText('Payment intent has expired', { exact: true })).toBeVisible()
      await expect(row.getByRole('button', { name: '去支付' })).toHaveCount(0)
      await expect(row.getByRole('button', { name: '收据' })).toHaveCount(0)
      await expect(page.locator('.qr-frame')).toHaveCount(0)
    } else {
      if (outcome === 'callback') {
        await expect(page.locator('.qr-frame')).toBeVisible()
        status = 'PAID'
      }
      await expect(page.locator('.qr-frame')).toHaveCount(0, { timeout: 10000 })
      await expect(row.getByRole('button', { name: '去支付' })).toHaveCount(0)
      await row.getByRole('button', { name: '收据' }).click()
      await expect.poll(() => receiptReads).toBe(1)
    }
    expect(creates).toBe(0)
    expect(starts).toBe(1)
  })
}

test('confirmed wallet recharge closes the QR dialog and refreshes the credited balance', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'wallet-test-token')
    localStorage.setItem('user_info', JSON.stringify({ username: 'wallet-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  let paid = false
  let creditedWalletReads = 0
  let paidOrderReads = 0
  await page.route('**/api/**', route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/user/wallet')) {
      if (paid) creditedWalletReads++
      return route.fulfill({ json: { balance: paid ? 10000 : 0, plans: [], transactionTotal: paid ? 1 : 0,
        transactions: paid ? [{ id: 1, type: 'RECHARGE', amount: 10000, balanceAfter: 10000 }] : [] } })
    }
    if (path.endsWith('/public/legal')) return route.fulfill({ json: { checkout_ready: true, checkout_disclosure_id: 'wallet-test' } })
    if (path.endsWith('/user/recharge-orders')) {
      if (request.method() === 'POST') return route.fulfill({ json: { paymentIntent: { id: 42 } } })
      if (paid) paidOrderReads++
      return route.fulfill({ json: { items: [], total: 0, page: 1, size: 10 } })
    }
    if (path.endsWith('/payment-intents/42/start')) return route.fulfill({ json: {
      intent: { status: 'PENDING', settlementMoney: { amount: 100, currency: 'CNY', scale: 100 } },
      action: { type: 'QRCODE', url: 'https://example.invalid/pay' }, expiresAt: new Date(Date.now() + 600000).toISOString()
    } })
    if (path.endsWith('/payment-intents/42')) return route.fulfill({ json: { status: paid ? 'PAID' : 'PENDING' } })
    return route.fulfill({ json: {} })
  })
  await page.goto('/console/wallet')
  await page.getByRole('button', { name: '自定义金额', exact: true }).click()
  const recharge = page.getByRole('dialog', { name: '充值方案' })
  await recharge.getByRole('button', { name: /自定义充值/ }).click()
  await recharge.getByText('我已阅读本次交易条件、退款与取消政策').click()
  await recharge.getByRole('button', { name: '立即支付' }).click()
  const payment = page.getByRole('dialog').filter({ has: page.locator('.qr-frame') })
  await expect(payment).toBeVisible()
  // A pending poll keeps checkout usable until the server confirms the callback.
  await page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/payment-intents/42'))
  await expect(payment).toBeVisible()
  paid = true
  await expect(payment).toBeHidden({ timeout: 10000 })
  await expect(page.getByText('支付已确认，充值额度已到账', { exact: true })).toBeVisible()
  await expect.poll(() => creditedWalletReads).toBeGreaterThan(0)
  await expect.poll(() => paidOrderReads).toBeGreaterThan(0)
})

test('wallet recharge keeps payment visible on a narrow screen and submits the chosen amount', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 667 })
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'wallet-test-token')
    localStorage.setItem('user_info', JSON.stringify({ username: 'wallet-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  const money = { amount: 5000, currency: 'CNY', scale: 100 }
  const plans = [{ id: 1, name: 'starter', bonus: 0, paymentMoney: money, totalCreditMoney: money }]
  const orders: unknown[] = []
  const disclosures: string[] = []
  await page.route('**/api/**', route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.pathname.endsWith('/user/wallet')) {
      return route.fulfill({ json: url.searchParams.get('listPage') === 'true'
        ? { items: plans, total: plans.length, page: 1, size: 10 }
        : { plans, invoiceEnabled: false } })
    }
    if (url.pathname.endsWith('/public/legal')) {
      return route.fulfill({ json: { checkout_ready: true, publication_ready: false, checkout_disclosure_id: 'wallet-test' } })
    }
    if (url.pathname.endsWith('/user/recharge-orders') && request.method() === 'POST') {
      orders.push(request.postDataJSON())
      return route.fulfill({ json: { paymentIntent: { id: 42 } } })
    }
    if (url.pathname.endsWith('/payment-intents/42/start')) {
      disclosures.push(request.headers()['x-checkout-disclosure'])
      return route.fulfill({ json: { intent: { status: 'PAID' } } })
    }
    return route.fulfill({ json: {} })
  })

  await page.goto('/console/wallet')
  await page.getByRole('button', { name: '购买', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '充值方案' })
  const pay = dialog.getByRole('button', { name: '立即支付' })
  await expect(dialog.getByRole('button', { name: /starter/ })).toHaveAttribute('aria-pressed', 'true')
  await expect(dialog.getByText('到账', { exact: true })).toBeVisible()
  await expect(pay).toBeInViewport({ ratio: 1 })
  await expect(pay).toBeDisabled()
  const dialogBounds = (await page.locator('.recharge-dialog').boundingBox())!
  const switchBounds = (await page.locator('.locale-switch').boundingBox())!
  expect(dialogBounds.y + dialogBounds.height).toBeLessThan(switchBounds.y)
  await page.screenshot({ path: test.info().outputPath('wallet-mobile.png') })

  await dialog.getByRole('button', { name: /自定义充值/ }).click()
  const amount = dialog.getByRole('spinbutton')
  await amount.fill('')
  await amount.press('Tab')
  await dialog.getByText('我已阅读本次交易条件、退款与取消政策').click()
  await expect(dialog.getByRole('checkbox')).toBeChecked()
  await expect(pay).toBeDisabled()
  await amount.fill('12.50')
  await amount.press('Tab')
  await dialog.getByText('微信支付', { exact: true }).click()
  await expect(dialog.getByRole('radio', { name: '微信支付' })).toBeChecked()
  await expect(pay).toBeEnabled()
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.screenshot({ path: test.info().outputPath('wallet-desktop.png') })
  await pay.click()
  await expect(dialog).toBeHidden()
  expect(orders).toHaveLength(1)
  expect(orders[0]).toMatchObject({ customAmount: 12.5, currency: 'CNY', paymentMethod: 'wxpay' })
  expect(disclosures).toEqual(['wallet-test'])
})

test('dragging the language switch moves it without toggling and keeps it on screen', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 700 })
  await page.route('**/api/**', route => route.fulfill({ json: {} }))
  await page.goto('/')
  const button = page.locator('.locale-switch')
  await expect(button).toBeVisible()
  const start = (await button.boundingBox())!
  const x = start.x + start.width / 2
  const y = start.y + start.height / 2
  await page.mouse.move(x, y)
  await page.mouse.down()
  await page.mouse.move(x - 220, y - 150, { steps: 8 })
  await page.mouse.up()
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN')
  const moved = (await button.boundingBox())!
  expect(moved.x).toBeCloseTo(start.x - 220, 0)
  expect(moved.y).toBeCloseTo(start.y - 150, 0)

  await page.reload()
  await expect(button).toHaveCSS('left', `${moved.x}px`)
  await page.setViewportSize({ width: 320, height: 480 })
  await expect(button).toBeInViewport({ ratio: 1 })
  await button.click()
  await expect(page.locator('html')).toHaveAttribute('lang', 'en-US')
  await button.press('Enter')
  await expect(page.locator('html')).toHaveAttribute('lang', 'zh-CN')
})
