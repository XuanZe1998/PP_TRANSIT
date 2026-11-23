<template>
  <el-button @click="open">接入上游</el-button>
  <el-dialog v-model="visible" title="接入上游" width="min(720px, 94vw)"
    :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @closed="reset">
    <el-steps :active="preview ? 1 : 0" simple style="margin-bottom: 20px">
    <el-step title="填写地址和 Key" /><el-step title="选择要导入的模型" />
    </el-steps>
    <el-form v-if="!preview" label-position="top" @submit.prevent="readModels">
      <el-form-item label="归属站点（留空创建新站点）"><el-select v-model="siteId" clearable filterable remote :remote-method="searchSites" @visible-change="(shown: boolean) => shown && searchSites('')"><el-option v-for="site in sites" :key="site.id" :value="site.id" :label="site.name"/></el-select><ListPagination v-if="siteTotal > siteSize || sitePage > 1 || siteSize !== 10" v-model:page="sitePage" v-model:size="siteSize" :total="siteTotal" :allow-all="false" @change="loadSites"/></el-form-item>
      <el-form-item label="站点地址">
        <el-input v-model="baseUrl" placeholder="https://example.com（也支持 /v1 地址）" :disabled="busy" />
      </el-form-item>
      <el-form-item label="上游推理 API Key">
        <el-input v-model="apiKey" type="password" show-password autocomplete="off" :disabled="busy" />
      </el-form-item>
      <p>使用上游 API Key 读取 /v1/models，选择模型后手工配置价格并发布。</p>
    </el-form>
    <template v-else>
      <p>{{ preview.baseUrl }} · 读取到 {{ preview.models.length }} 个模型</p>
      <el-form label-position="top">
        <el-form-item label="渠道名称"><el-input v-model="name" maxlength="100" :disabled="busy" /></el-form-item>
        <el-form-item label="模型">
          <el-select v-model="selected" multiple filterable placeholder="选择要导入的模型" style="width: 100%"
            collapse-tags collapse-tags-tooltip :max-collapse-tags="6" :disabled="busy">
            <el-option v-for="model in preview.models" :key="model" :label="model" :value="model" />
          </el-select>
        </el-form-item>
      </el-form>
      <el-button size="small" :disabled="busy" @click="selected = [...preview.models]">全选</el-button>
      <el-button size="small" :disabled="busy" @click="selected = []">清空</el-button>
      <p>已选 {{ selected.length }} 个模型</p>
      <el-alert v-if="tooLong" type="warning" :closable="false" title="所选模型名称总长度超过渠道容量，请减少选择或分批接入。" />
    </template>
    <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top: 12px" />
    <template #footer>
      <el-button :disabled="busy" @click="visible = false">取消</el-button>
      <el-button v-if="preview" :disabled="busy" @click="preview = null; error = ''">上一步</el-button>
      <el-button v-if="!preview" type="primary" :loading="busy" :disabled="!baseUrl.trim() || !apiKey.trim()" @click="readModels">读取模型</el-button>
      <el-button v-else type="primary" :loading="busy" :disabled="!selected.length || tooLong" @click="connect">导入模型</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import ListPagination from '@/components/ListPagination.vue'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '../utils/http'

type Preview = { baseUrl: string; suggestedName: string; models: string[] }
const emit = defineEmits<{ connected: [] }>()
const visible = ref(false)
const busy = ref(false)
const baseUrl = ref('')
const apiKey = ref('')
const endpoint = '/api/admin/api/gateway/onboard/openai-compatible'
const siteId=ref<number>()
const sites = ref<{ id: number; name: string }[]>([])
const sitePage = ref(1)
const siteSize = ref(10)
const siteTotal = ref(0)
const siteQuery = ref('')
let siteRequest = 0
function searchSites(query: string) {
  sitePage.value = 1
  siteQuery.value = query
  return loadSites()
}
async function loadSites() {
  const request = ++siteRequest
  try {
    const { data } = await http.get('/api/admin/api/gateway/sites', { params: {
      adapter: 'openai-compatible', query: siteQuery.value, page: sitePage.value, size: siteSize.value
    } })
    if (request !== siteRequest) return
    sites.value = data.items
    siteTotal.value = data.total
  } catch (cause) {
    if (request === siteRequest) error.value = getHttpErrorMessage(cause, '站点加载失败')
  }
}
const name = ref('')
const selected = ref<string[]>([])
const preview = ref<Preview | null>(null)
const error = ref('')
const tooLong = computed(() => selected.value.length > 500 || selected.value.join('\n').length > 2000)
function reset() {
  siteRequest++
  sites.value = []
  siteTotal.value = 0
  siteSize.value = 10
  sitePage.value = 1
  siteId.value = undefined
  siteQuery.value = ''
  baseUrl.value = ''
  apiKey.value = ''
  name.value = ''
  selected.value = []
  preview.value = null
  error.value = ''
}
function open() { reset(); visible.value = true }
function requestData() {
  return { siteId: siteId.value, baseUrl: baseUrl.value, apiKey: apiKey.value }
}
async function readModels() {
  busy.value = true; error.value = ''
  try {
    const { data } = await http.post<Preview>(endpoint + '/preview', requestData(), { timeout: 50_000 })
    preview.value = data
    name.value = data.suggestedName
    selected.value = data.models.length <= 500 && data.models.join('\n').length <= 2000 ? [...data.models] : []
  } catch (e) { error.value = getHttpErrorMessage(e, '读取模型失败') }
  finally { busy.value = false }
}
async function connect() {
  busy.value = true; error.value = ''
  try {
    await http.post(endpoint + '/connect', {
      ...requestData(), name: name.value, models: selected.value
    }, { timeout: 60_000 })
    apiKey.value = ''
    visible.value = false
    ElMessage.success('模型已导入，请配置价格后发布')
    emit('connected')
  } catch (e) { error.value = getHttpErrorMessage(e, '导入失败，请刷新渠道列表确认结果后重试') }
  finally { busy.value = false }
}
</script>
