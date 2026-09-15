import { expect, test, type Page } from '@playwright/test'

async function mockPublicApi(page: Page) {
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/public/models/summary')) {
      return route.fulfill({ json: { total: 207, publisherCount: 12 } })
    }
    if (url.pathname.endsWith('/public/site-config')) return route.fulfill({ json: {} })
    return route.fulfill({ json: {} })
  })
}

async function mockUserConsole(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('user_access_token', 'responsive-user-token')
    localStorage.setItem('user_info', JSON.stringify({ username: 'responsive-user', role: 'USER', accountType: 'PERSONAL' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/user/dashboard')) {
      return route.fulfill({
        json: {
          stats: { balance: 1288000, tokenCount: 1, totalTokens: 326000 },
          tokens: [{ id: 1, name: 'Responsive Key', enabled: true, allowAllModels: true }],
          recentLogs: [],
          models: ['linknux-model'],
          modelCatalog: [{ publicName: 'linknux-model', available: true }]
        }
      })
    }
    if (url.pathname.endsWith('/user/profile')) return route.fulfill({ json: { agreementRequired: false } })
    if (url.pathname.endsWith('/public/legal')) return route.fulfill({ json: {} })
    if (url.pathname.endsWith('/user/usage/analytics')) return route.fulfill({ json: { daily: [], totals: {}, tokenComposition: [] } })
    return route.fulfill({ json: {} })
  })
}

async function mockAdminConsole(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('admin_access_token', 'responsive-admin-token')
    localStorage.setItem('admin_info', JSON.stringify({ username: 'responsive-admin', role: 'ADMIN' }))
    localStorage.setItem('admin_last_active_at', String(Date.now()))
  })
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/account-verification-status')) {
      return route.fulfill({ json: { registrationReady: true, emailConfigured: true } })
    }
    if (url.pathname.endsWith('/admin/api/dashboard')) {
      return route.fulfill({
        json: {
          metrics: { totalUsers: 128, activeUsers: 64, totalTokens: 920000 },
          channelHealth: [],
          riskQueue: [],
          finance: {}
        }
      })
    }
    if (url.pathname.endsWith('/usage/analytics')) return route.fulfill({ json: { daily: [], dailyByModel: [], totals: {} } })
    return route.fulfill({ json: {} })
  })
}

async function expectNoDocumentOverflow(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
}

test.describe('mobile application shells', () => {
  test.use({ viewport: { width: 390, height: 844 } })

  test('public navigation uses an accessible drawer without horizontal overflow', async ({ page }) => {
    await mockPublicApi(page)
    await page.goto('/')
    await expect(page.locator('.site-links')).toBeHidden()
    await page.getByRole('button', { name: '打开导航菜单' }).click()
    const drawer = page.locator('.site-mobile-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('button', { name: '模型广场' })).toBeVisible()
    await expect(drawer.getByRole('button', { name: '开发文档' })).toBeVisible()
    await expectNoDocumentOverflow(page)
  })

  test('user workspace replaces the desktop sidebar with a complete drawer', async ({ page }) => {
    await mockUserConsole(page)
    await page.goto('/console')
    await expect(page.locator('.user-sidebar')).toBeHidden()
    await expect(page.locator('.user-mobile-header')).toBeVisible()
    const menuButton = page.locator('.user-mobile-header button').first()
    await expect(menuButton).toHaveAttribute('aria-label', '打开用户工作台菜单')
    await menuButton.click()
    const drawer = page.locator('.user-mobile-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByRole('button', { name: '在线调试' })).toBeVisible()
    await expect(drawer.getByRole('button', { name: '模型鉴别' })).toBeVisible()
    await expectNoDocumentOverflow(page)
  })

  test('admin workspace removes the narrow sidebar and keeps every section reachable', async ({ page }) => {
    await mockAdminConsole(page)
    await page.goto('/admin')
    await expect(page.locator('.admin-aside')).toBeHidden()
    await page.getByRole('button', { name: '打开管理后台菜单' }).click()
    const drawer = page.locator('.admin-mobile-drawer')
    await expect(drawer).toBeVisible()
    await expect(drawer.getByText('模型网关', { exact: true })).toBeVisible()
    await expect(drawer.getByText('审计与安全', { exact: true })).toBeVisible()
    await expectNoDocumentOverflow(page)
  })
})

test.describe('desktop application shells', () => {
  test.use({ viewport: { width: 1440, height: 900 } })

  test('user workspace keeps its task-oriented sidebar', async ({ page }) => {
    await mockUserConsole(page)
    await page.goto('/console')
    await expect(page.locator('.user-sidebar')).toBeVisible()
    await expect(page.locator('.user-mobile-header')).toBeHidden()
    await expectNoDocumentOverflow(page)
  })

  test('admin workspace keeps its dense navigation rail', async ({ page }) => {
    await mockAdminConsole(page)
    await page.goto('/admin')
    await expect(page.locator('.admin-aside')).toBeVisible()
    await expect(page.locator('.admin-menu-toggle')).toBeHidden()
    await expectNoDocumentOverflow(page)
  })
})

test.describe('tablet application shells', () => {
  test.use({ viewport: { width: 768, height: 1024 } })

  test('public and user navigation remain compact at the tablet breakpoint', async ({ page }) => {
    await mockPublicApi(page)
    await page.goto('/')
    await expect(page.locator('.site-links')).toBeHidden()
    await expect(page.getByRole('button', { name: '打开导航菜单' })).toBeVisible()
    await expectNoDocumentOverflow(page)

    await page.unroute('**/api/**')
    await mockUserConsole(page)
    await page.goto('/console')
    await expect(page.locator('.user-sidebar')).toBeHidden()
    await expect(page.locator('.user-mobile-header')).toBeVisible()
    await expectNoDocumentOverflow(page)
  })
})
