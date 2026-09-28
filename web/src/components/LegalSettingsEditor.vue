<template>
  <article class="panel legal-settings-editor">
    <div class="panel-head"><h3>公开信息填写区</h3><el-tag type="warning">草稿</el-tag></div>
    <p>以下字段已在线建立为空白草稿。逐项填写并点击“保存本项”；保存不会批准发布，也不会开启收款。没有营业执照时，登记信息请保持空白，切勿编造。</p>
    <el-alert title="填写前请核对事实及目标销售地区的要求。公开政策由你自行撰写并审阅；未完成前继续保持收款关闭。" type="info" :closable="false" />
    <section v-for="group in legalSettingGroups" :key="group.title" class="legal-settings-group">
      <h4>{{ group.title }}</h4>
      <div v-for="field in group.fields" :key="field.key" class="legal-settings-field">
        <div class="legal-settings-field-head">
          <label :for="field.key">{{ field.label }} <code>{{ field.key }}</code></label>
          <el-tag :type="missingFields.includes(field.key.slice(6)) ? 'warning' : 'success'" size="small">
            {{ missingFields.includes(field.key.slice(6)) ? '待填写或核对' : '已填写' }}
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
  </article>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorNotice } from '@/utils/http'
import { legalSettingGroups, type LegalSettingField } from '@/config/legalSettings'

const props = defineProps<{ settings: Array<{ setting_key: string; setting_value: unknown }>; missingFields: string[] }>()
const emit = defineEmits<{ saved: [key: string, value: string, description: string] }>()
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
.legal-settings-group { margin-top: 24px; }
.legal-settings-group h4 { margin: 0 0 14px; }
.legal-settings-field { padding: 16px 0; border-top: 1px solid #e4e7ed; }
.legal-settings-field-head, .legal-settings-field-actions { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; }
.legal-settings-field-head code { font-size: 12px; font-weight: normal; overflow-wrap: anywhere; }
.legal-settings-field-actions { margin: 8px 0 0; }
.legal-settings-field-actions small { color: #64748b; }
@media (max-width: 640px) { .legal-settings-field-head, .legal-settings-field-actions { align-items: flex-start; flex-direction: column; } }
</style>
