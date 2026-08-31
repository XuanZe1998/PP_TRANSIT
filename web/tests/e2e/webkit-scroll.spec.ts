import { expect, test } from '@playwright/test'

async function openMockedUserConsole(page: import('@playwright/test').Page, path: string) {
  await page.addInitScript(() => {
    localStorage.setItem('user_access_token', 'e2e-user-token')
    localStorage.setItem('user_info', JSON.stringify({ username: 'e2e-user', role: 'USER', accountType: 'PERSONAL' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/user/dashboard')) {
      return route.fulfill({ json: { stats: {}, recentLogs: [], tokens: [{ id: 1, name: 'E2E Key', enabled: true, allowAllModels: true }], modelCatalog: [{ publicName: 'e2e-model', available: true }] } })
    }
    if (url.pathname.endsWith('/user/billing/summary')) {
      return route.fulfill({ json: [{ model: 'e2e-model', request_count: 1, prompt_tokens: 100, completion_tokens: 20, cached_tokens: 0 }] })
    }
    if (url.pathname.endsWith('/user/billing/logs/page')) {
      return route.fulfill({ json: { total: 1, page: 1, size: 20, items: [{ created_at: '2026-08-31 12:00:00', trace_id: 'trace-e2e', token_name: 'E2E Key', model: 'e2e-model', prompt_tokens: 100, completion_tokens: 20, cached_tokens: 0, total_amount: 10, status: 'SUCCESS' }] } })
    }
    if (url.pathname.endsWith('/user/usage/analytics')) return route.fulfill({ json: { daily: [], totals: {}, tokenComposition: [] } })
    if (url.pathname.endsWith('/platform/user/recharge-orders')) return route.fulfill({ json: [] })
    return route.fulfill({ json: {} })
  })
  await page.goto(path)
  await page.waitForLoadState('domcontentloaded')
}

for (const viewport of [{ width: 1280, height: 800 }, { width: 1440, height: 900 }]) {
  test.describe(`MacBook ${viewport.width}x${viewport.height}`, () => {
    test.use({ viewport })
    for (const path of ['/', '/market', '/studio', '/services', '/pricing', '/docs']) {
      test(`${path} keeps document scrolling available`, async ({ page }) => {
        await page.goto(path)
        await page.waitForLoadState('domcontentloaded')
        const state = await page.evaluate(() => {
          const root = document.scrollingElement!
          const maximum = Math.max(0, root.scrollHeight - root.clientHeight)
          root.scrollTop = maximum
          return { maximum, scrollTop: root.scrollTop, bodyOverflow: getComputedStyle(document.body).overflowY }
        })
        expect(state.bodyOverflow).not.toBe('hidden')
        if (state.maximum > 0) expect(state.scrollTop).toBeGreaterThan(0)
      })
    }
  })
}

test('login dialog is geometrically centered', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/?auth=login')
  const dialog = page.locator('.auth-dialog')
  await expect(dialog).toBeVisible()
  await expect.poll(async () => {
    const box = await dialog.boundingBox()
    if (!box) return 999
    return Math.max(Math.abs((box.x + box.width / 2) - 640), Math.abs((box.y + box.height / 2) - 400))
  }, { message: 'dialog should settle at the viewport center after its opening animation' }).toBeLessThan(4)
})

test('opening login keeps the page horizontal position stable', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  await page.goto('/')
  const anchor = page.locator('.site-brand').first()
  await expect(anchor).toBeVisible()
  const before = await anchor.boundingBox()
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page.locator('.auth-dialog')).toBeVisible()
  const after = await anchor.boundingBox()
  expect(before).not.toBeNull()
  expect(after).not.toBeNull()
  expect(Math.abs(after!.x - before!.x)).toBeLessThanOrEqual(1)
})

test('short registration dialog keeps its form scrollable', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 520 })
  await page.goto('/?auth=register')
  const body = page.locator('.auth-dialog .el-dialog__body')
  await expect(body).toBeVisible()
  const scroll = await body.evaluate(element => ({ scrollHeight: element.scrollHeight, clientHeight: element.clientHeight, overflowY: getComputedStyle(element).overflowY }))
  expect(scroll.overflowY).toBe('auto')
  expect(scroll.scrollHeight).toBeGreaterThanOrEqual(scroll.clientHeight)
  await body.evaluate(element => { element.scrollTop = element.scrollHeight })
  await expect(page.getByRole('button', { name: '注册并进入控制台' })).toBeVisible()
})

test('online playground fits the 1366x768 viewport and keeps all three sections visible', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await openMockedUserConsole(page, '/console/playground')
  const sections = page.locator('.playground-layout > .console-panel')
  await expect(sections).toHaveCount(3)
  const state = await page.evaluate(() => ({ clientHeight: document.documentElement.clientHeight, scrollHeight: document.documentElement.scrollHeight }))
  expect(state.scrollHeight).toBeLessThanOrEqual(state.clientHeight + 1)
})

test('usage log tables expose a working horizontal drag area', async ({ page }) => {
  await page.setViewportSize({ width: 1366, height: 768 })
  await openMockedUserConsole(page, '/console/logs')
  const scroll = page.locator('.usage-table-scroll').first()
  await expect(scroll).toBeVisible()
  const moved = await scroll.evaluate(element => {
    const before = element.scrollLeft
    element.scrollLeft = 120
    return { before, after: element.scrollLeft, scrollWidth: element.scrollWidth, clientWidth: element.clientWidth }
  })
  expect(moved.scrollWidth).toBeGreaterThan(moved.clientWidth)
  expect(moved.after).toBeGreaterThan(moved.before)
})
