import { expect, test, type Page } from '@playwright/test'

const HAN = /[\u3400-\u9fff\uf900-\ufaff]/u

async function prepareEnglishSession(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'en-US')
    localStorage.setItem('user_access_token', 'english-user-token')
    localStorage.setItem('user_info', JSON.stringify({ username: 'english-user', role: 'USER', accountType: 'PERSONAL' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
    localStorage.setItem('admin_access_token', 'english-admin-token')
    localStorage.setItem('admin_info', JSON.stringify({ username: 'english-admin', role: 'ADMIN' }))
    localStorage.setItem('admin_last_active_at', String(Date.now()))
  })
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/public/site-config')) return route.fulfill({ json: {} })
    if (path.endsWith('/public/models/summary')) return route.fulfill({ json: { total: 207, publisherCount: 12 } })
    if (path.endsWith('/user/profile')) return route.fulfill({ json: { agreementRequired: false } })
    if (path.endsWith('/user/dashboard')) {
      return route.fulfill({ json: { stats: { balance: 1288000, tokenCount: 1, totalTokens: 326000 }, tokens: [], recentLogs: [], models: [], modelCatalog: [] } })
    }
    if (path.endsWith('/admin/api/dashboard')) {
      return route.fulfill({ json: { metrics: { totalUsers: 128, activeUsers: 64, totalTokens: 920000 }, channelHealth: [], riskQueue: [], finance: {} } })
    }
    if (path.endsWith('/account-verification-status')) return route.fulfill({ json: { registrationReady: true, emailConfigured: true } })
    if (path.endsWith('/usage/analytics')) return route.fulfill({ json: { daily: [], dailyByModel: [], totals: {}, tokenComposition: [] } })
    return route.fulfill({ json: {} })
  })
}

async function visibleChinese(page: Page) {
  return page.locator('body').evaluate((body, hanSource) => {
    const han = new RegExp(hanSource, 'u')
    const values: string[] = []
    const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT)
    let node: Node | null
    while ((node = walker.nextNode())) {
      const element = node.parentElement
      if (!element || getComputedStyle(element).display === 'none' || getComputedStyle(element).visibility === 'hidden') continue
      const value = node.textContent?.trim() ?? ''
      if (han.test(value)) values.push(value)
    }
    for (const element of body.querySelectorAll('[placeholder],[title],[aria-label],[alt]')) {
      for (const name of ['placeholder', 'title', 'aria-label', 'alt']) {
        const value = element.getAttribute(name)
        if (value && han.test(value)) values.push(`${name}=${value}`)
      }
    }
    return [...new Set(values)]
  }, HAN.source)
}

test('English mode contains no visible Chinese across public and application shells', async ({ page }) => {
  test.setTimeout(60_000)
  await prepareEnglishSession(page)
  const routes = [
    '/', '/market', '/services', '/pricing', '/docs', '/studio', '/terms', '/privacy', '/admin/login',
    '/console', '/console/keys', '/console/playground', '/console/logs', '/console/wallet', '/console/profile',
    '/console/docs', '/console/model-probe', '/console/organization', '/console/agent',
    '/admin', '/admin/users', '/admin/model-gateway', '/admin/creative-config', '/admin/tokens',
    '/admin/audit-logs', '/admin/finance', '/admin/agents', '/admin/model-probe', '/admin/other-services',
    '/admin/vmcard-test', '/admin/security', '/admin/settings',
  ]
  for (const route of routes) {
    await page.goto(route)
    await page.waitForTimeout(250)
    expect(await visibleChinese(page), `Residual Chinese on ${route}`).toEqual([])
  }
})
