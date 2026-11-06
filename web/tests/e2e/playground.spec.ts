import { expect, test } from '@playwright/test'

test('playground preserves the model answer and server billing when account refresh fails', async ({ page }) => {
  await page.addInitScript(() => {
    localStorage.setItem('linknux.locale', 'zh-CN')
    localStorage.setItem('user_access_token', 'playground-test')
    localStorage.setItem('user_info', JSON.stringify({ username: 'test', role: 'USER' }))
    localStorage.setItem('user_last_active_at', String(Date.now()))
  })
  let submitted = false
  const writes: unknown[] = []
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route(url => url.origin === 'https://api.linknux.com' || url.pathname.startsWith('/api/'), route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/user/dashboard')) return submitted
      ? route.fulfill({ status: 503, json: { message: '账户刷新暂不可用' } })
      : route.fulfill({ json: { stats: {}, tokens: [{ id: 1, name: 'Test key', enabled: true }], models: ['test-model'], recentLogs: [] } })
    if (path.endsWith('/user/playground')) {
      submitted = true
      writes.push(route.request().postDataJSON())
      return route.fulfill({ json: { choices: [{ message: { content: '测试回答成功' } }], usage: { prompt_tokens: 12, completion_tokens: 5 }, billing: { total_amount: '10000' } } })
    }
    return route.fulfill({ json: {} })
  })
  await page.goto('/console/playground')
  await expect(page.locator('.playground-request-panel')).toContainText('test-model')
  await page.getByPlaceholder('输入需要发送给模型的内容').fill('test prompt')
  await page.locator('.playground-request-panel').getByRole('button', { name: '发送请求', exact: true }).click()
  await expect(page.locator('.playground-answer-scroll')).toHaveText('测试回答成功')
  await expect(page.locator('.playground-billing-panel')).toContainText('服务端已结算')
  await expect(page.locator('.playground-result-error')).toHaveCount(0)
  await expect(page.locator('.playground-layout > .console-panel')).toHaveCount(3)
  expect(writes).toEqual([{ tokenId: 1, model: 'test-model', prompt: 'test prompt' }])
  expect(errors).toEqual([])
})
