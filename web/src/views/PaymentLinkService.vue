<template>
  <section class="payment-link-page">
    <div class="pl-title-row">
      <div>
        <p class="pl-eyebrow">PAYMENT LINK GENERATOR</p>
        <h2>支付链接生成</h2>
        <p>通过 ChatGPT session-token 自动生成 Stripe 托管支付长链，支持 {{ regions.length || '多' }} 个地区。</p>
      </div>
      <div class="pl-service-state">
        <el-tag :type="serviceChecking ? 'info' : serviceOnline ? 'success' : 'danger'">
          {{ serviceChecking ? '检测中' : serviceOnline ? '服务在线' : '服务离线' }}
        </el-tag>
        <el-button v-if="!serviceOnline && !serviceChecking" link type="primary" @click="checkService">
          重新检测
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="!serviceChecking && !serviceOnline"
      :title="serviceMessage"
      type="warning"
      show-icon
      :closable="false"
      class="pl-service-alert"
    />

    <!-- 输入区 -->
    <el-card class="pl-card" shadow="never">
      <template #header>
        <span>生成支付链接</span>
      </template>

      <el-form :model="form" label-width="100px" label-position="top">
        <el-form-item label="Session Token" required>
          <el-input
            v-model="form.token"
            type="textarea"
            :rows="4"
            placeholder="粘贴 ChatGPT session-token 或 accessToken (支持JSON格式和纯JWT)"
          />
        </el-form-item>

        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="支付地区">
              <el-select v-model="form.country" filterable placeholder="选择地区" style="width:100%">
                <el-option
                  v-for="r in regions"
                  :key="r.code"
                  :label="r.label"
                  :value="r.code"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="币种">
              <el-input :value="currentCurrency" disabled />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item>
          <el-button
            type="primary"
            :loading="submitting"
            :disabled="!serviceOnline || !form.token.trim()"
            @click="startGenerate"
          >
            生成支付链接
          </el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 进度区 (SSE) -->
    <el-card v-if="taskId" class="pl-card" shadow="never" style="margin-top:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>实时进度</span>
          <el-tag :type="progressType">{{ progressLabel }}</el-tag>
        </div>
      </template>

      <div class="pl-progress-log">
        <div v-for="(entry, i) in progressEntries" :key="i" class="pl-log-item">
          <span class="pl-log-time">{{ entry.time }}</span>
          <el-tag v-if="entry.type === 'success'" type="success" size="small">OK</el-tag>
          <el-tag v-else-if="entry.type === 'error'" type="danger" size="small">ERR</el-tag>
          <el-tag v-else size="small" type="info">INFO</el-tag>
          <span class="pl-log-msg">{{ entry.message }}</span>
        </div>
        <div v-if="sseConnecting" class="pl-log-item">
          <el-icon class="is-loading"><Loading /></el-icon>
          <span style="color:#999;margin-left:8px">等待进度推送...</span>
        </div>
      </div>
    </el-card>

    <!-- 结果区 -->
    <el-card v-if="result" class="pl-card" shadow="never" style="margin-top:16px">
      <template #header>
        <span>生成结果</span>
      </template>

      <el-descriptions :column="1" border>
        <el-descriptions-item label="Stripe 托管长链">
          <div style="display:flex;align-items:center;gap:8px">
            <el-input :model-value="result.hosted_url" readonly style="flex:1" />
            <el-button type="primary" link @click="copyText(result.hosted_url)">复制</el-button>
            <el-button type="primary" link @click="openUrl(result.hosted_url)">打开</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item v-if="result.short_url" label="ChatGPT 短链">
          <div style="display:flex;align-items:center;gap:8px">
            <el-input :model-value="result.short_url" readonly style="flex:1" />
            <el-button type="primary" link @click="copyText(result.short_url)">复制</el-button>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="地区">{{ result.country }}</el-descriptions-item>
        <el-descriptions-item label="币种">{{ result.currency }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 历史记录 -->
    <el-card class="pl-card" shadow="never" style="margin-top:16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>生成历史</span>
          <el-button link type="danger" @click="history = []">清空</el-button>
        </div>
      </template>
      <el-table :data="history" empty-text="暂无记录" size="small">
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ row.time }}</template>
        </el-table-column>
        <el-table-column label="地区" width="80">
          <template #default="{ row }">{{ row.country }}</template>
        </el-table-column>
        <el-table-column label="链接" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.hosted_url }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="copyText(row.hosted_url)">复制</el-button>
            <el-button link type="primary" @click="openUrl(row.hosted_url)">打开</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { getToken } from '@/utils/auth'

interface Region { code: string; currency: string; label: string }
interface ProgressEntry { time: string; message: string; type: 'info' | 'success' | 'error' }
interface HistoryEntry { time: string; country: string; hosted_url: string }

const serviceOnline = ref(false)
const serviceChecking = ref(false)
const serviceMessage = ref('Python 支付服务未连接，请确认服务已启动。')
const regions = ref<Region[]>([])
const submitting = ref(false)
const taskId = ref('')
const sseConnecting = ref(false)
const progressEntries = ref<ProgressEntry[]>([])
const result = ref<any>(null)
const history = ref<HistoryEntry[]>([])

const form = ref({
  token: '',
  country: 'US'
})

const currentCurrency = computed(() => {
  const r = regions.value.find(x => x.code === form.value.country)
  return r ? r.currency : ''
})

const progressLabel = computed(() => {
  if (!taskId.value) return ''
  const last = progressEntries.value[progressEntries.value.length - 1]
  if (!last) return '处理中'
  if (last.type === 'error') return '失败'
  if (last.message?.includes('完成') || last.message?.includes('成功')) return '完成'
  return '处理中'
})

const progressType = computed(() => {
  if (!taskId.value) return 'info'
  const last = progressEntries.value[progressEntries.value.length - 1]
  if (!last) return 'info'
  if (last.type === 'error') return 'danger'
  if (last.message?.includes('完成') || last.message?.includes('成功')) return 'success'
  return 'warning'
})

let sseAbort: AbortController | null = null
const PAYMENT_API_PREFIX = '/api/payment-service/api'

async function checkService() {
  serviceChecking.value = true
  try {
    const res = await http.get(`${PAYMENT_API_PREFIX}/regions`)
    regions.value = (res.data?.regions || res.data || []).map((r: any) => ({
      code: r[0] || r.code,
      currency: r[1]?.currency || r.currency || '',
      label: r[1]?.label || r.label || r[0] || r.code
    }))
    serviceOnline.value = true
    serviceMessage.value = 'Python 支付服务连接正常。'
  } catch (error) {
    serviceOnline.value = false
    serviceMessage.value = getHttpErrorMessage(
      error,
      'Python 支付服务未连接，请启动 payment-service 后重试。'
    )
  } finally {
    serviceChecking.value = false
  }
}

async function startGenerate() {
  if (!form.value.token.trim()) return

  submitting.value = true
  taskId.value = ''
  progressEntries.value = []
  result.value = null

  try {
    const res = await http.post(`${PAYMENT_API_PREFIX}/subscribe`, {
      token: form.value.token.trim(),
      country: form.value.country
    })

    if (res.data?.task_id) {
      taskId.value = res.data.task_id
      connectSSE(res.data.task_id)
    } else if (res.data?.hosted_url) {
      // 直接返回了结果（非SSE模式）
      result.value = res.data
      addProgress('支付链接生成成功', 'success')
      addToHistory(res.data)
    } else {
      addProgress(res.data?.error || res.data?.message || '支付服务返回了未知响应', 'error')
    }
  } catch (error) {
    addProgress(getHttpErrorMessage(error, '支付链接生成请求失败'), 'error')
  } finally {
    submitting.value = false
  }
}

function connectSSE(tid: string) {
  if (sseAbort) sseAbort.abort()
  sseConnecting.value = true
  sseAbort = new AbortController()

  const url = paymentApiUrl(`/events/${encodeURIComponent(tid)}`)
  const token = getToken()

  fetch(url, {
    signal: sseAbort.signal,
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
    .then(response => {
      if (!response.ok) {
        throw new Error(`进度服务返回 HTTP ${response.status}`)
      }
      sseConnecting.value = false
      const reader = response.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''
      let eventName = 'message'

      function read() {
        reader!.read().then(({ done, value }) => {
          if (done) {
            sseConnecting.value = false
            return
          }
          buffer += decoder.decode(value, { stream: true })

          const lines = buffer.split('\n')
          buffer = lines.pop() || ''

          for (const line of lines) {
            if (line.startsWith('event:')) {
              eventName = line.slice(6).trim() || 'message'
            } else if (line.startsWith('data:')) {
              try {
                const data = JSON.parse(line.slice(5).trim())
                handleSSEData(data, eventName)
              } catch {}
            } else if (!line.trim()) {
              eventName = 'message'
            }
          }
          read()
        })
      }
      read()
    })
    .catch(e => {
      sseConnecting.value = false
      if (e.name !== 'AbortError') {
        addProgress('SSE 连接失败: ' + e.message, 'error')
      }
    })
}

function paymentApiUrl(path: string) {
  const configuredBaseUrl = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  const apiPath = `${PAYMENT_API_PREFIX}${path}`
  if (!configuredBaseUrl) return apiPath
  return `${configuredBaseUrl}${apiPath.replace(/^\/api/, '')}`
}

function handleSSEData(data: any, eventName = 'message') {
  if (eventName === 'ping') return
  const isDone = eventName === 'done' || data.done || data.complete
  const failed = Boolean(data.error) || (isDone && data.success === false)
  const msg = data.message
    || data.msg
    || data.detail
    || (isDone ? (failed ? '处理失败' : '处理完成') : JSON.stringify(data))
  const type = failed ? 'error' : isDone ? 'success' : 'info'
  addProgress(msg, type)

  if (data.hosted_url) {
    result.value = data
    addToHistory(data)
  }
  if (isDone) {
    sseAbort?.abort()
    sseConnecting.value = false
  }
}

function addProgress(message: string, type: 'info' | 'success' | 'error' = 'info') {
  const now = new Date()
  const time = now.toLocaleTimeString('zh-CN', { hour12: false })
  progressEntries.value.push({ time, message, type })
  // 自动滚到底
  if (progressEntries.value.length > 200) {
    progressEntries.value = progressEntries.value.slice(-100)
  }
}

function addToHistory(data: any) {
  if (!data?.hosted_url || history.value.some(entry => entry.hosted_url === data.hosted_url)) return
  history.value.unshift({
    time: new Date().toLocaleString('zh-CN'),
    country: form.value.country,
    hosted_url: data.hosted_url
  })
  if (history.value.length > 50) history.value = history.value.slice(0, 50)
}

function copyText(text: string) {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  }).catch(() => {
    ElMessage.error('复制失败')
  })
}

function openUrl(url: string) {
  window.open(url, '_blank')
}

function resetForm() {
  form.value.token = ''
  form.value.country = 'US'
  taskId.value = ''
  progressEntries.value = []
  result.value = null
}

onMounted(() => {
  checkService()
})

onUnmounted(() => {
  if (sseAbort) sseAbort.abort()
})
</script>

<style scoped>
.payment-link-page {
  padding: 24px;
  max-width: 960px;
  margin: 0 auto;
}

.pl-title-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.pl-service-state {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pl-service-alert {
  margin-bottom: 16px;
}

.pl-eyebrow {
  font-size: 12px;
  letter-spacing: 1.5px;
  color: #909399;
  margin-bottom: 4px;
}

.pl-title-row h2 {
  margin: 0 0 4px;
  font-size: 22px;
}

.pl-title-row p {
  color: #606266;
  margin: 0;
  font-size: 14px;
}

.pl-card :deep(.el-card__header) {
  padding: 14px 20px;
  font-weight: 600;
  font-size: 15px;
}

.pl-progress-log {
  max-height: 320px;
  overflow-y: auto;
  background: #f5f7fa;
  border-radius: 6px;
  padding: 12px;
}

.pl-log-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
  font-size: 13px;
}

.pl-log-time {
  color: #909399;
  font-size: 12px;
  min-width: 70px;
}

.pl-log-msg {
  flex: 1;
  word-break: break-all;
}
</style>
