<template>
  <main class="agent-page">
    <header class="agent-header">
      <button class="site-brand" @click="router.push('/console')"><img class="site-brand-mark brand-image" src="/brand/linknux-mark-192.png" alt="" /><span>Linknux 代理中心</span></button>
      <el-button @click="router.push('/console')">返回控制台</el-button>
    </header>
    <section class="agent-content">
      <el-alert v-if="summary.feature && !summary.feature.enabled" type="info" :closable="false" title="代理分销正在分阶段开放" description="数据结构和结算链路已部署，管理员核对完整结算周期后会开放申请。" />
      <template v-if="summary.profile?.id">
        <div class="agent-metrics">
          <article><span>代理状态</span><strong>{{ statusLabel(summary.profile.status) }}</strong></article>
          <article><span>客户数量</span><strong>{{ summary.customerCount || 0 }}</strong></article>
          <article><span>冻结佣金</span><strong>{{ money(summary.frozenCommission) }}</strong></article>
          <article><span>可用佣金</span><strong>{{ money(summary.availableCommission) }}</strong></article>
        </div>
        <section class="agent-card">
          <h2>邀请链接</h2><p>邀请关系只绑定一级，注册后普通用户不能自行改绑。</p>
          <div class="invite-row"><el-input :model-value="inviteLink" readonly /><el-button type="primary" @click="copyInvite">复制</el-button></div>
          <el-descriptions :column="3" border><el-descriptions-item label="等级">{{ summary.profile.display_name }}</el-descriptions-item><el-descriptions-item label="佣金率">{{ bps(summary.profile.commission_bps) }}</el-descriptions-item><el-descriptions-item label="客户返利上限">{{ bps(summary.profile.max_customer_rebate_bps) }}</el-descriptions-item></el-descriptions>
        </section>
        <section class="agent-card">
          <h2>佣金操作</h2><p>消费佣金冻结 {{ summary.feature.freezeDays }} 天；提现最低 {{ money(summary.feature.minimumWithdrawal) }}。</p>
          <el-form inline><el-form-item label="金额（内部金额单位）"><el-input-number v-model="amount" :min="1" /></el-form-item><el-form-item><el-button @click="transfer">转入平台余额</el-button><el-button type="primary" @click="withdraw">申请人工提现</el-button></el-form-item></el-form>
        </section>
        <section class="agent-card"><h2>提现记录</h2><el-table :data="summary.withdrawals || []"><el-table-column prop="request_no" label="单号" min-width="190" /><el-table-column prop="amount" label="金额"><template #default="scope">{{ money(scope.row.amount) }}</template></el-table-column><el-table-column prop="status" label="状态" /><el-table-column prop="created_at" label="申请时间" min-width="170" /></el-table></section>
      </template>
      <section v-else class="agent-card application-card"><h1>成为 Linknux 代理伙伴</h1><p>按客户真实消费毛利结算，不对充值重复计佣。佣金账本全程幂等、可审计。</p><el-form label-position="top"><el-form-item label="给受邀客户的返利"><el-select v-model="rebateBps"><el-option label="不返利" :value="0"/><el-option label="1%" :value="100"/><el-option label="2%" :value="200"/></el-select></el-form-item><el-button type="primary" :disabled="!summary.feature?.enabled" @click="apply">提交申请</el-button></el-form></section>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

const router=useRouter(),summary=reactive<any>({feature:{},profile:{},withdrawals:[]}),rebateBps=ref(0),amount=ref(0)
const inviteLink=computed(()=>`${location.origin}/?auth=register&aff=${encodeURIComponent(summary.profile?.invite_code||'')}`)
async function load(){try{Object.assign(summary,(await http.get('/api/user/agent')).data||{})}catch(e){ElMessage.error(getHttpErrorMessage(e,'代理数据加载失败'))}}
async function apply(){try{await http.post('/api/user/agent/apply',{customerRebateBps:rebateBps.value});ElMessage.success('申请已提交');await load()}catch(e){ElMessage.error(getHttpErrorMessage(e,'申请提交失败'))}}
async function transfer(){try{await http.post('/api/user/agent/transfer',{amount:amount.value,eventKey:crypto.randomUUID()});ElMessage.success('已转入余额');await load()}catch(e){ElMessage.error(getHttpErrorMessage(e,'佣金转入失败'))}}
async function withdraw(){try{await http.post('/api/user/agent/withdrawals',{amount:amount.value,destinationType:'MANUAL'});ElMessage.success('提现申请已提交');await load()}catch(e){ElMessage.error(getHttpErrorMessage(e,'提现申请失败'))}}
async function copyInvite(){await navigator.clipboard.writeText(inviteLink.value);ElMessage.success('邀请链接已复制')}
const money=(v:unknown)=>`¥${(Number(v||0)/Number(summary.feature?.amountScale||10000)).toFixed(2)}`
const bps=(v:unknown)=>`${(Number(v||0)/100).toFixed(2)}%`
const statusLabel=(v:string)=>({PENDING:'待审核',ACTIVE:'合作中',SUSPENDED:'已暂停',REJECTED:'未通过'} as any)[v]||v
onMounted(load)
</script>

<style scoped>
.agent-page{min-height:100vh;min-height:100svh;background:#f4f7fb}.agent-header{position:sticky;top:0;z-index:3;display:flex;align-items:center;justify-content:space-between;padding:14px max(20px,5vw);border-bottom:1px solid #d7e4f7;background:rgba(255,255,255,.94);backdrop-filter:blur(14px)}.agent-content{display:grid;gap:18px;width:min(1120px,calc(100% - 32px));margin:28px auto 60px}.agent-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.agent-metrics article,.agent-card{padding:22px;border:1px solid #d7e4f7;border-radius:14px;background:#fff;box-shadow:0 12px 32px rgba(23,105,255,.06)}.agent-metrics span{display:block;color:#647792;font-size:13px}.agent-metrics strong{display:block;margin-top:8px;font-size:24px}.agent-card h1,.agent-card h2{margin-top:0}.agent-card p{color:#647792}.invite-row{display:grid;grid-template-columns:1fr auto;gap:10px;margin:16px 0}.application-card{max-width:620px;margin:50px auto;width:100%}@media(max-width:760px){.agent-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.agent-header{position:static}.invite-row{grid-template-columns:1fr}}
</style>
