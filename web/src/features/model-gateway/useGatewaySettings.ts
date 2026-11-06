import { ref, reactive, onBeforeUnmount, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'

export function useGatewaySettings(
  siteId: Ref<number | undefined>, siteOptions: Ref<any[]>, working: Ref<boolean>,
  reloadAll: () => Promise<void>, trackJob: (id: string) => void
) {
  const settingsOpen = ref(false), settingsKind = ref('site'), settingsId = ref(0), settingsError = ref('')
  const settings = reactive({ name: '', enabled: false, syncEnabled: true, apiKey: '',
    publicName: '', publicCode: '', badgeText: '', badgeColor: '#2563eb', inheritDisplay: true })
  const accountAuth = reactive({ email: '', password: '', totpCode: '', emailPreview: '',
    authenticated: false, status: 'ANONYMOUS', lastError: '' })

  function editSite(row?: any) {
    const site = row?.site_id != null ? {
      id: row.site_id, name: row.site_name, public_name: row.site_public_name || row.public_name,
      public_code: row.site_public_code || row.public_code, badge_text: row.site_badge_text || row.badge_text,
      badge_color: row.site_badge_color || row.badge_color
    } : siteOptions.value.find(item => item.id === siteId.value)
    if (!site) return
    settingsId.value = site.id
    settingsKind.value = 'site'
    Object.assign(settings, { name: site.name, publicName: site.public_name || '',
      publicCode: site.public_code || '', badgeText: site.badge_text || '',
      badgeColor: site.badge_color || '#2563eb', apiKey: '' })
    const auth = siteOptions.value.find(item => item.id === site.id) || {}
    Object.assign(accountAuth, { email: '', password: '', totpCode: '',
      emailPreview: auth.account_email_preview || '', authenticated: !!auth.account_authenticated,
      status: auth.account_auth_status || 'ANONYMOUS', lastError: auth.account_auth_error || '' })
    settingsError.value = ''
    settingsOpen.value = true
  }

  function editGroup(row: any) {
    settingsId.value = row.id
    settingsKind.value = 'group'
    Object.assign(settings, { name: row.name, enabled: !!row.enabled, syncEnabled: !!row.sync_enabled,
      apiKey: '', publicName: '', publicCode: '', badgeText: '', badgeColor: '#2563eb', inheritDisplay: true })
    settingsError.value = ''
    settingsOpen.value = true
  }

  // Settings mutations share busy/error handling while retaining domain-specific success behavior.
  async function mutate(action: () => Promise<void>, failure: string) {
    if (working.value) return
    working.value = true
    settingsError.value = ''
    try { await action() }
    catch (cause) { settingsError.value = getHttpErrorMessage(cause, failure) }
    finally { working.value = false }
  }

  function saveSettings() {
    return mutate(async () => {
      await http.put(`/api/admin/api/gateway/${settingsKind.value === 'site' ? 'sites' : 'groups'}/${settingsId.value}`, settings)
      settings.apiKey = ''
      settingsOpen.value = false
      await reloadAll()
    }, '保存失败')
  }

  function accountStatusMessage() {
    if (accountAuth.status === 'READY') return `账号目录已授权${accountAuth.emailPreview?'：'+accountAuth.emailPreview:''}`
    if (accountAuth.status === 'TWO_FACTOR_REQUIRED') return '请输入 AiAPIBank 两步验证码完成授权'
    if (accountAuth.status === 'ERROR') return accountAuth.lastError || '账号授权已失效，请重新登录'
    return '当前使用匿名模型广场目录'
  }

  function clearSettingsSecrets() {
    settings.apiKey = ''
    accountAuth.password = ''
    accountAuth.totpCode = ''
  }

  function authorizeAiApiBank() {
    return mutate(async () => {
      try {
        const payload = accountAuth.status === 'TWO_FACTOR_REQUIRED'
          ? { totpCode: accountAuth.totpCode } : { email: accountAuth.email, password: accountAuth.password }
        const { data } = await http.post(`/api/admin/api/gateway/sites/${settingsId.value}/aiapibank/authorize`, payload, { timeout: 60000 })
        accountAuth.emailPreview = data.accountEmailPreview || accountAuth.emailPreview
        accountAuth.authenticated = !!data.authenticated
        accountAuth.status = data.status
        if (data.requiresTwoFactor) ElMessage.warning('请输入两步验证码完成授权')
        else {
          if (data.jobId) trackJob(data.jobId)
          ElMessage.success('账号授权成功，专用分组同步已提交')
          await reloadAll()
        }
      } finally {
        accountAuth.password = ''
        accountAuth.totpCode = ''
      }
    }, 'AiAPIBank 登录授权失败')
  }

  function clearAiApiBankAuthorization() {
    return mutate(async () => {
      await http.delete(`/api/admin/api/gateway/sites/${settingsId.value}/aiapibank/authorization`)
      Object.assign(accountAuth, { email: '', password: '', totpCode: '', emailPreview: '',
        authenticated: false, status: 'ANONYMOUS', lastError: '' })
      ElMessage.success('账号授权已移除，后续使用匿名目录')
      await reloadAll()
    }, '移除账号授权失败')
  }

  onBeforeUnmount(clearSettingsSecrets)
  return { settingsOpen, settingsKind, settingsId, settingsError, settings, accountAuth,
    editSite, editGroup, saveSettings, accountStatusMessage, clearSettingsSecrets,
    authorizeAiApiBank, clearAiApiBankAuthorization }
}
