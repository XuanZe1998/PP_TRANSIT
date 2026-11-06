import { expect, test } from '@playwright/test'

test('user Key creation keeps its one-time secret separate from wallet and profile navigation', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'feature-test-user')
    localStorage.setItem('user_info', JSON.stringify({ username: 'feature-test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  const tokens: Record<string, unknown>[] = []
  const writes: Record<string, unknown>[] = []
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(url => url.origin === 'https://api.linknux.com' || url.pathname.startsWith('/api/'), route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/user/dashboard')) return route.fulfill({ json: { stats: {}, tokens, models: [], modelCatalog: [], recentLogs: [] } })
    if (path.endsWith('/user/tokens') && route.request().method() === 'POST') {
      writes.push(route.request().postDataJSON())
      tokens.push({ id: 7, name: 'Feature test Key', keyPreview: 'sk-at-••••', enabled: true, allowAllModels: true })
      return route.fulfill({ json: { secret: 'TEST-SECRET-ONLY-ONCE' } })
    }
    if (path.endsWith('/user/wallet')) return route.fulfill({ json: { plans: [], transactions: [], transactionTotal: 0 } })
    if (path.endsWith('/user/recharge-orders')) return route.fulfill({ json: { items: [], total: 0, page: 1, size: 10 } })
    if (path.endsWith('/user/profile')) return route.fulfill({ json: { username: 'feature-test', agreementRequired: false } })
    return route.fulfill({ json: {} })
  })
  await page.goto('/console/keys')
  await page.getByRole('button', { name: '新建 Key', exact: true }).last().click()
  const create = page.getByRole('dialog', { name: '新建 API Key', exact: true })
  await create.getByPlaceholder('例如：生产环境').fill('Feature test Key')
  await create.getByRole('button', { name: '创建', exact: true }).click()
  const secret = page.getByRole('dialog', { name: 'API Key 创建成功（仅显示一次）' })
  await expect(secret).toContainText('TEST-SECRET-ONLY-ONCE')
  expect(writes).toEqual([{ name: 'Feature test Key', totalQuota: 0, allowAllModels: true, allowedModelIds: [] }])
  await secret.getByRole('button', { name: '我已安全保存' }).click()
  await expect(page.getByText('TEST-SECRET-ONLY-ONCE', { exact: true })).toHaveCount(0)
  await expect(page.getByText('Feature test Key', { exact: true })).toBeVisible()
  await page.locator('.user-nav').getByRole('button', { name: '钱包充值', exact: true }).click()
  await expect(page.getByRole('heading', { name: '充值订单', exact: true })).toBeVisible()
  await page.locator('.user-nav').getByRole('button', { name: '个人中心', exact: true }).click()
  await expect(page).toHaveURL(/\/console\/profile$/)
  expect(errors).toEqual([])
})

test('administrator user adjustments keep the confirmation and reload the user list', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    sessionStorage.setItem('admin_access_token', 'feature-test-admin')
    sessionStorage.setItem('admin_info', JSON.stringify({ username: 'feature-test', role: 'ADMIN' }))
    sessionStorage.setItem('admin_last_active_at', String(Date.now()))
  })
  const writes: unknown[] = []
  let reads = 0
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(url => url.origin === 'https://api.linknux.com' || url.pathname.startsWith('/api/'), route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/admin/api/users')) {
      reads++
      const rows = [{ id: 42, username: 'account-under-test', status: 'ACTIVE', balance: 0 }]
      const paged = new URL(route.request().url()).searchParams.has('listPage')
      return route.fulfill({ json: paged ? { items: rows, total: 1, page: 1, size: 10 } : rows })
    }
    if (path.endsWith('/users/42/adjust-balance')) {
      writes.push(route.request().postDataJSON())
      return route.fulfill({ json: {} })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/admin/users')
  await page.getByRole('button', { name: '调账', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '用户手工调账', exact: true })
  await dialog.getByRole('spinbutton').fill('10000')
  await dialog.getByPlaceholder('请输入 3–500 个字符的调账原因').fill('test adjustment')
  await dialog.getByRole('button', { name: '确认调账' }).click()
  const confirmation = page.getByRole('dialog', { name: '高风险财务操作' })
  await expect(confirmation).toBeVisible()
  expect(writes).toEqual([])
  await confirmation.getByRole('button', { name: /^(取消|Cancel)$/ }).click()
  await expect(confirmation).toBeHidden()
  expect(writes).toEqual([])
  const previousReads = reads
  await dialog.getByRole('button', { name: '确认调账' }).click()
  await confirmation.getByRole('button', { name: '确认调账' }).click()
  await expect(dialog).toBeHidden()
  expect(writes).toEqual([{ amount: 10000, reason: 'test adjustment' }])
  await expect.poll(() => reads).toBeGreaterThan(previousReads)
  expect(errors).toEqual([])
})
