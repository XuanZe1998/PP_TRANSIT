<template>
      <el-dialog v-model="createKeyVisible" title="新建 API Key" width="480px" destroy-on-close>
        <el-form label-position="top" @submit.prevent="createKey">
          <el-form-item label="名称" required>
            <el-input
              v-model="createKeyForm.name"
              maxlength="80"
              show-word-limit
              placeholder="例如：生产环境"
            />
          </el-form-item>
          <el-form-item label="Token 调用额度">
            <el-input-number
              v-model="createKeyForm.totalQuota"
              :min="0"
              :max="1000000000"
              :step="10000"
              controls-position="right"
              style="width: 100%"
            />
            <div class="form-hint">单位为 Token；填写 0 表示不单独限制此 Key 的 Token 额度。账户余额仍会按模型定价扣减。</div>
          </el-form-item>
          <el-form-item label="授权全部模型">
            <el-switch v-model="createKeyForm.allowAllModels" />
          </el-form-item>
          <el-form-item v-if="!createKeyForm.allowAllModels" label="授权模型" required>
            <el-cascader
              v-model="createKeyForm.allowedModelIds"
              :options="modelGrantOptions"
              :props="{ multiple: true, emitPath: false }"
              collapse-tags
              collapse-tags-tooltip
              filterable
              clearable
              placeholder="按来源 / 功能 / 厂家选择模型"
              style="width: 100%"
            />
            <div class="form-hint">仅所选模型会出现在此 Key 的 /v1/models 中。</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createKeyVisible = false">取消</el-button>
          <el-button type="primary" :loading="createKeySubmitting" @click="createKey">创建</el-button>
        </template>
      </el-dialog>

      <el-dialog
        v-model="createdKeyVisible"
        title="API Key 创建成功（仅显示一次）"
        width="560px"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        @closed="clearCreatedKey"
      >
        <el-alert
          title="关闭后将无法再次查看完整 Key，请立即复制并保存在密码管理器中。"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="created-key-secret">
          <code>{{ createdKeySecret }}</code>
          <el-button type="primary" @click="copyText(createdKeySecret, 'API Key 已复制')">复制 Key</el-button>
        </div>
        <template #footer>
          <el-button type="primary" @click="createdKeyVisible = false">我已安全保存</el-button>
        </template>
      </el-dialog>

</template>
<script setup lang="ts">

import { number, copyText } from './format';

import type { useUserKeys } from './useUserKeys';
const props = defineProps<{ state: ReturnType<typeof useUserKeys> }>()
const {
  createKeyVisible,
  createKeySubmitting,
  createdKeyVisible,
  createdKeySecret,
  createKeyForm,
  modelGrantOptions,
  createKey,
  clearCreatedKey,
} = props.state
</script>
