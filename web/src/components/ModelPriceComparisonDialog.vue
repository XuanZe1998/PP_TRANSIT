<template>
  <el-dialog v-model="visible" width="min(1120px, 94vw)" class="model-compare-dialog" destroy-on-close>
    <template #header>
      <div class="compare-title"><span>渠道比价</span><strong>{{ comparison?.displayName || modelId }}</strong></div>
    </template>
    <div v-loading="loading" class="compare-body">
      <el-alert v-if="error" type="error" :closable="false" :title="error" />
      <el-empty v-else-if="!loading && !comparison" description="暂无比价数据" />
      <template v-else-if="comparison">
        <el-alert v-if="comparison.comparableCount < 2" type="info" :closable="false"
          title="当前只有一个公开可用报价，暂无其他渠道可比。" />
        <div class="compare-scroll">
          <table class="compare-table">
            <thead><tr><th>比较项</th><th v-for="offer in comparison.offers" :key="offer.publicName">
              <strong>{{ offer.routeName || offer.sourceName }}</strong><span>{{ offer.planName || '标准' }}</span>
            </th></tr></thead>
            <tbody>
              <tr><th>发布方</th><td v-for="offer in comparison.offers" :key="offer.publicName">{{ offer.publisherName || '未声明' }}</td></tr>
              <tr><th>API 模型 ID</th><td v-for="offer in comparison.offers" :key="offer.publicName"><code>{{ offer.publicName }}</code></td></tr>
              <tr><th>能力 / 模态</th><td v-for="offer in comparison.offers" :key="offer.publicName">{{ offer.capability || '未声明' }} · {{ offer.inputModalities || '未声明' }} → {{ offer.outputModalities || '未声明' }}</td></tr>
              <tr><th>协议 / 计费</th><td v-for="offer in comparison.offers" :key="offer.publicName">{{ offer.protocols || '未声明' }} · {{ unitLabel(offer.pricingUnit) }}</td></tr>
              <tr v-for="row in priceRows" :key="row.key"><th>{{ row.label }}</th>
                <td v-for="offer in comparison.offers" :key="offer.publicName" :class="{ cheapest: isLowest(row, offer) }">
                  <strong>{{ displayPrice(row, offer) }}</strong><small v-if="isLowest(row, offer)">最低价</small>
                </td>
              </tr>
              <tr><th>价格状态</th><td v-for="offer in comparison.offers" :key="offer.publicName">
                <el-tag :type="offer.billingMode === 'FREE_PREVIEW' ? 'info' : offer.billingConfigured ? 'success' : 'warning'" size="small">
                  {{ offer.billingMode === 'FREE_PREVIEW' ? '免费开发预览' : offer.billingConfigured ? '已核验' : '待配置' }}
                </el-tag>
              </td></tr>
            </tbody>
          </table>
        </div>
        <p class="compare-note">仅比较本站公开销售价格{{ rebateBps ? '，已应用当前账号返利' : '' }}；采购成本不会在模型广场展示。免费开发预览不参与生产最低价计算。</p>
      </template>
    </div>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import http from '@/utils/http'
import type { ModelComparison, PublicModelOffer } from '@/utils/modelMarket'

const props = withDefaults(defineProps<{ modelValue: boolean; modelId: string; rebateBps?: number }>(), { rebateBps: 0 })
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()
const visible = computed({ get: () => props.modelValue, set: value => emit('update:modelValue', value) })
const loading = ref(false)
const error = ref('')
const comparison = ref<ModelComparison | null>(null)
type PriceRow = { key: string; label: string; unit: string; values: Record<string, number | null> }

const priceRows = computed<PriceRow[]>(() => {
  if (!comparison.value) return []
  const rows = new Map<string, PriceRow>()
  const add = (key: string, label: string, unit: string, offer: PublicModelOffer, value: unknown) => {
    if (!rows.has(key)) rows.set(key, { key, label, unit, values: {} })
    const amount = Number(value)
    const rebate = Math.max(0, Math.min(10000, Number(props.rebateBps || 0)))
    rows.get(key)!.values[offer.publicName] = Number.isFinite(amount) && amount > 0 ? amount * (10000 - rebate) / 10000 : null
  }
  for (const offer of comparison.value.offers) {
    const unit = String(offer.pricingUnit || offer.pricing?.unit || 'TOKEN').toUpperCase()
    if (unit === 'TOKEN') {
      add('base:input', '输入 / 1M Token', unit, offer, offer.maxInputPricePerMillion ?? offer.minInputPricePerMillion)
      add('base:output', '输出 / 1M Token', unit, offer, offer.maxOutputPricePerMillion ?? offer.minOutputPricePerMillion)
      add('base:cache-read', '缓存读取 / 1M Token', unit, offer, offer.maxCacheReadPricePerMillion ?? offer.minCacheReadPricePerMillion)
      add('base:cache-write', '缓存写入 / 1M Token', unit, offer, offer.maxCacheWritePricePerMillion ?? offer.minCacheWritePricePerMillion)
    } else add(`unit:${unit}`, `按${unitLabel(unit)}计费`, unit, offer, offer.pricing?.saleUnitPrice ?? offer.saleUnitPrice)
    for (const tier of offer.priceTiers || []) {
      const range = tierRange(tier)
      for (const [dimension, label] of [['input', '输入'], ['output', '输出'], ['cacheRead', '缓存读取'], ['cacheWrite', '缓存写入'], ['cacheWrite1h', '缓存写入（1小时）'], ['imageInput', '图像输入'], ['imageOutput', '图像输出'], ['perRequest', '按请求']] as const) {
        add(`tier:${range}:${dimension}`, `${range} · ${label}`, 'TOKEN', offer, tier.sale?.[dimension])
      }
    }
    for (const variant of offer.unitPriceVariants || []) add(`image:${variant.resolution}`, `${variant.resolution} 图片`, 'IMAGE', offer, variant.sale)
  }
  return [...rows.values()].filter(row => Object.values(row.values).some(value => value != null))
})

function isLowest(row: PriceRow, offer: PublicModelOffer) {
  if (String(offer.billingMode || '').toUpperCase() === 'FREE_PREVIEW') return false
  const value = row.values[offer.publicName]
  if (value == null) return false
  const eligible = comparison.value?.offers.filter(item => String(item.billingMode || '').toUpperCase() !== 'FREE_PREVIEW')
    .map(item => row.values[item.publicName]).filter((amount): amount is number => amount != null) || []
  return eligible.length > 1 && value === Math.min(...eligible)
}
function displayPrice(row: PriceRow, offer: PublicModelOffer) {
  const value = row.values[offer.publicName]
  return value == null ? '—' : `$${value.toLocaleString('en-US', { maximumFractionDigits: 6 })}`
}
function unitLabel(unit?: string) {
  return ({ TOKEN: 'Token', IMAGE: '张', SECOND: '秒', MINUTE: '分钟', CHARACTER: '千字符', TASK: '次' } as Record<string, string>)[String(unit || 'TOKEN').toUpperCase()] || String(unit || '单位')
}
function tierRange(tier: Record<string, any>) {
  const min = Number(tier.minTokens || 0); const max = tier.maxTokens == null ? null : Number(tier.maxTokens)
  return max == null ? `${min.toLocaleString()}+ Token` : `${min.toLocaleString()}–${max.toLocaleString()} Token`
}
async function load() {
  if (!props.modelId || !props.modelValue) return
  loading.value = true; error.value = ''; comparison.value = null
  try { comparison.value = (await http.get<ModelComparison>('/api/public/model-comparisons', { params: { model: props.modelId } })).data }
  catch { error.value = '比价数据暂时不可用，请稍后重试。' }
  finally { loading.value = false }
}
watch(() => [props.modelValue, props.modelId], () => { void load() }, { immediate: true })
</script>

<style scoped>
.compare-title{display:grid;gap:3px}.compare-title span{color:#718077;font-size:12px}.compare-title strong{color:#173b29;font-size:20px}.compare-body{min-height:180px}.compare-scroll{overflow:auto;margin-top:12px}.compare-table{width:100%;min-width:760px;border-collapse:separate;border-spacing:0;font-size:12px}.compare-table th,.compare-table td{padding:11px;border-right:1px solid #e2e9e4;border-bottom:1px solid #e2e9e4;text-align:left;vertical-align:top}.compare-table thead th{position:sticky;top:0;background:#f3f8f5;color:#294c39}.compare-table th:first-child{position:sticky;left:0;z-index:2;min-width:165px;background:#f7faf8}.compare-table thead th:first-child{z-index:3}.compare-table thead th:not(:first-child){min-width:190px}.compare-table thead strong,.compare-table thead span{display:block}.compare-table thead span{margin-top:4px;color:#728078;font-size:10px}.compare-table code{overflow-wrap:anywhere;color:#345746}.compare-table td.cheapest{background:#edfff6}.compare-table td small{display:block;margin-top:3px;color:#08734f;font-weight:700}.compare-note{margin:12px 0 0;color:#718077;font-size:11px;line-height:1.6}
</style>
