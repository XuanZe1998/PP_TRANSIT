<template>
  <div class="auto-movie">
    <header class="am-head"><div><p>TXT → 剧本 → 画像 → 分镜 → 成片</p><h2>TXT 自动成片</h2></div><el-button @click="showProjects = !showProjects">{{ showProjects ? '返回当前项目' : '我的项目' }}</el-button></header>

    <div v-if="showProjects" class="project-list">
      <button v-for="item in projects" :key="item.id" @click="openProject(item.id)"><div><b>{{ item.title }}</b><span>{{ statusText(item.status) }}</span></div><small>{{ item.target_duration }} 秒 · {{ item.ratio }} · {{ item.resolution }}</small></button>
      <el-empty v-if="!projects.length" description="还没有自动成片项目" />
    </div>

    <section v-else-if="!project" class="source-step">
      <el-alert v-if="!catalog.enabled" title="TXT 自动成片功能尚未由管理员启用" type="warning" :closable="false" />
      <div class="source-grid">
        <label><span>片名</span><el-input v-model="createForm.title" maxlength="160" /></label>
        <label><span>目标时长</span><el-slider v-model="createForm.targetDuration" :min="30" :max="90" :step="5" show-input /></label>
        <label><span>画面比例</span><el-select v-model="createForm.ratio"><el-option v-for="r in ratios" :key="r" :label="r" :value="r" /></el-select></label>
        <label><span>清晰度</span><el-select v-model="createForm.resolution"><el-option label="480p" value="480p" /><el-option label="720p" value="720p" /><el-option label="1080p" value="1080p" /></el-select></label>
        <label><span>视觉风格</span><el-input v-model="createForm.style" maxlength="160" /></label>
        <label><span>语言</span><el-input v-model="createForm.language" maxlength="40" /></label>
        <label><span>文本模型连接</span><el-select v-model="createForm.textConnectionId" clearable placeholder="平台默认"><el-option v-for="c in textConnections" :key="c.id" :label="c.displayName" :value="c.id" /></el-select></label>
        <label><span>图片模型连接</span><el-select v-model="createForm.imageConnectionId" clearable placeholder="平台默认"><el-option v-for="c in imageConnections" :key="c.id" :label="c.displayName" :value="c.id" /></el-select></label>
        <label><span>视频模型连接</span><el-select v-model="createForm.videoConnectionId" clearable placeholder="平台默认 Seedance"><el-option v-for="c in videoConnections" :key="c.id" :label="c.displayName" :value="c.id" /></el-select></label>
      </div>
      <label class="source-text"><span>原文（最多 20 万字符）</span><el-input v-model="createForm.sourceText" type="textarea" :rows="10" maxlength="200000" show-word-limit placeholder="粘贴小说、故事、广告文案或创意原文…" /></label>
      <div class="source-actions"><label class="file-button">选择 UTF-8 TXT<input type="file" accept=".txt,text/plain" @change="pickTxt"></label><span v-if="txtFile">{{ txtFile.name }}</span><el-checkbox v-model="createForm.generateAudio">生成声音</el-checkbox><el-checkbox v-model="createForm.rightsConfirmed">我确认拥有原文、人物及品牌素材的使用授权</el-checkbox><el-button type="primary" :loading="busy" :disabled="!catalog.enabled" @click="createProject">创建项目</el-button></div>
    </section>

    <section v-else class="project-workspace">
      <div class="project-title"><div><el-button text @click="closeProject">← 新建/项目列表</el-button><h3>{{ project.title }}</h3><span>{{ project.target_duration }} 秒 · {{ project.ratio }} · {{ project.resolution }}</span></div><div><span class="status" :class="String(project.status).toLowerCase()">{{ statusText(project.status) }}</span><el-button v-if="isRunning" type="danger" plain @click="cancelProject">取消</el-button></div></div>
      <el-steps :active="activeStep" finish-status="success" align-center><el-step title="原文" /><el-step title="剧本" /><el-step title="视觉设定" /><el-step title="视频分镜" /><el-step title="完整成片" /></el-steps>
      <el-alert v-if="project.error_message" :title="project.error_message" type="error" :closable="false" />

      <div v-if="!project.script" class="stage-card"><h3>第一步：生成结构化剧本</h3><p>AI 会将原文整理成角色、场景和最多 12 个视频镜头。预估费用：{{ money(project.quote?.script) }}</p><el-button type="primary" :loading="busy" @click="action('script/generate', 'SCRIPT')">生成剧本</el-button></div>

      <div v-else class="stage-stack">
        <section class="stage-card"><div class="stage-heading"><div><h3>剧本与分镜</h3><p>{{ project.script.summary }}</p></div><el-button v-if="project.status === 'SCRIPT_REVIEW'" @click="saveScript">保存修改</el-button></div>
          <el-input v-model="scriptText" type="textarea" :rows="14" :disabled="project.status !== 'SCRIPT_REVIEW'" />
          <footer v-if="project.status === 'SCRIPT_REVIEW'"><el-button type="primary" @click="approveScript">确认剧本并创建角色/场景</el-button></footer>
        </section>

        <section v-if="project.assets?.length" class="stage-card"><div class="stage-heading"><div><h3>角色与场景画像</h3><p>逐项生成或上传替换；成功素材会作为视频参考图。</p></div><el-button v-if="canGenerateVisuals" type="primary" @click="action('visuals/generate', 'VISUALS')">生成全部画像 · {{ money(project.quote?.visuals) }}</el-button></div>
          <div class="asset-grid"><article v-for="asset in project.assets" :key="asset.id"><div class="asset-preview"><img v-if="asset.image_url" :src="asset.image_url"><span v-else>{{ asset.asset_type === 'CHARACTER' ? '人' : '景' }}</span></div><b>{{ asset.name }}</b><small>{{ asset.asset_type === 'CHARACTER' ? '角色' : '场景' }} · {{ statusText(asset.status) }}</small><el-input v-model="asset.prompt" type="textarea" :rows="3" /><div><el-button size="small" @click="saveAsset(asset)">保存</el-button><el-button size="small" @click="regenerateAsset(asset)">重新生成</el-button><label class="mini-upload">上传<input type="file" accept="image/jpeg,image/png,image/webp" @change="uploadAsset(asset, $event)"></label></div></article></div>
          <footer v-if="allAssetsSucceeded"><el-button type="primary" @click="approveVisuals">确认视觉设定</el-button></footer>
        </section>

        <section v-if="project.shots?.length" class="stage-card"><div class="stage-heading"><div><h3>视频分镜</h3><p>逐镜头生成短片，失败镜头可独立重试。</p></div><el-button v-if="canGenerateVideos" type="primary" @click="action('videos/generate', 'VIDEO')">批量生成视频 · {{ money(project.quote?.video) }}</el-button></div>
          <div class="shot-list"><article v-for="shot in project.shots" :key="shot.id"><div class="shot-index">{{ shot.shot_order }}</div><div class="shot-main"><video v-if="shot.video_url" :src="shot.video_url" controls /><el-input v-model="shot.video_prompt" type="textarea" :rows="3" /><div class="shot-fields"><el-input-number v-model="shot.duration" :min="2" :max="15" /><el-input v-model="shot.dialogue" placeholder="对白" /><el-input v-model="shot.narration" placeholder="旁白" /></div><small v-if="shot.error_message" class="error">{{ shot.error_message }}</small></div><div class="shot-actions"><span>{{ statusText(shot.status) }}</span><el-button size="small" @click="saveShot(shot)">保存</el-button><el-button v-if="['FAILED','STALE'].includes(shot.status)" size="small" type="primary" @click="retryShot(shot)">重试</el-button></div></article></div>
        </section>

        <section v-if="allShotsSucceeded || project.final_video_url" class="stage-card final-card"><h3>完整成片</h3><video v-if="project.final_video_url" :src="project.final_video_url" controls /><div v-else><p>全部镜头已经完成，可以重新触发最终合成。</p><el-button type="primary" @click="compose">开始合成</el-button></div><a v-if="project.final_video_url" :href="project.final_video_url" target="_blank">下载/打开完整视频 ↗</a></section>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

type Connection = { id: number; provider: string; capability?: string; displayName: string }
const catalog = reactive<any>({ enabled: false }); const connections = ref<Connection[]>([]); const projects = ref<any[]>([]); const project = ref<any>(null); const scriptText = ref(''); const showProjects = ref(false); const busy = ref(false); const txtFile = ref<File | null>(null)
const createForm = reactive({ title: '我的 AI 短片', sourceText: '', targetDuration: 60, ratio: '16:9', resolution: '720p', style: '电影感、角色一致、自然光影', language: 'zh-CN', generateAudio: true, rightsConfirmed: false, textConnectionId: null as number | null, imageConnectionId: null as number | null, videoConnectionId: null as number | null })
const ratios = ['16:9', '9:16', '1:1', '4:3', '3:4']; let timer: number | undefined
const textConnections = computed(() => connections.value.filter(c => c.capability === 'TEXT' || c.provider === 'openai-chat'))
const imageConnections = computed(() => connections.value.filter(c => c.capability === 'IMAGE' || c.provider === 'openai-image'))
const videoConnections = computed(() => connections.value.filter(c => c.capability === 'VIDEO' || c.provider === 'seedance'))
const isRunning = computed(() => ['SCRIPT_GENERATING','VISUALS_GENERATING','VIDEO_GENERATING','COMPOSING'].includes(project.value?.status))
const activeStep = computed(() => project.value?.status === 'SUCCEEDED' ? 5 : project.value?.stage === 'COMPOSE' ? 4 : project.value?.stage === 'VIDEO' ? 3 : project.value?.stage === 'VISUALS' ? 2 : project.value?.script ? 1 : 0)
const allAssetsSucceeded = computed(() => project.value?.assets?.length && project.value.assets.every((a: any) => a.status === 'SUCCEEDED'))
const allShotsSucceeded = computed(() => project.value?.shots?.length && project.value.shots.every((s: any) => s.status === 'SUCCEEDED'))
const canGenerateVisuals = computed(() => ['SCRIPT_REVIEW','VISUALS_REVIEW','PARTIAL_FAILED'].includes(project.value?.status))
const canGenerateVideos = computed(() => allAssetsSucceeded.value && ['VISUALS_REVIEW','PARTIAL_FAILED','SCRIPT_REVIEW'].includes(project.value?.status))

onMounted(async () => { await Promise.all([loadCatalog(), loadConnections(), loadProjects()]); timer = window.setInterval(() => { if (project.value && isRunning.value) void refreshProject() }, 5000) })
onBeforeUnmount(() => { if (timer) window.clearInterval(timer) })
async function loadCatalog() { try { Object.assign(catalog, (await http.get('/creative/auto-movie/catalog')).data) } catch { catalog.enabled = false } }
async function loadConnections() { try { connections.value = (await http.get('/creative/connections')).data || [] } catch { connections.value = [] } }
async function loadProjects() { try { projects.value = (await http.get('/creative/projects')).data || [] } catch { projects.value = [] } }
function pickTxt(event: Event) { txtFile.value = (event.target as HTMLInputElement).files?.[0] || null }
async function createProject() { if (!txtFile.value && !createForm.sourceText.trim()) return ElMessage.warning('请上传 TXT 或粘贴原文'); if (!createForm.rightsConfirmed) return ElMessage.warning('请先确认素材使用授权'); busy.value = true; try { let data: any; if (txtFile.value) { const fd = new FormData(); fd.append('file', txtFile.value); fd.append('options', JSON.stringify({ ...createForm, sourceText: undefined })); data = (await http.post('/creative/projects/import', fd)).data } else data = (await http.post('/creative/projects', createForm)).data; setProject(data); await loadProjects(); ElMessage.success('项目已创建') } catch (e) { ElMessage.error(getHttpErrorMessage(e, '创建项目失败')) } finally { busy.value = false } }
async function openProject(id: number) { showProjects.value = false; project.value = (await http.get(`/creative/projects/${id}`)).data; syncScript() }
function closeProject() { project.value = null; showProjects.value = false }
async function refreshProject() { if (!project.value) return; try { setProject((await http.get(`/creative/projects/${project.value.id}`)).data) } catch { /* transient polling error */ } }
function setProject(value: any) { project.value = value; syncScript() }
function syncScript() { if (project.value?.script?.content) scriptText.value = JSON.stringify(project.value.script.content, null, 2) }
async function confirmQuote(stage: string) { const q = (await http.post(`/creative/projects/${project.value.id}/quote`, { stage })).data; await ElMessageBox.confirm(q.byok ? '本阶段使用自有接口，本站不扣费。确认继续？' : `预计冻结 ${money(q.estimatedAmount)}，失败部分会释放。确认继续？`, '确认生成', { type: 'info' }) }
async function action(path: string, stage: string) { try { await confirmQuote(stage); busy.value = true; setProject((await http.post(`/creative/projects/${project.value.id}/${path}`, { version: project.value.version })).data) } catch (e) { if (e === 'cancel' || e === 'close') return; ElMessage.error(getHttpErrorMessage(e, '操作失败')) } finally { busy.value = false } }
async function saveScript() { try { const content = JSON.parse(scriptText.value); setProject((await http.put(`/creative/projects/${project.value.id}/script`, { version: project.value.version, content })).data); ElMessage.success('剧本已保存') } catch (e) { ElMessage.error(e instanceof SyntaxError ? '剧本 JSON 格式不正确' : getHttpErrorMessage(e, '保存失败')) } }
async function approveScript() { await plainPost('script/approve') }
async function approveVisuals() { await plainPost('visuals/approve') }
async function compose() { await plainPost('compose') }
async function plainPost(path: string) { try { setProject((await http.post(`/creative/projects/${project.value.id}/${path}`, { version: project.value.version })).data) } catch (e) { ElMessage.error(getHttpErrorMessage(e, '操作失败')) } }
async function saveAsset(asset: any) { try { setProject((await http.put(`/creative/projects/${project.value.id}/assets/${asset.id}`, { version: project.value.version, name: asset.name, description: asset.description, prompt: asset.prompt })).data) } catch (e) { ElMessage.error(getHttpErrorMessage(e, '画像保存失败')) } }
async function regenerateAsset(asset: any) { try { await confirmQuote('VISUALS'); setProject((await http.post(`/creative/projects/${project.value.id}/assets/${asset.id}/regenerate`, { version: project.value.version })).data) } catch (e) { if (e !== 'cancel') ElMessage.error(getHttpErrorMessage(e, '重新生成失败')) } }
async function uploadAsset(asset: any, event: Event) { const file = (event.target as HTMLInputElement).files?.[0]; if (!file) return; const fd = new FormData(); fd.append('file', file); try { setProject((await http.post(`/creative/projects/${project.value.id}/assets/${asset.id}/upload?version=${project.value.version}`, fd)).data) } catch (e) { ElMessage.error(getHttpErrorMessage(e, '上传失败')) } }
async function saveShot(shot: any) { try { setProject((await http.put(`/creative/projects/${project.value.id}/shots/${shot.id}`, { version: project.value.version, duration: shot.duration, dialogue: shot.dialogue, narration: shot.narration, videoPrompt: shot.video_prompt, characterRefs: shot.characterRefs || [], sceneRef: shot.scene_ref })).data) } catch (e) { ElMessage.error(getHttpErrorMessage(e, '镜头保存失败')) } }
async function retryShot(shot: any) { try { await confirmQuote('VIDEO'); setProject((await http.post(`/creative/projects/${project.value.id}/shots/${shot.id}/retry`, { version: project.value.version })).data) } catch (e) { if (e !== 'cancel') ElMessage.error(getHttpErrorMessage(e, '重试失败')) } }
async function cancelProject() { try { await ElMessageBox.confirm('取消尚未提交的任务并释放冻结余额？', '取消项目', { type: 'warning' }); setProject((await http.post(`/creative/projects/${project.value.id}/cancel`, { version: project.value.version })).data) } catch { /* cancelled */ } }
function money(value: any) { return `¥${(Number(value || 0) / 10000).toFixed(4)}` }
function statusText(status: string) { return ({ DRAFT:'草稿',SCRIPT_GENERATING:'正在生成剧本',SCRIPT_REVIEW:'待确认剧本',VISUALS_GENERATING:'正在生成画像',VISUALS_REVIEW:'待确认画像',VIDEO_GENERATING:'正在生成视频',COMPOSING:'正在合成',SUCCEEDED:'已完成',FAILED:'失败',PARTIAL_FAILED:'部分失败',CANCELLED:'已取消',PENDING:'待处理',QUEUED:'排队中',RUNNING:'生成中',STALE:'需重新生成' } as Record<string,string>)[status] || status }
</script>

<style scoped>
.auto-movie{display:grid;gap:22px}.am-head,.project-title,.stage-heading,.source-actions,.stage-card footer{display:flex;justify-content:space-between;align-items:center;gap:16px}.am-head p,.stage-heading p{margin:0;color:#8b7b70;font-size:12px}.am-head h2,.project-title h3,.stage-card h3{margin:4px 0}.source-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.source-grid label,.source-text{display:grid;gap:7px;font-size:13px;font-weight:700}.source-text{margin-top:18px}.file-button,.mini-upload{cursor:pointer;border:1px solid #dccfc5;border-radius:8px;padding:8px 12px;background:#fff}.file-button input,.mini-upload input{display:none}.project-list{display:grid;gap:10px}.project-list button{padding:16px;text-align:left;border:1px solid #eadfd7;border-radius:14px;background:#fff}.project-list button div{display:flex;justify-content:space-between}.project-list small{color:#8b7b70}.project-workspace,.stage-stack{display:grid;gap:18px}.status{padding:6px 10px;border-radius:999px;background:#f3ece7}.stage-card{border:1px solid #eadfd7;border-radius:16px;padding:18px;background:#fff}.asset-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px;margin-top:16px}.asset-grid article{display:grid;gap:9px;border:1px solid #eee4dd;border-radius:12px;padding:12px}.asset-preview{height:150px;border-radius:10px;background:#f5efeb;display:grid;place-items:center;overflow:hidden;font-size:32px}.asset-preview img{width:100%;height:100%;object-fit:cover}.asset-grid article>div:last-child{display:flex;gap:6px}.shot-list{display:grid;gap:12px;margin-top:16px}.shot-list article{display:grid;grid-template-columns:42px 1fr 100px;gap:12px;border-top:1px solid #eee4dd;padding-top:14px}.shot-index{width:34px;height:34px;border-radius:50%;display:grid;place-items:center;background:#241e1a;color:#fff}.shot-main{display:grid;gap:9px}.shot-main video,.final-card video{width:100%;max-height:420px;border-radius:10px;background:#111}.shot-fields{display:grid;grid-template-columns:120px 1fr 1fr;gap:8px}.shot-actions{display:grid;align-content:start;gap:8px}.error{color:#c23f36}.final-card a{display:inline-block;margin-top:12px;color:#d85c35}@media(max-width:900px){.source-grid,.asset-grid{grid-template-columns:1fr}.shot-list article{grid-template-columns:36px 1fr}.shot-actions{grid-column:2}.shot-fields{grid-template-columns:1fr}}
</style>
