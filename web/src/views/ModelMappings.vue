<template>
  <div class="admin-page">
    <section class="page-head">
      <div>
        <h2 class="section-title">模型映射</h2>
        <p class="section-subtitle">对外公开模型名与底层渠道模型解耦，便于主备切换和灰度治理。</p>
      </div>
      <el-button type="primary" @click="handleAdd">新增映射</el-button>
    </section>

    <section class="shell-section table-panel">
      <el-table :data="mappings" style="width: 100%" v-loading="loading">
        <el-table-column prop="publicModelName" label="公开模型名" min-width="220" />
        <el-table-column prop="channelModelName" label="渠道模型名" min-width="220" />
        <el-table-column prop="channel.name" label="归属渠道" min-width="180" />
        <el-table-column prop="priority" label="优先级" width="120" />
        <el-table-column prop="enabled" label="状态" width="100">
          <template #default="scope">
            <el-switch v-model="scope.row.enabled" @change="toggleMapping(scope.row)" />
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

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑映射' : '新增映射'" width="640px">
      <el-form :model="form" label-width="120px">
        <el-form-item label="公开模型名">
          <el-input v-model="form.publicModelName" placeholder="例如 gpt-5-main" />
        </el-form-item>
        <el-form-item label="渠道模型名">
          <el-input v-model="form.channelModelName" placeholder="例如 gpt-5 或 claude-sonnet" />
        </el-form-item>
        <el-form-item label="绑定渠道">
          <el-select v-model="form.channelId" placeholder="选择渠道">
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
const channels = ref<{ id: number; name: string }[]>([])
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

const fetchMappings = async () => {
  loading.value = true
  try {
    const res = await http.get('/api/mappings')
    mappings.value = res.data
  } catch {
    ElMessage.error('获取映射失败')
  } finally {
    loading.value = false
  }
}

const fetchChannels = async () => {
  try {
    const res = await http.get('/api/channels')
    channels.value = res.data
  } catch {
    ElMessage.error('获取渠道失败')
  }
}

const handleAdd = () => {
  form.value = {
    id: null,
    publicModelName: '',
    channelModelName: '',
    channelId: null,
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
  try {
    if (form.value.id) {
      await http.put(`/api/mappings/${form.value.id}`, form.value)
    } else {
      await http.post('/api/mappings', form.value)
    }
    ElMessage.success('映射已保存')
    dialogVisible.value = false
    fetchMappings()
  } catch {
    ElMessage.error('保存失败')
  }
}

const toggleMapping = async (row: MappingRow) => {
  try {
    await http.put(`/api/mappings/${row.id}`, row)
    ElMessage.success(row.enabled ? '映射已启用' : '映射已停用')
  } catch {
    ElMessage.error('状态更新失败')
    row.enabled = !row.enabled
  }
}

const handleDelete = (row: MappingRow) => {
  ElMessageBox.confirm(`确认删除映射 ${row.publicModelName} 吗？`, '删除确认', { type: 'warning' }).then(async () => {
    try {
      await http.delete(`/api/mappings/${row.id}`)
      ElMessage.success('映射已删除')
      fetchMappings()
    } catch {
      ElMessage.error('删除失败')
    }
  })
}

onMounted(() => {
  fetchMappings()
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
