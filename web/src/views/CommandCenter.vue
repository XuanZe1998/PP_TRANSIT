<template>
  <div class="shell-page command-page">
    <section class="command-head shell-section">
      <div>
        <h1>用户控制台</h1>
        <p>每个用户使用独立封装 Key，通过统一网关调用模型，并实时查看 token 消耗。</p>
      </div>
      <div class="head-actions">
        <el-button @click="openTokenDialog()">创建封装 Key</el-button>
        <el-button type="primary" @click="refreshDashboard">刷新</el-button>
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
      <div class="main-column">
        <section class="shell-section token-panel">
          <div class="panel-head">
            <div>
              <h2 class="section-title">封装 Key</h2>
              <p class="section-subtitle">Key 绑定当前用户，底层 DeepSeek 平台 Key 不会暴露给前端用户。</p>
            </div>
            <el-button type="primary" @click="openTokenDialog()">新增</el-button>
          </div>

          <div v-if="dashboard.tokens.length" class="token-grid">
            <article
              v-for="token in dashboard.tokens"
              :key="token.id"
              class="token-card"
              :class="{ active: selectedTokenId === token.id }"
              @click="selectToken(token.id)"
            >
              <div class="token-card-head">
                <strong>{{ token.name }}</strong>
                <el-tag :type="token.enabled ? 'success' : 'info'">
                  {{ token.enabled ? '启用' : '停用' }}
                </el-tag>
              </div>
              <div class="token-key">{{ token.key }}</div>
              <div class="token-meta">
                <span>请求 {{ token.requestCount }}</span>
                <span>Token {{ token.totalTokens }}</span>
              </div>
              <div class="token-meta">
                <span>已用 {{ token.usedQuota }}</span>
                <span>总额 {{ token.totalQuota || '不限' }}</span>
              </div>
              <div class="token-actions">
                <el-button link type="primary" @click.stop="copyText(token.key)">复制</el-button>
                <el-button link @click.stop="openTokenDialog(token)">编辑</el-button>
                <el-button link type="danger" @click.stop="deleteToken(token.id)">删除</el-button>
              </div>
            </article>
          </div>
          <el-empty v-else description="暂无封装 Key" />
        </section>

        <section class="shell-section playground">
          <div class="panel-head">
            <div>
              <h2 class="section-title">调用调试</h2>
              <p class="section-subtitle">直接使用当前用户的封装 Key 调试统一接口。</p>
            </div>
          </div>

          <el-form label-position="top">
            <el-form-item label="封装 Key">
              <el-select v-model="selectedTokenId" placeholder="选择封装 Key" @change="refreshExamples">
                <el-option
                  v-for="token in dashboard.tokens"
                  :key="token.id"
                  :label="`${token.name} (${token.key})`"
                  :value="token.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="模型">
              <el-select v-model="selectedModel" placeholder="选择公开模型" @change="refreshExamples">
                <el-option v-for="model in dashboard.models" :key="model" :label="model" :value="model" />
              </el-select>
            </el-form-item>
            <el-form-item label="Prompt">
              <el-input v-model="playgroundPrompt" type="textarea" :rows="6" />
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
            <pre>{{ responseText || '尚未发起请求' }}</pre>
          </div>
        </section>

        <section class="shell-section example-panel">
          <div class="panel-head">
            <div>
              <h2 class="section-title">自动调用示例</h2>
              <p class="section-subtitle">示例会按当前选中的封装 Key 和模型自动生成。</p>
            </div>
          </div>

          <el-tabs v-model="activeExampleTab">
            <el-tab-pane label="cURL" name="curl" />
            <el-tab-pane label="JavaScript" name="javascript" />
            <el-tab-pane label="Python" name="python" />
          </el-tabs>

          <div class="code-toolbar">
            <el-tag type="info">{{ selectedModel || '未选择模型' }}</el-tag>
            <el-button size="small" @click="copyText(activeExample)">复制示例</el-button>
          </div>
          <pre class="code-block">{{ activeExample || '请先选择封装 Key 和模型' }}</pre>
        </section>
      </div>

      <div class="side-column">
        <section class="shell-section log-panel">
          <div class="panel-head">
            <div>
              <h2 class="section-title">实时消耗</h2>
              <p class="section-subtitle">每 5 秒自动刷新一次。</p>
            </div>
            <el-tag type="success">实时</el-tag>
          </div>

          <el-table :data="dashboard.recentLogs" size="small" empty-text="暂无调用记录">
            <el-table-column prop="model" label="模型" min-width="120" />
            <el-table-column prop="totalTokens" label="总 Token" width="110" />
            <el-table-column prop="status" label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="createdAt" label="时间" min-width="160" />
          </el-table>
        </section>
      </div>
    </section>

    <el-dialog v-model="tokenDialogVisible" :title="tokenForm.id ? '编辑封装 Key' : '新增封装 Key'" width="560px">
      <el-form :model="tokenForm" label-width="110px">
        <el-form-item label="名称">
          <el-input v-model="tokenForm.name" placeholder="例如：前端演示环境" />
        </el-form-item>
        <el-form-item v-if="tokenForm.id" label="Key">
          <el-input :model-value="tokenForm.key" readonly />
        </el-form-item>
        <el-form-item label="总额度">
          <el-input-number v-model="tokenForm.totalQuota" :min="0" :step="10000" />
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="tokenForm.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tokenDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveToken">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/utils/http'

type DashboardToken = {
  id: number
  name: string
  key: string
  enabled: boolean
  usedQuota: number
  totalQuota: number
  requestCount: number
  totalTokens: number
}

type DashboardLog = {
  id: number
  model: string
  totalTokens: number
  status: string
  createdAt: string
}

type DashboardResponse = {
  stats: {
    balance: number
    requestCount: number
    totalTokensUsed: number
    successRequests: number
    failedRequests: number
    lastRequestAt: string | null
  }
  tokens: DashboardToken[]
  recentLogs: DashboardLog[]
  models: string[]
}

type TokenForm = {
  id: number | null
  name: string
  key: string
  totalQuota: number
  enabled: boolean
}

const dashboard = ref<DashboardResponse>({
  stats: {
    balance: 0,
    requestCount: 0,
    totalTokensUsed: 0,
    successRequests: 0,
    failedRequests: 0,
    lastRequestAt: null
  },
  tokens: [],
  recentLogs: [],
  models: []
})

const selectedTokenId = ref<number | null>(null)
const selectedModel = ref('')
const playgroundPrompt = ref('请总结当前用户最近一次模型调用的大致用途。')
const running = ref(false)
const responseText = ref('')
const responseMeta = ref('')
const tokenDialogVisible = ref(false)
const tokenForm = ref<TokenForm>({
  id: null,
  name: '',
  key: '',
  totalQuota: 1000000,
  enabled: true
})
const activeExampleTab = ref('curl')
const examples = ref<Record<string, string>>({})
let refreshTimer: number | null = null

const metrics = computed(() => [
  { label: '封装 Key', value: dashboard.value.tokens.length, footnote: '按用户隔离' },
  { label: '累计请求', value: dashboard.value.stats.requestCount, footnote: `成功 ${dashboard.value.stats.successRequests}` },
  { label: '累计 Token', value: dashboard.value.stats.totalTokensUsed, footnote: `失败 ${dashboard.value.stats.failedRequests}` },
  { label: '最近调用', value: dashboard.value.stats.lastRequestAt || '-', footnote: '自动刷新' }
])

const activeExample = computed(() => examples.value[activeExampleTab.value] || '')

const applySample = () => {
  if (!selectedModel.value && dashboard.value.models.length) {
    selectedModel.value = dashboard.value.models[0]
  }
  playgroundPrompt.value = '请给我一份当前封装 Key 的调用治理建议，要求控制在 5 条以内。'
}

const selectToken = (id: number) => {
  selectedTokenId.value = id
  refreshExamples()
}

const ensureSelections = () => {
  if (!selectedTokenId.value && dashboard.value.tokens.length) {
    selectedTokenId.value = dashboard.value.tokens[0].id
  }
  if (!selectedModel.value && dashboard.value.models.length) {
    selectedModel.value = dashboard.value.models[0]
  }
}

const refreshDashboard = async () => {
  const res = await http.get('/api/user/dashboard')
  dashboard.value = res.data
  ensureSelections()
  await refreshExamples()
}

const refreshExamples = async () => {
  if (!selectedTokenId.value) {
    examples.value = {}
    return
  }
  const res = await http.get(`/api/user/tokens/${selectedTokenId.value}/examples`, {
    params: { model: selectedModel.value || undefined }
  })
  examples.value = res.data
}

const openTokenDialog = (token?: DashboardToken) => {
  if (token) {
    tokenForm.value = {
      id: token.id,
      name: token.name,
      key: token.key,
      totalQuota: token.totalQuota,
      enabled: token.enabled
    }
  } else {
    tokenForm.value = {
      id: null,
      name: '',
      key: '',
      totalQuota: 1000000,
      enabled: true
    }
  }
  tokenDialogVisible.value = true
}

const saveToken = async () => {
  if (!tokenForm.value.name) {
    ElMessage.warning('请填写名称')
    return
  }
  if (tokenForm.value.id) {
    await http.put(`/api/user/tokens/${tokenForm.value.id}`, tokenForm.value)
  } else {
    await http.post('/api/user/tokens', tokenForm.value)
  }
  tokenDialogVisible.value = false
  ElMessage.success('封装 Key 已保存')
  await refreshDashboard()
}

const deleteToken = async (id: number) => {
  await ElMessageBox.confirm('确认删除这个封装 Key？', '删除确认', { type: 'warning' })
  await http.delete(`/api/user/tokens/${id}`)
  ElMessage.success('封装 Key 已删除')
  if (selectedTokenId.value === id) {
    selectedTokenId.value = null
  }
  await refreshDashboard()
}

const copyText = async (text: string) => {
  await navigator.clipboard.writeText(text)
  ElMessage.success('已复制')
}

const runPlayground = async () => {
  const token = dashboard.value.tokens.find(item => item.id === selectedTokenId.value)
  if (!token || !selectedModel.value || !playgroundPrompt.value) {
    ElMessage.warning('请选择封装 Key、模型并填写 Prompt')
    return
  }

  running.value = true
  responseText.value = ''
  responseMeta.value = ''

  try {
    const res = await axios.post('/api/v1/chat/completions', {
      model: selectedModel.value,
      messages: [{ role: 'user', content: playgroundPrompt.value }],
      stream: false
    }, {
      headers: {
        Authorization: `Bearer ${token.key}`
      }
    })
    const usage = res.data?.usage
    responseText.value = res.data?.choices?.[0]?.message?.content || JSON.stringify(res.data, null, 2)
    responseMeta.value = usage
      ? `prompt ${usage.prompt_tokens ?? 0} / completion ${usage.completion_tokens ?? 0} / total ${usage.total_tokens ?? 0}`
      : 'success'
    await refreshDashboard()
  } catch (error: any) {
    responseText.value = JSON.stringify(error.response?.data || { message: 'Request failed' }, null, 2)
    responseMeta.value = `HTTP ${error.response?.status || 500}`
  } finally {
    running.value = false
  }
}

onMounted(async () => {
  try {
    await refreshDashboard()
  } catch {
    ElMessage.error('控制台数据加载失败')
  }
  refreshTimer = window.setInterval(() => {
    refreshDashboard().catch(() => {})
  }, 5000)
})

onBeforeUnmount(() => {
  if (refreshTimer) {
    window.clearInterval(refreshTimer)
  }
})
</script>

<style scoped>
.command-page,
.main-column,
.side-column {
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
  margin: 8px 0 0;
  color: #64748b;
}

.head-actions,
.playground-actions,
.token-actions,
.code-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
}

.command-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.3fr) minmax(340px, 0.7fr);
  gap: 20px;
}

.token-panel,
.playground,
.example-panel,
.log-panel {
  padding: 24px;
  border-radius: 8px;
}

.token-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.token-card {
  padding: 16px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  cursor: pointer;
  background: #fff;
}

.token-card.active {
  border-color: #409eff;
  box-shadow: 0 0 0 1px rgba(64, 158, 255, 0.16);
}

.token-card-head,
.token-meta,
.response-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.token-key {
  margin: 12px 0;
  padding: 10px 12px;
  background: #0f172a;
  color: #e2e8f0;
  border-radius: 8px;
  word-break: break-all;
  font-family: Consolas, monospace;
  font-size: 12px;
}

.token-meta {
  margin-top: 8px;
  color: #64748b;
  font-size: 13px;
}

.response-panel,
.code-block {
  margin-top: 18px;
  padding: 16px;
  border-radius: 8px;
  background: #0f172a;
  color: #e2e8f0;
  font-family: Consolas, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.code-toolbar {
  justify-content: space-between;
  margin-top: 8px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: start;
  gap: 16px;
  margin-bottom: 20px;
}

@media (max-width: 1080px) {
  .command-layout,
  .token-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
