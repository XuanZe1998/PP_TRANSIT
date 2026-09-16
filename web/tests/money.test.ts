import { afterEach, describe, expect, it } from 'vitest'

import {
  AMOUNT_UNITS_PER_CNY,
  amountUnitsToCny,
  formatCny,
  formatPerMillionCny,
  formatSignedCny,
  formatUsd,
} from '../src/utils/money'
import { setLocale, setUsdCnyRate } from '../src/i18n/locale'

describe('money amount-unit contract', () => {
  afterEach(() => {
    setLocale('zh-CN')
    setUsdCnyRate(6.76693506)
  })
  it('uses 10,000 backend amount units per CNY', () => {
    expect(AMOUNT_UNITS_PER_CNY).toBe(10_000)
    expect(amountUnitsToCny(10_000)).toBe(1)
    expect(amountUnitsToCny(-25_000)).toBe(-2.5)
  })

  it('formats ledger amounts with the symbol, ISO currency, and four unit decimals', () => {
    expect(formatCny(0)).toBe('¥0.0000 CNY')
    expect(formatCny(12_345)).toBe('¥1.2345 CNY')
    expect(formatCny('12345678901234567890')).toBe('¥1,234,567,890,123,456.7890 CNY')
    expect(formatCny(-10_001)).toBe('-¥1.0001 CNY')
  })

  it('adds a plus sign only to positive signed amounts', () => {
    expect(formatSignedCny(20_000)).toBe('+¥2.0000 CNY')
    expect(formatSignedCny(-20_000)).toBe('-¥2.0000 CNY')
    expect(formatSignedCny(0)).toBe('¥0.0000 CNY')
  })

  it('labels model rates per one million tokens and preserves configured precision', () => {
    expect(formatPerMillionCny(1)).toBe('¥1.0000 CNY / 1M tokens')
    expect(formatPerMillionCny('1.234567')).toBe('¥1.234567 CNY / 1M tokens')
  })

  it('rejects fractional ledger units and unsafe numeric integers', () => {
    expect(() => formatCny('1.5')).toThrow(/whole amount units/)
    expect(() => formatCny(Number.MAX_SAFE_INTEGER + 1)).toThrow(/Unsafe integer/)
  })

  it('switches both symbols and converted values with the active locale', () => {
    setUsdCnyRate(7)
    setLocale('en-US')
    expect(formatCny(70_000)).toBe('$1.0000 USD')
    expect(formatUsd(10_000)).toBe('$1.0000 USD')
    setLocale('zh-CN')
    expect(formatUsd(10_000)).toBe('¥7.0000 CNY')
  })
})
