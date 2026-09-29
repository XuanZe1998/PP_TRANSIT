<template>
  <article class="panel legal-settings-editor">
    <el-collapse v-model="expandedSections">
      <el-collapse-item name="legal-fields">
        <template #title><div class="legal-settings-title"><h3>公开信息填写区</h3><el-tag type="warning">草稿</el-tag><span>点击展开或收起</span></div></template>
        <p>政策正文已补充中英文审核草稿；逐项核对并点击“保存本项”。保存不会批准发布，也不会开启收款。身份、供应商、保留期限及目标地区要求仍须核实；没有营业执照时不要编造登记号码。</p>
        <el-alert title="草稿不是正式法律审核结论。请补齐真实经营者身份、服务商与处理地区、保留期限及退款能力，审阅双语内容后再决定正式发布；未完成前继续保持收款关闭。" type="info" :closable="false" />
        <section v-for="group in legalSettingGroups" :key="group.title" class="legal-settings-group">
          <h4>{{ group.title }}</h4>
          <div v-for="field in group.fields" :key="field.key" class="legal-settings-field">
            <div class="legal-settings-field-head">
              <label :for="field.key">{{ field.label }} <code>{{ field.key }}</code></label>
              <el-tag :type="!draft[field.key]?.trim() || missingFields.includes(field.key.slice(6)) ? 'warning' : 'success'" size="small">
                {{ !draft[field.key]?.trim() || missingFields.includes(field.key.slice(6)) ? '待填写或核对' : '已填写' }}
              </el-tag>
            </div>
            <el-input :id="field.key" v-model="draft[field.key]" :type="field.multiline ? 'textarea' : 'text'"
                      :autosize="field.multiline ? { minRows: 4, maxRows: 12 } : undefined"
                      :placeholder="field.hint" />
            <div class="legal-settings-field-actions">
              <small>{{ field.hint }}</small>
              <el-button type="primary" size="small" :loading="savingKey === field.key"
                         :disabled="savingKey !== '' || draft[field.key] === saved[field.key]"
                         @click="saveField(field)">保存本项</el-button>
            </div>
          </div>
        </section>
      </el-collapse-item>
    </el-collapse>
  </article>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorNotice } from '@/utils/http'
import { legalSettingGroups, type LegalSettingField } from '@/config/legalSettings'

const props = defineProps<{ settings: Array<{ setting_key: string; setting_value: unknown }>; missingFields: string[] }>()
const emit = defineEmits<{ saved: [key: string, value: string, description: string] }>()
const expandedSections = ref([] as string[])
const draft = reactive<Record<string, string>>({})
const saved = reactive<Record<string, string>>({})
const savingKey = ref('')
watch(() => props.settings, rows => {
  const values = new Map(rows.map(row => [row.setting_key, String(row.setting_value ?? '')]))
  for (const group of legalSettingGroups) for (const field of group.fields) {
    const next = values.get(field.key) ?? ''
    if (draft[field.key] === saved[field.key]) draft[field.key] = next
    saved[field.key] = next
  }
}, { immediate: true })

async function saveField(field: LegalSettingField) {
  const value = draft[field.key] ?? ''
  savingKey.value = field.key
  try {
    await http.put('/api/admin/api/settings', { key: field.key, value, description: field.label })
    saved[field.key] = value
    emit('saved', field.key, value, field.label)
    ElMessage.success('本项草稿已保存')
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorNotice(error, '保存公开信息失败'))
  } finally { savingKey.value = '' }
}
</script>

<style scoped>
.legal-settings-editor { margin: 16px 0; }
.legal-settings-editor :deep(.el-collapse) { border: 0; }
.legal-settings-title { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.legal-settings-title h3 { margin: 0; }
.legal-settings-title > span { font-size: 12px; color: #64748b; }
.legal-settings-group { margin-top: 24px; }
.legal-settings-group h4 { margin: 0 0 14px; }
.legal-settings-field { padding: 16px 0; border-top: 1px solid #e4e7ed; }
.legal-settings-field-head, .legal-settings-field-actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; }
.legal-settings-field-head code { font-size: 12px; font-weight: normal; overflow-wrap: anywhere; }
.legal-settings-field-actions { margin: 8px 0 0; }
.legal-settings-field-actions small { color: #64748b; }
@media (max-width: 640px) { .legal-settings-field-head, .legal-settings-field-actions { align-items: flex-start; flex-direction: column; } }
</style>
