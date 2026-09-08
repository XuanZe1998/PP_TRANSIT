<template>
  <el-button @click="open">接入上游</el-button>
  <el-dialog v-model="visible" title="接入上游" width="min(720px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="reset">
    <el-steps :active="preview ? 1 : 0" simple style="margin-bottom: 20px">
    <el-step title="填写地址和 Key" /><el-step title="确认分组、价格和模型" />
    </el-steps>
    <el-form v-if="!preview" label-position="top" @submit.prevent="readModels">
      <el-form-item label="接入方式"><el-select v-model="adapter" @change="chooseAdapter"><el-option label="sub2api 站点（自动识别）" value="sub2api"/><el-option label="New API 兼容站点" value="new-api"/><el-option label="AiAPIBank" value="aiapibank"/><el-option label="好易智算" value="haoee"/></el-select></el-form-item>
      <el-form-item v-if="['new-api', 'sub2api'].includes(adapter)" label="归属站点（留空创建新站点）"><el-select v-model="siteId" clearable filterable remote :remote-method="searchSites" @visible-change="searchSites('')"><el-option v-for="site in sites" :key="site.id" :value="site.id" :label="site.name"/></el-select></el-form-item>
      <el-form-item label="站点地址">
        <el-input v-model="baseUrl" placeholder="https://example.com（也支持 /v1 地址）" :disabled="busy || !['new-api', 'sub2api'].includes(adapter)" />
      </el-form-item>
      <el-form-item label="上游推理 API Key">
        <el-input v-model="apiKey" type="password" show-password autocomplete="off" :disabled="busy" />
      </el-form-item>
      <p v-if="adapter === 'sub2api'">使用 sub2api 的 API Key；系统会校验站点特征、读取该 Key 真实可见的模型，并自动绑定它所属的上游分组。</p>
      <p v-else>使用上游「令牌」页面的 Key。令牌所属分组由上游决定；不同分组可分别接入。</p>
      <el-collapse v-if="adapter === 'new-api'">
        <el-collapse-item title="价格页需要登录时：配置账号访问令牌（可选）" name="pricing-auth">
          <el-form-item label="上游账号访问令牌（不是登录密码）"><el-input v-model="pricingAccessToken" type="password" show-password autocomplete="off" :disabled="busy" /></el-form-item>
          <el-form-item label="上游账号 ID（部分版本需要）"><el-input-number v-model="pricingUserId" :min="1" :precision="0" :disabled="busy" /></el-form-item>
          <p>仅用于同一站点的价格接口，加密保存供后续同步；公开价格页可留空。</p>
        </el-collapse-item>
      </el-collapse>
    </el-form>
    <template v-else>
      <p>{{ preview.baseUrl }} · 读取到 {{ preview.models.length }} 个模型</p>
      <el-form label-position="top">
        <el-form-item label="渠道名称"><el-input v-model="name" maxlength="100" :disabled="busy" /></el-form-item>
        <el-form-item label="前台公开渠道名（可选）">
          <el-input v-model="publicName" maxlength="120" placeholder="例如 ahh" :disabled="busy" />
          <small>发布后在模型广场的渠道筛选中显示；同一站点的多个分组会合并为一个渠道项。留空则继承站点设置或归入「平台智能路由」。</small>
        </el-form-item>
        <el-form-item label="确认该 Key 在上游所属的分组">
          <el-select v-model="upstreamGroup" filterable :allow-create="!preview.groups.length" default-first-option :disabled="busy" style="width: 100%" @change="updateQuote">
            <el-option v-for="group in preview.groups" :key="group.name" :value="group.name" :label="adapter === 'new-api' ? `${group.name}（倍率 ${group.ratio}）` : `${group.name} · ${group.description || ''}`" />
          </el-select>
          <small>必须与上游令牌分组一致；这里只选择报价分组，不会修改令牌权限。自动分组 Key 请先在上游改为固定分组。</small>
        </el-form-item>
        <el-form-item v-if="adapter === 'new-api'" label="销售倍率（本站售价 = 采购成本 × 倍率）"><el-input-number v-model="saleMarkup" :min="0.01" :max="1000" :step="0.1" :precision="4" :disabled="busy" @change="updateQuote" /></el-form-item>
        <el-form-item v-if="adapter === 'new-api'" label="每个上游报价单位折合 USD（标准 New API 填 1）">
          <el-input-number v-model="unitUsd" :min="0.00000001" :max="1000" :precision="8" :disabled="busy" @change="updateQuote" />
          <small>站点若重定义额度货币或有充值折扣，请按实际采购成本填写；不会根据页面货币符号猜测换算。</small>
        </el-form-item>
        <el-form-item label="模型">
          <el-select v-model="selected" multiple filterable placeholder="选择要导入的模型" style="width: 100%"
            collapse-tags collapse-tags-tooltip :max-collapse-tags="6" :disabled="busy">
            <el-option v-for="model in preview.models" :key="model" :label="model" :value="model" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-button size="small" :disabled="busy" @click="selected = [...preview.models]">全选</el-button>
      <el-button size="small" :disabled="busy" @click="selected = []">清空</el-button>
      <p>已选 {{ selected.length }} 个模型</p>
      <el-alert v-if="preview.pricingWarning" :title="preview.pricingWarning" type="warning" :closable="false" />
      <PagedTable :data="selectedPrices" max-height="280" size="small" list-id="NewApiConnect-1">
        <el-table-column prop="model" label="模型" min-width="150" />
        <el-table-column label="采购 / 售价 (USD)" min-width="190"><template #default="{ row }">
          <template v-if="row.status === 'READY'">
            <span v-if="row.unit === 'TASK'">每次 {{ row.perRequest }} / {{ row.salePerRequest }}</span>
            <span v-else>输入 {{ row.input }} / {{ row.saleInput }}<br>输出 {{ row.output }} / {{ row.saleOutput }}<br>缓存读 {{ row.cacheRead }} / {{ row.saleCacheRead }}<br>缓存写 {{ row.cacheWrite }} / {{ row.saleCacheWrite }}<br>每百万 Token</span>
          </template><span v-else>待配置</span>
        </template></el-table-column>
        <el-table-column prop="message" label="说明" min-width="180" />
      </PagedTable>
      <el-checkbox v-model="autoSync" :disabled="busy">每 15 分钟自动同步</el-checkbox>
      <el-checkbox v-model="addNewModels" :disabled="busy">自动导入新模型（未发布）</el-checkbox>
      <el-checkbox v-if="adapter === 'new-api'" v-model="updatePrices" :disabled="busy">同步覆盖采购价并按倍率更新售价</el-checkbox>
      <el-alert v-if="tooLong" type="warning" :closable="false" title="所选模型名称总长度超过渠道容量，请减少选择或分批接入。" />
      <el-alert type="info" :closable="false" :title="adapter === 'sub2api' ? 'sub2api 分组导入后默认未发布且待核价；同步不覆盖手工价格，模型连续两次完整目录缺失后停用。' : '导入为未发布渠道。有效价格自动填写，动态计费保留待配置；上游模型或分组连续两次完整目录缺失后自动停用，恢复后需手动发布。'" />
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top: 12px" />
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button v-if="preview" :disabled="busy" @click="preview = null; error = ''">上一步</el-button>
      <el-button v-if="!preview" type="primary" :loading="busy" :disabled="!baseUrl.trim() || !apiKey.trim()" @click="readModels">读取模型</el-button>
      <el-button v-else type="primary" :loading="busy" :disabled="(selectionRequired && (!selected.length || tooLong)) || !upstreamGroup || !quoteValid" @click="connect">导入渠道和价格</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '../utils/http'

type Price = { model: string; status: string; unit: string; message: string; input: number | null; output: number | null; cacheRead: number | null; cacheWrite: number | null; perRequest: number | null; saleInput: number | null; saleOutput: number | null; saleCacheRead: number | null; saleCacheWrite: number | null; salePerRequest: number | null }
type Preview = { baseUrl: string; suggestedName: string; models: string[]; groups: { name: string; description: string; ratio: number }[]; prices: Price[]; pricingWarning: string | null; selectedGroup?: string }
const emit = defineEmits<{ connected: [] }>()
const visible = ref(false)
const busy = ref(false)
const baseUrl = ref('')
const apiKey = ref('')
const adapter = ref('sub2api')
function chooseAdapter(){siteId.value=undefined;sites.value=[];baseUrl.value=adapter.value==='aiapibank'?'https://aiapibank.com':adapter.value==='haoee'?'https://maas.haoee.com':''}
function endpoint(action:string){return adapter.value==='new-api'?`/api/admin/api/channels/new-api/${action}`:`/api/admin/api/gateway/onboard/${adapter.value}/${action}`}

const siteId=ref<number>()
const sites=ref<any[]>([])
async function searchSites(query:string){sites.value=(await http.get('/api/admin/api/gateway/sites',{params:{query,size:100}})).data.items.filter((s:any)=>s.adapter===adapter.value)}
const name = ref('')
const publicName = ref('')
const selected = ref<string[]>([])
const preview = ref<Preview | null>(null)
const error = ref('')
const upstreamGroup = ref('')
const saleMarkup = ref(1.2)
const unitUsd = ref(1)
const pricingAccessToken = ref('')
const pricingUserId = ref<number>()
const autoSync = ref(true)
const addNewModels = ref(true)
const updatePrices = ref(true)
const quoteValid = ref(false)
const selectedPrices = computed(() => (preview.value?.prices || []).filter(row => selected.value.includes(row.model)))
const tooLong = computed(() => selected.value.length > 500 || selected.value.join('\n').length > 2000)
const selectionRequired = computed(() => ['new-api', 'sub2api'].includes(adapter.value))
function reset() {
  siteId.value=undefined; adapter.value='sub2api'; baseUrl.value = ''; apiKey.value = ''; name.value = ''; publicName.value = ''; selected.value = []; preview.value = null; error.value = ''
  upstreamGroup.value = ''; saleMarkup.value = 1.2; unitUsd.value = 1; pricingAccessToken.value = ''; pricingUserId.value = undefined
  autoSync.value = true; addNewModels.value = true; updatePrices.value = true; quoteValid.value = false
}
function open() { reset(); visible.value = true }
function requestData() {
  return { siteId:siteId.value, baseUrl: baseUrl.value, apiKey: apiKey.value, upstreamGroup: upstreamGroup.value, saleMarkup: saleMarkup.value,
    unitUsd: unitUsd.value, pricingAccessToken: pricingAccessToken.value, pricingUserId: pricingUserId.value,
    autoSync: autoSync.value, addNewModels: addNewModels.value, updatePrices: updatePrices.value }
}
async function updateQuote() { quoteValid.value = false; if (upstreamGroup.value) await readModels() }
async function readModels() {
  busy.value = true; error.value = ''
  try {
    const { data } = await http.post<Preview>(endpoint('preview'), requestData(), { timeout: 50_000 })
    const first = !preview.value
    preview.value = data
    if (data.selectedGroup) upstreamGroup.value = data.selectedGroup
    if (first) { name.value = data.suggestedName; selected.value = data.models.length <= 500 && data.models.join('\n').length <= 2000 ? [...data.models] : [] }
    else selected.value = selected.value.filter(model => data.models.includes(model))
    quoteValid.value = true
  } catch (e) { error.value = getHttpErrorMessage(e, '读取模型失败') }
  finally { busy.value = false }
}
async function connect() {
  busy.value = true; error.value = ''
  try {
    await http.post(endpoint('connect'), {
      ...requestData(), name: name.value, publicName: publicName.value, models: selected.value
    }, { timeout: 60_000 })
    apiKey.value = ''
    pricingAccessToken.value = ''
    visible.value = false
    ElMessage.success('渠道、模型和可用价格已导入，同步策略已保存；测试后可发布')
    emit('connected')
  } catch (e) { error.value = getHttpErrorMessage(e, '导入失败，请刷新渠道列表确认结果后重试') }
  finally { busy.value = false }
}
</script>
