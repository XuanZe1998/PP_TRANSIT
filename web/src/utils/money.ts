/**
 * Billing contract shared with the backend: 10,000 amount units equal CNY 1.
 * Ledger values should be supplied as integer units (prefer a string for values
 * outside JavaScript's safe-integer range).
 */
export const AMOUNT_UNITS_PER_CNY = 10_000
export const TOKENS_PER_MILLION = 1_000_000

export type AmountUnits = number | bigint | string

interface DecimalValue {
  negative: boolean
  coefficient: bigint
  scale: number
}

const MAX_INPUT_LENGTH = 256
const MAX_EXPONENT = 1_000

function parseDecimal(value: AmountUnits): DecimalValue {
  if (typeof value === 'bigint') {
    return {
      negative: value < 0n,
      coefficient: value < 0n ? -value : value,
      scale: 0,
    }
  }

  if (typeof value === 'number') {
    if (!Number.isFinite(value)) {
      throw new RangeError('Amount units must be finite')
    }
    if (Number.isInteger(value) && !Number.isSafeInteger(value)) {
      throw new RangeError('Unsafe integer amount; provide it as a string or bigint')
    }
  }

  const raw = String(value).trim()
  if (raw.length === 0 || raw.length > MAX_INPUT_LENGTH) {
    throw new RangeError('Amount units must be a non-empty decimal value')
  }

  const match = /^([+-]?)(\d+)(?:\.(\d+))?(?:[eE]([+-]?\d+))?$/.exec(raw)
  if (!match) {
    throw new RangeError('Amount units must be a decimal value')
  }

  const exponent = Number(match[4] ?? 0)
  if (!Number.isSafeInteger(exponent) || Math.abs(exponent) > MAX_EXPONENT) {
    throw new RangeError('Amount exponent is outside the supported range')
  }

  const fraction = match[3] ?? ''
  let coefficient = BigInt(`${match[2]}${fraction}`)
  let scale = fraction.length - exponent

  if (scale < 0) {
    coefficient *= 10n ** BigInt(-scale)
    scale = 0
  }

  // Canonicalize mathematically integral values such as "10000.0".
  while (scale > 0 && coefficient % 10n === 0n) {
    coefficient /= 10n
    scale -= 1
  }

  return {
    negative: match[1] === '-' && coefficient !== 0n,
    coefficient,
    scale,
  }
}

function requireIntegerUnits(value: AmountUnits): DecimalValue {
  const parsed = parseDecimal(value)
  if (parsed.scale !== 0) {
    throw new RangeError('Ledger amounts must use whole amount units')
  }
  return parsed
}

function roundToScale(value: DecimalValue, maximumScale: number): DecimalValue {
  if (value.scale <= maximumScale) return value

  const droppedScale = value.scale - maximumScale
  const divisor = 10n ** BigInt(droppedScale)
  let coefficient = value.coefficient / divisor
  const remainder = value.coefficient % divisor
  if (remainder * 2n >= divisor) coefficient += 1n

  return {
    negative: value.negative && coefficient !== 0n,
    coefficient,
    scale: maximumScale,
  }
}

function formatDecimal(
  value: DecimalValue,
  minimumFractionDigits: number,
  maximumFractionDigits: number,
  forcePositiveSign: boolean,
  sourceScale = 4,
): string {
  let cnyValue = roundToScale(
    { ...value, scale: value.scale + sourceScale },
    maximumFractionDigits,
  )

  let digits = cnyValue.coefficient.toString()
  const missingFractionDigits = cnyValue.scale - digits.length
  if (missingFractionDigits >= 0) {
    digits = `${'0'.repeat(missingFractionDigits + 1)}${digits}`
  }

  const integerLength = digits.length - cnyValue.scale
  let integer = cnyValue.scale === 0 ? digits : digits.slice(0, integerLength)
  let fraction = cnyValue.scale === 0 ? '' : digits.slice(integerLength)

  while (fraction.length > minimumFractionDigits && fraction.endsWith('0')) {
    fraction = fraction.slice(0, -1)
  }
  fraction = fraction.padEnd(minimumFractionDigits, '0')
  integer = integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')

  const sign = cnyValue.negative ? '-' : forcePositiveSign && cnyValue.coefficient !== 0n ? '+' : ''
  return `${sign}¥${integer}${fraction.length > 0 ? `.${fraction}` : ''} CNY`
}

/** Convert integer backend amount units to a JavaScript CNY number. */
export function amountUnitsToCny(amountUnits: AmountUnits): number {
  const value = requireIntegerUnits(amountUnits)
  const signedUnits = value.negative ? -value.coefficient : value.coefficient
  const unitsAsNumber = Number(signedUnits)
  if (!Number.isSafeInteger(unitsAsNumber)) {
    throw new RangeError('Amount is too large for a lossless JavaScript number; use formatCny instead')
  }
  return unitsAsNumber / AMOUNT_UNITS_PER_CNY
}

/** Format an integer ledger value with its explicit symbol and ISO currency. */
export function formatCny(amountUnits: AmountUnits): string {
  return formatDecimal(requireIntegerUnits(amountUnits), 4, 4, false)
}

/** As formatCny, with a leading plus sign for positive credits/refunds. */
export function formatSignedCny(amountUnits: AmountUnits): string {
  return formatDecimal(requireIntegerUnits(amountUnits), 4, 4, true)
}

/**
 * Format a model rate whose backend unit is CNY per one million tokens.
 * Ledger amounts use integer amount units, but model rate columns are decimal
 * CNY values because the backend multiplies them by billing.amount-scale only
 * when producing the final immutable ledger amount.
 */
export function formatPerMillionCny(cnyPerMillion: AmountUnits, unit = 'M', suffix?: string): string {
  const normalizedUnit = String(unit || 'M').toUpperCase() === 'KB' ? 'KB' : 'M'
  const formatted = formatDecimal(parseDecimal(cnyPerMillion), 4, 6, false, 0)
  const normalizedSuffix = String(suffix || '').trim()
  if (!normalizedSuffix && normalizedUnit === 'M') return `${formatted} / 1M tokens`
  return `${formatted} ${normalizedSuffix || `CNY / 1${normalizedUnit} Token`}`
}
