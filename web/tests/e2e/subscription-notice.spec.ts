import { expect, test } from '@playwright/test'

test('administrators edit and hide the announcement; public pages render plain multiline text', async ({ page }) => {
  let notice = { enabled: true, title: '订阅公告 · 购买须知', content: '购买前请核对商品说明' }
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    sessionStorage.setItem('admin_access_token', 'notice-admin-test')
    sessionStorage.setItem('admin_info', JSON.stringify({ id: 1, username: 'admin', role: 'ADMIN' }))
  })
  await page.route(url => url.pathname.startsWith('/api/') || url.origin === 'https://api.linknux.com', async route => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api(?=\/)/, '')
    if (path === '/public/subscription-notice') return route.fulfill({ json: notice })
    if (path === '/admin/api/subscription-notice') { notice = route.request().postDataJSON(); return route.fulfill({ json: notice }) }
    return route.fulfill({ json: { total: 0, page: 1, size: 10, items: [] } })
  })
  await page.goto('/admin/other-services?tab=notice')
  await page.getByRole('textbox', { name: '公告标题', exact: true }).fill('订阅更新公告')
  await page.getByRole('textbox', { name: '公告内容', exact: true }).fill('第一行\n<b>按商品说明兑换</b>')
  await page.getByRole('button', { name: '保存公告' }).click()
  await expect(page.getByText('公告已保存', { exact: true })).toBeVisible()
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/services')
  await expect(page.locator('.subscription-notice')).toContainText('订阅更新公告')
  await expect(page.locator('.subscription-notice p')).toHaveText('第一行\n<b>按商品说明兑换</b>')
  await expect(page.locator('.subscription-notice b')).toHaveCount(0)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  for (const width of [1440, 768, 390, 320]) {
    await page.setViewportSize({ width, height: 900 })
    const heading = await page.locator('.services-heading').boundingBox()
    const banner = await page.locator('.subscription-notice').boundingBox()
    const count = await page.locator('.services-count').boundingBox()
    const pagePadding = await page.locator('.other-services-page').evaluate(el => parseFloat(getComputedStyle(el).paddingTop))
    expect(pagePadding).toBeLessThanOrEqual(28)
    if (width > 640) {
      expect(banner!.x).toBeGreaterThan(heading!.x + heading!.width)
      expect(banner!.x + banner!.width).toBeLessThan(count!.x)
    } else {
      expect(banner!.y).toBeGreaterThanOrEqual(heading!.y + heading!.height)
    }
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    await page.screenshot({ path: `test-results/subscription-compact-${width}.png`, fullPage: true })
  }
  await page.goto('/admin/other-services?tab=notice')
  await expect(page.getByRole('switch', { name: '显示公告', exact: true })).toBeChecked()
  await page.locator('.el-switch').click()
  await page.getByRole('button', { name: '保存公告' }).click()
  await expect(page.getByText('公告已保存', { exact: true })).toBeVisible()
  await page.goto('/services')
  await expect(page.locator('.subscription-notice')).toHaveCount(0)
})
