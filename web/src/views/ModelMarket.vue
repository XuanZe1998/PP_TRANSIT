<template>
  <div class="shell-page market-page">
    <section class="market-head shell-section">
      <div>
        <h1>模型与供应商目录</h1>
        <p>把公开模型名、底层供应商、接入方式拆开管理，避免前台业务方直接依赖具体厂商。</p>
      </div>
      <div class="market-filters">
        <el-input v-model="searchQuery" placeholder="搜索公开模型名" clearable @input="handleSearch" />
        <el-select v-model="typeFilter" clearable placeholder="供应商类型" @change="onFilterChange">
          <el-option label="OpenAI" value="openai" />
          <el-option label="Anthropic" value="anthropic" />
          <el-option label="Gemini" value="gemini" />
          <el-option label="DeepSeek" value="deepseek" />
          <el-option label="xAI" value="xai" />
        </el-select>
      </div>
    </section>

    <section class="catalog-layout">
      <div class="provider-panel shell-section">
        <div class="panel-head">
          <h2 class="section-title">推荐接入供应商</h2>
          <p class="section-subtitle">后端已内置不同供应商协议的适配层。</p>
        </div>
        <div class="provider-card-list">
          <article v-for="provider in providers" :key="provider.provider" class="provider-card">
            <div class="provider-card-top">
              <div>
                <h3>{{ provider.provider }}</h3>
                <p>{{ provider.headline }}</p>
              </div>
              <el-tag>{{ provider.providerType }}</el-tag>
            </div>
            <div class="provider-meta">
              <span>接口形态</span>
              <strong>{{ provider.endpointStyle }}</strong>
            </div>
            <div class="provider-meta">
              <span>建议 Base URL</span>
              <strong>{{ provider.recommendedBaseUrl }}</strong>
            </div>
            <div class="chip-row">
              <el-tag v-for="family in provider.modelFamilies" :key="family" effect="plain">{{ family }}</el-tag>
            </div>
          </article>
        </div>
      </div>

      <div class="model-panel shell-section" v-loading="loading">
        <div class="panel-head">
          <h2 class="section-title">公开模型目录</h2>
          <p class="section-subtitle">对业务方暴露的统一模型名。</p>
        </div>
        <el-table :data="items" stripe>
          <el-table-column prop="publicName" label="公开模型名" min-width="240" />
          <el-table-column prop="type" label="供应商类型" width="160">
            <template #default="{ row }">
              <el-tag>{{ row.type || 'unknown' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="建议用途" min-width="220">
            <template #default="{ row }">
              {{ modelUseHint(row.publicName, row.type) }}
            </template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            background
            layout="prev, pager, next, total"
            :current-page="page"
            :page-size="size"
            :total="total"
            @current-change="onPageChange"
          />
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import http from '@/utils/http'

type ProviderCatalogItem = {
  provider: string
  providerType: string
  headline: string
  endpointStyle: string
  recommendedBaseUrl: string
  modelFamilies: string[]
}

const loading = ref(false)
const searchQuery = ref('')
const items = ref<{ publicName: string; type: string }[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(12)
const typeFilter = ref<string | undefined>(undefined)
const providers = ref<ProviderCatalogItem[]>([])

const onPageChange = (p: number) => {
  page.value = p
  fetchModels()
}

const fetchModels = async () => {
  loading.value = true
  try {
    const res = await http.get('/api/public/models', {
      params: { page: page.value, size: size.value, query: searchQuery.value || undefined, type: typeFilter.value, sort: 'name' }
    })
    items.value = res.data.items || []
    total.value = res.data.total || 0
  } catch {
    items.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  Promise.all([
    fetchModels(),
    http.get('/api/ops/catalog').then(res => {
      providers.value = res.data
    })
  ]).catch(() => {})
})

let searchTimer: number | undefined
const handleSearch = () => {
  if (searchTimer) window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => {
    page.value = 1
    fetchModels()
  }, 300)
}

const onFilterChange = () => {
  page.value = 1
  fetchModels()
}

const modelUseHint = (model: string, type: string) => {
  const lower = `${model} ${type}`.toLowerCase()
  if (lower.includes('reason')) return '推理、复杂分析、长链路任务'
  if (lower.includes('flash') || lower.includes('mini') || lower.includes('nano')) return '轻量实时调用'
  if (lower.includes('opus') || lower.includes('gpt-5')) return '高质量主路径'
  return '通用对话与文本生成'
}
</script>

<style scoped>
.market-page {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.market-head {
  display: flex;
  justify-content: space-between;
  align-items: end;
  gap: 20px;
  padding: 28px 32px;
  border-radius: 8px;
}

.market-head h1 {
  margin: 0;
  font-size: 30px;
  color: #0f172a;
}

.market-head p {
  margin: 10px 0 0;
  color: #64748b;
}

.market-filters {
  display: grid;
  grid-template-columns: minmax(260px, 360px) 180px;
  gap: 12px;
}

.catalog-layout {
  display: grid;
  grid-template-columns: minmax(340px, 0.9fr) minmax(0, 1.3fr);
  gap: 20px;
}

.provider-panel,
.model-panel {
  padding: 28px;
  border-radius: 8px;
}

.provider-card-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.provider-card {
  padding: 18px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.provider-card-top,
.provider-meta {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
}

.provider-card h3 {
  margin: 0;
  font-size: 18px;
}

.provider-card p {
  margin: 8px 0 0;
  color: #64748b;
  font-size: 14px;
}

.provider-meta {
  margin-top: 12px;
  color: #64748b;
  font-size: 13px;
}

.provider-meta strong {
  color: #0f172a;
  text-align: right;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.pager {
  display: flex;
  justify-content: center;
  margin-top: 24px;
}

@media (max-width: 1080px) {
  .catalog-layout,
  .market-head {
    grid-template-columns: minmax(0, 1fr);
    display: grid;
  }

  .market-filters {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
