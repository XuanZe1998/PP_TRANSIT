import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('Linknux UI design system', () => {
  it('loads the design-system layers after the legacy compatibility stylesheet', () => {
    const main = source('src/main.ts')
    const legacy = main.indexOf("import './style.css'")
    const tokens = main.indexOf("import './styles/tokens.css'")
    const foundation = main.indexOf("import './styles/foundation.css'")
    const components = main.indexOf("import './styles/components.css'")

    expect(legacy).toBeGreaterThan(-1)
    expect(tokens).toBeGreaterThan(legacy)
    expect(foundation).toBeGreaterThan(tokens)
    expect(components).toBeGreaterThan(foundation)
  })

  it('exposes the approved brand, grid and accessibility tokens', () => {
    const tokens = source('src/styles/tokens.css')
    const foundation = source('src/styles/foundation.css')

    expect(tokens).toContain('--lnx-brand-600: #2563eb')
    expect(tokens).toContain('--lnx-cyan-600: #0891b2')
    expect(tokens).toContain('--lnx-content-width: 1248px')
    expect(tokens).toContain('--lnx-radius-control: 10px')
    expect(tokens).toContain('--lnx-radius-card: 14px')
    expect(foundation).toContain('"Noto Sans SC"')
    expect(foundation).toMatch(/:focus-visible[\s\S]*?outline:/)
    expect(foundation).toMatch(/animation:\s*none\s*!important/)
    expect(foundation).toMatch(/transition:\s*none\s*!important/)
  })

  it('provides responsive navigation for all three application shells', () => {
    expect(source('src/layouts/PublicLayout.vue')).toContain('class="site-mobile-drawer"')
    expect(source('src/views/UserConsole.vue')).toContain('class="user-mobile-drawer"')
    expect(source('src/components/AdminLayout.vue')).toContain('class="admin-mobile-drawer"')
    expect(source('src/styles/components.css')).toContain('@media (max-width: 980px)')
    expect(source('src/styles/components.css')).toContain('@media (max-width: 700px)')
  })

  it('keeps all shared business lists on the approved default page size', () => {
    expect(source('src/utils/listPage.ts')).toContain(': 20')
    expect(source('src/components/SelectablePagination.vue')).toContain('pageSize:20')
    expect(source('src/components/ModelProbePanel.vue')).toContain('const pageSize = ref(20)')
    expect(source('src/views/ModelMarket.vue')).toContain('const pageSize = ref(20)')
  })
})
