<template>
  <section>
    <p>价格按商品币种设置。代理售价须介于底价与公开售价之间。</p>
    <el-form inline @submit.prevent="search">
      <el-form-item><el-input v-model="query" placeholder="搜索订阅商品" clearable @clear="search" /></el-form-item>
      <el-button native-type="submit">查询</el-button>
    </el-form>
    <PagedTable v-loading="loading" :data="rows" :list-id="admin ? 'agent-subscription-base-prices' : 'agent-subscription-retail-prices'" pagination="external">
      <el-table-column prop="name" label="订阅商品" min-width="155" />
      <el-table-column label="公开售价" min-width="100"><template #default="{row}">{{ money(row.price_cents, row.currency) }}</template></el-table-column>
      <el-table-column label="代理底价" min-width="100"><template #default="{row}">{{ money(row.base_cents, row.currency) }}</template></el-table-column>
      <el-table-column label="代理售价" min-width="100"><template #default="{row}">{{ money(row.retail_cents, row.currency) }}</template></el-table-column>
      <el-table-column label="操作" min-width="150"><template #default="{row}">
        <el-button link type="primary" :disabled="saving || (!admin && row.base_cents == null)" @click="edit(row)">{{ admin ? '设置底价' : '设置售价' }}</el-button>
        <el-button v-if="!admin && row.retail_cents != null" link :disabled="saving" @click="clear(row)">关闭代理价</el-button>
      </template></el-table-column>
    </PagedTable>
    <ListPagination v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="load" />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import PagedTable from '@/components/PagedTable.vue'
import ListPagination from '@/components/ListPagination.vue'
import http, { getHttpErrorMessage } from '@/utils/http'

type PriceRow = { id: number; name: string; currency: string; price_cents: number; base_cents: number | null; retail_cents: number | null; version: number }
const props = defineProps<{ endpoint: string; admin?: boolean }>()
const rows = ref<PriceRow[]>([])
const page = ref(1), size = ref(10), total = ref(0), query = ref('')
const loading = ref(false), saving = ref(false)
let generation = 0
const money = (cents: number | null, currency: string) => cents == null ? '未设置' : `${currency} ${(cents / 100).toFixed(2)}`

async function load() {
  const current = ++generation
  loading.value = true
  try {
    const { data } = await http.get(props.endpoint, { params: { page: page.value, size: size.value, query: query.value } })
    if (generation !== current) return
    rows.value = data.items
    total.value = data.total
  } catch (error) {
    if (generation === current) ElMessage.error(getHttpErrorMessage(error, '订阅价格加载失败'))
  } finally {
    if (generation === current) loading.value = false
  }
}
function search() { page.value = 1; void load() }
async function save(row: PriceRow, cents: number | null) {
  if (saving.value) return
  saving.value = true
  try {
    await http.put(`${props.endpoint}/${row.id}`, { cents, version: row.version })
    ElMessage.success('代理价格已保存')
  } catch (error) { ElMessage.error(getHttpErrorMessage(error, '价格保存失败')) }
  finally { await load(); saving.value = false }
}
async function edit(row: PriceRow) {
  const current = props.admin ? row.base_cents : row.retail_cents ?? row.base_cents
  const result = await ElMessageBox.prompt(`输入 ${row.name} 的价格（${row.currency}）`, props.admin ? '设置代理底价' : '设置代理售价', {
    inputValue: current == null ? '' : (current / 100).toFixed(2),
    inputPattern: /^\d{1,10}(\.\d{1,2})?$/,
    inputErrorMessage: '请输入金额，最多两位小数'
  }).catch(() => null)
  if (result) await save(row, Math.round(Number(result.value) * 100))
}
async function clear(row: PriceRow) {
  const confirmed = await ElMessageBox.confirm('关闭后，分享链接中的该商品将按公开价格显示。', '关闭代理售价').then(() => true, () => false)
  if (confirmed) await save(row, null)
}
onMounted(load)
</script>
