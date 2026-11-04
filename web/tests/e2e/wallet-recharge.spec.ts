import { expect, test } from '@playwright/test'

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
