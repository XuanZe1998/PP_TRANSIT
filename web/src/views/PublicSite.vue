<template>
  <div class="public-site">
    <header class="site-nav">
      <div class="site-nav-inner">
        <button class="site-brand" @click="go('/')">
          <span class="site-brand-mark">A</span>
          <span>API Transit</span>
        </button>
        <nav class="site-links" aria-label="Primary navigation">
          <button :class="{ active: section === 'home' }" @click="go('/')">首页</button>
          <button :class="{ active: section === 'models' }" @click="go('/market')">模型广场</button>
          <button :class="{ active: route.path === '/studio' }" @click="go('/studio')">AI创作</button>
          <button :class="{ active: section === 'services' }" @click="go('/services')">其他服务</button>
          <button :class="{ active: section === 'pricing' }" @click="go('/pricing')">套餐价格</button>
          <button :class="{ active: section === 'docs' }" @click="go('/docs')">开发文档</button>
        </nav>
        <AccountMenu @login="openAuth('login')" @register="openAuth('register')" />
      </div>
    </header>

    <main>
      <section v-if="section === 'home'" class="site-hero">
        <div class="hero-copy">
          <p class="eyebrow">AI API 中转与模型聚合平台</p>
          <h1>统一接入主流大模型，把调用、计费和风控放到一个控制台。</h1>
          <p>
            兼容 OpenAI SDK，集中管理 OpenAI、Claude、Gemini、DeepSeek、Grok、Qwen 等模型渠道，
            支持 Key 配额、余额计费、失败切换、用量审计和团队权限。
          </p>
          <div class="hero-actions">
            <el-button type="primary" size="large" @click="openAuth('register')">立即创建账号</el-button>
            <el-button size="large" @click="go('/market')">查看模型广场</el-button>
          </div>
          <div class="hero-proof">
            <span>OpenAI SDK 兼容</span>
            <span>多供应商路由</span>
            <span>统一 API Key</span>
          </div>
        </div>

        <div class="hero-product" aria-label="API Transit product preview">
          <div class="product-toolbar">
            <span></span><span></span><span></span>
            <strong>调度能力示意（非实时数据）</strong>
          </div>
          <div class="product-grid">
            <div class="product-card wide">
              <small>当前已接入模型</small>
              <strong>{{ featuredCatalogTotal }}</strong>
              <em>来自后端公开目录</em>
            </div>
            <div class="product-card">
              <small>已接入供应商</small>
              <strong>{{ featuredProviderCount }}</strong>
              <em class="green">以实际配置为准</em>
            </div>
            <div class="route-list">
              <div v-for="route in routes" :key="route.name">
                <span :class="route.tone"></span>
                <p>
                  <strong>{{ route.name }}</strong>
                  <small>{{ route.meta }}</small>
                </p>
                <b>{{ route.status }}</b>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section v-if="section === 'home'" class="logo-strip" aria-label="Supported providers">
        <span>OpenAI</span>
        <span>Anthropic</span>
        <span>Google Gemini</span>
        <span>DeepSeek</span>
        <span>xAI Grok</span>
        <span>Qwen</span>
      </section>

      <section v-if="section === 'home'" class="site-section">
        <div class="section-head">
          <p class="eyebrow">模型广场</p>
          <h2>仅展示后端当前已启用的模型，不伪造接入状态。</h2>
          <el-button @click="go('/market')">查看全部模型</el-button>
        </div>
        <el-empty v-if="featuredLoading || featuredModels.length === 0" :description="featuredLoading ? '正在读取模型目录' : '暂无可验证的已接入模型'" />
        <div v-else class="model-grid">
          <article v-for="model in featuredModels" :key="model.publicName" class="model-tile">
            <div>
              <span :class="['model-dot', model.tone]"></span>
              <h3>{{ model.publicName }}</h3>
            </div>
            <p>{{ model.description }}</p>
            <footer>
              <span>{{ model.context }}</span>
              <strong>{{ model.rate }}</strong>
            </footer>
          </article>
        </div>
      </section>

      <ModelMarket v-if="section === 'models'" />

      <OtherServices v-if="section === 'services'" />

      <section v-if="section === 'home'" class="site-section split-band">
        <div>
          <p class="eyebrow">面向用户的工作台</p>
          <h2>Key、调试、账单和日志都在前台自助完成。</h2>
          <p>
            管理员后台负责渠道、模型映射、定价和审计；普通用户只看到与自己调用相关的入口。
          </p>
        </div>
        <div class="workflow-list">
          <div v-for="item in workflow" :key="item.title">
            <span>{{ item.step }}</span>
            <p>
              <strong>{{ item.title }}</strong>
              <small>{{ item.desc }}</small>
            </p>
          </div>
        </div>
      </section>

      <section v-if="section === 'pricing'" id="pricing" class="site-section">
        <div class="section-head">
          <p class="eyebrow">套餐价格</p>
          <h2>充值方案从后端配置读取，未配置前不展示虚假价格。</h2>
          <el-button @click="go('/pricing')">查看计费说明</el-button>
        </div>
        <p class="pricing-disclosure">
          模型输入、输出、缓存命中与写入价格统一以 USD 展示；钱包充值和实际扣款以 CNY 结算。
          下单前请以登录后钱包中的实时方案和模型账单为准。
        </p>
        <div class="pricing-grid">
          <article v-for="plan in plans" :key="plan.name" class="pricing-card">
            <h3>{{ plan.name }}</h3>
            <strong>{{ plan.price }}</strong>
            <p>{{ plan.desc }}</p>
            <el-button :type="plan.primary ? 'primary' : 'default'" @click="selectPlan">选择套餐</el-button>
          </article>
          <article v-if="shopGptEnabled" class="pricing-card product-pricing-card">
            <div class="product-pricing-cover">
              <img src="https://shopgpt.plus/assets/cache/general/image/202603191955477249694.jpg" alt="GPT RT Plus 成品号（欧洲渠道）" />
            </div>
            <h3>GPT RT Plus 成品号（欧洲渠道）</h3>
            <strong>启用后实时询价</strong>
            <p>该外部商品只有在运营方显式开启功能开关后才展示，价格以商品页服务端询价为准。</p>
            <el-button type="primary" @click="go('/item')">查看商品</el-button>
          </article>
        </div>
      </section>

      <DeveloperDocs v-if="section === 'docs'" class="site-section" />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElEmpty, ElInput, ElMessage, ElOption, ElPagination, ElSelect, ElSkeleton } from 'element-plus'
import http from '@/utils/http'
import { getToken } from '@/utils/auth'
import OtherServices from '@/views/OtherServices.vue'
import ModelSalePricing from '@/components/ModelSalePricing.vue'
import DeveloperDocs from '@/components/DeveloperDocs.vue'
import AccountMenu from '@/components/AccountMenu.vue'
import ModelCallDialog from '@/components/ModelCallDialog.vue'

type PublicModel = {
  publicName: string
  type?: string
  source?: string
  sourceName?: string
  sources?: string
  vendor?: string
  capability?: string
  available?: boolean
  billingConfigured?: boolean
  verificationStatus?: string
  verificationMessage?: string
  verifiedAt?: string
  lastSeenAt?: string
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
  pricing?: { billingMode?: string; status?: string; message?: string; unit?: string; unitLabel?: string; saleUnitPrice?: number }
  upstreams?: Array<{ code: string; name: string; badgeText?: string; badgeColor?: string }>
  contextPricing?: { enabled?: boolean; thresholdTokens?: number; multiplier?: number; baseInputPrice?: number; baseOutputPrice?: number; longInputPrice?: number; longOutputPrice?: number; message?: string }
}

type PageResponse<T> = {
  total: number
  page: number
  size: number
  items: T[]
}

type CatalogModel = {
  publicName: string
  type: string
  providerLabel: string
  description: string
  tags: string[]
  context: string
  rate: string
  tone: string
  popularity: number
  addedRank: number
  pricingLines?: string[]
  pricingModel?: PublicModel
}

type EnrichedModel = CatalogModel & {
  provider: string
  upstreams: string[]
  upstreamLabels: Record<string, string>
  status: string
  verificationStatus: string
  verificationMessage: string
  connected: boolean
  pricingLines: string[]
}

const router = useRouter()
const route = useRoute()
const isLoggedIn = ref(Boolean(getToken()))
const shopGptEnabled = import.meta.env.VITE_ENABLE_SHOPGPT === 'true'
const featuredLoading = ref(false)
const featuredCatalog = ref<EnrichedModel[]>([])
const featuredTotal = ref(0)
const featuredCatalogTotal = computed(() => featuredLoading.value ? '—' : featuredTotal.value)
const featuredProviderCount = computed(() => new Set(featuredCatalog.value.map(model => model.provider)).size)
const gatewayBaseUrl = computed(() => {
  const configured = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  return `${configured || window.location.origin}/v1`
})
const sdkExample = computed(() => `import OpenAI from "openai";

const client = new OpenAI({
  apiKey: "YOUR_API_KEY",
  baseURL: "${gatewayBaseUrl.value}"
});`)

const section = computed(() => {
  if (route.path === '/market') return 'models'
  if (route.path === '/services') return 'services'
  if (route.path === '/pricing') return 'pricing'
  if (route.path === '/docs') return 'docs'
  return 'home'
})

const go = (path: string) => router.push(path)
const openAuth = (mode: 'login' | 'register') => router.replace({ path: route.path, query: { ...route.query, auth: mode } })
const selectPlan = () => {
  // Let the route guard preserve /console/wallet as the post-login deep link.
  router.push('/console/wallet')
}
const refreshLoginState = () => {
  isLoggedIn.value = Boolean(getToken())
}

onMounted(() => {
  window.addEventListener('auth-changed', refreshLoginState)
  window.addEventListener('storage', refreshLoginState)
  void fetchFeaturedModels()
})

onBeforeUnmount(() => {
  window.removeEventListener('auth-changed', refreshLoginState)
  window.removeEventListener('storage', refreshLoginState)
})

const routes = [
  { name: '失败切换', meta: '需由管理员配置多渠道', status: '策略能力', tone: 'blue' },
  { name: '成本与权重路由', meta: '以后台模型映射为准', status: '策略能力', tone: 'green' },
  { name: '请求审计', meta: '记录 Trace ID、Token 和扣费', status: '账单能力', tone: 'orange' }
]

const curatedModels: CatalogModel[] = [
  c('gpt-5.5', 'openai', 'OpenAI', 'OpenAI 旗舰模型，适合复杂推理、专业写作、代码生成和高价值智能体任务。', ['Chat', 'Reasoning', 'Coding', 'Agent'], '1M', '8x', 'blue', 100, 1),
  c('gpt-5.4-mini', 'openai', 'OpenAI', '高频业务低延迟模型，适合客服、工具调用、摘要和批量自动化。', ['Chat', '低延迟', '工具调用'], '1M', '2x', 'blue', 98, 2),
  c('gpt-5.4-nano', 'openai', 'OpenAI', '极低成本轻量模型，适合分类、抽取、改写、路由判断和海量简单任务。', ['低成本', '分类抽取', '批量'], '1M', '0.6x', 'blue', 94, 3),
  c('gpt-5-codex-mini', 'openai', 'OpenAI', '代码和命令行工作流优化模型，适合 IDE 助手、代码审查和自动修复。', ['Coding', 'Agent', '代码审查'], '400K', '3x', 'blue', 92, 4),
  c('o4-mini', 'openai', 'OpenAI', '轻量推理模型，适合数学、代码、结构化分析和需要思考过程的任务。', ['Reasoning', '数学', '低成本'], '200K', '1.5x', 'blue', 88, 5),
  c('claude-sonnet-5', 'anthropic', 'Anthropic', '速度、智能和成本平衡较好的 Claude 模型，适合代码、长文档和企业知识工作。', ['Chat', 'Coding', '长文本'], '200K', '5x', 'green', 99, 6),
  c('claude-opus-4-8', 'anthropic', 'Anthropic', '高智能 Claude 模型，适合复杂智能体、架构设计、深度代码任务和高质量分析。', ['Reasoning', 'Coding', 'Agent'], '200K', '10x', 'green', 96, 7),
  c('claude-haiku-4-5', 'anthropic', 'Anthropic', 'Claude 低延迟模型，适合高并发对话、内容改写、轻量问答和前台功能嵌入。', ['Chat', '低延迟', '高并发'], '200K', '2x', 'green', 90, 8),
  c('gemini-3.5-flash', 'google', 'Google', '高速多模态模型，适合复杂流程、长上下文、多轮智能体和高频业务调用。', ['Chat', '多模态', 'Agent'], '1M', '2x', 'purple', 97, 9),
  c('gemini-3.1-pro-preview', 'google', 'Google', '更偏可靠推理和软件工程的 Gemini Pro 预览模型，适合复杂任务和工具调用。', ['Reasoning', 'Coding', '多模态'], '1M', '5x', 'purple', 93, 10),
  c('gemini-2.5-flash', 'google', 'Google', '成熟稳定的 Gemini Flash 模型，适合结构化输出、搜索增强和日常多模态业务。', ['Chat', '结构化输出', '搜索增强'], '1M', '1.5x', 'purple', 89, 11),
  c('deepseek-v4-pro', 'deepseek', 'DeepSeek', '中文、代码和推理综合能力强，适合复杂业务、代码生成和中文智能体场景。', ['Chat', '中文优化', '推理', '代码'], '128K', '1.2x', 'orange', 97, 12),
  c('deepseek-v4-flash', 'deepseek', 'DeepSeek', '低成本高吞吐模型，适合中文客服、批量摘要、内容处理和高频调用。', ['Chat', '中文优化', '低成本'], '128K', '0.5x', 'orange', 96, 13),
  c('deepseek-reasoner', 'deepseek', 'DeepSeek', 'DeepSeek 思考模式兼容名，适合数学、代码、复杂推理和可解释推理场景。', ['Reasoning', '数学', '代码'], '128K', '1x', 'orange', 90, 14),
  c('grok-4.3', 'xai', 'xAI', 'Grok 通用旗舰模型，适合实时信息增强、通用问答、分析和产品内对话。', ['Chat', 'Reasoning', '实时增强'], '1M', '3x', 'teal', 91, 15),
  c('grok-build', 'xai', 'xAI', '面向代码生成和开发者工作流的 Grok 模型，适合构建、调试和代码协作。', ['Coding', 'Agent', '开发者'], '1M', '4x', 'teal', 84, 16),
  c('qwen3.7-max', 'qwen', 'Qwen', '通义千问旗舰模型，适合中文复杂任务、多步推理、知识问答和企业应用。', ['Chat', '中文优化', 'Reasoning'], '1M', '2x', 'cyan', 95, 17),
  c('qwen3.7-plus', 'qwen', 'Qwen', '均衡型千问模型，适合企业问答、结构化输出、RAG 和稳定生产调用。', ['Chat', 'RAG', '结构化输出'], '1M', '1x', 'cyan', 92, 18),
  c('qwen3.6-flash', 'qwen', 'Qwen', '低延迟千问模型，适合客服、内容处理、路由、分类和大规模轻量任务。', ['Chat', '低延迟', '低成本'], '1M', '0.4x', 'cyan', 88, 19),
  c('qwen3-coder-plus', 'qwen', 'Qwen', '代码场景优化模型，适合代码补全、项目解释、单测生成和工程自动化。', ['Coding', '代码补全', '工程'], '256K', '1.5x', 'cyan', 86, 20),
  c('kimi-k2-thinking', 'kimi', 'Moonshot', '长上下文和思考任务友好，适合资料分析、中文写作、Agent 和代码任务。', ['Reasoning', '长上下文', '中文'], '256K', '1.5x', 'pink', 85, 21),
  c('glm-5.1', 'glm', 'Zhipu', 'GLM 新一代通用模型，适合中文企业应用、知识问答、工具调用和复杂任务。', ['Chat', '中文优化', '工具调用'], '128K', '1.2x', 'indigo', 82, 22),
  c('z-ai/glm-5.2', 'nvidia', 'NVIDIA / Z.ai', '通过 NVIDIA 集成接口提供的 GLM-5.2，适合中文推理、长输出和复杂业务测试。', ['Chat', 'Reasoning', '中文', 'NVIDIA'], '128K', '1.8x', 'indigo', 84, 23),
  c('google/gemma-4-31b-it', 'nvidia', 'NVIDIA / Google', '通过 NVIDIA 集成接口提供的 Gemma-4 指令模型，适合开放权重、推理和通用对话场景。', ['Chat', 'Open Weights', 'Reasoning'], '128K', '1.4x', 'purple', 81, 24),
  c('mistral-large-latest', 'mistral', 'Mistral', 'Mistral 高质量通用模型，适合企业知识、欧洲语种、函数调用和生产问答。', ['Chat', '函数调用', '企业'], '128K', '2x', 'red', 83, 25),
  c('mistral-small-latest', 'mistral', 'Mistral', '更轻量的 Mistral 模型，适合低成本问答、分类、摘要和日常文本任务。', ['Chat', '低成本', '摘要'], '128K', '0.8x', 'red', 78, 26),
  c('llama-4-scout', 'meta', 'Meta Llama', '开源生态热门模型，适合私有化部署、低成本推理和通用对话场景。', ['Open Source', 'Chat', '私有化'], '10M', '0.8x', 'gray', 80, 27),
  c('llama-4-maverick', 'meta', 'Meta Llama', '更强的 Llama 系列模型，适合私有化高质量问答、RAG 和多语言业务。', ['Open Source', 'RAG', '多语言'], '1M', '1.2x', 'gray', 79, 28)
]

const featuredModels = computed(() => featuredCatalog.value.slice(0, 4))

const workflow = [
  { step: '01', title: '创建 API Key', desc: '设置额度、过期时间、可用模型和 IP 白名单。' },
  { step: '02', title: '在线调试', desc: '选择模型并生成 cURL、JS、Python 示例。' },
  { step: '03', title: '查看账单', desc: '按 Key、模型、日期和状态追踪消耗。' }
]

const plans = [
  { name: '自助额度', price: '以钱包实时方案为准', desc: '登录后查看管理员已启用的充值方案；未启用支付时仅支持受控兑换码。', primary: true },
  { name: '企业方案', price: '当前未配置公开价格', desc: '需由运营方配置专属渠道、计费和审计规则后再对外销售。' }
]

async function fetchFeaturedModels() {
  featuredLoading.value = true
  try {
    const response = await http.get<PageResponse<PublicModel>>('/api/public/models', {
      params: { page: 1, size: 100, sort: 'name' }
    })
    featuredCatalog.value = mergeCatalog(response.data?.items || [])
    featuredTotal.value = Number(response.data?.total ?? featuredCatalog.value.length)
  } catch {
    featuredCatalog.value = []
    featuredTotal.value = 0
  } finally {
    featuredLoading.value = false
  }
}

const ModelMarket = defineComponent({
  name: 'ModelMarket',
  setup() {
    const filters = reactive({ query: '', source: '', availability: '', type: '', tag: '', sort: 'name' })
    const filtersOpen = ref(false)
    const page = ref(1)
    const size = 10
    const total = ref(0)
    const loading = ref(false)
    const error = ref('')
    const callVisible = ref(false)
    const callTarget = ref<EnrichedModel | null>(null)
    const allModels = ref<EnrichedModel[]>([])
    const resultsElement = ref<HTMLElement | null>(null)
    let searchTimer: number | undefined

    const filteredModels = computed(() => {
      const keyword = filters.query.trim().toLowerCase()
      const type = filters.type.trim().toLowerCase()
      const rows = allModels.value.filter(model => {
        const matchesKeyword = !keyword
          || model.publicName.toLowerCase().includes(keyword)
          || getPublisher(model).label.toLowerCase().includes(keyword)
          || model.tags.some(tag => tag.toLowerCase().includes(keyword))
        const matchesPublisher = !type || getPublisher(model).value === type
        const matchesUpstream = !filters.source || model.upstreams.includes(filters.source)
        const matchesAvailability = !filters.availability
          || (filters.availability === 'available' ? model.connected : !model.connected)
        const matchesTag = !filters.tag || model.tags.some(tag => tag.toLowerCase() === filters.tag.toLowerCase())
        return matchesKeyword && matchesUpstream && matchesAvailability && matchesPublisher && matchesTag
      })
      if (filters.sort === 'provider') return [...rows].sort((a, b) => getPublisher(a).label.localeCompare(getPublisher(b).label) || a.publicName.localeCompare(b.publicName))
      if (filters.sort === 'popular') return [...rows].sort((a, b) => b.popularity - a.popularity || a.publicName.localeCompare(b.publicName))
      return [...rows].sort((a, b) => a.publicName.localeCompare(b.publicName))
    })

    const displayModels = computed(() => {
      const start = (page.value - 1) * size
      return filteredModels.value.slice(start, start + size)
    })
    const connectedCount = computed(() => allModels.value.filter(model => model.connected).length)
    const publisherOptions = computed(() => {
      const counts = new Map<string, number>()
      allModels.value.forEach(model => {
        const key = getPublisher(model).value
        counts.set(key, (counts.get(key) || 0) + 1)
      })
      return publisherCatalog
        .map(publisher => ({ ...publisher, count: counts.get(publisher.value) || 0 }))
        .filter(publisher => publisher.count > 0)
    })
    const providerCount = computed(() => publisherOptions.value.length)
    const upstreamOptions = computed(() => {
      const rows=new Map<string,{label:string;value:string;count:number}>()
      allModels.value.forEach(model=>model.upstreams.forEach(code=>{const item=rows.get(code)||{label:model.upstreamLabels[code]||'平台智能路由',value:code,count:0};item.count+=1;rows.set(code,item)}))
      return [...rows.values()].sort((a,b)=>a.label.localeCompare(b.label))
    })
    const tagOptions = computed(() => {
      const counts = new Map<string, number>()
      allModels.value.forEach(model => model.tags.forEach(tag => {
        if (tag === '已接入' || tag === getPublisher(model).label || tag === 'NVIDIA') return
        counts.set(tag, (counts.get(tag) || 0) + 1)
      }))
      return Array.from(counts.entries())
        .map(([label, count]) => ({ label, count }))
        .sort((a, b) => b.count - a.count || a.label.localeCompare(b.label))
        .slice(0, 8)
    })
    const fetchModels = async () => {
      loading.value = true
      error.value = ''
      try {
        const catalog: PublicModel[] = []
        let catalogPage = 1
        let expectedTotal = 0
        do {
          const res = await http.get<PageResponse<PublicModel>>('/api/public/models', {
            params: { page: catalogPage, size: 100, sort: 'name', availability: 'available' }
          })
          catalog.push(...(res.data.items || []))
          expectedTotal = Number(res.data.total || catalog.length)
          catalogPage += 1
        } while (catalog.length < expectedTotal && catalogPage <= 20)
        allModels.value = mergeCatalog(catalog)
        total.value = allModels.value.length
        page.value = Math.min(page.value, Math.max(1, Math.ceil(filteredModels.value.length / size)))
      } catch {
        error.value = '后端模型目录暂时不可用。为避免误导，不展示未验证的模型、状态或价格。'
        allModels.value = []
        total.value = 0
      } finally {
        loading.value = false
      }
    }

    const clearFilters = () => {
      filters.query = ''
      filters.source = ''
      filters.availability = ''
      filters.type = ''
      filters.tag = ''
      filters.sort = 'name'
      page.value = 1
    }

    const choosePublisher = (value: string) => {
      filters.type = filters.type === value ? '' : value
      page.value = 1
    }

    const chooseUpstream = (value: string) => {
      filters.source = filters.source === value ? '' : value
      page.value = 1
    }

    const chooseAvailability = (value: string) => {
      filters.availability = filters.availability === value ? '' : value
      page.value = 1
    }

    const chooseTag = (value: string) => {
      filters.tag = filters.tag === value ? '' : value
      page.value = 1
    }

    const callModel = (model: EnrichedModel) => {
      if (!model.connected) {
        ElMessage.warning(model.verificationMessage || '该模型尚未验证通过，暂不能调用')
        return
      }
      callTarget.value = model
      callVisible.value = true
    }

    const copyModel = async (model: EnrichedModel) => {
      try {
        await navigator.clipboard.writeText(model.publicName)
        ElMessage.success('模型名已复制')
      } catch {
        ElMessage.error('复制失败，请手动复制模型名')
      }
    }

    const changePage = (nextPage: number) => {
      page.value = nextPage
      const reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
      resultsElement.value?.scrollIntoView({ behavior: reducedMotion ? 'auto' : 'smooth', block: 'start' })
    }

    watch(() => [filters.query, filters.source, filters.availability, filters.type, filters.tag, filters.sort], () => {
      window.clearTimeout(searchTimer)
      searchTimer = window.setTimeout(() => {
        page.value = 1
      }, 160)
    })

    onMounted(fetchModels)

    const renderPublisherIcon = (model: EnrichedModel) => {
      const publisher = getPublisher(model)
      return publisher.icon
        ? h('img', { src: publisher.icon, alt: `${publisher.label} 图标` })
        : h('span', { class: 'market-publisher-fallback' }, publisher.label.slice(0, 1))
    }

    return () => h('section', { class: 'site-section market-page' }, [
      h('div', { class: 'market-hero' }, [
        h('div', { class: 'market-hero-copy' }, [
          h('div', { class: 'market-hero-label' }, [
            h('img', { src: '/model-icons/model-gateway.png', alt: '统一模型网关' }),
            h('span', '统一模型目录')
          ]),
          h('h1', '发现并调用适合你的 AI 模型'),
          h('p', '展示上游完整模型目录，并按上游、功能、厂家和验证状态筛选。只有验证成功的模型才能通过统一 API Key 调用。')
        ]),
        h('div', { class: 'market-stats' }, [
          h('article', null, [h('span', '目录模型'), h('strong', String(total.value))]),
          h('article', null, [h('span', '公开可调用'), h('strong', String(connectedCount.value))]),
          h('article', null, [h('span', '模型发布方'), h('strong', String(providerCount.value))])
        ])
      ]),

      error.value ? h('div', { class: 'market-fallback-note' }, error.value) : null,

      h('div', { class: 'market-catalog-shell' }, [
        h('aside', { class: ['market-filter-panel', filtersOpen.value ? 'is-open' : ''] }, [
          h('header', null, [
            h('div', null, [h('span', { class: 'market-filter-icon' }, '≡'), h('h2', '筛选')]),
            h('button', { type: 'button', onClick: clearFilters, disabled: !filters.source && !filters.availability && !filters.type && !filters.tag }, '重置')
          ]),
          h('div', { class: 'market-filter-group' }, [
            h('h3', '模型上游'),
            h('div', { class: 'market-filter-options text-only' }, upstreamOptions.value.map(upstream =>
              h('button', {
                type: 'button',
                class: { active: filters.source === upstream.value },
                'aria-pressed': filters.source === upstream.value,
                onClick: () => chooseUpstream(upstream.value)
              }, [h('span', null, [h('em', upstream.label)]), h('b', String(upstream.count))])
            ))
          ]),
          h('div', { class: 'market-filter-group' }, [
            h('h3', '可用状态'),
            h('div', { class: 'market-filter-options text-only' }, [
              h('button', { type: 'button', class: { active: filters.availability === 'available' }, onClick: () => chooseAvailability('available') },
                [h('span', null, [h('em', '已验证可调用')]), h('b', String(connectedCount.value))])
            ])
          ]),
          h('div', { class: 'market-filter-group' }, [
            h('h3', '模型发布方'),
            h('div', { class: 'market-filter-options' }, publisherOptions.value.map(publisher =>
              h('button', {
                type: 'button',
                class: { active: filters.type === publisher.value },
                'aria-pressed': filters.type === publisher.value,
                onClick: () => choosePublisher(publisher.value)
              }, [
                h('span', null, [
                  publisher.icon
                    ? h('img', { src: publisher.icon, alt: '' })
                    : h('i', null, publisher.label.slice(0, 1)),
                  h('em', publisher.label)
                ]),
                h('b', String(publisher.count))
              ])
            ))
          ]),
          tagOptions.value.length ? h('div', { class: 'market-filter-group' }, [
            h('h3', '能力与场景'),
            h('div', { class: 'market-filter-options text-only' }, tagOptions.value.map(tag =>
              h('button', {
                type: 'button',
                class: { active: filters.tag === tag.label },
                'aria-pressed': filters.tag === tag.label,
                onClick: () => chooseTag(tag.label)
              }, [h('span', null, [h('em', tag.label)]), h('b', String(tag.count))])
            ))
          ]) : null
        ]),

        h('div', { class: 'market-results', ref: resultsElement }, [
          h('div', { class: 'market-toolbar' }, [
            h('div', { class: 'market-result-count' }, [
              h('strong', String(filteredModels.value.length)),
              h('span', ' 个模型')
            ]),
            h(ElInput, {
              modelValue: filters.query,
              'onUpdate:modelValue': (value: string) => { filters.query = value },
              clearable: true,
              placeholder: '搜索模型名称、发布方或能力',
              class: 'market-search'
            }),
            h(ElSelect, {
              modelValue: filters.sort,
              'onUpdate:modelValue': (value: string) => { filters.sort = value },
              class: 'market-select',
              'aria-label': '排序方式'
            }, () => [
              h(ElOption, { label: '名称排序', value: 'name' }),
              h(ElOption, { label: '热度优先', value: 'popular' }),
              h(ElOption, { label: '发布方排序', value: 'provider' })
            ]),
            h(ElButton, { class: 'market-mobile-filter', onClick: () => { filtersOpen.value = !filtersOpen.value } }, () => filtersOpen.value ? '收起筛选' : '筛选'),
            h(ElButton, { class: 'market-refresh', loading: loading.value, onClick: fetchModels }, () => '刷新')
          ]),

          loading.value && !allModels.value.length
            ? h('div', { class: 'market-grid' }, Array.from({ length: 5 }).map((_, index) =>
              h('article', { class: 'market-card', key: index }, [h(ElSkeleton, { rows: 4, animated: true })])
            ))
            : displayModels.value.length
              ? h('div', { class: 'market-grid' }, displayModels.value.map(model => {
                const publisher = getPublisher(model)
                return h('article', { class: ['market-card', model.connected ? 'connected' : 'pending'], key: model.publicName }, [
                  h('div', { class: 'market-card-main' }, [
                    h('header', null, [
                      h('div', { class: 'market-publisher' }, [
                        h('span', { class: 'market-publisher-icon' }, [renderPublisherIcon(model)]),
                        h('span', null, [h('strong', publisher.label), h('small', model.provider === 'nvidia' ? 'NVIDIA API' : '统一网关')])
                      ]),
                      h('div', { class: 'market-card-badges' }, [
                        ...model.upstreams.map(upstream => h('span', {
                          class: ['market-badge', upstream === 'nvidia' ? 'nvidia' : ''],
                          key: upstream
                        }, model.upstreamLabels[upstream] || '平台智能路由')),
                        h('span', { class: 'market-badge available' }, model.status)
                      ])
                    ]),
                    h('h3', model.publicName),
                    h('p', { class: 'market-card-desc' }, model.description),
                    !model.connected && model.verificationMessage
                      ? h('p', { class: 'market-card-desc' }, `验证状态：${model.verificationMessage}`)
                      : null,
                    h('div', { class: 'market-tags' }, model.tags.filter(tag => tag !== '已接入' && tag !== publisher.label).slice(0, 4).map(tag => h('span', { key: tag }, tag)))
                  ]),
                  h('footer', null, [
                    h('dl', { class: 'market-meta' }, [h('div', null, [h('dt', '上下文 / 能力'), h('dd', model.context)])]),
                    model.pricingModel ? h(ModelSalePricing, { model: model.pricingModel, compact: true, class: 'market-sale-pricing' }) : null,
                    h('div', { class: 'market-card-actions' }, [
                      h(ElButton, { onClick: () => copyModel(model) }, () => '复制模型名'),
                      h(ElButton, { type: 'primary', disabled: !model.connected, onClick: () => callModel(model) }, () => model.connected ? '立即调用 →' : '尚不可调用')
                    ])
                  ])
                ])
              }))
              : h('div', { class: 'market-empty' }, [
                h(ElEmpty, { description: '没有找到匹配模型' }, () => h(ElButton, { onClick: clearFilters }, () => '清空筛选'))
              ]),

          filteredModels.value.length
            ? h('div', { class: 'market-pagination' }, [
              h(ElPagination, {
                currentPage: page.value,
                pageSize: size,
                total: filteredModels.value.length,
                layout: 'total, prev, pager, next',
                background: true,
                'aria-label': '模型列表分页',
                'onUpdate:currentPage': changePage
              })
            ])
            : null
        ])
      ]),

      h('section', { class: 'market-guide' }, [
        h('div', null, [
          h('p', { class: 'eyebrow' }, '快速接入'),
          h('h2', '后台配置渠道和映射后，模型即可从统一 Base URL 调用。'),
          h('p', '模型广场负责展示目录、本站销售价格和接入状态；真实调用仍使用平台 API Key 和 OpenAI SDK 兼容接口。'),
          h('div', { class: 'hero-actions' }, [
            h(ElButton, { type: 'primary', onClick: () => router.push('/docs') }, () => '查看接入文档'),
            h(ElButton, { onClick: () => router.push('/console/playground') }, () => '在线调试')
          ])
        ]),
        h('pre', `import OpenAI from "openai";\n\nconst client = new OpenAI({\n  apiKey: "YOUR_API_KEY",\n  baseURL: "${gatewayBaseUrl.value}"\n});\n\nawait client.chat.completions.create({\n  model: "YOUR_ENABLED_MODEL",\n  messages: [{ role: "user", content: "Hello" }]\n});`)
      ]),
      h(ModelCallDialog, {
        modelValue: callVisible.value,
        'onUpdate:modelValue': (value:boolean) => { callVisible.value = value },
        modelId: callTarget.value?.publicName || '',
        capability: String((callTarget.value as any)?.pricingModel?.capability || 'TEXT')
      })
    ])
  }
})

const publisherCatalog = [
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

function modelUpstreams(model: PublicModel) {
  const structured=(model.upstreams||[]).filter(item=>item?.code&&item?.name)
  if(structured.length)return {codes:[...new Set(structured.map(item=>item.code))],labels:Object.fromEntries(structured.map(item=>[item.code,item.name]))}
  return {codes:['platform-route'],labels:{'platform-route':'平台智能路由'}}
}

function getPublisher(model: EnrichedModel) {
  const name = model.publicName.toLowerCase()
  let key = model.provider
  if (name.includes('claude')) key = 'anthropic'
  else if (name.includes('deepseek')) key = 'deepseek'
  else if (name.startsWith('meta/') || name.includes('llama')) key = 'meta'
  else if (name.startsWith('google/') || name.includes('gemma') || name.includes('gemini')) key = 'google'
  else if (name.includes('mistral')) key = 'mistral'
  else if (name.startsWith('qwen/') || name.includes('qwen')) key = 'qwen'
  else if (name.startsWith('openai/') || name.includes('gpt-oss') || name.startsWith('gpt-')) key = 'openai'
  else if (name.includes('minimax')) key = 'minimax'
  else if (name.includes('stepfun') || name.includes('step-')) key = 'stepfun'
  else if (name.startsWith('z-ai/') || name.includes('glm')) key = 'zai'
  else if (name.startsWith('baai/') || name.includes('bge-')) key = 'baai'
  else if (name.includes('kimi')) key = 'kimi'
  else if (name.startsWith('nvidia/') || name.includes('nemotron') || name.startsWith('nv-')) key = 'nvidia'
  return publisherCatalog.find(publisher => publisher.value === key)
    || publisherCatalog[publisherCatalog.length - 1]
}

function c(
  publicName: string,
  type: string,
  providerLabel: string,
  description: string,
  tags: string[],
  context: string,
  rate: string,
  tone: string,
  popularity: number,
  addedRank: number
): CatalogModel {
  return { publicName, type, providerLabel, description, tags, context, rate, tone, popularity, addedRank }
}

function mergeCatalog(connectedModels: PublicModel[]) {
  const known = new Set<string>()
  const rows: EnrichedModel[] = []
  for (const model of connectedModels) {
    const name = model.publicName?.trim()
    if (!name || known.has(name.toLowerCase())) continue
    known.add(name.toLowerCase())
    const catalogMatch = curatedModels.find(item => item.publicName.toLowerCase() === name.toLowerCase())
    const inferred = catalogMatch || inferFamily(model)
    const upstream=modelUpstreams(model)
    rows.push(enrichModel({
      ...inferred,
      publicName: name,
      type: normalizeProvider(model.vendor || model.type || inferred.type, name.toLowerCase()),
      description: inferred.description,
      tags: [...new Set([...inferCapabilityTags(name, inferred.tags), capabilityLabel(model.capability)])],
      context: inferred.context || '以渠道配置为准',
      rate: publicPriceSummary(model),
      pricingLines: publicPriceLines(model),
      pricingModel: model
    }, model.available === true || model.verificationStatus === 'AVAILABLE', upstream.codes,
      model.verificationStatus, model.verificationMessage, upstream.labels))
  }
  return rows
}

function enrichModel(model: CatalogModel, connected: boolean, upstreams: string[] = ['other'],
  verificationStatus?: string, verificationMessage?: string, upstreamLabels: Record<string,string> = {'platform-route':'平台智能路由'}): EnrichedModel {
  return {
    ...model,
    provider: model.type,
    upstreams,
    upstreamLabels,
    status: connected ? '已验证' : verificationStatusLabel(verificationStatus),
    verificationStatus: verificationStatus || (connected ? 'AVAILABLE' : 'DISCOVERED'),
    verificationMessage: verificationMessage || (connected ? '验证成功，可调用' : '等待管理员验证'),
    rate: connected ? model.rate : '未配置',
    pricingLines: connected ? model.pricingLines || [] : [],
    pricingModel: connected ? model.pricingModel : undefined,
    connected
  }
}

function publicPriceLines(model: PublicModel) {
  const unit = String(model.pricing?.unit || model.pricingUnit || 'TOKEN').toUpperCase()
  if (model.pricing?.billingMode === 'FREE_PREVIEW' || model.billingMode === 'FREE_PREVIEW') return ['免费开发预览 · 非生产服务']
  if (unit !== 'TOKEN') {
    const sale = Number(model.pricing?.saleUnitPrice ?? model.saleUnitPrice ?? 0)
    return sale > 0 ? [`本站售价 ${usdUnitSale(sale, unit)}`] : [model.pricing?.message || model.pricingMessage || `尚未设置${unitLabel(unit)}售价`]
  }
  if (!hasSalePrice(model)) return ['销售价格待配置']
  return [
    `输入/未命中 ${usdSale(model.maxInputPricePerMillion ?? model.minInputPricePerMillion)}`,
    `输出 ${usdSale(model.maxOutputPricePerMillion ?? model.minOutputPricePerMillion)}`,
    `缓存命中 ${usdSale(model.maxCacheReadPricePerMillion ?? model.minCacheReadPricePerMillion)}`,
    `缓存写入 ${usdSale(model.maxCacheWritePricePerMillion ?? model.minCacheWritePricePerMillion)}`
  ]
}

function publicPriceSummary(model: PublicModel) {
  const unit = String(model.pricing?.unit || model.pricingUnit || 'TOKEN').toUpperCase()
  if (model.pricing?.billingMode === 'FREE_PREVIEW' || model.billingMode === 'FREE_PREVIEW') return '免费开发预览'
  if (unit !== 'TOKEN') {
    const sale = Number(model.pricing?.saleUnitPrice ?? model.saleUnitPrice ?? 0)
    return sale > 0 ? usdUnitSale(sale, unit) : (model.pricing?.message || model.pricingMessage || '价格待配置')
  }
  if (!hasSalePrice(model)) return '销售价格待配置'
  return `输入 ${usdSale(model.maxInputPricePerMillion ?? model.minInputPricePerMillion)} / 输出 ${usdSale(model.maxOutputPricePerMillion ?? model.minOutputPricePerMillion)}`
}

function hasSalePrice(model: PublicModel) {
  if (model.pricing?.billingMode === 'FREE_PREVIEW' || model.billingMode === 'FREE_PREVIEW') return true
  const unit = String(model.pricing?.unit || model.pricingUnit || 'TOKEN').toUpperCase()
  if (unit !== 'TOKEN') return Number(model.pricing?.saleUnitPrice ?? model.saleUnitPrice ?? 0) > 0
  return model.billingConfigured !== false
    && Number(model.maxInputPricePerMillion ?? model.minInputPricePerMillion ?? 0) > 0
}

function unitLabel(unit: string) { return ({ SECOND: '按秒', IMAGE: '按张', MINUTE: '按分钟', CHARACTER: '按千字符', TASK: '按次' } as Record<string, string>)[unit] || '单位' }
function usdUnitSale(value: number, unit: string) { return `$${value.toLocaleString('en-US', { maximumFractionDigits: 6 })} / ${({ SECOND: '秒', IMAGE: '张', MINUTE: '分钟', CHARACTER: '千字符', TASK: '次' } as Record<string, string>)[unit] || '单位'}` }

function usdSale(value?: number) {
  const price = Number(value || 0)
  const format = (value: number) => `$${value.toLocaleString('en-US', { maximumFractionDigits: 6 })} / 1M`
  return price > 0 ? format(price) : '不计费'
}

function verificationStatusLabel(status?: string) {
  return ({ DISCOVERED: '待验证', VERIFYING: '验证中', FAILED: '验证失败', UNSUPPORTED: '协议待接入', RETIRED: '已下架' } as Record<string, string>)[status || ''] || '待验证'
}

function capabilityLabel(capability?: string) {
  return ({ text: '文本', reasoning: '推理', vision: '视觉', image: '图像', video: '视频', music: '音乐', embedding: 'Embedding', rerank: 'Rerank', speech: '语音合成', transcription: '语音识别' } as Record<string, string>)[capability || ''] || '文本'
}

function inferFamily(model: PublicModel): CatalogModel {
  const name = model.publicName || 'unknown-model'
  const lowerName = name.toLowerCase()
  const provider = normalizeProvider(model.type, lowerName)
  if (lowerName.includes('claude')) {
    return c(name, provider, 'Anthropic', '适合长文本、代码生成、复杂推理和高质量内容处理。', ['Chat', '代码', '长文本'], '200K', '3x', 'green', 70, 100)
  }
  if (lowerName.includes('gemini')) {
    return c(name, provider, 'Google', '适合长上下文、多模态资料分析和批量知识处理。', ['Chat', '多模态', '长上下文'], '1M', '2x', 'purple', 70, 100)
  }
  if (lowerName.includes('deepseek')) {
    return c(name, provider, 'DeepSeek', '适合中文业务、低成本对话、代码辅助和高频调用。', ['Chat', '中文优化', '低成本'], '128K', '0.5x', 'orange', 70, 100)
  }
  if (lowerName.includes('embed') || lowerName.includes('bge-')) {
    return c(name, provider, 'NVIDIA', '面向语义检索、向量化、RAG 知识库与多模态检索的嵌入模型。', ['Embedding', 'RAG', '语义检索'], '以模型配置为准', '默认', 'indigo', 72, 100)
  }
  if (lowerName.startsWith('meta/') || lowerName.includes('llama')) {
    return c(name, provider, 'Meta', 'Meta Llama 开放模型，适合通用对话、企业知识问答、推理与私有化场景。', ['Chat', 'Open Weights', 'RAG'], '以模型配置为准', '默认', 'blue', 76, 100)
  }
  if (lowerName.startsWith('google/') || lowerName.includes('gemma')) {
    return c(name, provider, 'Google', 'Google 开放模型，适合通用对话、多模态理解、指令跟随与开发者应用。', ['Chat', 'Open Weights', '多模态'], '以模型配置为准', '默认', 'purple', 75, 100)
  }
  if (lowerName.includes('qwen')) {
    return c(name, provider, 'Qwen', 'Qwen 开放模型，适合中文任务、代码生成、复杂推理和企业级智能体。', ['Chat', '中文优化', 'Reasoning', 'Coding'], '以模型配置为准', '默认', 'cyan', 78, 100)
  }
  if (lowerName.includes('mistral')) {
    return c(name, provider, 'Mistral AI', 'Mistral 开放模型，适合文本生成、代码、工具调用与多语言企业应用。', ['Chat', 'Coding', '工具调用'], '以模型配置为准', '默认', 'red', 74, 100)
  }
  if (lowerName.includes('minimax')) {
    return c(name, provider, 'MiniMax', 'MiniMax 多模态模型，适合推理、代码、工具调用与长流程智能体任务。', ['Chat', '多模态', 'Agent'], '以模型配置为准', '默认', 'orange', 77, 100)
  }
  if (lowerName.includes('stepfun') || lowerName.includes('step-')) {
    return c(name, provider, 'StepFun', 'StepFun 多模态推理模型，适合企业应用、代码任务与智能体工作流。', ['Chat', '多模态', 'Coding'], '以模型配置为准', '默认', 'green', 73, 100)
  }
  if (lowerName.startsWith('z-ai/') || lowerName.includes('glm')) {
    return c(name, provider, 'Z.ai', 'GLM 系列模型，适合中文推理、代码生成、工具调用与长流程智能体任务。', ['Chat', 'Reasoning', '中文优化'], '以模型配置为准', '默认', 'indigo', 79, 100)
  }
  if (lowerName.startsWith('openai/') || lowerName.includes('gpt-oss')) {
    return c(name, provider, 'OpenAI', 'OpenAI 开放权重模型，适合推理、代码和可部署的智能体应用。', ['Chat', 'Reasoning', 'Coding'], '以模型配置为准', '默认', 'blue', 77, 100)
  }
  if (provider === 'nvidia' || lowerName.includes('gemma') || lowerName.includes('z-ai/')) {
    return c(name, provider, 'NVIDIA', '通过 NVIDIA 集成接口纳入统一网关，适合开放模型、推理和通用对话测试。', ['Chat', 'NVIDIA', 'Reasoning'], '128K', '默认', 'indigo', 70, 100)
  }
  if (provider === 'openai' || lowerName.includes('gpt')) {
    return c(name, provider, 'OpenAI', '适合通用对话、函数调用、低延迟任务和产品内 AI 功能。', ['Chat', '低延迟', '工具调用'], lowerName.includes('mini') ? '128K' : '128K+', lowerName.includes('mini') ? '1x' : '2x', 'blue', 70, 100)
  }
  return c(name, provider, provider || 'Custom', '统一纳入 API Transit 调度的公共模型，可通过兼容 OpenAI 的接口调用。', ['Chat', '自定义渠道', '统一网关'], '按渠道', '默认', 'gray', 50, 120)
}

function inferCapabilityTags(publicName: string, sourceTags: string[]) {
  const name = publicName.toLowerCase()
  const tags = new Set(sourceTags.filter(Boolean))
  if (name.includes('embed') || name.includes('bge-')) {
    tags.add('Embedding')
    tags.add('RAG')
    tags.add('语义检索')
  }
  if (name.includes('vision') || name.includes('-vl') || name.includes('omni')) tags.add('多模态')
  if (name.includes('reason') || name.includes('nemotron') || name.includes('glm')) tags.add('Reasoning')
  if (name.includes('coder') || name.includes('gpt-oss') || name.includes('deepseek')) tags.add('Coding')
  if (name.includes('flash') || name.includes('nano') || name.includes('mini')) tags.add('低延迟')
  tags.add('已接入')
  return Array.from(tags)
}

function normalizeProvider(type: string | undefined, lowerName: string) {
  if (lowerName.includes('claude')) return 'anthropic'
  if (lowerName.includes('gemini')) return 'google'
  if (lowerName.includes('grok')) return 'xai'
  if (lowerName.includes('qwen') || lowerName.includes('qwq')) return 'qwen'
  if (lowerName.includes('kimi')) return 'kimi'
  if (lowerName.includes('glm')) return 'glm'
  if (lowerName.includes('gemma') || lowerName.includes('z-ai/')) return 'nvidia'
  if (lowerName.includes('mistral')) return 'mistral'
  if (lowerName.includes('llama')) return 'meta'
  if (lowerName.includes('deepseek')) return 'deepseek'
  if (lowerName.includes('gpt') || lowerName.includes('o1') || lowerName.includes('o3') || lowerName.includes('o4')) return 'openai'
  const normalized = String(type || '').toLowerCase()
  if (normalized && !['unknown', 'other', 'haoee', 'nvidia', 'multi'].includes(normalized)) return normalized
  return 'custom'
}
</script>
