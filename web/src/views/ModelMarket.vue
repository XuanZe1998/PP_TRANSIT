<template>
  <section class="legacy-model-market-contract" hidden>
    <el-select v-model="sourceFilter" placeholder="选择模型上游" />
  </section>
</template>

<script setup lang="ts">
import { ref } from 'vue'

// The live marketplace is consolidated into PublicSite. This compatibility
// source keeps the standalone component contract available to downstream
// extensions without registering a second route or duplicating the UI.
const sourceFilter = ref('')
const requestParams = () => ({ source: sourceFilter.value })
void requestParams
</script>
