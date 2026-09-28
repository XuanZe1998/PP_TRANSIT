import { test, expect, type Page } from '@playwright/test'

async function mockInventory(page: Page) {
  await page.addInitScript(() => {
    localStorage.setItem('admin_access_token', 'browser-test-only')
    localStorage.setItem('admin_info', JSON.stringify({ username: 'test', role: 'ADMIN' }))
  })
  const services = Array.from({ length: 12 }, (_, index) => ({ id: index + 1, name: `卡密服务 ${index + 1}`, productType: 'CARD_KEY',
    fulfillmentMode: 'AUTOMATIC_DELIVERY', supplierType: index === 11 ? 'DUJIAO_NEXT' : 'LOCAL_INVENTORY',
    enabled: true, purchaseEnabled: true, amountCents: 100, priceCents: 100, currency: 'CNY', maxPurchaseQuantity: 1 }))
  let cards = [
    { id: 101, secretPreview: '****1001', status: 'AVAILABLE', createdAt: '2026-09-28 10:00:00' },
    { id: 102, secretPreview: '****1002', status: 'RESERVED', reservedOrderId: 800, orderNo: 'ORDER-800', buyerUserId: 22, reservedUntil: '2026-09-28 10:15:00' },
    { id: 103, secretPreview: '****1003', status: 'DELIVERED', reservedOrderId: 900, orderNo: 'ORDER-900', buyerUserId: 23, deliveredAt: '2026-09-28 10:05:00' }
  ]
  const calls: { path: string; method: string; params: URLSearchParams; body: any }[] = []
  await page.route(url => url.hostname === 'api.linknux.com' || url.pathname.startsWith('/api/'), async route => {
    const request = route.request(), url = new URL(request.url()), method = request.method()
    // Normalize production API origins and the development /api proxy for identical mock assertions.
    if (!url.pathname.startsWith('/api/')) url.pathname = '/api' + url.pathname
    let body: any = null; try { body = request.postDataJSON() } catch { /* no body */ }
    calls.push({ path: url.pathname, method, params: url.searchParams, body })
    if (url.pathname === '/api/admin/api/other-services') {
      const page = Number(url.searchParams.get('page') || 1), size = Number(url.searchParams.get('size') || 10)
      return route.fulfill({ json: { items: services.slice((page - 1) * size, page * size), total: 12, page, size } })
    }
    const detail = url.pathname.match(/\/admin\/api\/other-services\/(\d+)$/)
    if (detail) return route.fulfill({ json: services.find(service => service.id === Number(detail[1])) })
    if (url.pathname.endsWith('/inventory/stats')) return route.fulfill({ json: { AVAILABLE: cards.filter(c => c.status === 'AVAILABLE').length, RESERVED: 1, DELIVERED: 1 } })
    if (url.pathname.endsWith('/inventory')) {
      const status = url.searchParams.get('status'), q = url.searchParams.get('q')
      const items = cards.filter(c => (!status || c.status === status) && (!q || c.secretPreview.includes(q) || c.orderNo?.includes(q) || String(c.id) === q))
      return route.fulfill({ json: { items, total: items.length, page: 1, size: 10 } })
    }
    if (url.pathname.endsWith('/inventory/import')) return route.fulfill({ json: { received: 3, unique: 2, imported: 1, duplicateInInput: 1, duplicateInStock: 1 } })
    if (url.pathname.endsWith('/reveal')) return route.fulfill({ json: { content: 'FAKE-TEST-CARD-CONTENT' }, headers: { 'Cache-Control': 'no-store' } })
    if (method === 'DELETE' && url.pathname.includes('/inventory/')) {
      const id = Number(url.pathname.split('/').at(-1)); cards = cards.filter(c => c.id !== id)
      return route.fulfill({ status: 204 })
    }
    if (url.pathname.endsWith('/service-orders/admin/orders/900')) return route.fulfill({ json: { id: 900, orderNo: 'ORDER-900', status: 'FULFILLED', fulfillmentMode: 'AUTOMATIC_DELIVERY', productName: '卡密服务 11', amountCents: 100, currency: 'CNY' } })
    if (url.pathname.endsWith('/service-orders/admin/orders')) return route.fulfill({ json: { items: [], total: 0, page: 1, size: 10 } })
    return route.fulfill({ json: { items: [], total: 0, page: 1, size: 10 } })
  })
  await page.goto('/admin/other-services')
  return calls
}

test('catalog card management binds services on later pages and protects reserved and sold cards', async ({ page }) => {
  const calls = await mockInventory(page)
  const catalog = page.getByRole('tabpanel', { name: '服务目录' })
  await expect(catalog.getByText('卡密服务 1', { exact: true })).toBeVisible()
  await catalog.locator('.list-pagination .el-pagination').getByText('2', { exact: true }).click()
  await catalog.locator('tr').filter({ hasText: '卡密服务 11' }).getByRole('button', { name: '卡密管理' }).click()
  const dialog = page.getByRole('dialog', { name: '卡密管理', exact: true })
  await expect(dialog.getByRole('heading', { name: '卡密服务 11 · 卡密管理' })).toBeVisible()
  await expect.poll(() => calls.some(call => call.path === '/api/admin/api/other-services/11/inventory')).toBeTruthy()
  await expect(dialog.locator('tr').filter({ hasText: '****1001' }).getByRole('button', { name: '删除', exact: true })).toBeVisible()
  await expect(dialog.locator('tr').filter({ hasText: '****1002' }).getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
  await expect(dialog.locator('tr').filter({ hasText: '****1003' }).getByRole('button', { name: '替换', exact: true })).toHaveCount(0)
  await dialog.getByRole('button', { name: '已售／已发货 1', exact: true }).click()
  await expect(dialog.locator('tr').filter({ hasText: '****1001' })).toHaveCount(0)
  await expect(dialog.locator('tr').filter({ hasText: '****1003' })).toBeVisible()
  await expect(dialog.locator('tr').filter({ hasText: '****1003' }).locator('.el-tag')).toHaveText('已售／已发货')
  const dialogBounds = await dialog.boundingBox()
  const tableBounds = await dialog.locator('.paged-table').boundingBox()
  expect(tableBounds!.x + tableBounds!.width).toBeLessThanOrEqual(dialogBounds!.x + dialogBounds!.width)
  await expect(dialog.locator('tr').filter({ hasText: '****1003' }).getByRole('button', { name: '查看', exact: true })).toBeInViewport()
  await page.screenshot({ path: 'test-results/service-inventory-manager.png', fullPage: true })
  await dialog.getByRole('button', { name: 'ORDER-900', exact: true }).click()
  await expect(page.getByRole('dialog', { name: '处理服务订单' })).toBeVisible()
  await expect.poll(() => calls.some(call => call.path.endsWith('/service-orders/admin/orders/900'))).toBeTruthy()
  await expect(page.getByRole('dialog', { name: '处理服务订单' }).locator('input').first()).toHaveValue('ORDER-900')
  const orderDialog = page.getByRole('dialog', { name: '处理服务订单' })
  await orderDialog.getByRole('button', { name: /Close this dialog/ }).click()
  await expect(orderDialog).not.toBeVisible()
  await expect(page).not.toHaveURL(/orderId=900/)
  await page.getByRole('tab', { name: '服务目录', exact: true }).click()
  await catalog.locator('tr').filter({ hasText: '卡密服务 11' }).getByRole('button', { name: '卡密管理' }).click()
  await page.getByRole('dialog', { name: '卡密管理', exact: true }).getByRole('button', { name: 'ORDER-900', exact: true }).click()
  await expect(orderDialog).toBeVisible()
  expect(calls.filter(call => call.path.endsWith('/service-orders/admin/orders/900'))).toHaveLength(2)
})

test('import feedback, reveal expiry and cancelled deletion behave safely', async ({ page }) => {
  const calls = await mockInventory(page)
  const catalog = page.getByRole('tabpanel', { name: '服务目录' })
  await catalog.locator('tr').filter({ has: page.getByText('卡密服务 1', { exact: true }) }).getByRole('button', { name: '卡密管理' }).click()
  const dialog = page.getByRole('dialog', { name: '卡密管理', exact: true })
  await dialog.getByText('新增卡密／批量导入', { exact: true }).click()
  await dialog.locator('textarea').fill('one，one two')
  await expect(dialog.getByText('识别 3 条，去重后 2 条；每批最多 10000 条')).toBeVisible()
  await dialog.getByRole('button', { name: '导入卡密', exact: true }).click()
  await expect(page.getByText('识别 3 条，成功导入 1 条，输入内重复 1 条，库存重复 1 条')).toBeVisible()
  const row = dialog.locator('tr').filter({ hasText: '****1001' })
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('dialog', { name: '删除卡密确认' }).getByRole('button', { name: /取消|Cancel/ }).click()
  expect(calls.filter(call => call.method === 'DELETE' && call.path.includes('/inventory/'))).toHaveLength(0)
  await page.clock.install()
  await row.getByRole('button', { name: '查看', exact: true }).click()
  await expect(dialog.getByText('FAKE-TEST-CARD-CONTENT', { exact: false })).toBeVisible()
  await page.clock.fastForward(31000)
  await expect(dialog.getByText('FAKE-TEST-CARD-CONTENT', { exact: false })).toHaveCount(0)
  await row.getByRole('button', { name: '查看', exact: true }).click()
  await expect(dialog.getByText('FAKE-TEST-CARD-CONTENT', { exact: false })).toBeVisible()
  await dialog.getByRole('button', { name: /Close this dialog/ }).click()
  await page.clock.fastForward(1000)
  await expect(page.getByText('FAKE-TEST-CARD-CONTENT', { exact: false })).toHaveCount(0)
  await catalog.locator('tr').filter({ has: page.getByText('卡密服务 2', { exact: true }) }).getByRole('button', { name: '卡密管理' }).click()
  await expect(dialog.getByRole('heading', { name: '卡密服务 2 · 卡密管理' })).toBeVisible()
  await expect(dialog.getByText('FAKE-TEST-CARD-CONTENT', { exact: false })).toHaveCount(0)
})

test('upstream service explains its source and opens service-scoped orders without local inventory calls', async ({ page }) => {
  const calls = await mockInventory(page)
  const catalog = page.getByRole('tabpanel', { name: '服务目录' })
  await catalog.locator('.list-pagination .el-pagination').getByText('2', { exact: true }).click()
  await catalog.locator('tr').filter({ hasText: '卡密服务 12' }).getByRole('button', { name: '卡密管理' }).click()
  const dialog = page.getByRole('dialog', { name: '卡密管理', exact: true })
  await expect(dialog.getByText('卡密由上游采购并发货，不使用本站卡密库存。请通过服务订单查看采购和发货结果。')).toBeVisible()
  await expect(dialog.getByRole('button', { name: '导入卡密' })).toHaveCount(0)
  expect(calls.filter(call => call.path.includes('/other-services/12/inventory'))).toHaveLength(0)
  await dialog.getByRole('button', { name: '查看该服务订单' }).click()
  await expect(page.getByText('当前查看服务 ID 12 的订单')).toBeVisible()
  await expect.poll(() => calls.some(call => call.path.endsWith('/service-orders/admin/orders') && call.params.get('serviceId') === '12')).toBeTruthy()
})


test('legacy product configuration reuses inventory management and retains coupons', async ({ page }) => {
  const calls = await mockInventory(page)
  await page.getByRole('tab', { name: '服务订单', exact: true }).click()
  const commerce = page.locator('.commerce-admin')
  await expect(commerce.getByRole('heading', { name: '商品与履约配置' })).toBeVisible()
  await commerce.getByText('选择当前页服务', { exact: true }).click()
  await page.getByRole('option', { name: '卡密服务 1', exact: true }).click()
  await expect(commerce.getByRole('heading', { name: '卡密服务 1 · 卡密管理' })).toBeVisible()
  await expect(commerce.getByRole('button', { name: '保存商品配置', exact: true })).toBeVisible()
  await expect(commerce.getByRole('heading', { name: '优惠码', exact: true })).toBeVisible()
  await expect(commerce.locator('tr').filter({ hasText: '****1001' }).getByRole('button', { name: '替换', exact: true })).toBeVisible()
  await expect.poll(() => calls.some(call => call.path.endsWith('/other-services/1/inventory') && call.params.get('listPage') === 'true')).toBeTruthy()
})
