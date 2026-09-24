<template>
  <section class="subscription-page site-section">
    <div class="subscription-hero">
      <div>
        <p class="eyebrow">订阅服务</p>
        <h1>查看实时货源，按需询价</h1>
        <p>商品与价格来自已接入的上游网关；所有签名和密钥均仅在服务端处理。</p>
      </div>
      <el-input v-model="query" clearable placeholder="搜索商品编号或名称" @keyup.enter="search">
        <template #append><el-button @click="search">搜索</el-button></template>
      </el-input>
    </div>

    <el-alert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <el-skeleton v-if="loading" :rows="6" animated />
    <el-empty v-else-if="!items.length" description="暂无可用订阅商品" />
    <div v-else class="subscription-grid">
      <article v-for="item in items" :key="goodsNumber(item)" class="subscription-card">
        <div class="subscription-cover">
          <img v-if="imageOf(item)" :src="imageOf(item)" :alt="nameOf(item)" />
          <span v-else>SUBSCRIPTION</span>
        </div>
        <div class="subscription-card-body">
          <small>{{ goodsNumber(item) }}</small>
          <h2>{{ nameOf(item) }}</h2>
          <p class="subscription-summary">{{ descriptionOf(item) }}</p>
          <div class="subscription-card-foot">
            <strong>{{ priceOf(item) }}</strong>
            <el-button type="primary" @click="openDetail(item)">查看与询价</el-button>
          </div>
        </div>
      </article>
    </div>
    <ListPagination v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="load" />

    <el-dialog v-model="detailVisible" title="订阅商品详情" width="min(760px, 94vw)">
      <el-skeleton v-if="detailLoading" :rows="5" animated />
      <template v-else-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="商品编号">{{ goodsNumber(detail) }}</el-descriptions-item>
          <el-descriptions-item label="商品名称">{{ nameOf(detail) }}</el-descriptions-item>
          <el-descriptions-item label="实时价格">{{ priceOf(detail) }}</el-descriptions-item>
          <el-descriptions-item label="商品说明">
            <div class="subscription-description">{{ descriptionOf(detail) }}</div>
          </el-descriptions-item>
        </el-descriptions>
        <div class="quote-row">
          <el-input-number v-model="quantity" :min="1" :max="100" />
          <el-button type="primary" :loading="quoting" @click="quote">获取实时报价</el-button>
        </div>
        <el-alert v-if="quoteResult" type="success" :closable="false">
          <template #title><pre>{{ pretty(quoteResult) }}</pre></template>
        </el-alert>
        <p class="checkout-note">上游采购、订单、库存和投诉操作已在管理后台接入，避免普通账号直接消耗共享上游钱包。</p>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import ListPagination from '@/components/ListPagination.vue'
import http, { getHttpErrorMessage } from '@/utils/http'
import { getToken } from '@/utils/auth'
import { subscriptionPlainText } from '@/utils/subscriptionText'

type Row = Record<string, any>
const route = useRoute()
const router = useRouter()
const items = ref<Row[]>([])
const loading = ref(false)
const error = ref('')
const query = ref(typeof route.query.q === 'string' ? route.query.q : '')
const page = ref(1)
const size = ref(10)
const total = ref(0)
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<Row | null>(null)
const quantity = ref(1)
const quoting = ref(false)
const quoteResult = ref<unknown>(null)

function pick(row: Row | null, names: string[], fallback = '') {
  if (!row) return fallback
  for (const name of names) if (row[name] !== undefined && row[name] !== null && row[name] !== '') return row[name]
  return fallback
}
const goodsNumber = (row: Row | null) => String(pick(row, ['goods_no', 'goodsNo', 'no', 'id'], '-'))
const nameOf = (row: Row | null) => String(pick(row, ['name', 'title', 'goods_name'], '未命名订阅商品'))
const descriptionOf = (row: Row | null) => subscriptionPlainText(
  pick(row, ['description', 'instruction', 'goods_description'], ''),
  '详情以上游实时信息为准。',
)
const imageOf = (row: Row | null) => String(pick(row, ['image', 'image_url', 'cover'], ''))
function priceOf(row: Row | null) {
  const value = pick(row, ['price', 'sale_price', 'agent_price', 'amount'], '')
  return value === '' ? '询价后确认' : `¥${value}`
}
const pretty = (value: unknown) => JSON.stringify(value, null, 2)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await http.get('/api/public/subscription-services/catalog', { params: { page: page.value, size: size.value, query: query.value } })
    items.value = Array.isArray(response.data?.items) ? response.data.items : []
    total.value = Number(response.data?.total || 0)
    page.value = Number(response.data?.page || page.value)
  } catch (caught) {
    items.value = []
    total.value = 0
    error.value = getHttpErrorMessage(caught, '订阅服务暂时不可用')
  } finally { loading.value = false }
}

function search() {
  page.value = 1
  router.replace({ query: query.value ? { q: query.value } : {} })
  load()
}

async function openDetail(row: Row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  quoteResult.value = null
  quantity.value = 1
  try {
    const response = await http.get(`/api/public/subscription-services/catalog/${encodeURIComponent(goodsNumber(row))}`)
    detail.value = response.data || row
  } catch (caught) {
    detail.value = row
    ElMessage.error(getHttpErrorMessage(caught, '商品详情加载失败'))
  } finally { detailLoading.value = false }
}

async function quote() {
  if (!getToken()) {
    window.dispatchEvent(new CustomEvent('user-auth-required', { detail: { redirect: route.fullPath } }))
    ElMessage.info('请登录后获取实时报价')
    return
  }
  quoting.value = true
  try {
    const response = await http.post('/api/subscription-services/quote', { goods_no: goodsNumber(detail.value), quantity: quantity.value })
    quoteResult.value = response.data
  } catch (caught) { ElMessage.error(getHttpErrorMessage(caught, '询价失败')) }
  finally { quoting.value = false }
}

onMounted(load)
</script>

<style scoped>
.subscription-page { display: grid; gap: 24px; }
.subscription-hero { display: grid; grid-template-columns: minmax(0, 1fr) minmax(280px, 420px); align-items: end; gap: 28px; }
.subscription-hero h1 { margin: 6px 0 12px; font-size: clamp(30px, 4vw, 52px); }
.subscription-hero p { color: #64748b; line-height: 1.8; }
.subscription-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: stretch; gap: 18px; }
.subscription-card { display: grid; grid-template-rows: 180px minmax(0, 1fr); min-width: 0; overflow: hidden; border: 1px solid #dbe7f5; border-radius: 16px; background: #fff; box-shadow: 0 16px 40px rgba(34, 76, 128, .08); }
.subscription-cover { position: relative; height: 180px; display: grid; place-items: center; overflow: hidden; background: linear-gradient(135deg, #eaf4ff, #f4f0ff); color: #4f6f9a; letter-spacing: .16em; }
.subscription-cover img { position: absolute; inset: 0; display: block; width: 100%; height: 100%; min-width: 0; min-height: 0; object-fit: cover; }
.subscription-card-body { display: flex; min-width: 0; flex-direction: column; gap: 10px; padding: 18px; }
.subscription-card-body small { color: #6b7f99; }
.subscription-card-body h2 { display: -webkit-box; min-height: 52px; margin: 0; overflow: hidden; font-size: 20px; line-height: 1.3; overflow-wrap: anywhere; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.subscription-summary { display: -webkit-box; min-height: 78px; margin: 0; overflow: hidden; color: #64748b; line-height: 1.625; overflow-wrap: anywhere; white-space: pre-line; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.subscription-description { max-height: min(46vh, 420px); overflow: auto; line-height: 1.75; overflow-wrap: anywhere; white-space: pre-line; }
.subscription-card-foot, .quote-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.subscription-card-foot { margin-top: auto; padding-top: 4px; }
.quote-row { justify-content: flex-start; margin: 18px 0; }
.checkout-note { margin-top: 16px; color: #64748b; line-height: 1.7; }
pre { margin: 0; white-space: pre-wrap; word-break: break-word; }
@media (max-width: 960px) { .subscription-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 680px) {
  .subscription-page { gap: 18px; }
  .subscription-hero, .subscription-grid { grid-template-columns: 1fr; }
  .subscription-hero { gap: 18px; }
  .subscription-hero h1 { font-size: clamp(28px, 10vw, 40px); }
  .subscription-card { grid-template-rows: 156px minmax(0, 1fr); }
  .subscription-cover { height: 156px; }
  .subscription-card-body { padding: 16px; }
}
</style>
