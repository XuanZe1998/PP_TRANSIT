<template>
  <div class="auth-shell">
    <div class="auth-panel shell-section">
      <div class="auth-copy">
        <el-tag effect="plain">Access Control</el-tag>
        <h1>{{ isLogin ? '登录控制台' : '注册平台账号' }}</h1>
        <p>登录后可进入统一控制台，管理渠道、模型映射、访问令牌与调试请求。</p>
      </div>

      <el-form :model="form" label-position="top" size="large">
        <el-form-item label="用户名">
          <el-input v-model="form.username" placeholder="请输入用户名" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password placeholder="请输入密码" />
        </el-form-item>
        <el-form-item v-if="!isLogin" label="邮箱">
          <el-input v-model="form.email" placeholder="请输入邮箱" />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="handleAuth" class="auth-submit">
          {{ isLogin ? '登录' : '注册' }}
        </el-button>
      </el-form>

      <div class="auth-toggle">
        <span>{{ isLogin ? '还没有账号？' : '已有账号？' }}</span>
        <el-link type="primary" @click="isLogin = !isLogin">
          {{ isLogin ? '立即注册' : '返回登录' }}
        </el-link>
      </div>

      <el-divider>第三方登录</el-divider>

      <div class="social-login">
        <el-button @click="handleGithubLogin">GitHub</el-button>
        <el-button @click="handleGoogleLogin">Google</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { setAuth } from '@/utils/auth'

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
  const provider = (route.params.provider || route.query.provider) as string
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
  } catch {
    ElMessage.error('获取授权链接失败')
  }
}

const handleGoogleLogin = async () => {
  try {
    const res = await axios.get('/api/oauth/authorize?provider=google')
    window.location.href = res.data.url
  } catch {
    ElMessage.error('获取授权链接失败')
  }
}

const handleOAuthCallback = async (provider: string, code: string) => {
  loading.value = true
  try {
    const res = await axios.get(`/api/oauth/callback/${provider}?code=${code}`)
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
.auth-shell {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: calc(100vh - 180px);
  padding: 32px 16px;
}

.auth-panel {
  width: min(460px, 100%);
  padding: 32px;
  border-radius: 8px;
}

.auth-copy h1 {
  margin: 18px 0 10px;
  font-size: 32px;
  color: #0f172a;
}

.auth-copy p {
  margin: 0 0 24px;
  color: #64748b;
}

.auth-submit {
  width: 100%;
}

.auth-toggle {
  display: flex;
  justify-content: center;
  gap: 8px;
  margin-top: 18px;
  color: #64748b;
}

.social-login {
  gap: 8px;
  display: flex;
}
</style>
