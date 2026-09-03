<template>
  <section class="model-sale-pricing" :class="{ compact }">
    <header>
      <strong>销售价格</strong>
      <span>{{ unitLabel }}</span>
    </header>
    <div v-if="freePreview" class="pricing-free">
      <strong>免费开发预览</strong>
      <span>{{ pricingMessage || '非生产服务，不承诺生产 SLA；仍受配额和限流约束。' }}</span>
    </div>
    <div v-else-if="!billingConfigured" class="pricing-pending">
      <strong>{{ pricingStatus === 'PENDING' ? '价格待配置' : '暂不可计费' }}</strong>
      <span>{{ pricingMessage || `尚未设置${unitLabel}销售价格。` }}</span>
    </div>
    <div v-else-if="pricingUnit !== 'TOKEN'" class="unit-sale-price">
      <span>本站售价</span><strong>{{ salePrice(unitSalePrice) }}</strong><small>{{ unitLabel }}</small>
    </div>
    <div v-else class="sale-price-grid">
      <article v-for="item in dimensions" :key="item.key">
        <span>{{ item.label }}</span>
        <strong>{{ salePrice(item.price) }}</strong>
        <small v-if="rebateBps">返利后预计 {{ netPrice(item.price) }}</small>
      </article>
    </div>
    <div v-if="pricingUnit === 'TOKEN' && contextPricing.enabled" class="context-price-note">
      <strong>长上下文价格 · 实际输入 &gt; {{ Number(contextPricing.thresholdTokens || 0).toLocaleString() }} Token</strong>
      <span>输入 {{ salePrice(contextPricing.longInputPrice) }} / 输出 {{ salePrice(contextPricing.longOutputPrice) }}；缓存价格不翻倍。</span>
    </div>
    <details v-if="priceTiers.length" class="source-price-details">
      <summary>{{ publicOnly ? '查看阶梯售价' : '查看官方参考价、采购价与本站售价' }}</summary>
      <article v-for="(tier, index) in priceTiers" :key="`${tier.label || 'tier'}-${index}`" class="price-tier">
        <header><strong>{{ tier.label || `挡位 ${index + 1}` }}</strong><span>{{ tierRange(tier) }}</span></header>
        <div :class="['price-matrix', 'matrix-head', { 'public-matrix': publicOnly }]">
          <b>维度</b><template v-if="!publicOnly"><b>官方参考价</b><b>折后采购价</b></template><b>本站售价</b>
        </div>
        <div v-for="dimension in tierDimensions(tier)" :key="dimension.key" :class="['price-matrix', { 'public-matrix': publicOnly }]">
          <span>{{ dimension.label }}</span><template v-if="!publicOnly"><span>{{ salePrice(dimension.official) }}</span>
          <span>{{ salePrice(dimension.source) }}</span></template><strong>{{ salePrice(dimension.sale) }}</strong>
        </div>
      </article>
    </details>
    <details v-if="unitPriceVariants.length" class="source-price-details">
      <summary>查看图片分辨率价格</summary>
      <div :class="['image-price-row', 'matrix-head', { 'public-matrix': publicOnly }]"><b>分辨率</b><b v-if="!publicOnly">折后采购价</b><b>本站售价</b></div>
      <div v-for="variant in unitPriceVariants" :key="variant.resolution" :class="['image-price-row', { 'public-matrix': publicOnly }]">
        <span>{{ variant.resolution }}</span><span v-if="!publicOnly">{{ salePrice(variant.sourcePrice) }} / 张</span><strong>{{ salePrice(variant.sale) }} / 张</strong>
      </div>
    </details>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ model: Record<string, any>; compact?: boolean; rebateBps?: number; publicOnly?: boolean }>(), { compact: false, rebateBps: 0, publicOnly: false })
const rebateBps = computed(() => Math.max(0, Math.min(10000, Number(props.rebateBps || 0))))

const pricingUnit = computed(() => String(props.model.pricing?.unit || props.model.pricingUnit || 'TOKEN').toUpperCase())
const billingMode = computed(() => String(props.model.pricing?.billingMode || props.model.billingMode || 'PAID').toUpperCase())
const pricingStatus = computed(() => String(props.model.pricing?.status || props.model.pricingStatus || 'PENDING').toUpperCase())
const pricingMessage = computed(() => String(props.model.pricing?.message || props.model.pricingMessage || ''))
const unitSalePrice = computed(() => props.model.pricing?.saleUnitPrice ?? props.model.saleUnitPrice)
const unitLabel = computed(() => props.model.pricing?.unitLabel || ({ TOKEN: 'USD / 1M Token', SECOND: 'USD / 秒', IMAGE: 'USD / 张', MINUTE: 'USD / 分钟', CHARACTER: 'USD / 千字符', TASK: 'USD / 次' } as Record<string, string>)[pricingUnit.value] || 'USD / 单位')
const freePreview = computed(() => billingMode.value === 'FREE_PREVIEW' || pricingStatus.value === 'FREE_PREVIEW')
const contextPricing = computed(() => props.model.contextPricing || {})
const priceTiers = computed<Array<Record<string, any>>>(() => Array.isArray(props.model.priceTiers) ? props.model.priceTiers : [])
const unitPriceVariants = computed<Array<Record<string, any>>>(() => Array.isArray(props.model.unitPriceVariants) ? props.model.unitPriceVariants : [])
const billingConfigured = computed(() => props.model.billingConfigured !== false && (pricingUnit.value === 'TOKEN'
  ? decimal(props.model.maxInputPricePerMillion ?? props.model.minInputPricePerMillion) > 0
  : decimal(unitSalePrice.value) > 0))

const dimensions = computed(() => [
  { key: 'input', label: '输入 / 未命中', price: props.model.maxInputPricePerMillion ?? props.model.minInputPricePerMillion },
  { key: 'output', label: '输出', price: props.model.maxOutputPricePerMillion ?? props.model.minOutputPricePerMillion },
  { key: 'cache-read', label: '缓存命中', price: props.model.maxCacheReadPricePerMillion ?? props.model.minCacheReadPricePerMillion },
  { key: 'cache-write', label: '缓存写入', price: props.model.maxCacheWritePricePerMillion ?? props.model.minCacheWritePricePerMillion },
])

function decimal(value: unknown) {
  const number = Number(value ?? 0)
  return Number.isFinite(number) ? number : 0
}

function salePrice(value: unknown) {
  const price = decimal(value)
  return price > 0
    ? `$${price.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 6 })}`
    : '不计费'
}
function netPrice(value: unknown) {
  const price = decimal(value) * (10000 - rebateBps.value) / 10000
  return price > 0 ? `$${price.toLocaleString('en-US', { maximumFractionDigits: 6 })}` : '不计费'
}
function tierRange(tier: Record<string, any>) {
  const min = Number(tier.minTokens || 0)
  const max = tier.maxTokens == null ? null : Number(tier.maxTokens)
  return max == null ? `${min.toLocaleString()} Token 以上` : `${min.toLocaleString()}–${max.toLocaleString()} Token`
}
function tierDimensions(tier: Record<string, any>) {
  const rows = [
    ['input', '输入'], ['output', '输出'], ['cacheRead', '缓存读取'], ['cacheWrite', '缓存写入'],
    ['cacheWrite1h', '缓存写入（1小时）'], ['imageInput', '图像输入'], ['imageOutput', '图像输出'], ['perRequest', '按请求'],
  ] as const
  return rows.map(([key, label]) => ({ key, label, official: tier.official?.[key], source: tier.sourcePrice?.[key], sale: tier.sale?.[key] }))
    .filter(row => [row.official, row.source, row.sale].some(value => decimal(value) > 0))
}
</script>

<style scoped>
.model-sale-pricing{display:grid;gap:10px;min-width:0;padding:12px;border:1px solid #dce7e0;border-radius:10px;background:#fbfdfb}.model-sale-pricing header{display:flex;align-items:center;justify-content:space-between;gap:10px}.model-sale-pricing header strong{color:#263c30;font-size:13px}.model-sale-pricing header span{color:#78867d;font-size:10px}.pricing-pending{display:grid;gap:4px;padding:10px;border:1px dashed #aac5eb;border-radius:8px;background:#f2f7ff}.pricing-pending strong{color:#205493;font-size:12px}.pricing-pending span{color:#657b96;font-size:10px}.sale-price-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}.sale-price-grid article{display:grid;min-width:0;gap:3px;padding:8px;border-radius:8px;background:#f2f7f3}.sale-price-grid span,.sale-price-grid small{color:#6c7c72;font-size:10px}.sale-price-grid small{color:#08734f}.sale-price-grid strong{overflow-wrap:anywhere;color:#20372a;font-family:"JetBrains Mono","Cascadia Code",monospace;font-size:12px}.compact{padding:10px}.compact .sale-price-grid article{padding:7px}@media(max-width:520px){.sale-price-grid{grid-template-columns:minmax(0,1fr)}}
.pricing-free{display:grid;gap:4px;padding:10px;border:1px solid #9ddbc4;border-radius:8px;background:#edfff7}.pricing-free strong{color:#08734f;font-size:13px}.pricing-free span{color:#3e7562;font-size:10px;line-height:1.5}.unit-sale-price{display:grid;grid-template-columns:1fr auto;align-items:end;gap:3px 12px;padding:11px;border-radius:8px;background:#f2f7f3}.unit-sale-price span,.unit-sale-price small{color:#6c7c72;font-size:10px}.unit-sale-price strong{color:#173b29;font-family:"JetBrains Mono","Cascadia Code",monospace;font-size:18px}.unit-sale-price small{grid-column:1/-1}
.context-price-note{display:grid;gap:4px;padding:9px;border:1px solid #bfd4f3;border-radius:8px;background:#f1f7ff}.context-price-note strong{color:#164f97;font-size:11px}.context-price-note span{color:#526f91;font-size:10px;line-height:1.5}
.source-price-details{border-top:1px solid #e0e8e3;padding-top:8px}.source-price-details summary{cursor:pointer;color:#315d48;font-size:11px;font-weight:700}.price-tier{display:grid;gap:5px;margin-top:8px;padding:8px;border-radius:8px;background:#f7faf8}.price-tier header{display:flex;justify-content:space-between}.price-matrix{display:grid;grid-template-columns:.85fr repeat(3,1fr);gap:5px;align-items:center;font-size:9px}.price-matrix span,.price-matrix strong,.price-matrix b{overflow-wrap:anywhere}.matrix-head{color:#718077}.image-price-row{display:grid;grid-template-columns:.6fr 1fr 1fr;gap:6px;margin-top:7px;font-size:10px}.market-public-id{font-family:"JetBrains Mono","Cascadia Code",monospace}
.price-matrix.public-matrix,.image-price-row.public-matrix{grid-template-columns:1fr 1fr}
</style>
