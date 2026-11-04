import { expect, test, type Page } from '@playwright/test'

async function prepare(page: Page, admin: boolean) {
  await page.addInitScript(admin => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'referral-user-test')
    localStorage.setItem('user_info', JSON.stringify({ username: 'referral', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
    if (admin) {
      sessionStorage.setItem('admin_access_token', 'referral-admin-test')
      sessionStorage.setItem('admin_info', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
    }
  }, admin)
  const calls: { path: string; method: string; body: any; page: string | null }[] = []
  const settings = { enabled: true, freeze_days: 7, minimum_cents: 2000, service_notice: '两个工作日内审核', version: 1 }
  const summary = { ...settings, verified: true, profile: { invite_code: 'RF-TEST', status: 'ACTIVE' }, invited: 4, earnedCents: 2000, availableCents: 2000, debtCents: 0, paidCents: 0 }
  let withdrawalStatus = 'APPROVED'
  await page.route(url => url.pathname.startsWith('/api/') || url.origin === 'https://api.linknux.com', async route => {
    const request = route.request(), url = new URL(request.url())
    const path = url.pathname.startsWith('/api/') ? url.pathname.slice(4) : url.pathname
    let body: any = null
    try { body = request.postDataJSON() } catch { /* Reads have no body. */ }
    calls.push({ path, method: request.method(), body, page: url.searchParams.get('page') })
    if (path.endsWith('/referrals')) return route.fulfill({ json: admin ? { settings, overview: summary } : summary })
    if (path.endsWith('/account')) return route.fulfill({ json: { channel: 'ALIPAY', masked_account: 't***@example.com', accountToken: 'ACCOUNT-TOKEN' } })
    if (path.endsWith('/lists/products')) return route.fulfill({ json: { page: 1, size: 10, total: 1, items: [{ id: 1, name: 'Plus 订阅', enabled: true, currency: 'CNY', price_cents: 3000, mode: 'FIXED', fixed_cents: 500, cost_cents: 1000, rate_bps: 0, version: 1 }] } })
    if (path.endsWith('/lists/withdrawals')) return route.fulfill({ json: { page: 1, size: 10, total: 1, items: [{ id: 9, user_id: 1, email: 'ref@example.com', amount_cents: 2000, channel: 'ALIPAY', masked_account: 't***@example.com', status: withdrawalStatus, account_users: 1 }] } })
    if (path.endsWith('/lists/invitations')) return route.fulfill({ json: { page: 1, size: 10, total: 11, items: [{ id: 1, customer_email: 'b***@example.com', purchases: 4, binding_source: 'REGISTER', bound_at: '2026-11-04 00:00:00' }] } })
    if (path.endsWith('/claim')) { withdrawalStatus = 'PAYING'; return route.fulfill({ json: {} }) }
    if (path.endsWith('/reveal')) return route.fulfill({ json: { channel: 'ALIPAY', account: 'test@example.com', name: '测试收款人' } })
    if (path.endsWith('/paid')) { withdrawalStatus = 'PAID'; return route.fulfill({ json: {} }) }
    if (path.endsWith('/preview')) return route.fulfill({ json: { token: 'PREVIEW', commissionCents: 500, grossCents: 2000 } })
    return route.fulfill({ json: { items: [], total: 0, page: 1, size: 10 } })
  })
  await page.goto(admin ? '/admin/referrals' : '/console/referrals')
  await expect(page.getByRole('heading', { name: admin ? '邀请返佣管理' : '邀请有礼', exact: true })).toBeVisible()
  return calls
}

test('user confirms destination and amount before submitting an idempotent withdrawal', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  const calls = await prepare(page, false)
  await expect(page.getByText('每份最高返 ¥5.00')).toBeVisible()
  await page.getByRole('button', { name: '确认并申请提现' }).click()
  const dialog = page.getByRole('dialog', { name: '确认提现' })
  await expect(dialog).toContainText('¥20.00')
  await expect(dialog).toContainText('t***@example.com')
  await dialog.getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => calls.some(call => call.path === '/user/referrals/withdrawals' && call.method === 'POST')).toBeTruthy()
  const request = calls.find(call => call.path === '/user/referrals/withdrawals' && call.method === 'POST')!
  expect(request.body.amountCents).toBe(2000)
  expect(request.body.requestKey).toMatch(/^[a-f0-9-]{36}$/)
  await page.getByRole('tab', { name: '邀请记录', exact: true }).click()
  await expect(page.getByText('b***@example.com')).toBeVisible()
  await page.locator('.list-pagination').getByText('2', { exact: true }).click()
  await expect.poll(() => calls.some(call => call.path.endsWith('/lists/invitations') && call.page === '2')).toBeTruthy()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBeTruthy()
  const box = await page.locator('.referral-console').boundingBox()
  expect(box!.x).toBeGreaterThanOrEqual(0)
  expect(box!.width).toBeLessThanOrEqual(390)
  expect((await page.locator('.referral-metrics').boundingBox())!.width).toBeLessThanOrEqual(390)
  await page.evaluate(() => window.scrollTo(0, 0))
  await page.screenshot({ path: 'test-results/referrals-user-mobile.png', scale: 'css' })
})

test('administrator previews a rule and claims payout before revealing private details', async ({ page }) => {
  const calls = await prepare(page, true)
  await page.getByRole('button', { name: '配置规则' }).click()
  const rule = page.getByRole('dialog', { name: '商品返佣规则', exact: true })
  await expect(rule.getByRole('button', { name: '应用已预览规则' })).toBeDisabled()
  await rule.getByRole('button', { name: '预览计算' }).click()
  await expect(rule).toContainText('本次模拟返佣 ¥5.00')
  await rule.getByRole('button', { name: '应用已预览规则' }).click()
  await page.getByRole('tab', { name: '提现记录', exact: true }).click()
  expect(calls.some(call => call.path.endsWith('/reveal'))).toBeFalsy()
  await page.getByRole('button', { name: '领取打款', exact: true }).click()
  await page.getByRole('dialog', { name: '领取打款', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await page.getByRole('button', { name: '查看并登记' }).click()
  const payout = page.getByRole('dialog', { name: '人工打款', exact: true })
  await expect(payout).toContainText('测试收款人')
  await payout.getByRole('textbox').fill('ALIPAY-TEST-9')
  await payout.getByRole('button', { name: '登记已打款' }).click()
  await page.getByRole('dialog', { name: '登记已打款', exact: true }).getByRole('button', { name: '确定', exact: true }).click()
  await expect.poll(() => calls.some(call => call.path.endsWith('/paid') && call.body.reference === 'ALIPAY-TEST-9')).toBeTruthy()
  await page.screenshot({ path: 'test-results/referrals-admin.png', fullPage: true })
})
