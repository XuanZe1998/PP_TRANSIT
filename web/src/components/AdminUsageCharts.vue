<template>
  <section class="usage-dashboard">
    <div class="usage-kpis">
      <article><span>Token</span><strong>{{ integer(metric(totals, 'total_tokens')) }}</strong></article>
      <article><span>模型成本</span><strong>{{ usd(metric(totals, 'cost_amount')) }}</strong></article>
      <article><span>销售额 / 总收益</span><strong>{{ usd(metric(totals, 'total_amount')) }}</strong></article>
      <article><span>利润</span><strong :class="{ loss: metric(totals, 'profit_amount') < 0 }">{{ usd(metric(totals, 'profit_amount')) }}</strong></article>
      <article><span>利润率</span><strong>{{ Number(metric(totals, 'profit_margin')).toFixed(1) }}%</strong></article>
    </div>

    <div class="usage-chart-toolbar">
      <div><strong>{{ title }}</strong><span>柱状图按日期堆叠，饼图展示区间模型占比</span></div>
      <el-radio-group v-model="measure" size="small">
        <el-radio-button v-for="item in measures" :key="item.value" :label="item.value">{{ item.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <div v-if="dailyByModel.length" class="usage-chart-grid">
      <article class="chart-panel">
        <header><strong>每日模型趋势</strong><span>最多展示前 8 个模型，其余合并为“其他”</span></header>
        <v-chart class="usage-echart" :option="barOption" autoresize />
      </article>
      <article class="chart-panel">
        <header><strong>模型占比</strong><span>{{ activeMeasure.label }}</span></header>
        <v-chart class="usage-echart" :option="pieOption" autoresize />
      </article>
    </div>
    <el-empty v-else description="当前筛选范围暂无模型调用数据" :image-size="72" />

    <div v-if="showTable && dailyByModel.length" class="usage-daily-table">
      <div class="usage-table-title"><strong>每日模型明细</strong><span>完整数据，不合并“其他”</span></div>
      <el-table :data="pagedRows" size="small" max-height="460">
        <el-table-column prop="usage_day" label="日期" min-width="115" />
        <el-table-column prop="model" label="模型" min-width="190" show-overflow-tooltip />
        <el-table-column prop="request_count" label="请求" width="90" />
        <el-table-column label="Token" width="130"><template #default="{ row }">{{ integer(metric(row, 'total_tokens')) }}</template></el-table-column>
        <el-table-column label="成本(USD)" width="130"><template #default="{ row }">{{ usd(metric(row, 'cost_amount')) }}</template></el-table-column>
        <el-table-column label="销售额(USD)" width="140"><template #default="{ row }">{{ usd(metric(row, 'total_amount')) }}</template></el-table-column>
        <el-table-column label="利润(USD)" width="130"><template #default="{ row }"><span :class="{ loss: metric(row, 'profit_amount') < 0 }">{{ usd(metric(row, 'profit_amount')) }}</span></template></el-table-column>
        <el-table-column label="利润率" width="100"><template #default="{ row }">{{ Number(metric(row, 'profit_margin')).toFixed(1) }}%</template></el-table-column>
      </el-table>
      <el-pagination v-model:current-page="tablePage" layout="total, prev, pager, next" :page-size="20" :total="dailyByModel.length" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = withDefaults(defineProps<{ data?: Record<string, any>; kpiTotals?: Record<string, any>; pieRows?: any[]; title?: string; showTable?: boolean }>(), {
  data: () => ({}), title: '模型用量与收益', showTable: false
})

const measures = [
  { label: 'Token', value: 'total_tokens' },
  { label: '成本', value: 'cost_amount' },
  { label: '销售额', value: 'total_amount' },
  { label: '利润', value: 'profit_amount' }
]
const palette = ['#1769ff', '#00a8c6', '#6559f5', '#2ab27b', '#ff9d2e', '#ef5da8', '#586b8c', '#00c2ff', '#94a3b8']
const measure = ref('total_tokens')
const tablePage = ref(1)
const activeMeasure = computed(() => measures.find(item => item.value === measure.value) || measures[0])
const totals = computed(() => props.kpiTotals || props.data?.totals || {})
const dailyByModel = computed(() => (props.data?.dailyByModel || []).map((row: any) => ({
  ...row, usage_day: String(row.usage_day ?? row.USAGE_DAY ?? ''), model: String(row.model ?? row.MODEL ?? 'unknown')
})))
const pagedRows = computed(() => dailyByModel.value.slice((tablePage.value - 1) * 20, tablePage.value * 20))

watch(() => props.data, () => { tablePage.value = 1 }, { deep: false })

function metric(row: any, key: string) { return Number(row?.[key] ?? row?.[key.toUpperCase()] ?? 0) }
function integer(value: number) { return Math.round(value || 0).toLocaleString('zh-CN') }
function usd(value: number) { return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 6 }).format((value || 0) / 10_000) }
function chartValue(value: number) { return measure.value === 'total_tokens' ? value : value / 10_000 }
function chartLabel(value: number) { return measure.value === 'total_tokens' ? integer(value) : `$${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 6 })}` }

const grouped = computed(() => {
  const totalsByModel = new Map<string, number>()
  for (const row of dailyByModel.value) totalsByModel.set(row.model, (totalsByModel.get(row.model) || 0) + metric(row, measure.value))
  const top = [...totalsByModel.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([name]) => name)
  const dates = [...new Set<string>(dailyByModel.value.map((row: any) => String(row.usage_day)))].sort()
  const names = totalsByModel.size > 8 ? [...top, '其他'] : top
  const valueFor = (date: string, name: string) => dailyByModel.value
    .filter((row: any) => row.usage_day === date && (name === '其他' ? !top.includes(row.model) : row.model === name))
    .reduce((sum: number, row: any) => sum + metric(row, measure.value), 0)
  return { dates, names, totalsByModel, top, valueFor }
})

const barOption = computed(() => ({
  color: palette,
  tooltip: { trigger: 'axis', valueFormatter: (value: number) => chartLabel(value) },
  legend: { type: 'scroll', bottom: 0, textStyle: { color: '#52627a' } },
  grid: { left: 54, right: 18, top: 24, bottom: 72 },
  xAxis: { type: 'category', data: grouped.value.dates.map(date => date.slice(5)), axisLine: { lineStyle: { color: '#c9d6eb' } } },
  yAxis: { type: 'value', splitLine: { lineStyle: { color: '#e8eef8' } } },
  series: grouped.value.names.map((name, index) => ({
    name, type: 'bar', stack: 'total', barMaxWidth: 38,
    itemStyle: { borderRadius: index === grouped.value.names.length - 1 ? [5, 5, 0, 0] : 0 },
    data: grouped.value.dates.map(date => chartValue(grouped.value.valueFor(date, name)))
  }))
}))

const pieData = computed(() => {
  const source = props.pieRows?.length ? props.pieRows : dailyByModel.value
  const totals = new Map<string, number>()
  source.forEach((row: any) => {
    const name = String(row.model ?? row.MODEL ?? 'unknown')
    totals.set(name, (totals.get(name) || 0) + metric(row, measure.value))
  })
  const top = [...totals.entries()].sort((a, b) => b[1] - a[1]).slice(0, 8).map(([name]) => name)
  const rows = top.map(name => ({ name, value: totals.get(name) || 0 }))
  const other = [...totals.entries()].filter(([name]) => !top.includes(name)).reduce((sum, [, value]) => sum + value, 0)
  if (other) rows.push({ name: '其他', value: other })
  return rows.map(row => ({ ...row, value: chartValue(row.value) }))
})

const pieOption = computed(() => ({
  color: palette,
  tooltip: { trigger: 'item', formatter: (item: any) => `${item.name}<br/>${chartLabel(item.value)} · ${item.percent}%` },
  legend: { type: 'scroll', bottom: 0, textStyle: { color: '#52627a' } },
  series: [{ type: 'pie', radius: ['46%', '70%'], center: ['50%', '43%'], avoidLabelOverlap: true,
    itemStyle: { borderColor: '#fff', borderWidth: 3 }, label: { formatter: '{b}\n{d}%' }, data: pieData.value }]
}))
</script>

<style scoped>
.usage-dashboard{display:grid;gap:16px}.usage-kpis{display:grid;grid-template-columns:repeat(5,minmax(0,1fr));gap:10px}.usage-kpis article{padding:14px;border:1px solid #d8e5f7;border-radius:12px;background:linear-gradient(145deg,rgba(255,255,255,.95),rgba(242,248,255,.88));box-shadow:0 10px 28px rgba(21,80,160,.06)}.usage-kpis span,.chart-panel header span,.usage-chart-toolbar span,.usage-table-title span{display:block;color:#71829b;font-size:12px}.usage-kpis strong{display:block;margin-top:8px;color:#11233f;font-family:"JetBrains Mono","Cascadia Code",monospace;font-size:20px}.usage-chart-toolbar,.usage-table-title{display:flex;align-items:center;justify-content:space-between;gap:14px}.usage-chart-toolbar>div>strong,.usage-table-title strong{color:#162b49}.usage-chart-toolbar>div>span{margin-top:4px}.usage-chart-grid{display:grid;grid-template-columns:minmax(0,1.45fr) minmax(320px,.75fr);gap:14px}.chart-panel{min-width:0;padding:14px;border:1px solid #dbe7f8;border-radius:14px;background:rgba(255,255,255,.88)}.chart-panel header{display:flex;align-items:flex-start;justify-content:space-between;gap:10px}.usage-echart{height:330px}.usage-daily-table{display:grid;gap:12px}.usage-daily-table .el-pagination{justify-content:flex-end}.loss{color:#dc3b5d!important}@media(max-width:1100px){.usage-kpis{grid-template-columns:repeat(3,minmax(0,1fr))}.usage-chart-grid{grid-template-columns:1fr}}@media(max-width:680px){.usage-kpis{grid-template-columns:1fr 1fr}.usage-chart-toolbar{align-items:stretch;flex-direction:column}.usage-echart{height:290px}}@media(max-width:430px){.usage-kpis{grid-template-columns:1fr}}
</style>
