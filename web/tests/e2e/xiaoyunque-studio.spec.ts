import { test, expect } from '@playwright/test'

test('xiaoyunque uses private key settings, submits typed marketing options, and pages works', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('user_access_token', 'test-only-user-token')
    localStorage.setItem('user_info', JSON.stringify({ username: 'test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  let request: any, configured = false
  await page.route('**/creative/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/xiaoyunque/settings')) {
      if (route.request().method() === 'PUT') { expect(route.request().postDataJSON()).toEqual({ accessKey: 'test-only-xyq-key' }); configured = true }
      return route.fulfill({ json: { configured } })
    }
    if (path.endsWith('/xiaoyunque/balance')) return route.fulfill({ json: { total_remain_amount: '0' } })
    if (path.endsWith('/xiaoyunque/runs')) {
      if (route.request().method() === 'POST') { request = route.request().postDataJSON(); return route.fulfill({ json: { id: 'created' } }) }
      const current = Number(new URL(route.request().url()).searchParams.get('page') || 1)
      const rows = Array.from({ length: current === 1 ? 10 : 1 }, (_, i) => ({ id: `work-${current}-${i}`, message: `营销要求 ${current}-${i}`, state: '6', thread_id: 'thread', run_id: 'run', created_at: '2026-10-23' }))
      return route.fulfill({ json: { items: rows, total: 11, page: current, size: 10 } })
    }
    if (path.endsWith('/catalog')) return route.fulfill({ json: { providers: [] } })
    return route.fulfill({ json: [] })
  })
  await page.route('**/api/public/**', route => route.fulfill({ json: {} }))
  await page.goto('/studio')
  await page.getByRole('button', { name: /小云雀营销成片/ }).click()
  const studio = page.locator('.xyq-studio')
  await studio.getByPlaceholder('粘贴你的小云雀 Access Key').fill('test-only-xyq-key')
  await studio.getByRole('button', { name: '保存密钥', exact: true }).click()
  await expect(studio.getByPlaceholder('已配置；输入新值可更换')).toHaveValue('')
  await studio.getByRole('button', { name: '查询积分', exact: true }).click()
  await expect(studio.getByText('剩余积分：0')).toBeVisible()
  await studio.getByPlaceholder('描述商品卖点、目标人群、视频风格和文案要求…').fill('生成产品剧情广告')
  await studio.getByPlaceholder('填写有权限的视频模型标识').fill('Seedance_2.0_mini')
  await studio.locator('.el-switch').click()
  await studio.getByRole('button', { name: '开始生成 · 使用小云雀积分', exact: true }).click()
  await expect.poll(() => request).toBeTruthy()
  expect(request.general_agent_settings).toMatchObject({video_model:'Seedance_2.0_mini',ratio:3,show_subtitle:false,duration_start:15,duration_end:30})
  expect(request).not.toHaveProperty('accessKey')
  await expect(studio.locator('.run-card')).toHaveCount(10)
  await studio.locator('.list-pagination .el-pager').getByText('2', {exact:true}).click()
  await expect(studio.locator('.run-card')).toHaveCount(1)
  await page.setViewportSize({width:390,height:844})
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy()
})
