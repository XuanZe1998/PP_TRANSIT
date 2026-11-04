<template>
  <section class="service-order-admin">
    <div class="panel-head">
      <div>
        <h2>服务订单</h2>
        <p>查看用户购买记录，维护付款凭证、履约状态和交付信息。</p>
      </div>
      <el-button :loading="loading" @click="fetchOrders">刷新</el-button>
    </div>
    <el-form inline @submit.prevent="searchOrders">
      <el-form-item label="账号邮箱">
        <el-input v-model="emailInput" clearable maxlength="255" placeholder="输入完整邮箱" @clear="searchOrders" />
      </el-form-item>
      <el-form-item>
        <el-button native-type="submit" :disabled="bulkSaving">搜索</el-button>
      </el-form-item>
    </el-form>
    <el-space wrap>
      <span>本页已选 {{ selectedOrders.length }} 笔</span>
      <el-button :disabled="!canBulk('CONFIRMED')" @click="bulkUpdate('CONFIRMED')">批量确认</el-button>
      <el-button :disabled="!canBulk('CANCELLED')" @click="bulkUpdate('CANCELLED')">批量取消</el-button>
      <el-button :disabled="!canBulk('FULFILLED')" @click="bulkUpdate('FULFILLED')">批量标记已交付</el-button>
    </el-space>

    <el-alert v-if="serviceFilter" type="info" :closable="false" :title="`当前查看服务 ID ${serviceFilter} 的订单`" />
    <el-alert v-if="pendingOnly" type="warning" :closable="false" title="当前仅显示待付款和已确认订单" />
    <PagedTable ref="orderTable" v-loading="loading || bulkSaving" :data="orders" empty-text="暂无服务订单" list-id="AdminServiceOrders-1" pagination="external" @selection-change="selectedOrders = $event">
      <el-table-column type="selection" width="48" />
      <el-table-column prop="orderNo" label="订单号" min-width="190" />
      <el-table-column prop="userId" label="用户 ID" width="90" />
      <el-table-column prop="userEmail" label="账号邮箱" min-width="210" show-overflow-tooltip />
      <el-table-column prop="productName" label="服务名称" min-width="180" />
      <el-table-column label="金额" width="120">
        <template #default="{ row }">
          <div>{{ formatMoney(row.amountCents, row.currency) }}</div>
          <small v-if="row.paymentAmountCents && row.paymentCurrency">
            支付 {{ formatMoney(row.paymentAmountCents, row.paymentCurrency) }}
          </small>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusType(row.status)">{{ orderStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">处理</el-button>
          <el-button v-if="['PENDING','CONFIRMED','FAILED','CANCELLED'].includes(row.status)" link type="danger" @click="deleteOrder(row)">删除</el-button>
        </template>
      </el-table-column>
    </PagedTable>
    <ListPagination v-if="total > 0" v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="fetchOrders" />

    <el-dialog v-model="dialogVisible" title="处理服务订单" width="620px" @close="clearOrderRoute" @closed="!dialogVisible && (selectedOrder = null)">
      <el-form label-position="top">
        <el-form-item label="订单号">
          <el-input :model-value="selectedOrder?.orderNo || ''" readonly />
        </el-form-item>
        <el-form-item label="状态">
          <el-tag v-if="selectedOrder" :type="statusType(selectedOrder.status)">{{ orderStatusLabel(selectedOrder.status) }}</el-tag>
        </el-form-item>
        <el-form-item v-if="form.status === 'PAID'" label="付款凭证号" required>
          <el-input v-model="form.paymentReference" placeholder="支付平台交易号或本地验证编号" />
        </el-form-item>
        <el-form-item v-if="form.status === 'FULFILLED'" label="履约凭证号" required>
          <el-input v-model="form.fulfillmentReference" placeholder="供应商订单号或交付凭证" />
        </el-form-item>
        <el-form-item label="订单备注">
          <el-input v-model="form.fulfillmentNote" type="textarea" :rows="5" />
        </el-form-item>
        <el-descriptions v-if="selectedOrder?.supplierDetails" title="Dujiao-Next 采购" :column="2" border>
          <el-descriptions-item label="Product / SKU">{{ selectedOrder.supplierDetails.productId || '-' }} / {{ selectedOrder.supplierDetails.skuId || '-' }}</el-descriptions-item>
          <el-descriptions-item label="上游状态">{{ selectedOrder.supplierDetails.status ? supplierStatusLabel(selectedOrder.supplierDetails.status) : fulfillmentStatusLabel(selectedOrder.fulfillmentStatus) }}</el-descriptions-item>
          <el-descriptions-item label="上游订单号">{{ selectedOrder.supplierDetails.orderNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="采购金额">{{ selectedOrder.supplierDetails.amount || '-' }} {{ selectedOrder.supplierDetails.currency || '' }}</el-descriptions-item>
          <el-descriptions-item v-if="selectedOrder.supplierDetails.lastError" label="最近错误" :span="2">{{ selectedOrder.supplierDetails.lastError }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-if="paymentIntent" title="MaPay 支付" :column="2" border>
          <el-descriptions-item label="支付意图">{{ paymentIntent.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ orderStatusLabel(paymentIntent.status) }}</el-descriptions-item>
          <el-descriptions-item label="方式">{{ paymentIntent.paymentMethod }}</el-descriptions-item>
          <el-descriptions-item label="平台交易号">{{ paymentIntent.providerTradeNo || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-form-item v-if="selectedOrder?.fulfillmentMode === 'MANUAL_PROCESSING' && selectedOrder?.status === 'PAID'" label="交付内容" required>
          <el-input v-model="form.deliveryContent" type="textarea" :rows="6" placeholder="仅在订单详情中展示给订单所属用户" />
        </el-form-item>
      </el-form>
      <template #footer>
        <ReferralRefund v-if="selectedOrder" :order="selectedOrder" />
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveOrder">保存</el-button>
        <el-button v-if="paymentIntent && ['PENDING','EXPIRED'].includes(paymentIntent.status)" type="success" :loading="queryingPayment" @click="queryPayment">手动查单</el-button>
        <el-button v-if="selectedOrder?.fulfillmentMode === 'MANUAL_PROCESSING' && selectedOrder?.status === 'PAID'" type="primary" :loading="saving" @click="completeManual">完成交付</el-button>
        <el-button v-if="selectedOrder?.fulfillmentMode === 'AUTOMATIC_DELIVERY' && ['FAILED','REVIEW_REQUIRED'].includes(selectedOrder?.fulfillmentStatus || '')" type="primary" :loading="saving" @click="retryAutomatic">重试自动发货</el-button>
        <el-button v-if="['PENDING','CONFIRMED'].includes(selectedOrder?.status || '')" type="danger" plain :loading="saving" @click="applyAction('FAILED')">标记失败</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import ReferralRefund from '@/components/ReferralRefund.vue'
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { formatCurrencyCents } from '@/utils/money'
import { fulfillmentStatusLabel, orderStatusLabel, supplierStatusLabel } from '@/utils/serviceOrderStatus'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

type ServiceOrder = {
  id: number
  orderNo: string
  userId: number
  productName: string
  userEmail?: string
  amountCents: number
  currency: string
  paymentAmountCents?: number
  paymentCurrency?: string
  exchangeRate?: number
  status: string
  fulfillmentMode?: string
  fulfillmentStatus?: string
  customFields?: Record<string, string>
  fulfillmentNote?: string
  fulfillmentReference?: string
  paymentReference?: string
  supplierDetails?: Record<string, any>
  createdAt: string
}

const orders = ref<ServiceOrder[]>([])
const orderTable = ref<InstanceType<typeof PagedTable>>()
const selectedOrders = ref<ServiceOrder[]>([])
const emailInput = ref('')
const userEmail = ref('')
const bulkSaving = ref(false)
const router = useRouter()
const route=useRoute(),pendingOnly=computed(()=>route.query.status==='pending')
const serviceFilter = computed(() => {
  const id = Number(route.query.serviceId)
  return Number.isSafeInteger(id) && id > 0 ? id : undefined
})
const page = ref(1)
const size = ref(10)
const total = ref(0)
const paymentIntent = ref<Record<string, any> | null>(null)
const queryingPayment = ref(false)
const selectedOrder = ref<ServiceOrder | null>(null)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
let listRequest = 0, detailRequest = 0
onUnmounted(() => { listRequest++; detailRequest++ })
const form = ref({
  status: 'PENDING',
  fulfillmentNote: '',
  fulfillmentReference: '',
  paymentReference: '',
  deliveryContent: ''
})

const formatMoney = (cents: number, currency?: string) => formatCurrencyCents(cents, currency)
const statusType = (status: string) => {
  if (['PAID', 'CONFIRMED'].includes(status)) return 'success'
  if (status === 'FULFILLED') return 'primary'
  if (['FAILED', 'CANCELLED'].includes(status)) return 'info'
  return 'warning'
}
async function fetchOrders() {
  orderTable.value?.clearSelection()
  selectedOrders.value = []
  const request = ++listRequest
  loading.value = true
  try {
    const response = await http.get('/api/service-orders/admin/orders', { params: {
      listPage: true, page: page.value, size: size.value,
      status: pendingOnly.value ? 'PENDING,CONFIRMED' : undefined, serviceId: serviceFilter.value,
      userEmail: userEmail.value || undefined
    } })
    if (request !== listRequest) return
    orders.value = response.data?.items || []
    total.value = Number(response.data?.total || 0)
  } catch (error: unknown) {
    if (request === listRequest) ElMessage.error(getHttpErrorMessage(error, '服务订单加载失败'))
  } finally {
    if (request === listRequest) loading.value = false
  }
}

function searchOrders() {
  userEmail.value = emailInput.value.trim()
  page.value = 1
  void fetchOrders()
}

const bulkActions = {
  CONFIRMED: { label: '批量确认', allowed: ['PENDING'] },
  CANCELLED: { label: '批量取消', allowed: ['PENDING', 'CONFIRMED'] },
  FULFILLED: { label: '批量标记已交付', allowed: ['PAID'] }
}
type BulkStatus = keyof typeof bulkActions

function canBulk(status: BulkStatus) {
  return !loading.value && !bulkSaving.value && selectedOrders.value.length > 0
    && selectedOrders.value.every(order => bulkActions[status].allowed.includes(order.status))
}

async function bulkUpdate(status: BulkStatus) {
  if (!canBulk(status)) return
  const ids = selectedOrders.value.map(order => order.id)
  const label = bulkActions[status].label
  let fulfillmentReference: string | undefined
  bulkSaving.value = true
  try {
    try {
      if (status === 'FULFILLED') {
        const result = await ElMessageBox.prompt(
          `已在站外完成所选 ${ids.length} 笔订单的交付后，请填写共同的交付凭证。`, label,
          { inputValidator: value => Boolean(value?.trim() && value.trim().length <= 255) || '请输入 1 至 255 个字符' }
        )
        fulfillmentReference = result.value.trim()
      } else {
        await ElMessageBox.confirm(`确定对本页所选的 ${ids.length} 笔订单执行「${label}」？`, label)
      }
    } catch {
      return
    }
    await http.post('/api/service-orders/admin/orders/bulk', { ids, status, fulfillmentReference })
    ElMessage.success(`已处理 ${ids.length} 笔订单`)
    await fetchOrders()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '批量处理失败'))
    await fetchOrders()
  } finally {
    bulkSaving.value = false
  }
}

async function showOrder(order: ServiceOrder, request: number) {
  if (request !== detailRequest) return
  let payment: Record<string, any> | null = null
  try { payment = (await http.get(`/api/admin/payment-intents/business/SERVICE_ORDER/${order.id}`)).data }
  catch { /* Payment detail is optional; order identity must come from its exact endpoint. */ }
  if (request !== detailRequest) return
  selectedOrder.value = order
  paymentIntent.value = payment
  form.value = {
    status: order.status,
    fulfillmentNote: order.fulfillmentNote || '',
    fulfillmentReference: order.fulfillmentReference || '',
    paymentReference: order.paymentReference || '',
    deliveryContent: ''
  }
  dialogVisible.value = true
}
async function openEdit(order: ServiceOrder) {
  const request = ++detailRequest
  try { await showOrder((await http.get<ServiceOrder>(`/api/service-orders/admin/orders/${order.id}`)).data, request) }
  catch (error) { if (request === detailRequest) ElMessage.error(getHttpErrorMessage(error, '订单详情加载失败')) }
}
function clearOrderRoute() {
  detailRequest++
  paymentIntent.value = null
  if (Number(route.query.orderId) !== selectedOrder.value?.id) return
  const query = { ...route.query }; delete query.orderId
  router.replace({ query })
}
watch(() => route.query.orderId, async value => {
  const request = ++detailRequest, id = Number(value)
  if (!Number.isSafeInteger(id) || id < 1) return
  try { await showOrder((await http.get<ServiceOrder>(`/api/service-orders/admin/orders/${id}`)).data, request) }
  catch (error) {
    if (request !== detailRequest) return
    dialogVisible.value = false; selectedOrder.value = null
    ElMessage.error(getHttpErrorMessage(error, '订单不存在或无法查看'))
  }
}, { immediate: true })
watch(serviceFilter, () => { page.value = 1; void fetchOrders() })

async function saveOrder() {
  if (!selectedOrder.value) return
  saving.value = true
  try {
    await http.put(`/api/service-orders/admin/orders/${selectedOrder.value.id}`, form.value)
    ElMessage.success('服务订单已更新')
    dialogVisible.value = false
    await fetchOrders()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '服务订单更新失败'))
  } finally {
    saving.value = false
  }
}

async function applyAction(status: 'FAILED') {
  form.value.status = status
  await saveOrder()
}

async function queryPayment() {
  if (!paymentIntent.value?.id) return
  queryingPayment.value = true
  try {
    paymentIntent.value = (await http.post(`/api/admin/payment-intents/${paymentIntent.value.id}/query`)).data
    await fetchOrders()
    if (selectedOrder.value) await openEdit(selectedOrder.value)
    ElMessage.success(paymentIntent.value?.status === 'PAID' ? '已确认付款' : '查单完成，当前未支付')
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '查单失败'))
  } finally { queryingPayment.value = false }
}

async function completeManual() {
  if (!selectedOrder.value || !form.value.deliveryContent.trim()) {
    ElMessage.warning('请填写交付内容')
    return
  }
  saving.value = true
  try {
    await http.post(`/api/service-orders/admin/orders/${selectedOrder.value.id}/complete`, {
      deliveryContent: form.value.deliveryContent,
      note: form.value.fulfillmentNote
    })
    ElMessage.success('人工订单已完成交付')
    dialogVisible.value = false
    await fetchOrders()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '完成交付失败'))
  } finally {
    saving.value = false
  }
}

async function retryAutomatic() {
  if (!selectedOrder.value) return
  saving.value = true
  try {
    await http.post(`/api/service-orders/admin/orders/${selectedOrder.value.id}/retry-automatic`)
    ElMessage.success('已重试自动发货')
    dialogVisible.value = false
    await fetchOrders()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '重试自动发货失败'))
  } finally {
    saving.value = false
  }
}

async function deleteOrder(order: ServiceOrder) {
  try {
    await ElMessageBox.confirm(`确认删除订单 ${order.orderNo}？`, '删除确认', { type: 'warning' })
    await http.delete(`/api/service-orders/admin/orders/${order.id}`)
    ElMessage.success('服务订单已删除')
    await fetchOrders()
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getHttpErrorMessage(error, '服务订单删除失败'))
  }
}

onMounted(fetchOrders)
</script>

<style scoped>
.service-order-admin {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 8px 0;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel-head h2 {
  margin: 0;
}

.panel-head p {
  margin: 8px 0 0;
  color: #64748b;
}
</style>
