import { expect, test } from '@playwright/test'

for (const failure of [false, true]) {
  test(`registration blocks repeated sends while email delivery is pending (${failure ? 'failure' : 'success'})`, async ({ page }) => {
    let requests = 0
    let release!: () => void
    const pending = new Promise<void>(resolve => { release = resolve })
    await page.route(/\/api\//, async route => {
      if (route.request().url().includes('/verification/email/send')) {
        requests++
        await pending
        return route.fulfill({ status: failure ? 503 : 200, json: failure ? { message: 'Delivery unavailable' } : { sent: true } })
      }
      return route.fulfill({ json: { mode: 'EMAIL_ONLY' } })
    })
    await page.goto('/?auth=register')
    const dialog = page.getByRole('dialog')
    await dialog.getByLabel('邮箱', { exact: true }).fill('verification-test@example.com')
    const send = dialog.getByRole('button', { name: '发送验证码', exact: true })
    await send.click()
    await expect(send).toBeDisabled()
    await expect.poll(() => requests).toBe(1)
    release()
    if (failure) await expect(send).toBeEnabled()
    else await expect(dialog.getByRole('button', { name: /^\d+s$/ })).toBeDisabled()
    expect(requests).toBe(1)
  })
}
