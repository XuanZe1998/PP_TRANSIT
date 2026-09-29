import { expect, test } from '@playwright/test'

const contactPage = {
  total: 1, page: 1, size: 10,
  items: [{ id: 1, channel: 'QQ', number: '123456789' }],
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/**', route => {
    if (route.request().url().includes('/public/contact-methods')) {
      return route.fulfill({ json: contactPage })
    }
    return route.fulfill({ json: {} })
  })
})

test('contact entry is collapsed across public, user and admin routes and can be moved', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('user_access_token', 'test-user')
    localStorage.setItem('user_info', JSON.stringify({ username: 'test', role: 'USER' }))
    localStorage.setItem('admin_access_token', 'test-admin')
    localStorage.setItem('admin_info', JSON.stringify({ username: 'test', role: 'ADMIN' }))
  })
  await page.goto('/')
  const widget = page.locator('.contact-widget')
  const trigger = widget.locator('.contact-trigger')
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  await expect(widget.locator('.contact-card')).toBeHidden()

  const before = await trigger.boundingBox()
  expect(before).not.toBeNull()
  await page.mouse.move(before!.x + before!.width / 2, before!.y + before!.height / 2)
  await page.mouse.down()
  await page.mouse.move(before!.x + before!.width / 2 - 110, before!.y + before!.height / 2 - 95, { steps: 8 })
  await page.mouse.up()
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  const after = await trigger.boundingBox()
  expect(after!.x).toBeLessThan(before!.x - 50)
  expect(after!.y).toBeLessThan(before!.y - 50)

  await trigger.click()
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
  await expect(widget.locator('.contact-list button')).toHaveCount(1)
  await expect(widget.locator('.list-pagination')).toBeVisible()
  await page.goto('/admin/login')
  await expect(page.locator('.contact-trigger')).toHaveAttribute('aria-expanded', 'false')
  const restored = await page.locator('.contact-trigger').boundingBox()
  expect(restored!.x).toBeCloseTo(after!.x, 0)
  expect(restored!.y).toBeCloseTo(after!.y, 0)
  for (const path of ['/market', '/console/profile', '/admin']) {
    await page.goto(path)
    await expect(page.locator('.contact-trigger')).toHaveAttribute('aria-expanded', 'false')
  }
})

test('home feature cards do not clip their labels on narrow screens', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')
  const cards = page.locator('.hero-feature-grid article')
  await expect(cards).toHaveCount(4)
  const clipped = await cards.evaluateAll(nodes => nodes.some(card => {
    const text = card.querySelector('p')!
    return text.scrollWidth > text.clientWidth + 1 || card.scrollWidth > card.clientWidth + 1
  }))
  expect(clipped).toBe(false)
})
