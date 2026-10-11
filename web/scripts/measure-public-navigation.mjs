// Read-only synthetic check of public navigation. Run from web/: node scripts/measure-public-navigation.mjs
import { chromium } from '@playwright/test'
import { writeFile } from 'node:fs/promises'

const args = process.argv.slice(2)
const option = (name, fallback) => {
  const index = args.indexOf(name)
  return index < 0 ? fallback : args[index + 1]
}
const origin = new URL(option('--origin', 'https://linknux.com'))
const runs = Number(option('--runs', '1'))
const output = option('--output', '')
if (!['https:', 'http:'].includes(origin.protocol) || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password ||
    !Number.isInteger(runs) || runs < 1 || runs > 10 || (args.includes('--output') && !output)) {
  throw new Error('Use an HTTP(S) origin with no path and --runs 1..10; --output needs a filename')
}
const timeout = 30_000
const destinations = [
  { name: 'market', button: 1, path: '/market', content: '.market-page' },
  { name: 'services', button: 3, path: '/services', content: '.other-services-page' },
  { name: 'docs', button: 5, path: '/docs', content: '.site-section' },
  { name: 'market-hot', button: 1, path: '/market', content: '.market-page' },
]
const browser = await chromium.launch({ headless: true, ...(args.includes('--system-chrome') ? { channel: 'chrome' } : {}) })
const rows = []
try {
  for (let run = 1; run <= runs; run++) {
    // A fresh context starts with an empty HTTP cache and no site storage.
    const context = await browser.newContext()
    const page = await context.newPage()
    page.setDefaultTimeout(timeout)
    let failedRequests = 0
    let serverErrors = 0
    const completed = []
    const requestLabel = request => {
      const path = new URL(request.url()).pathname
      // Never record query strings, headers, bodies, cookies, or arbitrary account paths.
      return path.startsWith('/assets/') || path.startsWith('/public/') ? path : request.resourceType()
    }
    page.on('requestfinished', request => {
      const timing = request.timing()
      completed.push({ resource: requestLabel(request), type: request.resourceType(),
        durationMs: Math.round(Math.max(0, timing.responseEnd)) })
    })
    page.on('requestfailed', () => { failedRequests++ })
    page.on('response', response => { if (response.status() >= 500) serverErrors++ })
    const homeStart = Date.now()
    try {
      const response = await page.goto(new URL('/', origin).href, { waitUntil: 'domcontentloaded', timeout })
      await page.locator('h1').first().waitFor({ state: 'visible', timeout })
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
      const metrics = await page.evaluate(() => {
        const navigation = performance.getEntriesByType('navigation')[0]
        const fcp = performance.getEntriesByName('first-contentful-paint')[0]
        return {
          dnsMs: navigation?.domainLookupEnd - navigation?.domainLookupStart,
          connectMs: navigation?.connectEnd - navigation?.connectStart,
          firstByteMs: navigation?.responseStart,
          domContentLoadedMs: navigation?.domContentLoadedEventEnd,
          fcpMs: fcp?.startTime ?? null,
        }
      })
      rows.push({ timestampUtc: new Date().toISOString(), run, path: '/', cache: 'cold', status: response?.status() ?? 0,
        wallMs: Date.now() - homeStart, ...metrics, failedRequests, serverErrors })
      for (const destination of destinations) {
        const beforeResources = completed.length
        const beforeFailures = failedRequests
        const beforeErrors = serverErrors
        const started = Date.now()
        let error = null
        try {
          await page.locator('nav[aria-label="Primary navigation"] button').nth(destination.button).click({ timeout })
          await page.waitForURL(new URL(destination.path, origin).href, { timeout })
          await page.locator(destination.content).first().waitFor({ state: 'visible', timeout })
          // Two animation frames allow the destination component to paint; this does not wait for API data.
          await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
        } catch (cause) {
          error = cause.name === 'TimeoutError' ? 'timeout' : 'navigation-error'
        }
        rows.push({ timestampUtc: new Date().toISOString(), run, path: destination.path,
          cache: destination.name.endsWith('-hot') ? 'hot' : 'warm-context-first-route',
          routePaintMs: Date.now() - started, failedRequests: failedRequests - beforeFailures,
          slowResources: completed.slice(beforeResources).sort((a, b) => b.durationMs - a.durationMs).slice(0, 5),
          serverErrors: serverErrors - beforeErrors, error })
        if (error) break
      }
    } catch (cause) {
      rows.push({ timestampUtc: new Date().toISOString(), run, path: '/', cache: 'cold',
        wallMs: Date.now() - homeStart, failedRequests, serverErrors,
        error: cause.name === 'TimeoutError' ? 'timeout' : 'navigation-error' })
    } finally {
      await context.close()
    }
  }
} finally {
  await browser.close()
}
if (output) await writeFile(output, `${JSON.stringify(rows, null, 2)}\n`, { flag: 'w' })
console.log(JSON.stringify(rows, null, 2))
