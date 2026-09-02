export type CallableModel = {
  publicName: string
  displayName?: string
  upstreamModelName?: string
  type: string
  source: string
  sourceName: string
  vendor: string
  capability: string
  inputModalities: string
  outputModalities: string
  protocols: string
  pricingUnit: string
  available: boolean
  billingConfigured: boolean
  minInputPricePerMillion: number | string
  maxInputPricePerMillion: number | string
  minOutputPricePerMillion: number | string
  maxOutputPricePerMillion: number | string
  minCachedPricePerMillion: number | string
  maxCachedPricePerMillion: number | string
  minCacheReadPricePerMillion: number | string
  maxCacheReadPricePerMillion: number | string
  minCacheWritePricePerMillion: number | string
  maxCacheWritePricePerMillion: number | string
  minInputCostMultiplier?: number | string
  maxInputCostMultiplier?: number | string
  minOutputCostMultiplier?: number | string
  maxOutputCostMultiplier?: number | string
  minCacheReadCostMultiplier?: number | string
  maxCacheReadCostMultiplier?: number | string
  minCacheWriteCostMultiplier?: number | string
  maxCacheWriteCostMultiplier?: number | string
  routeCount: number
  providerCount: number
  currency?: string
  amountScale?: number
  priceUnit?: string
  priceVariesByRoute?: boolean
  upstreams?: Array<{ code: string; name: string; badgeText?: string; badgeColor?: string }>
  contextPricing?: Record<string, unknown>
  providerGroup?: Record<string, unknown>
  priceTiers?: Array<Record<string, unknown>>
  unitPriceVariants?: Array<Record<string, unknown>>
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
  const upstreams=Array.isArray(item.upstreams)?item.upstreams.filter((entry):entry is Record<string,unknown>=>Boolean(entry&&typeof entry==='object')).map(entry=>({code:typeof entry.code==='string'&&entry.code?entry.code:'platform-route',name:typeof entry.name==='string'&&entry.name?entry.name:'平台智能路由',badgeText:typeof entry.badgeText==='string'?entry.badgeText:undefined,badgeColor:typeof entry.badgeColor==='string'?entry.badgeColor:undefined})):[]
  const primary=upstreams[0]||{code:'platform-route',name:'平台智能路由'}
  return {
    publicName,
    displayName: typeof item.displayName === 'string' ? item.displayName : publicName,
    upstreamModelName: typeof item.upstreamModelName === 'string' ? item.upstreamModelName : publicName,
    type: primary.code,
    source: primary.code,
    sourceName: primary.name,
    vendor: typeof item.vendor === 'string' && item.vendor.trim() ? item.vendor.trim() : 'unknown',
    capability: typeof item.capability === 'string' && item.capability.trim() ? item.capability.trim() : 'text',
    inputModalities: typeof item.inputModalities === 'string' ? item.inputModalities : 'text',
    outputModalities: typeof item.outputModalities === 'string' ? item.outputModalities : 'text',
    protocols: typeof item.protocols === 'string' ? item.protocols : 'chat-completions',
    pricingUnit: typeof item.pricingUnit === 'string' ? item.pricingUnit : 'TOKEN',
    available: item.available !== false,
    billingConfigured: item.billingConfigured === true,
    minInputPricePerMillion: decimal(item.minInputPricePerMillion),
    maxInputPricePerMillion: decimal(item.maxInputPricePerMillion),
    minOutputPricePerMillion: decimal(item.minOutputPricePerMillion),
    maxOutputPricePerMillion: decimal(item.maxOutputPricePerMillion),
    minCachedPricePerMillion: decimal(item.minCachedPricePerMillion),
    maxCachedPricePerMillion: decimal(item.maxCachedPricePerMillion),
    minCacheReadPricePerMillion: decimal(item.minCacheReadPricePerMillion ?? item.minCachedPricePerMillion),
    maxCacheReadPricePerMillion: decimal(item.maxCacheReadPricePerMillion ?? item.maxCachedPricePerMillion),
    minCacheWritePricePerMillion: decimal(item.minCacheWritePricePerMillion),
    maxCacheWritePricePerMillion: decimal(item.maxCacheWritePricePerMillion),
    minInputCostMultiplier: optionalDecimal(item.minInputCostMultiplier),
    maxInputCostMultiplier: optionalDecimal(item.maxInputCostMultiplier),
    minOutputCostMultiplier: optionalDecimal(item.minOutputCostMultiplier),
    maxOutputCostMultiplier: optionalDecimal(item.maxOutputCostMultiplier),
    minCacheReadCostMultiplier: optionalDecimal(item.minCacheReadCostMultiplier),
    maxCacheReadCostMultiplier: optionalDecimal(item.maxCacheReadCostMultiplier),
    minCacheWriteCostMultiplier: optionalDecimal(item.minCacheWriteCostMultiplier),
    maxCacheWriteCostMultiplier: optionalDecimal(item.maxCacheWriteCostMultiplier),
    routeCount: positiveInteger(item.routeCount, 1),
    providerCount: positiveInteger(item.providerCount, 1),
    currency: typeof item.currency === 'string' ? item.currency : 'USD',
    amountScale: positiveInteger(item.amountScale, 10_000),
    priceUnit: typeof item.priceUnit === 'string' ? item.priceUnit : 'currency_per_1m_tokens',
    priceVariesByRoute: item.priceVariesByRoute === true,
    upstreams:upstreams.length?upstreams:[primary],
    contextPricing:item.contextPricing&&typeof item.contextPricing==='object'?item.contextPricing as Record<string,unknown>:undefined,
    providerGroup:item.providerGroup&&typeof item.providerGroup==='object'?item.providerGroup as Record<string,unknown>:undefined,
    priceTiers:Array.isArray(item.priceTiers)?item.priceTiers.filter((entry):entry is Record<string,unknown>=>Boolean(entry&&typeof entry==='object')):undefined,
    unitPriceVariants:Array.isArray(item.unitPriceVariants)?item.unitPriceVariants.filter((entry):entry is Record<string,unknown>=>Boolean(entry&&typeof entry==='object')):undefined,
  }
}

function fallbackModel(publicName: string): CallableModel {
  return {
    publicName,
    displayName: publicName,
    upstreamModelName: publicName,
    type: 'platform-route',
    source: 'platform-route',
    sourceName: '平台智能路由',
    vendor: 'unknown',
    capability: 'text',
    inputModalities: 'text',
    outputModalities: 'text',
    protocols: 'chat-completions',
    pricingUnit: 'TOKEN',
    available: true,
    billingConfigured: false,
    minInputPricePerMillion: emptyPrice,
    maxInputPricePerMillion: emptyPrice,
    minOutputPricePerMillion: emptyPrice,
    maxOutputPricePerMillion: emptyPrice,
    minCachedPricePerMillion: emptyPrice,
    maxCachedPricePerMillion: emptyPrice,
    minCacheReadPricePerMillion: emptyPrice,
    maxCacheReadPricePerMillion: emptyPrice,
    minCacheWritePricePerMillion: emptyPrice,
    maxCacheWritePricePerMillion: emptyPrice,
    routeCount: 1,
    providerCount: 1,
    currency: 'USD',
    amountScale: 10_000,
    priceUnit: 'currency_per_1m_tokens',
    priceVariesByRoute: false,
    upstreams: [{code:'platform-route',name:'平台智能路由'}],
  }
}

function decimal(value: unknown): number | string {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value.trim())) return value.trim()
  return emptyPrice
}

function optionalDecimal(value: unknown): number | string | undefined {
  if (value == null) return undefined
  return decimal(value)
}

function positiveInteger(value: unknown, fallback: number): number {
  const number = Number(value)
  return Number.isInteger(number) && number > 0 ? number : fallback
}
