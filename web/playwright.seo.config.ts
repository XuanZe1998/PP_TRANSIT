import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: 'public-seo.spec.ts',
  timeout: 30_000,
  use: {
    baseURL: process.env.SEO_TEST_BASE_URL || 'https://linknux.com',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'webkit', use: { ...devices['Desktop Safari'] } }],
})
