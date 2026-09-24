import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(import.meta.dirname, '..')
const source = (path: string) => readFileSync(resolve(root, path), 'utf8')

describe('enterprise and API calling flows', () => {
  it('registers enterprise contacts, accepts legal versions and supports new-IP verification', () => {
    const auth = source('src/components/AuthDialog.vue')
    for (const field of ['accountType', 'companyName', 'contactName', 'emailCode', 'confirmPassword', 'acceptedAgreements']) {
      expect(auth).toContain(field)
    }
    expect(auth).toContain('/api/auth/login/ip-verify')
    expect(source('src/components/AgreementGate.vue')).toContain('/api/user/legal/accept')
  })

  it('opens model call information instead of redirecting to the playground', () => {
    const site = source('src/views/ModelMarket.vue')
    const dialog = source('src/components/ModelCallDialog.vue')
    expect(site).toContain('ModelCallDialog')
    expect(site).not.toMatch(/callModel[\s\S]{0,500}playground/)
    expect(dialog).toContain('/chat/completions`')
    expect(dialog).toContain('/v1/messages`')
    expect(dialog).toContain('https://api.linknux.com')
    expect(dialog).toContain('anthropic-version')
  })

  it('exposes enterprise members, tokens, quotas and masking controls', () => {
    const organization = source('src/views/OrganizationConsole.vue')
    for (const value of ['/members/', '/tokens', 'walletQuota', 'allowedModels', 'ipWhitelist', 'data-security']) {
      expect(organization).toContain(value)
    }
  })

  it('keeps balance controls and exposes MaPay commerce without refund controls', () => {
    const admin = source('src/views/AdminConsole.vue')
    expect(admin).toContain('调账原因必须为 3–500 个字符')
    expect(admin).toContain('/finance/recharge-plans')
    expect(source('src/views/OtherServices.vue')).toContain('/service-orders')
    expect(source('src/views/UserConsole.vue')).toContain('/payment-intents')
    expect(source('src/views/AdminServiceOrders.vue')).not.toContain('/refund')
  })
})
