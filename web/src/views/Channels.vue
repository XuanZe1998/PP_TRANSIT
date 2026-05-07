<template>
  <div class="admin-page">
    <section class="page-head">
      <div>
        <h2 class="section-title">渠道管理</h2>
        <p class="section-subtitle">配置供应商账号、Base URL、协议类型与可用模型范围。</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增渠道</el-button>
    </section>

    <section class="shell-section table-panel">
      <el-table :data="channels" style="width: 100%" v-loading="loading">
        <el-table-column prop="name" label="渠道名称" min-width="180" />
        <el-table-column prop="type" label="协议类型" width="160">
          <template #default="{ row }">
            <el-tag>{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="baseUrl" label="Base URL" min-width="260" />
        <el-table-column label="支持模型" min-width="220">
          <template #default="{ row }">
            <el-text truncated>{{ row.models || '-' }}</el-text>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="toggleChannel(scope.row)" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑渠道' : '新增渠道'" width="640px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="渠道名称">
          <el-input v-model="form.name" placeholder="例如 OpenAI Primary" />
        </el-form-item>
        <el-form-item label="协议类型">
          <el-select v-model="form.type" placeholder="选择协议类型">
            <el-option label="OpenAI Compatible" value="openai" />
            <el-option label="Anthropic" value="anthropic" />
            <el-option label="Gemini" value="gemini" />
            <el-option label="DeepSeek" value="deepseek" />
            <el-option label="xAI" value="xai" />
          </el-select>
        </el-form-item>
        <el-form-item label="Base URL">
          <el-input v-model="form.baseUrl" placeholder="https://api.openai.com" />
        </el-form-item>
        <el-form-item label="API Key">
          <el-input v-model="form.apiKey" type="password" show-password />
        </el-form-item>
        <el-form-item label="模型范围">
          <el-input
            v-model="form.models"
            type="textarea"
            :rows="3"
            placeholder="用英文逗号分隔，例如 gpt-5,gpt-5-mini"
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
import { ref, onMounted } from 'vue'
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
    fetchChannels()
  } catch {
    ElMessage.error('保存失败')
  }
}

const toggleChannel = async (row: ChannelRow) => {
  try {
    await http.put(`/api/channels/${row.id}`, row)
    ElMessage.success(row.enabled ? '渠道已启用' : '渠道已停用')
  } catch {
    ElMessage.error('状态更新失败')
    row.enabled = !row.enabled
  }
}

const handleDelete = (row: ChannelRow) => {
  ElMessageBox.confirm(`确认删除渠道 ${row.name} 吗？`, '删除确认', { type: 'warning' }).then(async () => {
    try {
      await http.delete(`/api/channels/${row.id}`)
      ElMessage.success('渠道已删除')
      fetchChannels()
    } catch {
      ElMessage.error('删除失败')
    }
  })
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

.table-panel {
  padding: 24px;
  border-radius: 8px;
}
</style>
