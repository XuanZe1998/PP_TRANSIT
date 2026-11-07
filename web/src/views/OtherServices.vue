<template>
  <section class="other-services-page site-section">
    <div class="services-hero" :class="{ 'without-notice': !notice.enabled }">
      <div class="services-heading">
        <p class="eyebrow">AI SUBSCRIPTIONS</p>
        <h1>AI订阅</h1>
      </div>
      <aside v-if="notice.enabled" class="subscription-notice" aria-labelledby="subscription-notice-title">
        <span class="subscription-notice-icon" aria-hidden="true">!</span>
        <div>
          <h2 id="subscription-notice-title">{{ notice.title }}</h2>
          <p>{{ notice.content }}</p>
        </div>
      </aside>
      <div class="services-count">
        <span>当前可用</span>
        <strong>{{ serviceTotal }}</strong>
        <small>项服务</small>
      </div>
      <p class="services-description">所有服务统一配置价格、服务费和购买状态，并支持创建订单、支付与进度跟踪。</p>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-empty v-else-if="services.length === 0" description="暂无AI订阅" />
    <div v-else class="services-grid">
      <article v-for="(service, index) in services" :key="service.id" class="service-card">
        <div class="service-image">
          <img
            v-if="service.imageUrl && !failedImages.has(service.id)"
            :src="resolveApiResourceUrl(service.imageUrl)"
            :alt="service.name"
            crossorigin="anonymous"
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
            <el-tag v-if="service.productType === 'CARD_KEY'" type="success" effect="plain">卡密自动发货</el-tag>
          </div>
          <h2>{{ service.name }}</h2>
          <p>{{ service.description || '服务内容待完善' }}</p>
          <div class="service-order-row">
            <div class="service-price">
              <strong>{{ formatMoney(service.amountCents, service.currency) }}</strong>
              <small>
                商品 {{ formatMoney(service.priceCents, service.currency) }}
                + 服务费 {{ formatMoney(service.serviceFeeCents, service.currency) }}
              </small>
            </div>
            <div class="service-actions">
              <el-button
                type="primary"
                class="service-buy-button"
                :loading="buyingServiceId === service.id"
                :disabled="!service.orderEnabled"
                @click="handleServiceAction(service)"
              >
                {{ service.availableStock === 0 ? '已售罄' : (service.actionLabel || '立即购买') }}
              </el-button>
              <el-button
                v-if="service.redemptionConfigured"
                plain
                @click="router.push(service.redemptionPath || `/services/${service.id}/redeem`)"
              >
                去兑换
              </el-button>
            </div>
          </div>
        </div>
      </article>
    </div>
    <ListPagination v-if="serviceTotal > 0" v-model:page="servicePage" v-model:size="serviceSize" :total="serviceTotal" :allow-all="false" @change="loadCatalog" />

    <section v-if="authenticated" class="service-orders">
      <div class="orders-head">
        <div>
          <p class="eyebrow">订单中心</p>
          <h2>我的服务订单</h2>
        </div>
        <el-button :loading="ordersLoading" @click="loadOrders">刷新订单</el-button>
      </div>
      <PagedTable v-loading="ordersLoading" :data="orders" empty-text="暂无服务订单" list-id="OtherServices-1" pagination="external">
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
            <el-tag :type="statusType(row.status)">{{ orderStatusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="350" fixed="right">
          <template #default="{ row }">
            <el-button v-if="canPay(row)" link type="success" @click="startPayment(row)">去支付</el-button>
            <el-button
              v-if="row.status === 'FULFILLED' && row.fulfillmentStatus === 'COMPLETED'"
              link
              type="success"
              @click="showOrderDetail(row)"
            >查看卡密</el-button>
            <el-button v-else link type="primary" @click="showOrderDetail(row)">详情</el-button>
            <el-button
              v-if="!['FAILED', 'CANCELLED'].includes(row.status)"
              link
              type="primary"
              @click="downloadDocument(row.id, 'invoice')"
            >下载账单</el-button>
            <el-button
              v-if="['PAID', 'FULFILLED'].includes(row.status)"
              link
              type="primary"
              @click="downloadDocument(row.id, 'receipt')"
            >下载收据</el-button>
          </template>
        </el-table-column>
      </PagedTable>
      <ListPagination v-if="orderTotal > 0" v-model:page="orderPage" v-model:size="orderSize" :total="orderTotal" :allow-all="false" @change="loadOrders" />
    </section>

    <el-dialog v-model="orderDialogVisible" title="填写账单信息" width="min(680px, 94vw)">
      <el-alert
        title="以下信息将原样写入账单和收据，请按大陆地址格式填写。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-form ref="orderFormRef" :model="orderForm" :rules="orderRules" label-position="top" class="billing-form">
        <div class="product-order-config">
          <el-form-item label="购买数量">
            <el-input-number v-model="orderForm.quantity" :min="1" :max="selectedService?.maxPurchaseQuantity || 1" @change="refreshQuote" />
          </el-form-item>
          <el-form-item label="优惠码（可选）">
            <el-input v-model="orderForm.couponCode" maxlength="80" clearable placeholder="每笔订单限填一张券码" @change="refreshQuote" />
          </el-form-item>
          <el-form-item v-for="field in quote?.inputFields || []" :key="field.key" :label="field.label" :required="field.required">
            <el-input v-model="orderForm.customFields[field.key]" :maxlength="field.maxLength" show-word-limit />
          </el-form-item>
          <el-alert v-if="quote" :closable="false" type="success">
            <template #title>
              商品 {{ formatMoney(quote.merchandiseSubtotalCents, quote.currency) }}，
              阶梯优惠 {{ formatMoney(quote.wholesaleDiscountCents, quote.currency) }}，
              优惠码抵扣 {{ formatMoney(quote.couponDiscountCents, quote.currency) }}，
              服务费 {{ formatMoney(quote.serviceFeeCents, quote.currency) }}，
              应付 <strong>{{ formatMoney(quote.amountCents, quote.currency) }}</strong>
            </template>
          </el-alert>
          <el-alert v-if="selectedService?.purchasePrompt" :title="selectedService.purchasePrompt" type="info" :closable="false" />
        </div>
        <div class="billing-form-grid">
          <el-form-item label="姓名" prop="billingName">
            <el-input v-model="orderForm.billingName" maxlength="160" placeholder="例如：张三" />
          </el-form-item>
          <el-form-item label="账单邮箱" prop="contactEmail">
            <el-input v-model="orderForm.contactEmail" maxlength="255" placeholder="name@example.com" />
          </el-form-item>
          <el-form-item label="省 / 直辖市" prop="billingProvince">
            <el-input v-model="orderForm.billingProvince" maxlength="120" placeholder="例如：广东省" />
          </el-form-item>
          <el-form-item label="城市" prop="billingCity">
            <el-input v-model="orderForm.billingCity" maxlength="120" placeholder="例如：深圳市" />
          </el-form-item>
          <el-form-item label="区 / 县" prop="billingDistrict">
            <el-input v-model="orderForm.billingDistrict" maxlength="120" placeholder="例如：南山区" />
          </el-form-item>
          <el-form-item label="邮政编码" prop="billingPostalCode">
            <el-input v-model="orderForm.billingPostalCode" maxlength="20" placeholder="中国大陆地址填写 6 位邮编" />
          </el-form-item>
          <el-form-item label="详细地址" prop="billingAddressLine1" class="billing-form-wide">
            <el-input v-model="orderForm.billingAddressLine1" maxlength="255" placeholder="街道、道路、门牌号、楼栋和房间号" />
          </el-form-item>
          <el-form-item label="国家 / 地区" prop="billingCountry">
            <el-input v-model="orderForm.billingCountry" maxlength="120" />
          </el-form-item>
          <el-form-item label="付款方式" prop="paymentMethod">
            <el-radio-group v-model="orderForm.paymentMethod">
              <el-radio value="alipay">支付宝</el-radio>
              <el-radio value="wxpay">微信支付</el-radio>
              <el-radio value="balance">余额扣款</el-radio>
            </el-radio-group>
          </el-form-item>
        </div>
      </el-form>
      <CheckoutDisclosure purpose="order" v-model="checkoutAcknowledged" v-model:disclosure-id="checkoutDisclosureId" />
      <template #footer>
        <el-button @click="orderDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="buyingServiceId !== null" :disabled="!checkoutAcknowledged" @click="submitOrder">创建订单</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="checkoutDialogVisible"
      title="确认支付"
      width="min(560px, 94vw)"
      :close-on-click-modal="!startingPayment"
      :close-on-press-escape="!startingPayment"
      :show-close="!startingPayment"
    >
      <template v-if="checkoutDialogVisible && checkoutOrder">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="订单号">{{ checkoutOrder.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="服务">{{ checkoutOrder.productName }}</el-descriptions-item>
          <el-descriptions-item label="订单金额">{{ formatMoney(checkoutOrder.amountCents, checkoutOrder.currency) }}</el-descriptions-item>
          <el-descriptions-item label="支付金额">{{ formatMoney(checkoutOrder.paymentAmountCents ?? checkoutOrder.amountCents, checkoutOrder.paymentCurrency || checkoutOrder.currency) }}</el-descriptions-item>
          <el-descriptions-item label="付款方式">{{ paymentMethodLabels[checkoutOrder.paymentMethod || 'alipay'] }}</el-descriptions-item>
        </el-descriptions>
        <CheckoutDisclosure ref="paymentDisclosure" v-model="checkoutAccepted" v-model:disclosure-id="paymentDisclosureId" />
      </template>
      <template #footer>
        <el-button :disabled="startingPayment" @click="checkoutDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="startingPayment"
          :disabled="!checkoutAccepted || !paymentDisclosureId || startingPayment"
          @click="confirmPayment"
        >确认支付</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailDialogVisible" title="卡密与订单详情" width="min(720px, 94vw)" @closed="cancelRedemptionCountdown">
      <template v-if="orderDetail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="订单号">{{ orderDetail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="履约状态">{{ orderDetail.fulfillmentStatus ? fulfillmentStatusLabel(orderDetail.fulfillmentStatus) : orderStatusLabel(orderDetail.status) }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ orderDetail.quantity || 1 }}</el-descriptions-item>
          <el-descriptions-item label="最终金额">{{ formatMoney(orderDetail.amountCents, orderDetail.currency) }}</el-descriptions-item>
          <el-descriptions-item label="商品小计">{{ formatMoney(orderDetail.merchandiseSubtotalCents, orderDetail.currency) }}</el-descriptions-item>
          <el-descriptions-item label="优惠码抵扣">{{ formatMoney(orderDetail.couponDiscountCents, orderDetail.currency) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert v-if="orderDetail.purchasePrompt" class="detail-block" :title="orderDetail.purchasePrompt" type="info" :closable="false" />
        <el-alert
          v-if="redemptionCountdown > 0"
          class="detail-block"
          type="success"
          :closable="false"
          :title="`卡密已发货，${redemptionCountdown} 秒后自动前往兑换页。卡密也会永久保留在此订单中。`"
        />
        <el-alert v-if="awaitingFulfillment(orderDetail)" class="detail-block" title="支付成功，正在等待发货；卡密到账后会自动显示。" type="info" :closable="false" />
        <el-alert v-else-if="orderDetail.fulfillmentStatus === 'REVIEW_REQUIRED' || orderDetail.fulfillmentStatus === 'FAILED'" class="detail-block" title="支付已记录，履约需要管理员补充处理" type="warning" :closable="false" />
        <div v-if="orderDetail.deliveryItems?.length" class="delivery-box">
          <strong>已发货卡密</strong>
          <PagedList :data="orderDetail.deliveryItems" list-id="OtherServices.vue-2" v-slot="{items:pagedItems}"><div v-for="(item, index) in pagedItems" :key="index" class="delivery-row">
            <pre>{{ item }}</pre>
            <el-button size="small" @click="copyDelivery(item)">复制</el-button>
          </div></PagedList>
        </div>
      </template>
      <template #footer>
        <el-button v-if="orderDetail" @click="showOrderDetail(orderDetail)">刷新状态</el-button>
        <el-button v-if="orderDetail?.deliveryItems?.length" @click="copyAllDelivery">复制全部卡密</el-button>
        <el-button v-if="orderDetailRedemptionPath" type="primary" @click="goToRedemption">前往兑换</el-button>
      </template>
    </el-dialog>
    <PaymentActionDialog v-model="paymentDialogVisible" :action="paymentAction" :payment-method="paymentMethod" />
  </section>
</template>

<script setup lang="ts">
import PagedList from "@/components/PagedList.vue"
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import PaymentActionDialog, { type PaymentAction } from '@/components/PaymentActionDialog.vue'
import CheckoutDisclosure from '@/components/CheckoutDisclosure.vue'
import { paymentActionOf } from '@/utils/payment'
import { fulfillmentStatusLabel, orderStatusLabel } from '@/utils/serviceOrderStatus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { formatCurrencyCents } from '@/utils/money'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import http, { createIdempotencyKey, getHttpDownloadErrorMessage, getHttpErrorMessage, resolveApiResourceUrl } from '@/utils/http'
import { createPollingTask } from '@/utils/polling'
import { downloadFile } from '@/utils/download'
import { getToken } from '@/utils/auth'

type OtherService = {
  referralOffer?: { mode: string; fixed_cents: number; rate_bps: number; display_text?: string }
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
  fulfillmentMode?: string
  purchasePrompt?: string
  maxPurchaseQuantity?: number
  availableStock?: number
  productType?: 'STANDARD' | 'CARD_KEY'
  redemptionConfigured?: boolean
  redemptionPath?: string
}

type Quote = {
  amountCents: number
  merchandiseSubtotalCents: number
  wholesaleDiscountCents: number
  couponDiscountCents: number
  serviceFeeCents: number
  currency: string
  available: boolean
  inputFields: Array<{ key: string; label: string; required: boolean; maxLength: number }>
}

type ServiceOrder = {
  id: number
  serviceId?: number
  orderNo: string
  productName: string
  amountCents: number
  currency: string
  paymentAmountCents?: number
  referralCommissionCents?: number
  paymentCurrency?: string
  paymentMethod?: string
  exchangeRate?: number
  status: string
  quantity?: number
  fulfillmentStatus?: string
  merchandiseSubtotalCents?: number
  couponDiscountCents?: number
  purchasePrompt?: string
  deliveryItems?: string[]
  paymentUrl?: string
  createdAt: string
}

type PendingServicePayment = {
  orderId: number
  serviceId?: number
  createdAt: number
}

const pendingPaymentStorageKey = 'pending-service-payment'
const pendingPaymentMaxAge = 2 * 60 * 60 * 1000

const route = useRoute()
const agentCode = computed(() => typeof route.query.agent === 'string' ? route.query.agent.trim() : '')

const router = useRouter()
const services = ref<OtherService[]>([])
const orders = ref<ServiceOrder[]>([])
const servicePage = ref(1)
const serviceSize = ref(10)
const serviceTotal = ref(0)
const orderPage = ref(1)
const orderSize = ref(10)
const orderTotal = ref(0)
const checkoutDialogVisible = ref(false)
const checkoutOrder = ref<ServiceOrder | null>(null)
const checkoutAccepted = ref(false)
const paymentDisclosureId = ref('')
const paymentDisclosure = ref<InstanceType<typeof CheckoutDisclosure>>()
const startingPayment = ref(false)
const paymentDialogVisible = ref(false)
const paymentAction = ref<PaymentAction | null>(null)
const paymentMethod = ref('alipay')
const paymentMethodLabels: Record<string, string> = { alipay: '支付宝', wxpay: '微信支付', balance: '余额扣款' }
const notice = ref({ enabled: false, title: '', content: '' })
const loading = ref(true)
const ordersLoading = ref(false)
const buyingServiceId = ref<number | null>(null)
const failedImages = ref(new Set<number>())
const orderDialogVisible = ref(false)
const checkoutAcknowledged = ref(false)
const checkoutDisclosureId = ref('')
const orderFormRef = ref<FormInstance>()
const selectedService = ref<OtherService | null>(null)
const quote = ref<Quote | null>(null)
const quoteLoading = ref(false)
const detailDialogVisible = ref(false)
const orderDetail = ref<ServiceOrder | null>(null)
const orderDetailRedemptionPath = ref('')
const redemptionCountdown = ref(0)
let redemptionTimer: number | undefined

const authenticated = Boolean(getToken())
const orderForm = reactive({
  quantity: 1,
  couponCode: '',
  customFields: {} as Record<string, string>,
  billingName: '',
  contactEmail: '',
  billingProvince: '',
  billingCity: '',
  billingDistrict: '',
  billingAddressLine1: '',
  billingPostalCode: '',
  billingCountry: 'China',
  paymentMethod: 'alipay'
})
const orderRules: FormRules<typeof orderForm> = {
  billingName: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  contactEmail: [
    { required: true, message: '请填写账单邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
  ],
  billingProvince: [{ required: true, message: '请填写省或直辖市', trigger: 'blur' }],
  billingCity: [{ required: true, message: '请填写城市', trigger: 'blur' }],
  billingDistrict: [{ required: true, message: '请填写区或县', trigger: 'blur' }],
  billingAddressLine1: [{ required: true, message: '请填写详细地址', trigger: 'blur' }],
  billingPostalCode: [
    { required: true, message: '请填写邮政编码', trigger: 'blur' },
    {
      validator: (_rule, value: string, callback) => {
        const mainland = ['china', '中国', "people's republic of china", 'prc']
          .includes(orderForm.billingCountry.trim().toLowerCase())
        if (mainland && !/^\d{6}$/.test(value)) callback(new Error('中国大陆地址需填写 6 位邮政编码'))
        else callback()
      },
      trigger: 'blur'
    }
  ],
  billingCountry: [{ required: true, message: '请填写国家或地区', trigger: 'blur' }],
  paymentMethod: [{ required: true, message: '请选择付款方式', trigger: 'change' }]
}

const formatMoney = (cents?: number, currency?: string) => {
  if (cents === null || cents === undefined) return '-'
  return formatCurrencyCents(cents, currency)
}

const canPay = (order: ServiceOrder) => ['PENDING', 'CONFIRMED'].includes(order.status)
const deliveryComplete = (order: ServiceOrder) => order.status === 'FULFILLED'
  && order.fulfillmentStatus === 'COMPLETED'
const awaitingFulfillment = (order: ServiceOrder) => order.status === 'PAID'
  && !['COMPLETED', 'FAILED', 'REVIEW_REQUIRED'].includes(order.fulfillmentStatus || '')

const statusType = (status: string) => {
  if (['PAID', 'CONFIRMED'].includes(status)) return 'success'
  if (status === 'FULFILLED') return 'primary'
  if (['FAILED', 'CANCELLED'].includes(status)) return 'info'
  return 'warning'
}

function markImageFailed(id: number) {
  failedImages.value = new Set(failedImages.value).add(id)
}


async function handleServiceAction(service: OtherService) {
  openOrderDialog(service)
}

function goToPayment(paymentUrl?: string) {
  if (!paymentUrl) return false
  const target = new URL(paymentUrl, window.location.origin)
  if (!['https:', 'http:'].includes(target.protocol)) throw new Error('支付链接无效')
  window.location.assign(target.toString())
  return true
}

function rememberPendingPayment(order: ServiceOrder) {
  const pending: PendingServicePayment = {
    orderId: order.id,
    serviceId: order.serviceId,
    createdAt: Date.now()
  }
  window.sessionStorage.setItem(pendingPaymentStorageKey, JSON.stringify(pending))
}

function readPendingPayment(): PendingServicePayment | null {
  try {
    const raw = window.sessionStorage.getItem(pendingPaymentStorageKey)
    if (!raw) return null
    const pending = JSON.parse(raw) as Partial<PendingServicePayment>
    if (!Number.isInteger(pending.orderId) || !pending.createdAt
        || Date.now() - pending.createdAt > pendingPaymentMaxAge) {
      window.sessionStorage.removeItem(pendingPaymentStorageKey)
      return null
    }
    return pending as PendingServicePayment
  } catch {
    window.sessionStorage.removeItem(pendingPaymentStorageKey)
    return null
  }
}

function clearPendingPayment() {
  window.sessionStorage.removeItem(pendingPaymentStorageKey)
}

async function finishPaidFlow(order: ServiceOrder, message?: string) {
  clearPendingPayment()
  paymentDialogVisible.value = false
  ElMessage.success(message || '支付成功')
  await showOrderDetail(order)
  await loadOrders()
  if (awaitingFulfillment(order)) startFulfillmentPolling(order.id)
}

async function resumePendingPayment() {
  const queryId = Number(route.query.orderId)
  const pending = Number.isSafeInteger(queryId) && queryId > 0
    ? { orderId: queryId, serviceId: 0, createdAt: Date.now() }
    : readPendingPayment()
  if (!pending) return
  try {
    const { data: order } = await http.get<ServiceOrder>(`/api/service-orders/${pending.orderId}`)
    if (['PAID', 'FULFILLED'].includes(order.status)) {
      await finishPaidFlow(order)
    } else if (canPay(order)) {
      rememberPendingPayment(order)
      startFulfillmentPolling(order.id)
      // Query the provider once on return; polling below only reads local state.
      try { await http.post(`/api/service-orders/${order.id}/payment/query`) } catch { /* local polling continues */ }
    } else {
      clearPendingPayment()
      await showOrderDetail(order)
    }
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, '订单详情加载失败'))
  }
}

async function loadCatalog() {
  const endpoint = authenticated ? '/api/service-orders/catalog' : '/api/public/other-services'
  const code = agentCode.value
  const response = await http.get(endpoint, { params: { listPage: true, page: servicePage.value, size: serviceSize.value, agentCode: code || undefined } })
  if (code !== agentCode.value) return
  services.value = response.data?.items || []
  serviceTotal.value = Number(response.data?.total || 0)
}

async function loadOrders() {
  if (!authenticated) return
  ordersLoading.value = true
  try {
    const response = await http.get('/api/service-orders', { params: { listPage: true, page: orderPage.value, size: orderSize.value } })
    orders.value = response.data?.items || []
    orderTotal.value = Number(response.data?.total || 0)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '服务订单加载失败'))
  } finally {
    ordersLoading.value = false
  }
}

const fulfillmentPoll = createPollingTask<number>({
  interval: 2000,
  maxAttempts: 300,
  onExhausted: () => ElMessage.info('支付或发货结果仍在同步，请稍后刷新订单'),
  async run(targetId, context) {
    const { data: target } = await http.get<ServiceOrder>(`/api/service-orders/${targetId}`)
    if (!context.isCurrent()) return
    if (['PAID', 'FULFILLED'].includes(target.status)) {
      paymentDialogVisible.value = false
      clearPendingPayment()
      orderDetail.value = target
      detailDialogVisible.value = true
      if (awaitingFulfillment(target)) return
    } else if (canPay(target)) return
    else {
      clearPendingPayment()
      paymentDialogVisible.value = false
    }
    context.stop()
    await showOrderDetail(target)
    await loadOrders()
  }
})

function startFulfillmentPolling(orderId: number) { fulfillmentPoll.start(orderId) }

async function openOrderDialog(service: OtherService) {
  if (!authenticated) {
    router.push({ path: '/login', query: { redirect: route.fullPath, aff: agentCode.value || undefined } })
    return
  }
  if (!service.orderEnabled) {
    ElMessage.warning('该服务当前不可下单')
    return
  }
  checkoutAcknowledged.value = false
  selectedService.value = service
  orderForm.quantity = 1
  orderForm.couponCode = ''
  orderForm.customFields = {}
  orderDialogVisible.value = true
  await refreshQuote()
}

async function refreshQuote() {
  if (!selectedService.value) return
  quoteLoading.value = true
  try {
    const response = await http.post<Quote>('/api/service-orders/quote', {
      serviceId: selectedService.value.id,
      quantity: orderForm.quantity,
      couponCode: orderForm.couponCode || undefined,
      agentCode: agentCode.value || undefined
    })
    quote.value = response.data
    for (const field of quote.value.inputFields || []) {
      if (!(field.key in orderForm.customFields)) orderForm.customFields[field.key] = ''
    }
  } catch (error: unknown) {
    quote.value = null
    ElMessage.error(getHttpErrorMessage(error, '报价失败'))
  } finally {
    quoteLoading.value = false
  }
}

async function submitOrder() {
  if (!checkoutAcknowledged.value || !checkoutDisclosureId.value || quoteLoading.value || !quote.value) return
  const service = selectedService.value
  if (!service) return
  const code = agentCode.value
  try {
    await orderFormRef.value?.validate()
  } catch {
    return
  }
  const shownAmount = quote.value.amountCents
  buyingServiceId.value = service.id
  try {
    await refreshQuote()
    if (!orderDialogVisible.value || code !== agentCode.value) return
    if (!quote.value?.available) {
      ElMessage.warning('库存不足')
      return
    }
    if (quote.value.amountCents !== shownAmount) {
      ElMessage.warning('订单报价已更新，请核对金额后重新提交')
      return
    }
    for (const field of quote.value.inputFields || []) {
      if (field.required && !orderForm.customFields[field.key]?.trim()) {
        ElMessage.warning(`请填写${field.label}`)
        return
      }
    }
    const response = await http.post('/api/service-orders', {
      serviceId: service.id,
      ...orderForm,
      agentCode: code || undefined,
      expectedAmountCents: shownAmount
    }, {
      headers: { 'Idempotency-Key': createIdempotencyKey('service-order') }
    })
    ElMessage.success(response.data?.message || '订单已创建')
    orderDialogVisible.value = false
    const createdOrder = response.data?.order as ServiceOrder | undefined
    if (createdOrder && canPay(createdOrder)) startPayment(createdOrder)
    else if (createdOrder && ['PAID', 'FULFILLED'].includes(createdOrder.status)) {
      await finishPaidFlow(createdOrder)
    }
    await loadOrders()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '订单创建失败'))
  } finally {
    buyingServiceId.value = null
  }
}

function cancelRedemptionCountdown() {
  if (redemptionTimer !== undefined) window.clearInterval(redemptionTimer)
  redemptionTimer = undefined
  redemptionCountdown.value = 0
}

function startRedemptionCountdown() {
  cancelRedemptionCountdown()
  if (!orderDetailRedemptionPath.value) return
  redemptionCountdown.value = 10
  redemptionTimer = window.setInterval(() => {
    redemptionCountdown.value -= 1
    if (redemptionCountdown.value <= 0) void goToRedemption()
  }, 1000)
}

async function goToRedemption() {
  const path = orderDetailRedemptionPath.value
  cancelRedemptionCountdown()
  if (path) await router.replace(path)
}

async function showOrderDetail(order: ServiceOrder, autoRedemptionPath = '') {
  try {
    const response = await http.get<ServiceOrder>(`/api/service-orders/${order.id}`)
    orderDetail.value = response.data
    const service = services.value.find(item => item.id === response.data.serviceId)
    orderDetailRedemptionPath.value = autoRedemptionPath
      || (service?.redemptionConfigured ? (service.redemptionPath || `/services/${service.id}/redeem`) : '')
    detailDialogVisible.value = true
    if (autoRedemptionPath && response.data.deliveryItems?.length) startRedemptionCountdown()
    return true
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '订单详情加载失败'))
    return false
  }
}

async function copyDelivery(content: string) {
  await navigator.clipboard.writeText(content)
  ElMessage.success('交付内容已复制')
}

async function copyAllDelivery() {
  const items = orderDetail.value?.deliveryItems || []
  if (!items.length) return
  await navigator.clipboard.writeText(items.join('\n'))
  ElMessage.success('全部卡密已复制')
}

function startPayment(order: ServiceOrder) {
  if (startingPayment.value || !canPay(order)) return
  checkoutOrder.value = order
  checkoutAccepted.value = false
  paymentDisclosureId.value = ''
  checkoutDialogVisible.value = true
}

async function confirmPayment() {
  const order = checkoutOrder.value
  if (!order || !checkoutDialogVisible.value || !checkoutAccepted.value || !paymentDisclosureId.value || startingPayment.value) return
  startingPayment.value = true
  try {
    if (!paymentDisclosure.value) return
    await paymentDisclosure.value.accept()
    const response = await http.post(`/api/service-orders/${order.id}/payment`, {}, {
      headers: {
        'Idempotency-Key': createIdempotencyKey(`service-payment-${order.id}`),
        'X-Checkout-Disclosure': paymentDisclosureId.value
      }
    })
    checkoutDialogVisible.value = false
    await loadOrders()
    if (['PAID', 'FULFILLED'].includes(response.data?.order?.status)) {
      await finishPaidFlow(response.data.order, response.data?.message)
      return
    }
    const action = paymentActionOf(response.data)
    if (action) {
      rememberPendingPayment(response.data?.order || order)
      paymentAction.value = action
      paymentMethod.value = response.data?.paymentIntent?.paymentMethod || order.paymentMethod || 'alipay'
      paymentDialogVisible.value = true
      startFulfillmentPolling(order.id)
    } else {
      ElMessage.warning('支付链接尚未生成')
    }
  } catch (error: unknown) {
    await paymentDisclosure.value?.refresh()
    ElMessage.error(getHttpErrorMessage(error, '发起支付失败'))
  } finally {
    startingPayment.value = false
  }
}

async function downloadDocument(id: number, type: 'invoice' | 'receipt') {
  try {
    await downloadFile(`/api/service-orders/${id}/${type}`, `${type}-${id}.pdf`)
  } catch (error: unknown) {
    ElMessage.error(await getHttpDownloadErrorMessage(error, type === 'invoice' ? '账单下载失败' : '收据下载失败'))
  }
}

watch(agentCode, () => {
  orderDialogVisible.value = false
  servicePage.value = 1
  void loadCatalog().catch(error => ElMessage.error(getHttpErrorMessage(error, '订阅商品加载失败')))
})
onMounted(async () => {
  void http.get('/api/public/subscription-notice').then(({ data }) => { notice.value = data }).catch(() => undefined)
  try {
    await loadCatalog()
    if (authenticated) {
      await loadOrders()
      await resumePendingPayment()
    }
  } catch {
    ElMessage.error('AI订阅加载失败，请稍后重试')
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  cancelRedemptionCountdown()
  fulfillmentPoll.dispose()
})
</script>

<style scoped>
.other-services-page.site-section { padding-top: 28px; }
.services-hero {
  grid-template-columns: 150px minmax(0, 1fr) 150px;
  grid-template-areas: "heading notice count" "description description count";
  align-items: center;
  gap: 12px 20px;
  margin-bottom: 0;
}
.services-hero.without-notice {
  grid-template-columns: minmax(0, 1fr) 150px;
  grid-template-areas: "heading count" "description count";
}
.services-heading { grid-area: heading; }
.services-heading .eyebrow { margin: 0 0 10px; }
.services-description { grid-area: description; margin: 0; font-size: 16px; line-height: 1.75; }
.services-count { grid-area: count; align-self: stretch; display: flex; flex-direction: column; justify-content: center; }
.subscription-notice { grid-area: notice; min-width: 0; }
.subscription-notice h2 { overflow-wrap: anywhere; }
@media (max-width: 640px) {
  .other-services-page.site-section { padding-top: 20px; }
  .services-hero, .services-hero.without-notice {
    grid-template-columns: minmax(0, 1fr) 110px;
    grid-template-areas: "heading count" "notice notice" "description description";
    gap: 16px;
  }
  .services-hero.without-notice { grid-template-areas: "heading count" "description description"; }
  .services-heading h1 { font-size: 34px; }
  .services-count { padding: 14px; }
}

.subscription-notice {
  display: flex;
  gap: 18px;
  align-items: flex-start;
  padding: 24px;
  border: 2px solid #f59e0b;
  border-left-width: 6px;
  border-radius: 16px;
  background: linear-gradient(115deg, #fff7db, #fffdf5);
  color: #78350f;
  box-shadow: 0 8px 24px rgb(180 83 9 / 10%);
}
.subscription-notice-icon {
  flex: 0 0 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: #b45309;
  color: #fff;
  font-size: 24px;
  font-weight: 800;
}
.subscription-notice h2 { margin: 0 0 8px; font-size: 20px; color: #78350f; }
.subscription-notice p { margin: 0; line-height: 1.8; white-space: pre-wrap; overflow-wrap: anywhere; }
.subscription-notice > div { min-width: 0; }
@media (max-width: 640px) {
  .subscription-notice { padding: 18px 14px; gap: 12px; }
}

.service-card {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.service-image {
  flex-shrink: 0;
}

.other-services-page,
.service-orders {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.service-card-body {
  display: flex;
  flex: 1;
  min-width: 0;
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
  flex-direction: column;
  align-items: stretch;
  margin-top: auto;
  padding-top: 18px;
  border-top: 1px solid var(--lnx-line);
}

.service-price {
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-x: auto;
}

.service-order-row strong {
  color: var(--lnx-text);
  font-size: 22px;
  white-space: nowrap;
}

.service-order-row small {
  color: var(--lnx-text-muted);
  font-size: clamp(10px, 2.6vw, 12px);
  white-space: nowrap;
}

.service-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.service-actions .el-button + .el-button {
  margin-left: 0;
}

.service-orders {
  margin-top: 12px;
  padding: 24px;
  border: 1px solid var(--lnx-line);
  border-radius: 12px;
  background: #fff;
}

.orders-head h2 {
  margin: 4px 0 0;
}

.billing-form {
  margin-top: 20px;
}

.billing-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.billing-form-wide {
  grid-column: 1 / -1;
}

@media (max-width: 640px) {
  .service-order-row,
  .orders-head {
    align-items: stretch;
    flex-direction: column;
  }

  .billing-form-grid {
    grid-template-columns: 1fr;
  }

  .billing-form-wide {
    grid-column: auto;
  }
}
</style>
