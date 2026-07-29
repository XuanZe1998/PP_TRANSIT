<template>
  <section class="other-services-page site-section">
    <div class="services-hero">
      <div>
        <p class="eyebrow">服务目录</p>
        <h1>更多服务，按需选择</h1>
        <p>所有服务统一配置价格、服务费和购买状态，并支持创建订单、支付与进度跟踪。</p>
      </div>
      <div class="services-count">
        <span>当前可用</span>
        <strong>{{ services.length }}</strong>
        <small>项服务</small>
      </div>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-empty v-else-if="services.length === 0" description="暂无其他服务" />
    <div v-else class="services-grid">
      <article v-for="(service, index) in services" :key="service.id" class="service-card">
        <div class="service-image">
          <img
            v-if="service.imageUrl && !failedImages.has(service.id)"
            :src="service.imageUrl"
            :alt="service.name"
            @error="markImageFailed(service.id)"
          />
          <div v-else class="service-image-placeholder" aria-label="服务图片待上传">
            <span>SERVICE</span>
            <small>管理员可在后台配置展示图片</small>
          </div>
        </div>
        <div class="service-card-body">
          <div class="service-card-meta">
            <span class="service-index">SERVICE {{ String(index + 1).padStart(2, '0') }}</span>
          </div>
          <h2>{{ service.name }}</h2>
          <p>{{ service.description || '服务内容待完善' }}</p>
          <div class="service-order-row">
            <div>
              <strong>{{ formatMoney(service.amountCents, service.currency) }}</strong>
              <small>
                商品 {{ formatMoney(service.priceCents, service.currency) }}
                + 服务费 {{ formatMoney(service.serviceFeeCents, service.currency) }}
              </small>
            </div>
            <el-button
              type="primary"
              :loading="buyingServiceId === service.id"
              :disabled="!isService07(service) && !service.orderEnabled"
              @click="handleServiceAction(service)"
            >
              {{ isService07(service) ? '了解详情' : (service.actionLabel || '立即购买') }}
            </el-button>
          </div>
        </div>
      </article>
    </div>

    <section v-if="authenticated" class="service-orders">
      <div class="orders-head">
        <div>
          <p class="eyebrow">订单中心</p>
          <h2>我的服务订单</h2>
        </div>
        <el-button :loading="ordersLoading" @click="loadOrders">刷新订单</el-button>
      </div>
      <el-table v-loading="ordersLoading" :data="orders" empty-text="暂无服务订单">
        <el-table-column prop="orderNo" label="订单号" min-width="190" />
        <el-table-column prop="productName" label="服务" min-width="170" />
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
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canPay(row)" link type="success" @click="startPayment(row)">去支付</el-button>
            <el-button
              v-if="['PAID', 'FULFILLED'].includes(row.status)"
              link
              type="primary"
              @click="downloadReceipt(row.id)"
            >下载账单</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { getToken } from '@/utils/auth'

type OtherService = {
  id: number
  name: string
  description?: string
  imageUrl?: string
  sortOrder: number
  actionLabel?: string
  priceCents?: number
  serviceFeeCents?: number
  amountCents?: number
  currency?: string
  orderEnabled?: boolean
}

type PlusOrder = {
  id: number
  orderNo: string
  productName: string
  amountCents: number
  currency: string
  paymentAmountCents?: number
  paymentCurrency?: string
  exchangeRate?: number
  status: string
  paymentUrl?: string
  createdAt: string
}

const route = useRoute()
const router = useRouter()
const services = ref<OtherService[]>([])
const orders = ref<PlusOrder[]>([])
const loading = ref(true)
const ordersLoading = ref(false)
const buyingServiceId = ref<number | null>(null)
const failedImages = ref(new Set<number>())
const authenticated = Boolean(getToken())
const service07Id = Number(import.meta.env.VITE_SERVICE_07_ID || 7)

const formatMoney = (cents?: number, currency?: string) => {
  if (cents === null || cents === undefined) return '-'
  return `${currency || 'CNY'} ${(cents / 100).toFixed(2)}`
}

const canPay = (order: PlusOrder) => ['PENDING', 'CONFIRMED'].includes(order.status)

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

function markImageFailed(id: number) {
  failedImages.value = new Set(failedImages.value).add(id)
}

function isService07(service: OtherService) {
  return service.id === service07Id
}

async function handleServiceAction(service: OtherService) {
  if (isService07(service)) {
    await router.push('/services/7')
    return
  }
  await buy(service)
}

function goToPayment(paymentUrl?: string) {
  if (!paymentUrl) return false
  const target = new URL(paymentUrl, window.location.origin)
  if (!['https:', 'http:'].includes(target.protocol)) throw new Error('支付链接无效')
  window.location.assign(target.toString())
  return true
}

async function loadCatalog() {
  const response = await http.get<OtherService[]>('/api/public/other-services')
  services.value = response.data || []
}

async function loadOrders() {
  if (!authenticated) return
  ordersLoading.value = true
  try {
    const response = await http.get<PlusOrder[]>('/api/service-orders')
    orders.value = response.data || []
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '服务订单加载失败'))
  } finally {
    ordersLoading.value = false
  }
}

async function buy(service: OtherService) {
  if (!authenticated) {
    await router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  if (!service.orderEnabled) {
    ElMessage.warning('该服务当前不可下单')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认创建“${service.name}”订单，金额 ${formatMoney(service.amountCents, service.currency)}？`,
      '确认购买',
      { type: 'warning', confirmButtonText: '创建订单' }
    )
  } catch {
    return
  }
  buyingServiceId.value = service.id
  try {
    const response = await http.post('/api/service-orders', { serviceId: service.id })
    ElMessage.success(response.data?.message || '订单已创建')
    await loadOrders()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '订单创建失败'))
  } finally {
    buyingServiceId.value = null
  }
}

async function startPayment(order: PlusOrder) {
  try {
    const response = await http.post(`/api/service-orders/${order.id}/payment`)
    await loadOrders()
    if (['PAID', 'FULFILLED'].includes(response.data?.order?.status)) {
      ElMessage.success(response.data?.message || '支付成功')
      return
    }
    if (!goToPayment(response.data?.paymentUrl)) ElMessage.warning('支付链接尚未生成')
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '发起支付失败'))
  }
}

async function downloadReceipt(id: number) {
  try {
    const response = await http.get(`/api/service-orders/${id}/download`, { responseType: 'blob' })
    const url = URL.createObjectURL(response.data)
    const link = document.createElement('a')
    link.href = url
    link.download = `plus-order-${id}.pdf`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '账单下载失败'))
  }
}

onMounted(async () => {
  try {
    await loadCatalog()
    if (authenticated) await loadOrders()
  } catch {
    ElMessage.error('其他服务加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.other-services-page,
.service-orders {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.service-card-body {
  display: flex;
  min-height: 210px;
  flex-direction: column;
}

.service-card-meta,
.service-order-row,
.orders-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.service-order-row {
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid #e2e8f0;
}

.service-order-row div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.service-order-row strong {
  color: #0f172a;
  font-size: 22px;
}

.service-order-row small {
  color: #64748b;
}

.service-orders {
  margin-top: 12px;
  padding: 24px;
  border: 1px solid #e2e8f0;
  border-radius: 12px;
  background: #fff;
}

.orders-head h2 {
  margin: 4px 0 0;
}

@media (max-width: 640px) {
  .service-order-row,
  .orders-head {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
