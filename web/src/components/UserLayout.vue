<template>
  <el-container class="user-layout">
    <el-header class="user-header">
      <div class="header-left">
        <div class="logo" @click="$router.push('/')">API Transit</div>
        <el-menu mode="horizontal" :default-active="activeMenu" router class="header-menu" :ellipsis="false">
          <el-menu-item index="/">总览</el-menu-item>
          <el-menu-item index="/market">模型目录</el-menu-item>
          <el-menu-item v-if="isLoggedIn" index="/plus">成品服务</el-menu-item>
          <el-menu-item v-if="isLoggedIn" index="/console">控制台</el-menu-item>
        </el-menu>
      </div>
      <div class="header-right">
        <template v-if="!isLoggedIn">
          <el-button @click="$router.push('/login')">登录</el-button>
          <el-button type="primary" @click="$router.push('/register')">注册</el-button>
        </template>
        <template v-else>
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              {{ username }} <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="console">控制台</el-dropdown-item>
                <el-dropdown-item command="plus">成品服务</el-dropdown-item>
                <el-dropdown-item v-if="isAdmin" command="admin">管理后台</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </template>
      </div>
    </el-header>
    <el-main class="user-main">
      <router-view />
    </el-main>
    <el-footer class="user-footer">
      <p>API Transit Station · Multi-Provider AI Gateway</p>
    </el-footer>
  </el-container>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearAuth } from '@/utils/auth'

const route = useRoute()
const router = useRouter()

const activeMenu = computed(() => route.path)
const isLoggedIn = ref(false)
const username = ref('')
const isAdmin = ref(false)

const checkLogin = () => {
  const userStr = localStorage.getItem('user')
  const token = localStorage.getItem('token')
  if (!token || !userStr) {
    isLoggedIn.value = false
    username.value = ''
    isAdmin.value = false
    return
  }
  const user = JSON.parse(userStr)
  isLoggedIn.value = true
  username.value = user.username
  isAdmin.value = user.role === 'ADMIN'
}

const handleCommand = (command: string) => {
  if (command === 'logout') {
    clearAuth()
    isLoggedIn.value = false
    router.push('/')
  } else if (command === 'console') {
    router.push('/console')
  } else if (command === 'plus') {
    router.push('/plus')
  } else if (command === 'admin') {
    router.push('/admin')
  }
}

onMounted(() => {
  checkLogin()
  window.addEventListener('auth-changed', checkLogin)
})

onBeforeUnmount(() => {
  window.removeEventListener('auth-changed', checkLogin)
})
</script>

<style scoped>
.user-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.user-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 28px;
  background: rgba(255, 255, 255, 0.85);
  border-bottom: 1px solid rgba(148, 163, 184, 0.16);
  backdrop-filter: blur(16px);
  z-index: 100;
}

.header-left {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.logo {
  font-size: 20px;
  font-weight: 700;
  color: #0f172a;
  margin-right: 28px;
  cursor: pointer;
}

.header-menu {
  border-bottom: none;
  flex-grow: 1;
}

:deep(.el-menu--horizontal) {
  border-bottom: none;
  background: transparent;
}

:deep(.el-menu-item) {
  padding: 0 18px;
}

.user-info {
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 5px;
}

.user-main {
  flex: 1;
  padding: 0;
}

.user-footer {
  text-align: center;
  color: #64748b;
  padding: 18px 0 24px;
}

@media (max-width: 860px) {
  .user-header {
    padding: 0 12px;
  }

  .logo {
    margin-right: 12px;
  }
}
</style>
