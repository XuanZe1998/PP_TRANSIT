<template>
  <section class="site-hero">
    <div class="hero-copy">
      <p class="eyebrow">AI API 中转与模型聚合平台</p>
      <h1>统一接入主流大模型，把调用、计费和风控放到一个控制台。</h1>
      <p>
        兼容 OpenAI SDK，集中管理 OpenAI、Claude、Gemini、DeepSeek、Grok、Qwen 等模型渠道，
        支持 Key 配额、余额计费、失败切换、用量审计和团队权限。
      </p>
      <div class="hero-actions">
        <el-button type="primary" size="large" @click="openAuth('register')">立即创建账号</el-button>
        <el-button size="large" @click="router.push('/market')">查看模型广场</el-button>
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
          <strong>{{ loading ? '—' : total }}</strong>
          <em>来自后端公开目录</em>
        </div>
        <div class="product-card">
          <small>已接入供应商</small>
          <strong>{{ loading ? '—' : providerCount }}</strong>
          <em class="green">以实际配置为准</em>
        </div>
        <div class="route-list">
          <div v-for="item in routingCapabilities" :key="item.name">
            <span :class="item.tone"></span>
            <p><strong>{{ item.name }}</strong><small>{{ item.meta }}</small></p>
            <b>{{ item.status }}</b>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/utils/http'

type PublicModel = { publicName: string; vendor?: string; source?: string }
type PageResponse<T> = { total: number; items: T[] }

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const total = ref(0)
const models = ref<PublicModel[]>([])
const providerCount = computed(() => new Set(models.value.map(model => {
  const vendor = String(model.vendor || '').trim().toLowerCase()
  if (vendor && vendor !== 'unknown' && vendor !== 'multi') return vendor
  return model.publicName.split(/[\/-]/, 1)[0].toLowerCase()
})).size)

const routingCapabilities = [
  { name: '失败切换', meta: '需由管理员配置多渠道', status: '策略能力', tone: 'blue' },
  { name: '成本与权重路由', meta: '以后台模型映射为准', status: '策略能力', tone: 'green' },
  { name: '请求审计', meta: '记录 Trace ID、Token 和扣费', status: '账单能力', tone: 'orange' }
]

const openAuth = (mode: 'login' | 'register') => router.replace({
  path: route.path,
  query: { ...route.query, auth: mode }
})

onMounted(async () => {
  loading.value = true
  try {
    const response = await http.get<PageResponse<PublicModel>>('/api/public/models', {
      params: { page: 1, size: 100, sort: 'name', availability: 'available' }
    })
    models.value = response.data?.items || []
    total.value = Number(response.data?.total || models.value.length)
  } catch {
    models.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
})
</script>
