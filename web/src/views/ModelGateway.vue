<template>
  <div class="model-gateway-page">
    <section class="gateway-heading">
      <div>
        <p class="gateway-eyebrow">MODEL GATEWAY</p>
        <h2>模型网关统一管理</h2>
        <p>集中维护供应商渠道、公开模型路由、价格档位、健康状态与连通性测试。</p>
      </div>
      <div class="gateway-heading-actions">
        <el-button :loading="loading" @click="loadGatewayData">刷新状态</el-button>
        <el-button type="primary" @click="router.push('/market')">查看模型广场</el-button>
      </div>
    </section>

    <el-tabs v-model="activeTab" class="gateway-tabs" @tab-change="syncTab">
      <el-tab-pane label="总览" name="overview">
        <section class="gateway-metrics" v-loading="loading">
          <button type="button" @click="selectTab('channels')">
            <span>渠道总数</span><strong>{{ channels.length }}</strong><small>{{ enabledChannels }} 个已启用</small>
          </button>
          <button type="button" @click="selectTab('health')">
            <span>健康渠道</span><strong>{{ healthyChannels }}</strong><small>{{ unhealthyChannels }} 个需要处理</small>
          </button>
          <button type="button" @click="selectTab('models')">
            <span>公开模型</span><strong>{{ publicModelCount }}</strong><small>{{ enabledRoutes }} 条路由已发布</small>
          </button>
          <button type="button" @click="selectTab('catalog')">
            <span>前台可调用</span><strong>{{ callableModelCount }}</strong><small>通过统一 Base URL 调用</small>
          </button>
        </section>

        <section class="gateway-overview-grid">
          <article class="gateway-panel">
            <div class="gateway-panel-head">
              <div><h3>渠道健康</h3><p>当前所有上游渠道的运行状态</p></div>
              <el-button link type="primary" @click="selectTab('health')">查看测试记录</el-button>
            </div>
            <el-table :data="channels" size="small" empty-text="暂无渠道">
              <el-table-column prop="name" label="渠道" min-width="150" />
              <el-table-column prop="type" label="类型" width="110" />
              <el-table-column label="状态" width="110">
                <template #default="{ row }"><el-tag :type="healthTag(row.healthStatus)">{{ row.healthStatus || 'UNTESTED' }}</el-tag></template>
              </el-table-column>
              <el-table-column label="启用" width="80">
                <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag></template>
              </el-table-column>
            </el-table>
          </article>

          <article class="gateway-panel">
            <div class="gateway-panel-head">
              <div><h3>待处理问题</h3><p>影响模型发布或调用的配置</p></div>
              <el-tag :type="issues.length ? 'warning' : 'success'">{{ issues.length }} 项</el-tag>
            </div>
            <el-empty v-if="!issues.length" description="模型网关状态正常" :image-size="72" />
            <div v-else class="gateway-issues">
              <button v-for="issue in issues" :key="issue.key" type="button" @click="selectTab(issue.tab)">
                <span :class="`severity-${issue.severity}`"></span>
                <div><strong>{{ issue.title }}</strong><small>{{ issue.message }}</small></div>
              </button>
            </div>
          </article>
        </section>
      </el-tab-pane>

      <el-tab-pane label="渠道管理" name="channels">
        <AdminConsole module="channels" />
      </el-tab-pane>

      <el-tab-pane label="模型与路由" name="models">
        <AdminConsole module="models" />
      </el-tab-pane>

      <el-tab-pane label="公开上游映射" name="presentation">
        <section class="gateway-panel">
          <div class="gateway-panel-head"><div><h3>公开上游映射</h3><p>用户侧只能看到此处的公开名称；未配置时统一显示“平台智能路由”。</p></div></div>
          <el-table :data="displayMappings" border empty-text="暂无渠道">
            <el-table-column prop="internalName" label="内部渠道" min-width="160"/><el-table-column prop="sourceCode" label="内部代号" width="120"/>
            <el-table-column label="公开代号" min-width="150"><template #default="{row}"><el-input v-model="row.publicCode"/></template></el-table-column>
            <el-table-column label="公开显示名" min-width="170"><template #default="{row}"><el-input v-model="row.publicName"/></template></el-table-column>
            <el-table-column label="徽标" width="130"><template #default="{row}"><el-input v-model="row.badgeText"/></template></el-table-column>
            <el-table-column label="颜色" width="135"><template #default="{row}"><el-color-picker v-model="row.badgeColor"/></template></el-table-column>
            <el-table-column label="排序" width="110"><template #default="{row}"><el-input-number v-model="row.sortOrder" :min="0" :max="9999" controls-position="right"/></template></el-table-column>
            <el-table-column label="启用" width="80"><template #default="{row}"><el-switch v-model="row.enabled"/></template></el-table-column>
            <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button link type="primary" @click="saveDisplayMapping(row)">保存</el-button></template></el-table-column>
          </el-table>
        </section>
        <section class="gateway-panel context-policy-panel">
          <div class="gateway-panel-head"><div><h3>长上下文销售策略</h3><p>实际输入 Token 超过阈值后，输入和输出售价固定为基础价的 2 倍，缓存价格不变。</p></div></div>
          <div class="context-policy-form"><el-select v-model="contextModel" filterable placeholder="选择公开模型" @change="loadContextPolicy"><el-option v-for="name in publicModelNames" :key="name" :label="name" :value="name"/></el-select><el-switch v-model="contextPolicy.enabled" active-text="启用 2 倍价格"/><el-input-number v-model="contextPolicy.thresholdTokens" :min="1" :max="100000000"/><el-input v-model="contextPolicy.verificationNote" maxlength="500" placeholder="核验说明"/><el-button type="primary" :disabled="!contextModel" @click="saveContextPolicy">保存策略</el-button></div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="供应商目录" name="catalog">
        <section class="gateway-panel">
          <div class="gateway-panel-head">
            <div><h3>全量供应商模型目录</h3><p>目录可展示全部模型；只有 AVAILABLE 状态会进入用户 Key 和 /v1/models。</p></div>
            <div class="gateway-heading-actions">
              <el-button :loading="catalogAction !== ''" @click="syncCatalog('haoee')">同步好易智算</el-button>
              <el-button :loading="catalogAction !== ''" @click="syncCatalog('nvidia')">同步 NVIDIA</el-button>
              <el-button type="primary" :loading="catalogAction !== ''" @click="verifyCatalogSource">验证低成本模型</el-button>
              <el-button type="danger" plain :loading="catalogAction !== ''" @click="purgeFailedModels">删除验证失败</el-button>
              <el-button @click="openExclusions">排除清单</el-button>
            </div>
          </div>
          <div class="catalog-filters">
            <el-select v-model="catalogSource" clearable placeholder="全部上游"><el-option label="好易智算" value="haoee" /><el-option label="NVIDIA" value="nvidia" /></el-select>
            <el-select v-model="catalogStatus" clearable placeholder="全部状态">
              <el-option v-for="status in catalogStatuses" :key="status" :label="status" :value="status" />
            </el-select>
            <el-input v-model="catalogQuery" clearable placeholder="搜索模型、厂家或功能" />
          </div>
          <div class="gateway-table-shell catalog-table-shell">
          <el-table :data="filteredCatalog" v-loading="loading" max-height="calc(100vh - 330px)" scrollbar-always-on empty-text="暂无目录模型">
            <el-table-column prop="sourceName" label="上游" width="130" fixed="left" />
            <el-table-column prop="publicModelName" label="模型" min-width="230" show-overflow-tooltip fixed="left" />
            <el-table-column prop="vendor" label="厂家" width="130" />
            <el-table-column prop="capability" label="功能" width="110" />
            <el-table-column prop="protocols" label="协议" min-width="160" show-overflow-tooltip />
            <el-table-column label="验证状态" width="135">
              <template #default="{ row }"><el-tag :type="catalogStatusTag(row.verificationStatus)">{{ row.verificationStatus }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="verificationMessage" label="验证信息" min-width="220" show-overflow-tooltip />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.verificationStatus === 'VERIFYING'" @click="verifyCatalogModel(row)">验证</el-button>
                <el-button link @click="showVerificationHistory(row)">记录</el-button>
              </template>
            </el-table-column>
          </el-table>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="健康与测试" name="health">
        <section class="gateway-panel gateway-health-panel">
          <div class="gateway-panel-head">
            <div><h3>渠道连通性</h3><p>使用内置安全探针测试渠道，不执行自定义代码。</p></div>
          </div>
          <el-table :data="channels" v-loading="loading" empty-text="暂无渠道">
            <el-table-column prop="name" label="渠道" min-width="160" />
            <el-table-column prop="type" label="类型" width="120" />
            <el-table-column prop="baseUrl" label="Base URL" min-width="220" show-overflow-tooltip />
            <el-table-column label="健康状态" width="130">
              <template #default="{ row }"><el-tag :type="healthTag(row.healthStatus)">{{ row.healthStatus || 'UNTESTED' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="120" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :loading="testingChannelId === row.id" @click="testChannel(row)">立即测试</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section class="gateway-panel gateway-log-panel">
          <div class="gateway-panel-head">
            <div><h3>最近模型测试</h3><p>展示最近 500 条渠道模型探针记录。</p></div>
            <el-tag>{{ testLogs.length }} 条</el-tag>
          </div>
          <el-table :data="testLogs" size="small" :max-height="520" empty-text="暂无测试记录">
            <el-table-column prop="tested_at" label="时间" min-width="170" />
            <el-table-column prop="channel_name" label="渠道" min-width="140" />
            <el-table-column prop="model_name" label="模型" min-width="180" show-overflow-tooltip />
            <el-table-column label="结果" width="100">
              <template #default="{ row }"><el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="latency_ms" label="耗时(ms)" width="110" />
            <el-table-column prop="error_message" label="错误" min-width="240" show-overflow-tooltip />
          </el-table>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-drawer v-model="exclusionVisible" title="已永久排除的模型" size="min(720px, 92vw)">
      <p class="drawer-tip">这些模型不会在下次同步时重新出现。恢复后需重新同步，再由管理员手动验证。</p>
      <el-table :data="exclusions" v-loading="exclusionsLoading" empty-text="暂无排除记录">
        <el-table-column prop="source_code" label="上游" width="110" />
        <el-table-column prop="public_model_name" label="模型" min-width="220" show-overflow-tooltip />
        <el-table-column prop="reason" label="排除原因" min-width="220" show-overflow-tooltip />
        <el-table-column prop="excluded_at" label="排除时间" width="175" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" @click="restoreExclusion(row)">恢复</el-button></template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import AdminConsole from '@/views/AdminConsole.vue'
import http from '@/utils/http'

type GatewayTab = 'overview' | 'channels' | 'models' | 'presentation' | 'catalog' | 'health'
type ChannelRow = { id: number; name: string; type: string; baseUrl?: string; enabled?: boolean; healthStatus?: string }
type ModelRow = { id: number; publicModelName?: string; enabled?: boolean; callable?: boolean; availabilityMessage?: string }
type TestLog = Record<string, any>
type CatalogRow = { id: number; sourceCode: string; sourceName: string; publicModelName: string; vendor: string; capability: string; protocols: string; verificationStatus: string; verificationMessage?: string }
type ExclusionRow = { id: number; source_code: string; upstream_model_name: string; public_model_name: string; reason?: string; excluded_at?: string }

const route = useRoute()
const router = useRouter()
const tabs: GatewayTab[] = ['overview', 'channels', 'models', 'presentation', 'catalog', 'health']
const routeTab = (): GatewayTab => tabs.includes(route.query.tab as GatewayTab) ? route.query.tab as GatewayTab : 'overview'
const activeTab = ref<GatewayTab>(routeTab())
const loading = ref(false)
const testingChannelId = ref<number | null>(null)
const channels = ref<ChannelRow[]>([])
const models = ref<ModelRow[]>([])
const testLogs = ref<TestLog[]>([])
const providerCatalog = ref<CatalogRow[]>([])
const catalogSource = ref('')
const catalogStatus = ref('')
const catalogQuery = ref('')
const catalogAction = ref('')
const exclusionVisible = ref(false)
const exclusionsLoading = ref(false)
const exclusions = ref<ExclusionRow[]>([])
const displayMappings=ref<any[]>([]),contextModel=ref(''),contextPolicy=ref<any>({enabled:false,thresholdTokens:128000,verificationNote:''})
const publicModelNames=computed(()=>[...new Set(models.value.map(item=>item.publicModelName).filter((item):item is string=>Boolean(item)))].sort())
const catalogStatuses = ['DISCOVERED', 'VERIFYING', 'AVAILABLE', 'FAILED', 'UNSUPPORTED', 'RETIRED']
const filteredCatalog = computed(() => {
  const query = catalogQuery.value.trim().toLowerCase()
  return providerCatalog.value.filter(row => (!catalogSource.value || row.sourceCode === catalogSource.value)
    && (!catalogStatus.value || row.verificationStatus === catalogStatus.value)
    && (!query || `${row.publicModelName} ${row.vendor} ${row.capability}`.toLowerCase().includes(query)))
})

const enabledChannels = computed(() => channels.value.filter(channel => channel.enabled).length)
const healthyChannels = computed(() => channels.value.filter(channel => channel.enabled && channel.healthStatus === 'HEALTHY').length)
const unhealthyChannels = computed(() => channels.value.filter(channel => channel.enabled && channel.healthStatus !== 'HEALTHY').length)
const enabledRoutes = computed(() => models.value.filter(model => model.enabled).length)
const publicModelCount = computed(() => new Set(models.value.map(model => model.publicModelName).filter(Boolean)).size)
const callableModelCount = computed(() => new Set(models.value.filter(model => model.callable).map(model => model.publicModelName).filter(Boolean)).size)
const issues = computed(() => {
  const rows: Array<{ key: string; title: string; message: string; severity: 'warning' | 'danger'; tab: GatewayTab }> = []
  channels.value.filter(channel => channel.enabled && channel.healthStatus !== 'HEALTHY').forEach(channel => rows.push({
    key: `channel-${channel.id}`,
    title: `${channel.name} 状态异常`,
    message: `当前状态为 ${channel.healthStatus || 'UNTESTED'}，请执行连通性测试。`,
    severity: channel.healthStatus === 'DEGRADED' ? 'danger' : 'warning',
    tab: 'health'
  }))
  models.value.filter(model => model.enabled && !model.callable).slice(0, 8).forEach(model => rows.push({
    key: `model-${model.id}`,
    title: `${model.publicModelName || '未命名模型'} 暂不可调用`,
    message: model.availabilityMessage || '请检查渠道、密钥、健康状态和模型映射。',
    severity: 'warning',
    tab: 'models'
  }))
  return rows.slice(0, 12)
})

async function loadGatewayData() {
  loading.value = true
  try {
    const [channelResponse, modelResponse, logResponse, catalogResponse, mappingResponse] = await Promise.all([
      http.get<ChannelRow[]>('/api/admin/api/channels'),
      http.get<ModelRow[]>('/api/admin/api/models'),
      http.get<TestLog[]>('/api/admin/api/channels/test-logs'),
      http.get<CatalogRow[]>('/api/admin/api/model-catalog'),
      http.get<any[]>('/api/admin/api/upstream-display-mappings')
    ])
    channels.value = channelResponse.data || []
    models.value = modelResponse.data || []
    testLogs.value = logResponse.data || []
    providerCatalog.value = catalogResponse.data || []
    displayMappings.value=(mappingResponse.data||[]).map(row=>({...row,publicCode:row.publicCode||'platform-route',publicName:row.publicName||'平台智能路由',badgeText:row.badgeText||'智能路由',badgeColor:row.badgeColor||'#2563eb',sortOrder:Number(row.sortOrder??100),enabled:row.enabled!==false}))
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '模型网关数据加载失败')
  } finally {
    loading.value = false
  }
}

async function saveDisplayMapping(row:any){try{await http.put(`/api/admin/api/upstream-display-mappings/${row.channelId}`,row);ElMessage.success('公开上游映射已保存')}catch(error:any){ElMessage.error(error?.response?.data?.message||'映射保存失败')}}
async function loadContextPolicy(){if(!contextModel.value)return;try{const r=await http.get('/api/admin/api/models/context-pricing',{params:{publicName:contextModel.value}});contextPolicy.value=r.data||{enabled:false,thresholdTokens:128000,verificationNote:''}}catch(error:any){if(error?.response?.status===404)contextPolicy.value={enabled:false,thresholdTokens:128000,verificationNote:''};else ElMessage.error('策略加载失败')}}
async function saveContextPolicy(){try{await http.put('/api/admin/api/models/context-pricing',contextPolicy.value,{params:{publicName:contextModel.value}});ElMessage.success('长上下文策略已保存')}catch(error:any){ElMessage.error(error?.response?.data?.message||'策略保存失败')}}

function idempotencyKey(prefix: string) {
  return `${prefix}-${crypto.randomUUID()}`
}

async function syncCatalog(source: 'haoee' | 'nvidia') {
  catalogAction.value = `sync-${source}`
  try {
    const response = await http.post('/api/admin/api/model-catalog/sync', { source }, { headers: { 'Idempotency-Key': idempotencyKey(`catalog-${source}`) } })
    ElMessage.success(`${source === 'haoee' ? '好易智算' : 'NVIDIA'} 已同步 ${response.data?.total || 0} 个模型`)
    await loadGatewayData()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '目录同步失败')
  } finally { catalogAction.value = '' }
}

async function verifyCatalogSource() {
  const source = catalogSource.value || 'nvidia'
  catalogAction.value = `verify-${source}`
  try {
    const response = await http.post('/api/admin/api/model-catalog/verify', { source, limit: 20, allowPaid: false }, { headers: { 'Idempotency-Key': idempotencyKey(`verify-${source}`) } })
    ElMessage.success(`已加入 ${response.data?.count || 0} 个低成本验证任务`)
    await loadGatewayData()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '验证任务提交失败')
  } finally { catalogAction.value = '' }
}

async function verifyCatalogModel(row: CatalogRow) {
  const paid = ['image', 'video', 'music', 'speech', 'transcription'].includes(row.capability)
  if (paid) {
    try {
      await ElMessageBox.confirm(`验证 ${row.publicModelName} 可能产生真实的多模态费用，确认继续？`, '费用确认', { type: 'warning', confirmButtonText: '确认付费验证', cancelButtonText: '取消' })
    } catch { return }
  }
  catalogAction.value = `verify-${row.id}`
  try {
    await http.post('/api/admin/api/model-catalog/verify', { id: row.id, allowPaid: paid }, { headers: { 'Idempotency-Key': idempotencyKey(`verify-${row.id}`) } })
    ElMessage.success('模型已进入验证队列')
    await loadGatewayData()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '模型验证提交失败')
  } finally { catalogAction.value = '' }
}

async function purgeFailedModels() {
  const scope = catalogSource.value || '全部上游'
  const count = providerCatalog.value.filter(row => row.verificationStatus === 'FAILED'
    && (!catalogSource.value || row.sourceCode === catalogSource.value)).length
  if (!count) return ElMessage.info('当前筛选范围没有验证失败的模型')
  try {
    await ElMessageBox.confirm(`将删除 ${scope} 的 ${count} 个失败模型及对应路由，并加入永久排除清单。确认继续？`, '删除验证失败模型', {
      type: 'warning', confirmButtonText: '确认删除并排除', cancelButtonText: '取消'
    })
  } catch { return }
  catalogAction.value = 'purge-failed'
  try {
    const response = await http.post('/api/admin/api/model-catalog/purge-failed',
      catalogSource.value ? { source: catalogSource.value } : {},
      { headers: { 'Idempotency-Key': idempotencyKey(`purge-failed-${catalogSource.value || 'all'}`) } })
    ElMessage.success(`已删除并排除 ${response.data?.removed || 0} 个失败模型`)
    await loadGatewayData()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '删除失败模型失败')
  } finally { catalogAction.value = '' }
}

async function loadExclusions() {
  exclusionsLoading.value = true
  try {
    const response = await http.get<ExclusionRow[]>('/api/admin/api/model-catalog/exclusions', {
      params: catalogSource.value ? { source: catalogSource.value } : {}
    })
    exclusions.value = response.data || []
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '排除清单加载失败')
  } finally { exclusionsLoading.value = false }
}

async function openExclusions() {
  exclusionVisible.value = true
  await loadExclusions()
}

async function restoreExclusion(row: ExclusionRow) {
  try {
    await http.delete(`/api/admin/api/model-catalog/exclusions/${row.id}`)
    ElMessage.success(`${row.public_model_name} 已恢复，可重新同步并手动验证`)
    await loadExclusions()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '恢复失败')
  }
}

async function showVerificationHistory(row: CatalogRow) {
  try {
    const response = await http.get<Record<string, any>[]>('/api/admin/api/model-catalog/verifications', { params: { modelId: row.id, limit: 20 } })
    const records = response.data || []
    const text = records.length
      ? records.map(record => `${record.started_at || record.startedAt}  ${record.status}\n${record.message || '无详细信息'}`).join('\n\n')
      : '暂无验证记录'
    await ElMessageBox.alert(text, `${row.publicModelName} · 验证记录`, { customClass: 'catalog-history-dialog' })
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '验证记录加载失败')
  }
}

function catalogStatusTag(status: string) {
  if (status === 'AVAILABLE') return 'success'
  if (status === 'FAILED' || status === 'RETIRED') return 'danger'
  if (status === 'VERIFYING') return 'primary'
  return 'warning'
}

function healthTag(status?: string) {
  if (status === 'HEALTHY') return 'success'
  if (status === 'DEGRADED' || status === 'UNHEALTHY') return 'danger'
  return 'warning'
}

function selectTab(tab: GatewayTab) {
  activeTab.value = tab
  syncTab(tab)
}

function syncTab(tab: string | number) {
  const value = tabs.includes(tab as GatewayTab) ? tab as GatewayTab : 'overview'
  router.replace({ path: '/admin/model-gateway', query: value === 'overview' ? {} : { tab: value } })
}

async function testChannel(channel: ChannelRow) {
  testingChannelId.value = channel.id
  try {
    const response = await http.post(`/api/admin/api/channels/${channel.id}/test`)
    ElMessage.success(`${channel.name} 测试完成：${response.data?.healthStatus || response.data?.status || 'SUCCESS'}`)
    await loadGatewayData()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || `${channel.name} 测试失败`)
    await loadGatewayData()
  } finally {
    testingChannelId.value = null
  }
}

watch(() => route.query.tab, () => { activeTab.value = routeTab() })
onMounted(loadGatewayData)
</script>

<style scoped>
.model-gateway-page { display: grid; gap: 16px; }
.gateway-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 22px 24px; border: 1px solid #dfe7e2; border-radius: 14px; background: linear-gradient(135deg, #fff, #f4f9f1); }
.gateway-heading h2 { margin: 3px 0 6px; color: #17251c; font-size: 26px; }
.gateway-heading p { margin: 0; color: #66736b; }
.gateway-eyebrow { color: #5f9800 !important; font-size: 11px; font-weight: 800; letter-spacing: .12em; }
.gateway-heading-actions { display: flex; flex: 0 1 auto; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.gateway-tabs :deep(.el-tabs__header) { margin-bottom: 14px; }
.gateway-tabs,
.gateway-tabs :deep(.el-tabs__content),
.gateway-tabs :deep(.el-tab-pane) { min-width: 0; }
.gateway-tabs :deep(.el-tabs__content) { overflow: visible; }
.gateway-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; margin-bottom: 14px; }
.gateway-metrics button { display: grid; gap: 5px; padding: 17px; border: 1px solid #e0e7e2; border-radius: 12px; background: #fff; color: #657169; cursor: pointer; text-align: left; transition: border-color .15s ease, transform .15s ease; }
.gateway-metrics button:hover { border-color: #78ad30; transform: translateY(-1px); }
.gateway-metrics strong { color: #17251c; font-size: 28px; }
.gateway-metrics small { color: #8a968e; }
.gateway-overview-grid { display: grid; grid-template-columns: minmax(0, 1.35fr) minmax(320px, .65fr); gap: 14px; }
.gateway-panel { overflow: hidden; padding: 18px; border: 1px solid #e0e7e2; border-radius: 12px; background: #fff; }
.gateway-panel-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.gateway-panel-head h3 { margin: 0; color: #213128; font-size: 17px; }
.gateway-panel-head p { margin: 4px 0 0; color: #87928b; font-size: 12px; }
.gateway-issues { display: grid; gap: 7px; }
.gateway-issues button { display: grid; grid-template-columns: 8px minmax(0, 1fr); gap: 10px; align-items: start; padding: 10px; border: 0; border-radius: 8px; background: #f8faf8; cursor: pointer; text-align: left; }
.gateway-issues button:hover { background: #f0f7e9; }
.gateway-issues span { width: 8px; height: 8px; margin-top: 5px; border-radius: 50%; }
.severity-warning { background: #e6a23c; }.severity-danger { background: #f56c6c; }
.gateway-issues div { display: grid; gap: 3px; }.gateway-issues strong { color: #34433a; }.gateway-issues small { color: #829087; line-height: 1.45; }
.gateway-health-panel { margin-bottom: 14px; }
.catalog-filters { display: grid; grid-template-columns: 180px 190px minmax(220px, 1fr); gap: 10px; margin-bottom: 14px; }
.gateway-table-shell { width: 100%; min-width: 0; overflow: hidden; }
.gateway-table-shell :deep(.el-table__body),.gateway-table-shell :deep(.el-table__header) { min-width: 1250px; }
.gateway-table-shell :deep(.el-scrollbar__bar.is-horizontal) { height: 10px; opacity: 1; }
.gateway-table-shell :deep(.el-scrollbar__thumb) { background-color: #64748b; }
.drawer-tip { margin: 0 0 14px; color: #768279; font-size: 13px; line-height: 1.6; }
.context-policy-panel{margin-top:14px}.context-policy-form{display:grid;grid-template-columns:minmax(220px,1.2fr) auto 190px minmax(220px,1fr) auto;align-items:center;gap:10px}.context-policy-form .el-input-number{width:100%}
@media (max-width: 1100px) { .gateway-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }.gateway-overview-grid { grid-template-columns: minmax(0, 1fr); } }
@media (max-width: 1100px){.context-policy-form{grid-template-columns:repeat(2,minmax(0,1fr))}}@media (max-width: 680px) { .gateway-heading { align-items: stretch; flex-direction: column; }.gateway-heading-actions { display: grid; grid-template-columns: 1fr 1fr; }.gateway-metrics,.catalog-filters,.context-policy-form { grid-template-columns: minmax(0, 1fr); } }
</style>
