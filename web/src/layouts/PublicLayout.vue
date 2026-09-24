<template>
  <div class="public-site" :class="{ 'home-theme': route.path === '/' }">
    <header class="site-nav">
      <div class="site-nav-inner">
        <button class="site-brand" @click="go('/')">
          <img class="site-brand-mark brand-image" :src="siteConfig.logoUrl" alt="" />
          <span>{{ siteConfig.name }}</span>
        </button>
        <nav class="site-links" aria-label="Primary navigation">
          <button v-for="item in navigationItems" :key="item.path" :class="{ active: route.path === item.path }" @click="go(item.path)">
            {{ item.label }}
          </button>
        </nav>
        <div v-if="loggedIn" class="site-account-desktop"><AccountMenu /></div>
        <div v-else class="site-actions">
          <button class="site-auth-button" type="button" @click="openAuth('login')">{{ t('login') }}</button>
          <button class="site-auth-button primary" type="button" @click="openAuth('register')">{{ t('register') }}</button>
        </div>
        <button class="site-menu-toggle" type="button" :aria-label="t('openMenu')" @click="mobileMenuOpen = true">
          <el-icon><Menu /></el-icon>
        </button>
      </div>
    </header>
    <el-drawer
      v-model="mobileMenuOpen"
      class="site-mobile-drawer"
      direction="rtl"
      size="min(360px, 92vw)"
      :show-close="false"
      append-to-body
    >
      <template #header><span class="sr-only">站点导航</span></template>
      <div class="site-mobile-menu">
        <div class="mobile-menu-head">
          <button class="site-brand" type="button" @click="go('/')">
            <img class="site-brand-mark brand-image" :src="siteConfig.logoUrl" alt="" />
            <span>{{ siteConfig.name }}</span>
          </button>
          <button class="mobile-menu-close" type="button" :aria-label="t('closeMenu')" @click="mobileMenuOpen = false">
            <el-icon><Close /></el-icon>
          </button>
        </div>
        <nav class="site-mobile-links" aria-label="移动端导航">
          <button v-for="item in navigationItems" :key="item.path" :class="{ active: route.path === item.path }" @click="go(item.path)">
            <el-icon><component :is="item.icon" /></el-icon>
            {{ item.label }}
          </button>
        </nav>
        <div class="site-mobile-actions">
          <el-button v-if="loggedIn" type="primary" @click="go('/console')">{{ t('console') }}</el-button>
          <template v-else>
            <el-button @click="openAuth('login')">{{ t('login') }}</el-button>
            <el-button type="primary" @click="openAuth('register')">{{ t('register') }}</el-button>
          </template>
        </div>
      </div>
    </el-drawer>
    <main class="public-page-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Bell, Close, Coin, Document, HomeFilled, MagicStick, Menu, ShoppingBag } from '@element-plus/icons-vue'
import { getToken } from '@/utils/auth'
import { siteConfig } from '@/config/site'
import { t } from '@/i18n/locale'

const route = useRoute()
const router = useRouter()
const AccountMenu = defineAsyncComponent(() => import('@/components/AccountMenu.vue'))
const loggedIn = ref(Boolean(getToken()))
const mobileMenuOpen = ref(false)
const navigationItems = computed(() => [
  { path: '/', label: t('home'), icon: HomeFilled },
  { path: '/market', label: t('market'), icon: Coin },
  { path: '/studio', label: t('studio'), icon: MagicStick },
  { path: '/services', label: t('services'), icon: ShoppingBag },
  { path: '/subscriptions', label: t('subscriptions'), icon: Bell },
  { path: '/docs', label: t('docs'), icon: Document }
])
const refreshAuth = () => { loggedIn.value = Boolean(getToken()) }
const go = (path: string) => {
  mobileMenuOpen.value = false
  return router.push(path)
}
const openAuth = (mode: 'login' | 'register') => router.replace({
  path: route.path,
  query: { ...route.query, auth: mode }
})

watch(() => route.fullPath, () => { mobileMenuOpen.value = false })

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
