<template>
  <section class="organization-page">
    <div class="page-heading">
      <div><p class="eyebrow">ENTERPRISE</p><h1>企业账户</h1><p>管理成员、独立钱包额度和模型用量。</p></div>
      <el-button type="primary" @click="createVisible = true">创建公司</el-button>
    </div>

    <el-card v-loading="loading" shadow="never">
      <el-tabs v-model="selectedId" @tab-change="loadOrganization">
        <el-tab-pane v-for="org in organizations" :key="org.id" :name="String(org.id)" :label="`${org.name} · ${org.role}`" />
      </el-tabs>
      <el-empty v-if="!organizations.length" description="还没有企业账户" />
      <template v-else>
        <div class="toolbar">
          <el-button v-if="canManage" @click="inviteVisible = true">邀请成员</el-button>
          <el-button v-if="isOwner" @click="allocation(false)">分配额度</el-button>
          <el-button v-if="isOwner" @click="allocation(true)">收回额度</el-button>
          <el-button @click="loadOrganization">刷新</el-button>
        </div>
        <el-table :data="members" stripe>
          <el-table-column prop="username" label="成员" min-width="150" />
          <el-table-column prop="email" label="邮箱" min-width="190" />
          <el-table-column prop="role" label="角色" width="120" />
          <el-table-column prop="balance" label="可用额度" width="130" />
          <el-table-column prop="heldAmount" label="预占" width="110" />
        </el-table>
      </template>
    </el-card>

    <el-card v-if="organizations.length" shadow="never" class="usage-card">
      <template #header><strong>模型用量明细</strong></template>
      <el-table :data="usage" max-height="420">
        <el-table-column prop="username" label="成员" min-width="120" />
        <el-table-column prop="tokenName" label="Key" min-width="140" />
        <el-table-column prop="sourceCode" label="来源" width="120" />
        <el-table-column prop="model" label="模型" min-width="170" />
        <el-table-column prop="inputTokens" label="输入" width="100" />
        <el-table-column prop="outputTokens" label="输出" width="100" />
        <el-table-column prop="cacheReadTokens" label="缓存命中" width="110" />
        <el-table-column prop="cacheWriteTokens" label="缓存写入" width="110" />
        <el-table-column prop="cacheMissTokens" label="缓存未命中" width="120" />
        <el-table-column prop="requestCount" label="请求数" width="90" />
        <el-table-column prop="successRate" label="成功率" width="100" />
        <el-table-column prop="saleAmount" label="费用" width="110" />
        <el-table-column prop="grossProfit" label="毛利" width="110" />
      </el-table>
    </el-card>

    <el-dialog v-model="createVisible" title="创建公司" width="420px">
      <el-input v-model="companyName" maxlength="100" placeholder="公司名称" />
      <template #footer><el-button @click="createVisible=false">取消</el-button><el-button type="primary" @click="createCompany">创建</el-button></template>
    </el-dialog>
    <el-dialog v-model="inviteVisible" title="邀请成员" width="460px">
      <el-form label-width="80px">
        <el-form-item label="邮箱"><el-input v-model="inviteForm.email" /></el-form-item>
        <el-form-item label="角色"><el-select v-model="inviteForm.role"><el-option v-for="r in roles" :key="r" :value="r" :label="r" /></el-select></el-form-item>
      </el-form>
      <el-alert v-if="inviteToken" type="success" :closable="false" :title="`一次性邀请令牌：${inviteToken}`" />
      <template #footer><el-button @click="inviteVisible=false">关闭</el-button><el-button type="primary" @click="invite">生成邀请</el-button></template>
    </el-dialog>
    <el-dialog v-model="allocationVisible" :title="reclaiming ? '收回额度' : '分配额度'" width="440px">
      <el-form label-width="90px">
        <el-form-item label="成员"><el-select v-model="allocationForm.memberId"><el-option v-for="m in allocatableMembers" :key="m.memberId" :value="m.memberId" :label="m.username" /></el-select></el-form-item>
        <el-form-item label="额度"><el-input-number v-model="allocationForm.amount" :min="1" :precision="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="allocationVisible=false">取消</el-button><el-button type="primary" @click="submitAllocation">确认</el-button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

type Organization = { id: number; name: string; role: string }
type Member = { memberId: number; username: string; email?: string; role: string; balance: number; heldAmount: number }
const organizations = ref<Organization[]>([]), members = ref<Member[]>([]), usage = ref<Record<string, unknown>[]>([])
const selectedId = ref(''), loading = ref(false), createVisible = ref(false), inviteVisible = ref(false), allocationVisible = ref(false)
const companyName = ref(''), inviteToken = ref(''), reclaiming = ref(false)
const roles = ['ORG_ADMIN', 'BILLING', 'MEMBER']
const inviteForm = ref({ email: '', role: 'MEMBER' })
const allocationForm = ref({ memberId: undefined as number | undefined, amount: 1 })
const selected = computed(() => organizations.value.find(item => String(item.id) === selectedId.value))
const isOwner = computed(() => selected.value?.role === 'OWNER')
const canManage = computed(() => ['OWNER', 'ORG_ADMIN'].includes(selected.value?.role || ''))
const allocatableMembers = computed(() => members.value.filter(item => item.role !== 'OWNER'))
const idempotencyKey = () => `web-${crypto.randomUUID()}`

async function loadOrganizations() {
  loading.value = true
  try {
    organizations.value = (await http.get('/organizations')).data.map((item: Record<string, unknown>) => ({
      id: Number(item.id), name: String(item.name), role: String(item.member_role ?? item.role)
    }))
    if (!selectedId.value && organizations.value.length) selectedId.value = String(organizations.value[0].id)
    await loadOrganization()
  } catch (error) { ElMessage.error(getHttpErrorMessage(error)) } finally { loading.value = false }
}
async function loadOrganization() {
  if (!selectedId.value) return
  const [memberResponse, usageResponse] = await Promise.all([
    http.get(`/organizations/${selectedId.value}/members`), http.get(`/organizations/${selectedId.value}/usage`)
  ])
  members.value = memberResponse.data.map((item: Record<string, unknown>) => ({
    memberId: Number(item.user_id ?? item.userId), username: String(item.username), email: item.email ? String(item.email) : undefined,
    role: String(item.member_role ?? item.role), balance: Number(item.balance ?? 0), heldAmount: Number(item.held_balance ?? item.heldAmount ?? 0)
  }))
  usage.value = usageResponse.data.map((item: Record<string, unknown>) => ({
    username: item.username, tokenName: item.token_name ?? item.token_id, sourceCode: item.source_code, model: item.model,
    inputTokens: item.input_tokens, outputTokens: item.output_tokens, cacheReadTokens: item.cache_hit_tokens,
    cacheWriteTokens: item.cache_write_tokens, cacheMissTokens: item.cache_miss_tokens, requestCount: item.request_count,
    successRate: Number(item.request_count) ? `${(Number(item.success_count) * 100 / Number(item.request_count)).toFixed(1)}%` : '0%',
    saleAmount: item.sale_amount, grossProfit: item.gross_profit
  }))
}
async function createCompany() {
  try { await http.post('/organizations', { name: companyName.value }); createVisible.value=false; companyName.value=''; await loadOrganizations(); ElMessage.success('公司已创建') }
  catch (error) { ElMessage.error(getHttpErrorMessage(error)) }
}
async function invite() {
  try {
    const response = await http.post(`/organizations/${selectedId.value}/invitations`, inviteForm.value, { headers: { 'Idempotency-Key': idempotencyKey() } })
    inviteToken.value = response.data.invitationToken
    ElMessage.success('邀请已创建，令牌仅展示一次')
  } catch (error) { ElMessage.error(getHttpErrorMessage(error)) }
}
function allocation(reclaim: boolean) { reclaiming.value = reclaim; allocationForm.value = { memberId: allocatableMembers.value[0]?.memberId, amount: 1 }; allocationVisible.value=true }
async function submitAllocation() {
  try {
    const suffix = reclaiming.value ? '/allocations/reclaim' : '/allocations'
    await http.post(`/organizations/${selectedId.value}${suffix}`, { userId: allocationForm.value.memberId, amount: allocationForm.value.amount }, { headers: { 'Idempotency-Key': idempotencyKey() } })
    allocationVisible.value=false; await loadOrganization(); ElMessage.success(reclaiming.value ? '额度已收回' : '额度已分配')
  } catch (error) { ElMessage.error(getHttpErrorMessage(error)) }
}
onMounted(loadOrganizations)
</script>

<style scoped>
.organization-page{max-width:1280px;margin:0 auto;padding:32px 24px}.page-heading{display:flex;justify-content:space-between;align-items:center;margin-bottom:20px}.page-heading h1{margin:2px 0 6px}.page-heading p{margin:0;color:#64748b}.eyebrow{font-size:12px!important;color:#2563eb!important;letter-spacing:.16em}.toolbar{display:flex;gap:10px;margin:4px 0 18px}.usage-card{margin-top:20px}
</style>
