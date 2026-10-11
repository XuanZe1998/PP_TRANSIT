import { describe, expect, it } from 'vitest'
import { legalLinks } from '../src/config/legal'

describe('public disclosures', () => {
  it('links to each required policy and support page without duplicate routes', () => {
    expect(legalLinks.map(link => link.path)).toEqual([
      '/terms', '/privacy', '/refund', '/ai-data', '/subprocessors', '/cookies', '/rights', '/support', '/security'
    ])
    expect(new Set(legalLinks.map(link => link.path)).size).toBe(legalLinks.length)
  })
})
