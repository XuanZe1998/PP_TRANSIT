<template>
  <main class="admin-login">
    <section class="login-panel">
      <div class="brand-row">
        <div class="brand-mark">A</div>
        <div>
          <h1>管理员登录</h1>
          <p>登录后才能访问后台渠道、模型映射和令牌管理。</p>
        </div>
      </div>

      <el-form class="login-form" :model="form" label-position="top" @submit.prevent="submit">
        <el-form-item label="管理员账号">
          <el-input v-model="form.username" size="large" placeholder="admin" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            show-password
            placeholder="请输入管理员密码"
            autocomplete="current-password"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button class="submit-btn" type="primary" size="large" :loading="loading" @click="submit">
          登录后台
        </el-button>
      </el-form>
    </section>
  </main>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { setAuth } from '@/utils/auth'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const form = reactive({
  username: 'admin',
  password: ''
})

const submit = async () => {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入管理员账号和密码')
    return
  }

  loading.value = true
  try {
    const res = await http.post('/api/admin/auth/login', form)
    if (!res.data?.access_token || res.data?.role !== 'ADMIN') {
      throw new Error('服务端未返回有效的管理员会话')
    }
    setAuth(res.data.access_token, {
      username: res.data.username,
      role: res.data.role
    })
    ElMessage.success('登录成功')
    const redirect = route.query.redirect?.toString() || ''
    router.replace(redirect.startsWith('/admin') && !redirect.startsWith('//') && !redirect.includes('\\')
      ? redirect
      : '/admin')
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '管理员账号或密码错误'))
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.admin-login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    linear-gradient(180deg, rgba(246, 248, 251, 0.94), rgba(238, 242, 247, 0.96)),
    radial-gradient(circle at 20% 10%, rgba(47, 138, 245, 0.16), transparent 32%);
}

.login-panel {
  width: min(460px, 100%);
  padding: 34px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 24px 70px rgba(15, 23, 42, 0.12);
}

.brand-row {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  margin-bottom: 28px;
}

.brand-mark {
  width: 42px;
  height: 42px;
  flex: 0 0 auto;
  border-radius: 8px;
  display: grid;
  place-items: center;
  background: #2f8af5;
  color: #fff;
  font-weight: 700;
}

h1 {
  margin: 0;
  font-size: 28px;
  color: #0f172a;
}

p {
  margin: 8px 0 0;
  color: #64748b;
  line-height: 1.7;
}

.login-form {
  display: grid;
  gap: 4px;
}

.submit-btn {
  width: 100%;
  margin-top: 8px;
}
</style>
