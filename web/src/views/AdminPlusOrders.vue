<template>
  <section class="service-order-admin">
    <div class="panel-head">
      <div>
        <h2>服务订单</h2>
        <p>查看用户购买记录，维护付款凭证、履约状态和交付信息。</p>
      </div>
      <el-button :loading="loading" @click="fetchOrders">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="orders" empty-text="暂无服务订单">
      <el-table-column prop="orderNo" label="订单号" min-width="190" />
      <el-table-column prop="userId" label="用户 ID" width="90" />
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
          <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="170" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">处理</el-button>
          <el-button link type="danger" @click="deleteOrder(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" title="处理服务订单" width="620px">
      <el-form label-position="top">
        <el-form-item label="订单号">
          <el-input :model-value="selectedOrder?.orderNo || ''" readonly />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" style="width: 100%">
            <el-option label="待付款" value="PENDING" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已付款" value="PAID" />
            <el-option label="已履约" value="FULFILLED" />
            <el-option label="失败" value="FAILED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
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
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveOrder">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

type ServiceOrder = {
  id: number
  orderNo: string
  userId: number
  productName: string
  amountCents: number
  currency: string
  paymentAmountCents?: number
  paymentCurrency?: string
  exchangeRate?: number
  status: string
  fulfillmentNote?: string
  fulfillmentReference?: string
  paymentReference?: string
  createdAt: string
}

const orders = ref<ServiceOrder[]>([])
const selectedOrder = ref<ServiceOrder | null>(null)
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const form = ref({
  status: 'PENDING',
  fulfillmentNote: '',
  fulfillmentReference: '',
  paymentReference: ''
})

const formatMoney = (cents: number, currency?: string) => `${currency || 'CNY'} ${((cents || 0) / 100).toFixed(2)}`
const statusType = (status: string) => {
  if (['PAID', 'CONFIRMED'].includes(status)) return 'success'
  if (status === 'FULFILLED') return 'primary'
  if (['FAILED', 'CANCELLED'].includes(status)) return 'info'
  return 'warning'
}
const statusLabel = (status: string) => ({
  PENDING: '待付款',
  CONFIRMED: '已确认',
  PAID: '已付款',
  FULFILLED: '已履约',
  FAILED: '失败',
  CANCELLED: '已取消'
}[status] || status)

async function fetchOrders() {
  loading.value = true
  try {
    const response = await http.get<ServiceOrder[]>('/api/service-orders/admin/orders')
    orders.value = response.data || []
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '服务订单加载失败'))
  } finally {
    loading.value = false
  }
}

function openEdit(order: ServiceOrder) {
  selectedOrder.value = order
  form.value = {
    status: order.status,
    fulfillmentNote: order.fulfillmentNote || '',
    fulfillmentReference: order.fulfillmentReference || '',
    paymentReference: order.paymentReference || ''
  }
  dialogVisible.value = true
}

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
