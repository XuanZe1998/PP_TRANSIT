<template>
  <div class="public-site" :class="{ 'home-theme': route.path === '/' }">
    <header class="site-nav">
      <div class="site-nav-inner">
        <button class="site-brand" @click="go('/')">
          <img class="site-brand-mark brand-image" :src="siteConfig.logoUrl" alt="" />
          <span>{{ siteConfig.name }}</span>
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
import { siteConfig } from '@/config/site'

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

.home-theme .site-auth-button {
  border-color: rgba(111, 145, 220, 0.46);
  background: rgba(8, 16, 39, 0.58);
  color: #e8f1ff;
  box-shadow: inset 0 0 0 1px rgba(112, 157, 255, 0.04);
}

.home-theme .site-auth-button.primary {
  border-color: rgba(79, 124, 255, 0.92);
  background: linear-gradient(135deg, #2aa8ff, #4858ff);
  color: #fff;
  box-shadow: 0 10px 28px rgba(55, 103, 255, 0.28);
}

.home-theme .site-auth-button:hover,
.home-theme .site-auth-button:focus-visible {
  border-color: #39c7ff;
  background: rgba(24, 43, 88, 0.72);
}

.home-theme .site-auth-button.primary:hover,
.home-theme .site-auth-button.primary:focus-visible {
  background: linear-gradient(135deg, #42c8ff, #5a64ff);
}
</style>
