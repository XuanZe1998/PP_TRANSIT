<template>
    <el-dialog v-model="adjustVisible" title="用户手工调账" width="420px">
      <el-form label-position="top">
        <el-form-item label="用户">
          <el-input :model-value="adjustTarget?.username" disabled />
        </el-form-item>
        <el-form-item label="调整金额（10,000 amount units = ¥1.00 CNY）">
          <el-input-number v-model="adjustForm.amount" :step="10000" :min="-1000000000000" :max="1000000000000" />
          <div class="field-hint">当前调整折合：{{ money(adjustForm.amount) }}</div>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" minlength="3" maxlength="500" show-word-limit placeholder="请输入 3–500 个字符的调账原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" @click="saveAdjust">确认调账</el-button>
      </template>
    </el-dialog>


</template>
<script setup lang="ts">

import { money } from './format';
import type { useAdminUsers } from './useAdminUsers';
const props = defineProps<{ state: ReturnType<typeof useAdminUsers> }>()
const {
  adjustVisible,
  adjustSaving,
  adjustTarget,
  adjustForm,
  saveAdjust,
} = props.state

</script>

<style scoped src="./panels.css"></style>
