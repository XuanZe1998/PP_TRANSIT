<template>

      <el-alert :type="legalStatus?.publication_ready ? 'success' : 'warning'" :closable="false" :title="legalStatus?.publication_ready ? '出海公开信息已配置并标记为审核通过' : '公开信息尚未配置完成：线上支付处于保护状态'">
        <template #default>
          请在下方“公开信息填写区”逐项填写；仍需核对：{{ missingLegalFields }}。
          完成真实信息、双语正文与法律审核并更新协议版本后，在下方“政策审核与发布”中显式批准。该开关对应 legal.publication_approved，不会自动开启收款。
        </template>
      </el-alert>
      <article class="panel legal-publication-control" style="margin: 16px 0">
        <div class="panel-head"><h3>政策审核与发布</h3><code>legal.publication_approved</code></div>
        <p>仅在真实经营信息、中英文政策及法律审核完成后批准。开启会将 legal.publication_approved 设置为 true；关闭设为 false。此操作不会开启下方的收款总开关。</p>
        <el-switch :model-value="legalStatus?.legalReviewRequired === false" :loading="legalApprovalSaving"
                   :disabled="!legalApprovalStatusLoaded || (legalStatus?.legalReviewRequired !== false && Boolean(legalStatus?.missing_fields?.length))"
                   active-text="已审核批准" inactive-text="未审核批准" @change="setLegalPublicationApproved(Boolean($event))" />
        <el-tag style="margin-left: 12px" :type="legalStatus?.publication_ready ? 'success' : 'warning'">{{ legalStatus?.publication_ready ? '正式政策就绪' : '政策未就绪' }}</el-tag>
        <p v-if="legalStatus?.missing_fields?.length" class="field-hint">必填字段尚未齐全，请先展开“公开信息填写区”补齐，再决定是否批准。</p>
      </article>
      <LegalSettingsEditor :settings="rows" :missing-fields="legalStatus?.missing_fields || []" @saved="handleLegalFieldSaved" />
      <article class="panel" style="margin: 16px 0">
        <div class="panel-head"><h3>线上收款总开关</h3></div>
        <p>由此开关单独控制是否允许发起付款，公开资料审核状态单独维护。关闭后不再生成新的支付链接，已经发出的链接及支付回调仍可能完成，请核对待处理订单。</p>
        <el-switch :model-value="legalStatus?.payments_enabled === true" :loading="paymentSwitchSaving" :disabled="!legalApprovalStatusLoaded" active-text="允许发起付款" inactive-text="禁止发起付款" @change="setPaymentsEnabled(Boolean($event))" />
        <el-tag style="margin-left: 12px" :type="legalStatus?.checkout_ready ? 'success' : 'warning'">{{ legalStatus?.checkout_ready ? '结账开放' : '结账关闭' }}</el-tag>
      </article>
      <section class="admin-grid">
        <article class="panel">
          <div class="panel-head">
            <h3>系统配置</h3>
            <el-button type="primary" :icon="Plus" @click="emit('create')">保存配置</el-button>
          </div>
          <p class="field-hint">表格内容仅显示单行摘要，超出部分隐藏；点击“编辑”查看或修改完整内容。</p>
          <PagedTable class="settings-table" table-layout="fixed" :data="filteredRows" v-loading="props.loading" list-id="AdminConsole-5">
            <el-table-column prop="setting_key" label="键" min-width="180"><template #default="{ row }"><span class="settings-cell-text">{{ row.setting_key }}</span></template></el-table-column>
            <el-table-column prop="setting_value" label="值" min-width="220"><template #default="{ row }"><span class="settings-cell-text">{{ row.setting_value }}</span></template></el-table-column>
            <el-table-column prop="description" label="说明" min-width="200"><template #default="{ row }"><span class="settings-cell-text">{{ row.description }}</span></template></el-table-column>
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" @click="emit('edit', row)">编辑</el-button>
              </template>
            </el-table-column>
          </PagedTable>
        </article>
        <article class="panel">
          <div class="panel-head">
            <h3>成本报表</h3>
            <el-tag>实时聚合</el-tag>
          </div>
          <div class="report-summary">
            <div><span>模型收入</span><strong>{{ report.revenue == null ? '—' : formatUsd(report.revenue) }}</strong></div>
            <div><span>模型成本</span><strong>{{ report.cost == null ? '—' : formatUsd(report.cost) }}</strong></div>
            <div><span>毛利率</span><strong>{{ percent(report.grossMargin) }}</strong></div>
            <div><span>P95 延迟</span><strong>{{ report.p95LatencyMs || 0 }} ms</strong></div>
          </div>
          <PagedTable :data="report.models || []" size="small" list-id="AdminConsole-6">
            <el-table-column prop="model" label="模型" min-width="160" />
            <el-table-column prop="requests" label="请求" width="100" />
            <el-table-column prop="tokens" label="Token" width="120" />
            <el-table-column label="模型收入(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.revenue) }}</template>
            </el-table-column>
          </PagedTable>
        </article>
      </section>

</template>
<script setup lang="ts">
import { computed } from 'vue'
import PagedTable from '@/components/PagedTable.vue';

import LegalSettingsEditor from '@/components/LegalSettingsEditor.vue';
import { Plus } from '@element-plus/icons-vue';
import { formatUsd } from '@/utils/money';
import { percent } from './format';
import type { useAdminSettings } from './useAdminSettings';
const props = defineProps<{ state: ReturnType<typeof useAdminSettings>; loading: boolean }>()
const {
  rows,
  report,
  legalStatus,
  paymentSwitchSaving,
  legalApprovalSaving,
  legalApprovalStatusLoaded,
  setLegalPublicationApproved,
  handleLegalFieldSaved,
  setPaymentsEnabled,
  filteredRows,
} = props.state
const emit = defineEmits<{ create: []; edit: [row: Record<string, any>] }>()
const missingLegalFields = computed(() => legalStatus.value?.missing_fields?.map((key: string) => `legal.${key}`).join('、') || '字段已填，仍需核对真实经营信息、实际处理方及人工法律审核')
</script>

<style scoped src="./panels.css"></style>
