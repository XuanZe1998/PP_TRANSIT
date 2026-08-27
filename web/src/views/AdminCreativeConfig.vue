<template>
  <div class="creative-admin" v-loading="loading">
    <el-alert title="AI 创作配置只保存在数据库中；密钥加密后存储，页面不会回显原文。" type="info" show-icon :closable="false" />
    <el-tabs v-model="tab" class="config-tabs">
      <el-tab-pane label="平台模型" name="connections">
        <div class="toolbar"><p>分别配置文本、图片和 Seedance 视频默认连接。</p><el-button type="primary" @click="openConnection()">新增连接</el-button></div>
        <el-table :data="connections" empty-text="尚未配置平台模型">
          <el-table-column prop="capability" label="能力" width="90" />
          <el-table-column prop="displayName" label="名称" min-width="150" />
          <el-table-column prop="baseUrl" label="Base URL" min-width="240" show-overflow-tooltip />
          <el-table-column prop="defaultModel" label="默认模型" min-width="170" />
          <el-table-column label="状态" width="150"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag><el-tag v-if="row.isDefault" class="tag-gap">默认</el-tag></template></el-table-column>
          <el-table-column label="密钥" width="100"><template #default="{ row }">{{ row.apiKeyConfigured ? row.apiKeyPreview : '未配置' }}</template></el-table-column>
          <el-table-column label="操作" width="230" fixed="right"><template #default="{ row }"><el-button link @click="testConnection(row)">测试</el-button><el-button link @click="openConnection(row)">编辑</el-button><el-button link type="danger" @click="removeConnection(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="自动成片" name="runtime">
        <el-form label-width="170px" class="form-card">
          <el-form-item label="启用 TXT 自动成片"><el-switch v-model="settings.autoMovieEnabled" /></el-form-item>
          <el-divider content-position="left">计费（1/10000 元）</el-divider>
          <div class="grid"><el-form-item label="剧本/次"><el-input-number v-model="settings.scriptPrice" :min="0" /></el-form-item><el-form-item label="图片/张"><el-input-number v-model="settings.imagePrice" :min="0" /></el-form-item><el-form-item label="视频/秒"><el-input-number v-model="settings.videoSecondPrice" :min="0" /></el-form-item></div>
          <el-divider content-position="left">任务与限制</el-divider>
          <div class="grid"><el-form-item label="Worker 并发"><el-input-number v-model="settings.workerConcurrency" :min="1" :max="20" /></el-form-item><el-form-item label="视频并发"><el-input-number v-model="settings.videoConcurrency" :min="1" :max="20" /></el-form-item><el-form-item label="最大重试"><el-input-number v-model="settings.maxRetries" :min="0" :max="10" /></el-form-item><el-form-item label="轮询毫秒"><el-input-number v-model="settings.pollIntervalMs" :min="1000" :max="60000" /></el-form-item><el-form-item label="TXT 最大字节"><el-input-number v-model="settings.maxSourceBytes" :min="1" /></el-form-item><el-form-item label="TXT 最大字符"><el-input-number v-model="settings.maxSourceCharacters" :min="1" /></el-form-item><el-form-item label="图片最大字节"><el-input-number v-model="settings.maxImageBytes" :min="1" /></el-form-item><el-form-item label="最大角色"><el-input-number v-model="settings.maxCharacters" :min="1" :max="32" /></el-form-item><el-form-item label="最大场景"><el-input-number v-model="settings.maxScenes" :min="1" :max="32" /></el-form-item><el-form-item label="最大镜头"><el-input-number v-model="settings.maxShots" :min="1" :max="24" /></el-form-item><el-form-item label="最短成片秒数"><el-input-number v-model="settings.minDuration" :min="1" /></el-form-item><el-form-item label="最长成片秒数"><el-input-number v-model="settings.maxDuration" :min="1" /></el-form-item></div>
          <el-button type="primary" :loading="saving" @click="saveSettings">保存自动成片配置</el-button>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="S3 素材存储" name="storage">
        <el-form label-width="150px" class="form-card">
          <el-form-item label="启用"><el-switch v-model="storage.enabled" /></el-form-item>
          <el-form-item label="Endpoint"><el-input v-model="storage.endpoint" placeholder="https://s3.example.com" /></el-form-item>
          <div class="grid"><el-form-item label="Region"><el-input v-model="storage.region" /></el-form-item><el-form-item label="Bucket"><el-input v-model="storage.bucket" /></el-form-item></div>
          <el-form-item label="公开 HTTPS 地址"><el-input v-model="storage.publicBaseUrl" placeholder="https://cdn.example.com/bucket" /></el-form-item>
          <div class="grid"><el-form-item label="Access Key"><el-input v-model="storage.accessKey" type="password" show-password :placeholder="storage.accessKeyConfigured ? '留空保持原值' : '请输入'" /></el-form-item><el-form-item label="Secret Key"><el-input v-model="storage.secretKey" type="password" show-password :placeholder="storage.secretKeyConfigured ? '留空保持原值' : '请输入'" /></el-form-item></div>
          <div class="grid"><el-form-item label="Path Style"><el-switch v-model="storage.pathStyle" /></el-form-item><el-form-item label="签名有效秒数"><el-input-number v-model="storage.signedUrlSeconds" :min="60" :max="86400" /></el-form-item></div>
          <el-button type="primary" :loading="saving" @click="saveStorage">保存</el-button><el-button @click="testStorage">测试 Bucket</el-button>
        </el-form>
      </el-tab-pane>
      <el-tab-pane label="运行诊断" name="diagnostics">
        <div class="toolbar"><p>检查模型、S3 和最终合成环境。</p><el-button @click="loadDiagnostics">重新检查</el-button></div>
        <el-descriptions :column="2" border v-if="diagnostics.ffmpeg">
          <el-descriptions-item label="FFmpeg 路径">{{ diagnostics.ffmpeg.path }}</el-descriptions-item><el-descriptions-item label="FFmpeg"><Status :ok="diagnostics.ffmpeg.ready" /></el-descriptions-item>
          <el-descriptions-item label="libx264"><Status :ok="diagnostics.ffmpeg.libx264" /></el-descriptions-item><el-descriptions-item label="AAC"><Status :ok="diagnostics.ffmpeg.aac" /></el-descriptions-item>
          <el-descriptions-item label="xfade"><Status :ok="diagnostics.ffmpeg.xfade" /></el-descriptions-item><el-descriptions-item label="acrossfade"><Status :ok="diagnostics.ffmpeg.acrossfade" /></el-descriptions-item>
          <el-descriptions-item label="文本模型"><Status :ok="diagnostics.text?.configured" /></el-descriptions-item><el-descriptions-item label="图片模型"><Status :ok="diagnostics.image?.configured" /></el-descriptions-item>
          <el-descriptions-item label="视频模型"><Status :ok="diagnostics.video?.configured" /></el-descriptions-item><el-descriptions-item label="S3"><Status :ok="diagnostics.storage?.configured" /></el-descriptions-item>
        </el-descriptions>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="connectionDialog" :title="connectionForm.id ? '编辑平台连接' : '新增平台连接'" width="620px">
      <el-form label-width="110px">
        <el-form-item label="能力"><el-select v-model="connectionForm.capability" :disabled="Boolean(connectionForm.id)" @change="syncProvider"><el-option label="文本" value="TEXT" /><el-option label="图片" value="IMAGE" /><el-option label="视频" value="VIDEO" /></el-select></el-form-item>
        <el-form-item label="协议"><el-input v-model="connectionForm.provider" disabled /></el-form-item>
        <el-form-item label="连接名称"><el-input v-model="connectionForm.displayName" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="connectionForm.baseUrl" /></el-form-item>
        <el-form-item label="模型 ID"><el-input v-model="connectionForm.modelsText" type="textarea" :rows="3" placeholder="每行一个模型 ID" /></el-form-item>
        <el-form-item label="默认模型"><el-input v-model="connectionForm.defaultModel" /></el-form-item>
        <el-form-item label="API Key"><el-input v-model="connectionForm.apiKey" type="password" show-password :placeholder="connectionForm.id ? '留空保持原值' : '请输入'" /></el-form-item>
        <div class="grid"><el-form-item label="启用"><el-switch v-model="connectionForm.enabled" /></el-form-item><el-form-item label="设为默认"><el-switch v-model="connectionForm.isDefault" /></el-form-item></div>
      </el-form>
      <template #footer><el-button @click="connectionDialog=false">取消</el-button><el-button type="primary" :loading="saving" @click="saveConnection">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { defineComponent, h, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, ElTag } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

const Status = defineComponent({ props: { ok: Boolean }, setup: p => () => h(ElTag, { type: p.ok ? 'success' : 'danger' }, () => p.ok ? '正常' : '未就绪') })
const tab = ref('connections'), loading = ref(false), saving = ref(false), connectionDialog = ref(false)
const connections = ref<any[]>([]), diagnostics = reactive<any>({})
const settings = reactive<any>({})
const storage = reactive<any>({ accessKey: '', secretKey: '' })
const connectionForm = reactive<any>({})
const api = '/api/admin/api/creative'

async function loadAll() { loading.value=true; try { const [c,s,st]=await Promise.all([http.get(`${api}/connections`),http.get(`${api}/settings`),http.get(`${api}/storage`)]); connections.value=c.data; Object.assign(settings,s.data); Object.assign(storage,st.data,{accessKey:'',secretKey:''}) } catch(e){ElMessage.error(getHttpErrorMessage(e,'配置加载失败'))} finally{loading.value=false} }
function syncProvider(){ connectionForm.provider=connectionForm.capability==='TEXT'?'openai-chat':connectionForm.capability==='IMAGE'?'openai-image':'seedance' }
function openConnection(row?:any){ Object.keys(connectionForm).forEach(k=>delete connectionForm[k]); Object.assign(connectionForm,row?{...row,modelsText:(row.models||[]).join('\n'),apiKey:''}:{capability:'TEXT',provider:'openai-chat',displayName:'',baseUrl:'',modelsText:'',defaultModel:'',apiKey:'',enabled:true,isDefault:true}); connectionDialog.value=true }
async function saveConnection(){ saving.value=true; try { const payload={...connectionForm,models:String(connectionForm.modelsText||'').split(/[\r\n,]+/).map(v=>v.trim()).filter(Boolean)}; if(connectionForm.id) await http.put(`${api}/connections/${connectionForm.id}`,payload); else await http.post(`${api}/connections`,payload); connectionDialog.value=false; ElMessage.success('连接已保存'); await loadAll() } catch(e){ElMessage.error(getHttpErrorMessage(e,'保存失败'))} finally{saving.value=false} }
async function removeConnection(row:any){ try{await ElMessageBox.confirm(`确认删除“${row.displayName}”？`,'删除连接',{type:'warning'});await http.delete(`${api}/connections/${row.id}`);ElMessage.success('已删除');await loadAll()}catch(e:any){if(e!=='cancel'&&e!=='close')ElMessage.error(getHttpErrorMessage(e,'删除失败'))} }
async function testConnection(row:any){try{const {data}=await http.post(`${api}/connections/${row.id}/test`);ElMessage.success(data.message||'连接成功')}catch(e){ElMessage.error(getHttpErrorMessage(e,'连接测试失败'))}}
async function saveSettings(){saving.value=true;try{const {data}=await http.put(`${api}/settings`,settings);Object.assign(settings,data);ElMessage.success('自动成片配置已保存')}catch(e){ElMessage.error(getHttpErrorMessage(e,'保存失败'))}finally{saving.value=false}}
async function saveStorage(){saving.value=true;try{const {data}=await http.put(`${api}/storage`,storage);Object.assign(storage,data,{accessKey:'',secretKey:''});ElMessage.success('S3 配置已保存')}catch(e){ElMessage.error(getHttpErrorMessage(e,'保存失败'))}finally{saving.value=false}}
async function testStorage(){try{const {data}=await http.post(`${api}/storage/test`);ElMessage.success(data.message||'S3 连接成功')}catch(e){ElMessage.error(getHttpErrorMessage(e,'S3 测试失败'))}}
async function loadDiagnostics(){try{const {data}=await http.get(`${api}/diagnostics`);Object.assign(diagnostics,data)}catch(e){ElMessage.error(getHttpErrorMessage(e,'诊断失败'))}}
onMounted(async()=>{await loadAll();await loadDiagnostics()})
</script>

<style scoped>
.creative-admin{display:grid;gap:18px}.config-tabs,.form-card{padding:20px;border:1px solid #e5e7eb;border-radius:12px;background:#fff}.toolbar{display:flex;align-items:center;justify-content:space-between;margin-bottom:16px}.toolbar p{margin:0;color:#6b7280}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 18px}.tag-gap{margin-left:6px}.el-select,.el-input-number{width:100%}@media(max-width:760px){.grid{grid-template-columns:1fr}.toolbar{align-items:flex-start;flex-direction:column;gap:12px}}
</style>
