const ENTITY_MAP: Record<string, string> = {
  amp: '&',
  apos: "'",
  gt: '>',
  lt: '<',
  nbsp: ' ',
  quot: '"',
}

function decodeEntity(_match: string, entity: string) {
  const normalized = entity.toLowerCase()
  if (normalized.startsWith('#x')) {
    const codePoint = Number.parseInt(normalized.slice(2), 16)
    return Number.isFinite(codePoint) ? String.fromCodePoint(codePoint) : ' '
  }
  if (normalized.startsWith('#')) {
    const codePoint = Number.parseInt(normalized.slice(1), 10)
    return Number.isFinite(codePoint) ? String.fromCodePoint(codePoint) : ' '
  }
  return ENTITY_MAP[normalized] ?? ' '
}

/** Convert untrusted upstream rich text into a compact, display-safe summary. */
export function subscriptionPlainText(value: unknown, fallback = '') {
  const source = String(value ?? '').trim()
  if (!source) return fallback

  const text = source
    .replace(/<(script|style)\b[^>]*>[\s\S]*?<\/\1\s*>/giu, ' ')
    .replace(/<\s*br\s*\/?>/giu, '\n')
    .replace(/<\/(?:div|h[1-6]|li|ol|p|section|table|tr|ul)\s*>/giu, '\n')
    .replace(/<[^>]+>/gu, ' ')
    .replace(/&(#x[\da-f]+|#\d+|[a-z]+);/giu, decodeEntity)
    .replace(/[\t\f\v ]+/gu, ' ')
    .replace(/ *\n */gu, '\n')
    .replace(/\n{3,}/gu, '\n\n')
    .trim()

  return text || fallback
}
