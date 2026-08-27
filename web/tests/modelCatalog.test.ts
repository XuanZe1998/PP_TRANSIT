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

  it('renders a concise scope label', () => {
    expect(modelScopeLabel(null)).toBe('全部可用模型')
    expect(modelScopeLabel('*')).toBe('全部可用模型')
    expect(modelScopeLabel('gpt-4o-mini, deepseek-chat')).toBe('gpt-4o-mini, deepseek-chat')
  })
})
