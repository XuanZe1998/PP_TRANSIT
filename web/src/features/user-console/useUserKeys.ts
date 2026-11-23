import { computed, ref, onBeforeUnmount, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import http, { getHttpErrorMessage } from '@/utils/http';

import { number, keyPreview } from './format';
import { cheapestCallableModels, modelScopeLabel } from '@/utils/modelCatalog';

export function useUserKeys(dashboard: Ref<any>, loadDashboard: () => Promise<void>) {
  const createKeyVisible = ref(false)
  const createKeySubmitting = ref(false)
  const createdKeyVisible = ref(false)
  const createdKeySecret = ref('')
  const createKeyForm = ref({
    name: '',
    totalQuota: 0,
    allowAllModels: true,
    allowedModelIds: [] as string[]
  })
  const modelGrantOptions = computed(() => {
    const categories = new Map<string, { value: string; label: string; children: { value: string; label: string }[] }>()
    for (const model of cheapestCallableModels(dashboard.value.modelCatalog || [])) {
      const capability = model.capability || 'text'
      const group = categories.get(capability) || { value: capability, label: capability, children: [] }
      group.children.push({ value: model.publicName, label: model.displayName || model.publicName })
      categories.set(capability, group)
    }
    return Array.from(categories.values())
  })
  const openCreateKey = () => {
    clearCreatedKey()
    createKeyForm.value = { name: '', totalQuota: 0, allowAllModels: true, allowedModelIds: [] }
    createKeyVisible.value = true
  }

  const createKey = async () => {
    const name = createKeyForm.value.name.trim()
    if (!name) {
      ElMessage.warning('请输入 Key 名称')
      return
    }
    if (!createKeyForm.value.allowAllModels && createKeyForm.value.allowedModelIds.length === 0) {
      ElMessage.warning('请至少选择一个授权模型')
      return
    }
    createKeySubmitting.value = true
    try {
      const response = await http.post('/api/user/tokens', {
        name,
        totalQuota: createKeyForm.value.totalQuota,
        allowAllModels: createKeyForm.value.allowAllModels,
        allowedModelIds: createKeyForm.value.allowedModelIds
      })
      createKeyVisible.value = false
      const secret = typeof response.data?.secret === 'string' ? response.data.secret : ''
      if (secret) {
        createdKeySecret.value = secret
        createdKeyVisible.value = true
        ElMessage.success('API Key 已创建，请立即复制保存')
      } else {
        ElMessage.warning('API Key 已创建，但服务端未返回一次性密钥；请立即轮换该 Key')
      }
      void loadDashboard().catch(error => {
        ElMessage.warning(getHttpErrorMessage(error, 'Key 已创建，但列表刷新失败'))
      })
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorMessage(error, 'API Key 创建失败'))
    } finally {
      createKeySubmitting.value = false
    }
  }

  const clearCreatedKey = () => {
    createdKeySecret.value = ''
  }
  const keys = computed(() => (dashboard.value.tokens || []).map((token: any) => ({
    name: token.name || `Key #${token.id}`,
    key: keyPreview(token),
    quota: `${number(token.usedQuota)} / ${number(token.totalQuota)}`,
    models: token.allowAllModels
      ? '全部可用模型'
      : (Array.isArray(token.allowedModelIds) && token.allowedModelIds.length
        ? token.allowedModelIds.join(', ')
        : modelScopeLabel(token.allowedModels)),
    lastUsed: token.requestCount > 0 ? `${number(token.requestCount)} 次 / ${number(token.totalTokens)} tokens` : '从未调用',
    status: token.enabled ? '启用' : '停用'
  })))

  onBeforeUnmount(clearCreatedKey)

  return {
    createKeyVisible,
    createKeySubmitting,
    createdKeyVisible,
    createdKeySecret,
    createKeyForm,
    modelGrantOptions,
    openCreateKey,
    createKey,
    clearCreatedKey,
    keys,
  }
}
