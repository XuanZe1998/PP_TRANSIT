import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('console partial-loading resilience', () => {
  it('keeps billing data and usage analytics independent', () => {
    const userConsole = source('src/views/UserConsole.vue')
    expect(userConsole).toContain('Promise.allSettled')
    expect(userConsole).toContain('billingDataError')
    expect(userConsole).toContain('usageAnalyticsError')
    expect(userConsole).toContain('loadUsageAnalytics')
  })

  it('loads each administrator finance section independently', () => {
    const adminConsole = source('src/views/AdminConsole.vue')
    expect(adminConsole).toContain("type FinanceSection = 'summary' | 'transactions' | 'codes' | 'plans'")
    expect(adminConsole).toContain('loadFinanceSection')
    expect(adminConsole).toContain('financeErrors.transactions')
    expect(adminConsole).toContain('financeErrors.plans')
  })

  it('supports automatic discovery plus manual channel model selection', () => {
    const adminConsole = source('src/views/AdminConsole.vue')
    expect(adminConsole).toContain('allow-create')
    expect(adminConsole).toContain('discoverChannelModelsForEditor')
    expect(adminConsole).toContain('加入全部识别结果')
    expect(adminConsole).toContain('activePricingRows')
    expect(adminConsole).toContain('removeActivePricingModel')
    expect(adminConsole).toContain('shouldAutoDiscover')
  })
})

describe('console layout contracts', () => {
  it('uses a draggable 90-day token timeline and three persistent playground columns', () => {
    const userConsole = source('src/views/UserConsole.vue')
    const timeline = source('src/components/UsageTimelineChart.vue')
    const css = source('src/style.css')
    expect(userConsole).toContain(':days="90"')
    expect(timeline).toContain("type: 'inside'")
    expect(timeline).toContain("type: 'slider'")
    expect(timeline).toContain("stack: 'tokens'")
    expect(userConsole).not.toContain('v-if="playgroundUsage" class="console-panel playground-billing-panel"')
    expect(css).toMatch(/\.playground-layout\s*\{[\s\S]*?repeat\(3, minmax\(0, 1fr\)\)/)
  })

  it('keeps usage tables paged and horizontally draggable', () => {
    const userConsole = source('src/views/UserConsole.vue')
    expect(userConsole).toContain('/api/user/billing/logs/page')
    expect(userConsole).toContain(':page-sizes="[10, 20, 50, 100]"')
    expect(userConsole).toContain('usage-table-scroll')
    expect(userConsole).toContain('scrollbar-always-on')
  })

  it('keeps the user console stable when browser developer tools narrow the viewport', () => {
    const userConsole = source('src/views/UserConsole.vue')
    const css = source('src/style.css')
    expect(userConsole).toContain('console-status-note')
    expect(userConsole).toContain('HomeFilled')
    expect(userConsole).toContain('SwitchButton')
    expect(css).toContain('@media (min-width: 700px) and (max-width: 1099px)')
    expect(css).toMatch(/grid-template-columns:\s*80px minmax\(0, 1fr\)/)
    expect(css).toMatch(/@media \(max-width: 700px\)[\s\S]*?\.user-console\s*\{[\s\S]*?minmax\(0, 1fr\)/)
    expect(css).toContain('.console-status-note.is-green')
    expect(css).not.toMatch(/(?:^|\n)\.green\s*\{/)
  })

  it('uses one high-contrast light primary button style without affecting link or plain buttons', () => {
    const css = source('src/style.css')
    expect(css).toContain('.el-button--primary:not(.is-link):not(.is-plain):not(.is-text)')
    expect(css).toContain('background: #e2efff !important')
    expect(css).toContain('color: #0d4f9f !important')
    expect(css).not.toContain('.market-page .el-button--primary')
  })

  it('uses the bright technology administrator navigation palette', () => {
    const layout = source('src/components/AdminLayout.vue')
    expect(layout).toContain('rgba(250,253,255,.98)')
    expect(layout).toContain('linear-gradient(135deg, #1769ff, #00a9cc)')
    expect(layout).toContain('.el-sub-menu__title')
  })

  it('keeps gateway columns accessible through persistent horizontal scrolling', () => {
    const adminConsole = source('src/views/AdminConsole.vue')
    const gateway = source('src/views/ModelGateway.vue')
    expect(adminConsole).toContain('scrollbar-always-on')
    expect(adminConsole).toContain('min-width: 1400px')
    expect(adminConsole).toContain('min-width: 2600px')
    expect(gateway).toContain('scrollbar-always-on')
    expect(gateway).toContain('min-width: 1250px')
    expect(gateway).toContain('fixed="right"')
  })
})
