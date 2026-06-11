<template>
  <div class="admin-page">
    <section class="page-head">
      <div>
        <h2 class="section-title">渠道管理</h2>
        <p class="section-subtitle">管理员维护底层供应商渠道。DeepSeek 可以使用 OpenAI 兼容地址或 Anthropic 兼容地址。</p>
      </div>
      <div class="page-actions">
        <el-button @click="openPreset('deepseek')">DeepSeek 预填</el-button>
        <el-button @click="openPreset('deepseek-anthropic')">Anthropic 预填</el-button>
        <el-button type="primary" @click="handleAdd">新增渠道</el-button>
      </div>
    </section>

    <section class="shell-section table-panel">
      <el-table :data="channels" style="width: 100%" v-loading="loading">
        <el-table-column prop="name" label="渠道名称" min-width="180" />
        <el-table-column prop="type" label="协议类型" width="180">
          <template #default="{ row }">
            <el-tag>{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="260" />
        <el-table-column label="模型范围" min-width="220">
          <template #default="{ row }">
            <el-text truncated>{{ row.models || '-' }}</el-text>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleChannel(row)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleEdit(row)">编辑</el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑渠道' : '新增渠道'" width="640px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="渠道名称">
          <el-input v-model="form.name" placeholder="例如 DeepSeek Primary" />
        </el-form-item>
        <el-form-item label="协议类型">
          <el-select v-model="form.type" placeholder="选择协议类型">
            <el-option label="OpenAI Compatible" value="openai" />
            <el-option label="Anthropic" value="anthropic" />
            <el-option label="Gemini" value="gemini" />
            <el-option label="DeepSeek" value="deepseek" />
            <el-option label="DeepSeek Anthropic" value="deepseek-anthropic" />
            <el-option label="xAI" value="xai" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="https://api.deepseek.com" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password />
        </el-form-item>
        <el-form-item label="模型范围">
          <el-input
            v-model="form.models"
            type="textarea"
            :rows="3"
            placeholder="例如：deepseek-chat,deepseek-reasoner"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveChannel">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/utils/http'

type ChannelRow = {
  id: number | null
  name: string
  type: string
  baseUrl: string
  apiKey: string
  models: string
  enabled: boolean
}

const channels = ref<ChannelRow[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref<ChannelRow>({
  id: null,
  name: '',
  type: 'openai',
  baseUrl: '',
  apiKey: '',
  models: '',
  enabled: true
})

const fetchChannels = async () => {
  loading.value = true
  try {
    const res = await http.get('/api/channels')
    channels.value = res.data
  } catch {
    ElMessage.error('获取渠道失败')
  } finally {
    loading.value = false
  }
}

const handleAdd = () => {
  form.value = {
    id: null,
    name: '',
    type: 'openai',
    baseUrl: '',
    apiKey: '',
    models: '',
    enabled: true
  }
  dialogVisible.value = true
}

const openPreset = (type: 'deepseek' | 'deepseek-anthropic') => {
  form.value = {
    id: null,
    name: type === 'deepseek' ? 'DeepSeek Primary' : 'DeepSeek Anthropic',
    type,
    baseUrl: type === 'deepseek' ? 'https://api.deepseek.com' : 'https://api.deepseek.com/anthropic',
    apiKey: '',
    models: 'deepseek-chat,deepseek-reasoner',
    enabled: true
  }
  dialogVisible.value = true
}

const handleEdit = (row: ChannelRow) => {
  form.value = { ...row }
  dialogVisible.value = true
}

const saveChannel = async () => {
  try {
    if (form.value.id) {
      await http.put(`/api/channels/${form.value.id}`, form.value)
    } else {
      await http.post('/api/channels', form.value)
    }
    ElMessage.success('渠道已保存')
    dialogVisible.value = false
    await fetchChannels()
  } catch {
    ElMessage.error('保存失败')
  }
}

const toggleChannel = async (row: ChannelRow) => {
  try {
    await http.put(`/api/channels/${row.id}`, row)
    ElMessage.success('状态已更新')
  } catch {
    ElMessage.error('状态更新失败')
    row.enabled = !row.enabled
  }
}

const handleDelete = async (row: ChannelRow) => {
  await ElMessageBox.confirm(`确认删除渠道 ${row.name}？`, '删除确认', { type: 'warning' })
  try {
    await http.delete(`/api/channels/${row.id}`)
    ElMessage.success('渠道已删除')
    await fetchChannels()
  } catch {
    ElMessage.error('删除失败')
  }
}

onMounted(() => {
  fetchChannels()
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

.page-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.table-panel {
  padding: 24px;
  border-radius: 8px;
}
</style>
