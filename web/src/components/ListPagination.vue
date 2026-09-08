<template>
  <div class="list-pagination">
    <span>共 {{ total }} 条</span>
    <el-select :model-value="all ? 'all' : size" style="width: 125px" @change="resize">
      <el-option v-for="n in [10,20,50,100]" :key="n" :value="n" :label="`${n} 条 / 页`" />
      <el-option v-if="total <= 200" value="all" label="显示全部" />
    </el-select>
    <el-pagination v-if="!all" :current-page="page" :page-size="size" :total="total" layout="prev, pager, next" @update:current-page="changePage" />
  </div>
</template>
<script setup lang="ts">
const props = defineProps<{ total: number; page: number; size: number; all?: boolean }>()
const emit = defineEmits<{ 'update:page': [number]; 'update:size': [number]; 'update:all': [boolean]; change: [] }>()
function resize(value: number | string) { emit('update:all', value === 'all'); if (typeof value === 'number') emit('update:size', value); emit('update:page', 1); emit('change') }
function changePage(page: number) { emit('update:page', page); emit('change') }
</script>
<style scoped>.list-pagination { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:12px; padding:16px 0; color:var(--el-text-color-secondary); }</style>
