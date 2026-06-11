<template>
  <div class="admin-products">
    <section class="shell-section panel">
      <div class="panel-head">
        <div>
          <h2 class="section-title">成品管理</h2>
          <p class="section-subtitle">维护前台成品服务展示的商品信息。</p>
        </div>
        <el-button type="primary" @click="openDialog()">新增成品</el-button>
      </div>

      <el-table :data="products" empty-text="暂无成品">
        <el-table-column label="图片" width="110">
          <template #default="{ row }">
            <div class="thumb">
              <img v-if="row.imageUrl" :src="row.imageUrl" :alt="row.name" />
              <span v-else>无图</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="description" label="描述" min-width="260" show-overflow-tooltip />
        <el-table-column label="单价" width="120">
          <template #default="{ row }">¥{{ formatPrice(row.priceCents) }}</template>
        </el-table-column>
        <el-table-column label="服务费" width="120">
          <template #default="{ row }">¥{{ formatPrice(row.serviceFeeCents) }}</template>
        </el-table-column>
        <el-table-column label="前台价格" width="130">
          <template #default="{ row }">¥{{ formatPrice(totalPriceCents(row)) }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="deleteProduct(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑成品' : '新增成品'" width="640px">
      <el-form :model="form" label-position="top">
        <el-form-item label="名称">
          <el-input v-model="form.name" placeholder="例如：ChatGPT 成品服务" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="4" placeholder="填写服务内容、交付方式或注意事项" />
        </el-form-item>
        <el-form-item label="图片 URL">
          <el-input v-model="form.imageUrl" placeholder="https://example.com/product.jpg" />
        </el-form-item>
        <el-form-item label="单价">
          <el-input-number v-model="form.priceYuan" :min="0" :precision="2" :step="10" />
          <span class="price-hint">元</span>
        </el-form-item>
        <el-form-item label="服务费">
          <el-input-number v-model="form.serviceFeeYuan" :min="0" :precision="2" :step="1" />
          <span class="price-hint">元</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveProduct">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/utils/http'

type FinishedProduct = {
  id: number
  name: string
  description: string
  imageUrl: string
  priceCents: number
  serviceFeeCents: number
  createdAt: string
}

type ProductForm = {
  id: number | null
  name: string
  description: string
  imageUrl: string
  priceYuan: number
  serviceFeeYuan: number
}

const products = ref<FinishedProduct[]>([])
const dialogVisible = ref(false)
const form = ref<ProductForm>({
  id: null,
  name: '',
  description: '',
  imageUrl: '',
  priceYuan: 0,
  serviceFeeYuan: 0
})

const formatPrice = (cents: number) => ((cents || 0) / 100).toFixed(2)
const totalPriceCents = (product: FinishedProduct) => (product.priceCents || 0) + (product.serviceFeeCents || 0)

const fetchProducts = async () => {
  const res = await http.get('/api/plus/admin/products')
  products.value = res.data
}

const openDialog = (product?: FinishedProduct) => {
  if (product) {
    form.value = {
      id: product.id,
      name: product.name,
      description: product.description || '',
      imageUrl: product.imageUrl || '',
      priceYuan: (product.priceCents || 0) / 100,
      serviceFeeYuan: (product.serviceFeeCents || 0) / 100
    }
  } else {
    form.value = {
      id: null,
      name: '',
      description: '',
      imageUrl: '',
      priceYuan: 0,
      serviceFeeYuan: 0
    }
  }
  dialogVisible.value = true
}

const saveProduct = async () => {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写名称')
    return
  }
  const payload = {
    name: form.value.name.trim(),
    description: form.value.description,
    imageUrl: form.value.imageUrl,
    priceCents: Math.round((form.value.priceYuan || 0) * 100),
    serviceFeeCents: Math.round((form.value.serviceFeeYuan || 0) * 100)
  }
  if (form.value.id) {
    await http.put(`/api/plus/admin/products/${form.value.id}`, payload)
  } else {
    await http.post('/api/plus/admin/products', payload)
  }
  ElMessage.success('成品已保存')
  dialogVisible.value = false
  await fetchProducts()
}

const deleteProduct = async (id: number) => {
  await ElMessageBox.confirm('确认删除这个成品？', '删除确认', { type: 'warning' })
  await http.delete(`/api/plus/admin/products/${id}`)
  ElMessage.success('成品已删除')
  await fetchProducts()
}

onMounted(() => {
  fetchProducts().catch(() => ElMessage.error('成品加载失败'))
})
</script>

<style scoped>
.admin-products,
.panel {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.panel {
  padding: 24px;
  border-radius: 8px;
}

.panel-head {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: start;
}

.thumb {
  width: 72px;
  height: 48px;
  border-radius: 8px;
  background: #e2e8f0;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #64748b;
  font-size: 12px;
}

.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.price-hint {
  margin-left: 8px;
  color: #64748b;
}
</style>
