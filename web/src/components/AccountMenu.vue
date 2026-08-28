<template>
  <div class="account-menu">
    <template v-if="loggedIn">
      <el-dropdown trigger="click" @command="command">
        <button class="account-trigger" aria-label="账户菜单"><img v-if="profile.avatarPath" :src="profile.avatarPath" alt=""><span v-else>{{ initial }}</span><b>{{ profile.displayName || profile.username || '我的账户' }}</b></button>
        <template #dropdown><el-dropdown-menu><el-dropdown-item command="/console/profile">个人中心</el-dropdown-item><el-dropdown-item v-if="profile.accountType==='ENTERPRISE'" command="/console/organization">企业控制台</el-dropdown-item><el-dropdown-item command="/console">控制台</el-dropdown-item><el-dropdown-item command="/console/keys">API Key</el-dropdown-item><el-dropdown-item command="/console/wallet">钱包充值</el-dropdown-item><el-dropdown-item command="/services">我的订单</el-dropdown-item><el-dropdown-item command="/console/docs">开发文档</el-dropdown-item><el-dropdown-item divided command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
      </el-dropdown>
    </template>
    <template v-else><el-button @click="$emit('login')">登录</el-button><el-button type="primary" @click="$emit('register')">免费接入</el-button></template>
  </div>
</template>
<script setup lang="ts">
import { computed,onBeforeUnmount,onMounted,reactive,ref } from 'vue'
import { useRouter } from 'vue-router'
import http from '@/utils/http'
import { clearAuth,getToken,getUser } from '@/utils/auth'
defineEmits<{login:[];register:[]}>();const router=useRouter(),loggedIn=ref(Boolean(getToken())),profile=reactive<any>({})
const initial=computed(()=>String(profile.displayName||profile.username||getUser()?.username||'用').slice(0,1).toUpperCase())
async function refresh(){loggedIn.value=Boolean(getToken());Object.assign(profile,getUser()||{});if(loggedIn.value)try{Object.assign(profile,(await http.get('/api/user/profile')).data||{})}catch{/* session interceptor handles expiry */}}
async function command(value:string){if(value==='logout'){try{await http.post('/api/auth/logout')}catch{}clearAuth('user');loggedIn.value=false;router.push('/');return}router.push(value)}
onMounted(()=>{window.addEventListener('auth-changed',refresh);window.addEventListener('storage',refresh);refresh()});onBeforeUnmount(()=>{window.removeEventListener('auth-changed',refresh);window.removeEventListener('storage',refresh)})
</script>
<style scoped>.account-menu{display:flex;min-height:42px;align-items:center;gap:8px}.account-menu :deep(.el-tooltip__trigger){display:flex;align-items:center}.account-trigger{display:inline-flex;height:40px;line-height:1;align-items:center;gap:9px;max-width:190px;padding:4px 12px 4px 4px;border:1px solid #cfe0f5;border-radius:999px;background:#f7fbff;color:#17385f;cursor:pointer}.account-trigger>span,.account-trigger img{display:grid;width:32px;height:32px;flex:0 0 32px;line-height:32px;place-items:center;border-radius:50%;object-fit:cover;background:#deebff;color:#1254ad;font-weight:800}.account-trigger b{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:13px;line-height:20px}@media(max-width:680px){.account-trigger b{display:none}.account-trigger{width:40px;padding:4px}.account-trigger>span,.account-trigger img{width:30px;height:30px;flex-basis:30px}}</style>
