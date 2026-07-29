<template>
  <section class="other-services-admin">
    <div class="admin-service-head">
      <div>
        <h2>服务与订单</h2>
        <p>每项服务统一配置展示信息、价格、服务费、币种和购买状态，并在这里处理服务订单。</p>
      </div>
      <div class="admin-service-actions">
        <el-button plain @click="router.push('/admin/payment-link')">打开支付链接工具</el-button>
        <el-button v-if="activeTab === 'catalog'" type="primary" @click="openCreate">新增服务</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="syncTab">
      <el-tab-pane label="服务目录" name="catalog">
    <el-table v-loading="loading" :data="services" empty-text="暂无服务">
      <el-table-column label="图片" width="116">
        <template #default="{ row }">
          <div class="admin-service-thumb">
            <img v-if="row.imageUrl" :src="row.imageUrl" :alt="row.name" />
            <span v-else>图片待上传</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="服务名称" min-width="150" />
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
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
      </el-tab-pane>
      <el-tab-pane label="服务订单" name="orders">
        <AdminServiceOrders />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑服务' : '新增服务'" width="620px">
      <el-form label-position="top" :model="form">
        <el-form-item label="服务名称" required>
          <el-input v-model="form.name" maxlength="160" show-word-limit placeholder="例如：服务7" />
        </el-form-item>
        <el-form-item label="服务介绍">
          <el-input v-model="form.description" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="填写服务内容或说明" />
        </el-form-item>
        <el-form-item label="商品图片">
          <div class="service-image-editor">
            <div class="service-image-preview">
              <img v-if="form.imageUrl" :src="form.imageUrl" alt="商品图片预览" />
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
              <span class="form-tip">支持 JPG、PNG、WebP，文件不超过 5 MB。</span>
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
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import AdminServiceOrders from '@/views/AdminPlusOrders.vue'

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
}

const route = useRoute()
const router = useRouter()
const services = ref<OtherService[]>([])
const loading = ref(false)
const saving = ref(false)
const uploadingImage = ref(false)
const imageFileInput = ref<HTMLInputElement | null>(null)
const dialogVisible = ref(false)
const allowedTabs = ['catalog', 'orders']
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
  purchaseEnabled: false
})

const formatMoney = (cents?: number, currency?: string) => cents === null || cents === undefined
  ? '-'
  : `${currency || 'CNY'} ${(cents / 100).toFixed(2)}`

function syncTab(tab: string | number) {
  const value = String(tab)
  router.replace({ query: value === 'catalog' ? {} : { tab: value } })
}

async function load() {
  loading.value = true
  try {
    const response = await http.get<OtherService[]>('/api/admin/api/other-services')
    services.value = response.data || []
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '其他服务加载失败')
  } finally {
    loading.value = false
  }
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
}

function openCreate() {
  resetForm()
  dialogVisible.value = true
}

function openEdit(service: OtherService) {
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
  dialogVisible.value = true
}

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
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('图片文件不能超过 5 MB')
    input.value = ''
    return
  }
  uploadingImage.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    const response = await http.post<{ url: string }>('/api/admin/api/other-services/image', body)
    form.imageUrl = response.data.url
    ElMessage.success('图片上传成功')
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
    purchaseEnabled: form.purchaseEnabled
  }
  try {
    if (form.id) {
      await http.put(`/api/admin/api/other-services/${form.id}`, payload)
    } else {
      await http.post('/api/admin/api/other-services', payload)
    }
    ElMessage.success('服务已保存')
    dialogVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(error?.response?.data?.message || '保存失败')
  } finally {
    saving.value = false
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

onMounted(load)
</script>

<style scoped>
.other-services-admin {
  padding: 24px;
  border-radius: 8px;
  background: #fff;
}

.admin-service-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 20px;
}

.admin-service-head h2 {
  margin: 0;
  color: #111827;
}

.admin-service-head p {
  margin: 8px 0 0;
  color: #64748b;
}

.admin-service-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
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
  object-fit: cover;
}

.form-tip {
  margin-top: 6px;
  color: #94a3b8;
  font-size: 12px;
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
  object-fit: cover;
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
  .admin-service-head {
    flex-direction: column;
  }

  .service-image-editor {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
