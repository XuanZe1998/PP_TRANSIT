<template>
  <el-container class="admin-shell">
    <el-aside width="248px" class="admin-aside">
      <div class="brand" @click="$router.push('/')">
        <div class="brand-mark">A</div>
        <div>
          <div class="brand-title">API Transit</div>
          <div class="brand-subtitle">Enterprise Console</div>
        </div>
      </div>
      <el-menu :default-active="activeRoute" class="admin-menu" router>
        <el-menu-item index="/admin/channels">
          <el-icon><Connection /></el-icon>
          <span>渠道管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/mappings">
          <el-icon><Switch /></el-icon>
          <span>模型映射</span>
        </el-menu-item>
        <el-menu-item index="/admin/tokens">
          <el-icon><Key /></el-icon>
          <span>令牌治理</span>
        </el-menu-item>
        <el-menu-item index="/admin/finished-products">
          <el-icon><Goods /></el-icon>
          <span>成品管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/plus-orders">
          <el-icon><Tickets /></el-icon>
          <span>成品订单</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="admin-header">
        <div>
          <h1>运营控制台</h1>
          <p>统一管理供应商渠道、模型路由与访问令牌。</p>
        </div>
        <el-button plain @click="$router.push('/console')">返回控制台</el-button>
      </el-header>
      <el-main class="admin-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeRoute = computed(() => route.path)
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
}

.admin-aside {
  padding: 24px 18px;
  background: linear-gradient(180deg, #0f172a 0%, #111827 100%);
  color: #e2e8f0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 28px;
  cursor: pointer;
}

.brand-mark {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: linear-gradient(135deg, #0ea5e9, #22c55e);
  color: #fff;
  font-weight: 700;
}

.brand-title {
  font-weight: 700;
  color: #f8fafc;
}

.brand-subtitle {
  margin-top: 2px;
  font-size: 12px;
  color: #94a3b8;
}

.admin-menu {
  border-right: none;
  background: transparent;
}

.admin-menu :deep(.el-menu-item) {
  margin-bottom: 8px;
  border-radius: 8px;
  color: #cbd5e1;
}

.admin-menu :deep(.el-menu-item.is-active) {
  background: rgba(14, 165, 233, 0.12);
  color: #f8fafc;
}

.admin-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  min-height: 80px;
  padding: 20px 32px;
  background: rgba(255, 255, 255, 0.82);
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
}

.admin-header h1 {
  margin: 0;
  font-size: 24px;
  color: #0f172a;
}

.admin-header p {
  margin: 6px 0 0;
  color: #64748b;
}

.admin-main {
  padding: 24px 32px 40px;
  background: transparent;
}

@media (max-width: 900px) {
  .admin-shell {
    display: block;
  }

  .admin-aside {
    width: auto !important;
  }
}
</style>
