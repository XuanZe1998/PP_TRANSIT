<template>
  <div class="public-site">
    <header class="site-nav">
      <div class="site-nav-inner">
        <button class="site-brand" @click="go('/')">
          <span class="site-brand-mark">A</span>
          <span>API Transit</span>
        </button>
        <nav class="site-links" aria-label="Primary navigation">
          <button :class="{ active: route.path === '/' }" @click="go('/')">首页</button>
          <button :class="{ active: route.path === '/market' }" @click="go('/market')">模型广场</button>
          <button :class="{ active: route.path === '/studio' }" @click="go('/studio')">AI创作</button>
          <button :class="{ active: route.path === '/services' }" @click="go('/services')">其他服务</button>
          <button :class="{ active: route.path === '/pricing' }" @click="go('/pricing')">套餐价格</button>
          <button :class="{ active: route.path === '/docs' }" @click="go('/docs')">开发文档</button>
        </nav>
        <AccountMenu v-if="loggedIn" />
        <div v-else class="site-actions">
          <button class="site-auth-button" type="button" @click="openAuth('login')">登录</button>
          <button class="site-auth-button primary" type="button" @click="openAuth('register')">免费接入</button>
        </div>
      </div>
    </header>
    <main class="public-page-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { defineAsyncComponent, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getToken } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const AccountMenu = defineAsyncComponent(() => import('@/components/AccountMenu.vue'))
const loggedIn = ref(Boolean(getToken()))
const refreshAuth = () => { loggedIn.value = Boolean(getToken()) }
const go = (path: string) => router.push(path)
const openAuth = (mode: 'login' | 'register') => router.replace({
  path: route.path,
  query: { ...route.query, auth: mode }
})

onMounted(() => {
  window.addEventListener('auth-changed', refreshAuth)
  window.addEventListener('storage', refreshAuth)
})
onBeforeUnmount(() => {
  window.removeEventListener('auth-changed', refreshAuth)
  window.removeEventListener('storage', refreshAuth)
})
</script>

<style scoped>
.site-auth-button {
  min-height: 40px;
  padding: 0 16px;
  border: 1px solid #cfdced;
  border-radius: 8px;
  background: #fff;
  color: #38506c;
  cursor: pointer;
}

.site-auth-button.primary {
  border-color: #9fc5f5;
  background: #e2efff;
  color: #0d4f9f;
}

.site-auth-button:hover,
.site-auth-button:focus-visible {
  border-color: #75acef;
  outline: none;
}
</style>
