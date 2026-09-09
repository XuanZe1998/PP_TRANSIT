import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('model marketplace layout', () => {
  it('uses a full-width themed header and numbered server pagination', () => {
    const publicSite = source('src/views/ModelMarket.vue')
    const layout = source('src/layouts/PublicLayout.vue')
    const css = source('src/style.css')
    expect(layout).toContain('site-nav-inner')
    expect(publicSite).toContain('<SelectablePagination')
    expect(publicSite).toContain('layout="total, sizes, prev, pager, next, jumper"')
    expect(publicSite).toContain(':page-sizes="[10, 20, 50, 100, 200]"')
    expect(publicSite).not.toContain('加载更多（已显示')
    expect(css).toContain('.market-pagination')
    expect(css).toMatch(/\.site-nav\s*\{[\s\S]*?width:\s*100%/)
  })

  it('offers independent route and publisher facets from the server', () => {
    const publicSite = source('src/views/ModelMarket.vue')
    expect(publicSite).toContain("title: '渠道 / 路由'")
    expect(publicSite).toContain("title: '模型发布方'")
    expect(publicSite).toContain("routes: []")
    expect(publicSite).toContain("publishers: []")
    expect(publicSite).not.toContain("{ label: '好易智算', value: 'haoee' }")
  })

  it('uses the gateway icon and keeps the desktop grid responsive', () => {
    expect(source('src/views/ModelMarket.vue')).toContain('/model-icons/model-gateway.png')

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

  it('exposes the three consolidated management tabs from one navigation entry', () => {
    const gateway = source('src/views/ModelGateway.vue')
    for (const tab of ['channels', 'models', 'records']) {
      expect(gateway).toContain(`name="${tab}"`)
    }

    const layout = source('src/components/AdminLayout.vue')
    expect(layout).toContain("path: '/admin/model-gateway'")
    expect(layout).not.toContain("path: '/admin/channels'")
    expect(layout).not.toContain("path: '/admin/models'")
  })

  it('supports encrypted AiAPIBank account authorization without retaining the password', () => {
    const gateway = source('src/views/ModelGateway.vue')
    expect(gateway).toContain('未授权时同步匿名模型广场')
    expect(gateway).toContain('登录密码（仅用于本次登录，不保存）')
    expect(gateway).toContain('登录授权并同步')
    expect(gateway).toContain('totpCode')
    expect(gateway).toContain('“对接专用”分组')
    expect(gateway).not.toContain('accountAccessToken')
    expect(gateway).not.toContain('accountUserAgent')
    expect(gateway).not.toContain('clearAccountAccessToken')
  })

  it('keeps public route identity at site scope instead of creating group aliases', () => {
    const gateway = source('src/views/ModelGateway.vue')
    expect(gateway).toContain('前台渠道名称由所属站点统一维护')
    expect(gateway).toContain('function editPublicName(row:any){editSite(row)}')
    expect(gateway).not.toContain('settings.inheritDisplay=false')
  })
})
