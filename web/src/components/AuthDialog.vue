<template>
  <el-dialog v-model="visible" class="auth-dialog" width="min(520px, 92vw)" :show-close="true" :close-on-click-modal="!loading" @closed="closeDialog">
    <div class="auth-dialog-head">
      <span class="auth-dialog-mark">AT</span>
      <div><p>SECURE ACCESS</p><h2>{{ mode === 'register' ? '创建 API Transit 账号' : '登录 API Transit' }}</h2></div>
    </div>
    <el-radio-group v-model="mode" class="auth-mode" @change="changeMode">
      <el-radio-button label="login">登录</el-radio-button><el-radio-button label="register">注册</el-radio-button>
    </el-radio-group>
    <el-form :model="form" label-position="top" @submit.prevent="submit">
      <el-form-item v-if="mode === 'login'" label="账号"><el-input v-model="form.identifier" size="large" autofocus autocomplete="username" placeholder="邮箱 / 手机号" /></el-form-item>
      <template v-else>
        <el-form-item label="显示名称"><el-input v-model="form.displayName" size="large" maxlength="80" autocomplete="nickname" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" size="large" autocomplete="email" placeholder="team@example.com" /></el-form-item>
        <el-form-item label="邮箱验证码"><div class="verify-row"><el-input v-model="form.emailCode" size="large" maxlength="6" inputmode="numeric" /><el-button :disabled="emailCountdown>0" @click="sendCode('email')">{{ emailCountdown ? `${emailCountdown}s` : '发送验证码' }}</el-button></div></el-form-item>
        <el-form-item v-if="requiresPhone" label="手机号"><el-input v-model="form.phone" size="large" autocomplete="tel" placeholder="13800138000 / +8613800138000" /></el-form-item>
        <el-form-item v-if="requiresPhone" label="短信验证码"><div class="verify-row"><el-input v-model="form.phoneCode" size="large" maxlength="6" inputmode="numeric" /><el-button :disabled="phoneCountdown>0" @click="sendCode('phone')">{{ phoneCountdown ? `${phoneCountdown}s` : '发送验证码' }}</el-button></div></el-form-item>
      </template>
      <el-form-item label="密码"><el-input v-model="form.password" size="large" type="password" show-password :autocomplete="mode==='login'?'current-password':'new-password'" placeholder="10–72 字节，包含字母和数字" /></el-form-item>
      <el-form-item v-if="mode === 'register'" label="确认密码"><el-input v-model="form.confirmPassword" size="large" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-button native-type="submit" class="auth-submit" type="primary" size="large" :loading="loading" :disabled="loading">{{ mode === 'register' ? '注册并进入控制台' : '登录控制台' }}</el-button>
    </el-form>
    <el-divider>或使用第三方账号</el-divider>
    <div class="oauth-actions"><el-button :disabled="loading" @click="startOAuth('github')">GitHub</el-button><el-button :disabled="loading" @click="startOAuth('google')">Google</el-button></div>
    <p class="auth-security-note">登录信息通过 HTTPS 发送；连续失败会触发短时锁定。第三方授权会离开本站并在完成后返回。</p>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { setAuth } from '@/utils/auth'
import { clearOAuthState, saveOAuthState, type OAuthProvider } from '@/utils/oauthState'

const route = useRoute(), router = useRouter()
const visible = computed({
  get: () => route.query.auth === 'login' || route.query.auth === 'register',
  set: value => { if (!value) closeDialog() }
})
const mode = ref<'login' | 'register'>(route.query.auth === 'register' ? 'register' : 'login')
const loading = ref(false)
const verificationMode = ref<'EMAIL_ONLY'|'EMAIL_AND_PHONE'>('EMAIL_AND_PHONE')
const requiresPhone = computed(() => verificationMode.value === 'EMAIL_AND_PHONE')
const identifierStatus = ref<{ valid: boolean; available: boolean; type: string } | null>(null)
const form = reactive({ identifier: '', displayName:'', email:'', emailCode:'', phone:'', phoneCode:'', password: '', confirmPassword: '' })
const emailCountdown=ref(0),phoneCountdown=ref(0)
let timer: number | undefined, requestId = 0

watch(() => route.query.auth, value => { if (value === 'login' || value === 'register') mode.value = value })
watch(() => form.identifier, value => {
  window.clearTimeout(timer); const current = ++requestId; identifierStatus.value = null
  if (mode.value !== 'register' || (!isEmail(value) && !isPhone(value))) return
  timer = window.setTimeout(async () => {
    try { const response = await http.get('/api/auth/validate-identifier', { params: { identifier: value.trim() } }); if (current === requestId) identifierStatus.value = response.data }
    catch { if (current === requestId) identifierStatus.value = null }
  }, 300)
})
onBeforeUnmount(() => window.clearTimeout(timer))
onMounted(async () => {
  try {
    const { data } = await http.get('/api/auth/verification/policy')
    if (data?.mode === 'EMAIL_ONLY' || data?.mode === 'EMAIL_AND_PHONE') verificationMode.value = data.mode
  } catch { verificationMode.value = 'EMAIL_AND_PHONE' }
})

const identifierMessage = computed(() => !identifierStatus.value?.valid ? '请输入合法邮箱或手机号' : identifierStatus.value.available ? '该账号可以注册' : '该账号已被注册')
function safeRedirect(value: unknown) {
  const path = String(value || '')
  const forbidden = ['/admin', '/login', '/register', '/auth/callback', '/oauth/callback']
  return path.startsWith('/')
    && !path.startsWith('//')
    && !path.includes('\\')
    && !/[\u0000-\u001f\u007f]/.test(path)
    && !forbidden.some(prefix => path.startsWith(prefix))
    ? path
    : ''
}
function changeMode(value: string | number | boolean | undefined) {
  mode.value = value === 'register' ? 'register' : 'login'; form.confirmPassword = ''; identifierStatus.value = null
  router.replace({ path: route.path, query: { ...route.query, auth: mode.value } })
}
async function sendCode(channel:'email'|'phone'){
  const recipient=channel==='email'?form.email.trim():form.phone.trim()
  if(channel==='email'&&!isEmail(recipient))return ElMessage.warning('请先填写有效邮箱')
  if(channel==='phone'&&!isPhone(recipient))return ElMessage.warning('请先填写有效手机号')
  try{const r=await http.post(`/api/auth/verification/${channel}/send`,{recipient,purpose:'REGISTER'});const debug=r.data?.debugCode;if(debug)ElMessage.info(`本地调试验证码：${debug}`);let left=60;const target=channel==='email'?emailCountdown:phoneCountdown;target.value=left;const id=window.setInterval(()=>{target.value=--left;if(left<=0)window.clearInterval(id)},1000);ElMessage.success('验证码已发送')}
  catch(error){ElMessage.error(getHttpErrorMessage(error,'验证码发送失败'))}
}
function closeDialog() {
  if (loading.value) return
  const query = { ...route.query }; delete query.auth; delete query.redirect
  router.replace({ path: route.path, query })
}
async function submit() {
  if (loading.value) return
  if ((mode.value==='login'&&!form.identifier) || !form.password) return ElMessage.warning('请填写完整的登录信息')
  if (mode.value === 'register') {
    if (!isEmail(form.email) || (requiresPhone.value && !isPhone(form.phone))) return ElMessage.warning(requiresPhone.value ? '请填写有效邮箱和手机号' : '请填写有效邮箱')
    if (!form.emailCode || (requiresPhone.value && !form.phoneCode)) return ElMessage.warning(requiresPhone.value ? '请完成邮箱和短信验证' : '请完成邮箱验证')
    if (!/^(?=.*[A-Za-z])(?=.*\d).{10,}$/.test(form.password) || new TextEncoder().encode(form.password).length>72) return ElMessage.warning('密码需为 10–72 字节，且包含字母和数字')
    if (form.password !== form.confirmPassword) return ElMessage.warning('两次输入的密码不一致')
  }
  loading.value = true
  try {
    const endpoint = mode.value === 'register' ? '/api/auth/register' : '/api/auth/login'
    const registration = requiresPhone.value
      ? { email:form.email.trim(),emailCode:form.emailCode,phone:form.phone.trim(),phoneCode:form.phoneCode,displayName:form.displayName.trim(),password:form.password }
      : { email:form.email.trim(),emailCode:form.emailCode,displayName:form.displayName.trim(),password:form.password }
    const response = await http.post(endpoint, mode.value === 'register' ? registration : { username: form.identifier.trim(), password: form.password })
    const token = response.data?.access_token || response.data?.token
    if (!token) throw new Error('Missing access token')
    setAuth(token, { username: response.data?.username || form.email || form.identifier, role: response.data?.role || 'USER',displayName:response.data?.displayName,avatarPath:response.data?.avatarPath,accountComplete:response.data?.accountComplete }, response.data?.refresh_token)
    ElMessage.success(mode.value === 'register' ? '注册成功' : '登录成功')
    const target=safeRedirect(route.query.redirect)||(response.data?.accountComplete===false?'/console/profile':'/console')
    const query={...route.query};delete query.auth;delete query.redirect
    await router.replace({path:route.path,query})
    if(target!==route.path)await router.push(target)
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, mode.value === 'register' ? '注册失败，请检查填写信息' : '登录失败，请检查账号和密码')) }
  finally { loading.value = false }
}
async function startOAuth(provider: OAuthProvider) {
  try {
    const response = await http.get('/api/oauth/authorize', { params: { provider } })
    const url = String(response.data?.url || ''), state = String(response.data?.state || '')
    const parsed = new URL(url)
    if (!state || parsed.protocol !== 'https:' || parsed.searchParams.get('state') !== state) throw new Error('OAuth 授权地址校验失败')
    saveOAuthState(provider, state, safeRedirect(route.query.redirect) || undefined)
    window.location.assign(parsed.toString())
  } catch (error: unknown) {
    try { clearOAuthState() } catch { /* keep original error */ }
    ElMessage.error(getHttpErrorMessage(error, `${provider} OAuth 尚未配置或暂不可用`))
  }
}
const isEmail = (value: string) => /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(value.trim())
const isPhone = (value: string) => /^(1[3-9]\d{9}|\+[1-9]\d{7,14})$/.test(value.trim())
</script>

<style scoped>
.auth-dialog-head{display:flex;align-items:center;gap:14px;margin-bottom:18px}.auth-dialog-mark{display:grid;width:48px;height:48px;place-items:center;border-radius:14px;background:linear-gradient(135deg,#1769ff,#00b8d9);box-shadow:0 10px 24px rgba(23,105,255,.24);color:#fff;font-weight:900}.auth-dialog-head p{margin:0;color:#0b91b5;font-size:11px;font-weight:800;letter-spacing:.16em}.auth-dialog-head h2{margin:3px 0 0;color:#132846;font-size:24px}.auth-mode{width:100%;margin-bottom:18px}.auth-mode :deep(.el-radio-button){width:50%}.auth-mode :deep(.el-radio-button__inner){width:100%}.auth-submit{width:100%}.oauth-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.auth-security-note{margin:16px 0 0;color:#71829b;font-size:12px;line-height:1.65}.ok{color:#149260}.bad{color:#d73d5b}
.verify-row{display:grid;width:100%;grid-template-columns:minmax(0,1fr) auto;gap:8px}
</style>
