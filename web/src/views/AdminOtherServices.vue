<template>
  <section class="other-services-admin">
    <AdminPageToolbar title="服务与订单" description="每项服务统一配置展示信息、价格、服务费、币种和购买状态，并在这里处理服务订单。">
      <template #actions>
        <el-button v-if="activeTab === 'catalog'" type="primary" @click="openCreate">新增服务</el-button>
      </template>
    </AdminPageToolbar>

    <el-tabs v-model="activeTab" @tab-change="syncTab">
      <el-tab-pane label="服务目录" name="catalog">
    <PagedTable v-loading="loading" :data="services" empty-text="暂无服务" list-id="AdminOtherServices-1" pagination="external">
      <el-table-column label="图片" width="116">
        <template #default="{ row }">
          <div class="admin-service-thumb">
            <img v-if="row.imageUrl" :src="resolveApiResourceUrl(row.imageUrl)" :alt="row.name" crossorigin="anonymous" />
            <span v-else>图片待上传</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="服务名称" min-width="150" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">
          <el-tag :type="row.productType === 'CARD_KEY' ? 'success' : 'info'">
            {{ row.productType === 'CARD_KEY' ? '卡密发货' : '普通服务' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="服务介绍" min-width="260" show-overflow-tooltip />
      <el-table-column label="价格" width="130">
        <template #default="{ row }">
          {{ formatMoney(row.amountCents, row.currency) }}
        </template>
      </el-table-column>
      <el-table-column label="允许购买" width="110">
        <template #default="{ row }">
          <el-tag :type="row.purchaseEnabled ? 'success' : 'info'">{{ row.purchaseEnabled ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sortOrder" label="排序" width="90" />
      <el-table-column label="前台展示" width="110">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '展示' : '隐藏' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.productType==='CARD_KEY'" link type="success" @click="manageCards">卡密管理</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </PagedTable>
    <ListPagination v-if="serviceTotal > 0" v-model:page="servicePage" v-model:size="serviceSize" :total="serviceTotal" :allow-all="false" @change="load" />
      </el-tab-pane>
      <el-tab-pane label="服务订单" name="orders">
        <AdminServiceOrders />
        <AdminProductCommerce />
      </el-tab-pane>
      <el-tab-pane label="兑换域名" name="redemption-hosts">
        <div class="redemption-host-toolbar">
          <el-input v-model="hostInput" maxlength="253" placeholder="兑换站域名，例如 redeem.example.com（不要填写 https://）" @keyup.enter="addHost" />
          <el-button type="primary" :loading="hostSaving" @click="addHost">添加域名</el-button>
        </div>
        <el-alert title="只填写可信的 HTTPS 兑换站域名；添加或删除后立即生效，无需重启。" type="info" :closable="false" show-icon />
        <div class="redemption-host-toolbar">
          <el-input v-model="hostQuery" clearable placeholder="搜索域名" @keyup.enter="searchHosts" @clear="searchHosts" />
          <el-button @click="searchHosts">搜索</el-button>
        </div>
        <PagedTable v-loading="hostLoading" :data="hosts" empty-text="暂无兑换域名" list-id="AdminOtherServices-RedemptionHosts" pagination="external">
          <el-table-column prop="host" label="域名" min-width="250" />
          <el-table-column prop="createdAt" label="添加时间" min-width="180" />
          <el-table-column label="操作" width="110">
            <template #default="{ row }"><el-button link type="danger" @click="removeHost(row)">删除</el-button></template>
          </el-table-column>
        </PagedTable>
        <ListPagination v-if="hostTotal > 0" v-model:page="hostPage" v-model:size="hostSize" :total="hostTotal" :allow-all="false" @change="loadHosts" />
      </el-tab-pane>
      <el-tab-pane label="支付记录" name="payments">
        <AdminPaymentIntents />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑服务' : '新增服务'" width="620px">
      <el-form label-position="top" :model="form">
        <el-form-item label="服务类型" required>
          <el-radio-group v-model="form.productType" @change="syncProductType">
            <el-radio-button value="STANDARD">普通服务</el-radio-button>
            <el-radio-button value="CARD_KEY">卡密自动发货</el-radio-button>
          </el-radio-group>
          <span class="form-tip form-tip-block">卡密服务在付款成功后自动发放加密库存中的一条卡密。</span>
        </el-form-item>
        <el-form-item label="服务名称" required>
          <el-input v-model="form.name" maxlength="160" show-word-limit placeholder="例如：服务7" />
        </el-form-item>
        <el-form-item label="交付来源" required>
          <el-radio-group v-model="form.supplierType">
            <el-radio-button value="LOCAL_INVENTORY">本站库存/人工</el-radio-button>
            <el-radio-button value="DUJIAO_NEXT">cccrad.uk 自动采购</el-radio-button>
          </el-radio-group>
          <span class="form-tip form-tip-block">Dujiao-Next 模式只会在本站确认收款后创建上游采购单。</span>
        </el-form-item>
        <template v-if="form.supplierType === 'DUJIAO_NEXT'">
          <el-alert
            title="售价由本站设置；采购价从 cccrad.uk 钱包扣除。请先测试连接并确认上游余额。"
            type="warning"
            :closable="false"
            show-icon
          />
          <el-form-item label="上游商品 / SKU" required>
            <div class="supplier-picker">
              <el-select
                v-model="form.supplierSelection"
                filterable
                placeholder="加载并选择 cccrad.uk 商品"
                :loading="supplierLoading"
                @change="applySupplierSelection"
              >
                <el-option
                  v-for="option in supplierOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <el-button :loading="supplierLoading" @click="loadSupplierProducts">从上游刷新</el-button>
              <el-button :loading="supplierTesting" @click="testSupplier">测试连接</el-button>
            </div>
            <span class="form-tip">Product ID: {{ form.supplierProductId || '-' }}；SKU ID: {{ form.supplierSkuId || '-' }}</span>
          </el-form-item>
        </template>
        <el-form-item label="服务介绍">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="填写服务内容或说明" />
        </el-form-item>
        <el-form-item label="商品图片">
          <div class="service-image-editor">
            <div class="service-image-preview">
              <img v-if="form.imageUrl" :src="resolveApiResourceUrl(form.imageUrl)" alt="商品图片预览" crossorigin="anonymous" />
              <span v-else>暂无图片</span>
            </div>
            <div class="service-image-actions">
              <input
                ref="imageFileInput"
                class="native-file-input"
                type="file"
                accept="image/jpeg,image/png,image/webp"
                @change="uploadLocalImage"
              />
              <div>
                <el-button type="primary" plain :loading="uploadingImage" @click="chooseLocalImage">
                  本地上传
                </el-button>
                <el-button v-if="form.imageUrl" :disabled="uploadingImage" @click="form.imageUrl = ''">
                  移除图片
                </el-button>
              </div>
              <span class="form-tip">支持 JPG、PNG、WebP；上传前自动缩小至 1600×900 内并转为 WebP，尽量控制在 1.5 MB。</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="外部图片 URL（可选）">
          <el-input v-model="form.imageUrl" :disabled="uploadingImage" placeholder="https://example.com/service.jpg" />
          <span class="form-tip">本地上传成功后会自动填写图片地址，也可以手动使用 HTTPS 图片链接。</span>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sortOrder" :min="0" :step="10" />
        </el-form-item>
        <el-form-item label="币种">
          <el-select v-model="form.currency" style="width: 180px">
            <el-option label="CNY - 人民币" value="CNY" />
            <el-option label="USD - 美元" value="USD" />
          </el-select>
        </el-form-item>
        <el-form-item label="服务价格">
          <el-input-number v-model="form.price" :min="0" :precision="2" :step="10" />
          <span class="price-hint">{{ form.currency }}</span>
        </el-form-item>
        <el-form-item label="服务费">
          <el-input-number v-model="form.serviceFee" :min="0" :precision="2" :step="1" />
          <span class="price-hint">{{ form.currency }}</span>
        </el-form-item>
        <el-form-item label="购买按钮文案">
          <el-input v-model="form.actionLabel" maxlength="40" placeholder="立即购买" />
        </el-form-item>
        <template v-if="form.productType === 'CARD_KEY'">
          <el-alert
            title="卡密仅在付款成功后展示；库存内容使用服务端密钥加密，管理列表不会返回明文。"
            type="success"
            :closable="false"
            show-icon
          />
          <el-form-item label="卡密兑换网站" required>
            <el-input v-model="form.redemptionUrl" maxlength="2000" placeholder="https://redeem.example.com/" />
            <span class="form-tip">
              只接受 HTTPS 且域名必须先在后台兑换域名列表中添加。前台通过本站 /services/:id/redeem 安全跳转，不使用 iframe，也不会携带卡密参数。
              {{ form.id && form.redemptionConfigured ? '留空则保留当前地址。' : '' }}
            </span>
            <el-button link type="primary" @click="openHostManager">在新标签页管理兑换域名</el-button>
          </el-form-item>
          <el-form-item label="单笔最多购买">
            <el-input-number v-model="form.maxPurchaseQuantity" :min="1" :max="1000" />
          </el-form-item>
          <el-form-item label="购买提示">
            <el-input v-model="form.purchasePrompt" type="textarea" :rows="2" maxlength="1000" placeholder="例如：付款后在订单详情复制卡密，再前往兑换网站使用" />
          </el-form-item>
          <el-form-item v-if="form.supplierType === 'LOCAL_INVENTORY'" label="初始卡密库存">
            <el-input
              v-model="form.inventoryText"
              type="textarea"
              :rows="6"
              placeholder="粘贴多个卡密，可用逗号、中文逗号、顿号、换行或空格分隔"
            />
            <span class="form-tip form-tip-block">
              已识别 {{ recognizedInventoryItems.length }} 条；保存服务后将立即加密导入，重复卡密自动忽略。
            </span>
          </el-form-item>
        </template>
        <el-form-item label="允许购买">
          <el-switch v-model="form.purchaseEnabled" active-text="启用" inactive-text="停用" />
          <span class="form-tip">停用后服务仍可展示，但不会创建订单。</span>
        </el-form-item>
        <el-form-item label="前台展示">
          <el-switch v-model="form.enabled" active-text="展示" inactive-text="隐藏" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { formatCurrencyCents } from '@/utils/money'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage, resolveApiResourceUrl } from '@/utils/http'
import { compressServiceImage } from '@/utils/serviceImage'
import AdminServiceOrders from '@/views/AdminServiceOrders.vue'
import AdminProductCommerce from '@/views/AdminProductCommerce.vue'
import AdminPaymentIntents from '@/views/AdminPaymentIntents.vue'

type OtherService = {
  id: number
  name: string
  description?: string
  imageUrl?: string
  sortOrder: number
  enabled: boolean
  actionLabel?: string
  priceCents?: number
  serviceFeeCents?: number
  amountCents?: number
  currency?: string
  purchaseEnabled?: boolean
  productType?: 'STANDARD' | 'CARD_KEY'
  fulfillmentMode?: string
  redemptionConfigured?: boolean
  maxPurchaseQuantity?: number
  purchasePrompt?: string
  supplierType?: 'LOCAL_INVENTORY' | 'DUJIAO_NEXT'
  supplierProductId?: number
  supplierSkuId?: number
}

type SupplierOption = { value: string; label: string; productId: number; skuId: number }

const route = useRoute()
const router = useRouter()
const services = ref<OtherService[]>([])
const servicePage = ref(1)
const serviceSize = ref(10)
const serviceTotal = ref(0)
const loading = ref(false)
const saving = ref(false)
const uploadingImage = ref(false)
const supplierLoading = ref(false)
const supplierTesting = ref(false)
const supplierOptions = ref<SupplierOption[]>([])
const imageFileInput = ref<HTMLInputElement | null>(null)
const dialogVisible = ref(false)
type RedemptionHost = { id: number; host: string; createdAt: string }
const hosts = ref<RedemptionHost[]>([])
const hostInput = ref('')
const hostQuery = ref('')
const hostPage = ref(1)
const hostSize = ref(10)
const hostTotal = ref(0)
const hostLoading = ref(false)
const hostSaving = ref(false)
const allowedTabs = ['catalog', 'orders', 'payments', 'redemption-hosts']
const initialTab = typeof route.query.tab === 'string' && allowedTabs.includes(route.query.tab) ? route.query.tab : 'catalog'
const activeTab = ref(initialTab)
const form = reactive({
  id: null as number | null,
  name: '',
  description: '',
  imageUrl: '',
  sortOrder: 0,
  enabled: true,
  actionLabel: '立即购买',
  currency: 'CNY',
  price: 0,
  serviceFee: 0,
  purchaseEnabled: false,
  productType: 'STANDARD' as 'STANDARD' | 'CARD_KEY',
  redemptionUrl: '',
  redemptionConfigured: false,
  maxPurchaseQuantity: 1,
  purchasePrompt: '',
  supplierType: 'LOCAL_INVENTORY' as 'LOCAL_INVENTORY' | 'DUJIAO_NEXT',
  supplierProductId: null as number | null,
  supplierSkuId: null as number | null,
  supplierSelection: '',
  inventoryText: ''
})
const recognizedInventoryItems = computed(() => Array.from(new Set(
  form.inventoryText.split(/[\s,，、]+/u).map(item => item.trim()).filter(Boolean)
)))

const formatMoney = (cents?: number, currency?: string) => cents === null || cents === undefined
  ? '-'
  : formatCurrencyCents(cents, currency)

function syncTab(tab: string | number) {
  const value = String(tab)
  router.replace({ query: value === 'catalog' ? {} : { tab: value } })
  if (value === 'redemption-hosts') loadHosts()
}

async function load() {
  loading.value = true
  try {
    const response = await http.get('/api/admin/api/other-services', { params: { listPage: true, page: servicePage.value, size: serviceSize.value } })
    services.value = response.data?.items || []
    serviceTotal.value = Number(response.data?.total || 0)
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '其他服务加载失败')
  } finally {
    loading.value = false
  }
}

async function loadHosts() {
  hostLoading.value = true
  try {
    const { data } = await http.get('/api/admin/api/other-services/redemption-hosts', {
      params: { page: hostPage.value, size: hostSize.value, query: hostQuery.value.trim() }
    })
    hosts.value = data?.items || []
    hostTotal.value = Number(data?.total || 0)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '兑换域名加载失败'))
  } finally {
    hostLoading.value = false
  }
}
function searchHosts() { hostPage.value = 1; loadHosts() }
async function addHost() {
  if (!hostInput.value.trim()) { ElMessage.warning('请输入兑换站域名'); return }
  hostSaving.value = true
  try {
    await http.post('/api/admin/api/other-services/redemption-hosts', { host: hostInput.value.trim() })
    hostInput.value = ''
    ElMessage.success('域名已添加，即时生效')
    hostPage.value = 1
    await loadHosts()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '添加域名失败'))
  } finally {
    hostSaving.value = false
  }
}
async function removeHost(host: RedemptionHost) {
  try {
    await ElMessageBox.confirm(`删除“${host.host}”后，已有使用该域名的服务兑换跳转将失效。确定删除？`, '删除兑换域名', { type: 'warning' })
    await http.delete(`/api/admin/api/other-services/redemption-hosts/${host.id}`)
    ElMessage.success('域名已删除，即时生效')
    if (hosts.value.length === 1 && hostPage.value > 1) hostPage.value--
    await loadHosts()
  } catch (error: unknown) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getHttpErrorMessage(error, '删除域名失败'))
  }
}

function openHostManager() {
  window.open(router.resolve({ query: { ...route.query, tab: 'redemption-hosts' } }).href, '_blank', 'noopener,noreferrer')
}

function resetForm() {
  form.id = null
  form.name = ''
  form.description = ''
  form.imageUrl = ''
  form.sortOrder = services.value.length ? Math.max(...services.value.map(item => item.sortOrder || 0)) + 10 : 10
  form.enabled = true
  form.actionLabel = '立即购买'
  form.currency = 'CNY'
  form.price = 0
  form.serviceFee = 0
  form.purchaseEnabled = false
  form.productType = 'STANDARD'
  form.redemptionUrl = ''
  form.redemptionConfigured = false
  form.maxPurchaseQuantity = 1
  form.purchasePrompt = ''
  form.supplierType = 'LOCAL_INVENTORY'
  form.supplierProductId = null
  form.supplierSkuId = null
  form.supplierSelection = ''
  form.inventoryText = ''
}

function syncProductType() {
  if (form.productType === 'CARD_KEY') {
    form.actionLabel = '立即购买'
    if (!form.purchasePrompt) form.purchasePrompt = '付款成功后可在订单详情查看卡密并前往兑换。'
  }
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

async function openEdit(service: OtherService) {
  try{service={...service,...(await http.get<OtherService>(`/api/admin/api/other-services/${service.id}`)).data}}catch{/* list data remains usable */}
  form.id = service.id
  form.name = service.name
  form.description = service.description || ''
  form.imageUrl = service.imageUrl || ''
  form.sortOrder = service.sortOrder || 0
  form.enabled = service.enabled
  form.actionLabel = service.actionLabel || '立即购买'
  form.currency = service.currency || 'CNY'
  form.price = (service.priceCents || 0) / 100
  form.serviceFee = (service.serviceFeeCents || 0) / 100
  form.purchaseEnabled = service.purchaseEnabled === true
  form.productType = service.productType || 'STANDARD'
  form.redemptionUrl = (service as any).redemptionUrl || ''
  form.redemptionConfigured = service.redemptionConfigured === true
  form.maxPurchaseQuantity = service.maxPurchaseQuantity || 1
  form.purchasePrompt = service.purchasePrompt || ''
  form.supplierType = service.supplierType || 'LOCAL_INVENTORY'
  form.supplierProductId = service.supplierProductId || null
  form.supplierSkuId = service.supplierSkuId || null
  form.supplierSelection = form.supplierProductId && form.supplierSkuId
    ? `${form.supplierProductId}:${form.supplierSkuId}` : ''
  form.inventoryText = ''
  dialogVisible.value = true
}
function manageCards(){activeTab.value='orders';router.replace({query:{tab:'orders'}});window.setTimeout(()=>document.querySelector('.commerce-admin')?.scrollIntoView({behavior:'smooth'}),50)}

function chooseLocalImage() {
  imageFileInput.value?.click()
}

async function uploadLocalImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    ElMessage.warning('仅支持 JPG、PNG 和 WebP 图片')
    input.value = ''
    return
  }
  if (file.size > 30 * 1024 * 1024) {
    ElMessage.warning('原始图片不能超过 30 MB')
    input.value = ''
    return
  }
  uploadingImage.value = true
  try {
    const uploadFile = await compressServiceImage(file)
    if (uploadFile.size > 5 * 1024 * 1024) throw new Error('图片压缩后仍超过 5 MB')
    const body = new FormData()
    body.append('file', uploadFile)
    const response = await http.post<{ url: string }>('/api/admin/api/other-services/image', body, {
      timeout: 180_000
    })
    form.imageUrl = response.data.url
    ElMessage.success(uploadFile === file ? '图片上传成功' : '图片已自动适配并上传')
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '图片上传失败'))
  } finally {
    uploadingImage.value = false
    input.value = ''
  }
}

async function save() {
  if (!form.name.trim()) {
    ElMessage.warning('请填写服务名称')
    return
  }
  if (form.productType === 'CARD_KEY' && !form.redemptionUrl.trim() && !form.redemptionConfigured) {
    ElMessage.warning('请填写卡密兑换网站')
    return
  }
  if (form.supplierType === 'DUJIAO_NEXT' && (!form.supplierProductId || !form.supplierSkuId)) {
    ElMessage.warning('请先选择 cccrad.uk 上游商品和 SKU')
    return
  }
  saving.value = true
  const payload = {
    name: form.name.trim(),
    description: form.description.trim(),
    imageUrl: form.imageUrl.trim(),
    sortOrder: form.sortOrder,
    enabled: form.enabled,
    actionLabel: form.actionLabel.trim() || '立即购买',
    currency: form.currency,
    priceCents: Math.round((form.price || 0) * 100),
    serviceFeeCents: Math.round((form.serviceFee || 0) * 100),
    purchaseEnabled: form.purchaseEnabled,
    productType: form.productType,
    fulfillmentMode: form.productType === 'CARD_KEY' || form.supplierType === 'DUJIAO_NEXT' ? 'AUTOMATIC_DELIVERY' : 'MANUAL_PROCESSING',
    supplierType: form.supplierType,
    supplierProductId: form.supplierType === 'DUJIAO_NEXT' ? form.supplierProductId : undefined,
    supplierSkuId: form.supplierType === 'DUJIAO_NEXT' ? form.supplierSkuId : undefined,
    redemptionUrl: form.redemptionUrl.trim() || undefined,
    maxPurchaseQuantity: form.maxPurchaseQuantity,
    purchasePrompt: form.purchasePrompt.trim()
  }
  try {
    let savedService: OtherService
    if (form.id) {
      const response = await http.put<OtherService>(`/api/admin/api/other-services/${form.id}`, payload)
      savedService = response.data
    } else {
      const response = await http.post<OtherService>('/api/admin/api/other-services', payload)
      savedService = response.data
    }
    if (form.productType === 'CARD_KEY' && form.supplierType === 'LOCAL_INVENTORY' && recognizedInventoryItems.value.length > 0) {
      try {
        const response = await http.post<{ imported: number }>(
          `/api/admin/api/other-services/${savedService.id}/inventory/import`,
          { content: form.inventoryText }
        )
        const imported = Number(response.data?.imported || 0)
        ElMessage.success(`服务已保存，识别 ${recognizedInventoryItems.value.length} 条，成功导入 ${imported} 条卡密`)
      } catch (inventoryError: unknown) {
        form.id = savedService.id
        ElMessage.error(`服务已保存，但${getHttpErrorMessage(inventoryError, '卡密导入失败')}`)
        await load()
        return
      }
    } else {
      ElMessage.success('服务已保存')
    }
    dialogVisible.value = false
    await load()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

function localizedText(value: unknown) {
  if (typeof value === 'string') return value
  if (!value || typeof value !== 'object') return ''
  const record = value as Record<string, unknown>
  return String(record['zh-CN'] || record['zh'] || record['en-US'] || record['en'] || Object.values(record)[0] || '')
}

async function loadSupplierProducts() {
  supplierLoading.value = true
  try {
    const response = await http.get('/api/admin/api/dujiao-next/products', { params: { page: 1, page_size: 100 } })
    const items = Array.isArray(response.data?.items) ? response.data.items : []
    supplierOptions.value = items.flatMap((product: any) => (Array.isArray(product.skus) ? product.skus : [])
      .filter((sku: any) => sku?.is_active !== false)
      .map((sku: any) => ({
        value: `${product.id}:${sku.id}`,
        productId: Number(product.id),
        skuId: Number(sku.id),
        label: `${localizedText(product.title) || `商品 ${product.id}`} / ${sku.sku_code || `SKU ${sku.id}`} · ${sku.price_amount || product.price_amount || '-'} · 库存 ${sku.stock_quantity === -1 ? '∞' : (sku.stock_quantity ?? '-')}`
      })))
    if (form.supplierSelection && !supplierOptions.value.some(option => option.value === form.supplierSelection)) {
      supplierOptions.value.unshift({
        value: form.supplierSelection,
        productId: Number(form.supplierProductId),
        skuId: Number(form.supplierSkuId),
        label: `当前映射 Product ${form.supplierProductId} / SKU ${form.supplierSkuId}`
      })
    }
    ElMessage.success(`已加载 ${supplierOptions.value.length} 个可用 SKU`)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '上游商品加载失败'))
  } finally {
    supplierLoading.value = false
  }
}

function applySupplierSelection(value: string) {
  const option = supplierOptions.value.find(item => item.value === value)
  if (!option) return
  form.supplierProductId = option.productId
  form.supplierSkuId = option.skuId
}

async function testSupplier() {
  supplierTesting.value = true
  try {
    const response = await http.post('/api/admin/api/dujiao-next/ping')
    ElMessage.success(`连接成功：${response.data?.site_name || 'Dujiao-Next'}，上游余额 ${response.data?.balance ?? '-'} ${response.data?.currency || ''}`)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, 'Dujiao-Next 连接测试失败'))
  } finally {
    supplierTesting.value = false
  }
}

async function remove(service: OtherService) {
  try {
    await ElMessageBox.confirm(`确认删除“${service.name}”？`, '删除确认', { type: 'warning' })
    await http.delete(`/api/admin/api/other-services/${service.id}`)
    ElMessage.success('服务已删除')
    await load()
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error?.response?.data?.message || '删除失败')
  }
}

onMounted(() => { load(); if (activeTab.value === 'redemption-hosts') loadHosts() })
</script>

<style scoped>
.other-services-admin {
  display: grid;
  gap: 20px;
  min-width: 0;
}

.redemption-host-toolbar {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
  max-width: 700px;
}

.supplier-picker {
  display: flex;
  width: 100%;
  gap: 10px;
  flex-wrap: wrap;
}

.supplier-picker .el-select {
  flex: 1 1 360px;
}

.admin-service-thumb {
  width: 80px;
  height: 54px;
  overflow: hidden;
  display: grid;
  place-items: center;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 11px;
}

.admin-service-thumb img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.form-tip {
  margin-top: 6px;
  color: #94a3b8;
  font-size: 12px;
}

.form-tip-block {
  display: block;
  width: 100%;
}

.service-image-editor {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
}

.service-image-preview {
  width: 160px;
  height: 100px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  overflow: hidden;
  border: 1px dashed #cbd5e1;
  border-radius: 8px;
  background: #f8fafc;
  color: #94a3b8;
  font-size: 12px;
}

.service-image-preview img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.service-image-actions {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.native-file-input {
  display: none;
}

@media (max-width: 640px) {
  .service-image-editor {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
