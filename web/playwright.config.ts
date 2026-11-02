import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  // These assertions exercise Nginx routing and headers, which Vite preview does not implement.
  testIgnore: 'public-seo.spec.ts',
  timeout: 30_000,
  use: { baseURL: 'http://127.0.0.1:4173', trace: 'retain-on-failure' },
  webServer: { command: 'npm run preview -- --host 127.0.0.1', port: 4173, reuseExistingServer: true },
  projects: [{ name: 'webkit', use: { ...devices['Desktop Safari'] } }]
})
