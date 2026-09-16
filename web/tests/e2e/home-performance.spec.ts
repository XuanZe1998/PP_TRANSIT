import { expect, test } from '@playwright/test'

test('home loads summary without chart renderer and keeps login available', async ({ page }) => {
  const scripts: string[] = []
  page.on('request', request => {
    if (request.resourceType() === 'script') scripts.push(request.url())
  })
  await page.route('**/public/models/summary', route => route.fulfill({
    json: { total: 207, publisherCount: 2 }
  }))
  await page.route('**/public/site-config', route => route.fulfill({ json: {} }))
  await page.goto('/')
  await expect(page.locator('.routing-stats')).toContainText('207')
  await expect(page.locator('.routing-stats')).toContainText('2')
  expect(scripts.some(url => url.includes('installCanvasRenderer'))).toBe(false)
  await expect(page.locator('.particle-canvas')).toHaveCount(0)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await expect(page).toHaveURL(/auth=login/)
  await expect(page.locator('.el-dialog')).toBeVisible()
})

test('mobile summary failure leaves page usable and does not claim zero models', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.route('**/public/models/summary', route => route.fulfill({ status: 503, body: '' }))
  await page.route('**/public/site-config', route => route.fulfill({ json: {} }))
  await page.goto('/')
  await expect(page.locator('h1')).toBeVisible()
  await expect(page.locator('.routing-stats strong').first()).toHaveText('—')
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

test('language switch presents the approved English campaign and persists the choice', async ({ page }) => {
  await page.route('**/public/models/summary', route => route.fulfill({ json: { total: 207, publisherCount: 8 } }))
  await page.route('**/public/site-config', route => route.fulfill({ json: { usdCnyRate: 7 } }))
  await page.goto('/')
  await page.getByRole('button', { name: 'Switch to English' }).click()
  await expect(page.getByRole('heading', { name: 'One API. Every AI Model.' })).toBeVisible()
  await expect(page.locator('.hero-copy')).toContainText('One API key. One endpoint. One bill.')
  await expect(page.getByRole('button', { name: 'Log in', exact: true })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'One API. Every AI Model.' })).toBeVisible()
})
