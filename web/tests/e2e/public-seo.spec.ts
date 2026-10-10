import { expect, test } from '@playwright/test'
import { publicPages } from '../../src/seo/pages.mjs'

test('public pages remain readable without JavaScript and have distinct initial metadata', async ({ browser }) => {
  const context = await browser.newContext({ javaScriptEnabled: false })
  const page = await context.newPage()
  try {
    for (const entry of publicPages) {
      const response = await page.goto(test.info().project.use.baseURL + entry.path)
      expect(response?.status()).toBe(200)
      await expect(page.locator('h1')).toHaveText(entry.zh.heading)
      await expect(page).toHaveTitle(entry.zh.title)
      await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href', `https://linknux.com${entry.path}`)
      await expect(page.locator('meta[name="description"]')).toHaveAttribute('content', entry.zh.description)
      await expect(page.getByRole('link', { name: '接入文档' })).toBeVisible()
    }
  } finally {
    await context.close()
  }
})

test('text/XML routes do not fall back, headers are preserved, slash redirect keeps query', async ({ request }) => {
  const robots = await request.get('/robots.txt')
  expect(robots.headers()['content-type']).toContain('text/plain')
  expect(await robots.text()).toContain('User-agent: *')
  expect(await robots.text()).not.toContain('<html')
  expect(robots.headers()['x-content-type-options']).toBe('nosniff')
  const sitemap = await request.get('/sitemap.xml')
  expect(sitemap.headers()['content-type']).toContain('application/xml')
  expect(await sitemap.text()).toContain('<urlset')
  const redirect = await request.get('/docs/?utm_source=test', { maxRedirects: 0 })
  expect(redirect.status()).toBe(301)
  expect(redirect.headers().location).toContain('/docs?utm_source=test')
  const shell = await request.get('/console/keys')
  expect(await shell.text()).not.toContain('rel="canonical"')
})

test('Vue mount, site-config loading, navigation and English keep correct page metadata', async ({ page }) => {
  await page.route('**/api/**', route => route.fulfill({ json: {} }))
  await page.route('**/public/**', route => route.fulfill({ json: {} }))
  await page.goto('/docs')
  await expect(page.locator('.public-page-intro h1')).toHaveText(publicPages[1].zh.heading)
  await expect(page.locator('.seo-public-shell')).toHaveCount(0)
  await expect(page).toHaveTitle(publicPages[1].zh.title)
  await page.getByRole('button', { name: 'Switch to English' }).click()
  await expect(page).toHaveTitle(publicPages[1].en.title)
  await expect(page.locator('meta[name="description"]')).toHaveAttribute('content', publicPages[1].en.description)
  await page.getByRole('button', { name: 'Home', exact: true }).click()
  await expect(page).toHaveTitle(publicPages[0].en.title)
  await page.getByRole('button', { name: 'Docs', exact: true }).click()
  await expect(page).toHaveTitle(publicPages[1].en.title)
  await page.goto('/pricing')
  await expect(page.locator('.public-page-intro h1')).toHaveText(publicPages[2].en.heading)
  await expect(page.locator('link[rel="canonical"]')).toHaveCount(1)
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute('href', 'https://linknux.com/pricing')
  await page.goto('/admin/login')
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute('content', 'noindex,follow')
  await expect(page.locator('link[rel="canonical"]')).toHaveCount(0)
})
