import { describe, expect, it } from 'vitest'
import {
  modelScopeLabel,
  modelsAllowedForToken,
  normalizeModelCatalog,
} from '../src/utils/modelCatalog'

describe('model catalog helpers', () => {
  it('normalizes the callable catalog and keeps legacy model compatibility', () => {
    const catalog = normalizeModelCatalog([
      {
        publicName: 'gpt-4o-mini',
        type: 'openai',
        minInputPricePerMillion: '1.25',
        minOutputPricePerMillion: 4,
        minInputCostMultiplier: '0.55',
        routeCount: 2,
      },
      { publicName: '  ', type: 'invalid' },
    ], ['gpt-4o-mini', 'deepseek-chat'])

    expect(catalog.map(item => item.publicName)).toEqual(['deepseek-chat', 'gpt-4o-mini'])
    expect(catalog[1]).toMatchObject({
      type: 'platform-route',
      sourceName: '平台智能路由',
      minInputPricePerMillion: '1.25',
      minInputCostMultiplier: '0.55',
      currency: 'USD',
      routeCount: 2,
    })
    expect(JSON.stringify(catalog[1])).not.toContain('openai')
  })

  it('filters callable models using the selected API Key scope', () => {
    const catalog = normalizeModelCatalog(null, ['gpt-4o-mini', 'DeepSeek-Chat'])

    expect(modelsAllowedForToken(catalog, { allowedModels: 'deepseek-chat' })
      .map(item => item.publicName)).toEqual(['DeepSeek-Chat'])
    expect(modelsAllowedForToken(catalog, { allowedModels: '*' })).toHaveLength(2)
    expect(modelsAllowedForToken(catalog, { allowedModels: '' })).toHaveLength(2)
  })

  it('preserves AiAPIBank group and price matrices while keeping the path model id callable', () => {
    const [model] = normalizeModelCatalog([{
      publicName: 'aiapibank/gpt-low/gpt-5.1',
      displayName: 'gpt-5.1 · GPT 低倍率',
      upstreamModelName: 'gpt-5.1',
      upstreams: [{ code: 'aiapibank', name: 'AiAPIBank' }],
      providerGroup: { slug: 'gpt-low', name: 'GPT 低倍率', platform: 'OpenAI', resolvedRateMultiplier: '0.55' },
      priceTiers: [{ label: '0-272K', official: { input: '2.5' }, sourcePrice: { input: '1.375' }, sale: { input: '1.5125' } }],
      unitPriceVariants: [{ resolution: '1K', sourcePrice: '0.05', sale: '0.055', unit: '张' }],
    }], [])

    expect(model).toMatchObject({
      publicName: 'aiapibank/gpt-low/gpt-5.1',
      displayName: 'gpt-5.1 · GPT 低倍率',
      upstreamModelName: 'gpt-5.1',
      sourceName: 'AiAPIBank',
      providerGroup: { slug: 'gpt-low', resolvedRateMultiplier: '0.55' },
    })
    expect(model.priceTiers?.[0]).toMatchObject({ sale: { input: '1.5125' } })
    expect(model.unitPriceVariants?.[0]).toMatchObject({ resolution: '1K', sale: '0.055' })
  })

  it('renders a concise scope label', () => {
    expect(modelScopeLabel(null)).toBe('全部可用模型')
    expect(modelScopeLabel('*')).toBe('全部可用模型')
    expect(modelScopeLabel('gpt-4o-mini, deepseek-chat')).toBe('gpt-4o-mini, deepseek-chat')
  })
})
