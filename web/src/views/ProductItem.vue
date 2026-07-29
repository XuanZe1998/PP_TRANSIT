<template>
  <div class="item-page">
    <header class="item-nav">
      <button class="item-brand" @click="go('/')">
        <span class="item-brand-mark">G</span>
        <span>GPT专卖-cw</span>
      </button>

      <nav class="item-links" aria-label="商品页导航">
        <button class="active" @click="go('/item')">
          <el-icon><ShoppingCart /></el-icon>
          <span>购物</span>
        </button>
        <button @click="go('/services')">
          <el-icon><Tickets /></el-icon>
          <span>订单查询</span>
        </button>
      </nav>

      <div class="item-search">
        <el-icon><Search /></el-icon>
        <input type="search" placeholder="搜索商品关键词..." />
      </div>

      <div class="item-actions">
        <el-button disabled>{{ userEmail || '已登录' }}</el-button>
        <el-button type="primary" @click="go('/services')">我的服务订单</el-button>
      </div>
    </header>

    <main class="item-shell">
      <section class="item-hero">
        <div class="item-gallery">
          <img :src="product.cover" :alt="product.name" />
          <div class="gallery-strip">
            <span>自动发货</span>
            <span>欧洲渠道</span>
            <span>Hotmail 登录</span>
          </div>
        </div>

        <aside class="buy-panel">
          <div class="buy-heading">
            <div>
              <p class="item-eyebrow">成品服务</p>
              <h1>{{ product.name }}</h1>
            </div>
            <el-button circle title="分享商品" @click="shareProduct">
              <el-icon><Share /></el-icon>
            </el-button>
          </div>

          <div class="badge-row">
            <span class="badge green">自动发货</span>
            <span v-if="product.sold !== null" class="badge blue">已售 {{ product.sold.toLocaleString() }}</span>
            <span class="badge cyan">{{ product.stock === null ? '库存待同步' : `库存 ${product.stock}` }}</span>
          </div>

          <div class="price-block">
            <span>到手价</span>
            <strong>{{ product.price === null ? '待实时询价' : `¥${product.price.toFixed(2)} CNY` }}</strong>
            <small>价格和库存均以外部服务端本次会话的返回值为准。</small>
          </div>

          <form class="order-form" @submit.prevent="submitOrder">
            <label>
              <span>联系方式</span>
              <el-input v-model="form.contact" size="large" disabled placeholder="自动使用登录邮箱">
                <template #prefix>
                  <el-icon><User /></el-icon>
                </template>
              </el-input>
            </label>

            <label>
              <span>购买数量</span>
              <div class="quantity-control">
                <button type="button" @click="changeQuantity(-1)">
                  <el-icon><Minus /></el-icon>
                </button>
                <input v-model.number="form.quantity" type="number" min="1" :max="product.stock || 1" :disabled="!sessionReady" />
                <button type="button" @click="changeQuantity(1)">
                  <el-icon><Plus /></el-icon>
                </button>
              </div>
            </label>

            <label>
              <span>人机验证</span>
              <div class="captcha-row">
                <el-input v-model="form.captcha" size="large" placeholder="输入右侧验证码" />
                <button type="button" class="captcha-image" :disabled="captchaLoading" @click="refreshCaptcha">
                  <img v-if="captchaUrl" :src="captchaUrl" alt="验证码" />
                  <span v-else>刷新</span>
                </button>
              </div>
            </label>

            <div class="pay-methods" aria-label="付款方式">
              <button
                v-for="method in payMethods"
                :key="method.id"
                type="button"
                :class="{ selected: form.payId === method.id }"
                @click="form.payId = method.id"
              >
                <el-icon><Check /></el-icon>
                <span>{{ method.name }}</span>
              </button>
            </div>

            <p class="sync-status" :class="{ error: syncError }">{{ syncMessage }}</p>

            <div class="order-summary">
              <span>订单合计</span>
              <strong>{{ product.price === null ? '待询价' : `¥${totalPrice.toFixed(2)} CNY` }}</strong>
            </div>

            <el-button type="primary" size="large" native-type="submit" class="submit-button" :loading="submitting" :disabled="!canOrder">
              立即购买
            </el-button>
          </form>
        </aside>
      </section>

      <section class="detail-layout">
        <article class="detail-panel">
          <div class="section-title">
            <el-icon><DocumentChecked /></el-icon>
            <h2>宝贝详情</h2>
          </div>
          <div class="detail-copy">
            <p>Plus 成品号，微软 Hotmail 邮箱登录，可直接获得原始邮箱，并附自建快捷接码平台。</p>
            <p>支持下载 sub2 / cpa 格式 JSON，包含 RT+ 与已绑定手机号验证信息。</p>
            <p>
              兑换地址：
              <a href="https://chongzhi.art/" target="_blank" rel="noreferrer noopener">https://chongzhi.art/</a>
            </p>
          </div>
        </article>

        <article class="detail-panel notice-panel">
          <div class="section-title">
            <el-icon><Warning /></el-icon>
            <h2>购买前提示</h2>
          </div>
          <ul>
            <li>账号为一号一绑欧洲渠道，该商品无质保，请谨慎购买。</li>
            <li>非日抛号，但建议尽快做好账号与会话数据备份。</li>
            <li>请尽快使用剩余额度，重要会话内容及时保存。</li>
            <li>本功能开关启用后，点击购买会向外部服务提交真实订单，并可能跳转到真实支付页。</li>
          </ul>
        </article>

        <article class="tier-panel">
          <h2>价格说明</h2>
          <p>页面不内置阶梯价或历史价。修改数量后会重新向外部服务询价，提交前请核对本次订单合计。</p>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Check,
  DocumentChecked,
  Minus,
  Plus,
  Search,
  Share,
  ShoppingCart,
  Tickets,
  User,
  Warning
} from '@element-plus/icons-vue'
import http from '@/utils/http'

type PayMethod = {
  id: number
  name: string
  icon?: string
}

const router = useRouter()

const product = reactive<{
  name: string
  cover: string
  price: number | null
  stock: number | null
  sold: number | null
}>({
  name: 'GPT RT Plus 成品号（欧洲渠道）',
  cover: 'https://shopgpt.plus/assets/cache/general/image/202603191955477249694.jpg',
  price: null,
  stock: null,
  sold: null
})

const form = reactive({
  contact: '',
  quantity: 1,
  captcha: '',
  payId: 7
})

const userEmail = ref('')
const captchaUrl = ref('')
const captchaLoading = ref(false)
const submitting = ref(false)
const syncing = ref(false)
const syncError = ref(false)
const syncMessage = ref('正在建立第三方下单会话...')
const sessionReady = ref(false)
const payMethods = ref<PayMethod[]>([])
let syncTimer: number | undefined

const totalPrice = computed(() => {
  const quantity = clampQuantity(form.quantity)
  return quantity * (product.price || 0)
})
const canOrder = computed(() => sessionReady.value && product.price !== null && product.price > 0 && product.stock !== null && product.stock > 0)

const go = (path: string) => router.push(path)

const clampQuantity = (value: number) => {
  const next = Number.isFinite(value) ? Math.floor(value) : 1
  const availableStock = product.stock && product.stock > 0 ? product.stock : 1
  return Math.min(availableStock, Math.max(1, next))
}

const changeQuantity = (step: number) => {
  form.quantity = clampQuantity(form.quantity + step)
}

const applyEmail = (email: string) => {
  userEmail.value = email
  form.contact = email
}

const normalizePayMethods = (rows: unknown): PayMethod[] => {
  if (!Array.isArray(rows)) return []
  return rows
    .map(item => {
      const row = item as Record<string, unknown>
      return {
        id: Number(row.id),
        name: String(row.name || ''),
        icon: typeof row.icon === 'string' ? row.icon : undefined
      }
    })
    .filter(item => Number.isFinite(item.id) && item.name)
}

const errorMessage = (error: unknown, fallback: string) => {
  const err = error as { response?: { data?: { message?: string; error?: string; path?: string } } }
  return err.response?.data?.message || err.response?.data?.error || fallback
}

const applySyncPayload = (payload: any) => {
  const valuationPrice = Number(payload?.valuation?.data?.price)
  const stockValue = Number(payload?.stock?.data?.stock)
  if (Number.isFinite(valuationPrice) && valuationPrice > 0) {
    product.price = valuationPrice / clampQuantity(form.quantity)
  }
  if (Number.isFinite(stockValue) && stockValue >= 0) {
    product.stock = Math.floor(stockValue)
  }
}

const refreshCaptcha = async () => {
  captchaLoading.value = true
  try {
    const res = await http.get('/api/shopgpt/item-68/captcha')
    const data = res.data
    captchaUrl.value = `data:${data.contentType};base64,${data.base64}`
    form.captcha = ''
  } catch (error) {
    ElMessage.error(errorMessage(error, '验证码加载失败'))
  } finally {
    captchaLoading.value = false
  }
}

const syncDraft = async () => {
  syncing.value = true
  syncError.value = false
  try {
    form.quantity = clampQuantity(form.quantity)
    const res = await http.post('/api/shopgpt/item-68/sync', {
      quantity: form.quantity,
      captcha: form.captcha,
      payId: form.payId
    })
    applySyncPayload(res.data)
    syncMessage.value = `已同步到第三方会话：联系方式为 ${userEmail.value}，订单查询凭据由服务端随机生成并保护。`
  } catch (error) {
    syncError.value = true
    syncMessage.value = errorMessage(error, '第三方会话同步失败，请刷新验证码或稍后重试')
  } finally {
    syncing.value = false
  }
}

const scheduleSync = () => {
  window.clearTimeout(syncTimer)
  syncTimer = window.setTimeout(() => {
    syncDraft()
  }, 300)
}

const loadSession = async () => {
  const res = await http.get('/api/shopgpt/item-68/session')
  applyEmail(res.data.email)
  const methods = normalizePayMethods(res.data.payMethods)
  payMethods.value = methods.length ? methods : [{ id: 7, name: '支付宝' }]
  form.payId = payMethods.value[0].id
  applySyncPayload(res.data.sync)
  sessionReady.value = true
  syncMessage.value = `已连接第三方会话：联系方式为 ${userEmail.value}，不会复用邮箱或平台密码作为查询凭据。`
  await refreshCaptcha()
}

const shareProduct = async () => {
  const shareUrl = `${window.location.origin}/item`
  try {
    await navigator.clipboard.writeText(shareUrl)
    ElMessage.success('商品链接已复制')
  } catch {
    ElMessage.info(shareUrl)
  }
}

const submitOrder = async () => {
  form.quantity = clampQuantity(form.quantity)
  if (!canOrder.value) {
    ElMessage.warning('实时价格或库存尚未完成校验，暂不能下单')
    return
  }
  if (!userEmail.value) {
    ElMessage.warning('当前账号没有邮箱，无法同步到第三方页面')
    return
  }
  if (!form.captcha.trim()) {
    ElMessage.warning('请输入第三方页面验证码')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确认向外部服务提交 ${form.quantity} 件真实订单，当前合计 ¥${totalPrice.value.toFixed(2)} CNY？`,
      '外部真实下单确认',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' }
    )
  } catch {
    return
  }

  submitting.value = true
  try {
    const res = await http.post('/api/shopgpt/item-68/trade', {
      quantity: form.quantity,
      captcha: form.captcha,
      payId: form.payId
    })
    if (res.data?.code === 200 && res.data?.data?.url) {
      window.location.href = res.data.data.url
      return
    }
    if (res.data?.code === 200) {
      ElMessage.success(res.data?.msg || '第三方订单已提交')
      return
    }
    ElMessage.error(res.data?.msg || '第三方下单失败')
    await refreshCaptcha()
  } catch (error) {
    ElMessage.error(errorMessage(error, '第三方下单失败'))
    await refreshCaptcha()
  } finally {
    submitting.value = false
  }
}

watch(
  () => [form.quantity, form.captcha, form.payId],
  () => {
    if (userEmail.value) scheduleSync()
  }
)

onMounted(() => {
  loadSession().catch(error => {
    syncError.value = true
    syncMessage.value = errorMessage(error, '第三方会话初始化失败，请确认当前账号已绑定邮箱')
  })
})
</script>

<style scoped>
.item-page {
  min-height: 100vh;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.86), rgba(246, 248, 251, 0.96)),
    radial-gradient(circle at 18% 12%, rgba(20, 184, 166, 0.16), transparent 30%),
    radial-gradient(circle at 82% 18%, rgba(37, 99, 235, 0.14), transparent 34%),
    #f6f8fb;
  color: #111827;
}

.item-nav {
  position: sticky;
  top: 0;
  z-index: 10;
  max-width: 1180px;
  min-height: 76px;
  display: grid;
  grid-template-columns: auto auto minmax(220px, 1fr) auto;
  align-items: center;
  gap: 16px;
  margin: 0 auto;
  padding: 14px 24px;
  background: rgba(246, 248, 251, 0.88);
  backdrop-filter: blur(16px);
}

.item-brand,
.item-links button {
  border: 0;
  background: transparent;
  cursor: pointer;
}

.item-brand {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  color: #0f172a;
  font-weight: 800;
}

.item-brand-mark {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border-radius: 8px;
  background: #0f172a;
  color: #ffffff;
}

.item-links {
  display: flex;
  gap: 4px;
  padding: 4px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
}

.item-links button {
  min-height: 36px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 0 12px;
  border-radius: 6px;
  color: #526070;
}

.item-links button.active,
.item-links button:hover {
  background: #eef5ff;
  color: #0b63ce;
}

.item-search {
  min-height: 42px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #ffffff;
  color: #64748b;
}

.item-search input {
  width: 100%;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: #111827;
}

.item-actions {
  display: flex;
  gap: 10px;
}

.item-shell {
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px 24px 54px;
}

.item-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.02fr) minmax(380px, 0.8fr);
  gap: 22px;
  align-items: stretch;
}

.item-gallery,
.buy-panel,
.detail-panel,
.tier-panel {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 18px 46px rgba(15, 23, 42, 0.08);
}

.item-gallery {
  overflow: hidden;
}

.item-gallery img {
  width: 100%;
  height: 100%;
  min-height: 530px;
  display: block;
  object-fit: cover;
}

.gallery-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  padding: 14px;
  border-top: 1px solid #e2e8f0;
}

.gallery-strip span {
  display: grid;
  min-height: 38px;
  place-items: center;
  border-radius: 8px;
  background: #f6f8fb;
  color: #475569;
  font-size: 13px;
  font-weight: 700;
}

.buy-panel {
  padding: 24px;
}

.buy-heading {
  display: flex;
  justify-content: space-between;
  gap: 16px;
}

.item-eyebrow {
  margin: 0 0 9px;
  color: #0b63ce;
  font-size: 13px;
  font-weight: 800;
}

.buy-heading h1 {
  margin: 0;
  color: #0f172a;
  font-size: 32px;
  line-height: 1.18;
  letter-spacing: 0;
}

.badge-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.badge {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 700;
}

.badge.green {
  color: #047857;
  background: #dff8ec;
}

.badge.blue {
  color: #0b63ce;
  background: #e8f2ff;
}

.badge.cyan {
  color: #0e7490;
  background: #e5f8fb;
}

.price-block {
  margin-top: 22px;
  padding: 18px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.price-block span,
.price-block small {
  display: block;
  color: #64748b;
}

.price-block strong {
  display: block;
  margin: 8px 0 4px;
  color: #e11d48;
  font-size: 42px;
  line-height: 1;
}

.order-form {
  display: grid;
  gap: 16px;
  margin-top: 20px;
}

.order-form label > span {
  display: block;
  margin-bottom: 7px;
  color: #334155;
  font-size: 14px;
  font-weight: 700;
}

.quantity-control,
.captcha-row {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) 44px;
  gap: 8px;
}

.quantity-control button {
  display: grid;
  min-height: 44px;
  place-items: center;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  background: #ffffff;
  color: #334155;
  cursor: pointer;
}

.quantity-control input {
  min-width: 0;
  min-height: 44px;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  text-align: center;
  outline: 0;
}

.captcha-row {
  grid-template-columns: minmax(0, 1fr) 112px;
}

.captcha-image {
  display: grid;
  min-height: 44px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background:
    repeating-linear-gradient(135deg, #e2e8f0 0, #e2e8f0 6px, #f8fafc 6px, #f8fafc 12px);
  color: #0f172a;
  font-weight: 900;
  letter-spacing: 3px;
  cursor: pointer;
  overflow: hidden;
  padding: 0;
}

.captcha-image img {
  width: 100%;
  height: 44px;
  display: block;
  object-fit: cover;
}

.sync-status {
  min-height: 22px;
  margin: -2px 0 0;
  color: #0b63ce;
  font-size: 13px;
  line-height: 1.5;
}

.sync-status.error {
  color: #dc2626;
}

.pay-methods {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.pay-methods button {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid #dbe3ee;
  border-radius: 8px;
  background: #ffffff;
  color: #475569;
  cursor: pointer;
}

.pay-methods button.selected {
  border-color: #0b63ce;
  background: #eef5ff;
  color: #0b63ce;
  font-weight: 800;
}

.order-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 0 0;
  border-top: 1px solid #e2e8f0;
}

.order-summary span {
  color: #64748b;
}

.order-summary strong {
  color: #e11d48;
  font-size: 28px;
}

.submit-button {
  width: 100%;
}

.detail-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 0.7fr);
  gap: 18px;
  margin-top: 18px;
}

.detail-panel,
.tier-panel {
  padding: 22px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.section-title h2,
.tier-panel h2 {
  margin: 0;
  color: #111827;
  font-size: 20px;
}

.detail-copy p,
.notice-panel li {
  color: #536273;
  line-height: 1.8;
}

.detail-copy p {
  margin: 0 0 10px;
}

.detail-copy a {
  color: #0b63ce;
  font-weight: 700;
}

.notice-panel ul {
  margin: 0;
  padding-left: 20px;
}

.tier-panel {
  grid-column: 1 / -1;
}

.tier-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-top: 14px;
}

.tier-grid div {
  min-height: 82px;
  padding: 14px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.tier-grid span,
.tier-grid strong {
  display: block;
}

.tier-grid span {
  color: #64748b;
}

.tier-grid strong {
  margin-top: 10px;
  color: #111827;
  font-size: 24px;
}

@media (max-width: 980px) {
  .item-nav,
  .item-hero,
  .detail-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .item-search {
    order: 4;
  }

  .item-actions {
    justify-content: flex-start;
  }

  .item-gallery img {
    min-height: 360px;
  }
}

@media (max-width: 640px) {
  .item-nav,
  .item-shell {
    padding-left: 16px;
    padding-right: 16px;
  }

  .item-links,
  .item-actions,
  .pay-methods,
  .gallery-strip,
  .tier-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .item-links,
  .item-actions {
    display: grid;
  }

  .buy-heading h1 {
    font-size: 26px;
  }

  .price-block strong {
    font-size: 34px;
  }
}
</style>
