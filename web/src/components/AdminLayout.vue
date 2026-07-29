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
        <el-menu-item v-for="item in navItems" :key="item.path" :index="item.path" :title="item.label">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </el-menu-item>
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
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import http from '@/utils/http'
import { clearAuth } from '@/utils/auth'

const route = useRoute()
const router = useRouter()

const navItems = [
  { path: '/admin', label: '运营总览', icon: 'DataBoard' },
  { path: '/admin/users', label: '用户与分组', icon: 'User' },
  { path: '/admin/channels', label: '渠道治理', icon: 'Connection' },
  { path: '/admin/models', label: '模型与定价', icon: 'Switch' },
  { path: '/admin/tokens', label: 'Token 与权限', icon: 'Key' },
  { path: '/admin/audit-logs', label: '调用审计', icon: 'Document' },
  { path: '/admin/finance', label: '钱包财务', icon: 'Wallet' },
  { path: '/admin/other-services', label: '服务与订单', icon: 'Grid' },
  { path: '/admin/payment-link', label: '支付链接生成', icon: 'Link' },
  { path: '/admin/vmcard-test', label: 'VMCard 接口测试', icon: 'CreditCard' },
  { path: '/admin/security', label: '安全策略', icon: 'Lock' },
  { path: '/admin/settings', label: '系统配置与报表', icon: 'Setting' }
]

const activeRoute = computed(() => {
  if (route.path === '/admin/mappings') return '/admin/models'
  if (route.path === '/admin/reports') return '/admin/settings'
  return route.path
})
const currentTitle = computed(() => navItems.find(item => item.path === activeRoute.value)?.label || '管理员后台')

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
  background: #111827;
  color: #e5e7eb;
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
  color: #f9fafb;
}

.brand-subtitle {
  margin-top: 2px;
  color: #9ca3af;
  font-size: 12px;
}

.admin-menu {
  border-right: none;
  background: transparent;
}

.admin-menu :deep(.el-menu-item) {
  height: 42px;
  margin-bottom: 6px;
  border-radius: 8px;
  color: #d1d5db;
}

.admin-menu :deep(.el-menu-item.is-active),
.admin-menu :deep(.el-menu-item:hover) {
  background: rgba(37, 99, 235, 0.2);
  color: #fff;
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
  overflow-x: hidden;
  padding: 24px 30px 40px;
  background: #f3f6fb;
}

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
  .admin-menu :deep(.el-menu-item span) {
    display: none;
  }

  .admin-menu :deep(.el-menu-item) {
    justify-content: center;
    padding: 0 !important;
  }

  .admin-menu :deep(.el-menu-item .el-icon) {
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
