<template>
  <el-dialog :model-value="modelValue" title="调用信息" width="min(760px, 94vw)" @update:model-value="$emit('update:modelValue', $event)">
    <div class="call-summary"><span>模型 ID</span><code>{{ modelId }}</code><el-button size="small" @click="copy(modelId)">复制</el-button></div>
    <el-tabs v-if="isChat" v-model="protocol">
      <el-tab-pane label="OpenAI" name="openai" />
      <el-tab-pane label="Anthropic" name="anthropic" />
    </el-tabs>
    <div class="endpoint"><span>请求地址</span><code>{{ endpoint }}</code><el-button size="small" @click="copy(endpoint)">复制 URL</el-button></div>
    <el-alert v-if="isChat" type="info" :closable="false" title="同类模型共用同一个 URL，仅请求体中的 model 不同。" />
    <div class="code-head"><strong>{{ protocol === 'anthropic' ? 'Anthropic Messages' : 'OpenAI 兼容' }} 示例</strong><el-button size="small" @click="copy(example)">复制示例</el-button></div>
    <pre>{{ example }}</pre>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
const props=defineProps<{modelValue:boolean;modelId:string;capability?:string}>()
defineEmits<{ 'update:modelValue':[value:boolean] }>()
const protocol=ref<'openai'|'anthropic'>('openai')
const apiRoot=computed(()=>String(import.meta.env.VITE_API_BASE_URL
  || (['linknux.com','www.linknux.com'].includes(window.location.hostname) ? 'https://api.linknux.com' : window.location.origin)).replace(/\/$/,''))
const openAiBase=computed(()=>`${apiRoot.value}/v1`)
const capability=computed(()=>String(props.capability||'TEXT').toUpperCase())
const isChat=computed(()=>['TEXT','CHAT','MULTIMODAL'].includes(capability.value))
const endpoint=computed(()=>{
  if(isChat.value)return protocol.value==='anthropic'?`${apiRoot.value}/v1/messages`:`${openAiBase.value}/chat/completions`
  if(capability.value.includes('EMBED'))return `${openAiBase.value}/embeddings`
  if(capability.value.includes('IMAGE'))return `${openAiBase.value}/images/generations`
  if(capability.value.includes('SPEECH'))return `${openAiBase.value}/audio/speech`
  if(capability.value.includes('TRANSCR'))return `${openAiBase.value}/audio/transcriptions`
  return `${openAiBase.value}/responses`
})
const example=computed(()=>{
  if(protocol.value==='anthropic'&&isChat.value)return `curl ${endpoint.value} \\\n+  -H "x-api-key: YOUR_API_KEY" \\\n+  -H "anthropic-version: 2023-06-01" \\\n+  -H "content-type: application/json" \\\n+  -d '${JSON.stringify({model:props.modelId,max_tokens:1024,messages:[{role:'user',content:'Hello'}]})}'`
  const body=capability.value.includes('EMBED')?{model:props.modelId,input:'Hello'}:isChat.value?{model:props.modelId,messages:[{role:'user',content:'Hello'}]}:{model:props.modelId,input:'Hello'}
  return `curl ${endpoint.value} \\\n+  -H "Authorization: Bearer YOUR_API_KEY" \\\n+  -H "Content-Type: application/json" \\\n+  -d '${JSON.stringify(body)}'`
})
async function copy(value:string){try{await navigator.clipboard.writeText(value);ElMessage.success('已复制')}catch{ElMessage.error('复制失败，请手动复制')}}
</script>
<style scoped>.call-summary,.endpoint{display:grid;grid-template-columns:auto minmax(0,1fr) auto;align-items:center;gap:10px;margin:10px 0}.call-summary span,.endpoint span{color:#64748b;font-size:12px}.call-summary code,.endpoint code{overflow:auto;padding:10px;border:1px solid #dce8f7;border-radius:8px;background:#f7faff;color:#174b8f;white-space:nowrap}.code-head{display:flex;align-items:center;justify-content:space-between;margin:18px 0 8px}pre{overflow:auto;margin:0;padding:16px;border-radius:10px;background:#10233f;color:#d9ecff;line-height:1.65}@media(max-width:600px){.call-summary,.endpoint{grid-template-columns:1fr}.call-summary button,.endpoint button{justify-self:start}}</style>
