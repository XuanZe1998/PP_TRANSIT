<template>
  <div class="admin-page">
    <section class="page-head">
      <div>
        <h2 class="section-title">模型映射</h2>
        <p class="section-subtitle">把前台公开模型名绑定到底层渠道模型名。模型广场只展示已启用映射和已启用渠道。</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增映射</el-button>
    </section>

    <section class="shell-section table-panel">
      <el-table :data="mappings" style="width: 100%" v-loading="loading">
        <el-table-column prop="publicModelName" label="公开模型名" min-width="220" />
        <el-table-column prop="channelModelName" label="渠道模型名" min-width="220" />
        <el-table-column label="归属渠道" min-width="180">
          <template #default="{ row }">
            {{ row.channel?.name || channelName(row.channelId) || '-' }}
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="120" />
        <el-table-column prop="enabled" label="启用" width="100">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" @change="toggleMapping(row)" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑映射' : '新增映射'" width="640px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="公开模型名" required>
          <el-input v-model="form.publicModelName" placeholder="例如 gpt-4o-mini" />
        </el-form-item>
        <el-form-item label="渠道模型名" required>
          <el-input v-model="form.channelModelName" placeholder="例如 gpt-4o-mini 或 claude-3-5-sonnet-latest" />
        </el-form-item>
        <el-form-item label="绑定渠道" required>
          <el-select v-model="form.channelId" placeholder="选择渠道" filterable>
            <el-option v-for="channel in channels" :key="channel.id" :label="channel.name" :value="channel.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveMapping">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/utils/http'

type ChannelOption = {
  id: number
  name: string
}

type MappingRow = {
  id: number | null
  publicModelName: string
  channelModelName: string
  channelId: number | null
  priority: number
  enabled: boolean
  channel?: { id: number; name: string }
}

const mappings = ref<MappingRow[]>([])
const channels = ref<ChannelOption[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = ref<MappingRow>({
  id: null,
  publicModelName: '',
  channelModelName: '',
  channelId: null,
  priority: 10,
  enabled: true
})

const errorMessage = (error: any, fallback: string) => error?.response?.data?.message || error?.response?.data?.error || fallback
const channelName = (id: number | null) => channels.value.find(channel => channel.id === id)?.name

const fetchMappings = async () => {
  loading.value = true
  try {
    const res = await http.get('/api/mappings')
    mappings.value = res.data
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '获取映射失败'))
  } finally {
    loading.value = false
  }
}

const fetchChannels = async () => {
  try {
    const res = await http.get('/api/channels')
    channels.value = res.data
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '获取渠道失败'))
  }
}

const handleAdd = () => {
  form.value = {
    id: null,
    publicModelName: '',
    channelModelName: '',
    channelId: channels.value[0]?.id ?? null,
    priority: 10,
    enabled: true
  }
  dialogVisible.value = true
}

const handleEdit = (row: MappingRow) => {
  form.value = { ...row, channelId: row.channel?.id ?? row.channelId }
  dialogVisible.value = true
}

const saveMapping = async () => {
  if (!form.value.publicModelName || !form.value.channelModelName || !form.value.channelId) {
    ElMessage.warning('请填写公开模型名、渠道模型名并选择渠道')
    return
  }
  try {
    if (form.value.id) {
      await http.put(`/api/mappings/${form.value.id}`, form.value)
    } else {
      await http.post('/api/mappings', form.value)
    }
    ElMessage.success('映射已保存')
    dialogVisible.value = false
    await fetchMappings()
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '保存映射失败'))
  }
}

const toggleMapping = async (row: MappingRow) => {
  try {
    await http.put(`/api/mappings/${row.id}`, row)
    ElMessage.success(row.enabled ? '映射已启用' : '映射已停用')
  } catch (error: any) {
    ElMessage.error(errorMessage(error, '状态更新失败'))
    row.enabled = !row.enabled
  }
}

const handleDelete = (row: MappingRow) => {
  ElMessageBox.confirm(`确认删除映射 ${row.publicModelName}？`, '删除确认', { type: 'warning' }).then(async () => {
    try {
      await http.delete(`/api/mappings/${row.id}`)
      ElMessage.success('映射已删除')
      await fetchMappings()
    } catch (error: any) {
      ElMessage.error(errorMessage(error, '删除映射失败'))
    }
  })
}

onMounted(async () => {
  await fetchChannels()
  await fetchMappings()
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
