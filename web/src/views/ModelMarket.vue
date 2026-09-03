<template>
  <section class="site-section market-page">
    <div class="market-hero">
      <div class="market-hero-copy">
        <div class="market-hero-label"><img src="/model-icons/model-gateway.png" alt="统一模型网关" /><span>统一模型目录</span></div>
        <h1>发现、筛选并比较适合你的 AI 模型</h1>
        <p>平台智能路由、AiAPIBank 与后续渠道使用同一套模型属性和销售价格标准。</p>
      </div>
      <div class="market-stats">
        <article><span>目录模型</span><strong>{{ total }}</strong></article>
        <article><span>公开可调用</span><strong>{{ total }}</strong></article>
        <article><span>模型发布方</span><strong>{{ facets.publishers?.length || 0 }}</strong></article>
      </div>
    </div>

    <div v-if="error" class="market-fallback-note">{{ error }}</div>
    <div class="market-catalog-shell">
      <aside class="market-filter-panel">
        <header><div><span class="market-filter-icon">≡</span><h2>筛选</h2></div><button type="button" :disabled="!hasFilters" @click="clearFilters">重置</button></header>
        <div v-for="group in facetGroups" v-show="group.options.length" :key="group.key" class="market-filter-group">
          <h3>{{ group.title }}</h3>
          <div class="market-filter-options text-only">
            <button v-for="option in group.options" :key="option.value" type="button"
              :class="{ active: group.selected.includes(option.value) }" :aria-pressed="group.selected.includes(option.value)"
              @click="toggleFacet(group.key, option.value)">
              <span><em>{{ option.label }}</em></span><b>{{ option.count }}</b>
            </button>
          </div>
        </div>
      </aside>

      <el-drawer v-model="filtersOpen" title="筛选模型" direction="ltr" size="min(360px, 88vw)" class="market-filter-drawer">
        <div v-for="group in facetGroups" v-show="group.options.length" :key="group.key" class="market-filter-group">
          <h3>{{ group.title }}</h3>
          <div class="market-filter-options text-only">
            <button v-for="option in group.options" :key="option.value" type="button"
              :class="{ active: group.selected.includes(option.value) }" :aria-pressed="group.selected.includes(option.value)"
              @click="toggleFacet(group.key, option.value)">
              <span><em>{{ option.label }}</em></span><b>{{ option.count }}</b>
            </button>
          </div>
        </div>
        <template #footer><el-button :disabled="!hasFilters" @click="clearFilters">清空筛选</el-button><el-button type="primary" @click="filtersOpen = false">查看 {{ total }} 个模型</el-button></template>
      </el-drawer>

      <div ref="resultsElement" class="market-results">
        <div class="market-toolbar">
          <div class="market-result-count"><strong>{{ total }}</strong><span> 个模型</span></div>
          <el-input v-model="filters.query" clearable placeholder="搜索模型名称、发布方、套餐或能力" class="market-search" />
          <el-select v-model="filters.sort" class="market-select" aria-label="排序方式">
            <el-option label="优先级（高到低）" value="priority" />
            <el-option label="价格（低到高）" value="price_asc" />
            <el-option label="价格（高到低）" value="price_desc" />
            <el-option label="名称升序" value="name_asc" />
            <el-option label="名称降序" value="name_desc" />
            <el-option label="最近核价" value="recent" />
          </el-select>
          <el-button class="market-mobile-filter" @click="filtersOpen = true">筛选</el-button>
          <el-button class="market-refresh" :loading="loading" @click="fetchCatalog">刷新</el-button>
        </div>

        <div v-if="activeFilters.length" class="market-active-filters">
          <span>已选条件</span><button v-for="item in activeFilters" :key="`${item.key}-${item.value}`" type="button" @click="toggleFacet(item.key, item.value)">{{ item.label }} ×</button>
        </div>

        <div v-if="loading && !models.length" class="market-grid">
          <article v-for="index in 6" :key="index" class="market-card"><el-skeleton :rows="6" animated /></article>
        </div>
        <div v-else-if="models.length" class="market-grid">
          <article v-for="model in models" :key="model.publicName" class="market-card connected">
            <div class="market-card-main">
              <header>
                <div class="market-publisher">
                  <span class="market-publisher-icon"><img v-if="publisherIcon(model.publisherCode)" :src="publisherIcon(model.publisherCode)" :alt="`${model.publisherName} 图标`" /><span v-else class="market-publisher-fallback">{{ (model.publisherName || '未').slice(0, 1) }}</span></span>
                  <span><strong>{{ model.publisherName || '未声明' }}</strong><small>{{ model.routeName || '平台智能路由' }}</small></span>
                </div>
                <div class="market-card-badges"><span class="market-badge">{{ model.planName || '标准' }}</span><span class="market-badge available">已验证可调用</span></div>
              </header>
              <h3>{{ model.displayName || model.upstreamModelName || model.publicName }}</h3>
              <p class="market-public-id">{{ model.publicName }}</p>
              <p class="market-card-desc">{{ model.routeName || '平台智能路由' }} · {{ model.planName || '标准' }}，价格与可用性以当前公开目录为准。</p>
              <div class="market-tags"><span>{{ categoryLabel(model.category) }}</span><span>{{ capabilityLabel(model.capability) }}</span><span>{{ model.inputModalities || '未声明' }} → {{ model.outputModalities || '未声明' }}</span><span>{{ model.protocols || '未声明' }}</span></div>
            </div>
            <footer>
              <dl class="market-meta">
                <div><dt>渠道 / 套餐</dt><dd>{{ model.routeName || '平台智能路由' }} · {{ model.planName || '标准' }}</dd></div>
                <div><dt>类型 / 能力</dt><dd>{{ categoryLabel(model.category) }} · {{ capabilityLabel(model.capability) }}</dd></div>
                <div><dt>输入 / 输出模态</dt><dd>{{ model.inputModalities || '未声明' }} → {{ model.outputModalities || '未声明' }}</dd></div>
                <div><dt>协议 / 计费单位</dt><dd>{{ model.protocols || '未声明' }} · {{ unitLabel(model.pricingUnit) }}</dd></div>
                <div><dt>价格状态 / 核价时间</dt><dd>{{ priceStatusLabel(model) }} · {{ dateLabel(model.pricingVerifiedAt) }}</dd></div>
              </dl>
              <ModelSalePricing :model="model" :rebate-bps="customerRebateBps" compact public-only class="market-sale-pricing" />
              <div class="market-card-actions">
                <el-button @click="openComparison(model)">比价</el-button>
                <el-button @click="copyModel(model.publicName)">复制模型名</el-button>
                <el-button type="primary" @click="callModel(model)">立即调用 →</el-button>
              </div>
            </footer>
          </article>
        </div>
        <div v-else class="market-empty"><el-empty description="没有找到匹配模型"><el-button @click="clearFilters">清空筛选</el-button></el-empty></div>

        <div v-if="total" class="market-pagination">
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10, 20, 50]" :total="total"
            layout="total, sizes, prev, pager, next, jumper" background aria-label="模型列表分页" @current-change="scrollToResults" @size-change="handlePageSizeChange" />
        </div>
      </div>
    </div>

    <section class="market-guide"><div><p class="eyebrow">快速接入</p><h2>选择模型 ID，通过统一 Base URL 调用。</h2><p>模型广场只公开用户销售价格；采购成本仅管理员可见。</p><div class="hero-actions"><el-button type="primary" @click="router.push('/docs')">查看接入文档</el-button><el-button @click="router.push('/console/playground')">在线调试</el-button></div></div><pre>{{ sdkExample }}</pre></section>

    <ModelPriceComparisonDialog v-model="compareVisible" :model-id="compareTarget?.publicName || ''" :rebate-bps="customerRebateBps" />
    <ModelCallDialog v-model="callVisible" :model-id="callTarget?.publicName || ''" :capability="callTarget?.capability || 'TEXT'" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import http from '@/utils/http'
import ModelSalePricing from '@/components/ModelSalePricing.vue'
import ModelPriceComparisonDialog from '@/components/ModelPriceComparisonDialog.vue'
import ModelCallDialog from '@/components/ModelCallDialog.vue'
import { getToken } from '@/utils/auth'
import { clampPage, type ModelFacets, type PublicModelOffer } from '@/utils/modelMarket'

type PageResponse<T> = { total: number; page: number; size: number; items: T[] }
type FilterKey = 'routes' | 'publishers' | 'categories' | 'capabilities' | 'inputModalities' | 'outputModalities' | 'protocols' | 'pricingUnits' | 'plans' | 'priceStatuses'
type FilterState = Record<FilterKey, string[]> & { query: string; sort: string }
const filterDefinitions: Array<{ key: FilterKey; title: string }> = [
  { key: 'routes', title: '渠道 / 路由' }, { key: 'publishers', title: '模型发布方' }, { key: 'categories', title: '模型类型' },
  { key: 'capabilities', title: '模型能力' }, { key: 'inputModalities', title: '输入模态' }, { key: 'outputModalities', title: '输出模态' },
  { key: 'protocols', title: 'API 协议' }, { key: 'pricingUnits', title: '计费单位' }, { key: 'plans', title: '套餐 / 分组' },
  { key: 'priceStatuses', title: '价格状态' }
]

const router = useRouter(); const route = useRoute()
const loading = ref(false); const error = ref(''); const models = ref<PublicModelOffer[]>([]); const facets = ref<ModelFacets>({})
const total = ref(0); const page = ref(1); const pageSize = ref(10); const filtersOpen = ref(false); const resultsElement = ref<HTMLElement | null>(null)
const callVisible = ref(false); const callTarget = ref<PublicModelOffer | null>(null); const compareVisible = ref(false); const compareTarget = ref<PublicModelOffer | null>(null)
const customerRebateBps = ref(0); let timer: ReturnType<typeof setTimeout> | undefined; let requestVersion = 0; let hydrating = true
const filters = reactive<FilterState>({ query: '', sort: 'priority', routes: [], publishers: [], categories: [], capabilities: [], inputModalities: [], outputModalities: [], protocols: [], pricingUnits: [], plans: [], priceStatuses: [] })

const facetGroups = computed(() => filterDefinitions.map(group => ({ ...group, options: facets.value[group.key] || [], selected: filters[group.key] })))
const activeFilters = computed(() => filterDefinitions.flatMap(group => filters[group.key].map(value => ({ key: group.key, value, label: facets.value[group.key]?.find(item => item.value === value)?.label || value }))))
const hasFilters = computed(() => Boolean(filters.query || filters.sort !== 'priority' || activeFilters.value.length))
const configuredBase = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const sdkExample = `import OpenAI from "openai";\n\nconst client = new OpenAI({\n  apiKey: "YOUR_API_KEY",\n  baseURL: "${configuredBase || window.location.origin}/v1"\n});`

function requestParams(includePage = true) {
  const params: Record<string, string | number> = { sort: filters.sort }
  if (includePage) { params.page = page.value; params.size = pageSize.value }
  if (filters.query.trim()) params.query = filters.query.trim()
  for (const group of filterDefinitions) if (filters[group.key].length) params[group.key] = filters[group.key].join(',')
  return params
}
async function fetchCatalog() {
  const version = ++requestVersion; loading.value = true; error.value = ''
  try {
    const [catalog, facetResponse] = await Promise.all([
      http.get<PageResponse<PublicModelOffer>>('/api/public/models', { params: requestParams(true) }),
      http.get<ModelFacets>('/api/public/models/facets', { params: requestParams(false) })
    ])
    if (version !== requestVersion) return
    models.value = catalog.data.items || []; total.value = Number(catalog.data.total || 0); facets.value = facetResponse.data || {}
    const safePage = clampPage(page.value, total.value, pageSize.value)
    if (safePage !== page.value) page.value = safePage
  } catch { if (version === requestVersion) { models.value = []; total.value = 0; error.value = '后端模型目录暂时不可用。为避免误导，不展示未验证的模型、状态或价格。' } }
  finally { if (version === requestVersion) loading.value = false }
}
function scheduleFetch() { clearTimeout(timer); timer = setTimeout(() => { void fetchCatalog() }, 220) }
function toggleFacet(key: FilterKey, value: string) { const selected = filters[key]; const index = selected.indexOf(value); if (index >= 0) selected.splice(index, 1); else selected.push(value) }
function clearFilters() { filters.query = ''; filters.sort = 'priority'; for (const group of filterDefinitions) filters[group.key].splice(0); page.value = 1 }
function handlePageSizeChange() { page.value = 1; scrollToResults() }
function scrollToResults() { const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches; resultsElement.value?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' }) }
async function copyModel(name: string) { try { await navigator.clipboard.writeText(name); ElMessage.success('模型名已复制') } catch { ElMessage.error('复制失败，请手动复制模型名') } }
function callModel(model: PublicModelOffer) { callTarget.value = model; callVisible.value = true }
function openComparison(model: PublicModelOffer) { compareTarget.value = model; compareVisible.value = true }
async function fetchRebate() { if (!getToken()) return; try { customerRebateBps.value = Number((await http.get('/api/user/agent')).data?.customerBinding?.customer_rebate_bps || 0) } catch { customerRebateBps.value = 0 } }

function syncUrl() { const query: Record<string, string> = {}; if (filters.query) query.query = filters.query; if (filters.sort !== 'priority') query.sort = filters.sort; for (const group of filterDefinitions) if (filters[group.key].length) query[group.key] = filters[group.key].join(','); if (page.value > 1) query.page = String(page.value); if (pageSize.value !== 10) query.size = String(pageSize.value); void router.replace({ query }) }
function hydrateFromUrl() { filters.query = String(route.query.query || ''); filters.sort = String(route.query.sort || 'priority'); page.value = Math.max(1, Number(route.query.page || 1)); pageSize.value = [10, 20, 50].includes(Number(route.query.size)) ? Number(route.query.size) : 10; for (const group of filterDefinitions) filters[group.key] = String(route.query[group.key] || '').split(',').filter(Boolean); hydrating = false }

const icons: Record<string, string> = { nvidia: '/model-icons/nvidia.svg', meta: '/model-icons/meta.svg', google: '/model-icons/google.svg', mistral: '/model-icons/mistral.svg', alibaba: '/model-icons/qwen.svg', deepseek: '/model-icons/deepseek.svg', openai: '/openai-icon.svg', anthropic: '/model-icons/anthropic.svg', xai: '/model-icons/xai.svg', minimax: '/model-icons/minimax.jpg', stepfun: '/model-icons/stepfun.jpg', zai: '/model-icons/z-ai.jpg' }
function publisherIcon(code?: string | null) { return icons[String(code || '').toLowerCase()] || '' }
function categoryLabel(value?: string | null) { return ({ language: '大语言模型', multimodal: '多模态模型', image: '图像模型', video: '视频模型', audio: '音频模型', vector: '向量模型' } as Record<string, string>)[String(value || '')] || '大语言模型' }
function capabilityLabel(value?: string | null) { return ({ text: '文本', reasoning: '推理', vision: '视觉', image: '图像生成', video: '视频生成', speech: '语音合成', transcription: '语音识别', music: '音乐', embedding: '向量嵌入', rerank: '重排' } as Record<string, string>)[String(value || '').toLowerCase()] || value || '未声明' }
function unitLabel(value?: string | null) { return ({ TOKEN: 'USD / 1M Token', IMAGE: 'USD / 张', SECOND: 'USD / 秒', MINUTE: 'USD / 分钟', CHARACTER: 'USD / 千字符', TASK: 'USD / 次' } as Record<string, string>)[String(value || 'TOKEN').toUpperCase()] || value || '未声明' }
function priceStatusLabel(model: PublicModelOffer) { return model.billingMode === 'FREE_PREVIEW' ? '免费预览' : model.billingConfigured ? '价格已核验' : '价格待配置' }
function dateLabel(value?: string | null) { if (!value) return '未记录'; const date = new Date(value); return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('zh-CN') }

watch(filters, () => { if (hydrating) return; page.value = 1; syncUrl(); scheduleFetch() }, { deep: true })
watch([page, pageSize], () => { if (hydrating) return; syncUrl(); scheduleFetch() })
onMounted(() => { hydrateFromUrl(); void Promise.all([fetchCatalog(), fetchRebate()]) })
</script>
