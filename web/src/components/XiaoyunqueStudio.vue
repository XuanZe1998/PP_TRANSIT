<template>
  <section class="xyq-studio">
    <h2>小云雀 · 营销成片</h2>
    <p>上传商品素材，描述创作要求，制作剧情广告、品牌大片或达人带货视频。生成消耗你的小云雀积分。</p>
    <el-alert v-if="!loggedIn" title="登录后可配置自己的 Access Key 并开始创作" type="info" :closable="false" />
    <template v-else>
      <el-form label-position="top" @submit.prevent>
        <el-form-item label="我的 Access Key">
          <el-input v-model="accessKey" type="password" show-password autocomplete="new-password" :placeholder="configured ? '已配置；输入新值可更换' : '粘贴你的小云雀 Access Key'" />
          <small>密钥在服务器加密保存，保存后不会回显。已提交任务继续使用提交时的密钥查询。</small>
        </el-form-item>
        <div class="actions"><el-button :disabled="!accessKey.trim()" :loading="busy" @click="saveKey">保存密钥</el-button><el-button v-if="configured" :loading="busy" @click="removeKey">移除密钥</el-button><el-button :disabled="!configured" :loading="busy" @click="loadBalance">查询积分</el-button><span v-if="balance !== null">剩余积分：{{ balance }}</span><a href="https://xyq.jianying.com/cli?tab=api" target="_blank" rel="noopener noreferrer">获取 Access Key ↗</a></div>
        <el-divider />
        <el-form-item label="创作要求"><el-input v-model="form.message" type="textarea" :rows="5" maxlength="10000" show-word-limit placeholder="描述商品卖点、目标人群、视频风格和文案要求…" /></el-form-item>
        <el-alert v-if="form.continuationId" title="正在继续选中的营销会话；清除后将创建新会话" type="info" :closable="false"><el-button text @click="form.continuationId = ''">清除续写</el-button></el-alert>
        <div class="fields">
          <el-form-item label="视频模型"><el-input v-model="settings.video_model" placeholder="填写有权限的视频模型标识" /><small>如 seedance2.0_vision；以小云雀当前权限为准。</small></el-form-item>
          <el-form-item label="画面比例"><el-select v-model="settings.ratio"><el-option v-for="ratio in ratios" :key="ratio.value" :value="ratio.value" :label="ratio.label" /></el-select></el-form-item>
          <el-form-item label="清晰度"><el-select v-model="settings.video_resolution"><el-option v-for="r in ['480p', '720p', '1080p']" :key="r" :value="r" :label="r" /></el-select></el-form-item>
          <el-form-item label="最短时长（秒）"><el-input-number v-model="settings.duration_start" :min="1" :precision="0" /></el-form-item>
          <el-form-item label="最长时长（秒）"><el-input-number v-model="settings.duration_end" :min="settings.duration_start" :precision="0" /></el-form-item>
          <el-form-item label="字幕"><el-switch v-model="settings.show_subtitle" active-text="显示字幕" /></el-form-item>
        </div>
        <el-form-item label="参考素材（可选，最多20个，每个不超过5MB）"><input ref="fileInput" type="file" accept="image/*,video/*,audio/*" :disabled="busy || !configured || assets.length >= 20" @change="upload" /><small role="status">已上传 {{ assets.length }} 个素材{{ lastUpload ? `，最近上传：${lastUpload}` : '' }}</small><el-button v-if="assets.length" text @click="assets = []; lastUpload = ''">清空本次选择</el-button></el-form-item>
        <el-button type="primary" size="large" :disabled="!configured || !form.message.trim() || !settings.video_model.trim()" :loading="busy" @click="submit">开始生成 · 使用小云雀积分</el-button>
      </el-form>
      <el-divider />
      <div class="actions"><h3>我的小云雀作品</h3><el-input v-model="query" clearable placeholder="搜索创作要求" @keyup.enter="search" /><el-button @click="search">搜索</el-button><el-button :loading="loading" @click="loadRuns">刷新列表</el-button></div>
      <p v-if="error" role="alert" class="error">{{ error }}</p>
      <el-empty v-if="!loading && !runs.length" description="还没有营销任务" />
      <article v-for="run in runs" :key="run.id" class="run-card">
        <b>{{ stateLabel(run.state) }}</b><p class="prompt">{{ run.message }}</p><small>{{ run.created_at }}</small>
        <p v-if="run.state === 'UNKNOWN' || run.state === 'SUBMITTING'">提交结果未确认，请先在小云雀检查，避免重复生成和扣积分。</p>
        <p v-if="['6','9'].includes(run.state)">需要补充输入或人工确认，请打开小云雀会话处理。原任务不会自动重复提交。</p>
        <p v-if="run.state === '3' && !hasMedia(run)">本次运行已完成但没有返回媒体，可在小云雀会话查看下一步。</p>
        <p v-if="run.result?.fail_reason?.message" class="error">{{ run.result.fail_reason.message }}</p>
        <div class="actions"><el-button v-if="run.run_id" size="small" :loading="refreshing === run.id" @click="refresh(run)">查询进度</el-button><el-button v-if="hasMedia(run)" size="small" @click="openMedia(run)">预览与下载</el-button><el-button v-if="run.thread_id" size="small" @click="continueRun(run)">继续这个会话</el-button><a v-if="threadLink(run)" :href="threadLink(run)" target="_blank" rel="noopener noreferrer">打开小云雀会话 ↗</a></div>
      </article>
      <ListPagination v-model:page="page" v-model:size="size" v-model:all="all" :total="total" @change="loadRuns" />
    </template>
    <el-dialog v-model="mediaDialog" title="成片与图片" width="min(900px, 94vw)">
      <div v-for="media in mediaItems" :key="media.url" class="media"><video v-if="media.type === 'video'" :src="media.url" controls preload="metadata" /><img v-else :src="media.url" alt="小云雀生成图片" /><a :href="media.url" target="_blank" rel="noopener noreferrer" download>打开或下载{{ media.type === 'video' ? '视频' : '图片' }} ↗</a></div>
      <ListPagination v-model:page="mediaPage" v-model:size="mediaSize" v-model:all="mediaAll" :total="mediaTotal" @change="loadMedia" />
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ListPagination from './ListPagination.vue'
import http, { getHttpErrorMessage } from '@/utils/http'
import { getToken } from '@/utils/auth'
type Run = { id: string; message: string; state: string; run_id?: string; thread_id?: string; created_at: string; result?: any }
const api = '/creative/xiaoyunque'
const loggedIn = Boolean(getToken()), configured = ref(false), accessKey = ref(''), busy = ref(false), loading = ref(false)
const balance = ref<string | null>(null), error = ref(''), refreshing = ref(''), fileInput = ref<HTMLInputElement>()
const assets = ref<string[]>([]), lastUpload = ref(''), runs = ref<Run[]>([])
const page = ref(1), size = ref(10), all = ref(false), total = ref(0), query = ref(''), appliedQuery = ref('')
const form = reactive({message: '', continuationId: ''})
const settings = reactive({video_model: '', ratio: 3, duration_start: 15, duration_end: 30, show_subtitle: true, video_resolution: '720p'})
const ratios = [{value:2,label:'16:9'},{value:3,label:'9:16'},{value:4,label:'4:3'},{value:5,label:'3:4'},{value:6,label:'1:1'}]
const mediaDialog = ref(false), mediaRunId = ref(''), mediaPage = ref(1), mediaSize = ref(10), mediaAll = ref(false), mediaTotal = ref(0)
const mediaItems = ref<Array<{type:string;url:string}>>([])
let timer: ReturnType<typeof setInterval> | undefined, generation = 0, mediaGeneration = 0, pollCursor = 0
onMounted(async () => {
  if (!loggedIn) return
  try { configured.value = (await http.get(`${api}/settings`)).data.configured; await loadRuns() }
  catch (e) { error.value = getHttpErrorMessage(e, '小云雀配置加载失败') }
  timer = setInterval(() => { if (!loading.value && !refreshing.value) { const active = runs.value.filter(r => ['1','2','7'].includes(r.state)); const next = active.length ? active[pollCursor++ % active.length] : undefined; if (next) void refresh(next, true) } }, 10000)
})
onBeforeUnmount(() => { if (timer) clearInterval(timer); generation++; mediaGeneration++ })
async function action(work:()=>Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''; try { await work() } catch(e) { error.value = getHttpErrorMessage(e, '小云雀操作失败'); ElMessage.error(error.value) } finally { busy.value = false } }
async function saveKey() { await action(async()=> { await http.put(`${api}/settings`, {accessKey: accessKey.value}); accessKey.value = ''; configured.value = true; assets.value = []; lastUpload.value = ''; balance.value = null; ElMessage.success('Access Key 已加密保存') }) }
async function removeKey() { try { await ElMessageBox.confirm('移除后不能提交新任务，已提交任务仍可查询。', '移除 Access Key', {type:'warning'}) } catch { return } await action(async()=> { await http.delete(`${api}/settings`); configured.value = false; accessKey.value = ''; balance.value = null; assets.value = []; lastUpload.value = '' }) }
async function loadBalance() { await action(async()=> { const {data} = await http.post(`${api}/balance`); balance.value = String(data.total_remain_amount ?? '暂不可用') }) }
async function upload(event:Event) { const input = event.target as HTMLInputElement; const file = input.files?.[0]; if (!file) return; if (file.size > 5*1024*1024 || !file.size) { input.value=''; return ElMessage.warning('请选择非空且不超过5MB的素材') } await action(async()=> { const body = new FormData(); body.append('file',file); const {data} = await http.post(`${api}/assets`,body,{timeout:90000}); assets.value.push(data.id); lastUpload.value = data.name }); input.value='' }
async function submit() { if (settings.duration_start > settings.duration_end) return ElMessage.warning('时长下限不能大于上限'); await action(async()=> { await http.post(`${api}/runs`, {...form, asset_ids:assets.value, general_agent_settings:{...settings}}, {timeout:75000}); assets.value=[]; lastUpload.value=''; form.continuationId=''; page.value=1; await loadRuns(); ElMessage.success('营销任务已提交') }); await loadRuns() }
function search() { appliedQuery.value = query.value; page.value = 1; void loadRuns() }
async function loadRuns() { const version=++generation; loading.value=true; try { const {data}=await http.get(`${api}/runs`,{params:{page:page.value,size:size.value,all:all.value,q:appliedQuery.value}}); if(version!==generation)return; runs.value=data.items; total.value=data.total; page.value=data.page } catch(e) { if(version===generation)error.value=getHttpErrorMessage(e,'作品加载失败') } finally { if(version===generation)loading.value=false } }
async function refresh(run:Run, quiet=false) { if (refreshing.value) return; refreshing.value=run.id; try { const {data}=await http.post(`${api}/runs/${run.id}/refresh`,{}, {timeout:75000}); runs.value=runs.value.map(r=>r.id===run.id?data:r) } catch(e) { if(!quiet)ElMessage.error(getHttpErrorMessage(e,'查询失败')) } finally { refreshing.value='' } }
function stateLabel(state:string) { return ({SUBMITTING:'正在提交',UNKNOWN:'提交待确认','0':'状态未指定','1':'已提交','2':'处理中','3':'本次运行完成','4':'失败','5':'已取消','6':'等待补充输入','7':'生成中','8':'等待工具回调','9':'等待人工确认'} as Record<string,string>)[state] || `未知状态 ${state}，请手动核对` }
function hasMedia(run:Run) { return run.state==='3' && ((run.result?.video_urls?.length || 0) + (run.result?.image_urls?.length || 0) > 0) }
function threadLink(run:Run) { try { const url = new URL(run.result?.web_thread_link || ''); return url.protocol==='https:' && url.hostname==='xyq.jianying.com' ? url.href : '' } catch {return ''} }
function continueRun(run:Run) { form.continuationId=run.id; form.message=''; ElMessage.info('已选择原会话，请填写补充要求后提交') }
async function openMedia(run:Run) { mediaRunId.value=run.id; mediaPage.value=1; mediaDialog.value=true; await loadMedia() }
async function loadMedia() { const version=++mediaGeneration; try { const {data}=await http.get(`${api}/runs/${mediaRunId.value}/media`,{params:{page:mediaPage.value,size:mediaSize.value,all:mediaAll.value}}); if(version!==mediaGeneration)return; mediaItems.value=data.items; mediaTotal.value=data.total; mediaPage.value=data.page } catch(e) { ElMessage.error(getHttpErrorMessage(e,'媒体加载失败')) } }
</script>

<style scoped>
.xyq-studio{min-width:0}.xyq-studio>p,small{color:var(--el-text-color-secondary);line-height:1.7}small{display:block}.actions{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.actions .el-input{max-width:260px}.fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin-top:20px}.fields .el-select{width:100%}.run-card{padding:18px;margin-top:12px;border:1px solid var(--el-border-color);border-radius:12px}.prompt{white-space:pre-wrap;overflow-wrap:anywhere}.error{color:var(--el-color-danger)}a{color:var(--el-color-primary)}.media{display:grid;gap:8px;margin-bottom:20px}.media video,.media img{width:100%;max-height:65vh;object-fit:contain}@media(max-width:700px){.fields{grid-template-columns:1fr}}
</style>
