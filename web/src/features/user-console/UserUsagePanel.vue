<template>
        <section class="console-panel" v-loading="billingLoading">
          <div class="panel-head">
            <div>
              <h2>模型账单明细</h2>
              <p>按模型、Key 和日期聚合输入、输出、缓存命中 token 及扣费。</p>
            </div>
            <el-button @click="loadBilling">刷新</el-button>
          </div>
          <div class="usage-filter-row">
            <el-date-picker v-model="usageRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" @change="applyBillingFilters" />
            <el-select v-model="usageModel" clearable placeholder="全部模型" @change="applyBillingFilters">
              <el-option v-for="model in props.models" :key="model" :label="model" :value="model" />
            </el-select>
          </div>
          <div v-if="billingDataError" class="module-error-state">
            <span>{{ billingDataError }}</span>
            <el-button link type="primary" @click="loadBilling">重新加载账单</el-button>
          </div>
          <div v-if="usageAnalyticsError" class="module-error-state">
            <span>{{ usageAnalyticsError }}</span>
            <el-button link type="primary" @click="loadUsageAnalytics">重新加载图表</el-button>
          </div>
          <div v-if="!usageAnalyticsError" class="usage-visual-grid" v-loading="usageAnalyticsLoading">
            <article class="usage-visual-card usage-bars-card">
              <header><strong>单日 Token 消耗</strong><span>堆叠显示输入未命中、缓存命中/写入和输出</span></header>
              <div class="usage-stacked-chart">
                <div v-for="day in usageDays" :key="day.date" class="usage-day-column">
                  <div class="usage-day-bar" :title="`${day.date} · ${number(day.total)} Token`" :style="{ height: `${day.height}%` }">
                    <i v-for="part in day.parts" :key="part.name" :class="part.className" :style="{ flexGrow: part.value || 0 }"></i>
                  </div>
                  <small>{{ day.date.slice(5) }}</small>
                </div>
              </div>
            </article>
            <article class="usage-visual-card usage-pie-card">
              <header><strong>Token 构成</strong><span>当前筛选范围</span></header>
              <div class="usage-pie-wrap"><div class="usage-pie" :style="usagePieStyle"><b>{{ compactNumber(usageTotalTokens) }}</b><small>Token</small></div>
                <ul><li v-for="slice in usageComposition" :key="slice.name"><i :style="{ background: slice.color }"></i><span>{{ slice.name }}</span><b>{{ number(slice.value) }}</b></li></ul>
              </div>
              <footer>模型消费总额 <strong>{{ formatUsd(usageMetric(usageAnalytics.totals, 'total_amount')) }}</strong></footer>
            </article>
          </div>
          <div class="usage-table-scroll" tabindex="0" aria-label="账单汇总横向滚动区域">
          <PagedTable :data="billingSummary" border scrollbar-always-on style="min-width: 1260px" list-id="UserConsole-2">
            <el-table-column prop="model" label="模型" min-width="180" />
            <el-table-column prop="request_count" label="请求" width="90" />
            <el-table-column prop="prompt_tokens" label="输入 Token" width="130" />
            <el-table-column prop="completion_tokens" label="输出 Token" width="130" />
            <el-table-column prop="cached_tokens" label="缓存 Token" width="130" />
            <el-table-column label="输入费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.input_amount) }}</template>
            </el-table-column>
            <el-table-column label="输出费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.output_amount) }}</template>
            </el-table-column>
            <el-table-column label="缓存费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.cached_amount) }}</template>
            </el-table-column>
            <el-table-column label="总费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.total_amount) }}</template>
            </el-table-column>
          </PagedTable>
          </div>

          <div class="usage-table-scroll billing-detail-table" tabindex="0" aria-label="用量明细横向滚动区域">
          <PagedTable :data="billingRows" border scrollbar-always-on style="min-width: 1390px" list-id="UserConsole-3" pagination="external">
            <el-table-column prop="created_at" label="时间" min-width="170" />
            <el-table-column prop="trace_id" label="Trace ID" min-width="150" />
            <el-table-column prop="token_name" label="Key" min-width="140" />
            <el-table-column prop="model" label="模型" min-width="160" />
            <el-table-column prop="prompt_tokens" label="输入" width="90" />
            <el-table-column prop="completion_tokens" label="输出" width="90" />
            <el-table-column prop="cached_tokens" label="缓存" width="90" />
            <el-table-column label="模型费用(USD)" width="140">
              <template #default="{ row }">{{ formatUsd(row.total_amount) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="error_message" label="错误" min-width="220" />
          </PagedTable>
          </div>
          <SelectablePagination
            v-model:current-page="billingPage"
            v-model:page-size="billingPageSize"
            class="billing-pagination"
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="billingTotal"
            @current-change="loadBilling"
            @size-change="handleBillingPageSizeChange"
           list-id="UserConsole-1"/>
        </section>


</template>
<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue';

import SelectablePagination from '@/components/SelectablePagination.vue';
import { formatUsd } from '@/utils/money';
import { number, compactNumber } from './format';

import type { useUserUsage } from './useUserUsage';
const props = defineProps<{ state: ReturnType<typeof useUserUsage>; models: string[] }>()
const {
  billingRows,
  billingSummary,
  billingPage,
  billingPageSize,
  billingTotal,
  usageAnalytics,
  billingLoading,
  billingDataError,
  usageAnalyticsLoading,
  usageAnalyticsError,
  usageRange,
  usageModel,
  loadBilling,
  applyBillingFilters,
  handleBillingPageSizeChange,
  loadUsageAnalytics,
  usageMetric,
  usageDays,
  usageComposition,
  usageTotalTokens,
  usagePieStyle,
} = props.state
</script>
