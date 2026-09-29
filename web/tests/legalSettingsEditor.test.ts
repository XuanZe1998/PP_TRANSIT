import { describe, expect, it } from 'vitest'
import { allLegalSettingKeys, legalSettingGroups, requiredLegalSettingKeys } from '../src/config/legalSettings'

describe('legal disclosure editor', () => {
  it('offers required fields for review, without approval or payment toggles', () => {
    expect(requiredLegalSettingKeys).toEqual([
      'legal.operator', 'legal.address', 'legal.registration', 'legal.jurisdiction', 'legal.contact_email',
      'legal.terms', 'legal.privacy', 'legal.refund', 'legal.ai_data', 'legal.rights', 'legal.support',
      'legal.terms_version', 'legal.privacy_version', 'legal.effective_date',
      'legal.terms_en', 'legal.privacy_en', 'legal.refund_en', 'legal.ai_data_en', 'legal.rights_en', 'legal.support_en'
    ])
    expect(new Set(requiredLegalSettingKeys).size).toBe(20)
    expect(new Set(allLegalSettingKeys).size).toBe(26)
    expect(allLegalSettingKeys).toEqual(expect.arrayContaining(['legal.cookies', 'legal.cookies_en', 'legal.security', 'legal.security_en', 'legal.subprocessors', 'legal.subprocessors_en']))
    expect(requiredLegalSettingKeys).not.toContain('legal.publication_approved')
    expect(requiredLegalSettingKeys).not.toContain('commerce.payments_enabled')
    expect(legalSettingGroups.flatMap(group => group.fields).every(field => !('value' in field))).toBe(true)
  })
})
