<template><el-dialog v-model="visible" title="协议更新确认" width="min(620px,94vw)" :close-on-click-modal="false" :close-on-press-escape="false" :show-close="false"><p>继续使用受保护功能前，请阅读并接受当前版本的用户协议与隐私政策。</p><p><router-link target="_blank" to="/terms">用户协议（{{ legal?.terms_version }}）</router-link> · <router-link target="_blank" to="/privacy">隐私政策（{{ legal?.privacy_version }}）</router-link></p><el-checkbox v-model="accepted">我已阅读并接受上述协议</el-checkbox><template #footer><el-button type="primary" :disabled="!accepted" :loading="saving" @click="confirm">接受并继续</el-button></template></el-dialog></template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { getToken } from '@/utils/auth'
const route=useRoute(),visible=ref(false),accepted=ref(false),saving=ref(false),legal=ref<any>(null)
async function check(){
  if(!getToken()||route.meta.role!=='user')return
  try{const[p,l]=await Promise.all([http.get('/api/user/profile'),http.get('/api/public/legal')]);legal.value=l.data;visible.value=p.data?.agreementRequired===true}catch{/* interceptor handles expired sessions */}
}
async function confirm(){
  saving.value=true
  try{await http.post('/api/user/legal/accept',{termsVersion:legal.value.terms_version,privacyVersion:legal.value.privacy_version});visible.value=false;ElMessage.success('协议已确认')}
  catch(e){ElMessage.error(getHttpErrorMessage(e,'协议确认失败'));await check()}
  finally{saving.value=false}
}
onMounted(()=>{check();window.addEventListener('auth-changed',check)})
onBeforeUnmount(()=>window.removeEventListener('auth-changed',check))
watch(()=>route.fullPath,check)
</script>
