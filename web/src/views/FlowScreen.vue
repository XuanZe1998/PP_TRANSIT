<template>
  <div class="flow-shell" :class="`role-${screen.role}`">
    <aside v-if="screen.role !== 'public'" class="flow-sidebar">
      <div class="flow-brand" @click="go('/')">
        <div class="brand-mark"></div>
        <div>
          <strong>API Transit</strong>
          <span>{{ screen.role === 'admin' ? 'Admin Console' : 'User Console' }}</span>
        </div>
      </div>

      <nav class="flow-nav">
        <button
          v-for="item in navItems"
          :key="item.path"
          class="nav-item"
          :class="{ active: item.label === screen.activeNav }"
          @click="go(item.path)"
        >
          <span class="nav-dot"></span>
          {{ item.label }}
        </button>
      </nav>

      <div class="health-card">
        <span>集群健康</span>
        <strong>99.92%</strong>
        <small>3 个渠道正在降级保护</small>
      </div>
    </aside>

    <main class="flow-main">
      <header class="flow-topbar">
        <div class="topbar-copy">
          <h1>{{ pageTitle }}</h1>
          <p>{{ pageSubtitle }}</p>
        </div>

        <div v-if="screen.role === 'public'" class="topbar-actions">
          <el-button @click="go('/')">返回首页</el-button>
          <el-button type="primary" @click="screen.key === 'auth' ? submitAuth() : go('/login')">
            {{ screen.key === 'auth' ? primaryAuthAction : screen.primaryAction }}
          </el-button>
        </div>
        <div v-else class="topbar-actions">
          <el-input v-model="query" class="global-search" placeholder="搜索模型、用户、渠道" />
          <el-button>刷新</el-button>
          <el-button type="primary" @click="handlePrimaryAction">{{ screen.primaryAction }}</el-button>
        </div>
      </header>

      <AuthWorkspace v-if="screen.key === 'auth'" />

      <section v-else class="flow-content">
        <div v-if="screen.metrics?.length" class="metric-grid">
          <article v-for="metric in screen.metrics" :key="metric.label" class="metric-card">
            <span>{{ metric.label }}</span>
            <div>
              <strong>{{ metric.value }}</strong>
              <em :class="`tone-${metric.tone}`">{{ metric.badge }}</em>
            </div>
          </article>
        </div>

        <div v-if="screen.form?.length || screen.code" class="split-grid">
          <section v-if="screen.form?.length" class="panel">
            <h2>配置面板</h2>
            <el-form label-position="top">
              <el-form-item v-for="field in screen.form" :key="field.label" :label="field.label">
                <el-input :model-value="field.value" readonly />
              </el-form-item>
            </el-form>
            <el-button type="primary" @click="handlePrimaryAction">{{ screen.primaryAction }}</el-button>
          </section>

          <section v-if="screen.code" class="panel code-panel">
            <h2>调用示例</h2>
            <pre>{{ screen.code }}</pre>
          </section>
        </div>

        <div v-if="screen.cards?.length" class="card-grid">
          <article v-for="card in screen.cards" :key="card.title" class="feature-card">
            <h2>{{ card.title }}</h2>
            <p>{{ card.description }}</p>
            <div class="tag-row">
              <span v-for="tag in card.tags" :key="tag">{{ tag }}</span>
            </div>
          </article>
        </div>

        <section v-if="screen.table" class="panel table-panel">
          <div class="panel-head">
            <h2>{{ screen.table.title }}</h2>
            <el-button size="small">筛选</el-button>
          </div>
          <el-table :data="tableRows" border>
            <el-table-column
              v-for="column in screen.table.columns"
              :key="column"
              :prop="column"
              :label="column"
              min-width="130"
            >
              <template #default="{ row }">
                <el-tag v-if="isStatusColumn(column)" :type="statusType(row[column])">
                  {{ row[column] }}
                </el-tag>
                <span v-else>{{ row[column] }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElButton, ElDivider, ElForm, ElFormItem, ElInput, ElMessage, ElRadioButton, ElRadioGroup, ElTag } from 'element-plus'
import { adminNav, publicNav, screenByKey, userNav } from '@/config/flowScreens'
import { setAuth } from '@/utils/auth'
import http, { getHttpErrorMessage } from '@/utils/http'
import {
  OAuthStateError,
  clearOAuthState,
  consumeOAuthState,
  saveOAuthState,
  type OAuthProvider
} from '@/utils/oauthState'

const props = defineProps<{ screenKey: string }>()
const router = useRouter()
const route = useRoute()
const query = ref('')
const authMode = ref<'login' | 'register'>(route.path === '/register' ? 'register' : 'login')
const identifierStatus = ref<{ valid: boolean; available: boolean; type: string } | null>(null)
const authLoading = ref(false)
const oauthRedirect = ref('')
let identifierTimer: number | undefined
let identifierRequest = 0

const authForm = reactive({
  identifier: '',
  password: '',
  confirmPassword: ''
})

const screen = computed(() => screenByKey[props.screenKey] ?? screenByKey.home)
const isAuthScreen = computed(() => screen.value.key === 'auth')
const pageTitle = computed(() => isAuthScreen.value ? '登录 / 注册 / OAuth' : screen.value.title)
const pageSubtitle = computed(() => isAuthScreen.value
  ? '注册只允许使用邮箱或手机号，后台会校验格式与唯一性；也可以通过 GitHub 或 Google 一键注册并绑定账号。'
  : screen.value.subtitle)
const primaryAuthAction = computed(() => authMode.value === 'register' ? '注册并进入控制台' : '登录控制台')

const navItems = computed(() => {
  if (screen.value.role === 'admin') return adminNav
  if (screen.value.role === 'user') return userNav
  return publicNav
})

const tableRows = computed(() => {
  const table = screen.value.table
  if (!table) return []
  return table.rows.map(row => Object.fromEntries(table.columns.map((column, index) => [column, row[index] ?? ''])))
})

watch(() => route.path, path => {
  if (path === '/register') authMode.value = 'register'
  if (path === '/login') authMode.value = 'login'
})

watch(() => authForm.identifier, value => {
  window.clearTimeout(identifierTimer)
  const requestId = ++identifierRequest
  identifierStatus.value = null
  if (!value || authMode.value !== 'register') return
  if (!isEmail(value) && !isPhone(value)) {
    identifierStatus.value = { valid: false, available: false, type: 'unknown' }
    return
  }
  identifierTimer = window.setTimeout(async () => {
    try {
      const res = await http.get('/api/auth/validate-identifier', { params: { identifier: value.trim() } })
      if (requestId === identifierRequest) identifierStatus.value = res.data
    } catch {
      if (requestId === identifierRequest) identifierStatus.value = null
    }
  }, 300)
})

onMounted(handleOAuthCallback)
onBeforeUnmount(() => window.clearTimeout(identifierTimer))

const go = (path: string) => {
  router.push(path)
}

const safeLocalRedirect = (value: string | undefined) => {
  if (
    value
    && value.startsWith('/')
    && !value.startsWith('//')
    && !value.includes('\\')
    && !/[\u0000-\u001f\u007f]/.test(value)
    && !value.startsWith('/login')
    && !value.startsWith('/register')
    && !value.startsWith('/admin')
    && !value.startsWith('/oauth/callback/')
  ) {
    return value
  }
  return ''
}

const authRedirect = () => {
  const redirect = safeLocalRedirect(oauthRedirect.value || route.query.redirect?.toString())
  if (redirect) return redirect
  return '/console'
}

const handlePrimaryAction = () => {
  ElMessage.success(`${screen.value.primaryAction} 已进入原型流程`)
}

const submitAuth = async () => {
  if (authMode.value === 'register') {
    await register()
  } else {
    await login()
  }
}

const register = async () => {
  if (!isEmail(authForm.identifier) && !isPhone(authForm.identifier)) {
    ElMessage.warning('请输入合法邮箱或手机号')
    return
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d).{8,}$/.test(authForm.password)) {
    ElMessage.warning('密码至少 8 位，且包含字母和数字')
    return
  }
  if (authForm.password !== authForm.confirmPassword) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }
  authLoading.value = true
  try {
    const res = await http.post('/api/auth/register', {
      identifier: authForm.identifier.trim(),
      password: authForm.password
    })
    applyAuthResponse(res.data)
    ElMessage.success('注册成功')
    router.replace(authRedirect())
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '注册失败，请检查填写信息'))
  } finally {
    authLoading.value = false
  }
}

const login = async () => {
  if (!authForm.identifier || !authForm.password) {
    ElMessage.warning('请输入账号和密码')
    return
  }
  authLoading.value = true
  try {
    const res = await http.post('/api/auth/login', {
      username: authForm.identifier.trim(),
      password: authForm.password
    })
    applyAuthResponse(res.data)
    ElMessage.success('登录成功')
    router.replace(authRedirect())
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '登录失败，请检查账号和密码'))
  } finally {
    authLoading.value = false
  }
}

const startOAuth = async (provider: 'github' | 'google') => {
  try {
    const res = await http.get('/api/oauth/authorize', { params: { provider } })
    const authorizeUrl = String(res.data?.url || '')
    const state = String(res.data?.state || '')
    if (!authorizeUrl || !state) throw new Error('OAuth 服务未返回完整的授权信息')

    const parsedUrl = new URL(authorizeUrl)
    if (parsedUrl.protocol !== 'https:' || parsedUrl.searchParams.get('state') !== state) {
      throw new Error('OAuth 授权地址校验失败')
    }

    const redirect = safeLocalRedirect(route.query.redirect?.toString()) || undefined
    saveOAuthState(provider, state, redirect)
    window.location.assign(parsedUrl.toString())
  } catch (error: unknown) {
    try {
      clearOAuthState()
    } catch {
      // The original error is more useful when sessionStorage is unavailable.
    }
    ElMessage.error(error instanceof OAuthStateError
      ? oauthStateErrorMessage(error)
      : getHttpErrorMessage(error, `${provider} OAuth 尚未配置或暂不可用`))
  }
}

async function handleOAuthCallback() {
  if (screen.value.key !== 'auth' || !route.path.startsWith('/oauth/callback/')) return

  const rawProvider = route.params.provider?.toString() || ''
  if (!isOAuthProvider(rawProvider)) {
    ElMessage.error('不支持的 OAuth 登录方式')
    await router.replace('/login')
    return
  }

  const returnedState = route.query.state?.toString() || ''
  if (!returnedState) {
    ElMessage.error('OAuth 回调缺少 state，已拒绝本次登录')
    await router.replace('/login')
    return
  }

  try {
    const pending = consumeOAuthState(rawProvider, returnedState)
    oauthRedirect.value = safeLocalRedirect(pending.redirect) || ''
  } catch (error: unknown) {
    ElMessage.error(oauthStateErrorMessage(error))
    await router.replace('/login')
    return
  }

  const providerError = route.query.error?.toString()
  if (providerError) {
    const description = route.query.error_description?.toString()
    ElMessage.warning(description || (providerError === 'access_denied' ? '你已取消第三方授权' : '第三方授权未完成'))
    await router.replace({
      path: '/login',
      query: oauthRedirect.value ? { redirect: oauthRedirect.value } : {}
    })
    return
  }

  const code = route.query.code?.toString() || ''
  if (!code) {
    ElMessage.error('OAuth 回调缺少授权码，请重新登录')
    await router.replace({
      path: '/login',
      query: oauthRedirect.value ? { redirect: oauthRedirect.value } : {}
    })
    return
  }

  await finishOAuth(rawProvider, code, returnedState)
}

const finishOAuth = async (provider: OAuthProvider, code: string, state: string) => {
  authLoading.value = true
  try {
    const res = await http.get(`/api/oauth/callback/${provider}`, { params: { code, state } })
    applyAuthResponse(res.data)
    ElMessage.success(`${provider} 注册/登录成功`)
    await router.replace(authRedirect())
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, `${provider} 第三方登录失败`))
    await router.replace({
      path: '/login',
      query: oauthRedirect.value ? { redirect: oauthRedirect.value } : {}
    })
  } finally {
    authLoading.value = false
  }
}

const isOAuthProvider = (value: string): value is OAuthProvider => value === 'github' || value === 'google'

const oauthStateErrorMessage = (error: unknown) => {
  if (!(error instanceof OAuthStateError)) return 'OAuth 会话校验失败，请重新登录'
  if (error.code === 'EXPIRED_STATE') return 'OAuth 授权已超时，请重新登录'
  if (error.code === 'STORAGE_UNAVAILABLE') return '浏览器禁用了会话存储，无法安全完成 OAuth 登录'
  if (error.code === 'MISSING_STATE') return '未找到待完成的 OAuth 会话，请从登录页重新发起'
  return 'OAuth state 或登录方式不匹配，已拒绝本次登录'
}

const changeAuthMode = (value: string | number | boolean | undefined) => {
  const mode = value === 'register' ? 'register' : 'login'
  authMode.value = mode
  identifierStatus.value = null
  authForm.confirmPassword = ''
  const redirect = safeLocalRedirect(route.query.redirect?.toString())
  router.replace({
    path: mode === 'register' ? '/register' : '/login',
    query: redirect ? { redirect } : {}
  })
}

const applyAuthResponse = (payload: Record<string, any>) => {
  const token = payload.access_token || payload.token
  if (!token) throw new Error('Missing access token')
  setAuth(token, {
    username: payload.username || authForm.identifier,
    role: payload.role || 'USER'
  })
}

const isEmail = (value: string) => /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(value.trim())
const isPhone = (value: string) => /^(1[3-9]\d{9}|\+[1-9]\d{7,14})$/.test(value.trim())
const isStatusColumn = (column: string) => ['状态', '结果', '健康'].includes(column)
const statusType = (value: string) => {
  if (['启用', '成功', '健康', '已履约'].includes(value)) return 'success'
  if (['降级', '待确认'].includes(value)) return 'warning'
  if (['异常', '停用'].includes(value)) return 'danger'
  return 'info'
}

const AuthWorkspace = defineComponent({
  name: 'AuthWorkspace',
  setup() {
    return () => h('section', { class: 'flow-content auth-content' }, [
      h('section', { class: 'auth-card panel' }, [
        h(ElRadioGroup, {
          modelValue: authMode.value,
          'onUpdate:modelValue': changeAuthMode
        }, () => [
          h(ElRadioButton, { label: 'register' }, () => '注册'),
          h(ElRadioButton, { label: 'login' }, () => '登录')
        ]),
        h('h2', authMode.value === 'register' ? '创建 API Transit 账号' : '登录 API Transit'),
        h('p', { class: 'auth-hint' }, authMode.value === 'register'
          ? '只能使用邮箱或手机号注册。手机号支持中国大陆 11 位号码和 E.164 国际格式。'
          : '可使用邮箱、手机号或用户名登录。'),
        h(ElForm, { labelPosition: 'top' }, () => [
          h(ElFormItem, { label: authMode.value === 'register' ? '邮箱或手机号' : '账号' }, () => [
            h(ElInput, {
              modelValue: authForm.identifier,
              'onUpdate:modelValue': (value: string) => { authForm.identifier = value },
              placeholder: 'team@example.com / 13800138000'
            }),
            authMode.value === 'register' && identifierStatus.value
              ? h('div', { class: ['identifier-status', identifierStatus.value.valid && identifierStatus.value.available ? 'ok' : 'bad'] },
                identifierStatus.value.valid
                  ? identifierStatus.value.available ? `可使用的${identifierStatus.value.type === 'email' ? '邮箱' : '手机号'}` : '该邮箱或手机号已注册'
                  : '请输入合法邮箱或手机号')
              : null
          ]),
          h(ElFormItem, { label: '密码' }, () => h(ElInput, {
            modelValue: authForm.password,
            'onUpdate:modelValue': (value: string) => { authForm.password = value },
            type: 'password',
            showPassword: true,
            placeholder: '至少 8 位，包含字母和数字'
          })),
          authMode.value === 'register'
            ? h(ElFormItem, { label: '确认密码' }, () => h(ElInput, {
              modelValue: authForm.confirmPassword,
              'onUpdate:modelValue': (value: string) => { authForm.confirmPassword = value },
              type: 'password',
              showPassword: true,
              placeholder: '再次输入密码'
            }))
            : null
        ]),
        h(ElButton, { type: 'primary', loading: authLoading.value, class: 'full-button', onClick: submitAuth }, () => primaryAuthAction.value),
        h(ElDivider, () => '或使用第三方账号'),
        h('div', { class: 'oauth-row' }, [
          h(ElButton, { onClick: () => startOAuth('github') }, () => 'GitHub 注册/登录'),
          h(ElButton, { onClick: () => startOAuth('google') }, () => 'Google 注册/登录')
        ])
      ]),
      h('section', { class: 'auth-side panel' }, [
        h('h2', '后台校验与绑定规则'),
        h('p', '注册提交后，后端会进行格式校验、唯一性校验和密码强度校验。OAuth 登录会优先按邮箱复用账号，否则创建新用户并保存绑定关系。'),
        h('div', { class: 'tag-row' }, [
          h('span', '邮箱格式校验'),
          h('span', '手机号格式校验'),
          h('span', 'GitHub user:email'),
          h('span', 'Google openid email profile')
        ]),
        h('pre', 'POST /auth/register\n{ identifier, password }\n\nGET /oauth/authorize?provider=github|google\nGET /oauth/callback/{provider}?code=...&state=...')
      ])
    ])
  }
})
</script>
