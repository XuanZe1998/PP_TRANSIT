<template>
  <section class="payment-intent-admin">
    <div class="panel-head">
      <div>
        <h2>支付记录</h2>
        <p>分页查看 MaPay 支付意图，并对待支付或已过期记录执行一次主动查单。</p>
      </div>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </div>

    <div class="filters">
      <el-input
        v-model="query"
        clearable
        placeholder="订单号、平台交易号或说明"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="status" clearable placeholder="全部状态" @change="search">
        <el-option label="待支付" value="PENDING" />
        <el-option label="已支付" value="PAID" />
        <el-option label="已过期" value="EXPIRED" />
        <el-option label="已取消" value="CANCELLED" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <PagedTable v-loading="loading" :data="items" empty-text="暂无支付记录" list-id="AdminPaymentIntents-1" pagination="external">
      <el-table-column prop="orderNo" label="支付订单号" min-width="190" />
      <el-table-column prop="businessType" label="业务" width="145">
        <template #default="{ row }">{{ businessLabel(row.businessType) }}</template>
      </el-table-column>
      <el-table-column prop="userId" label="用户 ID" width="90" />
      <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="金额" width="120">
        <template #default="{ row }">{{ formatCurrencyCents(row.settlementAmountCents, row.settlementCurrency) }}</template>
      </el-table-column>
      <el-table-column label="方式" width="95">
        <template #default="{ row }">{{ row.paymentMethod === 'wxpay' ? '微信' : '支付宝' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }"><el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="providerTradeNo" label="平台交易号" min-width="180">
        <template #default="{ row }">{{ row.providerTradeNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" min-width="170" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button
            v-if="['PENDING', 'EXPIRED'].includes(row.status)"
            link
            type="primary"
            :loading="queryingId === row.id"
            @click="queryPayment(row)"
          >手动查单</el-button>
          <span v-else>-</span>
        </template>
      </el-table-column>
    </PagedTable>
    <ListPagination v-if="total > 0" v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="load" />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import http, { getHttpErrorMessage } from '@/utils/http'
import { formatCurrencyCents } from '@/utils/money'

type PaymentIntent = {
  id: number
  orderNo: string
  userId: number
  businessType: string
  description: string
  settlementAmountCents: number
  settlementCurrency: string
  paymentMethod: string
  status: string
  providerTradeNo?: string
  createdAt: string
}

const items = ref<PaymentIntent[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const query = ref('')
const status = ref('')
const loading = ref(false)
const queryingId = ref<number | null>(null)

const businessLabel = (value: string) => ({
  WALLET_RECHARGE: '钱包充值',
  SERVICE_ORDER: '服务订单',
  SHOPGPT_ORDER: '商品订单'
}[value] || value)

const statusLabel = (value: string) => ({
  PENDING: '待支付', PAID: '已支付', EXPIRED: '已过期', CANCELLED: '已取消'
}[value] || value)

const statusType = (value: string) => {
  if (value === 'PAID') return 'success'
  if (value === 'PENDING') return 'warning'
  return 'info'
}

async function load() {
  loading.value = true
  try {
    const response = await http.get('/api/admin/payment-intents', {
      params: { page: page.value, size: size.value, query: query.value || undefined, status: status.value || undefined }
    })
    items.value = response.data?.items || []
    total.value = Number(response.data?.total || 0)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '支付记录加载失败'))
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

async function queryPayment(intent: PaymentIntent) {
  queryingId.value = intent.id
  try {
    const response = await http.post(`/api/admin/payment-intents/${intent.id}/query`)
    ElMessage.success(response.data?.status === 'PAID' ? '已确认付款' : '查单完成，当前未支付')
    await load()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '查单失败'))
  } finally {
    queryingId.value = null
  }
}

onMounted(load)
</script>

<style scoped>
.payment-intent-admin { display: flex; flex-direction: column; gap: 18px; padding: 8px 0; }
.panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.panel-head h2 { margin: 0; }
.panel-head p { margin: 8px 0 0; color: #64748b; }
.filters { display: grid; grid-template-columns: minmax(240px, 1fr) 180px auto; gap: 12px; }
@media (max-width: 720px) { .filters { grid-template-columns: 1fr; } }
</style>
