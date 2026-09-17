<template>
  <div class="admin-model-probe">
    <AdminPageToolbar
      title="模型鉴别"
      description="对 OpenAI 兼容端点执行质量、安全、完整性与身份探针，识别模型调包或降级。"
    >
      <template #actions><el-button @click="probePanel?.refresh()">刷新任务</el-button></template>
    </AdminPageToolbar>
    <ModelProbePanel ref="probePanel" endpoint="/admin/api/model-probe" :show-header="false" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AdminPageToolbar from '@/components/AdminPageToolbar.vue'
import ModelProbePanel from '@/components/ModelProbePanel.vue'

const probePanel = ref<InstanceType<typeof ModelProbePanel>>()
</script>

<style scoped>
.admin-model-probe { display: grid; gap: 18px; min-width: 0; }
</style>
