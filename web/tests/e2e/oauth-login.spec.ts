import { expect, test } from '@playwright/test'

test('GitHub login opens email verification when the login IP changes', async ({ page, baseURL }) => {
  const state = 'github-login-test-state'
  const challengeId = 'ipc_github_login_test'

  await page.route('**/api/**', route => route.fulfill({ json: {} }))
  await page.route('**/api/oauth/authorize?provider=github', route => route.fulfill({
    json: { state, url: `https://github.com/login/oauth/authorize?state=${state}` },
  }))
  await page.route('https://github.com/login/oauth/authorize?**', route => route.fulfill({
    contentType: 'text/html',
    body: `<meta http-equiv="refresh" content="0;url=${baseURL}/oauth/callback/github?code=github-test-code&amp;state=${state}">`,
  }))
  await page.route('**/api/oauth/callback/github?**', route => {
    const query = new URL(route.request().url()).searchParams
    expect(query.get('code')).toBe('github-test-code')
    expect(query.get('state')).toBe(state)
    return route.fulfill({ json: { verificationRequired: true, challengeId, maskedEmail: 'g***@example.com' } })
  })
  await page.route('**/api/auth/login/ip-verify', route => {
    expect(route.request().postDataJSON()).toEqual({ challengeId, code: '123456' })
    return route.fulfill({ json: { access_token: 'test-session', username: 'github-user', role: 'USER' } })
  })

  await page.goto('/login')
  await page.getByRole('button', { name: 'GitHub', exact: true }).click()

  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('g***@example.com', { timeout: 8_000 })
  await dialog.getByLabel('邮箱验证码').fill('123456')
  await dialog.getByRole('button', { name: '验证并登录' }).click()
  await expect(page).toHaveURL(/\/console$/)
})
