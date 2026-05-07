<template>
  <div class="shell-page command-page">
    <section class="command-head shell-section">
      <div>
        <h1>统一控制台</h1>
        <p>集中看网关状态、供应商目录，并直接调试统一接口。</p>
      </div>
      <div class="head-actions">
        <el-button @click="$router.push('/admin/channels')">管理渠道</el-button>
        <el-button type="primary" @click="$router.push('/market')">查看模型目录</el-button>
      </div>
    </section>

    <section class="metric-grid">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card shell-section">
        <div class="metric-label">{{ metric.label }}</div>
        <div class="metric-value">{{ metric.value }}</div>
        <div class="metric-footnote">{{ metric.footnote }}</div>
      </article>
    </section>

    <section class="command-layout">
      <div class="playground shell-section">
        <div class="panel-head">
          <h2 class="section-title">接口调试台</h2>
          <p class="section-subtitle">用平台令牌直接测试 `/v1/chat/completions`。</p>
        </div>
        <el-form label-position="top">
          <el-form-item label="平台令牌">
            <el-input v-model="playground.token" placeholder="输入 sk- 开头的访问令牌" show-password />
          </el-form-item>
          <el-form-item label="公开模型名">
            <el-input v-model="playground.model" placeholder="例如 gpt-5-main" />
          </el-form-item>
          <el-form-item label="提示词">
            <el-input v-model="playground.prompt" type="textarea" :rows="8" placeholder="输入一段测试指令" />
          </el-form-item>
          <div class="playground-actions">
            <el-button type="primary" :loading="running" @click="runPlayground">发送请求</el-button>
            <el-button @click="applySample">填充示例</el-button>
          </div>
        </el-form>

        <div class="response-panel">
          <div class="response-head">
            <strong>响应结果</strong>
            <el-tag v-if="responseMeta">{{ responseMeta }}</el-tag>
          </div>
          <pre>{{ responseText || '尚未发起请求。' }}</pre>
        </div>
      </div>

      <div class="sidebar">
        <div class="catalog shell-section">
          <div class="panel-head">
            <h2 class="section-title">供应商状态</h2>
            <p class="section-subtitle">当前项目建议优先接入的主流供应商。</p>
          </div>
          <div class="catalog-list">
            <div v-for="provider in providers" :key="provider.provider" class="catalog-row">
              <div>
                <strong>{{ provider.provider }}</strong>
                <p>{{ provider.endpointStyle }}</p>
              </div>
              <el-tag>{{ provider.providerType }}</el-tag>
            </div>
          </div>
        </div>

        <div class="quickstart shell-section">
          <div class="panel-head">
            <h2 class="section-title">上线前配置</h2>
            <p class="section-subtitle">已改成环境变量优先，建议先补这几项。</p>
          </div>
          <el-alert title="DB_URL / DB_USERNAME / DB_PASSWORD" type="info" :closable="false" />
          <el-alert title="JWT_SECRET" type="warning" :closable="false" />
          <el-alert title="GITHUB_CLIENT_ID / GOOGLE_CLIENT_ID" type="success" :closable="false" />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import http from '@/utils/http'

type Overview = {
  totalChannels: number
  enabledChannels: number
  totalMappings: number
  totalTokens: number
  totalRequests: number
  successRequests: number
  failedRequests: number
  totalConsumedTokens: number
}

type ProviderCatalogItem = {
  provider: string
  providerType: string
  endpointStyle: string
}

const overview = ref<Overview | null>(null)
const providers = ref<ProviderCatalogItem[]>([])
const running = ref(false)
const responseText = ref('')
const responseMeta = ref('')
const playground = ref({
  token: '',
  model: '',
  prompt: ''
})

const metrics = computed(() => {
  const data = overview.value
  return [
    { label: '启用渠道', value: data?.enabledChannels ?? 0, footnote: `总渠道 ${data?.totalChannels ?? 0}` },
    { label: '模型映射', value: data?.totalMappings ?? 0, footnote: `令牌 ${data?.totalTokens ?? 0}` },
    { label: '累计请求', value: data?.totalRequests ?? 0, footnote: `成功 ${data?.successRequests ?? 0}` },
    { label: '消耗 Token', value: data?.totalConsumedTokens ?? 0, footnote: `失败 ${data?.failedRequests ?? 0}` }
  ]
})

const applySample = () => {
  playground.value.model = playground.value.model || 'gpt-5-main'
  playground.value.prompt = '请用企业架构师视角，给出一个多模型 AI 网关的灰度发布策略。'
}

const runPlayground = async () => {
  if (!playground.value.token || !playground.value.model || !playground.value.prompt) {
    ElMessage.warning('请填写令牌、模型和提示词')
    return
  }

  running.value = true
  responseText.value = ''
  responseMeta.value = ''

  try {
    const res = await axios.post('/api/v1/chat/completions', {
      model: playground.value.model,
      messages: [{ role: 'user', content: playground.value.prompt }],
      stream: false
    }, {
      headers: {
        Authorization: `Bearer ${playground.value.token}`
      }
    })
    const usage = res.data?.usage
    responseText.value = res.data?.choices?.[0]?.message?.content || JSON.stringify(res.data, null, 2)
    responseMeta.value = usage ? `prompt ${usage.prompt_tokens ?? 0} / completion ${usage.completion_tokens ?? 0}` : 'success'
  } catch (error: any) {
    responseText.value = JSON.stringify(error.response?.data || { message: 'Request failed' }, null, 2)
    responseMeta.value = `HTTP ${error.response?.status || 500}`
  } finally {
    running.value = false
  }
}

onMounted(() => {
  Promise.all([
    http.get('/api/ops/overview').then(res => { overview.value = res.data }),
    http.get('/api/ops/catalog').then(res => { providers.value = res.data })
  ]).catch(() => {})
})
</script>

<style scoped>
.command-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.command-head {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 20px;
  padding: 28px 32px;
  border-radius: 8px;
}

.command-head h1 {
  margin: 0;
  font-size: 30px;
}

.command-head p {
  margin: 10px 0 0;
  color: #64748b;
}

.head-actions {
  display: flex;
  gap: 12px;
}

.command-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(320px, 0.8fr);
  gap: 20px;
}

.playground,
.catalog,
.quickstart {
  padding: 28px;
  border-radius: 8px;
}

.playground-actions {
  display: flex;
  gap: 12px;
}

.response-panel {
  margin-top: 24px;
  padding: 18px;
  border-radius: 8px;
  background: #0f172a;
  color: #e2e8f0;
}

.response-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}

.response-panel pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: "Cascadia Code", Consolas, monospace;
  font-size: 13px;
}

.sidebar {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.catalog-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.catalog-row {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 14px;
  border-bottom: 1px solid #e2e8f0;
}

.catalog-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.catalog-row p {
  margin: 6px 0 0;
  color: #64748b;
  font-size: 13px;
}

.quickstart {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

@media (max-width: 1080px) {
  .command-layout,
  .command-head {
    grid-template-columns: minmax(0, 1fr);
    display: grid;
  }
}
</style>
