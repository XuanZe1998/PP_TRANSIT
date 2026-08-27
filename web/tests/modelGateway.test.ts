import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('model marketplace layout', () => {
  it('uses a full-width themed header and numbered client-side pagination', () => {
    const publicSite = source('src/views/PublicSite.vue')
    const css = source('src/style.css')
    expect(publicSite).toContain('site-nav-inner')
    expect(publicSite).toContain('ElPagination')
    expect(publicSite).toContain("layout: 'total, prev, pager, next'")
    expect(publicSite).toContain('const start = (page.value - 1) * size')
    expect(publicSite).not.toContain('加载更多（已显示')
    expect(css).toContain('.market-pagination')
    expect(css).toMatch(/\.site-nav\s*\{[\s\S]*?width:\s*100%/)
  })

  it('offers a dedicated upstream selector independent from model publisher', () => {
    const publicSite = source('src/views/PublicSite.vue')
    expect(publicSite).toContain("h('h3', '模型上游')")
    expect(publicSite).toContain('const upstreamOptions = computed')
    expect(publicSite).toContain("model.upstreamLabels[code]||'平台智能路由'")
    expect(publicSite).not.toContain("{ label: '好易智算', value: 'haoee' }")

    const standaloneMarket = source('src/views/ModelMarket.vue')
    expect(standaloneMarket).toContain('placeholder="选择模型上游"')
    expect(standaloneMarket).toContain('source: sourceFilter.value')
  })

  it('uses the gateway icon and keeps the desktop grid responsive', () => {
    expect(source('src/views/PublicSite.vue')).toContain('/model-icons/model-gateway.png')

    const css = source('src/style.css')
    expect(css).toMatch(/\.market-grid\s*\{[^}]*repeat\(2,\s*minmax\(0,\s*1fr\)\)/s)
    expect(css).toMatch(/@media\s*\(max-width:\s*980px\)[\s\S]*?\.market-grid\s*\{[^}]*minmax\(0,\s*1fr\)/)
  })

  it('ships a transparent PNG asset', () => {
    const png = readFileSync(resolve(root, 'public/model-icons/model-gateway.png'))
    expect(png.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a')
    // PNG IHDR colour type 6 means truecolour with an alpha channel.
    expect(png[25]).toBe(6)
  })
})

describe('model gateway administration routes', () => {
  it('keeps legacy URLs as redirects into the unified workbench', () => {
    const router = source('src/router/index.ts')
    expect(router).toContain("path: 'model-gateway', component: ModelGateway")
    expect(router).toMatch(/path: 'channels'.*tab: 'channels'/)
    expect(router).toMatch(/path: 'models'.*tab: 'models'/)
    expect(router).toMatch(/path: 'mappings'.*tab: 'models'/)
  })

  it('exposes all four management tabs from one navigation entry', () => {
    const gateway = source('src/views/ModelGateway.vue')
    for (const tab of ['overview', 'channels', 'models', 'health']) {
      expect(gateway).toContain(`name="${tab}"`)
    }

    const layout = source('src/components/AdminLayout.vue')
    expect(layout).toContain("path: '/admin/model-gateway'")
    expect(layout).not.toContain("path: '/admin/channels'")
    expect(layout).not.toContain("path: '/admin/models'")
  })
})
