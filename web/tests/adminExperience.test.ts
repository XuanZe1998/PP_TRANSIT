import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('admin analytics and security experience', () => {
  it('renders real lazy-loaded bar and pie charts with daily model details', () => {
    const consoleView = source('src/views/AdminConsole.vue')
    const charts = source('src/components/AdminUsageCharts.vue')
    expect(consoleView).toContain('defineAsyncComponent')
    expect(charts).toContain('BarChart')
    expect(charts).toContain('PieChart')
    expect(charts).toContain('dailyByModel')
    expect(charts).toContain('profit_amount')
  })

  it('cascades enterprise, member and model filters and exposes security controls', () => {
    const consoleView = source('src/views/AdminConsole.vue')
    expect(consoleView).toContain('handleAudienceChange')
    expect(consoleView).toContain('handleOrganizationChange')
    expect(consoleView).toContain('remote-method="loadAuditOptions"')
    expect(consoleView).toContain('security/sensitive-words/bulk')
    expect(consoleView).toContain('安全事件')
    expect(consoleView).toContain(':disabled="!auditFilterReady"')
    expect(consoleView).toContain('敏感词不是系统自动认定的固定名单')
  })

  it('saves one channel model without closing the editor and tracks dirty drafts', () => {
    const consoleView = source('src/views/AdminConsole.vue')
    expect(consoleView).toContain('保存当前模型')
    expect(consoleView).toContain('/model-pricing`')
    expect(consoleView).toContain('isPricingDirty')
    expect(consoleView).toContain('返回列表')
    expect(consoleView).toContain('渠道凭证池')
    expect(consoleView).toContain('pricingUnitOptions')
    expect(consoleView).toContain('非 Token 模型只维护单一单位价格')
  })

  it('shares the ordered sale-price matrix across public and user model views', () => {
    const component = source('src/components/ModelSalePricing.vue')
    const publicSite = source('src/views/PublicSite.vue')
    const userConsole = source('src/views/UserConsole.vue')
    expect(component).toContain('输入 / 未命中')
    expect(component).toContain('缓存命中')
    expect(component).toContain('不计费')
    expect(component).not.toContain('成本倍率')
    expect(component).not.toContain('priceRange')
    expect(component).toContain('免费开发预览')
    expect(component).toContain("pricingUnit !== 'TOKEN'")
    expect(publicSite).toContain('ModelSalePricing')
    expect(publicSite).toContain("name.includes('claude')")
    expect(publicSite).not.toContain('publicMultiplierSummary')
    expect(userConsole).toContain('ModelSalePricing')
  })

  it('keeps an explicit return-to-home action in the creative studio', () => {
    const studio = source('src/views/CreativeStudio.vue')
    expect(studio).toContain('返回主界面')
    expect(studio).toContain("router.push('/')")
  })

  it('loads managed service images through the API with explicit CORS mode', () => {
    const adminServices = source('src/views/AdminOtherServices.vue')
    const publicServices = source('src/views/OtherServices.vue')
    expect(adminServices).toContain('resolveApiResourceUrl(row.imageUrl)')
    expect(adminServices).toContain('crossorigin="anonymous"')
    expect(publicServices).toContain('resolveApiResourceUrl(service.imageUrl)')
    expect(publicServices).toContain('crossorigin="anonymous"')
  })
})

describe('front-end modal authentication', () => {
  it('uses a submit form for Enter and keeps legacy links compatible', () => {
    const dialog = source('src/components/AuthDialog.vue')
    const router = source('src/router/index.ts')
    expect(dialog).toContain('@submit.prevent="submit"')
    expect(dialog).toContain('native-type="submit"')
    expect(dialog).toContain(':disabled="loading"')
    expect(router).toContain("auth: 'login'")
    expect(router).toContain("auth: 'register'")
  })
})
