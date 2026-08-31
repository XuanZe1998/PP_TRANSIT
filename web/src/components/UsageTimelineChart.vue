<template>
  <v-chart class="usage-timeline-chart" :option="option" autoresize />
</template>

<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { DataZoomComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, DataZoomComponent, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = withDefaults(defineProps<{ daily?: any[]; days?: number; visibleDays?: number }>(), {
  daily: () => [], days: 90, visibleDays: 14
})

function metric(row: any, key: string) {
  return Number(row?.[key] ?? row?.[key.toUpperCase()] ?? 0)
}

function localDate(value: Date) {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const timeline = computed(() => {
  const byDate = new Map((props.daily || []).map(row => [String(row.usage_day ?? row.USAGE_DAY ?? ''), row]))
  const dates = Array.from({ length: props.days }, (_, index) => {
    const date = new Date()
    date.setHours(12, 0, 0, 0)
    date.setDate(date.getDate() - (props.days - index - 1))
    return localDate(date)
  })
  return { dates, rows: dates.map(date => byDate.get(date) || {}) }
})

const option = computed(() => {
  const { dates, rows } = timeline.value
  const startIndex = Math.max(0, dates.length - props.visibleDays)
  const series = [
    { name: '输入未命中', key: 'cache_miss_tokens', color: '#1769ff' },
    { name: '缓存命中', key: 'cache_read_tokens', color: '#19b28b' },
    { name: '缓存写入', key: 'cache_write_tokens', color: '#8b5cf6' },
    { name: '输出', key: 'completion_tokens', color: '#f59e0b' }
  ]
  return {
    animationDuration: 350,
    color: series.map(item => item.color),
    grid: { left: 54, right: 22, top: 42, bottom: 74 },
    legend: { top: 4, textStyle: { color: '#52627a' } },
    tooltip: {
      trigger: 'axis',
      valueFormatter: (value: number) => `${Math.round(Number(value || 0)).toLocaleString('zh-CN')} Token`
    },
    xAxis: { type: 'category', data: dates, axisLabel: { formatter: (value: string) => value.slice(5), color: '#71829b' } },
    yAxis: { type: 'value', axisLabel: { color: '#71829b' }, splitLine: { lineStyle: { color: '#e8eef6' } } },
    dataZoom: [
      { type: 'inside', startValue: dates[startIndex], endValue: dates[dates.length - 1], zoomOnMouseWheel: true, moveOnMouseMove: true, moveOnMouseWheel: true },
      { type: 'slider', startValue: dates[startIndex], endValue: dates[dates.length - 1], height: 22, bottom: 14, brushSelect: false }
    ],
    series: series.map(item => ({
      name: item.name, type: 'bar', stack: 'tokens', barMaxWidth: 28,
      emphasis: { focus: 'series' }, data: rows.map(row => metric(row, item.key))
    }))
  }
})
</script>

<style scoped>
.usage-timeline-chart { width: 100%; height: 320px; }
</style>
