<template>
  <div class="shell-page home-page">
    <section class="home-hero shell-section">
      <div class="hero-copy">
        <el-tag type="info" effect="plain">AI Gateway Platform</el-tag>
        <h1>把多家大模型接成一套可运营、可审计、可扩展的企业网关。</h1>
        <p>
          统一接入 OpenAI、Claude、Gemini、DeepSeek、Grok 等主流供应商，对外输出一致接口，
          把模型映射、渠道治理、令牌分发、调用审计收拢到一个后台。
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" @click="$router.push('/console')">进入控制台</el-button>
          <el-button size="large" @click="$router.push('/market')">查看模型目录</el-button>
        </div>
      </div>
      <div class="hero-status">
        <div class="status-header">
          <h3>接入优先级</h3>
          <span>按企业落地顺序</span>
        </div>
        <div class="status-list">
          <div v-for="item in priorities" :key="item.name" class="status-item">
            <div>
              <strong>{{ item.name }}</strong>
              <p>{{ item.note }}</p>
            </div>
            <el-tag :type="item.type">{{ item.level }}</el-tag>
          </div>
        </div>
      </div>
    </section>

    <section class="metric-grid">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card shell-section">
        <div class="metric-label">{{ metric.label }}</div>
        <div class="metric-value">{{ metric.value }}</div>
        <div class="metric-footnote">{{ metric.footnote }}</div>
      </article>
    </section>

    <section class="home-lower">
      <div class="capabilities shell-section">
        <div class="panel-head">
          <h2 class="section-title">企业级能力基线</h2>
          <p class="section-subtitle">这次整理后的项目主线。</p>
        </div>
        <div class="capability-grid">
          <article v-for="capability in capabilities" :key="capability.title">
            <el-icon><component :is="capability.icon" /></el-icon>
            <h3>{{ capability.title }}</h3>
            <p>{{ capability.text }}</p>
          </article>
        </div>
      </div>

      <div class="provider-brief shell-section">
        <div class="panel-head">
          <h2 class="section-title">建议先接的供应商</h2>
          <p class="section-subtitle">先把主路由搭稳，再逐步扩供应商池。</p>
        </div>
        <div class="provider-list">
          <div v-for="provider in providers" :key="provider.provider" class="provider-row">
            <div>
              <strong>{{ provider.provider }}</strong>
              <p>{{ provider.headline }}</p>
            </div>
            <el-tag>{{ provider.endpointStyle }}</el-tag>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import http from '@/utils/http'

type Overview = {
  totalChannels: number
  enabledChannels: number
  totalMappings: number
  totalTokens: number
  totalUsers: number
  totalRequests: number
  successRequests: number
  failedRequests: number
  totalConsumedTokens: number
  activeProviders: string[]
}

type ProviderCatalogItem = {
  provider: string
  headline: string
  endpointStyle: string
}

const overview = ref<Overview | null>(null)
const providers = ref<ProviderCatalogItem[]>([])

const priorities = [
  { name: 'OpenAI', note: '默认标准面，兼容生态最成熟。', level: 'P0', type: 'primary' },
  { name: 'Anthropic', note: '高质量文本与代码生成，建议作为备路。', level: 'P0', type: 'success' },
  { name: 'Gemini', note: '多模态和长上下文任务优先。', level: 'P1', type: 'warning' },
  { name: 'DeepSeek', note: '中文和成本优化层。', level: 'P1', type: 'danger' },
  { name: 'Grok', note: '扩展供应商冗余池。', level: 'P2', type: 'info' }
]

const capabilities = [
  { title: '统一调用入口', text: '对外保持 OpenAI 风格接口，前端和业务方不再感知底层厂商差异。', icon: 'Connection' },
  { title: '供应商适配层', text: '后端已拆出 OpenAI-compatible、Anthropic、Gemini 三类网关适配器。', icon: 'Cpu' },
  { title: '模型路由治理', text: '同一个公开模型名可挂多条渠道映射，便于做主备、灰度和价格策略。', icon: 'Switch' },
  { title: '运维可观测', text: '令牌、配额、调用成功率和总消耗在后台统一管理。', icon: 'DataAnalysis' }
]

const metrics = computed(() => {
  const data = overview.value
  return [
    { label: '已配置渠道', value: data?.totalChannels ?? 0, footnote: `启用 ${data?.enabledChannels ?? 0} 个` },
    { label: '模型映射', value: data?.totalMappings ?? 0, footnote: `活跃供应商 ${data?.activeProviders?.length ?? 0} 个` },
    { label: '访问令牌', value: data?.totalTokens ?? 0, footnote: `用户 ${data?.totalUsers ?? 0} 个` },
    { label: '累计请求', value: data?.totalRequests ?? 0, footnote: `成功 ${data?.successRequests ?? 0} / 失败 ${data?.failedRequests ?? 0}` }
  ]
})

const fetchData = async () => {
  const [overviewRes, providerRes] = await Promise.all([
    http.get('/api/ops/overview'),
    http.get('/api/ops/catalog')
  ])
  overview.value = overviewRes.data
  providers.value = providerRes.data
}

onMounted(() => {
  fetchData().catch(() => {})
})
</script>

<style scoped>
.home-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.home-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(360px, 0.9fr);
  gap: 24px;
  padding: 32px;
  border-radius: 8px;
}

.hero-copy h1 {
  max-width: 820px;
  margin: 20px 0 0;
  font-size: 44px;
  line-height: 1.15;
  color: #0f172a;
}

.hero-copy p {
  max-width: 760px;
  margin: 20px 0 0;
  color: #475569;
  font-size: 16px;
}

.hero-actions {
  display: flex;
  gap: 12px;
  margin-top: 28px;
}

.hero-status {
  padding: 24px;
  border-radius: 8px;
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.98), rgba(30, 41, 59, 0.98));
  color: #f8fafc;
}

.status-header {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 18px;
}

.status-header h3 {
  margin: 0;
  font-size: 20px;
}

.status-header span {
  font-size: 13px;
  color: #94a3b8;
}

.status-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.status-item {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0;
  border-bottom: 1px solid rgba(148, 163, 184, 0.18);
}

.status-item:last-child {
  border-bottom: none;
}

.status-item strong {
  display: block;
  font-size: 15px;
}

.status-item p {
  margin: 6px 0 0;
  color: #cbd5e1;
  font-size: 13px;
}

.home-lower {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(360px, 0.8fr);
  gap: 20px;
}

.capabilities,
.provider-brief {
  padding: 28px;
  border-radius: 8px;
}

.panel-head {
  margin-bottom: 24px;
}

.capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.capability-grid article {
  padding: 18px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.capability-grid :deep(.el-icon) {
  font-size: 18px;
  color: #0ea5e9;
}

.capability-grid h3 {
  margin: 14px 0 8px;
  font-size: 16px;
}

.capability-grid p,
.provider-row p {
  margin: 0;
  color: #64748b;
  font-size: 14px;
  line-height: 1.6;
}

.provider-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.provider-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid #e2e8f0;
}

.provider-row:last-child {
  padding-bottom: 0;
  border-bottom: none;
}

.provider-row strong {
  display: block;
  margin-bottom: 6px;
}

@media (max-width: 1080px) {
  .home-hero,
  .home-lower {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 768px) {
  .home-hero {
    padding: 24px 20px;
  }

  .hero-copy h1 {
    font-size: 34px;
  }

  .capability-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
