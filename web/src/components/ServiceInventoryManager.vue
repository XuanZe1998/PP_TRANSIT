<template>
  <section class="inventory-manager">
    <div class="inventory-header"><h3>{{ service.name }} · 卡密管理</h3><el-tag>{{ isLocal ? '本站库存' : '上游自动采购' }}</el-tag></div>
    <template v-if="!isLocal">
      <el-alert title="卡密由上游采购并发货，不使用本站卡密库存。请通过服务订单查看采购和发货结果。" type="info" :closable="false" show-icon />
      <el-button @click="$emit('orders', service.id)">查看该服务订单</el-button>
    </template>
    <template v-else>
      <div class="inventory-stats">
        <el-button v-for="item in inventoryStatuses" :key="item.value" :type="status === item.value ? 'primary' : 'default'" @click="setStatus(item.value)">{{ item.label }} {{ stats[item.value] || 0 }}</el-button>
        <el-button @click="setStatus('')">全部状态</el-button>
      </div>
      <el-alert title="预留中为待支付订单占用，不可删除；已售为已发货，不代表已在兑换站使用。列表默认掩码，主动查看的明文将在 30 秒后清除。" type="info" :closable="false" />
      <div class="inventory-filters">
        <el-select :model-value="status" aria-label="卡密状态" @change="setStatus"><el-option label="全部状态" value="" /><el-option v-for="item in inventoryStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select>
        <el-input v-model="searchText" maxlength="160" clearable placeholder="搜索卡密 ID、掩码尾号或订单号（勿输入完整卡密）" @keyup.enter="search" @clear="search" />
        <el-button @click="search">搜索</el-button><el-button :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-collapse><el-collapse-item title="新增卡密／批量导入" name="import">
        <el-input v-model="importText" type="textarea" :rows="5" autocomplete="off" placeholder="输入一条卡密，或批量粘贴；逗号、中文逗号、顿号、空格和换行均为分隔符。" />
        <div class="inventory-import"><span>识别 {{ tokens.length }} 条，去重后 {{ recognized }} 条；每批最多 10000 条</span><el-button type="success" :loading="busy" :disabled="!recognized || busy" @click="importCards">导入卡密</el-button></div>
      </el-collapse-item></el-collapse>
      <div><el-button type="danger" :disabled="!selectedIds.length || busy || loading" @click="deleteCards(selectedIds)">批量删除未售（{{ selectedIds.length }}）</el-button></div>
      <PagedTable :key="selectionKey" v-loading="loading" :data="rows" row-key="id" empty-text="暂无匹配的卡密" list-id="ServiceInventoryManager-Cards" pagination="external" @selection-change="selectRows">
        <el-table-column type="selection" width="44" :selectable="selectable" />
        <el-table-column prop="id" label="ID" width="85" />
        <el-table-column label="卡密掩码" min-width="155"><template #default="{ row }"><span>{{ row.secretPreview || '****' }}</span><div v-if="revealedId === row.id" class="revealed-secret">{{ revealedContent }}<el-button link @click="clearSecret">隐藏</el-button></div></template></el-table-column>
        <el-table-column label="状态" min-width="120"><template #default="{ row }"><el-tag :type="row.status === 'AVAILABLE' ? 'success' : row.status === 'RESERVED' ? 'warning' : 'info'">{{ inventoryStatuses.find(item => item.value === row.status)?.label || row.status }}</el-tag></template></el-table-column>
        <el-table-column label="关联订单" min-width="180"><template #default="{ row }"><el-button v-if="row.orderNo" link type="primary" @click="$emit('order', row.reservedOrderId)">{{ row.orderNo }}</el-button><span v-else>{{ row.reservedOrderId ? '订单不存在' : '—' }}</span></template></el-table-column>
        <el-table-column label="购买用户 ID" width="115"><template #default="{ row }">{{ row.buyerUserId ?? '—' }}</template></el-table-column>
        <el-table-column label="导入时间" min-width="170"><template #default="{ row }">{{ row.createdAt || '—' }}</template></el-table-column>
        <el-table-column label="预留到期时间" min-width="170"><template #default="{ row }">{{ row.reservedUntil || '—' }}</template></el-table-column>
        <el-table-column label="发货时间" min-width="170"><template #default="{ row }">{{ row.deliveredAt || '—' }}</template></el-table-column>
        <el-table-column label="操作" width="235" fixed="right"><template #default="{ row }">
          <el-button link :disabled="busy || loading" @click="revealCard(row)">查看</el-button><el-button link :disabled="busy || loading" @click="revealCard(row, true)">复制</el-button>
          <el-button v-if="row.status === 'AVAILABLE'" link type="primary" :disabled="busy || loading" @click="replaceCard(row)">替换</el-button>
          <el-button v-if="row.status === 'AVAILABLE'" link type="danger" :disabled="busy || loading" @click="deleteCards([row.id])">删除</el-button>
        </template></el-table-column>
      </PagedTable>
      <ListPagination v-model:page="page" v-model:size="size" :total="total" :allow-all="false" @change="changePage" />
    </template>
  </section>
</template>
<script setup lang="ts">
import { toRef } from 'vue'
import PagedTable from './PagedTable.vue'
import ListPagination from './ListPagination.vue'
import { inventoryStatuses, useServiceInventory, type InventoryService, type InventoryRow } from '@/composables/useServiceInventory'
const props = defineProps<{ service: InventoryService }>()
defineEmits<{ order: [id: number]; orders: [serviceId: number] }>()
const { rows, stats, page, size, total, status, searchText, loading, busy, error, importText, recognized, tokens,
  selectedIds, selectionKey, revealedId, revealedContent, isLocal, clearSecret, selectRows, load, setStatus, search, changePage, importCards, deleteCards, replaceCard, revealCard } = useServiceInventory(toRef(props, 'service'))
const selectable = (row: InventoryRow) => row.status === 'AVAILABLE' && !busy.value && !loading.value
</script>
<style scoped>
.inventory-manager{display:grid;grid-template-columns:minmax(0,1fr);gap:16px;min-width:0}.inventory-header,.inventory-stats,.inventory-filters,.inventory-import{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.inventory-header h3{margin:0}.inventory-filters .el-select{width:160px}.inventory-filters .el-input{flex:1;min-width:220px}.inventory-import{justify-content:space-between;margin-top:10px}.inventory-import span{font-size:12px;color:#64748b}.revealed-secret{overflow-wrap:anywhere;white-space:pre-wrap;margin-top:8px;color:#b45309}@media(max-width:640px){.inventory-filters .el-input{min-width:100%;order:-1}}
</style>
