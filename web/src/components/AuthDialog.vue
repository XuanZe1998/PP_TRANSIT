<template>
  <el-dialog v-model="visible" class="auth-dialog" width="min(520px, 92vw)" :show-close="true" :close-on-click-modal="!loading" @closed="closeDialog">
    <div class="auth-dialog-head">
      <span class="auth-dialog-mark"><img :src="siteConfig.logoUrl" alt="" /></span>
      <div><p>SECURE ACCESS</p><h2>{{ authTitle }}</h2></div>
    </div>
    <el-radio-group v-if="!ipChallengeId && mode !== 'reset'" v-model="mode" class="auth-mode" @change="changeMode">
      <el-radio-button value="login">登录</el-radio-button><el-radio-button value="register">注册</el-radio-button>
    </el-radio-group>
    <el-form v-if="ipChallengeId" label-position="top" @submit.prevent="verifyLoginIp">
      <el-alert type="warning" :closable="false" :title="`检测到新的登录 IP，验证码已发送至 ${maskedEmail}`" />
      <el-form-item label="邮箱验证码"><el-input v-model="ipVerifyCode" maxlength="6" inputmode="numeric" autocomplete="one-time-code" /></el-form-item>
      <el-button native-type="submit" class="auth-submit" type="primary" size="large" :loading="loading">验证并登录</el-button>
      <el-button class="auth-submit secondary" @click="ipChallengeId=''">返回登录</el-button>
    </el-form>
    <el-form v-else-if="mode === 'reset'" :model="resetForm" label-position="top" @submit.prevent="confirmPasswordReset">
      <el-alert title="使用注册邮箱接收验证码并设置新密码。为保护账号，无论邮箱是否存在都会显示相同提示。" type="info" :closable="false" />
      <el-form-item label="注册邮箱">
        <el-input v-model="resetForm.email" size="large" autocomplete="email" placeholder="team@example.com" />
      </el-form-item>
      <el-form-item label="邮箱验证码">
        <div class="verify-row">
          <el-input v-model="resetForm.code" size="large" maxlength="6" inputmode="numeric" autocomplete="one-time-code" />
          <el-button :disabled="resetCountdown > 0 || loading" @click="requestPasswordReset">{{ resetCountdown ? `${resetCountdown}s` : '发送验证码' }}</el-button>
        </div>
      </el-form-item>
      <el-form-item label="新密码"><el-input v-model="resetForm.password" size="large" type="password" show-password autocomplete="new-password" placeholder="10–72 字节，包含字母和数字" /></el-form-item>
      <el-form-item label="确认新密码"><el-input v-model="resetForm.confirmPassword" size="large" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-button native-type="submit" class="auth-submit" type="primary" size="large" :loading="loading">重置密码</el-button>
      <el-button class="auth-submit secondary" @click="changeMode('login')">返回登录</el-button>
    </el-form>
    <el-form v-else :model="form" label-position="top" @submit.prevent="submit">
      <el-form-item v-if="mode === 'login'" label="账号"><el-input v-model="form.identifier" size="large" autofocus autocomplete="username" placeholder="邮箱 / 手机号" /></el-form-item>
      <template v-else>
        <el-form-item label="账户类型"><el-radio-group v-model="form.accountType"><el-radio-button value="PERSONAL">个人用户</el-radio-button><el-radio-button value="ENTERPRISE">企业用户</el-radio-button></el-radio-group></el-form-item>
        <el-form-item v-if="form.accountType==='ENTERPRISE'" label="企业名称"><el-input v-model="form.companyName" size="large" maxlength="160" autocomplete="organization" /></el-form-item>
        <el-form-item :label="form.accountType==='ENTERPRISE'?'联系人姓名':'显示名称'"><el-input v-model="form.contactName" size="large" maxlength="80" autocomplete="name" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" size="large" autocomplete="email" placeholder="team@example.com" /></el-form-item>
        <el-form-item v-if="form.inviteCode" label="代理邀请码"><el-input v-model="form.inviteCode" size="large" readonly /></el-form-item>
        <el-form-item label="邮箱验证码"><div class="verify-row"><el-input v-model="form.emailCode" size="large" maxlength="6" inputmode="numeric" /><el-button :disabled="emailCountdown>0" @click="sendCode('email')">{{ emailCountdown ? `${emailCountdown}s` : '发送验证码' }}</el-button></div></el-form-item>
        <el-form-item v-if="phoneRequired" label="手机号"><el-input v-model="form.phone" size="large" autocomplete="tel" placeholder="13800138000 / +8613800138000" /></el-form-item>
        <el-form-item v-if="requiresPhone && form.accountType==='PERSONAL'" label="短信验证码"><div class="verify-row"><el-input v-model="form.phoneCode" size="large" maxlength="6" inputmode="numeric" /><el-button :disabled="phoneCountdown>0" @click="sendCode('phone')">{{ phoneCountdown ? `${phoneCountdown}s` : '发送验证码' }}</el-button></div></el-form-item>
      </template>
      <el-form-item label="密码"><el-input v-model="form.password" size="large" type="password" show-password :autocomplete="mode==='login'?'current-password':'new-password'" placeholder="10–72 字节，包含字母和数字" /></el-form-item>
      <div v-if="mode === 'login'" class="forgot-password-row"><el-button link type="primary" @click="changeMode('reset')">忘记密码？</el-button></div>
      <el-form-item v-if="mode === 'register'" label="确认密码"><el-input v-model="form.confirmPassword" size="large" type="password" show-password autocomplete="new-password" /></el-form-item>
      <el-checkbox v-if="mode==='register'" v-model="form.acceptedAgreements" class="agreement-check">我已阅读并接受 <router-link target="_blank" to="/terms">用户协议</router-link> 和 <router-link target="_blank" to="/privacy">隐私政策</router-link></el-checkbox>
      <el-button native-type="submit" class="auth-submit" type="primary" size="large" :loading="loading" :disabled="loading">{{ mode === 'register' ? '注册并进入控制台' : '登录控制台' }}</el-button>
    </el-form>
    <template v-if="mode !== 'reset'">
      <el-divider>或使用第三方账号</el-divider>
      <div class="oauth-actions"><el-button :disabled="loading" @click="startOAuth('github')">GitHub</el-button><el-button :disabled="loading" @click="startOAuth('google')">Google</el-button></div>
      <p class="auth-security-note">登录信息通过 HTTPS 发送；连续失败会触发短时锁定。第三方授权会离开本站并在完成后返回。</p>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { setAuth } from '@/utils/auth'
import { clearOAuthState, saveOAuthState, type OAuthProvider } from '@/utils/oauthState'
import { siteConfig } from '@/config/site'

const route = useRoute(), router = useRouter()
const visible = computed({
  get: () => route.query.auth === 'login' || route.query.auth === 'register' || route.query.auth === 'reset',
  set: value => { if (!value) closeDialog() }
})
type AuthMode = 'login' | 'register' | 'reset'
const mode = ref<AuthMode>(route.query.auth === 'register' ? 'register' : route.query.auth === 'reset' ? 'reset' : 'login')
const authTitle = computed(() => mode.value === 'register' ? `创建 ${siteConfig.name} 账号` : mode.value === 'reset' ? '找回密码' : `登录 ${siteConfig.name}`)
const loading = ref(false)
const verificationMode = ref<'EMAIL_ONLY'|'EMAIL_AND_PHONE'>('EMAIL_AND_PHONE')
const requiresPhone = computed(() => verificationMode.value === 'EMAIL_AND_PHONE')
const phoneRequired = computed(() => form.accountType === 'ENTERPRISE' || requiresPhone.value)
const identifierStatus = ref<{ valid: boolean; available: boolean; type: string } | null>(null)
const form = reactive({ identifier: '', accountType:'PERSONAL' as 'PERSONAL'|'ENTERPRISE',companyName:'',contactName:'',email:'', emailCode:'', phone:'', phoneCode:'', password: '', confirmPassword: '',acceptedAgreements:false,inviteCode:String(route.query.aff || '').trim().slice(0,32) })
const resetForm = reactive({ email: '', code: '', password: '', confirmPassword: '' })
const legal=ref<any>(null),ipChallengeId=ref(''),maskedEmail=ref(''),ipVerifyCode=ref('')
const emailCountdown=ref(0),phoneCountdown=ref(0),resetCountdown=ref(0)
let timer: number | undefined, requestId = 0

watch(() => route.query.auth, value => { if (value === 'login' || value === 'register' || value === 'reset') mode.value = value })
watch(() => form.identifier, value => {
  window.clearTimeout(timer); const current = ++requestId; identifierStatus.value = null
  if (mode.value !== 'register' || (!isEmail(value) && !isPhone(value))) return
  timer = window.setTimeout(async () => {
    try { const response = await http.get('/api/auth/validate-identifier', { params: { identifier: value.trim() } }); if (current === requestId) identifierStatus.value = response.data }
    catch { if (current === requestId) identifierStatus.value = null }
  }, 300)
})
function receiveIpChallenge(event: Event){const detail=(event as CustomEvent).detail||{};ipChallengeId.value=String(detail.challengeId||'');maskedEmail.value=String(detail.maskedEmail||'邮箱');mode.value='login'}
onBeforeUnmount(() => { window.clearTimeout(timer); window.removeEventListener('ip-login-challenge',receiveIpChallenge) })
onMounted(async () => {
  window.addEventListener('ip-login-challenge',receiveIpChallenge)
  try {
    const { data } = await http.get('/api/auth/verification/policy')
    if (data?.mode === 'EMAIL_ONLY' || data?.mode === 'EMAIL_AND_PHONE') verificationMode.value = data.mode
  } catch { verificationMode.value = 'EMAIL_AND_PHONE' }
  try{legal.value=(await http.get('/api/public/legal')).data}catch{legal.value=null}
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
  mode.value = value === 'register' ? 'register' : value === 'reset' ? 'reset' : 'login'; form.confirmPassword = ''; identifierStatus.value = null
  router.replace({ path: route.path, query: { ...route.query, auth: mode.value } })
}

async function requestPasswordReset() {
  if (!isEmail(resetForm.email)) return ElMessage.warning('请先填写有效邮箱')
  loading.value = true
  try {
    const response = await http.post('/api/auth/password-reset/request', { email: resetForm.email.trim() })
    if (response.data?.debugCode) ElMessage.info(`本地调试验证码：${response.data.debugCode}`)
    ElMessage.success(response.data?.message || '如邮箱已注册，验证码将发送至该邮箱')
    let left = 60
    resetCountdown.value = left
    const id = window.setInterval(() => { resetCountdown.value = --left; if (left <= 0) window.clearInterval(id) }, 1000)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '密码重置申请失败'))
  } finally {
    loading.value = false
  }
}

async function confirmPasswordReset() {
  if (!isEmail(resetForm.email) || !/^\d{6}$/.test(resetForm.code)) return ElMessage.warning('请填写有效邮箱和 6 位验证码')
  if (!/^(?=.*[A-Za-z])(?=.*\d).{10,}$/.test(resetForm.password) || new TextEncoder().encode(resetForm.password).length > 72) return ElMessage.warning('密码需为 10–72 字节，且包含字母和数字')
  if (resetForm.password !== resetForm.confirmPassword) return ElMessage.warning('两次输入的密码不一致')
  loading.value = true
  try {
    await http.post('/api/auth/password-reset/confirm', resetForm)
    ElMessage.success('密码已重置，请使用新密码登录')
    form.identifier = resetForm.email.trim()
    form.password = ''
    resetForm.code = ''; resetForm.password = ''; resetForm.confirmPassword = ''
    await changeMode('login')
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '验证码无效或已过期'))
  } finally {
    loading.value = false
  }
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
    if (!form.contactName.trim() || (form.accountType==='ENTERPRISE'&&!form.companyName.trim())) return ElMessage.warning(form.accountType==='ENTERPRISE'?'请填写企业名称和联系人姓名':'请填写显示名称')
    if (!isEmail(form.email) || (phoneRequired.value && !isPhone(form.phone))) return ElMessage.warning(phoneRequired.value ? '请填写有效邮箱和手机号' : '请填写有效邮箱')
    if (!form.emailCode || (requiresPhone.value && form.accountType==='PERSONAL' && !form.phoneCode)) return ElMessage.warning(requiresPhone.value && form.accountType==='PERSONAL' ? '请完成邮箱和短信验证' : '请完成邮箱验证')
    if (!/^(?=.*[A-Za-z])(?=.*\d).{10,}$/.test(form.password) || new TextEncoder().encode(form.password).length>72) return ElMessage.warning('密码需为 10–72 字节，且包含字母和数字')
    if (form.password !== form.confirmPassword) return ElMessage.warning('两次输入的密码不一致')
    if(!form.acceptedAgreements||!legal.value)return ElMessage.warning('请阅读并接受当前用户协议和隐私政策')
  }
  loading.value = true
  try {
    const endpoint = mode.value === 'register' ? '/api/auth/register' : '/api/auth/login'
    const registration = {accountType:form.accountType,companyName:form.companyName.trim(),contactName:form.contactName.trim(),email:form.email.trim(),emailCode:form.emailCode,phone:form.phone.trim(),phoneCode:form.phoneCode,password:form.password,confirmPassword:form.confirmPassword,acceptedAgreements:form.acceptedAgreements,termsVersion:legal.value?.terms_version,privacyVersion:legal.value?.privacy_version,inviteCode:form.inviteCode}
    const response = await http.post(endpoint, mode.value === 'register' ? registration : { username: form.identifier.trim(), password: form.password })
    if(response.data?.verificationRequired){ipChallengeId.value=String(response.data.challengeId);maskedEmail.value=String(response.data.maskedEmail||'邮箱');ElMessage.warning('请验证新的登录 IP');return}
    const token = response.data?.access_token || response.data?.token
    if (!token) throw new Error('Missing access token')
    setAuth(token, { username: response.data?.username || form.email || form.identifier, role: response.data?.role || 'USER',displayName:response.data?.displayName,avatarPath:response.data?.avatarPath,accountComplete:response.data?.accountComplete,accountType:response.data?.accountType }, response.data?.refresh_token)
    ElMessage.success(mode.value === 'register' ? '注册成功' : '登录成功')
    const target=safeRedirect(route.query.redirect)||(response.data?.accountComplete===false?'/console/profile':response.data?.accountType==='ENTERPRISE'?'/console/organization':'/console')
    const query={...route.query};delete query.auth;delete query.redirect
    await router.replace({path:route.path,query})
    if(target!==route.path)await router.push(target)
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, mode.value === 'register' ? '注册失败，请检查填写信息' : '登录失败，请检查账号和密码')) }
  finally { loading.value = false }
}
async function verifyLoginIp(){if(!/^\d{6}$/.test(ipVerifyCode.value))return ElMessage.warning('请输入 6 位验证码');loading.value=true;try{const response=await http.post('/api/auth/login/ip-verify',{challengeId:ipChallengeId.value,code:ipVerifyCode.value});const token=response.data?.access_token;if(!token)throw new Error('Missing access token');setAuth(token,{username:response.data?.username||form.identifier,role:response.data?.role||'USER',displayName:response.data?.displayName,avatarPath:response.data?.avatarPath,accountType:response.data?.accountType},response.data?.refresh_token);ElMessage.success('验证成功');ipChallengeId.value='';await router.push(response.data?.accountType==='ENTERPRISE'?'/console/organization':'/console')}catch(e){ElMessage.error(getHttpErrorMessage(e,'登录地址验证失败'))}finally{loading.value=false}}
async function startOAuth(provider: OAuthProvider) {
  try {
    const response = await http.get('/api/oauth/authorize', {
      params: { provider, inviteCode: mode.value === 'register' ? form.inviteCode : undefined }
    })
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
.auth-dialog-head{display:flex;align-items:center;gap:14px;margin-bottom:18px}.auth-dialog-mark{display:grid;width:48px;height:48px;place-items:center;border-radius:14px;background:#eef6ff;box-shadow:0 10px 24px rgba(23,105,255,.18)}.auth-dialog-mark img{width:42px;height:42px;object-fit:contain}.auth-dialog-head p{margin:0;color:#0b91b5;font-size:11px;font-weight:800;letter-spacing:.16em}.auth-dialog-head h2{margin:3px 0 0;color:#132846;font-size:24px}.auth-mode{width:100%;margin-bottom:18px}.auth-mode :deep(.el-radio-button){width:50%}.auth-mode :deep(.el-radio-button__inner){width:100%}.auth-submit{width:100%}.oauth-actions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.auth-security-note{margin:16px 0 0;color:#71829b;font-size:12px;line-height:1.65}.ok{color:#149260}.bad{color:#d73d5b}
.verify-row{display:grid;width:100%;grid-template-columns:minmax(0,1fr) auto;gap:8px}
.agreement-check{margin:0 0 16px;white-space:normal}.agreement-check a{color:#2563eb}.auth-submit.secondary{margin:10px 0 0;width:100%}
.forgot-password-row{display:flex;justify-content:flex-end;margin:-12px 0 10px}
</style>
