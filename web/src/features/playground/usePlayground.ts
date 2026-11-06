import { ref, computed, watch, onBeforeUnmount, type Ref } from 'vue'
import { ElMessage } from 'element-plus'
import http, { getHttpErrorMessage } from '@/utils/http'
import { modelsAllowedForToken, type CallableModel } from '@/utils/modelCatalog'
import { extractPlaygroundResult, extractPlaygroundUsage, formatPlaygroundRawResponse, type PlaygroundUsage } from './response'

export function usePlayground(dashboard: Ref<any>, current: Ref<{ key: string }>, refreshDashboard: () => Promise<void>) {
  const playgroundLoading = ref(false)
  const playgroundResponse = ref('')
  const playgroundRawResponse = ref('')
  const playgroundError = ref('')
  const playgroundRawSections = ref<string[]>([])
  const playgroundUsage = ref<PlaygroundUsage | null>(null)
  const playground = ref({
    tokenId: undefined as number | undefined,
    model: '',
    prompt: ''
  })
  const selectedPlaygroundToken = computed(() => (dashboard.value.tokens || [])
    .find((token: any) => Number(token.id) === Number(playground.value.tokenId)) || null)
  const playgroundModels = computed<CallableModel[]>(() => modelsAllowedForToken(
    dashboard.value.modelCatalog || [],
    selectedPlaygroundToken.value,
  ))
  const selectedModelDetail = computed<CallableModel | null>(() => playgroundModels.value
    .find(model => model.publicName === playground.value.model) || null)
  function syncSelection(requested?: string) {
    const selectedTokenStillEnabled = dashboard.value.tokens.some((token: any) =>
      Number(token.id) === Number(playground.value.tokenId) && token.enabled)
    if (!selectedTokenStillEnabled) {
      playground.value.tokenId = dashboard.value.tokens.find((token: any) => token.enabled)?.id
    }
    const requestedModel = requested?.trim()
    if (requestedModel && dashboard.value.models.includes(requestedModel)) {
      const matchingToken = dashboard.value.tokens.find((token: any) =>
        token.enabled && modelsAllowedForToken(dashboard.value.modelCatalog, token)
          .some(model => model.publicName === requestedModel))
      if (matchingToken) playground.value.tokenId = matchingToken.id
    }
    syncPlaygroundModel(requestedModel || playground.value.model)
  }
  function syncPlaygroundModel(preferredModel = playground.value.model) {
    const available = playgroundModels.value
    if (preferredModel && available.some(model => model.publicName === preferredModel)) {
      playground.value.model = preferredModel
      return
    }
    playground.value.model = available[0]?.publicName || ''
  }


  let generation = 0
  let disposed = false
  let controller: AbortController | undefined
  function cancelRequest() {
    generation++
    controller?.abort()
    controller = undefined
    playgroundLoading.value = false
  }
  watch(() => current.value.key, key => { if (key !== 'playground') cancelRequest() }, { flush: 'sync' })
  onBeforeUnmount(() => { disposed = true; cancelRequest() })

  async function sendPlaygroundRequest() {
    if (disposed || playgroundLoading.value || current.value.key !== 'playground') return
    if (!selectedPlaygroundToken.value?.enabled) { ElMessage.warning('请先选择当前账户的 API Key'); return }
    if (!selectedModelDetail.value) { ElMessage.warning('请先选择模型'); return }
    if (!playground.value.prompt.trim()) { ElMessage.warning('请输入 Prompt'); return }
    const version = ++generation
    const isCurrent = () => !disposed && version === generation
    controller = new AbortController()
    playgroundLoading.value = true
    playgroundResponse.value = ''
    playgroundRawResponse.value = ''
    playgroundError.value = ''
    playgroundRawSections.value = []
    playgroundUsage.value = null
    try {
      const response = await http.post('/api/user/playground', { ...playground.value }, {
        timeout: 120_000, signal: controller.signal
      })
      if (!isCurrent()) return
      playgroundResponse.value = extractPlaygroundResult(response.data)
      playgroundRawResponse.value = formatPlaygroundRawResponse(response.data)
      playgroundUsage.value = extractPlaygroundUsage(response.data)
      if (!playgroundResponse.value) playgroundError.value = '服务端没有返回可展示的模型回答'
      // Refresh failure must not erase a successfully settled model response.
      try { await refreshDashboard() }
      catch (error) { if (isCurrent()) ElMessage.warning(getHttpErrorMessage(error, '控制台数据加载失败')) }
    } catch (error: any) {
      if (!isCurrent()) return
      playgroundError.value = getHttpErrorMessage(error, '模型调用失败')
      playgroundRawResponse.value = JSON.stringify(error?.response?.data || { error: '请求失败' }, null, 2)
    } finally {
      if (isCurrent()) { playgroundLoading.value = false; controller = undefined }
    }
  }
  return { playground, playgroundLoading, playgroundResponse, playgroundRawResponse, playgroundError,
    playgroundRawSections, playgroundUsage, playgroundModels, selectedModelDetail,
    syncPlaygroundModel, syncSelection, sendPlaygroundRequest }
}
