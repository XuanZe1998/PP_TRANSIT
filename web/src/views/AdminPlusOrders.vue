<template>
  <div class="admin-finished">
    <section class="shell-section panel">
      <div class="panel-head">
        <div>
          <h2 class="section-title">成品订单</h2>
          <p class="section-subtitle">查看成品服务订单并更新确认/履约状态。</p>
        </div>
        <el-button type="primary" @click="fetchOrders">刷新</el-button>
      </div>

      <el-table :data="orders" empty-text="暂无订单">
        <el-table-column prop="orderNo" label="订单号" min-width="190" />
        <el-table-column prop="userId" label="用户" width="90" />
        <el-table-column prop="productName" label="成品服务" min-width="220" />
        <el-table-column label="金额" width="110">
          <template #default="{ row }">¥{{ formatPrice(row.amountCents) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" title="处理成品订单" width="620px">
      <el-form label-position="top">
        <el-form-item label="订单号">
          <el-input :model-value="selectedOrder?.orderNo || ''" readonly />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status">
            <el-option label="待确认" value="PENDING" />
            <el-option label="已确认" value="CONFIRMED" />
            <el-option label="已履约" value="FULFILLED" />
            <el-option label="已取消" value="CANCELLED" />
          </el-select>
        </el-form-item>
        <el-form-item label="履约备注">
          <el-input v-model="form.fulfillmentNote" type="textarea" :rows="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveOrder">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/utils/http'

type FinishedOrder = {
  id: number
  orderNo: string
  userId: number
  productName: string
  amountCents: number
  status: string
  fulfillmentNote: string
  createdAt: string
}

const orders = ref<FinishedOrder[]>([])
const selectedOrder = ref<FinishedOrder | null>(null)
const dialogVisible = ref(false)
const form = ref({
  status: 'PENDING',
  fulfillmentNote: ''
})

const formatPrice = (cents: number) => ((cents || 0) / 100).toFixed(2)
const statusType = (status: string) => {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'FULFILLED') return 'primary'
  if (status === 'CANCELLED') return 'info'
  return 'warning'
}

const fetchOrders = async () => {
  const res = await http.get('/api/plus/admin/orders')
  orders.value = res.data
}

const openEdit = (order: FinishedOrder) => {
  selectedOrder.value = order
  form.value = {
    status: order.status,
    fulfillmentNote: order.fulfillmentNote || ''
  }
  dialogVisible.value = true
}

const saveOrder = async () => {
  if (!selectedOrder.value) return
  await http.put(`/api/plus/admin/orders/${selectedOrder.value.id}`, form.value)
  ElMessage.success('订单已更新')
  dialogVisible.value = false
  await fetchOrders()
}

onMounted(() => {
  fetchOrders().catch(() => ElMessage.error('成品订单加载失败'))
})
</script>

<style scoped>
.admin-finished,
.panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.panel {
  padding: 24px;
  border-radius: 8px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}
</style>
