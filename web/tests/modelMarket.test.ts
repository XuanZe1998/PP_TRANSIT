import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { clampPage, classifyModel, pageItems } from '../src/utils/modelMarket'

const source = (path: string) => readFileSync(resolve(import.meta.dirname, '..', path), 'utf8')

describe('model marketplace helpers', () => {
  it('classifies model types with deterministic priority', () => {
    expect(classifyModel({ capability: 'reasoning' })).toBe('language')
    expect(classifyModel({ capability: 'vision', inputModalities: 'text,image' })).toBe('multimodal')
    expect(classifyModel({ capability: 'text', outputModalities: 'image' })).toBe('image')
    expect(classifyModel({ capability: 'vision', outputModalities: 'video' })).toBe('video')
    expect(classifyModel({ capability: 'transcription', inputModalities: 'audio' })).toBe('audio')
    expect(classifyModel({ capability: 'embedding', outputModalities: 'vector' })).toBe('vector')
  })

  it('clamps pages after filters or page-size changes', () => {
    expect(clampPage(5, 21, 10)).toBe(3)
    expect(clampPage(0, 0, 20)).toBe(1)
    expect(pageItems([1, 2, 3, 4, 5], 2, 2)).toEqual([3, 4])
    expect(pageItems([1, 2, 3], 9, 2)).toEqual([3])
  })

  it('resets pagination when filters or page size change', () => {
    const market = source('src/views/ModelMarket.vue')
    expect(market).toContain(':page-sizes="[10, 20, 50]"')
    expect(market).toContain('function handlePageSizeChange() { page.value = 1')
    expect(market).toContain('watch(filters, () => { if (hydrating) return; page.value = 1')
    expect(market).toContain('const safePage = clampPage(page.value, total.value, pageSize.value)')
  })

  it('exposes unified facets, public-only pricing and channel comparison', () => {
    const market = source('src/views/ModelMarket.vue')
    const pricing = source('src/components/ModelSalePricing.vue')
    const comparison = source('src/components/ModelPriceComparisonDialog.vue')
    const consoleView = source('src/views/UserConsole.vue')

    expect(market).toContain("title: '渠道 / 路由'")
    expect(market).toContain("title: '模型发布方'")
    expect(market).toContain("'/api/public/models/facets'")
    expect(market).toContain('ModelPriceComparisonDialog')
    expect(market).toContain('<el-drawer v-model="filtersOpen"')
    expect(market).toContain('public-only')
    expect(pricing).toContain('官方参考价')
    expect(pricing).toContain('折后采购价')
    expect(pricing).toContain('本站售价')
    expect(pricing).toContain('v-if="!publicOnly"')
    expect(pricing).toContain('图片分辨率价格')
    expect(comparison).toContain('采购成本不会在模型广场展示')
    expect(comparison).not.toContain('sourcePrice')
    expect(consoleView).toContain("model.providerGroup?.channel === 'AiAPIBank'")
  })

  it('keeps only the hero section on the home route and lazy-loads public pages', () => {
    const home = source('src/views/HomePage.vue')
    const router = source('src/router/index.ts')
    expect(home.match(/<section\b/g)).toHaveLength(1)
    expect(router).toContain("const ModelMarket=()=>import('@/views/ModelMarket.vue')")
    expect(router).toContain("const PricingPage=()=>import('@/views/PricingPage.vue')")
    expect(router).toContain("const DocsPage=()=>import('@/views/DocsPage.vue')")
  })
})
