<template>
  <main class="service07-page">
    <div class="ambient ambient-one" />
    <div class="ambient ambient-two" />

    <section class="service07-shell">
      <header class="page-heading">
        <div class="brand-mark" aria-hidden="true"><span>07</span></div>
        <div>
          <p class="eyebrow">CHATGPT PLUS · SESSION SUBSCRIPTION</p>
          <h1>使用 Session 完成订阅</h1>
          <p class="heading-copy">按下方步骤复制 Session，粘贴后即可进入安全支付流程。</p>
        </div>
      </header>

      <div class="content-grid">
        <section class="guide-card" aria-labelledby="session-guide-title">
          <div class="card-title">
            <span class="title-icon">?</span>
            <div>
              <p>操作指引</p>
              <h2 id="session-guide-title">如何复制 Session</h2>
            </div>
          </div>

          <ol class="guide-steps">
            <li>
              <span class="step-number">1</span>
              <div>
                <strong>登录 ChatGPT</strong>
                <p>
                  在浏览器中打开
                  <a href="https://chatgpt.com" target="_blank" rel="noopener noreferrer">chatgpt.com</a>
                  并确认账号处于登录状态。
                </p>
              </div>
            </li>
            <li>
              <span class="step-number">2</span>
              <div>
                <strong>打开Session页面</strong>
                <p>复制 <kbd>https://chatgpt.com/api/auth/session</kbd> 并打开，<kbd>ctrl</kbd>+<kbd>A</kbd>选中然后<kbd>ctrl</kbd>+<kbd>C</kbd>复制所有内容</p>
              </div>
            </li>
            <li>
              <span class="step-number">3</span>
              <div>
                <strong>粘贴 Session 值</strong>
                <p>
                  <kbd>ctrl</kbd>+<kbd>V</kbd>把Session粘贴到右边的输入框内
                </p>
              </div>
            </li>
          </ol>

          <div class="security-tip">
            <span class="shield" aria-hidden="true">✓</span>
            <p><strong>隐私保护</strong> Session 仅用于本次订阅任务，不会写入数据库或日志。</p>
          </div>
        </section>

        <form class="subscription-card" autocomplete="off" @submit.prevent="subscribe">
          <div class="form-heading">
            <div>
              <p>最后一步</p>
              <h2>粘贴你的 Session</h2>
            </div>
            <span class="secure-badge"><i /> 安全传输</span>
          </div>

          <label for="service07-session">Session</label>
          <el-input
            id="service07-session"
            v-model="session"
            type="textarea"
            :rows="8"
            maxlength="12000"
            resize="none"
            autocomplete="off"
            aria-label="Session"
            placeholder="在此粘贴完整的 session-token…"
            :disabled="working"
            :spellcheck="false"
          />
          <p class="field-help">请确认没有遗漏开头、结尾或中间的任何字符。</p>

          <div class="flow-preview" aria-label="订阅执行流程">
            <span :class="{ active: phase === 'payment' }">确认支付</span>
            <i />
            <span :class="{ active: phase === 'fulfillment' }">申请卡片</span>
            <i />
            <span :class="{ active: phase === 'fulfillment' }">自动订阅</span>
          </div>

          <el-button
            native-type="submit"
            type="primary"
            size="large"
            :loading="working"
            :disabled="working || session.trim().length <= 50"
          >
            {{ buttonText }}
          </el-button>
          <p class="agreement">点击订阅即表示你确认 Session 属于本人账号。</p>
        </form>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

type ServiceOrder = {
  id: number
  status: string
  paymentUrl?: string
}

type Fulfillment = {
  orderId: number
  status: string
  errorMessage?: string
}

const session = ref('')
const working = ref(false)
const phase = ref<'idle' | 'payment' | 'fulfillment'>('idle')
let disposed = false

const buttonText = computed(() => {
  if (phase.value === 'payment') return '等待支付'
  if (phase.value === 'fulfillment') return '正在订阅'
  return '订阅'
})

const delay = (milliseconds: number) =>
  new Promise<void>(resolve => window.setTimeout(resolve, milliseconds))

function normalizeStatus(status?: string) {
  return (status || '').trim().toUpperCase()
}

function openPaymentPage(target: Window | null, paymentUrl?: string) {
  if (!paymentUrl) throw new Error('支付链接尚未生成，请稍后重试')
  const url = new URL(paymentUrl, window.location.origin)
  if (!['https:', 'http:'].includes(url.protocol)) throw new Error('支付链接无效')
  if (!target || target.closed) {
    throw new Error('支付窗口被浏览器拦截，请允许本站弹出窗口后重试')
  }
  target.opener = null
  target.location.replace(url.toString())
}

async function waitForPayment(orderId: number): Promise<ServiceOrder> {
  const deadline = Date.now() + 10 * 60 * 1000
  let attempts = 0
  while (!disposed && Date.now() < deadline) {
    await delay(2500)
    const response = await http.get<ServiceOrder>(`/api/service-orders/${orderId}`)
    let order = response.data
    if (['PAID', 'FULFILLED'].includes(normalizeStatus(order.status))) return order

    attempts += 1
    if (attempts % 4 === 0) {
      try {
        const queried = await http.post(`/api/service-orders/${orderId}/payment/query`)
        order = queried.data?.order || order
        if (['PAID', 'FULFILLED'].includes(normalizeStatus(order.status))) return order
      } catch {
        // Continue polling the locally verified webhook status.
      }
    }
  }
  throw new Error('等待支付超时，请确认支付结果后重新点击订阅')
}

async function waitForFulfillment(orderId: number) {
  const deadline = Date.now() + 8 * 60 * 1000
  while (!disposed && Date.now() < deadline) {
    await delay(2500)
    const response = await http.get<Fulfillment>(
      `/api/service-orders/${orderId}/service-07-fulfillment`
    )
    const fulfillment = response.data
    if (fulfillment.status === 'SUCCEEDED') return
    if (fulfillment.status === 'FAILED') {
      throw new Error(fulfillment.errorMessage || '订阅自动化执行失败')
    }
  }
  throw new Error('订阅任务仍在后台执行，请稍后重新进入查看')
}

async function subscribe() {
  const rawSession = session.value.trim()
  if (rawSession.length <= 50) {
    ElMessage.warning('请输入有效的 Session')
    return
  }

  const paymentWindow = window.open('', '_blank')
  working.value = true
  phase.value = 'payment'
  try {
    const orderResponse = await http.post('/api/service-07/order')
    let order: ServiceOrder = orderResponse.data?.order
    if (!order?.id) throw new Error('订单创建失败')

    if (!['PAID', 'FULFILLED'].includes(normalizeStatus(order.status))) {
      const paymentResponse = await http.post(`/api/service-orders/${order.id}/payment`)
      order = paymentResponse.data?.order || order
      if (['PAID', 'FULFILLED'].includes(normalizeStatus(order.status))) {
        paymentWindow?.close()
      } else {
        openPaymentPage(paymentWindow, paymentResponse.data?.paymentUrl)
        order = await waitForPayment(order.id)
      }
    } else {
      paymentWindow?.close()
    }

    phase.value = 'fulfillment'
    await http.post(`/api/service-orders/${order.id}/service-07-fulfillment`, {
      session: rawSession
    })
    session.value = ''
    await waitForFulfillment(order.id)
    ElMessage.success('订阅已完成')
  } catch (error: unknown) {
    paymentWindow?.close()
    ElMessage.error(getHttpErrorMessage(error, error instanceof Error ? error.message : '订阅失败'))
  } finally {
    working.value = false
    phase.value = 'idle'
  }
}

onBeforeUnmount(() => {
  disposed = true
})
</script>

<style scoped>
.service07-page {
  min-height: 100vh;
  position: relative;
  overflow: hidden;
  padding: 72px 24px;
  background:
    linear-gradient(145deg, rgb(248 250 252 / 98%), rgb(239 246 255 / 94%)),
    #f8fafc;
  color: #10233e;
}

.ambient {
  position: absolute;
  border-radius: 999px;
  filter: blur(12px);
  pointer-events: none;
}

.ambient-one {
  width: 420px;
  height: 420px;
  top: -210px;
  right: -70px;
  background: rgb(37 99 235 / 13%);
}

.ambient-two {
  width: 360px;
  height: 360px;
  bottom: -210px;
  left: -120px;
  background: rgb(14 165 233 / 11%);
}

.service07-shell {
  position: relative;
  z-index: 1;
  width: min(100%, 1080px);
  margin: 0 auto;
}

.page-heading {
  max-width: 760px;
  display: flex;
  align-items: center;
  gap: 22px;
  margin: 0 auto 34px;
}

.brand-mark {
  width: 78px;
  height: 78px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border: 1px solid rgb(255 255 255 / 70%);
  border-radius: 24px;
  background: linear-gradient(145deg, #2563eb, #0ea5e9);
  box-shadow: 0 18px 38px rgb(37 99 235 / 25%);
  color: #fff;
}

.brand-mark span {
  font-size: 25px;
  font-weight: 800;
  letter-spacing: -0.04em;
}

.eyebrow,
.card-title p,
.form-heading p {
  margin: 0;
  color: #2563eb;
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.page-heading h1 {
  margin: 4px 0 7px;
  color: #0f2747;
  font-size: clamp(30px, 5vw, 46px);
  line-height: 1.08;
  letter-spacing: -0.035em;
}

.heading-copy {
  margin: 0;
  color: #64748b;
  font-size: 16px;
  line-height: 1.7;
}

.content-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.92fr) minmax(0, 1.08fr);
  gap: 22px;
  align-items: stretch;
}

.guide-card,
.subscription-card {
  border: 1px solid rgb(203 213 225 / 72%);
  border-radius: 24px;
  background: rgb(255 255 255 / 86%);
  box-shadow: 0 24px 70px rgb(30 64 175 / 9%);
  backdrop-filter: blur(18px);
}

.guide-card {
  padding: 30px;
}

.subscription-card {
  display: flex;
  flex-direction: column;
  padding: 32px;
}

.card-title,
.form-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.card-title {
  justify-content: flex-start;
  padding-bottom: 22px;
  border-bottom: 1px solid #e8eef6;
}

.title-icon {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  border-radius: 13px;
  background: #eaf2ff;
  color: #2563eb;
  font-size: 18px;
  font-weight: 800;
}

.card-title h2,
.form-heading h2 {
  margin: 4px 0 0;
  color: #10233e;
  font-size: 22px;
  letter-spacing: -0.02em;
}

.guide-steps {
  display: grid;
  gap: 24px;
  margin: 26px 0;
  padding: 0;
  list-style: none;
}

.guide-steps li {
  display: grid;
  grid-template-columns: 34px 1fr;
  gap: 14px;
}

.step-number {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: 1px solid #bfd6ff;
  border-radius: 11px;
  background: #f3f7ff;
  color: #2563eb;
  font-size: 13px;
  font-weight: 800;
}

.guide-steps strong {
  display: block;
  margin: 2px 0 6px;
  color: #162b49;
  font-size: 15px;
}

.guide-steps p {
  margin: 0;
  color: #64748b;
  font-size: 13px;
  line-height: 1.75;
}

.guide-steps a {
  color: #2563eb;
  font-weight: 700;
  text-decoration: none;
}

.guide-steps code,
.guide-steps kbd {
  padding: 3px 6px;
  border: 1px solid #dce6f5;
  border-radius: 6px;
  background: #f6f8fc;
  color: #1e3a5f;
  font-size: 11px;
  overflow-wrap: anywhere;
}

.security-tip {
  display: grid;
  grid-template-columns: 32px 1fr;
  gap: 11px;
  align-items: center;
  padding: 14px;
  border: 1px solid #bdebd6;
  border-radius: 14px;
  background: #effcf6;
}

.security-tip .shield {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: #10b981;
  color: #fff;
  font-weight: 800;
}

.security-tip p {
  margin: 0;
  color: #3f6f60;
  font-size: 12px;
  line-height: 1.55;
}

.secure-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 10px;
  border: 1px solid #bdebd6;
  border-radius: 999px;
  background: #effcf6;
  color: #16835f;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.secure-badge i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 0 4px rgb(16 185 129 / 12%);
}

.subscription-card > label {
  margin: 28px 0 9px;
  color: #334155;
  font-size: 13px;
  font-weight: 750;
}

.subscription-card :deep(.el-textarea__inner) {
  min-height: 214px !important;
  padding: 17px;
  border: 1px solid #d6e0ee;
  border-radius: 12px;
  background: #f9fbfe;
  color: #18304f;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.65;
  box-shadow: inset 0 1px 2px rgb(15 23 42 / 3%);
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.subscription-card :deep(.el-textarea__inner:focus) {
  border-color: #3b82f6;
  background: #fff;
  box-shadow: 0 0 0 4px rgb(59 130 246 / 10%);
}

.field-help {
  margin: 8px 0 0;
  color: #94a3b8;
  font-size: 11px;
}

.flow-preview {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin: auto 0 18px;
  padding-top: 24px;
}

.flow-preview span {
  color: #94a3b8;
  font-size: 11px;
  font-weight: 700;
  transition: color 0.2s;
}

.flow-preview span.active {
  color: #2563eb;
}

.flow-preview i {
  width: 22px;
  height: 1px;
  background: #d7e1ee;
}

.subscription-card .el-button {
  width: 100%;
  min-height: 50px;
  border: 0;
  border-radius: 13px;
  background: linear-gradient(135deg, #2563eb, #0ea5e9);
  box-shadow: 0 12px 26px rgb(37 99 235 / 22%);
  font-size: 15px;
  font-weight: 750;
  letter-spacing: 0.04em;
}

.subscription-card .el-button:hover {
  transform: translateY(-1px);
  box-shadow: 0 16px 32px rgb(37 99 235 / 28%);
}

.subscription-card .el-button.is-disabled {
  background: #cbd5e1;
  box-shadow: none;
}

.agreement {
  margin: 11px 0 0;
  color: #94a3b8;
  font-size: 10px;
  text-align: center;
}

@media (max-width: 840px) {
  .service07-page {
    padding: 42px 18px;
  }

  .page-heading {
    align-items: flex-start;
  }

  .brand-mark {
    width: 64px;
    height: 64px;
    border-radius: 19px;
  }

  .content-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 540px) {
  .service07-page {
    padding: 30px 14px;
  }

  .page-heading {
    display: block;
    margin-bottom: 24px;
  }

  .brand-mark {
    width: 52px;
    height: 52px;
    margin-bottom: 18px;
    border-radius: 16px;
  }

  .brand-mark span {
    font-size: 19px;
  }

  .page-heading h1 {
    font-size: 29px;
  }

  .heading-copy {
    font-size: 14px;
  }

  .guide-card,
  .subscription-card {
    padding: 22px;
    border-radius: 20px;
  }

  .form-heading {
    align-items: flex-start;
  }

  .form-heading h2 {
    font-size: 20px;
  }

  .secure-badge {
    padding: 6px 8px;
  }

  .flow-preview {
    gap: 7px;
  }

  .flow-preview i {
    width: 12px;
  }
}
</style>
