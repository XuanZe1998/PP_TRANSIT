import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { globSync } from 'node:fs'

describe('Linknux brand guard', () => {
  it('keeps user-visible frontend sources free of legacy marketing names', () => {
    const files = globSync('src/**/*.{vue,ts}')
    const source = files.map(file => readFileSync(file, 'utf8')).join('\n')
    expect(source).not.toMatch(/API\s*Transit/i)
    expect(source).not.toMatch(/API\s*中转|中转站/)
  })
})
