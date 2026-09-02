<template>
  <section class="model-probe-panel">
    <div class="probe-head">
      <div>
        <h2>模型鉴别</h2>
        <p>对任意 OpenAI 兼容端点执行质量、安全、完整性与身份探针，产出具 0-100 评分报告与身份鉴别结论（用于检测模型调包 / 降级）。</p>
      </div>
      <el-button :icon="Refresh" :loading="loadingList" @click="loadList">刷新</el-button>
    </div>

    <el-form :model="form" label-position="top" class="probe-form" @submit.prevent>
      <el-row :gutter="16">
        <el-col :xs="24" :sm="12">
          <el-form-item label="Base URL（端点地址）" required>
            <el-input v-model="form.baseUrl" placeholder="https://api.example.com/v1" clearable />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="API Key" required>
            <el-input v-model="form.apiKey" type="password" show-password placeholder="目标端点的调用凭证" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="Model ID" required>
            <el-input v-model="form.modelId" placeholder="例如 gpt-4o" />
          </el-form-item>
        </el-col>
        <el-col :xs="24" :sm="12">
          <el-form-item label="声称运行的模型（可选）">
            <el-input v-model="form.claimedModel" placeholder="厂商声称的模型，用于身份校验" />
          </el-form-item>
        </el-col>
      </el-row>
      <div class="probe-form-actions">
        <el-checkbox v-model="form.includeOptional">包含可选探针（如上下文长度检测，耗时更长）</el-checkbox>
        <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
          <el-icon><MagicStick /></el-icon>开始鉴别
        </el-button>
      </div>
    </el-form>

    <el-table v-loading="loadingList" :data="tasks" class="probe-table" empty-text="暂无鉴别任务">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="baseUrl" label="Base URL" min-width="180" show-overflow-tooltip />
      <el-table-column prop="modelId" label="模型" min-width="120" />
      <el-table-column label="评分" width="110">
        <template #default="{ row }">
          <template v-if="row.status === 'SUCCESS'">
            <strong :class="scoreClass(row.score)">{{ row.score }}</strong>
            <span class="score-max">/ {{ row.scoreMax }}</span>
          </template>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="row.status !== 'SUCCESS' && row.status !== 'FAILED'" @click="openDetail(row)">
            {{ row.status === 'SUCCESS' ? '查看报告' : row.status === 'FAILED' ? '查看错误' : '详情' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-if="total > pageSize" class="probe-pagination" background layout="prev, pager, next"
      :total="total" :page-size="pageSize" :current-page="page" @current-change="changePage" />

    <el-dialog v-model="detailVisible" :title="detailTitle" width="820px" class="probe-detail-dialog">
      <el-descriptions v-if="detail" :column="2" border size="small" class="probe-desc">
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(detail.status)" size="small">{{ statusLabel(detail.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="评分">
          <template v-if="detail.status === 'SUCCESS'">
            <strong :class="scoreClass(detail.score)">{{ detail.score }}</strong> / {{ detail.scoreMax }}
          </template>
          <span v-else class="muted">-</span>
        </el-descriptions-item>
        <el-descriptions-item label="Base URL">{{ detail.baseUrl }}</el-descriptions-item>
        <el-descriptions-item label="模型">{{ detail.modelId }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.claimedModel" label="声称模型">{{ detail.claimedModel }}</el-descriptions-item>
        <el-descriptions-item v-if="detail.error" label="错误信息" :span="2">
          <el-text type="danger">{{ detail.error }}</el-text>
        </el-descriptions-item>
      </el-descriptions>

      <template v-if="detail && detail.report">
        <h4 class="report-title">鉴别报告</h4>
        <pre class="report-json">{{ reportJson }}</pre>
      </template>
      <template v-else-if="detail && detail.status === 'SUCCESS'">
        <el-empty description="报告暂不可用" :image-size="64" />
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick, Refresh } from '@element-plus/icons-vue'
import http, { createIdempotencyKey, getHttpErrorNotice } from '@/utils/http'

const props = withDefaults(defineProps<{ endpoint: string }>(), {
  endpoint: '/user/model-probe'
})

type ProbeTask = Record<string, any>

const submitting = ref(false)
const loadingList = ref(false)
const tasks = ref<ProbeTask[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20

const form = ref({ baseUrl: '', apiKey: '', modelId: '', claimedModel: '', includeOptional: false })
const canSubmit = computed(() => !!(form.value.baseUrl && form.value.apiKey && form.value.modelId))

const detailVisible = ref(false)
const detail = ref<ProbeTask | null>(null)

async function submit() {
  if (!canSubmit.value) {
    ElMessage.warning('请填写 Base URL、API Key 和 Model ID')
    return
  }
  submitting.value = true
  try {
    const { data } = await http.post(props.endpoint, {
      baseUrl: form.value.baseUrl.trim(),
      apiKey: form.value.apiKey,
      modelId: form.value.modelId.trim(),
      claimedModel: form.value.claimedModel?.trim() || undefined,
      includeOptional: form.value.includeOptional
    }, {
      headers: { 'Idempotency-Key': createIdempotencyKey('model-probe') },
      timeout: 30_000
    })
    ElMessage.success(`任务已提交（ID：${data?.id ?? ''}）`)
    form.value.apiKey = ''
    await loadList()
    startPolling()
  } catch (error) {
    ElMessage.error(getHttpErrorNotice(error, '操作失败，请稍后重试'))
  } finally {
    submitting.value = false
  }
}

async function loadList() {
  loadingList.value = true
  try {
    const { data } = await http.get(props.endpoint, { params: { page: page.value, size: pageSize } })
    tasks.value = data?.items || []
    total.value = Number(data?.total || 0)
  } catch (error) {
    ElMessage.error(getHttpErrorNotice(error, '操作失败，请稍后重试'))
  } finally {
    loadingList.value = false
  }
}

let pollTimer: number | null = null
function startPolling() {
  if (pollTimer) return
  pollTimer = window.setInterval(async () => {
    await loadList()
    const pending = tasks.value.some(t => t.status === 'SUBMITTED' || t.status === 'RUNNING')
    if (!pending && pollTimer) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }, 4000)
}

function changePage(p: number) {
  page.value = p
  loadList()
}

function openDetail(row: ProbeTask) {
  detail.value = row
  detailVisible.value = true
}

const reportJson = computed(() => {
  if (!detail.value?.report) return ''
  const raw = detail.value.report
  try {
    return JSON.stringify(typeof raw === 'string' ? JSON.parse(raw) : raw, null, 2)
  } catch {
    return typeof raw === 'string' ? raw : JSON.stringify(raw, null, 2)
  }
})

const detailTitle = computed(() => `鉴别任务 #${detail.value?.id ?? ''}`)

function statusLabel(status?: string) {
  return { SUBMITTED: '排队中', RUNNING: '运行中', SUCCESS: '已完成', FAILED: '失败' }[status || ''] || (status || '-')
}
function statusType(status?: string) {
  return { SUBMITTED: 'info', RUNNING: 'warning', SUCCESS: 'success', FAILED: 'danger' }[status || ''] as any || 'info'
}
function scoreClass(score?: number | string) {
  const n = Number(score || 0)
  if (n >= 80) return 'score-high'
  if (n >= 50) return 'score-mid'
  return 'score-low'
}
function formatTime(value?: string | null) {
  if (!value) return '-'
  const d = new Date(value)
  return isNaN(d.getTime()) ? String(value) : d.toLocaleString()
}
function getErrorMessageFor(error: unknown) {
  return getHttpErrorNotice(error, '操作失败，请稍后重试')
}

onMounted(() => {
  loadList()
  startPolling()
})
onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.model-probe-panel { display: flex; flex-direction: column; gap: 18px; }
.probe-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.probe-head h2 { margin: 0 0 6px; font-size: 20px; color: #111827; }
.probe-head p { margin: 0; color: #6b7280; font-size: 13px; line-height: 1.6; max-width: 720px; }
.probe-form { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px; padding: 18px; }
.probe-form-actions { display: flex; justify-content: space-between; align-items: center; margin-top: 4px; gap: 12px; flex-wrap: wrap; }
.probe-table { width: 100%; }
.probe-pagination { display: flex; justify-content: flex-end; }
.score-max { color: #9ca3af; font-size: 12px; }
.muted { color: #9ca3af; }
.score-high { color: #16a34a; font-weight: 700; }
.score-mid { color: #f59e0b; font-weight: 700; }
.score-low { color: #ef4444; font-weight: 700; }
.probe-desc { margin-bottom: 16px; }
.report-title { margin: 4px 0 10px; font-size: 15px; color: #111827; }
.report-json {
  background: #0f172a; color: #d1e5ff; border-radius: 10px; padding: 16px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px; line-height: 1.6; max-height: 420px; overflow: auto; white-space: pre-wrap; word-break: break-word;
}
</style>