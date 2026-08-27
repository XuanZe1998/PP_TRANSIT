<template>
  <el-container class="admin-shell">
    <el-aside width="260px" class="admin-aside">
      <div class="brand" @click="$router.push('/')">
        <div class="brand-mark">AT</div>
        <div class="brand-copy">
          <div class="brand-title">API Transit</div>
          <div class="brand-subtitle">商业运营后台</div>
        </div>
      </div>

      <el-menu :default-active="activeRoute" class="admin-menu" router>
        <template v-for="item in navItems" :key="item.path">
          <el-sub-menu v-if="item.children" :index="item.path">
            <template #title><el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span></template>
            <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">{{ child.label }}</el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="item.path" :title="item.label">
            <el-icon><component :is="item.icon" /></el-icon><span>{{ item.label }}</span>
          </el-menu-item>
        </template>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="admin-header">
        <div class="header-copy">
          <h1>{{ currentTitle }}</h1>
          <p>统一管理渠道、模型、用户、计费、安全、审计和履约。</p>
        </div>
        <div class="header-actions">
          <el-button plain @click="$router.push('/market')">前台模型市场</el-button>
          <el-button type="danger" plain @click="logout">退出登录</el-button>
        </div>
      </el-header>
      <el-main class="admin-main">
        <el-alert v-if="verificationWarning" class="verification-warning" type="warning" :closable="false" show-icon
          title="新用户注册通道未就绪" :description="verificationWarning" />
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/utils/http'
import { clearAuth } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const verificationWarning = ref('')

type NavItem = { path: string; label: string; icon?: string; children?: Array<{ path: string; label: string }> }
const navItems: NavItem[] = [
  { path: '/admin', label: '运营总览', icon: 'DataBoard' },
  { path: 'users-access', label: '用户与权限', icon: 'User', children: [
    { path: '/admin/users', label: '用户与分组' }, { path: '/admin/tokens', label: 'Token 与权限' }
  ] },
  { path: '/admin/model-gateway', label: '模型网关', icon: 'Connection' },
  { path: 'content-services', label: '内容与服务', icon: 'Grid', children: [
    { path: '/admin/creative-config', label: 'AI 创作配置' }, { path: '/admin/other-services', label: '服务与订单' }
  ] },
  { path: 'finance-payment', label: '财务与支付', icon: 'Wallet', children: [
    { path: '/admin/finance', label: '钱包财务' }, { path: '/admin/vmcard-test', label: 'VMCard 测试' }
  ] },
  { path: 'audit-security', label: '审计与安全', icon: 'Lock', children: [
    { path: '/admin/audit-logs', label: '调用审计' }, { path: '/admin/security', label: '安全策略' }
  ] },
  { path: '/admin/settings', label: '系统配置与报表', icon: 'Setting' }
]

const activeRoute = computed(() => {
  if (['/admin/channels', '/admin/models', '/admin/mappings'].includes(route.path)) return '/admin/model-gateway'
  if (route.path === '/admin/reports') return '/admin/settings'
  return route.path
})
const currentTitle = computed(() => navItems.flatMap(item => item.children || [item]).find(item => item.path === activeRoute.value)?.label || '管理员后台')

onMounted(async () => {
  try {
    const { data } = await http.get('/api/admin/api/account-verification-status')
    const needsPhone = data?.mode === 'EMAIL_AND_PHONE'
    const missing = [!data?.emailConfigured ? 'SMTP 邮件' : '', needsPhone && !data?.smsConfigured ? '短信适配器' : ''].filter(Boolean)
    verificationWarning.value = !data?.registrationReady && missing.length ? `尚未配置${missing.join('、')}；生产环境将拒绝新用户注册。` : ''
  } catch { verificationWarning.value = '' }
})

const logout = async () => {
  try {
    await http.post('/api/admin/auth/logout')
  } catch {
    // Local logout should still proceed if the server is unreachable.
  }
  clearAuth()
  router.replace('/admin/login')
}
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
}

.admin-shell > .el-container {
  min-width: 0;
}

.admin-aside {
  padding: 24px 16px;
  border-right: 1px solid #d7e4f7;
  background: linear-gradient(180deg, rgba(250,253,255,.98), rgba(235,244,255,.96));
  color: #183354;
  box-shadow: 12px 0 36px rgba(23,105,255,.06);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
  cursor: pointer;
}

.brand-mark {
  width: 42px;
  height: 42px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #2563eb;
  color: #fff;
  font-weight: 800;
}

.brand-title {
  font-weight: 800;
  color: #102541;
}

.brand-subtitle {
  margin-top: 2px;
  color: #6d7f98;
  font-size: 12px;
}

.admin-menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #4e6380;
  --el-menu-hover-bg-color: #e5f0ff;
  --el-menu-active-color: #1769ff;
}

.admin-menu :deep(.el-menu-item),
.admin-menu :deep(.el-sub-menu__title) {
  height: 42px;
  margin-bottom: 6px;
  border-radius: 8px;
  color: #4e6380;
  font-weight: 600;
}

.admin-menu :deep(.el-menu-item .el-icon),
.admin-menu :deep(.el-sub-menu__title .el-icon),
.admin-menu :deep(.el-sub-menu__icon-arrow) {
  color: #6a7e99;
}

.admin-menu :deep(.el-sub-menu .el-menu-item) {
  min-width: 0;
  padding-left: 48px !important;
  color: #607590;
  font-weight: 500;
}

.admin-menu :deep(.el-menu-item.is-active),
.admin-menu :deep(.el-menu-item.is-active:hover) {
  background: linear-gradient(135deg, #1769ff, #00a9cc);
  color: #fff;
}

.admin-menu :deep(.el-menu-item:hover),
.admin-menu :deep(.el-sub-menu__title:hover),
.admin-menu :deep(.el-sub-menu.is-opened > .el-sub-menu__title) {
  background: #e5f0ff;
  color: #1769ff;
}

.admin-menu :deep(.el-menu-item.is-active .el-icon),
.admin-menu :deep(.el-sub-menu.is-opened > .el-sub-menu__title .el-icon),
.admin-menu :deep(.el-sub-menu.is-opened > .el-sub-menu__title .el-sub-menu__icon-arrow) {
  color: #1769ff;
}

.admin-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 82px;
  padding: 18px 30px;
  background: #fff;
  border-bottom: 1px solid #e5e7eb;
}

.header-copy h1 {
  margin: 0;
  font-size: 24px;
  color: #111827;
}

.header-copy p {
  margin: 6px 0 0;
  color: #6b7280;
}

.header-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.admin-main {
  min-width: 0;
  overflow-x: visible;
  padding: 24px 30px 40px;
  background: transparent;
}

.verification-warning { margin-bottom: 16px; }

@media (max-width: 900px) {
  .admin-aside {
    flex: 0 0 76px;
    width: 76px !important;
    padding: 16px 10px;
    overflow: hidden;
  }

  .brand {
    justify-content: center;
  }

  .brand-copy,
  .admin-menu :deep(.el-menu-item span),
  .admin-menu :deep(.el-sub-menu__title span),
  .admin-menu :deep(.el-sub-menu__icon-arrow) {
    display: none;
  }

  .admin-menu :deep(.el-menu-item),
  .admin-menu :deep(.el-sub-menu__title) {
    justify-content: center;
    padding: 0 !important;
  }

  .admin-menu :deep(.el-menu-item .el-icon),
  .admin-menu :deep(.el-sub-menu__title .el-icon) {
    margin-right: 0;
  }

  .admin-header {
    flex-wrap: wrap;
    padding: 16px 20px;
  }

  .admin-main {
    padding: 18px 16px 32px;
  }
}

@media (max-width: 680px) {
  .admin-header {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }
}
</style>
