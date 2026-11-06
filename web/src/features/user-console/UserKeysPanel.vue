<template>
        <section class="console-panel">
          <div class="panel-head">
            <div>
              <h2>Key 列表</h2>
              <p>按环境拆分 Key，控制额度、模型范围和启停状态。</p>
            </div>
            <el-button type="primary" @click="openCreateKey">新建 Key</el-button>
          </div>
          <PagedTable :data="keys" border list-id="UserConsole-1">
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="key" label="Key" min-width="220" />
            <el-table-column prop="quota" label="额度" min-width="140" />
            <el-table-column prop="models" label="模型范围" min-width="170" />
            <el-table-column prop="lastUsed" label="最近调用" min-width="140" />
            <el-table-column prop="status" label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === '启用' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </PagedTable>
        </section>


</template>
<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue';

import type { useUserKeys } from './useUserKeys';
const props = defineProps<{ state: ReturnType<typeof useUserKeys> }>()
const {
  keys,
  openCreateKey,
} = props.state
</script>
