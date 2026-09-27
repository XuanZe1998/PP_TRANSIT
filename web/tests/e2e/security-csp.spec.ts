import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

// Exercise the exact proposed Nginx document policy against the built frontend.
const nginx = readFileSync(new URL('../../../deploy/nginx/linknux.conf', import.meta.url), 'utf8')
const policy = nginx.match(/add_header Content-Security-Policy "([^"]+)" always;/)?.[1]
if (!policy) throw new Error('Frontend CSP missing from deploy/nginx/linknux.conf')

test('proposed frontend CSP permits representative pages to load', async ({ page }) => {
  await page.addInitScript(() => {
    ;(window as Window & { __cspViolations?: string[] }).__cspViolations = []
    document.addEventListener('securitypolicyviolation', event => {
      ;(window as Window & { __cspViolations?: string[] }).__cspViolations?.push(
        `${event.effectiveDirective}: ${event.blockedURI}`,
      )
    })
    localStorage.setItem('user_access_token', 'csp-test-user')
    localStorage.setItem('user_info', JSON.stringify({ username: 'csp-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
    localStorage.setItem('admin_access_token', 'csp-test-admin')
    localStorage.setItem('admin_info', JSON.stringify({ username: 'csp-test', role: 'ADMIN' }))
    localStorage.setItem('admin_last_active_at', String(Date.now()))
  })
  await page.route('http://127.0.0.1:4173/**', async route => {
    if (route.request().resourceType() !== 'document') return route.continue()
    const response = await route.fetch()
    await route.fulfill({ response, headers: { ...response.headers(), 'content-security-policy': policy! } })
  })
  // Register the API stub last: Playwright runs the most recently registered route first.
  await page.route('**/api/**', route => route.fulfill({ json: {} }))

  for (const path of ['/', '/market', '/admin/login', '/console/profile', '/admin']) {
    await page.goto(path)
    await expect(page.locator('#app')).not.toBeEmpty()
    await page.waitForTimeout(250)
    const violations = await page.evaluate(() =>
      (window as Window & { __cspViolations?: string[] }).__cspViolations ?? [],
    )
    expect(violations, `CSP violation on ${path}`).toEqual([])
  }
})
