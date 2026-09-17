<template>
  <div class="contact-admin">
    <AdminPageToolbar title="联系方式" description="维护前台悬浮联系卡展示的渠道与号码。">
      <template #filters>
        <el-input
          class="contact-search"
          v-model="query"
          clearable
          placeholder="搜索渠道或号码"
          :prefix-icon="Search"
          @keyup.enter="search"
          @clear="search"
        />
      </template>
      <template #actions>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增联系方式</el-button>
      </template>
    </AdminPageToolbar>

    <section class="contact-panel">
      <PagedTable :data="rows" :pagination="'external'" list-id="AdminContactMethods-1" v-loading="loading">
        <el-table-column prop="channel" label="渠道" min-width="180" />
        <el-table-column prop="number" label="号码" min-width="260" />
        <el-table-column prop="updated_at" label="更新时间" min-width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </PagedTable>
      <ListPagination
        v-model:page="paging.page"
        v-model:size="paging.size"
        v-model:all="paging.all"
        :total="total"
        @change="load"
      />
    </section>

    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑联系方式' : '新增联系方式'" width="min(520px, calc(100vw - 24px))">
      <el-form label-position="top" @submit.prevent="save">
        <el-form-item label="渠道" required>
          <el-input v-model="form.channel" maxlength="80" show-word-limit placeholder="例如：微信、QQ、邮箱、电话" />
        </el-form-item>
        <el-form-item label="号码" required>
          <el-input v-model="form.number" maxlength="240" show-word-limit placeholder="请输入号码或账号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import http, { getHttpErrorMessage } from '@/utils/http'
import { useListPage } from '@/utils/listPage'

type ContactMethod = { id: number; channel: string; number: string; updated_at?: string }

const rows = ref<ContactMethod[]>([])
const total = ref(0)
const query = ref('')
const loading = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive({ channel: '', number: '' })
const paging = useListPage('AdminContactMethods-1')

async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/api/admin/api/contact-methods', {
      params: {
        page: paging.page,
        size: paging.size,
        all: paging.all,
        query: query.value.trim() || undefined,
      },
    })
    rows.value = data.items || []
    total.value = Number(data.total || 0)
    paging.page = Number(data.page || 1)
    if (paging.all && total.value > 200) paging.all = false
  } catch (error) {
    if (paging.all) {
      paging.all = false
      return load()
    }
    ElMessage.error(getHttpErrorMessage(error, '联系方式加载失败'))
  } finally {
    loading.value = false
  }
}

function search() {
  paging.page = 1
  void load()
}

function openCreate() {
  editingId.value = null
  form.channel = ''
  form.number = ''
  dialogVisible.value = true
}

function openEdit(row: ContactMethod) {
  editingId.value = row.id
  form.channel = row.channel
  form.number = row.number
  dialogVisible.value = true
}

async function save() {
  if (!form.channel.trim() || !form.number.trim()) {
    ElMessage.warning('请完整填写渠道和号码')
    return
  }
  saving.value = true
  try {
    const payload = { channel: form.channel.trim(), number: form.number.trim() }
    if (editingId.value) await http.put(`/api/admin/api/contact-methods/${editingId.value}`, payload)
    else await http.post('/api/admin/api/contact-methods', payload)
    ElMessage.success(editingId.value ? '联系方式已更新' : '联系方式已添加')
    dialogVisible.value = false
    await load()
  } catch (error) {
    ElMessage.error(getHttpErrorMessage(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

async function remove(row: ContactMethod) {
  try {
    await ElMessageBox.confirm(`确定删除“${row.channel}：${row.number}”吗？`, '删除联系方式', { type: 'warning' })
    await http.delete(`/api/admin/api/contact-methods/${row.id}`)
    ElMessage.success('联系方式已删除')
    if (rows.value.length === 1 && paging.page > 1) paging.page--
    await load()
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(getHttpErrorMessage(error, '删除失败'))
  }
}

onMounted(load)
</script>

<style scoped>
.contact-admin { display: grid; gap: 20px; }
.contact-search { width: 240px; }
.contact-panel { padding: 20px; border: 1px solid #dce6f2; border-radius: 14px; background: #fff; box-shadow: 0 12px 28px rgba(51, 75, 105, .07); }
@media (max-width: 760px) {
  .contact-search { width: 100%; }
  .contact-panel { padding: 12px; }
}
</style>
