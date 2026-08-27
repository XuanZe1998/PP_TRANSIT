<template>
  <div class="vmcard-page">
    <section class="vmcard-panel vmcard-overview">
      <div class="vmcard-title-row">
        <div>
          <p class="vmcard-eyebrow">VMCARDIO API</p>
          <h2>虚拟卡接口测试台</h2>
          <p>覆盖 accessToken、12 个卡操作以及交易/3DS Webhook。所有第三方密钥与 RSA 运算均在服务端完成。</p>
        </div>
        <el-button :loading="tokenChecking" :disabled="!configuration.configured" @click="checkToken">
          验证 accessToken
        </el-button>
      </div>

      <div class="vmcard-status-grid">
        <article>
          <span>集成状态</span>
          <el-tag :type="configuration.enabled && configuration.configured ? 'success' : 'warning'">
            {{ configuration.enabled && configuration.configured ? '可连接' : '待配置' }}
          </el-tag>
        </article>
        <article>
          <span>运行环境</span>
          <strong>{{ configuration.environment === 'production' ? '生产环境' : '沙箱环境' }}</strong>
        </article>
        <article>
          <span>RSA 加解密</span>
          <el-tag :type="configuration.encryptionConfigured ? 'success' : 'warning'">
            {{ configuration.encryptionConfigured ? '已配置' : '未配置' }}
          </el-tag>
        </article>
        <article>
          <span>写操作</span>
          <el-tag :type="configuration.mutationsAllowed ? 'danger' : 'info'">
            {{ configuration.mutationsAllowed ? '已解锁' : '已锁定' }}
          </el-tag>
        </article>
      </div>

      <el-alert
        v-if="!configuration.enabled || !configuration.configured || !configuration.encryptionConfigured"
        type="warning"
        :closable="false"
        show-icon
        title="请先在服务端配置 VMCARD_ENABLED、VMCARD_APP_ID、VMCARD_APP_SECRET、VMCARD_PUBLIC_KEY 和 VMCARD_PRIVATE_KEY。"
      />
    </section>

    <el-tabs v-model="activeTab" class="vmcard-tabs">
      <el-tab-pane label="接口调试" name="console">
        <section class="vmcard-console-grid">
          <div class="vmcard-panel operation-list">
            <h3>接口列表</h3>
            <button
              v-for="operation in configuration.operations"
              :key="operation.id"
              :class="{ active: selectedOperation?.id === operation.id }"
              type="button"
              @click="selectOperation(operation)"
            >
              <span>
                <strong>{{ operation.label }}</strong>
                <small>POST {{ operation.path }}</small>
              </span>
              <el-tag v-if="operation.mutating" size="small" type="danger">写操作</el-tag>
              <el-tag v-else size="small" type="info">查询</el-tag>
            </button>
          </div>

          <div class="vmcard-panel request-panel">
            <div class="request-head">
              <div>
                <p class="vmcard-eyebrow">{{ selectedOperation?.path || '请选择接口' }}</p>
                <h3>{{ selectedOperation?.label || '接口请求' }}</h3>
              </div>
              <el-button
                type="primary"
                :loading="executing"
                :disabled="!selectedOperation || !configuration.enabled || !configuration.configured"
                @click="executeOperation"
              >
                发送请求
              </el-button>
            </div>

            <el-alert
              v-if="selectedOperation?.id === 'createCard'"
              type="info"
              :closable="false"
              show-icon
              title="product_code 由后端自动选择：只会使用已启用且剩余开卡数量大于 0 的产品码。"
            />

            <el-alert
              v-if="selectedOperation?.id === 'cardDetail'"
              type="info"
              :closable="false"
              show-icon
              title="卡详情需要 card_id（不是卡号）。如果本地已有申请记录，系统会自动填入最近一张卡的 card_id。"
            />

            <el-alert
              v-if="selectedOperation?.mutating"
              type="error"
              :closable="false"
              show-icon
              title="该接口会改变卡片或资金状态。沙箱测试已开放；生产环境仍需要第二道独立开关。"
            />

            <label class="json-label" for="vmcard-request-json">业务参数 JSON</label>
            <el-input
              id="vmcard-request-json"
              v-model="requestJson"
              type="textarea"
              :rows="15"
              resize="vertical"
              spellcheck="false"
            />

            <div class="response-head">
              <h3>响应结果</h3>
              <span v-if="responseTime">{{ responseTime }}</span>
            </div>
            <pre class="response-json">{{ responseJson || '请求结果将在这里显示。' }}</pre>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="产品码" name="products">
        <section class="vmcard-panel">
          <div class="product-code-head">
            <div>
              <h3>VMCard 产品码</h3>
              <p>申请卡只会自动选择“可用”且剩余开卡数量大于 0 的产品码。</p>
            </div>
            <el-button type="primary" :loading="productsRefreshing" @click="refreshProductCodes">
              从 VMCard 刷新
            </el-button>
          </div>
          <el-table v-loading="productsLoading" :data="productCodes" empty-text="暂无产品码，请先从 VMCard 刷新">
            <el-table-column prop="productCode" label="产品码" min-width="140" fixed />
            <el-table-column prop="bin" label="BIN" min-width="120" />
            <el-table-column prop="type" label="类型" min-width="100" />
            <el-table-column prop="network" label="卡组织" min-width="100" />
            <el-table-column prop="media" label="介质" min-width="100" />
            <el-table-column prop="issuingArea" label="发行地区" min-width="120" />
            <el-table-column prop="remainingOpenCardNum" label="剩余数量" width="110" align="right" />
            <el-table-column label="可用" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-switch
                  v-model="row.available"
                  :loading="availabilityUpdating === row.id"
                  @change="updateProductAvailability(row)"
                />
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100" align="center" fixed="right">
              <template #default="{ row }">
                <el-tag :type="row.available && row.remainingOpenCardNum > 0 ? 'success' : 'info'">
                  {{ row.available && row.remainingOpenCardNum > 0 ? '可申请' : '不可申请' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="本地卡片" name="cards">
        <section class="vmcard-panel">
          <div class="saved-card-head">
            <div>
              <h3>已申请的卡片</h3>
              <p>申请参数、申请响应和卡片详情均使用 AES-256-GCM 加密后保存在本地数据库。</p>
            </div>
            <el-button :loading="cardsLoading" @click="loadSavedCards">刷新</el-button>
          </div>
          <el-table :data="savedCards" empty-text="暂无本地卡片记录">
            <el-table-column type="expand">
              <template #default="{ row }">
                <el-alert
                  type="warning"
                  :closable="false"
                  title="展开内容可能包含卡号等敏感信息，请勿截图或转发。"
                />
                <pre class="event-json">{{ formatJson(row.payload) }}</pre>
              </template>
            </el-table-column>
            <el-table-column prop="cardId" label="卡片 ID" min-width="210" show-overflow-tooltip />
            <el-table-column prop="label" label="标签" min-width="150" show-overflow-tooltip />
            <el-table-column prop="productCode" label="产品代码" min-width="160" show-overflow-tooltip />
            <el-table-column prop="email" label="邮箱" min-width="220" show-overflow-tooltip />
            <el-table-column prop="environment" label="环境" width="110" />
            <el-table-column prop="cardCreatedAt" label="卡片创建日期" width="190" />
            <el-table-column prop="disabledOrFrozenAt" label="禁用/冻结日期" width="190">
              <template #default="{ row }">{{ row.disabledOrFrozenAt || '-' }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" label="本地入库时间" width="190" />
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="Webhook 记录" name="webhooks">
        <section class="vmcard-panel">
          <div class="webhook-head">
            <div>
              <h3>最近接收的通知</h3>
              <p>支持普通交易通知和 Card3ds 通知；敏感载荷在数据库中加密保存。</p>
            </div>
            <el-button :loading="eventsLoading" @click="loadEvents">刷新</el-button>
          </div>
          <el-alert
            v-if="!configuration.webhookConfigured"
            type="warning"
            :closable="false"
            title="请配置 VMCARD_WEBHOOK_SECRET，再将 /webhooks/vmcard/{secret} 设置为 VMCard Webhook 地址。"
          />
          <el-table :data="events" empty-text="暂无 Webhook 通知">
            <el-table-column type="expand">
              <template #default="{ row }">
                <pre class="event-json">{{ formatJson(row.payload) }}</pre>
              </template>
            </el-table-column>
            <el-table-column prop="eventType" label="类型" width="140" />
            <el-table-column prop="externalId" label="事件标识" min-width="220" show-overflow-tooltip />
            <el-table-column prop="receivedAt" label="接收时间" width="190" />
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

type Operation = {
  id: string
  label: string
  method: string
  path: string
  mutating: boolean
  sampleJson: string
}

type Configuration = {
  enabled: boolean
  configured: boolean
  encryptionConfigured: boolean
  environment: string
  baseUrl: string
  mutationsAllowed: boolean
  productionMutationsAllowed: boolean
  webhookConfigured: boolean
  operations: Operation[]
}

type WebhookEvent = {
  id: number
  eventType: string
  externalId: string
  receivedAt: string
  payload: unknown
}

type SavedCard = {
  id: number
  cardId: string
  environment: string
  label?: string
  productCode?: string
  email?: string
  cardCreatedAt?: string
  disabledOrFrozenAt?: string
  createdAt: string
  updatedAt?: string
  payload: unknown
}

type ProductCode = {
  id: number
  environment: string
  bin: string
  productCode: string
  type: string
  network: string
  media: string
  issuingArea: string
  remainingOpenCardNum: number
  available: boolean
  usable: boolean
  createdAt?: string
  updatedAt?: string
}

const emptyConfiguration: Configuration = {
  enabled: false,
  configured: false,
  encryptionConfigured: false,
  environment: 'sandbox',
  baseUrl: '',
  mutationsAllowed: false,
  productionMutationsAllowed: false,
  webhookConfigured: false,
  operations: []
}

const configuration = ref<Configuration>(emptyConfiguration)
const selectedId = ref('accountBalance')
const requestJson = ref('{}')
const responseJson = ref('')
const responseTime = ref('')
const activeTab = ref('console')
const executing = ref(false)
const tokenChecking = ref(false)
const eventsLoading = ref(false)
const events = ref<WebhookEvent[]>([])
const cardsLoading = ref(false)
const savedCards = ref<SavedCard[]>([])
const productsLoading = ref(false)
const productsRefreshing = ref(false)
const availabilityUpdating = ref<number | null>(null)
const productCodes = ref<ProductCode[]>([])
const cardIdOperationIds = new Set([
  'cardDetail',
  'updateCardLimit',
  'freezeCard',
  'rechargeCard',
  'refundCard',
  'deleteCard'
])

const selectedOperation = computed(() => configuration.value.operations.find(item => item.id === selectedId.value))

function selectOperation(operation: Operation) {
  selectedId.value = operation.id
}

watch(selectedOperation, operation => {
  if (!operation) return
  requestJson.value = formatJson(operationSample(operation))
  responseJson.value = ''
  responseTime.value = ''
})

function operationSample(operation: Operation) {
  const sample = JSON.parse(operation.sampleJson || '{}') as Record<string, unknown>
  if (cardIdOperationIds.has(operation.id) && !sample.card_id && savedCards.value[0]?.cardId) {
    sample.card_id = savedCards.value[0].cardId
  }
  return sample
}

async function loadConfiguration() {
  try {
    const response = await http.get<Configuration>('/api/admin/api/vmcard/configuration')
    configuration.value = response.data
    const initial = response.data.operations.find(item => item.id === selectedId.value) || response.data.operations[0]
    if (initial) {
      selectedId.value = initial.id
      requestJson.value = formatJson(operationSample(initial))
    }
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, 'VMCard 配置状态加载失败'))
  }
}

async function checkToken() {
  tokenChecking.value = true
  try {
    const response = await http.post('/api/admin/api/vmcard/token-check')
    ElMessage.success(`accessToken 获取成功，有效期至 ${response.data.expiresAt}`)
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, 'accessToken 验证失败'))
  } finally {
    tokenChecking.value = false
  }
}

async function executeOperation() {
  const operation = selectedOperation.value
  if (!operation) return
  let body: Record<string, unknown>
  try {
    body = JSON.parse(requestJson.value || '{}')
    if (!body || Array.isArray(body) || typeof body !== 'object') throw new Error('业务参数必须是 JSON 对象')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : 'JSON 格式不正确')
    return
  }

  if (operation.mutating) {
    try {
      await ElMessageBox.confirm(
        `确认在${configuration.value.environment === 'production' ? '生产' : '沙箱'}环境执行“${operation.label}”？`,
        '高风险接口确认',
        { type: 'warning', confirmButtonText: '确认执行', cancelButtonText: '取消' }
      )
    } catch {
      return
    }
  }

  executing.value = true
  const startedAt = performance.now()
  try {
    const response = await http.post(`/api/admin/api/vmcard/execute/${operation.id}`, body)
    responseJson.value = formatJson(response.data)
    responseTime.value = `${Math.round(performance.now() - startedAt)} ms`
    ElMessage.success('接口调用完成')
    if (operation.id === 'createCard') await loadSavedCards()
    if (operation.id === 'productCodes') await loadProductCodes()
  } catch (error: any) {
    responseJson.value = formatJson(error?.response?.data || { error: getHttpErrorMessage(error) })
    responseTime.value = `${Math.round(performance.now() - startedAt)} ms`
    ElMessage.error(getHttpErrorMessage(error, '接口调用失败'))
  } finally {
    executing.value = false
  }
}

async function loadEvents() {
  eventsLoading.value = true
  try {
    const response = await http.get<WebhookEvent[]>('/api/admin/api/vmcard/webhook-events')
    events.value = response.data || []
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, 'Webhook 记录加载失败'))
  } finally {
    eventsLoading.value = false
  }
}

async function loadSavedCards() {
  cardsLoading.value = true
  try {
    const response = await http.get<SavedCard[]>('/api/admin/api/vmcard/saved-cards')
    savedCards.value = response.data || []
    prefillCardIdIfEmpty()
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, '本地卡片记录加载失败'))
  } finally {
    cardsLoading.value = false
  }
}

function prefillCardIdIfEmpty() {
  const operation = selectedOperation.value
  const cardId = savedCards.value[0]?.cardId
  if (!operation || !cardIdOperationIds.has(operation.id) || !cardId) return
  try {
    const body = JSON.parse(requestJson.value || '{}') as Record<string, unknown>
    if (!body.card_id) {
      body.card_id = cardId
      requestJson.value = formatJson(body)
    }
  } catch {
    // Keep manually edited invalid JSON untouched so the regular validation can explain it.
  }
}

async function loadProductCodes() {
  productsLoading.value = true
  try {
    const response = await http.get<ProductCode[]>('/api/admin/api/vmcard/product-codes')
    productCodes.value = response.data || []
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, '产品码加载失败'))
  } finally {
    productsLoading.value = false
  }
}

async function refreshProductCodes() {
  productsRefreshing.value = true
  try {
    const response = await http.post('/api/admin/api/vmcard/execute/productCodes', {})
    await loadProductCodes()
    ElMessage.success(`已同步 ${response.data.synchronizedProducts || 0} 个产品码`)
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, '产品码刷新失败'))
  } finally {
    productsRefreshing.value = false
  }
}

async function updateProductAvailability(row: ProductCode) {
  availabilityUpdating.value = row.id
  const desired = row.available
  try {
    const response = await http.put<ProductCode>(
      `/api/admin/api/vmcard/product-codes/${row.id}/availability`,
      { available: desired }
    )
    Object.assign(row, response.data)
    ElMessage.success(desired ? '产品码已启用' : '产品码已停用')
  } catch (error) {
    row.available = !desired
    ElMessage.error(getHttpErrorMessage(error, '产品码状态更新失败'))
  } finally {
    availabilityUpdating.value = null
  }
}

function formatJson(value: unknown) {
  return JSON.stringify(value, null, 2)
}

onMounted(async () => {
  await loadConfiguration()
  await loadProductCodes()
  await loadSavedCards()
  await loadEvents()
})
</script>

<style scoped>
.vmcard-page {
  display: grid;
  gap: 18px;
}

.vmcard-panel {
  padding: 24px;
  border: 1px solid #e3e8f0;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.05);
}

.vmcard-title-row,
.request-head,
.response-head,
.webhook-head,
.saved-card-head,
.product-code-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
}

.vmcard-eyebrow {
  margin: 0 0 6px;
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
}

.vmcard-title-row h2,
.request-head h3,
.webhook-head h3,
.saved-card-head h3,
.product-code-head h3,
.operation-list h3,
.response-head h3 {
  margin: 0;
  color: #111827;
}

.vmcard-title-row > div > p:last-child,
.webhook-head p,
.saved-card-head p,
.product-code-head p {
  margin: 8px 0 0;
  color: #64748b;
}

.vmcard-status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin: 22px 0;
}

.vmcard-status-grid article {
  min-height: 78px;
  display: grid;
  align-content: space-between;
  gap: 10px;
  padding: 14px;
  border: 1px solid #edf2f7;
  border-radius: 8px;
  background: #f8fafc;
}

.vmcard-status-grid span {
  color: #64748b;
  font-size: 13px;
}

.vmcard-status-grid strong {
  color: #111827;
}

.vmcard-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.vmcard-console-grid {
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr);
  gap: 18px;
}

.operation-list {
  align-self: start;
}

.operation-list h3 {
  margin-bottom: 14px;
}

.operation-list button {
  width: 100%;
  min-height: 62px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: #334155;
  text-align: left;
  cursor: pointer;
}

.operation-list button:hover,
.operation-list button.active {
  border-color: #bfdbfe;
  background: #eff6ff;
}

.operation-list button strong,
.operation-list button small {
  display: block;
}

.operation-list button small {
  margin-top: 4px;
  color: #94a3b8;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

.request-panel {
  min-width: 0;
}

.request-panel .el-alert {
  margin: 18px 0;
}

.json-label {
  display: block;
  margin: 18px 0 8px;
  color: #475569;
  font-weight: 700;
}

.request-panel :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  line-height: 1.6;
}

.response-head {
  align-items: center;
  margin-top: 20px;
}

.response-head span {
  color: #64748b;
  font-size: 12px;
}

.response-json,
.event-json {
  overflow: auto;
  margin: 10px 0 0;
  padding: 18px;
  border-radius: 8px;
  background: #0f172a;
  color: #dbeafe;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.response-json {
  min-height: 180px;
}

.webhook-head,
.saved-card-head,
.product-code-head {
  margin-bottom: 18px;
}

.event-json {
  margin: 0 20px 16px;
}

@media (max-width: 1000px) {
  .vmcard-status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .vmcard-console-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .operation-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .operation-list h3 {
    grid-column: 1 / -1;
  }
}

@media (max-width: 640px) {
  .vmcard-title-row,
  .request-head,
  .webhook-head,
  .saved-card-head,
  .product-code-head {
    flex-direction: column;
  }

  .vmcard-status-grid,
  .operation-list {
    grid-template-columns: minmax(0, 1fr);
  }

  .operation-list h3 {
    grid-column: auto;
  }
}
</style>
