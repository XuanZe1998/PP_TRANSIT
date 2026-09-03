export type ModelCategory = 'language' | 'multimodal' | 'image' | 'video' | 'audio' | 'vector'

export type ModelCategoryInput = {
  capability?: string | null
  inputModalities?: string | null
  outputModalities?: string | null
}

export type PublicUpstream = { code: string; name: string; badgeText?: string; badgeColor?: string }
export type PublicModelOffer = ModelCategoryInput & {
  publicName: string
  displayName?: string
  upstreamModelName?: string
  comparisonKey?: string
  publisherCode?: string
  publisherName?: string
  category?: ModelCategory
  routeCode?: string
  routeName?: string
  planCode?: string
  planName?: string
  displayPriority?: number
  source?: string
  sources?: string
  sourceName?: string
  vendor?: string
  protocols?: string
  pricingUnit?: string
  billingMode?: string
  pricingStatus?: string
  pricingMessage?: string
  pricingVerifiedAt?: string
  available?: boolean
  billingConfigured?: boolean
  upstreams?: PublicUpstream[]
  minInputPricePerMillion?: number
  maxInputPricePerMillion?: number
  minOutputPricePerMillion?: number
  maxOutputPricePerMillion?: number
  minCacheReadPricePerMillion?: number
  maxCacheReadPricePerMillion?: number
  minCacheWritePricePerMillion?: number
  maxCacheWritePricePerMillion?: number
  saleUnitPrice?: number
  pricing?: Record<string, any>
  contextPricing?: Record<string, any>
  providerGroup?: Record<string, any>
  priceTiers?: Array<Record<string, any>>
  unitPriceVariants?: Array<Record<string, any>>
}

export type ModelFacetOption = { value: string; label: string; count: number }
export type ModelFacets = Record<string, ModelFacetOption[]>
export type ModelComparison = { comparisonKey: string; displayName: string; comparableCount: number; offers: PublicModelOffer[] }

export const modelCategoryOptions: Array<{ value: ModelCategory; label: string }> = [
  { value: 'language', label: '大语言模型' },
  { value: 'multimodal', label: '多模态模型' },
  { value: 'image', label: '图像模型' },
  { value: 'video', label: '视频模型' },
  { value: 'audio', label: '音频模型' },
  { value: 'vector', label: '向量模型' }
]

const values = (raw?: string | null) => String(raw || '')
  .toLowerCase()
  .split(/[,\s]+/)
  .map(value => value.trim())
  .filter(Boolean)

export function classifyModel(model: ModelCategoryInput): ModelCategory {
  const capability = String(model.capability || 'text').trim().toLowerCase()
  const inputs = values(model.inputModalities)
  const outputs = values(model.outputModalities)

  if (capability === 'video' || outputs.includes('video')) return 'video'
  if (capability === 'image' || outputs.includes('image')) return 'image'
  if (['speech', 'transcription', 'music', 'audio'].includes(capability)
    || inputs.includes('audio') || outputs.includes('audio') || outputs.includes('music')) return 'audio'
  if (capability === 'vision' || inputs.filter(value => ['text', 'image', 'audio', 'video'].includes(value)).length > 1) return 'multimodal'
  if (['embedding', 'rerank'].includes(capability) || outputs.includes('vector')) return 'vector'
  return 'language'
}

export function clampPage(page: number, total: number, pageSize: number) {
  const lastPage = Math.max(1, Math.ceil(Math.max(0, total) / Math.max(1, pageSize)))
  return Math.min(Math.max(1, page), lastPage)
}

export function pageItems<T>(items: T[], page: number, pageSize: number) {
  const safePage = clampPage(page, items.length, pageSize)
  const start = (safePage - 1) * pageSize
  return items.slice(start, start + pageSize)
}
