<template>
  <div class="admin-page">
    <section class="page-head">
      <div>
        <h2 class="section-title">令牌治理</h2>
        <p class="section-subtitle">向业务系统下发访问令牌，并控制额度、有效期和启停状态。</p>
      </div>
      <el-button type="primary" @click="handleAdd">生成令牌</el-button>
    </section>

    <section class="shell-section table-panel">
      <el-table :data="tokens" style="width: 100%" v-loading="loading">
        <el-table-column prop="name" label="令牌名称" min-width="180" />
        <el-table-column prop="key" label="令牌 Key" min-width="260">
          <template #default="{ row }">
            <div class="token-cell">
              <el-text truncated>{{ row.key }}</el-text>
              <el-button size="small" link type="primary" @click="copyToClipboard(row.key)">复制</el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="额度" width="120">
          <template #default="{ row }">{{ formatQuota(row.totalQuota) }}</template>
        </el-table-column>
        <el-table-column label="已用" width="120">
          <template #default="{ row }">{{ formatQuota(row.usedQuota) }}</template>
        </el-table-column>
        <el-table-column prop="expiredAt" label="过期时间" min-width="180" />
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="toggleToken(scope.row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <el-button size="small" @click="handleEdit(scope.row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑令牌' : '生成令牌'" width="620px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="令牌名称">
          <el-input v-model="form.name" placeholder="例如 ERP Production" />
        </el-form-item>
        <el-form-item v-if="form.id" label="令牌 Key">
          <el-input v-model="form.key" readonly />
        </el-form-item>
        <el-form-item label="总额度">
          <el-input-number v-model="form.totalQuota" :min="0" :step="10000" />
          <div class="tip">单位是 token，总量控制在网关侧生效。</div>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="form.expiredAt" type="datetime" placeholder="选择过期时间" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveToken">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/utils/http'

type TokenRow = {
  id: number | null
  name: string
  key: string
  totalQuota: number
  usedQuota: number
  expiredAt: string | null
  enabled: boolean
}

const tokens = ref<TokenRow[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref<TokenRow>({
  id: null,
  name: '',
  key: '',
  totalQuota: 1000000,
  usedQuota: 0,
  expiredAt: null,
  enabled: true
})

const fetchTokens = async () => {
  loading.value = true
  try {
    const res = await http.get('/api/tokens')
    tokens.value = res.data
  } catch {
    ElMessage.error('获取令牌失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  form.value = {
    id: null,
    name: '',
    key: '',
    totalQuota: 1000000,
    usedQuota: 0,
    expiredAt: null,
    enabled: true
  }
  dialogVisible.value = true
}

const handleEdit = (row: TokenRow) => {
  form.value = { ...row }
  dialogVisible.value = true
}

const saveToken = async () => {
  try {
    if (form.value.id) {
      await http.put(`/api/tokens/${form.value.id}`, form.value)
    } else {
      await http.post('/api/tokens', form.value)
    }
    ElMessage.success('令牌已保存')
    dialogVisible.value = false
    fetchTokens()
  } catch {
    ElMessage.error('保存失败')
  }
}

const copyToClipboard = (text: string) => {
  navigator.clipboard.writeText(text).then(() => {
    ElMessage.success('已复制到剪贴板')
  })
}

const toggleToken = async (row: TokenRow) => {
  try {
    await http.put(`/api/tokens/${row.id}`, row)
    ElMessage.success(row.enabled ? '令牌已启用' : '令牌已停用')
  } catch {
    ElMessage.error('状态更新失败')
    row.enabled = !row.enabled
  }
}

const handleDelete = (row: TokenRow) => {
  ElMessageBox.confirm(`确认删除令牌 ${row.name} 吗？`, '删除确认', { type: 'warning' }).then(async () => {
    try {
      await http.delete(`/api/tokens/${row.id}`)
      ElMessage.success('令牌已删除')
      fetchTokens()
    } catch {
      ElMessage.error('删除失败')
    }
  })
}

const formatQuota = (value: number) => `${Math.round(value / 1000)} K`

onMounted(() => {
  fetchTokens()
})
</script>

<style scoped>
.admin-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.page-head {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 16px;
}

.table-panel {
  padding: 24px;
  border-radius: 8px;
}

.token-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.tip {
  font-size: 12px;
  color: #64748b;
  margin-top: 6px;
}
</style>
