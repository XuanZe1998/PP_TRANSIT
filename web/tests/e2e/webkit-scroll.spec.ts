import { expect, test } from '@playwright/test'

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
