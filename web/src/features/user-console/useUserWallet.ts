import { computed, ref, onBeforeUnmount, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import http, { createIdempotencyKey, getHttpErrorMessage, getHttpDownloadErrorMessage } from '@/utils/http';
import { DEFAULT_PAGE_SIZE } from '@/utils/listPage';
import { amountValue } from './format';
import { useRoute, useRouter } from 'vue-router';
import { displayCurrency } from '@/i18n/locale';
import { createPollingTask } from '@/utils/polling';
import { downloadFile } from '@/utils/download';
import { paymentActionOf, paymentExpiryOf } from '@/utils/payment';
import { usePaymentClock } from '@/utils/paymentClock';
import type { PaymentAction } from '@/components/PaymentActionDialog.vue';
import { formatCny, formatMoneyDto as formatLocalizedMoneyDto, type AmountUnits } from '@/utils/money';

export function useUserWallet(dashboard: Ref<any>, current: Ref<{ key: string }>, loadDashboard: () => Promise<void>) {
  const route = useRoute()
  const router = useRouter()
  const walletLoading = ref(false)
  const walletTransactionPage = ref(1)
  const walletTransactionPageSize = ref(DEFAULT_PAGE_SIZE)
  const walletTransactionTotal = ref(0)
  const redeeming = ref(false)
  const redeemCode = ref('')
  type MoneyDto = { amount: number | string; currency: string; scale: number }
  type RechargePlan = { id: number | string; name: string; amount: AmountUnits; bonus?: number; paymentMoney?: MoneyDto; totalCreditMoney?: MoneyDto }
  const rechargeVisible = ref(false)
  const rechargeSubmitting = ref(false)
  const rechargeLegal = ref<Record<string, any> | null>(null)
  const rechargeLegalLoading = ref(false)
  const checkoutAcknowledged = ref(false)
  const selectedRechargePlan = ref<RechargePlan | null>(null)
  const customRecharge = ref(false)
  const customRechargeAmount = ref<number | undefined>(1)
  const rechargeCheckoutReady = computed(() => rechargeLegal.value?.checkout_ready === true
    && Boolean(rechargeLegal.value.checkout_disclosure_id))
  const canSubmitRecharge = computed(() => rechargeCheckoutReady.value && checkoutAcknowledged.value
    && (customRecharge.value
      ? Number.isFinite(customRechargeAmount.value) && (customRechargeAmount.value ?? 0) >= 0.01
      : Boolean(selectedRechargePlan.value)))
  const rechargeOrders = ref<Array<Record<string, any>>>([])
  const rechargeOrderPage = ref(1)
  const rechargeOrderPageSize = ref(DEFAULT_PAGE_SIZE)
  const rechargeOrderTotal = ref(0)
  const paymentDialogVisible = ref(false)
  const paymentAction = ref<PaymentAction | null>(null)
  const activeRechargeId = ref<number | null>(null)
  const paymentNow = usePaymentClock()
  function rechargeExpired(order: Record<string, any>) {
    return !['PAID', 'REFUNDED'].includes(order.status) && (order.status === 'EXPIRED'
      || order.paymentIntent?.status === 'EXPIRED' || (paymentExpiryOf(order) ?? Infinity) <= paymentNow.value)
  }
  const resumeOrder = ref<Record<string, any> | null>(null)
  const resumeVisible = ref(false)
  const resumeLoading = ref(false)
  const resumeAccepted = ref(false)
  const resumeDisclosureId = ref('')
  const rechargeForm = ref({ needInvoice: false, billingName: '', contactEmail: '', billingAddressLine1: '', billingDistrict: '', billingCity: '', billingProvince: '', billingPostalCode: '', billingCountry: 'China', paymentMethod: 'alipay' })

  const wallet = ref({
    balance: 0 as AmountUnits,
    monthSpend: 0 as AmountUnits,
    giftBalance: 0 as AmountUnits,
    invoiceableAmount: 0 as AmountUnits,
    invoiceEnabled: false,
    transactions: [] as Array<Record<string, any>>,
    plans: [] as RechargePlan[]
  })

  async function loadWallet() {
    if (current.value.key !== 'wallet') return
    walletLoading.value = true
    try {
      const [response, ordersResponse] = await Promise.all([
        http.get('/api/platform/user/wallet', { params: { page: walletTransactionPage.value, pageSize: walletTransactionPageSize.value } }),
        http.get('/api/platform/user/recharge-orders', { params: { listPage: true, page: rechargeOrderPage.value, size: rechargeOrderPageSize.value } })
      ])
      wallet.value = {
        balance: amountValue(response.data?.balance),
        monthSpend: amountValue(response.data?.monthSpend),
        giftBalance: amountValue(response.data?.giftBalance),
        invoiceableAmount: amountValue(response.data?.invoiceableAmount),
        invoiceEnabled: Boolean(response.data?.invoiceEnabled),
        transactions: Array.isArray(response.data?.transactions) ? response.data.transactions : [],
        plans: Array.isArray(response.data?.plans) ? response.data.plans : []
      }
      if (!wallet.value.invoiceEnabled) rechargeForm.value.needInvoice = false
      walletTransactionTotal.value = Number(response.data?.transactionTotal || 0)
      rechargeOrders.value = Array.isArray(ordersResponse.data?.items) ? ordersResponse.data.items : []
      rechargeOrderTotal.value = Number(ordersResponse.data?.total || 0)
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, '钱包数据加载失败'))
    } finally {
      walletLoading.value = false
    }
  }

  function formatMoneyDto(money?: MoneyDto | null) {
    if (!money || !Number(money.scale)) return '—'
    return formatLocalizedMoneyDto(money)
  }

  async function loadCheckoutDisclosure() {
    checkoutAcknowledged.value = false
    rechargeLegalLoading.value = true
    rechargeLegal.value = null
    try {
      rechargeLegal.value = (await http.get('/api/public/legal')).data
    } catch {
      rechargeLegal.value = null
    } finally {
      rechargeLegalLoading.value = false
    }
  }

  function openRecharge(plan: RechargePlan) {
    void loadCheckoutDisclosure()
    customRecharge.value = false
    selectedRechargePlan.value = plan
    rechargeForm.value.contactEmail = String(dashboard.value?.profile?.email || dashboard.value?.user?.email || '')
    rechargeVisible.value = true
  }

  async function handleWalletTransactionPageSizeChange() {
    walletTransactionPage.value = 1
    await loadWallet()
  }

  async function handleRechargeOrderPageSizeChange() {
    rechargeOrderPage.value = 1
    await loadWallet()
  }

  function openCustomRecharge() {
    void loadCheckoutDisclosure()
    selectCustomRecharge()
    rechargeForm.value.contactEmail = String(dashboard.value?.profile?.email || dashboard.value?.user?.email || '')
    rechargeVisible.value = true
  }

  function selectRechargePlan(plan: RechargePlan) {
    customRecharge.value = false
    selectedRechargePlan.value = plan
  }

  function selectCustomRecharge() {
    customRecharge.value = true
    selectedRechargePlan.value = null
  }

  async function openRechargePanel() {
    await loadCheckoutDisclosure()
    if (route.path !== '/console/wallet') await router.push('/console/wallet')
    if (!wallet.value.plans.length) await loadWallet()
    selectedRechargePlan.value = wallet.value.plans[0] || null
    customRecharge.value = !selectedRechargePlan.value
    rechargeVisible.value = true
  }

  async function submitRecharge() {
    if (!canSubmitRecharge.value || rechargeSubmitting.value) return
    if (rechargeForm.value.needInvoice && Object.entries(rechargeForm.value)
      .some(([key, value]) => !['paymentMethod', 'needInvoice'].includes(key) && !String(value).trim())) {
      ElMessage.warning('请完整填写账单信息')
      return
    }
    const rechargeTarget = customRecharge.value
      ? { customAmount: customRechargeAmount.value, currency: displayCurrency.value }
      : { planId: selectedRechargePlan.value!.id }
    const disclosureId = rechargeLegal.value?.checkout_disclosure_id
    rechargeSubmitting.value = true
    try {
      const currentLegal = await http.get('/api/public/legal').then(response => response.data).catch(() => null)
      if (!currentLegal?.checkout_ready || currentLegal.checkout_disclosure_id !== disclosureId) {
        checkoutAcknowledged.value = false
        rechargeLegal.value = currentLegal
        ElMessage.warning('公开条款已更新或暂不可用，请重新阅读后确认')
        return
      }
      const created = await http.post('/api/platform/user/recharge-orders',
        { ...rechargeTarget, ...rechargeForm.value, needInvoice: wallet.value.invoiceEnabled && rechargeForm.value.needInvoice },
        { headers: { 'Idempotency-Key': createIdempotencyKey('wallet-recharge-order') } })
      const intentId = created.data?.paymentIntent?.id
      if (!intentId) throw new Error('支付意图创建失败')
      activeRechargeId.value = created.data?.id ?? null
      const started = await http.post(`/api/payment-intents/${intentId}/start`, {},
        { headers: { 'Idempotency-Key': createIdempotencyKey(`payment-start-${intentId}`), 'X-Checkout-Disclosure': disclosureId } })
      rechargeVisible.value = false
      const status = started.data?.intent?.status
      paymentAction.value = status === 'PAID' ? null : paymentActionOf(started.data, { paymentIntent: created.data?.paymentIntent })
      paymentDialogVisible.value = Boolean(paymentAction.value)
      ElMessage.success(status === 'PAID' ? '充值已到账' : '充值订单已创建，请完成付款')
      if (status !== 'PAID') pollPayment(intentId)
      await Promise.all([loadWallet(), loadDashboard()])
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, error instanceof Error ? error.message : '充值订单创建失败'))
      await loadWallet()
    } finally {
      rechargeSubmitting.value = false
    }
  }

  const paymentPoll = createPollingTask<number>({
    interval: 3000,
    maxAttempts: 600,
    async run(intentId, context) {
      const response = await http.get(`/api/payment-intents/${intentId}`)
      if (!context.isCurrent()) return
      if (response.data?.status !== 'PAID') return
      context.stop()
      paymentDialogVisible.value = false
      paymentAction.value = null
      ElMessage.success('支付已确认，充值额度已到账')
      await Promise.all([loadWallet(), loadDashboard()])
    }
  })

  function pollPayment(intentId: number) { paymentPoll.start(intentId) }

  async function openRechargePayment(id: number) {
    if (resumeLoading.value) return
    resumeLoading.value = true
    try {
      let order = (await http.get(`/api/platform/user/recharge-orders/${id}`)).data
      // A replacement is linked durably, so reopening an older row reuses it.
      for (let hop = 0; order?.retryOrderId && hop < 20; hop++) {
        order = (await http.get(`/api/platform/user/recharge-orders/${order.retryOrderId}`)).data
      }
      if (order?.paymentIntent?.providerTradeNo && !['PAID', 'REFUNDED'].includes(order.status)) {
        await http.post(`/api/payment-intents/${order.paymentIntent.id}/query`)
        order = (await http.get(`/api/platform/user/recharge-orders/${order.id}`)).data
      }
      if (!['PENDING', 'EXPIRED'].includes(order?.status) || !['PENDING', 'EXPIRED'].includes(order?.paymentIntent?.status)) {
        await loadWallet()
        ElMessage.warning('订单状态已变化，请查看充值订单')
        return
      }
      resumeOrder.value = order
      resumeAccepted.value = false
      resumeDisclosureId.value = ''
      resumeVisible.value = true
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, '发起支付失败'))
    } finally {
      resumeLoading.value = false
    }
  }

  async function resumeRechargePayment(accept: () => Promise<unknown>) {
    let order = resumeOrder.value
    const disclosureId = resumeDisclosureId.value
    if (!order?.paymentIntent?.id || !resumeAccepted.value || !disclosureId || resumeLoading.value) return
    resumeLoading.value = true
    try {
      await accept()
      if (rechargeExpired(order)) {
        const originalId = order.id
        order = (await http.post(`/api/platform/user/recharge-orders/${order.id}/retry`, {}, {
          headers: { 'Idempotency-Key': createIdempotencyKey(`recharge-retry-${order.id}`), 'X-Checkout-Disclosure': disclosureId }
        })).data
        if (order?.id !== originalId) rechargeOrderPage.value = 1
        if (order?.status === 'PAID') {
          resumeVisible.value = false
          paymentDialogVisible.value = false
          paymentAction.value = null
          ElMessage.success('充值已到账')
          await Promise.all([loadWallet(), loadDashboard()])
          return
        }
        if (!order?.paymentIntent?.id || rechargeExpired(order)) throw new Error('订单状态已变化，请查看充值订单')
      }
      activeRechargeId.value = order!.id
      const started = await http.post(`/api/payment-intents/${order.paymentIntent.id}/start`, {}, {
        headers: { 'Idempotency-Key': createIdempotencyKey(`payment-start-${order.paymentIntent.id}`), 'X-Checkout-Disclosure': disclosureId }
      })
      resumeVisible.value = false
      const paid = started.data?.intent?.status === 'PAID'
      rechargeForm.value.paymentMethod = order.paymentMethod
      paymentAction.value = paid ? null : paymentActionOf(started.data, { paymentIntent: order.paymentIntent })
      paymentDialogVisible.value = Boolean(paymentAction.value)
      if (paid) ElMessage.success('充值已到账')
      else pollPayment(order.paymentIntent.id)
      await Promise.all([loadWallet(), loadDashboard()])
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, '发起支付失败'))
      resumeVisible.value = false
      resumeAccepted.value = false
      await loadWallet()
    } finally {
      resumeLoading.value = false
    }
  }

  async function refreshRechargePayment(id = activeRechargeId.value) {
    if (!id || resumeLoading.value) return
    resumeLoading.value = true
    try {
      const order = (await http.get(`/api/platform/user/recharge-orders/${id}`)).data
      const intent = order?.paymentIntent
      const result = intent?.providerTradeNo && intent.status !== 'PAID'
        ? (await http.post(`/api/payment-intents/${intent.id}/query`)).data : intent
      if (result?.status === 'PAID') {
        paymentPoll.stop()
        paymentDialogVisible.value = false
        paymentAction.value = null
        ElMessage.success('充值已到账')
      }
      await Promise.all([loadWallet(), loadDashboard()])
    } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '查单失败')) }
    finally { resumeLoading.value = false }
  }

  async function retryActiveRecharge() {
    if (activeRechargeId.value) {
      paymentDialogVisible.value = false
      await openRechargePayment(activeRechargeId.value)
    }
  }

  async function downloadRecharge(id: number, type: 'invoice' | 'receipt') {
    try {
      await downloadFile(`/api/platform/user/recharge-orders/${id}/${type}`, `${type}-${id}.pdf`)
    } catch (error: unknown) { ElMessage.error(await getHttpDownloadErrorMessage(error, '文件下载失败')) }
  }

  async function redeemWalletCode() {
    const code = redeemCode.value.trim()
    if (!code) {
      ElMessage.warning('请输入兑换码')
      return
    }
    redeeming.value = true
    try {
      const response = await http.post('/api/platform/user/wallet/redeem', { code })
      redeemCode.value = ''
      ElMessage.success(`兑换成功，到账 ${formatCny(response.data?.amount || 0)}`)
      await Promise.all([loadWallet(), loadDashboard()])
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, '兑换失败，请检查兑换码'))
    } finally {
      redeeming.value = false
    }
  }

  onBeforeUnmount(() => paymentPoll.dispose())

  return {
    walletLoading,
    walletTransactionPage,
    walletTransactionPageSize,
    walletTransactionTotal,
    redeeming,
    redeemCode,
    rechargeVisible,
    rechargeSubmitting,
    rechargeLegal,
    rechargeLegalLoading,
    checkoutAcknowledged,
    selectedRechargePlan,
    customRecharge,
    customRechargeAmount,
    rechargeCheckoutReady,
    canSubmitRecharge,
    rechargeOrders,
    rechargeOrderPage,
    rechargeOrderPageSize,
    rechargeOrderTotal,
    paymentDialogVisible,
    paymentAction,
    rechargeExpired,
    refreshRechargePayment,
    retryActiveRecharge,
    resumeOrder,
    resumeVisible,
    resumeLoading,
    resumeAccepted,
    resumeDisclosureId,
    openRechargePayment,
    resumeRechargePayment,
    rechargeForm,
    wallet,
    loadWallet,
    formatMoneyDto,
    openRecharge,
    handleWalletTransactionPageSizeChange,
    handleRechargeOrderPageSizeChange,
    openCustomRecharge,
    selectRechargePlan,
    selectCustomRecharge,
    openRechargePanel,
    submitRecharge,
    downloadRecharge,
    redeemWalletCode,
  }
}
