<template>
  <div class="auth-container">
    <el-card class="auth-card">
      <h2>{{ isLogin ? '用户登录' : '用户注册' }}</h2>
      <el-form :model="form" label-position="top" size="large">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
        </el-form-item>
        <el-form-item v-if="!isLogin" label="电子邮箱">
          <el-input v-model="form.email" placeholder="请输入电子邮箱" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="handleAuth" block>{{ isLogin ? '登录' : '注册' }}</el-button>
        <div class="auth-toggle">
          {{ isLogin ? '还没有账号？' : '已有账号？' }}
          <el-link type="primary" @click="isLogin = !isLogin">{{ isLogin ? '立即注册' : '返回登录' }}</el-link>
        </div>
      </el-form>
      
      <el-divider>或使用以下方式登录</el-divider>
      
      <div class="social-login">
        <el-button class="github-btn" @click="handleGithubLogin">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z"/>
          </svg>
          GitHub 登录
        </el-button>
        <el-button class="google-btn" @click="handleGoogleLogin">
          <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24">
            <path fill="#4285F4" d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92c-.26 1.37-1.04 2.53-2.21 3.31v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.09z"/>
            <path fill="#34A853" d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"/>
            <path fill="#FBBC05" d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"/>
            <path fill="#EA4335" d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"/>
          </svg>
          Google 登录
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()

const isLogin = ref(true)
const loading = ref(false)
const form = ref({
  username: '',
  password: '',
  email: ''
})

onMounted(() => {
  if (route.path === '/register') {
    isLogin.value = false
  }
  
  const code = route.query.code as string
  const provider = route.query.provider as string
  if (code && provider) {
    handleOAuthCallback(provider, code)
  }
})

const handleAuth = async () => {
  if (!form.value.username || !form.value.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  
  loading.value = true
  try {
    const endpoint = isLogin.value ? '/api/auth/login' : '/api/auth/register'
    const res = await axios.post(endpoint, form.value)
    const { setAuth } = await import('@/utils/auth')
    setAuth(res.data.access_token, { username: res.data.username, role: res.data.role })
    
    ElMessage.success(isLogin.value ? '登录成功' : '注册成功')
    router.push('/console')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || (isLogin.value ? '登录失败' : '注册失败'))
  } finally {
    loading.value = false
  }
}

const handleGithubLogin = async () => {
  try {
    const res = await axios.get('/api/oauth/authorize?provider=github')
    window.location.href = res.data.url
  } catch (error) {
    ElMessage.error('获取授权链接失败')
  }
}

const handleGoogleLogin = async () => {
  try {
    const res = await axios.get('/api/oauth/authorize?provider=google')
    window.location.href = res.data.url
  } catch (error) {
    ElMessage.error('获取授权链接失败')
  }
}

const handleOAuthCallback = async (provider: string, code: string) => {
  loading.value = true
  try {
    const res = await axios.get(`/api/oauth/callback/${provider}?code=${code}`)
    const { setAuth } = await import('@/utils/auth')
    setAuth(res.data.access_token, { username: res.data.username, role: res.data.role })
    ElMessage.success('登录成功')
    router.push('/console')
  } catch (error: any) {
    ElMessage.error(error.response?.data?.message || '第三方登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.auth-container {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 80vh;
}

.auth-card {
  width: 400px;
  padding: 20px;
  border-radius: 12px;
}

.auth-card h2 {
  text-align: center;
  margin-bottom: 30px;
  color: #303133;
}

.auth-toggle {
  text-align: center;
  margin-top: 20px;
  font-size: 0.9rem;
  color: #606266;
}

.block {
  width: 100%;
}

.social-login {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.github-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background-color: #24292e;
  color: white;
  border: none;
}

.github-btn:hover {
  background-color: #1b1f23;
}

.google-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  background-color: white;
  color: #333;
  border: 1px solid #dadce0;
}

.google-btn:hover {
  background-color: #f1f3f4;
}
</style>
