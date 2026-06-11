<template>
  <div class="shell-page finished-page">
    <section class="finished-head shell-section">
      <div>
        <el-tag type="success" effect="plain">Finished Service</el-tag>
        <h1>成品服务</h1>
        <p>选择成品服务后点击购买，系统会立即生成订单并显示在我的订单中。</p>
      </div>
      <el-button type="primary" @click="refreshAll">刷新</el-button>
    </section>

    <section class="product-grid">
      <article v-for="product in products" :key="product.id" class="product-card shell-section">
        <div class="product-image">
          <img v-if="product.imageUrl" :src="product.imageUrl" :alt="product.name" />
          <div v-else class="image-placeholder">成品</div>
        </div>
        <div class="product-body">
          <div class="product-meta">
            <h2>{{ product.name }}</h2>
            <span>{{ formatDate(product.createdAt) }}</span>
          </div>
          <p>{{ product.description }}</p>
          <div class="product-foot">
            <div>
              <div class="price">¥{{ formatPrice(totalPriceCents(product)) }}</div>
              <div class="price-detail">
                单价 ¥{{ formatPrice(product.priceCents) }} + 服务费 ¥{{ formatPrice(product.serviceFeeCents) }}
              </div>
            </div>
            <el-button type="primary" :loading="creatingProductId === product.id" @click="buyProduct(product)">
              购买
            </el-button>
          </div>
        </div>
      </article>
    </section>

    <section class="orders shell-section">
      <div class="section-bar">
        <div>
          <h2 class="section-title">我的订单</h2>
          <p class="section-subtitle">购买后生成的订单会出现在这里，可下载订单凭证。</p>
        </div>
      </div>
      <el-table :data="orders" empty-text="暂无订单">
        <el-table-column prop="orderNo" label="订单号" min-width="190" />
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
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="downloadOrder(row.id)">下载订单</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/utils/http'

type FinishedProduct = {
  id: number
  name: string
  description: string
  imageUrl: string
  priceCents: number
  serviceFeeCents: number
  createdAt: string
}

type FinishedOrder = {
  id: number
  orderNo: string
  productName: string
  amountCents: number
  status: string
  createdAt: string
}

const products = ref<FinishedProduct[]>([])
const orders = ref<FinishedOrder[]>([])
const creatingProductId = ref<number | null>(null)

const formatPrice = (cents: number) => ((cents || 0) / 100).toFixed(2)
const totalPriceCents = (product: FinishedProduct) => (product.priceCents || 0) + (product.serviceFeeCents || 0)
const formatDate = (date: string) => date ? String(date).replace('T', ' ').slice(0, 16) : '-'

const statusType = (status: string) => {
  if (status === 'CONFIRMED') return 'success'
  if (status === 'FULFILLED') return 'primary'
  if (status === 'CANCELLED') return 'info'
  return 'warning'
}

const buyProduct = async (product: FinishedProduct) => {
  creatingProductId.value = product.id
  try {
    const res = await http.post('/api/plus/orders', { productId: product.id })
    ElMessage.success(res.data?.message || '订单已创建')
    await refreshOrders()
  } finally {
    creatingProductId.value = null
  }
}

const refreshProducts = async () => {
  const res = await http.get('/api/plus/products')
  products.value = res.data
}

const refreshOrders = async () => {
  const res = await http.get('/api/plus/orders')
  orders.value = res.data
}

const refreshAll = async () => {
  await Promise.all([refreshProducts(), refreshOrders()])
}

const downloadOrder = async (id: number) => {
  const res = await http.get(`/api/plus/orders/${id}/download`, { responseType: 'blob' })
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = `receipt-${id}.pdf`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  refreshAll().catch(() => ElMessage.error('成品服务数据加载失败'))
})
</script>

<style scoped>
.finished-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.finished-head {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 30px 32px;
  border-radius: 8px;
}

.finished-head h1 {
  margin: 14px 0 0;
  font-size: 32px;
  color: #0f172a;
}

.finished-head p {
  max-width: 760px;
  margin: 10px 0 0;
  color: #64748b;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.product-card,
.orders {
  border-radius: 8px;
}

.product-card {
  overflow: hidden;
}

.product-image {
  aspect-ratio: 16 / 9;
  background: #e2e8f0;
}

.product-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.image-placeholder {
  width: 100%;
  height: 100%;
  display: grid;
  place-items: center;
  color: #64748b;
  font-size: 20px;
  font-weight: 700;
}

.product-body,
.orders {
  padding: 24px;
}

.product-meta {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}

.product-meta h2 {
  margin: 0;
  font-size: 20px;
}

.product-meta span,
.product-body p,
.price-detail {
  color: #64748b;
}

.product-body p {
  min-height: 44px;
  margin: 12px 0 20px;
}

.product-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
}

.price {
  font-size: 30px;
  font-weight: 700;
  color: #0f172a;
}

.price-detail {
  margin-top: 4px;
  font-size: 13px;
}

.section-bar {
  margin-bottom: 18px;
}

@media (max-width: 900px) {
  .product-grid,
  .finished-head {
    grid-template-columns: minmax(0, 1fr);
    display: grid;
  }
}
</style>
