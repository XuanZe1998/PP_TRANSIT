<template>
  <section class="site-section market-page">
    <div class="market-hero">
      <div class="market-hero-copy">
        <div class="market-hero-label">
          <img src="/model-icons/model-gateway.png" alt="统一模型网关" />
          <span>统一模型目录</span>
        </div>
        <h1>发现并调用适合你的 AI 模型</h1>
        <p>展示已验证的模型目录，并按上游、模型类型、发布方和能力筛选。</p>
      </div>
      <div class="market-stats">
        <article><span>目录模型</span><strong>{{ models.length }}</strong></article>
        <article><span>公开可调用</span><strong>{{ connectedCount }}</strong></article>
        <article><span>模型发布方</span><strong>{{ publisherOptions.length }}</strong></article>
      </div>
    </div>

    <div v-if="error" class="market-fallback-note">{{ error }}</div>

    <div class="market-catalog-shell">
      <aside :class="['market-filter-panel', { 'is-open': filtersOpen }]">
        <header>
          <div><span class="market-filter-icon">≡</span><h2>筛选</h2></div>
          <button type="button" :disabled="!hasFilters" @click="clearFilters">重置</button>
        </header>

        <div class="market-filter-group">
          <h3>模型类型</h3>
          <div class="market-filter-options text-only">
            <button v-for="option in categoryOptions" :key="option.value" type="button"
              :class="{ active: filters.category === option.value }" :aria-pressed="filters.category === option.value"
              @click="choose('category', option.value)">
              <span><em>{{ option.label }}</em></span><b>{{ option.count }}</b>
            </button>
          </div>
        </div>

        <div v-if="upstreamOptions.length" class="market-filter-group">
          <h3>模型上游</h3>
          <div class="market-filter-options text-only">
            <button v-for="option in upstreamOptions" :key="option.value" type="button"
              :class="{ active: filters.source === option.value }" :aria-pressed="filters.source === option.value"
              @click="choose('source', option.value)">
              <span><em>{{ option.label }}</em></span><b>{{ option.count }}</b>
            </button>
          </div>
        </div>

        <div class="market-filter-group">
          <h3>模型发布方</h3>
          <div class="market-filter-options">
            <button v-for="option in publisherOptions" :key="option.value" type="button"
              :class="{ active: filters.publisher === option.value }" :aria-pressed="filters.publisher === option.value"
              @click="choose('publisher', option.value)">
              <span>
                <img v-if="option.icon" :src="option.icon" alt="" />
                <i v-else>{{ option.label.slice(0, 1) }}</i>
                <em>{{ option.label }}</em>
              </span>
              <b>{{ option.count }}</b>
            </button>
          </div>
        </div>

        <div v-if="tagOptions.length" class="market-filter-group">
          <h3>能力与场景</h3>
          <div class="market-filter-options text-only">
            <button v-for="option in tagOptions" :key="option.value" type="button"
              :class="{ active: filters.tag === option.value }" :aria-pressed="filters.tag === option.value"
              @click="choose('tag', option.value)">
              <span><em>{{ option.label }}</em></span><b>{{ option.count }}</b>
            </button>
          </div>
        </div>
      </aside>

      <div ref="resultsElement" class="market-results">
        <div class="market-toolbar">
          <div class="market-result-count"><strong>{{ filteredModels.length }}</strong><span> 个模型</span></div>
          <el-input v-model="filters.query" clearable placeholder="搜索模型名称、发布方或能力" class="market-search" />
          <el-select v-model="filters.sort" class="market-select" aria-label="排序方式">
            <el-option label="优先级（高到低）" value="priority" />
            <el-option label="名称升序" value="name_asc" />
            <el-option label="名称降序" value="name_desc" />
            <el-option label="热度优先" value="popular" />
            <el-option label="发布方排序" value="provider" />
          </el-select>
          <el-button class="market-mobile-filter" @click="filtersOpen = !filtersOpen">{{ filtersOpen ? '收起筛选' : '筛选' }}</el-button>
          <el-button class="market-refresh" :loading="loading" @click="fetchModels">刷新</el-button>
        </div>

        <div v-if="loading && !models.length" class="market-grid">
          <article v-for="index in 5" :key="index" class="market-card"><el-skeleton :rows="4" animated /></article>
        </div>
        <div v-else-if="displayModels.length" class="market-grid">
          <article v-for="model in displayModels" :key="model.publicName" class="market-card connected">
            <div class="market-card-main">
              <header>
                <div class="market-publisher">
                  <span class="market-publisher-icon">
                    <img v-if="model.publisher.icon" :src="model.publisher.icon" :alt="`${model.publisher.label} 图标`" />
                    <span v-else class="market-publisher-fallback">{{ model.publisher.label.slice(0, 1) }}</span>
                  </span>
                  <span><strong>{{ model.publisher.label }}</strong><small>统一网关</small></span>
                </div>
                <div class="market-card-badges">
                  <span v-for="upstream in model.upstreams" :key="upstream.value" :class="['market-badge', { nvidia: upstream.value === 'nvidia' }]">{{ upstream.label }}</span>
                  <span class="market-badge available">已验证可调用</span>
                </div>
              </header>
              <h3>{{ model.publicName }}</h3>
              <p class="market-card-desc">{{ model.description }}</p>
              <div class="market-tags"><span v-for="tag in model.tags.slice(0, 4)" :key="tag">{{ tag }}</span></div>
            </div>
            <footer>
              <dl class="market-meta"><div><dt>模型类型 / 能力</dt><dd>{{ model.categoryLabel }} · {{ model.capabilityLabel }}</dd></div></dl>
              <ModelSalePricing :model="model.raw" :rebate-bps="customerRebateBps" compact class="market-sale-pricing" />
              <div class="market-card-actions">
                <el-button @click="copyModel(model.publicName)">复制模型名</el-button>
                <el-button type="primary" @click="callModel(model)">立即调用 →</el-button>
              </div>
            </footer>
          </article>
        </div>
        <div v-else class="market-empty"><el-empty description="没有找到匹配模型"><el-button @click="clearFilters">清空筛选</el-button></el-empty></div>

        <div v-if="filteredModels.length" class="market-pagination">
          <el-pagination v-model:current-page="page" v-model:page-size="pageSize"
            :page-sizes="[10, 20, 50]" :total="filteredModels.length"
            layout="total, sizes, prev, pager, next, jumper" background aria-label="模型列表分页"
            @current-change="scrollToResults" @size-change="handlePageSizeChange" />
        </div>
      </div>
    </div>

    <section class="market-guide">
      <div>
        <p class="eyebrow">快速接入</p>
        <h2>后台配置渠道和映射后，模型即可从统一 Base URL 调用。</h2>
        <p>模型广场展示目录、本站销售价格和接入状态；真实调用使用平台 API Key。</p>
        <div class="hero-actions">
          <el-button type="primary" @click="router.push('/docs')">查看接入文档</el-button>
          <el-button @click="router.push('/console/playground')">在线调试</el-button>
        </div>
      </div>
      <pre>{{ sdkExample }}</pre>
    </section>

    <ModelCallDialog v-model="callVisible" :model-id="callTarget?.publicName || ''" :capability="callTarget?.raw.capability || 'TEXT'" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'
import http from '@/utils/http'
import ModelSalePricing from '@/components/ModelSalePricing.vue'
import ModelCallDialog from '@/components/ModelCallDialog.vue'
import { getToken } from '@/utils/auth'
import { clampPage, classifyModel, modelCategoryOptions, pageItems, type ModelCategory } from '@/utils/modelMarket'

type PublicUpstream = { code: string; name: string }
type PublicModel = {
  publicName: string
  displayPriority?: number
  source?: string
  sources?: string
  sourceName?: string
  vendor?: string
  capability?: string
  inputModalities?: string
  outputModalities?: string
  available?: boolean
  upstreams?: PublicUpstream[]
  minInputPricePerMillion?: number
  maxInputPricePerMillion?: number
  minOutputPricePerMillion?: number
  maxOutputPricePerMillion?: number
  minCacheReadPricePerMillion?: number
  maxCacheReadPricePerMillion?: number
  minCacheWritePricePerMillion?: number
  maxCacheWritePricePerMillion?: number
  pricingUnit?: string
  billingMode?: string
  pricingStatus?: string
  pricingMessage?: string
  saleUnitPrice?: number
  pricing?: Record<string, unknown>
  contextPricing?: Record<string, unknown>
}
type PageResponse<T> = { total: number; page: number; size: number; items: T[] }
type Publisher = { value: string; label: string; icon: string }
type MarketModel = {
  publicName: string
  raw: PublicModel
  publisher: Publisher
  category: ModelCategory
  categoryLabel: string
  capabilityLabel: string
  description: string
  tags: string[]
  upstreams: Array<{ value: string; label: string }>
  popularity: number
  displayPriority: number
}

const router = useRouter()
const loading = ref(false)
const error = ref('')
const models = ref<MarketModel[]>([])
const filtersOpen = ref(false)
const page = ref(1)
const pageSize = ref(10)
const resultsElement = ref<HTMLElement | null>(null)
const callVisible = ref(false)
const callTarget = ref<MarketModel | null>(null)
const customerRebateBps = ref(0)
const filters = reactive({ query: '', source: '', publisher: '', category: '' as ModelCategory | '', tag: '', sort: 'priority' })

const publishers: Publisher[] = [
  { label: 'NVIDIA', value: 'nvidia', icon: '/model-icons/nvidia.svg' },
  { label: 'Meta', value: 'meta', icon: '/model-icons/meta.svg' },
  { label: 'Google', value: 'google', icon: '/model-icons/google.svg' },
  { label: 'Mistral AI', value: 'mistral', icon: '/model-icons/mistral.svg' },
  { label: 'Qwen', value: 'qwen', icon: '/model-icons/qwen.svg' },
  { label: 'DeepSeek AI', value: 'deepseek', icon: '/model-icons/deepseek.svg' },
  { label: 'OpenAI', value: 'openai', icon: '/openai-icon.svg' },
  { label: 'Anthropic', value: 'anthropic', icon: '/model-icons/anthropic.svg' },
  { label: 'xAI', value: 'xai', icon: '/model-icons/xai.svg' },
  { label: 'MiniMax', value: 'minimax', icon: '/model-icons/minimax.jpg' },
  { label: 'StepFun', value: 'stepfun', icon: '/model-icons/stepfun.jpg' },
  { label: 'Z.ai', value: 'zai', icon: '/model-icons/z-ai.jpg' },
  { label: 'BAAI', value: 'baai', icon: '' },
  { label: 'Moonshot', value: 'kimi', icon: '' },
  { label: '其他', value: 'custom', icon: '' }
]

const categoryLabels = Object.fromEntries(modelCategoryOptions.map(item => [item.value, item.label])) as Record<ModelCategory, string>
const capabilityLabels: Record<string, string> = {
  text: '文本', reasoning: '推理', vision: '视觉', image: '图像生成', video: '视频生成',
  speech: '语音合成', transcription: '语音识别', music: '音乐', embedding: '向量嵌入', rerank: '重排'
}

function publisherFor(model: PublicModel) {
  const name = model.publicName.toLowerCase()
  let key = String(model.vendor || '').toLowerCase()
  if (name.includes('claude')) key = 'anthropic'
  else if (name.includes('deepseek')) key = 'deepseek'
  else if (name.includes('llama') || name.startsWith('meta/')) key = 'meta'
  else if (name.includes('gemma') || name.includes('gemini') || name.startsWith('google/')) key = 'google'
  else if (name.includes('mistral')) key = 'mistral'
  else if (name.includes('qwen')) key = 'qwen'
  else if (name.startsWith('gpt-') || name.startsWith('openai/') || name.includes('gpt-oss')) key = 'openai'
  else if (name.includes('minimax')) key = 'minimax'
  else if (name.includes('stepfun') || name.includes('step-')) key = 'stepfun'
  else if (name.includes('glm') || name.startsWith('z-ai/')) key = 'zai'
  else if (name.includes('bge-') || name.startsWith('baai/')) key = 'baai'
  else if (name.includes('kimi')) key = 'kimi'
  else if (name.includes('nemotron') || name.startsWith('nvidia/') || name.startsWith('nv-')) key = 'nvidia'
  return publishers.find(item => item.value === key) || publishers[publishers.length - 1]
}

function enrich(model: PublicModel, index: number): MarketModel {
  const publisher = publisherFor(model)
  const category = classifyModel(model)
  const capability = String(model.capability || 'text').toLowerCase()
  const capabilityLabel = capabilityLabels[capability] || capability
  const upstreams = model.upstreams?.length
    ? model.upstreams.map(item => ({ value: item.code, label: item.name }))
    : [{ value: String(model.source || 'platform-route'), label: model.sourceName || '平台智能路由' }]
  const tags = Array.from(new Set([categoryLabels[category], capabilityLabel, publisher.label]))
  return {
    publicName: model.publicName,
    raw: model,
    publisher,
    category,
    categoryLabel: categoryLabels[category],
    capabilityLabel,
    description: `${publisher.label} 提供的${categoryLabels[category]}，通过本站统一网关接入；可用性与价格以当前公开目录为准。`,
    tags,
    upstreams,
    popularity: Math.max(1, 1000 - index),
    displayPriority: Number(model.displayPriority || 0)
  }
}

const filteredModels = computed(() => {
  const keyword = filters.query.trim().toLowerCase()
  const rows = models.value.filter(model => {
    const keywordMatched = !keyword || model.publicName.toLowerCase().includes(keyword)
      || model.publisher.label.toLowerCase().includes(keyword) || model.tags.some(tag => tag.toLowerCase().includes(keyword))
    return keywordMatched
      && (!filters.source || model.upstreams.some(item => item.value === filters.source))
      && (!filters.publisher || model.publisher.value === filters.publisher)
      && (!filters.category || model.category === filters.category)
      && (!filters.tag || model.tags.includes(filters.tag))
  })
  if (filters.sort === 'popular') return [...rows].sort((a, b) => b.popularity - a.popularity)
  if (filters.sort === 'provider') return [...rows].sort((a, b) => a.publisher.label.localeCompare(b.publisher.label) || a.publicName.localeCompare(b.publicName))
  if (filters.sort === 'name_desc') return [...rows].sort((a, b) => b.publicName.localeCompare(a.publicName))
  if (filters.sort === 'priority') return [...rows].sort((a, b) => b.displayPriority - a.displayPriority || a.publicName.localeCompare(b.publicName))
  return [...rows].sort((a, b) => a.publicName.localeCompare(b.publicName))
})
const displayModels = computed(() => pageItems(filteredModels.value, page.value, pageSize.value))
const connectedCount = computed(() => models.value.filter(model => model.raw.available !== false).length)
const hasFilters = computed(() => Boolean(filters.query || filters.source || filters.publisher || filters.category || filters.tag || filters.sort !== 'priority'))

function countedOptions<T extends { value: string; label: string }>(options: T[], values: string[]) {
  const counts = new Map<string, number>()
  values.forEach(value => counts.set(value, (counts.get(value) || 0) + 1))
  return options.map(option => ({ ...option, count: counts.get(option.value) || 0 })).filter(option => option.count > 0)
}
const categoryOptions = computed(() => countedOptions(modelCategoryOptions, models.value.map(model => model.category)))
const publisherOptions = computed(() => countedOptions(publishers, models.value.map(model => model.publisher.value)))
const upstreamOptions = computed(() => {
  const labels = new Map<string, string>()
  const values: string[] = []
  models.value.forEach(model => model.upstreams.forEach(item => { labels.set(item.value, item.label); values.push(item.value) }))
  return countedOptions([...labels].map(([value, label]) => ({ value, label })), values)
})
const tagOptions = computed(() => {
  const values = models.value.flatMap(model => model.tags.filter(tag => tag !== model.publisher.label && tag !== model.categoryLabel))
  return countedOptions(Array.from(new Set(values)).map(value => ({ value, label: value })), values).slice(0, 10)
})

const configuredBase = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
const sdkExample = `import OpenAI from "openai";\n\nconst client = new OpenAI({\n  apiKey: "YOUR_API_KEY",\n  baseURL: "${configuredBase || window.location.origin}/v1"\n});`

async function fetchModels() {
  loading.value = true
  error.value = ''
  try {
    const catalog: PublicModel[] = []
    let catalogPage = 1
    let total = 0
    do {
      const response = await http.get<PageResponse<PublicModel>>('/api/public/models', {
        params: { page: catalogPage, size: 100, sort: 'priority', availability: 'available' }
      })
      catalog.push(...(response.data?.items || []))
      total = Number(response.data?.total || catalog.length)
      catalogPage += 1
    } while (catalog.length < total && catalogPage <= 20)
    models.value = catalog.map(enrich)
    page.value = clampPage(page.value, filteredModels.value.length, pageSize.value)
  } catch {
    models.value = []
    error.value = '后端模型目录暂时不可用。为避免误导，不展示未验证的模型、状态或价格。'
  } finally {
    loading.value = false
  }
}

async function fetchRebate() {
  if (!getToken()) return
  try {
    const { data } = await http.get('/api/user/agent')
    customerRebateBps.value = Number(data?.customerBinding?.customer_rebate_bps || 0)
  } catch { customerRebateBps.value = 0 }
}

function choose(field: 'source' | 'publisher' | 'category' | 'tag', value: string) {
  const record = filters as unknown as Record<string, string>
  record[field] = record[field] === value ? '' : value
}
function clearFilters() {
  filters.query = ''; filters.source = ''; filters.publisher = ''; filters.category = ''; filters.tag = ''; filters.sort = 'priority'; page.value = 1
}
function handlePageSizeChange() { page.value = 1; scrollToResults() }
function scrollToResults() {
  const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  resultsElement.value?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
}
async function copyModel(name: string) {
  try { await navigator.clipboard.writeText(name); ElMessage.success('模型名已复制') }
  catch { ElMessage.error('复制失败，请手动复制模型名') }
}
function callModel(model: MarketModel) { callTarget.value = model; callVisible.value = true }

watch(() => [filters.query, filters.source, filters.publisher, filters.category, filters.tag, filters.sort], () => { page.value = 1 })
watch(() => filteredModels.value.length, total => { page.value = clampPage(page.value, total, pageSize.value) })
onMounted(() => { void Promise.all([fetchModels(), fetchRebate()]) })
</script>
