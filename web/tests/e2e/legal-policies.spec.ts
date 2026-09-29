import { readFileSync } from 'node:fs'
import { expect, test } from '@playwright/test'

const kinds = ['terms', 'privacy', 'refund', 'ai_data', 'subprocessors', 'cookies', 'rights', 'support', 'security']
const draft: Record<string, unknown> = {
  operator: 'Test individual operator', address: 'Suzhou, Jiangsu, China', registration: 'Individual',
  jurisdiction: 'Suzhou', contact_email: 'operator@example.com', effective_date: '2026-10-01',
  terms_version: '2026-10-01', privacy_version: '2026-10-01',
  publication_ready: false, payments_enabled: false, checkout_ready: false,
}
for (const kind of kinds) for (const suffix of ['', '_en']) {
  draft[kind + suffix] = readFileSync(new URL('../../../src/main/resources/legal-drafts/' + kind + suffix + '.txt', import.meta.url), 'utf8')
}

for (const language of ['zh-CN', 'en-US']) {
  test('all nine policy drafts render safely in ' + language, async ({ page }, testInfo) => {
    await page.setViewportSize(language === 'zh-CN' ? { width: 390, height: 844 } : { width: 1280, height: 900 })
    await page.addInitScript(locale => localStorage.setItem('linknux.locale', locale), language)
    await page.route('**/api/**', route => route.fulfill({ json: {} }))
    await page.route('**/api/public/legal', route => route.fulfill({ json: draft }))
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    for (const kind of kinds) {
      await page.goto('/' + kind.replace('_', '-'))
      await expect(page.locator('.legal-copy')).toContainText('2026-09-29')
      const text = String(draft[kind + (language === 'en-US' ? '_en' : '')])
      const expectedParagraphs = text.split(/\n\s*\n|\n/).map(line => line.trim()).filter(Boolean)
      await expect(page.locator('.legal-copy > p, .legal-copy > h2')).toHaveText(expectedParagraphs)
      await expect(page.locator('.eyebrow')).toHaveText(language === 'en-US' ? 'LINKNUX · INFORMATION' : 'LINKNUX · 公开信息')
      await expect(page.locator('.meta')).toContainText(language === 'en-US' ? 'Proposed effective date' : '拟生效日期')
      await expect(page.locator('.meta')).toContainText('2026-10-01')
      await expect(page.locator('.legal-copy h2').first()).toContainText('1.')
      await expect(page.locator('.legal-page .el-alert').first()).toBeVisible()
      await expect(page.locator('.legal-page a[href="mailto:operator@example.com"]')).toBeVisible()
      if (kind === 'privacy') await page.screenshot({ path: testInfo.outputPath('privacy-' + language + '.png') })
      expect(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)).toBe(false)
    }
    expect(errors).toEqual([])
  })
}
