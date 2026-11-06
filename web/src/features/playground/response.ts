import type { AmountUnits } from '@/utils/money'

export type PlaygroundUsage = {
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  estimated: boolean
  priceTier: string
  saleGroupName: string
  priceUnit: string
  priceSuffix: string
  inputPricePerMillion: number | string
  outputPricePerMillion: number | string
  cachedPricePerMillion: number | string
  cacheReadPricePerMillion: number | string
  cacheWritePricePerMillion: number | string
  inputAmount: AmountUnits
  outputAmount: AmountUnits
  cachedAmount: AmountUnits
  cacheReadAmount: AmountUnits
  cacheWriteAmount: AmountUnits
  totalAmount: AmountUnits
}
export function extractPlaygroundResult(data: any) {
  const choice = data?.choices?.[0]
  const message = choice?.message
  const candidates = [
    message?.content,
    message?.reasoning_content,
    message?.reasoning,
    choice?.text,
    data?.output_text
  ]
  for (const candidate of candidates) {
    const text = extractTextContent(candidate)
    if (text) return text
  }
  return ''
}

function extractTextContent(content: any): string {
  if (typeof content === 'string') return content.trim()
  if (!Array.isArray(content)) return ''
  return content
    .map(part => typeof part === 'string' ? part : part?.text)
    .filter((part): part is string => typeof part === 'string' && Boolean(part.trim()))
    .join('\n')
    .trim()
}

export function formatPlaygroundRawResponse(data: any) {
  if (!data || typeof data !== 'object') return JSON.stringify(data ?? null, null, 2)
  const responseOnly = { ...data }
  delete responseOnly.usage
  delete responseOnly.billing
  return JSON.stringify(responseOnly, null, 2)
}

export function extractPlaygroundUsage(data: any): PlaygroundUsage | null {
  const usage = data?.usage
  const billing = data?.billing
  if (!usage || !billing) return null
  const promptTokens = Math.max(0, Number(usage.prompt_tokens || 0))
  const completionTokens = Math.max(0, Number(usage.completion_tokens || 0))
  const cachedFromDetails = Number(usage.prompt_tokens_details?.cached_tokens || 0)
    + Number(usage.cache_read_input_tokens || 0)
    + Number(usage.cache_creation_input_tokens || 0)
  const cacheReadTokens = Math.max(0,
    Number(billing.cache_read_tokens ?? usage.prompt_tokens_details?.cached_tokens ?? usage.cache_read_input_tokens ?? 0))
  const cacheWriteTokens = Math.max(0,
    Number(billing.cache_write_tokens ?? usage.cache_creation_input_tokens ?? 0))
  return {
    promptTokens,
    completionTokens,
    cachedTokens: Math.max(0, Number(billing.cached_tokens ?? cachedFromDetails)),
    cacheReadTokens,
    cacheWriteTokens,
    estimated: Boolean(usage.estimated),
    priceTier: String(billing.price_tier || '默认挡位'),
    saleGroupName: String(billing.sale_group_name || '本站售价'),
    priceUnit: String(billing.price_unit || 'M'),
    priceSuffix: String(billing.price_suffix || 'USD / 1M Token'),
    inputPricePerMillion: billing.input_price_per_million ?? 0,
    outputPricePerMillion: billing.output_price_per_million ?? 0,
    cachedPricePerMillion: billing.cached_price_per_million ?? 0,
    cacheReadPricePerMillion: billing.cache_read_price_per_million ?? billing.cached_price_per_million ?? 0,
    cacheWritePricePerMillion: billing.cache_write_price_per_million ?? 0,
    inputAmount: billing.input_amount ?? 0,
    outputAmount: billing.output_amount ?? 0,
    cachedAmount: billing.cached_amount ?? 0,
    cacheReadAmount: billing.cache_read_amount ?? 0,
    cacheWriteAmount: billing.cache_write_amount ?? 0,
    totalAmount: billing.total_amount ?? 0
  }
}
