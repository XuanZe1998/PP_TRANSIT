<template>
  <section class="site-hero">
    <div class="hero-atmosphere" aria-hidden="true">
      <i class="orbit orbit-one"></i>
      <i class="orbit orbit-two"></i>
      <i class="signal signal-one"></i>
      <i class="signal signal-two"></i>
      <i class="signal signal-three"></i>
    </div>

    <div class="hero-copy">
      <p class="hero-brandline">Linknux <i>•</i> <span>AI 能力平台</span></p>
      <h1>连接主流模型，让团队专注创造。</h1>
      <p>面向开发者与团队的一站式 AI 能力平台，统一连接主流模型，提供智能路由、用量治理、创作工具与企业协作。</p>
      <div class="hero-actions">
        <el-button class="hero-primary" type="primary" size="large" @click="openAuth('register')">
          立即创建账号 <span aria-hidden="true">→</span>
        </el-button>
        <el-button class="hero-secondary" size="large" @click="router.push('/market')">
          <span class="grid-icon" aria-hidden="true"><i></i><i></i><i></i><i></i></span>
          查看模型广场
        </el-button>
      </div>
      <div class="hero-feature-grid">
        <article v-for="feature in heroFeatures" :key="feature.title">
          <b>{{ feature.icon }}</b>
          <p><strong>{{ feature.title }}</strong><small>{{ feature.meta }}</small></p>
        </article>
      </div>
    </div>

    <div class="hero-product" aria-label="Linknux product preview">
      <div class="product-toolbar">
        <span class="product-logo">△</span>
        <strong>智能调度网络</strong>
        <em><i></i> 实时运行中</em>
        <button type="button" @click="router.push('/market')">查看详情 <span>→</span></button>
      </div>
      <div class="routing-map">
        <svg class="routing-lines" viewBox="0 0 700 390" preserveAspectRatio="none" aria-hidden="true">
          <g class="routing-input-lines">
            <path v-for="(_, index) in sourceNodes" :key="`input-${index}`" :d="`M 188 ${42 + index * 51} C 278 ${42 + index * 51}, 266 195, 350 195`" />
          </g>
          <g class="routing-output-lines">
            <path v-for="(_, index) in outputNodes" :key="`output-${index}`" :d="`M 350 195 C 432 195, 424 ${52 + index * 64}, 512 ${52 + index * 64}`" />
          </g>
        </svg>
        <div class="source-stack">
          <article v-for="node in sourceNodes" :key="node.name">
            <img :src="node.icon" alt="" />
            <strong>{{ node.name }}</strong>
            <small>{{ node.latency }}</small>
            <i></i>
          </article>
        </div>
        <div class="routing-core">
          <span>△</span>
          <strong>智能路由</strong>
        </div>
        <div class="output-stack">
          <article v-for="node in outputNodes" :key="node.name">
            <b>{{ node.icon }}</b><strong>{{ node.name }}</strong><i></i>
          </article>
        </div>
      </div>
      <div class="routing-stats">
        <p><strong>{{ loading ? '—' : total }}</strong><small>当前模型</small></p>
        <p><strong>{{ loading ? '—' : publisherCount }}</strong><small>模型厂商</small></p>
        <p><strong>API</strong><small>统一协议接入</small></p>
        <span class="routing-chart" aria-hidden="true"><i v-for="n in 18" :key="n"></i></span>
      </div>
    </div>

    <div class="model-constellations" aria-hidden="true">
      <span class="constellation constellation-one">GPT</span>
      <span class="constellation constellation-two">Claude</span>
      <span class="constellation constellation-three">Gemini</span>
      <span class="constellation constellation-four">Qwen</span>
    </div>
    <footer class="hero-footnote">
      <span></span>
      <p>更开放的 AI，更强大的创造力<small>BUILD CONNECTIONS FOR A BRIGHTER TOMORROW</small></p>
    </footer>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/utils/http'

type ModelSummary = { total: number; publisherCount: number }

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const total = ref<number | string>('—')
const publisherCount = ref<number | string>('—')

const heroFeatures = [
  { icon: 'ϟ', title: '多模型接入', meta: '一次接入，全面覆盖' },
  { icon: '◎', title: '智能路由', meta: '自动选择最优模型' },
  { icon: '▥', title: '用量治理', meta: '可视化成本与分析' },
  { icon: '♧', title: '企业协作', meta: '让团队高效共创' }
]

const sourceNodes = [
  { name: 'OpenAI', latency: '28 ms', icon: '/model-icons/model-gateway.png' },
  { name: 'Claude', latency: '36 ms', icon: '/model-icons/anthropic.svg' },
  { name: 'Gemini', latency: '42 ms', icon: '/model-icons/gemini.svg' },
  { name: 'Llama', latency: '53 ms', icon: '/model-icons/meta.svg' },
  { name: 'Mistral', latency: '46 ms', icon: '/model-icons/mistral.svg' },
  { name: 'Qwen', latency: '38 ms', icon: '/model-icons/qwen.svg' }
]

const outputNodes = [
  { name: '文本生成', icon: '▤' },
  { name: '图像生成', icon: '▧' },
  { name: '多模态理解', icon: '◇' },
  { name: '代码助手', icon: '</>' },
  { name: '企业知识库', icon: '◎' }
]

const openAuth = (mode: 'login' | 'register') => router.replace({
  path: route.path,
  query: { ...route.query, auth: mode }
})

onMounted(async () => {
  loading.value = true
  try {
    const { data } = await http.get<ModelSummary>('/api/public/models/summary', { timeout: 5000 })
    total.value = data.total
    publisherCount.value = data.publisherCount
  } catch {
    total.value = '—'
    publisherCount.value = '—'
  } finally {
    loading.value = false
  }
})
</script>
