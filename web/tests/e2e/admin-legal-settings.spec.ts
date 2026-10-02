import { expect, test, type Page } from '@playwright/test'

const longText = Array.from({ length: 300 }, (_, i) => `Policy paragraph ${i}: ${'long-policy-content-'.repeat(18)}`).join('\n')
type Options = { approved?: boolean; missing?: string[]; failWrite?: boolean; failLegal?: boolean; locale?: 'zh-CN' | 'en-US' }
async function mockSettings(page: Page, options: Options = {}) {
  await page.addInitScript(locale => {
    localStorage.setItem('linknux.locale', locale)
    sessionStorage.setItem('admin_access_token', 'test-admin-token')
    sessionStorage.setItem('admin_info', JSON.stringify({ username: 'test-admin', role: 'ADMIN' }))
    sessionStorage.setItem('admin_last_active_at', String(Date.now()))
  }, options.locale ?? 'zh-CN')
  let approved = options.approved ?? false
  const writes: Record<string, string>[] = []
  const rows = [
    { id: 1, setting_key: 'legal.terms', setting_value: longText, description: longText },
    { id: 2, setting_key: 'x'.repeat(3000), setting_value: longText, description: longText },
    ...Array.from({ length: 10 }, (_, i) => ({ id: i + 3, setting_key: `test.setting.${i}`, setting_value: longText, description: 'Test setting' }))
  ]
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname.endsWith('/admin/api/settings')) {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON()
        writes.push(body)
        if (options.failWrite) return route.fulfill({ status: 500, json: { message: 'Simulated save failure' } })
        if (body.key === 'legal.publication_approved') approved = body.value === 'true'
        return route.fulfill({ json: {} })
      }
      if (url.searchParams.get('listPage') === 'true') {
        const size = Number(url.searchParams.get('listSize') || 10), pageNumber = Number(url.searchParams.get('listCurrent') || 1)
        return route.fulfill({ json: { total: rows.length, page: pageNumber, size, items: rows.slice((pageNumber - 1) * size, pageNumber * size) } })
      }
      return route.fulfill({ json: rows })
    }
    if (url.pathname.endsWith('/public/legal')) {
      if (options.failLegal) return route.fulfill({ status: 503, json: { message: 'Unavailable' } })
      const missing = options.missing ?? []
      return route.fulfill({ json: { missing_fields: missing, legalReviewRequired: !approved, publication_ready: approved && missing.length === 0, payments_enabled: false, checkout_ready: false } })
    }
    if (url.pathname.endsWith('/admin/api/reports')) return route.fulfill({ json: { revenue: 0, cost: 0, grossMargin: 0, p95LatencyMs: 0, models: [] } })
    if (url.pathname.endsWith('/account-verification-status')) return route.fulfill({ json: { registrationReady: true, emailConfigured: true } })
    return route.fulfill({ json: {} })
  })
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.goto('/admin/settings')
  await expect(page.locator('.legal-publication-control')).toBeVisible()
  return { writes, errors }
}

for (const width of [390, 1440]) {
  test(`legal editor starts collapsed and long settings stay compact at ${width}px`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 950 })
    const { writes, errors } = await mockSettings(page)
    const editor = page.locator('.legal-settings-editor')
    const header = editor.getByRole('button', { name: /公开信息填写区/ })
    await expect(header).toHaveAttribute('aria-expanded', 'false')
    const input = editor.locator('textarea[id="legal.terms"]')
    await expect(input).toBeHidden()
    await header.click()
    await expect(input).toBeVisible()
    await expect(input).toHaveValue(longText)
    await input.fill('Unsaved local edit')
    await header.click()
    await expect(input).toBeHidden()
    await header.click()
    await expect(input).toHaveValue('Unsaved local edit')
    await header.click()
    const table = page.locator('.settings-table')
    await expect(table.locator('.el-table__body tbody tr')).toHaveCount(10)
    const metrics = await table.locator('.el-table__body tbody tr').evaluateAll(rows => rows.map(row => ({
      height: row.getBoundingClientRect().height,
      cells: [...row.querySelectorAll('.settings-cell-text')].map(cell => ({
        overflow: getComputedStyle(cell).overflow, whiteSpace: getComputedStyle(cell).whiteSpace,
        ellipsis: getComputedStyle(cell).textOverflow, clientWidth: cell.clientWidth, scrollWidth: cell.scrollWidth
      }))
    })))
    expect(metrics.every(row => row.height < 70)).toBe(true)
    expect(metrics.every(row => row.cells.every(cell => cell.overflow === 'hidden' && cell.whiteSpace === 'nowrap' && cell.ellipsis === 'ellipsis'))).toBe(true)
    expect(metrics[0].cells[1].scrollWidth).toBeGreaterThan(metrics[0].cells[1].clientWidth)
    expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)
    const pagedTable = table.locator('..')
    await expect(pagedTable.locator('.list-pagination')).toContainText('共 12 条')
    await pagedTable.getByRole('button', { name: 'Go to next page', exact: true }).click()
    await expect(table.locator('.el-table__body tbody tr')).toHaveCount(2)
    await pagedTable.getByRole('button', { name: 'Go to previous page', exact: true }).click()
    await expect(table.locator('.el-table__body tbody tr')).toHaveCount(10)
    await page.screenshot({ path: testInfo.outputPath(`settings-${width}.png`) })
    await table.locator('.el-table__body tbody tr').first().getByRole('button', { name: '编辑' }).click()
    await expect(page.locator('.el-drawer textarea').first()).toHaveValue(longText)
    expect(writes).toEqual([])
    expect(errors).toEqual([])
  })
}

test('approval is explicit, cancel is safe, and payment stays independent', async ({ page }) => {
  const { writes, errors } = await mockSettings(page)
  const control = page.locator('.legal-publication-control')
  const approval = control.getByRole('switch')
  await expect(approval).toHaveAttribute('aria-checked', 'false')
  await expect(control).toContainText('legal.publication_approved')
  await page.locator('.legal-publication-control .el-switch').click()
  const dialog = page.locator('.el-message-box')
  await expect(dialog).toContainText('legal.publication_approved=true')
  await dialog.getByRole('button', { name: '取消', exact: true }).click()
  await expect(approval).toHaveAttribute('aria-checked', 'false')
  expect(writes).toEqual([])
  await page.locator('.legal-publication-control .el-switch').click()
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(approval).toHaveAttribute('aria-checked', 'true')
  expect(writes).toHaveLength(1)
  expect(writes[0]).toMatchObject({ key: 'legal.publication_approved', value: 'true' })
  await expect(control).toContainText('正式政策就绪')
  await expect(page.getByRole('switch').last()).toHaveAttribute('aria-checked', 'false')
  await page.locator('.legal-publication-control .el-switch').click()
  await expect(dialog).toContainText('legal.publication_approved=false')
  await dialog.getByRole('button', { name: '确认', exact: true }).click()
  await expect(approval).toHaveAttribute('aria-checked', 'false')
  expect(writes).toHaveLength(2)
  expect(writes[1]).toMatchObject({ key: 'legal.publication_approved', value: 'false' })
  expect(writes.every(body => body.key === 'legal.publication_approved')).toBe(true)
  expect(errors).toEqual([])
})

test('missing required disclosures block approval but never block revocation', async ({ page }) => {
  const { writes } = await mockSettings(page, { missing: ['terms_en'] })
  await expect(page.locator('.legal-publication-control').getByRole('switch')).toHaveAttribute('aria-disabled', 'true')
  await expect(page.locator('.legal-publication-control')).toContainText('必填字段尚未齐全')
  expect(writes).toEqual([])
})

test('an approved policy can be revoked even after a required field is cleared', async ({ page }) => {
  const { writes } = await mockSettings(page, { approved: true, missing: ['terms_en'] })
  const approval = page.locator('.legal-publication-control').getByRole('switch')
  await expect(approval).toHaveAttribute('aria-checked', 'true')
  await page.locator('.legal-publication-control .el-switch').click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect(approval).toHaveAttribute('aria-checked', 'false')
  expect(writes[0]).toMatchObject({ key: 'legal.publication_approved', value: 'false' })
})

test('failed approval save leaves the policy unapproved and payment closed', async ({ page }) => {
  const { writes } = await mockSettings(page, { failWrite: true })
  const approval = page.locator('.legal-publication-control').getByRole('switch')
  await page.locator('.legal-publication-control .el-switch').click()
  await page.locator('.el-message-box').getByRole('button', { name: '确认', exact: true }).click()
  await expect(page.locator('.el-message--error')).toBeVisible()
  await expect(approval).toHaveAttribute('aria-checked', 'false')
  await expect(page.getByRole('switch').last()).toHaveAttribute('aria-checked', 'false')
  expect(writes).toHaveLength(1)
})

test('unknown legal status cannot be approved', async ({ page }) => {
  const { writes } = await mockSettings(page, { failLegal: true })
  await expect(page.locator('.legal-publication-control').getByRole('switch')).toHaveAttribute('aria-disabled', 'true')
  expect(writes).toEqual([])
})


test('English settings exposes localized approval controls without changing any setting', async ({ page }) => {
  const { writes, errors } = await mockSettings(page, { locale: 'en-US' })
  await expect(page.locator('.legal-publication-control h3')).toHaveText('Policy review and publication')
  await expect(page.locator('.legal-publication-control')).toContainText('Not approved')
  await expect(page.locator('.legal-settings-title')).toContainText('Click to expand or collapse')
  await expect(page.locator('.legal-settings-editor').getByRole('button').first()).toHaveAttribute('aria-expanded', 'false')
  expect(writes).toEqual([])
  expect(errors).toEqual([])
})
