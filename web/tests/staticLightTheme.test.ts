import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('static light theme', () => {
  it('does not mount the animated technology backdrop', () => {
    const app = source('src/App.vue')
    expect(app).not.toContain('<TechBackdrop')
    expect(app).not.toContain("import TechBackdrop")
  })

  it('disables global motion and selects the light color scheme', () => {
    const css = source('src/style.css')
    expect(css).toMatch(/color-scheme:\s*light/)
    expect(css).toMatch(/\*,\s*\n\*::before,\s*\n\*::after\s*\{[\s\S]*?animation:\s*none\s*!important/)
    expect(css).toMatch(/\*,\s*\n\*::before,\s*\n\*::after\s*\{[\s\S]*?transition:\s*none\s*!important/)
  })

  it('pairs filled controls and alerts with explicit readable foregrounds', () => {
    const css = source('src/style.css')
    expect(css).toContain('Contrast and color harmony audit')
    expect(css).toMatch(/\.el-button > span,[\s\S]*?color:\s*inherit\s*!important/)
    expect(css).toMatch(/\.el-alert--warning\.is-light[\s\S]*?background:\s*var\(--ui-warning-surface\)[\s\S]*?color:\s*var\(--ui-warning-text\)/)
    expect(css).toMatch(/\.el-alert--success\.is-light[\s\S]*?background:\s*var\(--ui-success-surface\)[\s\S]*?color:\s*var\(--ui-success-text\)/)
    expect(css).toMatch(/\.el-alert--error\.is-light[\s\S]*?background:\s*var\(--ui-danger-surface\)[\s\S]*?color:\s*var\(--ui-danger-text\)/)
  })

  it('overrides former dark-theme text on light business cards', () => {
    const css = source('src/style.css')
    expect(css).toMatch(/#app \.metric-card strong,[\s\S]*?#app \.market-stats strong,[\s\S]*?color:\s*var\(--ui-text\)\s*!important/)
    expect(css).toMatch(/#app \.developer-docs header h2,[\s\S]*?color:\s*var\(--ui-text\)\s*!important/)
    expect(css).toMatch(/#app \.market-guide,[\s\S]*?background:\s*#ffffff\s*!important/)
    expect(css).toMatch(/#app \.list-pagination,[\s\S]*?color:\s*var\(--ui-muted\)\s*!important/)
  })

  it('keeps other-service cards and their bodies on readable light surfaces', () => {
    const css = source('src/style.css')
    expect(css).toMatch(/#app \.other-services-page \.service-card\s*\{[\s\S]*?background:\s*#ffffff\s*!important/)
    expect(css).toMatch(/#app \.other-services-page \.service-card-body\s*\{[\s\S]*?background:\s*#ffffff\s*!important[\s\S]*?color:\s*var\(--ui-text\)\s*!important/)
    expect(css).toMatch(/#app \.other-services-page \.service-order-row\s*\{[\s\S]*?border-color:\s*#d8e3f0\s*!important/)
  })

  it('turns off chart rendering animation', () => {
    expect(source('src/components/UsageTimelineChart.vue')).toContain('animation: false')
  })
  it('keeps the model-market toolbar and every admin surface on readable light colors', () => {
    const css = source('src/style.css')
    expect(css).toMatch(/#app \.market-toolbar\s*\{[\s\S]*?background:\s*#ffffff\s*!important[\s\S]*?color:\s*var\(--ui-text\)\s*!important/)
    expect(css).toMatch(/#app \.admin-login \.login-panel\s*\{[\s\S]*?background:\s*var\(--admin-surface\)\s*!important[\s\S]*?color:\s*var\(--admin-text\)\s*!important/)
    expect(css).toMatch(/#app \.admin-login \.submit-btn\.el-button--primary[^\{]*\{[\s\S]*?background:\s*var\(--admin-primary\)\s*!important[\s\S]*?color:\s*#ffffff\s*!important/)
    expect(css).toMatch(/#app \.admin-shell \.admin-main\s*\{[\s\S]*?background:\s*var\(--admin-bg\)\s*!important[\s\S]*?color:\s*var\(--admin-text\)\s*!important/)
  })
})



