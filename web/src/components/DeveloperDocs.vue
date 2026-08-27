<template>
  <section class="developer-docs">
    <header><div><p class="eyebrow">INTEGRATION CENTER</p><h2>多客户端接入中心</h2><p>同一个授权全部模型的 API Key 可调用所有可见模型；每次请求通过 <code>model</code> 选择模型。</p></div></header>
    <div class="docs-config-grid">
      <label><span>客户端</span><el-select v-model="clientId" filterable><el-option v-for="item in clients" :key="item.id" :label="item.name" :value="item.id" /></el-select></label>
      <label><span>操作系统</span><el-select v-model="os"><el-option label="Windows" value="windows"/><el-option label="macOS / Linux" value="unix"/></el-select></label>
      <label><span>API Key</span><el-input v-model="apiKey" show-password placeholder="sk-…" /></label>
      <label><span>模型 ID</span><el-input v-model="model" placeholder="例如 claude-opus-4-8" /></label>
    </div>
    <el-alert :title="selected?.note || '请选择客户端'" type="info" :closable="false" show-icon />
    <div class="docs-endpoints"><div><span>Base URL</span><code>{{ baseUrl }}</code></div><div><span>Chat Completions</span><code>{{ baseUrl }}/chat/completions</code></div></div>
    <div class="docs-code-head"><strong>可直接使用的配置</strong><el-button size="small" @click="copy">复制</el-button></div>
    <pre>{{ generated }}</pre>
    <div class="docs-checklist"><b>连通检查</b><ol><li>先请求 <code>GET {{ baseUrl }}/models</code> 确认 Key 和模型权限。</li><li>再用所选模型发送最小 Chat Completions 请求。</li><li>若客户端要求“完整端点”，填写 <code>{{ baseUrl }}/chat/completions</code>；要求 Base URL 时只填 <code>{{ baseUrl }}</code>。</li></ol></div>
  </section>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/utils/http'
type Client={id:string;name:string;protocol:string;note:string}
const fallbackClients:Client[]=[
  {id:'curl',name:'curl / HTTP',protocol:'OPENAI',note:'使用完整 /v1/chat/completions 端点。'},
  {id:'python',name:'Python OpenAI SDK',protocol:'OPENAI',note:'base_url 填写以 /v1 结尾的地址。'},
  {id:'javascript',name:'JavaScript OpenAI SDK',protocol:'OPENAI',note:'baseURL 填写以 /v1 结尾的地址。'},
  {id:'workbuddy',name:'WorkBuddy',protocol:'OPENAI',note:'选择 OpenAI 兼容服务，填写 Base URL、Key 和模型 ID。'},
  {id:'teleagent',name:'TeleAgent',protocol:'OPENAI',note:'配置自定义 OpenAI 兼容模型服务。'},
  {id:'qclaw',name:'QClaw 小龙虾',protocol:'OPENAI',note:'Provider 选择 OpenAI 兼容。'},
  {id:'openclaw',name:'OpenClaw',protocol:'OPENAI',note:'Provider 选择 OpenAI 兼容。'},
  {id:'ccswitch',name:'CC Switch',protocol:'OPENAI_OR_ANTHROPIC',note:'按目标客户端协议配置统一网关。'},
  {id:'trae',name:'TRAE',protocol:'OPENAI',note:'添加自定义 OpenAI 兼容模型。'},
  {id:'opencode',name:'OpenCode',protocol:'OPENAI',note:'provider.baseURL 填写以 /v1 结尾的地址。'},
  {id:'cherry-studio',name:'Cherry Studio',protocol:'OPENAI',note:'API 主机地址填写以 /v1 结尾的地址。'},
  {id:'claude-code',name:'Claude Code',protocol:'ANTHROPIC',note:'仅在模型开放 Anthropic 兼容协议时使用。'},
  {id:'codex',name:'Codex',protocol:'OPENAI',note:'配置 OpenAI 兼容 Base URL、API Key 和模型 ID。'}
]
const configured=String(import.meta.env.VITE_API_BASE_URL||'').replace(/\/$/,'')
const baseUrl=ref(`${configured||window.location.origin}/v1`),clients=ref<Client[]>([]),clientId=ref('curl'),os=ref('windows'),apiKey=ref('YOUR_API_KEY'),model=ref('YOUR_MODEL_ID')
const selected=computed(()=>clients.value.find(item=>item.id===clientId.value))
const generated=computed(()=>{
  if(clientId.value==='python')return `from openai import OpenAI\n\nclient = OpenAI(api_key="${apiKey.value}", base_url="${baseUrl.value}")\nresponse = client.chat.completions.create(model="${model.value}", messages=[{"role":"user","content":"Hello"}])`
  if(clientId.value==='javascript')return `import OpenAI from "openai";\n\nconst client = new OpenAI({ apiKey: "${apiKey.value}", baseURL: "${baseUrl.value}" });\nawait client.chat.completions.create({ model: "${model.value}", messages: [{ role: "user", content: "Hello" }] });`
  if(clientId.value==='curl')return `curl ${os.value==='windows'?'^':'\\'}\n  -H "Authorization: Bearer ${apiKey.value}" ${os.value==='windows'?'^':'\\'}\n  -H "Content-Type: application/json" ${os.value==='windows'?'^':'\\'}\n  -d '${JSON.stringify({model:model.value,messages:[{role:'user',content:'Hello'}]})}' ${baseUrl.value}/chat/completions`
  return `Client: ${selected.value?.name||clientId.value}\nProtocol: ${selected.value?.protocol||'OPENAI'}\nBase URL: ${baseUrl.value}\nAPI Key: ${apiKey.value}\nModel: ${model.value}\n\n${selected.value?.note||''}`
})
async function load(){clients.value=fallbackClients;try{const r=await http.get('/api/platform/user/docs');baseUrl.value=r.data?.baseUrl||baseUrl.value;clients.value=r.data?.clients?.length?r.data.clients:fallbackClients}catch{clients.value=fallbackClients}}
async function copy(){await navigator.clipboard.writeText(generated.value);ElMessage.success('配置已复制')}
onMounted(load)
</script>
<style scoped>
.developer-docs{display:grid;min-width:0;gap:18px;padding:24px;border:1px solid var(--tech-line);border-radius:18px;background:rgba(255,255,255,.9);box-shadow:var(--tech-glow)}.developer-docs>*{min-width:0}.developer-docs header h2{margin:4px 0 8px;font-size:28px}.developer-docs header p{margin:0;color:var(--tech-muted);line-height:1.7}.docs-config-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.docs-config-grid label{display:grid;min-width:0;gap:7px}.docs-config-grid label>span,.docs-endpoints span{color:#526a88;font-size:12px;font-weight:700}.docs-endpoints{display:grid;grid-template-columns:1fr 1.4fr;gap:10px}.docs-endpoints div{display:grid;min-width:0;gap:5px;padding:12px;border:1px solid #dce8f7;border-radius:10px;background:#f7faff}.docs-endpoints code{overflow:auto;color:#174b8f;white-space:nowrap}.docs-code-head{display:flex;align-items:center;justify-content:space-between}.developer-docs>pre{max-width:100%;overflow:auto;margin:0;padding:18px;border-radius:12px;background:#10233f;color:#d9ecff;line-height:1.7}.docs-checklist{min-width:0;color:#52657e;line-height:1.75}.docs-checklist ol,.docs-checklist li{min-width:0}.docs-checklist ol{margin-bottom:0}.docs-checklist code{overflow-wrap:anywhere;word-break:break-all}@media(max-width:680px){.docs-config-grid,.docs-endpoints{grid-template-columns:1fr}.developer-docs{padding:16px}}
</style>
