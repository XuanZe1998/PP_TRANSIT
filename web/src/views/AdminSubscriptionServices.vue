<template>
  <section class="subscription-admin">
    <AdminPageToolbar title="订阅服务" description="16688 Open API 的商品、库存、采购、订单、投诉、账户与图片能力统一工作台。" />
    <el-alert
      v-if="config"
      :type="config.enabled && config.configured ? (config.mutationsAllowed ? 'success' : 'warning') : 'error'"
      :closable="false"
      show-icon
      :title="configTitle"
      :description="`网关：${config.gateway || '-'}。密钥不会返回到浏览器或写入请求日志。`"
    />

    <div class="operation-shell">
      <aside class="operation-nav">
        <section v-for="group in groups" :key="group">
          <strong>{{ group }}</strong>
          <button
            v-for="operation in operationsFor(group)"
            :key="operation.key"
            :class="{ active: operation.key === selectedKey }"
            @click="select(operation.key)"
          >
            <span>{{ operation.label }}</span><small>{{ operation.mutation ? '写入' : '读取' }}</small>
          </button>
        </section>
      </aside>

      <main v-if="selected" class="operation-panel">
        <header>
          <div><p>{{ selected.group }}</p><h2>{{ selected.label }}</h2><code>{{ selected.path }}</code></div>
          <el-tag :type="selected.mutation ? 'warning' : 'success'">{{ selected.mutation ? '会修改上游数据' : '只读操作' }}</el-tag>
        </header>

        <el-form label-position="top" class="operation-form">
          <el-form-item v-for="field in selected.fields" :key="field.name" :label="field.label" :required="field.required">
            <el-input
              v-if="field.type === 'text' || field.type === 'array'"
              v-model="form[field.name]" type="textarea" :rows="field.name === 'image' || field.name === 'content' ? 8 : 4"
              :placeholder="field.placeholder || (field.type === 'array' ? '每行一项' : '')"
            />
            <el-input-number v-else-if="field.type === 'int' || field.type === 'float'" v-model="form[field.name]" :precision="field.type === 'float' ? 4 : 0" controls-position="right" />
            <el-input v-else v-model="form[field.name]" :placeholder="field.placeholder" clearable />
          </el-form-item>
        </el-form>

        <el-alert v-if="selected.mutation" type="warning" :closable="false" show-icon title="执行前会再次确认；重复提交由服务端幂等记录保护。" />
        <div class="operation-actions">
          <el-button type="primary" :loading="running" :disabled="!config?.enabled || !config?.configured" @click="execute()">执行接口</el-button>
          <el-button @click="reset">清空参数</el-button>
        </div>

        <section v-if="result !== null" class="result-panel">
          <div class="result-title"><h3>返回结果</h3><span v-if="selected.paged">共 {{ total }} 条</span></div>
          <PagedTable v-if="rows.length" :data="rows" :pagination="selected.paged ? 'external' : 'local'" list-id="AdminSubscriptionServices-1">
            <el-table-column v-for="column in columns" :key="column" :label="column" min-width="150">
              <template #default="{ row }">{{ cell(row[column]) }}</template>
            </el-table-column>
          </PagedTable>
          <ListPagination v-if="selected.paged" v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="execute(false)" />
          <el-collapse>
            <el-collapse-item title="查看完整 JSON"><pre>{{ pretty(result) }}</pre></el-collapse-item>
          </el-collapse>
        </section>
      </main>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import http, { createIdempotencyKey, getHttpErrorMessage } from '@/utils/http'
import { subscriptionOperations, type SubscriptionOperation } from '@/config/subscriptionServiceOperations'

type Configuration = { enabled: boolean; configured: boolean; mutationsAllowed: boolean; gateway: string }
const config = ref<Configuration | null>(null)
const selectedKey = ref(subscriptionOperations[0].key)
const form = reactive<Record<string, any>>({})
const result = ref<any>(null)
const running = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const groups = Array.from(new Set(subscriptionOperations.map(item => item.group)))
const selected = computed(() => subscriptionOperations.find(item => item.key === selectedKey.value) || subscriptionOperations[0])
const rows = computed<Record<string, any>[]>(() => {
  if (Array.isArray(result.value?.items)) return result.value.items
  if (Array.isArray(result.value)) return result.value
  const list = result.value && typeof result.value === 'object'
    ? Object.values(result.value).find(value => Array.isArray(value)) : null
  return Array.isArray(list) ? list : []
})
const columns = computed(() => Array.from(new Set(rows.value.slice(0, 20).flatMap(row => Object.keys(row || {})))).slice(0, 12))
const configTitle = computed(() => {
  if (!config.value?.enabled) return '订阅服务尚未启用'
  if (!config.value?.configured) return '订阅服务凭据不完整'
  return config.value.mutationsAllowed ? '读写接口已就绪' : '只读接口已就绪，写操作开关关闭'
})

const operationsFor = (group: string) => subscriptionOperations.filter(item => item.group === group)
const pretty = (value: unknown) => JSON.stringify(value, null, 2)
const cell = (value: unknown) => value !== null && typeof value === 'object' ? JSON.stringify(value) : String(value ?? '')

function reset() {
  Object.keys(form).forEach(key => delete form[key])
  selected.value.fields.forEach(field => { form[field.name] = field.type === 'int' || field.type === 'float' ? undefined : '' })
  page.value = 1
  size.value = 10
  total.value = 0
  result.value = null
}

function select(key: string) {
  selectedKey.value = key
  reset()
}

function payloadFor(operation: SubscriptionOperation) {
  const payload: Record<string, unknown> = {}
  for (const field of operation.fields) {
    const value = form[field.name]
    if (value === '' || value === undefined || value === null) {
      if (field.required) throw new Error(`请填写“${field.label}”`)
      continue
    }
    if (field.type === 'array') {
      const values = String(value).split(/[\n,，]+/u).map(item => item.trim()).filter(Boolean)
      if (field.name === 'ids') {
        const ids = values.map(item => Number(item))
        if (ids.some(item => !Number.isSafeInteger(item) || item < 1)) throw new Error(`“${field.label}”只能包含正整数`)
        payload[field.name] = ids
      } else payload[field.name] = values
    } else payload[field.name] = value
  }
  if (operation.paged) {
    payload.page_no = page.value
    payload.page_size = size.value
  }
  return payload
}

async function execute(confirmMutation = true) {
  const operation = selected.value
  let payload: Record<string, unknown>
  try { payload = payloadFor(operation) }
  catch (caught) { ElMessage.warning(caught instanceof Error ? caught.message : '请检查参数'); return }
  if (operation.mutation && confirmMutation) {
    try {
      await ElMessageBox.confirm(`确认执行“${operation.label}”？该操作会修改上游数据或钱包。`, '上游写操作确认', { type: 'warning', confirmButtonText: '确认执行' })
    } catch { return }
  }
  running.value = true
  try {
    const response = await http.post(`/api/admin/api/subscription-services/operations/${operation.key}`, payload, {
      headers: operation.mutation ? { 'Idempotency-Key': createIdempotencyKey(`subscription-${operation.key}`) } : undefined,
      timeout: 120_000
    })
    result.value = response.data
    total.value = Number(response.data?.total ?? (Array.isArray(response.data) ? response.data.length : 0))
    if (operation.paged) page.value = Number(response.data?.page || page.value)
    ElMessage.success('接口执行成功')
  } catch (caught) { ElMessage.error(getHttpErrorMessage(caught, '接口执行失败')) }
  finally { running.value = false }
}

onMounted(async () => {
  reset()
  try {
    const response = await http.get<Configuration>('/api/admin/api/subscription-services/configuration')
    config.value = response.data
    const remote = await http.get<Array<{ key: string }>>('/api/admin/api/subscription-services/operations')
    const remoteKeys = new Set((remote.data || []).map(item => item.key))
    const missing = subscriptionOperations.filter(item => !remoteKeys.has(item.key))
    if (missing.length) ElMessage.warning(`后端尚缺 ${missing.length} 个订阅服务操作`)
  } catch (caught) { ElMessage.error(getHttpErrorMessage(caught, '订阅服务配置读取失败')) }
})
</script>

<style scoped>
.subscription-admin { display: grid; gap: 18px; }
.operation-shell { display: grid; grid-template-columns: 250px minmax(0, 1fr); gap: 18px; align-items: start; }
.operation-nav, .operation-panel { border: 1px solid #dce6f2; border-radius: 14px; background: #fff; }
.operation-nav { max-height: calc(100vh - 180px); overflow: auto; padding: 12px; }
.operation-nav section { display: grid; gap: 4px; margin-bottom: 14px; }
.operation-nav strong { padding: 8px; color: #667991; font-size: 12px; }
.operation-nav button { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 9px 10px; border: 0; border-radius: 8px; background: transparent; color: #334155; text-align: left; cursor: pointer; }
.operation-nav button.active { background: #e8f2ff; color: #1769ff; font-weight: 700; }
.operation-nav button small { color: #94a3b8; }
.operation-panel { min-width: 0; padding: 22px; }
.operation-panel > header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; padding-bottom: 18px; border-bottom: 1px solid #e8eef5; }
.operation-panel header p, .operation-panel header h2 { margin: 0 0 6px; }
.operation-panel code { color: #64748b; }
.operation-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; margin-top: 18px; }
.operation-form :deep(.el-textarea), .operation-form :deep(.el-form-item:has(.el-textarea)) { grid-column: 1 / -1; }
.operation-actions { display: flex; gap: 10px; margin: 18px 0; }
.result-panel { display: grid; gap: 14px; margin-top: 22px; }
.result-title { display: flex; align-items: center; justify-content: space-between; }
.result-title h3 { margin: 0; }
pre { max-height: 520px; overflow: auto; margin: 0; padding: 14px; border-radius: 8px; background: #0f172a; color: #dbeafe; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 900px) { .operation-shell, .operation-form { grid-template-columns: 1fr; } .operation-nav { max-height: 340px; } }
</style>
