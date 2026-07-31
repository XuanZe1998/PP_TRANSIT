export type CallableModel = {
  publicName: string
  type: string
  minInputPricePerMillion: number | string
  maxInputPricePerMillion: number | string
  minOutputPricePerMillion: number | string
  maxOutputPricePerMillion: number | string
  minCachedPricePerMillion: number | string
  maxCachedPricePerMillion: number | string
  routeCount: number
  providerCount: number
  currency?: string
  amountScale?: number
  priceUnit?: string
  priceVariesByRoute?: boolean
}

export type TokenModelScope = {
  allowedModels?: string | null
}

const emptyPrice = 0

export function normalizeModelCatalog(
  catalog: unknown,
  legacyModelNames: unknown,
): CallableModel[] {
  const rawCatalog = Array.isArray(catalog) ? catalog : []
  const normalized = rawCatalog
    .map(item => normalizeCatalogItem(item))
    .filter((item): item is CallableModel => item !== null)

  const seen = new Set(normalized.map(item => item.publicName))
  if (Array.isArray(legacyModelNames)) {
    for (const value of legacyModelNames) {
      const publicName = typeof value === 'string' ? value.trim() : ''
      if (!publicName || seen.has(publicName)) continue
      seen.add(publicName)
      normalized.push(fallbackModel(publicName))
    }
  }

  return normalized.sort((left, right) => left.publicName.localeCompare(right.publicName))
}

export function modelsAllowedForToken(
  catalog: CallableModel[],
  token: TokenModelScope | null | undefined,
): CallableModel[] {
  const allowed = parseAllowedModels(token?.allowedModels)
  if (allowed === null || allowed.has('*')) return catalog
  return catalog.filter(model => allowed.has(model.publicName.toLowerCase()))
}

export function modelScopeLabel(allowedModels: string | null | undefined): string {
  const allowed = parseAllowedModels(allowedModels)
  if (allowed === null || allowed.has('*')) return '全部可用模型'
  if (allowed.size === 0) return '无可用模型'
  return [...allowed].join(', ')
}

function parseAllowedModels(value: string | null | undefined): Set<string> | null {
  if (!value || !value.trim()) return null
  return new Set(value
    .split(',')
    .map(item => item.trim().toLowerCase())
    .filter(Boolean))
}

function normalizeCatalogItem(value: unknown): CallableModel | null {
  if (!value || typeof value !== 'object') return null
  const item = value as Record<string, unknown>
  const publicName = typeof item.publicName === 'string' ? item.publicName.trim() : ''
  if (!publicName) return null
  return {
    publicName,
    type: typeof item.type === 'string' && item.type.trim() ? item.type.trim() : 'unknown',
    minInputPricePerMillion: decimal(item.minInputPricePerMillion),
    maxInputPricePerMillion: decimal(item.maxInputPricePerMillion),
    minOutputPricePerMillion: decimal(item.minOutputPricePerMillion),
    maxOutputPricePerMillion: decimal(item.maxOutputPricePerMillion),
    minCachedPricePerMillion: decimal(item.minCachedPricePerMillion),
    maxCachedPricePerMillion: decimal(item.maxCachedPricePerMillion),
    routeCount: positiveInteger(item.routeCount, 1),
    providerCount: positiveInteger(item.providerCount, 1),
    currency: typeof item.currency === 'string' ? item.currency : 'CNY',
    amountScale: positiveInteger(item.amountScale, 10_000),
    priceUnit: typeof item.priceUnit === 'string' ? item.priceUnit : 'currency_per_1m_tokens',
    priceVariesByRoute: item.priceVariesByRoute === true,
  }
}

function fallbackModel(publicName: string): CallableModel {
  return {
    publicName,
    type: 'unknown',
    minInputPricePerMillion: emptyPrice,
    maxInputPricePerMillion: emptyPrice,
    minOutputPricePerMillion: emptyPrice,
    maxOutputPricePerMillion: emptyPrice,
    minCachedPricePerMillion: emptyPrice,
    maxCachedPricePerMillion: emptyPrice,
    routeCount: 1,
    providerCount: 1,
    currency: 'CNY',
    amountScale: 10_000,
    priceUnit: 'currency_per_1m_tokens',
    priceVariesByRoute: false,
  }
}

function decimal(value: unknown): number | string {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value.trim())) return value.trim()
  return emptyPrice
}

function positiveInteger(value: unknown, fallback: number): number {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : fallback
}
