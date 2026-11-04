<template>
  <section class="referral-console">
    <header class="referral-heading">
      <div><h1>{{ admin ? '邀请返佣管理' : '邀请有礼' }}</h1><p>邀请好友购买订阅，每笔有效购买均可获得返佣。</p></div>
      <el-space><el-button v-if="!admin" @click="router.push('/console')">返回控制台</el-button><el-button :loading="loading" @click="refresh">刷新</el-button></el-space>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <div class="referral-metrics">
      <article v-for="metric in metrics" :key="metric.key"><span>{{ metric.label }}</span><strong>{{ metric.key === 'invited' ? summary[metric.key] || 0 : money(summary[metric.key]) }}</strong></article>
    </div>
    <template v-if="!admin">
      <el-alert v-if="!summary.enabled" title="邀请活动尚未开启，已有佣金和提现记录仍可查询。" type="info" :closable="false" />
      <el-alert v-if="summary.debtCents" :title="'退款待抵扣金额 ' + money(summary.debtCents) + '，后续佣金将优先抵扣。'" type="warning" :closable="false" />
      <el-card shadow="never">
        <h2>专属邀请链接</h2>
        <el-button v-if="!summary.verified" @click="router.push('/console/profile')">前往验证邮箱</el-button>
        <el-button v-else-if="!summary.profile?.invite_code" :disabled="!summary.enabled" :loading="busy" @click="enroll">生成邀请链接</el-button>
        <el-space v-else wrap><el-input :model-value="inviteLink" readonly class="invite-input" /><el-button :disabled="summary.profile.status !== 'ACTIVE'" @click="copyInvite">复制链接</el-button><el-tag>{{ label(summary.profile.status) }}</el-tag></el-space>
        <p>仅通过链接新注册的好友建立邀请关系。佣金以订单确认的规则为准，最多为商品毛利的 50%，服务费及支付加价不计佣。</p>
        <p>交付完成后冻结 {{ summary.freeze_days ?? 7 }} 天，满 {{ money(summary.minimum_cents ?? 2000) }} 可申请提现。</p>
      </el-card>
      <el-card shadow="never">
        <h2>申请提现</h2><p>{{ summary.service_notice }}</p>
        <p v-if="savedAccount.masked_account">已保存 {{ label(savedAccount.channel) }} {{ savedAccount.masked_account }}</p>
        <el-form label-position="top" class="referral-form">
          <el-form-item label="收款方式"><el-select v-model="account.channel"><el-option label="支付宝" value="ALIPAY"/><el-option label="微信" value="WECHAT"/></el-select></el-form-item>
          <el-form-item :label="account.channel === 'ALIPAY' ? '支付宝账号' : '微信号'"><el-input v-model="account.account" maxlength="160" autocomplete="off"/></el-form-item>
          <el-form-item label="收款人姓名"><el-input v-model="account.name" maxlength="80" autocomplete="off"/></el-form-item>
          <el-form-item label="提现金额（元）"><el-input-number v-model="withdrawAmount" :min="0.01" :precision="2" :step="5"/></el-form-item>
        </el-form>
        <p v-if="account.channel === 'WECHAT'">管理员可能需要添加微信好友确认收款。</p>
        <el-space wrap><el-button :loading="busy" @click="saveAccount">保存收款信息</el-button><el-button type="primary" :loading="busy" :disabled="!savedAccount.masked_account || summary.debtCents > 0" @click="withdraw">确认并申请提现</el-button></el-space>
      </el-card>
    </template>
    <el-tabs v-model="tab" @tab-change="changeTab">
      <el-tab-pane v-if="admin" label="活动设置" name="settings">
        <el-form label-position="top" class="referral-form">
          <el-form-item label="开启邀请活动"><el-switch v-model="settings.enabled"/></el-form-item>
          <el-form-item label="交付后冻结天数"><el-input-number v-model="settings.freezeDays" :min="0" :max="365"/></el-form-item>
          <el-form-item label="最低提现金额（元）"><el-input-number v-model="minimumYuan" :min="0.01" :precision="2"/></el-form-item>
          <el-form-item label="处理时效说明"><el-input v-model="settings.serviceNotice" maxlength="500" type="textarea"/></el-form-item>
        </el-form><el-button type="primary" :loading="busy" @click="saveSettings">保存设置</el-button>
      </el-tab-pane>
      <el-tab-pane label="参与商品" name="products"/>
      <el-tab-pane label="邀请记录" name="invitations"/>
      <el-tab-pane label="返佣明细" name="commissions"/>
      <el-tab-pane label="提现记录" name="withdrawals"/>
      <el-tab-pane v-if="admin" label="退款记录" name="refunds"/>
    </el-tabs>
    <template v-if="tab !== 'settings'">
      <el-form inline @submit.prevent="search">
        <el-form-item><el-input v-model="query" clearable :placeholder="tab === 'refunds' ? '完整订单号' : '搜索邮箱、商品或订单号'" @clear="search"/></el-form-item>
        <el-form-item v-if="tab === 'commissions' || tab === 'withdrawals'"><el-select v-model="status" clearable placeholder="全部状态" style="width:160px"><el-option v-for="code in statusOptions" :key="code" :label="label(code)" :value="code"/></el-select></el-form-item>
        <el-form-item v-if="tab === 'commissions'"><el-date-picker v-model="dates" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期"/></el-form-item>
        <el-button native-type="submit">查询</el-button>
      </el-form>
      <el-space v-if="admin && tab === 'withdrawals'" wrap><span>本页已选 {{ selected.length }} 笔</span><el-button :disabled="!selected.length || busy" @click="review('APPROVED')">批量批准</el-button><el-button :disabled="!selected.length || busy" @click="review('REJECTED')">批量驳回</el-button></el-space>
      <PagedTable :key="tab" ref="table" v-loading="loading" :data="rows" list-id="referral-records" pagination="external" @selection-change="selected = $event">
        <el-table-column v-if="admin && tab === 'withdrawals'" type="selection" width="45" :selectable="(row: Row) => ['PENDING','APPROVED'].includes(row.status)"/>
        <template v-if="tab === 'products'">
          <el-table-column prop="name" label="商品" min-width="180"/>
          <el-table-column label="返佣规则" min-width="190"><template #default="{row}">{{ row.enabled ? offer(row) : '未参与活动' }}</template></el-table-column>
          <el-table-column v-if="admin" label="单份成本"><template #default="{row}">{{ money(row.cost_cents) }}</template></el-table-column>
          <el-table-column v-if="admin" label="操作"><template #default="{row}"><el-button link @click="editRule(row)">配置规则</el-button></template></el-table-column>
        </template>
        <template v-if="tab === 'invitations'">
          <el-table-column v-if="admin" prop="inviter_email" label="邀请人邮箱" min-width="200"/>
          <el-table-column prop="customer_email" label="受邀人邮箱" min-width="180"/>
          <el-table-column prop="created_at" label="注册时间（UTC）" min-width="170"/>
          <el-table-column prop="bound_at" label="绑定时间" min-width="170"/>
          <el-table-column prop="purchases" label="有效购买次数" width="120"/>
          <el-table-column label="注册来源"><template #default="{row}">{{ label(row.binding_source) }}</template></el-table-column>
          <el-table-column v-if="admin" label="推广资格"><template #default="{row}"><el-button link :disabled="busy" @click="toggleMember(row)">{{ row.status === 'ACTIVE' ? '暂停推广' : '恢复推广' }}</el-button></template></el-table-column>
        </template>
        <template v-if="tab === 'commissions'">
          <el-table-column v-if="admin" prop="inviter_email" label="邀请人" min-width="190"/>
          <el-table-column prop="order_no" label="订单号" min-width="200"/>
          <el-table-column prop="product_name" label="商品" min-width="150"/>
          <el-table-column prop="quantity" label="数量" width="70"/>
          <el-table-column label="佣金"><template #default="{row}">{{ money(row.commission_cents - row.reversed_cents) }}</template></el-table-column>
          <el-table-column label="已扣回"><template #default="{row}">{{ money(row.reversed_cents) }}</template></el-table-column>
          <el-table-column label="状态"><template #default="{row}">{{ label(row.status) }}</template></el-table-column>
          <el-table-column prop="available_at" label="预计到账（UTC）" min-width="175"/>
          <el-table-column v-if="admin" label="计算依据" min-width="230"><template #default="{row}">规则版本 {{ row.rule_version }} · 商品实付 {{ money(row.sale_cents) }} · 成本 {{ money(row.cost_cents) }}</template></el-table-column>
        </template>
        <template v-if="tab === 'withdrawals'">
          <el-table-column prop="id" label="申请编号" width="100"/>
          <el-table-column v-if="admin" prop="email" label="用户邮箱" min-width="190"/>
          <el-table-column label="金额"><template #default="{row}">{{ money(row.amount_cents) }}</template></el-table-column>
          <el-table-column label="收款方式"><template #default="{row}">{{ label(row.channel) }}</template></el-table-column>
          <el-table-column prop="masked_account" label="收款账号" min-width="150"/>
          <el-table-column label="状态"><template #default="{row}">{{ label(row.status) }}</template></el-table-column>
          <el-table-column prop="note" label="审核说明" min-width="180"/>
          <el-table-column prop="created_at" label="申请时间（UTC）" min-width="170"/>
          <el-table-column prop="paid_at" label="完成时间（UTC）" min-width="170"/>
          <el-table-column v-if="admin" label="核查提示" min-width="150"><template #default="{row}">{{ row.account_users > 1 ? '多个用户使用相同收款账号' : '无重复收款账号' }}</template></el-table-column>
          <el-table-column label="操作" min-width="180" fixed="right"><template #default="{row}">
            <el-button v-if="!admin && row.status === 'PENDING'" link :disabled="busy" @click="cancel(row)">取消申请</el-button>
            <el-button v-if="admin" link @click="inspectBalance(row)">核对余额</el-button>
            <el-button v-if="admin && row.status === 'APPROVED'" link :disabled="busy" @click="claim(row)">领取打款</el-button>
            <el-button v-if="admin && row.status === 'PAYING'" link @click="openPayout(row)">查看并登记</el-button>
          </template></el-table-column>
        </template>
        <template v-if="tab === 'refunds'">
          <el-table-column prop="order_no" label="订单号" min-width="200"/>
          <el-table-column label="退款金额"><template #default="{row}">{{ money(row.amount_cents) }}</template></el-table-column>
          <el-table-column label="商品退款"><template #default="{row}">{{ money(row.merchandise_cents) }}</template></el-table-column>
          <el-table-column prop="reference" label="退款凭证" min-width="180"/>
          <el-table-column prop="refunded_at" label="退款时间（UTC）" min-width="180"/>
        </template>
      </PagedTable>
      <ListPagination v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="loadRows"/>
    </template>
    <el-dialog v-model="ruleDialog" title="商品返佣规则" width="min(620px, 95vw)">
      <el-form label-position="top" @input="preview = null">
        <el-form-item label="参与活动"><el-switch v-model="rule.enabled" @change="preview = null"/></el-form-item>
        <el-form-item label="返佣方式"><el-select v-model="rule.mode" @change="preview = null"><el-option label="每份固定金额" value="FIXED"/><el-option label="商品实付百分比" value="PERCENT"/></el-select></el-form-item>
        <el-form-item v-if="rule.mode === 'FIXED'" label="每份返佣（元）"><el-input-number v-model="fixedYuan" :min="0" :precision="2"/></el-form-item>
        <el-form-item v-else label="返佣比例（%）"><el-input-number v-model="ratePercent" :min="0" :max="100" :precision="2"/></el-form-item>
        <el-form-item label="可靠的人民币单份成本（元）"><el-input-number v-model="costYuan" :min="0" :precision="2"/><p class="form-hint">填写每份商品的实际采购成本，不是对外售价。售价与成本相同时，毛利和返佣均为零。</p></el-form-item>
        <el-form-item label="AI订阅卡片返佣文案"><el-input v-model="rule.displayText" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="留空使用默认返佣说明"/><p class="form-hint">只修改当前服务的展示文案，不改变实际返佣计算。保存后卡片立即更新，已创建订单的返佣金额不变。</p></el-form-item>
        <el-form-item label="模拟购买数量"><el-input-number v-model="previewQuantity" :min="1" :max="10000" :precision="0"/></el-form-item>
        <el-form-item label="模拟优惠后商品总实付（人民币元）"><el-input-number v-model="previewSale" :min="0" :precision="2"/></el-form-item>
        <p class="form-hint">此处填写本次购买全部份数优惠后的商品总额，不含服务费和支付加价。外币商品请填写折算后的人民币金额。</p>
      </el-form>
      <p>最终佣金不超过商品毛利的 50%，向下取整到分。修改规则后只影响后续新订单。</p>
      <div v-if="preview" ref="previewResult">
        <el-descriptions :column="1" border class="rule-breakdown">
          <el-descriptions-item label="商品总实付">{{ money(preview.merchandiseCents) }}</el-descriptions-item>
          <el-descriptions-item label="总成本（单份成本 × 数量）">{{ money(preview.totalCostCents) }}</el-descriptions-item>
          <el-descriptions-item label="商品毛利（实付 − 成本）">{{ money(preview.grossCents) }}</el-descriptions-item>
          <el-descriptions-item label="按配置计算的返佣">{{ money(preview.configuredCommissionCents) }}</el-descriptions-item>
          <el-descriptions-item label="毛利的50%上限（向下取整到分）">{{ money(preview.profitCapCents) }}</el-descriptions-item>
        </el-descriptions>
        <el-alert :title="'本次模拟返佣 ' + money(preview.commissionCents) + '，毛利 ' + money(preview.grossCents)" :type="preview.commissionCents > 0 ? 'success' : 'warning'" :closable="false" :description="previewExplanation"/>
      </div>
      <template #footer><el-button :loading="busy" @click="previewRule">预览计算</el-button><el-button type="primary" :disabled="!preview" :loading="busy" @click="applyRule">应用已预览规则</el-button></template>
    </el-dialog>
    <el-dialog v-model="payoutDialog" title="人工打款" width="min(580px, 95vw)" @closed="payoutAccount = null">
      <p v-if="payoutAccount">{{ label(payoutAccount.channel) }} · {{ payoutAccount.account }} · {{ payoutAccount.name }}</p>
      <p>应打款 {{ money(payout?.amount_cents) }}。转账结果不确定时请保留打款中状态，核实后再登记。</p>
      <el-form label-position="top"><el-form-item label="渠道交易号"><el-input v-model="paymentReference" maxlength="160"/></el-form-item><el-form-item label="实际打款金额（元）"><el-input-number v-model="paymentAmount" :precision="2" :min="0.01"/></el-form-item><el-form-item label="实际打款时间"><el-date-picker v-model="paymentTime" type="datetime"/></el-form-item></el-form>
      <template #footer><el-button type="danger" :loading="busy" @click="failPayout">确认未转账并驳回</el-button><el-button type="primary" :loading="busy" @click="finishPayout">登记已打款</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import http, { getHttpErrorMessage } from '@/utils/http'
import { referralLabel as label, referralMoney as money, referralOffer as offer } from '@/utils/referral'

type Row = Record<string, any>
const props = withDefaults(defineProps<{ admin?: boolean }>(), { admin: false })
const admin = props.admin
const base = admin ? '/api/admin/api/referrals' : '/api/user/referrals'
const router = useRouter()
const summary = ref<Row>({}), savedAccount = ref<Row>({})
const settings = reactive({ enabled: false, freezeDays: 7, serviceNotice: '', version: 0 })
const minimumYuan = ref(20)
const tab = ref('products'), page = ref(1), size = ref(10), total = ref(0)
const query = ref(''), status = ref(''), dates = ref<string[]>([])
const rows = ref<Row[]>([]), selected = ref<Row[]>([])
const table = ref<InstanceType<typeof PagedTable>>()
const loading = ref(false), busy = ref(false), error = ref('')
let generation = 0
onUnmounted(() => { generation++ })
const account = reactive({ channel: 'ALIPAY', account: '', name: '' })
const withdrawAmount = ref(20)
let withdrawalKey = ''
watch(withdrawAmount, () => { withdrawalKey = '' })
const inviteLink = computed(() => location.origin + '/?auth=register&aff=' + encodeURIComponent(summary.value.profile?.invite_code || ''))
const metrics = admin ? [
  { key: 'invited', label: '邀请注册数' }, { key: 'salesCents', label: '有效商品实付' },
  { key: 'commissionCents', label: '有效佣金' }, { key: 'frozenCents', label: '待到账' },
  { key: 'availableCents', label: '可提现余额' }, { key: 'pendingPayoutCents', label: '待审核金额' },
  { key: 'approvedPayoutCents', label: '待打款金额' }, { key: 'payingCents', label: '打款中金额' }, { key: 'paidCents', label: '已打款金额' }
] : [
  { key: 'invited', label: '邀请人数' }, { key: 'earnedCents', label: '累计有效佣金' },
  { key: 'pendingCents', label: '待到账' }, { key: 'availableCents', label: '可提现' },
  { key: 'withdrawingCents', label: '提现处理中' }, { key: 'paidCents', label: '已提现' }
]
const statusOptions = computed(() => tab.value === 'withdrawals'
  ? ['PENDING','APPROVED','PAYING','PAID','REJECTED','CANCELLED']
  : ['AWAITING_PAYMENT','AWAITING_DELIVERY','FROZEN','AVAILABLE','REVERSED','VOID'])

const confirm = (message: string, title: string) => ElMessageBox.confirm(message, title, { confirmButtonText: '确定', cancelButtonText: '取消' })
async function run(action: () => Promise<void>) {
  if (busy.value) return
  busy.value = true
  try { await action() } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(getHttpErrorMessage(e, '操作失败，请刷新后重试'))
  } finally { busy.value = false }
}
async function loadSummary() {
  const { data } = await http.get(base)
  if (admin) {
    summary.value = data.overview
    Object.assign(settings, { enabled: data.settings.enabled, freezeDays: data.settings.freeze_days, serviceNotice: data.settings.service_notice, version: data.settings.version })
    minimumYuan.value = data.settings.minimum_cents / 100
  } else {
    summary.value = data
    savedAccount.value = (await http.get(base + '/account')).data
  }
}
async function loadRows() {
  const version = ++generation
  selected.value = []
  table.value?.clearSelection()
  if (tab.value === 'settings') return
  loading.value = true
  try {
    const { data } = await http.get(base + '/lists/' + tab.value, { params: { listPage: true, page: page.value, size: size.value, query: query.value, status: status.value, from: dates.value?.[0], to: dates.value?.[1] } })
    if (version !== generation) return
    rows.value = data.items
    total.value = data.total
    error.value = ''
  } catch (e) { if (version === generation) error.value = getHttpErrorMessage(e, '记录加载失败') }
  finally { if (version === generation) loading.value = false }
}
async function refresh() {
  try { await loadSummary(); await loadRows() } catch (e) { error.value = getHttpErrorMessage(e, '邀请信息加载失败') }
}
function search() { page.value = 1; void loadRows() }
function changeTab() { query.value = ''; status.value = ''; dates.value = []; search() }
function enroll() { return run(async () => { await http.post(base + '/enroll'); await refresh() }) }
async function copyInvite() { try { await navigator.clipboard.writeText(inviteLink.value); ElMessage.success('邀请链接已复制') } catch { ElMessage.info('请手动复制邀请链接') } }
function saveAccount() {
  return run(async () => {
    await confirm(account.name + ' · ' + account.account, '确认保存收款信息')
    await http.put(base + '/account', account)
    withdrawalKey = ''
    savedAccount.value = (await http.get(base + '/account')).data
    account.account = ''; account.name = ''
    ElMessage.success('收款信息已保存')
  })
}
function withdraw() {
  return run(async () => {
    await confirm(label(savedAccount.value.channel) + ' ' + savedAccount.value.masked_account + ' · ' + money(Math.round(withdrawAmount.value * 100)), '确认提现')
    if (!withdrawalKey) withdrawalKey = crypto.randomUUID()
    await http.post(base + '/withdrawals', { amountCents: Math.round(withdrawAmount.value * 100), requestKey: withdrawalKey, accountToken: savedAccount.value.accountToken })
    withdrawalKey = ''; ElMessage.success('提现申请已提交'); await refresh()
  })
}
function cancel(row: Row) { return run(async () => { await confirm('取消后金额将退回可提现余额', '取消提现'); await http.post(base + '/withdrawals/' + row.id + '/cancel'); await refresh() }) }
function saveSettings() { return run(async () => { await http.put(base + '/settings', { ...settings, minimumCents: Math.round(minimumYuan.value * 100) }); await refresh(); ElMessage.success('设置已保存') }) }
function toggleMember(row: Row) { return run(async () => { await confirm(row.inviter_email, '确认修改推广资格'); await http.put(base + '/members/' + row.agent_user_id, { status: row.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE' }); await loadRows() }) }
function review(action: string) {
  const ids = selected.value.map(row => row.id)
  return run(async () => {
    let note = ''
    if (action === 'REJECTED') note = (await ElMessageBox.prompt('请填写驳回原因', '批量驳回', { confirmButtonText: '确定', cancelButtonText: '取消', inputValidator: value => !!value?.trim() || '请填写原因' })).value
    else await confirm('所选 ' + ids.length + ' 笔申请将进入待打款', '批量批准')
    await http.post(base + '/withdrawals/review', { ids, action, note }); await refresh()
  })
}
function claim(row: Row) { return run(async () => { await confirm('领取后由你核实收款信息并处理打款', '领取打款'); await http.post(base + '/withdrawals/' + row.id + '/claim'); await refresh() }) }
function inspectBalance(row: Row) { return run(async () => { const { data } = await http.get(base + '/users/' + row.user_id); await ElMessageBox.alert('可提现 ' + money(data.availableCents) + ' · 提现处理中 ' + money(data.withdrawingCents) + ' · 待抵扣 ' + money(data.debtCents), '账户核对') }) }

const ruleDialog = ref(false), ruleRow = ref<Row>({})
const rule = reactive({ enabled: false, mode: 'FIXED', displayText: '' })
const fixedYuan = ref(5), ratePercent = ref(5), costYuan = ref(0), previewSale = ref<number>(), previewQuantity = ref(1)
const preview = ref<Row | null>(null)
const previewResult = ref<HTMLElement>()
let ruleRevision = 0
watch([() => rule.enabled, () => rule.mode, () => rule.displayText, fixedYuan, ratePercent, costYuan, previewSale, previewQuantity], () => { ruleRevision++; preview.value = null }, { flush: 'sync' })
const previewExplanation = computed(() => {
  if (!preview.value) return ''
  if (!preview.value.enabled) return '当前商品未参与活动，实际返佣为零。'
  if (preview.value.grossCents <= 0) return '商品实付未超过总成本，没有可分配毛利，因此返佣为零。请核对成本与模拟实付金额。'
  if (preview.value.configuredCommissionCents > preview.value.profitCapCents) return '配置金额超过毛利的50%上限，本次按上限计算。'
  return '配置金额未超过毛利上限，本次按配置计算。'
})
function editRule(row: Row) {
  ruleRevision++
  ruleRow.value = row
  Object.assign(rule, { enabled: !!row.enabled, mode: row.mode || 'FIXED', displayText: row.display_text || '' })
  fixedYuan.value = (row.fixed_cents ?? 500) / 100; ratePercent.value = (row.rate_bps ?? 500) / 100; costYuan.value = (row.cost_cents || 0) / 100
  previewSale.value = row.currency === 'CNY' ? (row.price_cents || 0) / 100 : undefined
  previewQuantity.value = 1; preview.value = null; ruleDialog.value = true
}
function previewRule() { return run(async () => {
  if (previewSale.value == null || !Number.isFinite(previewSale.value)) throw new Error('请填写模拟优惠后商品总实付（人民币元）')
  const revision = ruleRevision
  const { data } = await http.post(base + '/products/' + ruleRow.value.id + '/preview', {
  version: ruleRow.value.version, rule: { ...rule, fixedCents: Math.round(fixedYuan.value * 100), rateBps: Math.round(ratePercent.value * 100), costCents: Math.round(costYuan.value * 100) },
  merchandiseCents: Math.round(previewSale.value * 100), quantity: previewQuantity.value
  })
  if (revision === ruleRevision && ruleDialog.value) {
    preview.value = data
    await nextTick()
    previewResult.value?.scrollIntoView({ block: 'nearest' })
  }
}) }
function applyRule() { return run(async () => { await http.post(base + '/products/' + ruleRow.value.id + '/apply', { token: preview.value?.token }); ruleDialog.value = false; await refresh() }) }

const payoutDialog = ref(false), payout = ref<Row | null>(null), payoutAccount = ref<Row | null>(null)
const paymentReference = ref(''), paymentAmount = ref(0), paymentTime = ref<Date>(new Date())
function openPayout(row: Row) { return run(async () => {
  payoutAccount.value = (await http.post(base + '/withdrawals/' + row.id + '/reveal')).data
  payout.value = row; paymentAmount.value = row.amount_cents / 100; paymentReference.value = ''; paymentTime.value = new Date(); payoutDialog.value = true
}) }
function finishPayout() { return run(async () => {
  await confirm('请确认收款人已收到转账，渠道交易号填写准确', '登记已打款')
  await http.post(base + '/withdrawals/' + payout.value?.id + '/paid', { amountCents: Math.round(paymentAmount.value * 100), reference: paymentReference.value, paidAt: paymentTime.value.toISOString().slice(0,19) })
  payoutDialog.value = false; await refresh()
}) }
function failPayout() { return run(async () => {
  const { value } = await ElMessageBox.prompt('仅在已确认转账未发生时操作。请填写核实结果', '确认未转账并驳回', { confirmButtonText: '确定', cancelButtonText: '取消', inputValidator: value => !!value?.trim() || '请填写核实结果' })
  await http.post(base + '/withdrawals/' + payout.value?.id + '/failed', { note: value }); payoutDialog.value = false; await refresh()
}) }
onMounted(refresh)
</script>

<style scoped>
.rule-breakdown { margin-bottom: 16px; }
.referral-console{grid-template-columns:minmax(0,1fr);width:100%;box-sizing:border-box}.referral-console>*{min-width:0}.referral-console :deep(.el-date-editor){max-width:100%}.referral-console h2{color:var(--lnx-text,var(--lnx-text))!important}
.referral-console{display:grid;gap:20px;max-width:1400px;margin:0 auto;padding:24px;min-width:0}.referral-heading{display:flex;justify-content:space-between;align-items:center;gap:16px}.referral-console h1,.referral-console h2{margin:0 0 12px}.referral-console p{color:var(--lnx-text-muted);line-height:1.7}.referral-metrics{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.referral-metrics article{padding:20px;border:1px solid var(--lnx-line);border-radius:12px;background:white}.referral-metrics span{display:block;color:var(--lnx-text-muted)}.referral-metrics strong{display:block;font-size:24px;margin-top:10px}.referral-form{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}.invite-input{width:min(460px,65vw)}@media(max-width:640px){.referral-console{padding:12px}.referral-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.referral-form{grid-template-columns:1fr}.referral-heading{align-items:flex-start;flex-direction:column}}
</style>
