import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: 'public-seo.spec.ts',
  timeout: 30_000,
  use: {
    baseURL: process.env.SEO_TEST_BASE_URL || 'http://127.0.0.1:18667',
    channel: process.env.PLAYWRIGHT_CHANNEL || 'chrome',
    trace: 'retain-on-failure',
  },
})
