import { expect, test, type Page } from '@playwright/test'

type Options = { locale?: 'zh-CN' | 'en-US'; count?: number; failDelete?: boolean }
async function mockGateway(page: Page, options: Options = {}) {
  await page.addInitScript(locale => {
    localStorage.setItem('linknux.locale', locale)
    sessionStorage.setItem('admin_access_token', 'test-admin-token')
    sessionStorage.setItem('admin_info', JSON.stringify({ username: 'test-admin', role: 'ADMIN' }))
    sessionStorage.setItem('admin_last_active_at', String(Date.now()))
  }, options.locale ?? 'zh-CN')
  let rows = Array.from({ length: options.count ?? 12 }, (_, i) => ({
    id: i + 1, site_id: 1, site_name: 'Test upstream', group_name: `Group ${i + 1}`,
    source_code: 'new-api', model_count: 2, enabled: i > 1 && i < 10,
    sync_status: i === 1 ? 'RUNNING' : i === 0 || i >= 10 ? 'GROUP_REMOVED' : 'SUCCESS',
    sync_enabled: false, health_status: 'UNTESTED', credential_configured: false, public_name: 'Test upstream'
  }))
  const writes: string[] = [], errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url())
    if (request.method() !== 'GET') {
      writes.push(`${request.method()} ${url.pathname}`)
      if (request.method() === 'DELETE' && /\/gateway\/groups\/\d+$/.test(url.pathname)) {
        if (options.failDelete) return route.fulfill({ status: 409, json: { message: '分组或所属上游正在同步，请等待任务结束后再删除' } })
        const id = Number(url.pathname.split('/').at(-1))
        rows = rows.filter(row => row.id !== id)
        return route.fulfill({ status: 204 })
      }
      return route.fulfill({ status: 400, json: { message: 'Unexpected write' } })
    }
    if (url.pathname.endsWith('/gateway/groups')) {
      const size = Number(url.searchParams.get('size') || 10)
      const filtered = url.searchParams.get('groupId') ? rows.filter(row => row.id === Number(url.searchParams.get('groupId'))) : rows
      const current = Math.min(Number(url.searchParams.get('page') || 1), Math.max(1, Math.ceil(filtered.length / size)))
      return route.fulfill({ json: { total: filtered.length, page: current, size, items: filtered.slice((current - 1) * size, current * size) } })
    }
    if (url.pathname.endsWith('/gateway/sites')) return route.fulfill({ json: { total: 1, page: 1, size: 100, items: [{ id: 1, name: 'Test upstream', adapter: 'new-api' }] } })
    if (url.pathname.endsWith('/gateway/summary')) return route.fulfill({ json: { models: rows.length * 2, published: 0, pending_price: 0, pending_publish: 0 } })
    return route.fulfill({ json: {} })
  })
  await page.goto('/admin/model-gateway')
  await expect(page.locator('.el-table__body tbody tr')).toHaveCount(Math.min(10, options.count ?? 12))
  return { writes, errors }
}

for (const width of [390, 1440]) {
  test(`disabled groups expose a visible delete action at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 950 })
    const { writes, errors } = await mockGateway(page)
    const rows = page.locator('.el-table__body tbody tr')
    await expect(rows.first()).toContainText('分组已停用')
    await expect(rows.first().getByRole('button', { name: '删除', exact: true })).toBeVisible()
    await expect(rows.nth(1).getByRole('button', { name: '删除', exact: true })).toBeDisabled()
    await expect(rows.nth(2).getByRole('button', { name: '删除', exact: true })).toHaveCount(0)
    await expect(page.locator('.el-loading-mask')).toHaveCount(0)
    await expect(rows.first().getByRole('button', { name: '删除', exact: true })).toHaveCSS('color', 'rgb(159, 38, 54)')
    await rows.first().getByRole('button', { name: '删除', exact: true }).scrollIntoViewIfNeeded()
    await page.screenshot({ path: testInfo.outputPath(`gateway-delete-${width}.png`) })
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}

test('cancel does not delete; confirmation removes only the chosen group and refreshes totals', async ({ page }) => {
  const { writes, errors } = await mockGateway(page)
  const first = page.locator('.el-table__body tbody tr').first()
  await first.getByRole('button', { name: '删除', exact: true }).click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toContainText('Group 1')
  await expect(dialog).toContainText('模型映射、定价规则、自动发布请求及本地凭据')
  await expect(dialog).toContainText('所属上游站点和历史运行记录保留')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  expect(writes).toEqual([])
  await first.getByRole('button', { name: '删除', exact: true }).click()
  await dialog.getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.locator('.el-message--success')).toContainText('停用分组已删除')
  await expect(first).toContainText('Group 2')
  await expect(page.locator('.list-pagination')).toContainText('共 11 条')
  expect(writes).toEqual(['DELETE /api/admin/api/gateway/groups/1'])
  expect(errors).toEqual([])
})

test('deleting the only row on the last page returns to the preceding valid page', async ({ page }) => {
  const { writes } = await mockGateway(page, { count: 11 })
  await page.getByRole('button', { name: 'Go to next page', exact: true }).click()
  const rows = page.locator('.el-table__body tbody tr')
  await expect(rows).toHaveCount(1)
  await expect(rows.first()).toContainText('Group 11')
  await rows.first().getByRole('button', { name: '删除', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(rows).toHaveCount(10)
  await expect(page.locator('.list-pagination')).toContainText('共 10 条')
  expect(writes).toEqual(['DELETE /api/admin/api/gateway/groups/11'])
})

test('backend conflict is displayed and leaves the group available for retry', async ({ page }) => {
  const { writes } = await mockGateway(page, { failDelete: true })
  const first = page.locator('.el-table__body tbody tr').first()
  await first.getByRole('button', { name: '删除', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认删除', exact: true }).click()
  await expect(page.locator('.el-message--error')).toContainText('正在同步')
  await expect(first).toContainText('Group 1')
  await expect(first.getByRole('button', { name: '删除', exact: true })).toBeEnabled()
  await expect(page.locator('.list-pagination')).toContainText('共 12 条')
  expect(writes).toHaveLength(1)
})

test('English delete action and dynamic confirmation are localized', async ({ page }) => {
  const { writes } = await mockGateway(page, { locale: 'en-US' })
  await page.locator('.el-table__body tbody tr').first().getByRole('button', { name: 'Delete', exact: true }).click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toContainText('Delete disabled group “Group 1”?')
  await expect(dialog).toContainText('The upstream site and historical run records will be retained.')
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click()
  expect(writes).toEqual([])
})
