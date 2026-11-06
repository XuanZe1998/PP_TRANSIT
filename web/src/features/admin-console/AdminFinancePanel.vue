<template>

      <div v-if="financeErrors.summary" class="module-error-state">
        <span>{{ financeErrors.summary }}</span>
        <el-button link type="primary" @click="loadFinanceSection('summary')">重试财务汇总</el-button>
      </div>
      <section class="admin-grid">
        <article class="panel wide-panel">
          <div class="panel-head">
            <h3>钱包流水</h3>
            <el-button type="primary" :icon="Plus" @click="emit('create')">生成兑换码</el-button>
          </div>
          <div v-if="financeErrors.transactions" class="module-error-state">
            <span>{{ financeErrors.transactions }}</span>
            <el-button link type="primary" @click="loadFinanceSection('transactions')">重试</el-button>
          </div>
          <PagedTable :data="rows" v-loading="props.loading" list-id="AdminConsole-2" pagination="external">
            <el-table-column prop="username" label="用户" min-width="120" />
            <el-table-column prop="type" label="类型" width="120" />
            <el-table-column label="金额(CNY)" width="150">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="余额(CNY)" width="150">
              <template #default="{ row }">{{ money(row.balance_after) }}</template>
            </el-table-column>
            <el-table-column prop="channel" label="渠道" width="120" />
            <el-table-column prop="remark" label="备注" min-width="180" />
            <el-table-column prop="created_at" label="时间" min-width="180" />
          </PagedTable>
          <SelectablePagination
            v-model:current-page="financePage"
            v-model:page-size="financePageSize"
            class="admin-pagination"
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="financeTotal"
            @current-change="loadFinanceTransactions"
            @size-change="handleFinancePageSizeChange"
           list-id="AdminConsole-1"/>
        </article>
        <article class="panel">
          <div class="panel-head">
            <h3>兑换码</h3>
          </div>
          <div v-if="financeErrors.codes" class="module-error-state">
            <span>{{ financeErrors.codes }}</span>
            <el-button link type="primary" @click="loadFinanceSection('codes')">重试</el-button>
          </div>
          <PagedTable :data="secondaryRows" size="small" list-id="AdminConsole-3">
            <el-table-column prop="code" label="兑换码" min-width="150" />
            <el-table-column label="额度(CNY)" width="140">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="used_count" label="已用" width="90" />
            <el-table-column prop="max_uses" label="上限" width="90" />
          </PagedTable>
        </article>
        <article class="panel wide-panel">
          <div class="panel-head">
            <div><h3>充值套餐</h3><small>金额和赠送比例将作为订单快照保存</small></div>
            <el-button type="primary" @click="openRechargePlan()">新增套餐</el-button>
          </div>
          <div v-if="financeErrors.plans" class="module-error-state">
            <span>{{ financeErrors.plans }}</span>
            <el-button link type="primary" @click="loadFinanceSection('plans')">重试</el-button>
          </div>
          <PagedTable :data="rechargePlans" size="small" list-id="AdminConsole-4">
            <el-table-column prop="name" label="套餐" min-width="150" />
            <el-table-column label="售价(CNY)" width="150"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column prop="bonus_percent" label="赠送比例" width="120"><template #default="{ row }">{{ row.bonus_percent }}%</template></el-table-column>
            <el-table-column prop="sort_order" label="排序" width="90" />
            <el-table-column label="启用" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="140"><template #default="{ row }">
              <el-button link type="primary" @click="openRechargePlan(row)">编辑</el-button>
              <el-button link type="danger" @click="removeRechargePlan(row)">删除</el-button>
            </template></el-table-column>
          </PagedTable>
        </article>
      </section>
      <el-dialog v-model="rechargePlanVisible" :title="rechargePlanForm.id ? '编辑充值套餐' : '新增充值套餐'" width="480px">
        <el-form label-position="top">
          <el-form-item label="套餐名称"><el-input v-model="rechargePlanForm.name" maxlength="120" /></el-form-item>
          <el-form-item label="售价（内部单位，10,000 = ¥1）"><el-input-number v-model="rechargePlanForm.amount" :min="1" :step="10000" style="width:100%" /></el-form-item>
          <el-form-item label="赠送比例 %"><el-input-number v-model="rechargePlanForm.bonusPercent" :min="0" :max="1000" :precision="3" :step="0.001" style="width:100%" /><div class="field-hint">支持 0 或 0.001%–1000.000%，订单会保存当前比例快照。</div></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="rechargePlanForm.sortOrder" :min="0" style="width:100%" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="rechargePlanForm.enabled" /></el-form-item>
        </el-form>
        <template #footer><el-button @click="rechargePlanVisible=false">取消</el-button><el-button type="primary" :loading="rechargePlanSaving" @click="saveRechargePlan">保存</el-button></template>
      </el-dialog>

</template>
<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue';
import SelectablePagination from '@/components/SelectablePagination.vue';

import { Plus } from '@element-plus/icons-vue';
import { money } from './format';
import type { useAdminFinance } from './useAdminFinance';
const props = defineProps<{ state: ReturnType<typeof useAdminFinance>; loading: boolean }>()
const {
  rows,
  secondaryRows,
  rechargePlans,
  financeErrors,
  financePage,
  financePageSize,
  financeTotal,
  rechargePlanVisible,
  rechargePlanSaving,
  rechargePlanForm,
  loadFinanceSection,
  loadFinanceTransactions,
  handleFinancePageSizeChange,
  openRechargePlan,
  saveRechargePlan,
  removeRechargePlan,
} = props.state
const emit = defineEmits<{ create: [] }>()
</script>

<style scoped src="./panels.css"></style>
