<template>
  <section class="site-hero">
    <div class="hero-copy">
      <p class="eyebrow">Linknux · AI 能力平台</p>
      <h1>连接主流模型，让团队专注创造。</h1>
      <p>面向开发者与团队的一站式 AI 能力平台，统一连接主流模型，提供智能路由、用量治理、创作工具与企业协作。</p>
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

    <div class="hero-product" aria-label="Linknux product preview">
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
