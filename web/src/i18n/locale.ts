import { computed, readonly, ref } from 'vue'
import { generatedEnglishCopy } from './englishCopy.generated'

export type AppLocale = 'zh-CN' | 'en-US'
export type DisplayCurrency = 'CNY' | 'USD'

const STORAGE_KEY = 'linknux.locale'
const DEFAULT_USD_CNY_RATE = 6.76693506

function initialLocale(): AppLocale {
  if (typeof window === 'undefined') return 'zh-CN'
  const saved = window.localStorage.getItem(STORAGE_KEY)
  if (saved === 'zh-CN' || saved === 'en-US') return saved
  return 'zh-CN'
}

const localeState = ref<AppLocale>(initialLocale())
const usdCnyRateState = ref(DEFAULT_USD_CNY_RATE)

export const locale = readonly(localeState)
export const isEnglish = computed(() => localeState.value === 'en-US')
export const displayCurrency = computed<DisplayCurrency>(() => isEnglish.value ? 'USD' : 'CNY')

export function getLocale() { return localeState.value }
export function getDisplayCurrency() { return displayCurrency.value }
export function getUsdCnyRate() { return usdCnyRateState.value }

export function setUsdCnyRate(value: unknown) {
  const rate = Number(value)
  if (Number.isFinite(rate) && rate >= 0.01 && rate <= 100) usdCnyRateState.value = rate
}

export function setLocale(next: AppLocale) {
  localeState.value = next
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(STORAGE_KEY, next)
    document.documentElement.lang = next
    document.documentElement.dataset.locale = next
    window.dispatchEvent(new CustomEvent('locale-changed', {
      detail: { locale: next, currency: getDisplayCurrency() },
    }))
  }
}

export function toggleLocale() {
  setLocale(isEnglish.value ? 'zh-CN' : 'en-US')
}

export function initLocale() { setLocale(localeState.value) }

const messages = {
  'zh-CN': {
    languageName: '中文', switchLanguage: 'Switch to English', home: '首页', market: '模型广场',
    studio: 'AI 创作', services: '其他服务', subscriptions: '订阅服务', docs: '开发文档', login: '登录',
    register: '免费接入', console: '进入用户工作台', openMenu: '打开导航菜单', closeMenu: '关闭导航菜单',
  },
  'en-US': {
    languageName: 'EN', switchLanguage: '切换到中文', home: 'Home', market: 'Models', studio: 'AI Studio',
    services: 'Services', subscriptions: 'Subscriptions', docs: 'Docs', login: 'Log in', register: 'Get started',
    console: 'Open console', openMenu: 'Open navigation', closeMenu: 'Close navigation',
  },
} as const

export type MessageKey = keyof typeof messages['zh-CN']
export function t(key: MessageKey) { return messages[localeState.value][key] }

// Exact UI copy used outside the key-based public shell. Keeping this mapping in
// one place lets legacy screens participate in locale switching while they are
// incrementally migrated to keyed copy.
const curatedEnglishCopy: Record<string, string> = {
  '首页': 'Home', '模型市场': 'Model market', '模型广场': 'Models', 'AI 创作': 'AI Studio',
  '其他服务': 'Services', '成品服务': 'Services', '订阅服务': 'Subscription services', '开发文档': 'Documentation',
  '登录': 'Log in', '退出登录': 'Log out', '注册': 'Sign up', '免费接入': 'Get started', '立即创建账号': 'Create an account',
  '进入用户工作台': 'Open console', '查看模型广场': 'Explore models', '查看详情': 'View details',
  '用户总览': 'Overview', 'API Key 管理': 'API keys', '在线调试': 'Playground', '用量日志': 'Usage',
  '账户余额': 'Balance', '个人中心': 'Profile', '企业账户': 'Organization', '代理中心': 'Partner center',
  '管理后台': 'Admin', '运营总览': 'Operations', '用户与分组': 'Users & groups', '模型网关': 'Model gateway',
  '安全策略': 'Security', '系统配置与报表': 'Settings & reports', '服务目录': 'Service catalog',
  '购买': 'Buy', '订单': 'Orders', '余额': 'Balance', '本月消费': 'Spent this month', '赠送余额': 'Bonus balance',
  '全部': 'All', '搜索': 'Search', '查询': 'Search', '重置': 'Reset', '刷新': 'Refresh', '保存': 'Save', '确定': 'Confirm',
  '共': 'Total', '个': 'items', '条': 'items',
  '新建': 'Create', '编辑': 'Edit', '删除': 'Delete', '操作': 'Actions', '状态': 'Status', '名称': 'Name', '创建时间': 'Created',
  '暂无数据': 'No data', '加载中': 'Loading', '请稍候': 'Please wait', '请选择': 'Select', '已启用': 'Enabled', '已禁用': 'Disabled',
  '成功': 'Success', '失败': 'Failed', '待处理': 'Pending', '进行中': 'In progress', '已完成': 'Completed',
  '当前模型': 'Models available', '模型厂商': 'Model providers', '统一协议接入': 'One compatible API',
  '智能调度网络': 'Intelligent routing network', '实时运行中': 'Live', '智能路由': 'Smart routing',
  '多模型接入': 'Every major model', '一次接入，全面覆盖': 'Integrate once, reach them all',
  '自动选择最优模型': 'Choose the best model automatically', '用量治理': 'Usage governance',
  '可视化成本与分析': 'Clear cost and usage insights', '企业协作': 'Team collaboration', '让团队高效共创': 'Build better, together',
  '更开放的 AI，更强大的创造力': 'Open access. Limitless creation.',
  '文本生成': 'Text generation', '图像生成': 'Image generation', '多模态理解': 'Multimodal understanding', '代码助手': 'Coding', '企业知识库': 'Enterprise knowledge',
  '用户协议': 'Terms of service', '隐私政策': 'Privacy policy', '返回首页': 'Back to home',
  '运营主体：': 'Operating entity:', '联系邮箱：': 'Contact email:', '联系地址：': 'Contact address:',
  '运营主体': 'Operating entity', '联系邮箱': 'Contact email', '联系地址': 'Contact address',
  'LinkNux API 服务平台': 'LinkNux API Platform',
  '使用本平台即表示您同意妥善保管账户与 API Key，并对企业成员授权负责。': 'By using this platform, you agree to safeguard your account and API keys and remain responsible for authorizing organization members.',
  '服务按实际用量计费，第三方模型提供方可能按请求处理数据。': 'Services are billed by actual usage. Third-party model providers may process request data.',
  '禁止违法、侵权、绕过安全控制或滥用服务。': 'Illegal or infringing activity, bypassing security controls, and service abuse are prohibited.',
  '企业主可管理组织成员、额度和 Token，相关操作会保留审计与财务记录。': 'Organization owners can manage members, quotas, and tokens. Related actions are retained in audit and financial records.',
  '具体退款、服务可用性、终止及争议处理规则以页面公示为准。': 'The published terms on refunds, service availability, termination, and dispute resolution apply.',
  '本文需由正式法律顾问复核。': 'This document must be reviewed by qualified legal counsel.',
  '我们为注册、鉴权、计费、安全审计和服务交付处理账户资料、企业联系信息、调用元数据及加密登录 IP 历史。': 'We process account data, organization contact information, request metadata, and encrypted login IP history for registration, authentication, billing, security audits, and service delivery.',
  '请求内容可能转交所选第三方模型服务商；企业可显式开启请求脱敏。': 'Request content may be sent to the selected third-party model provider. Organizations can explicitly enable request redaction.',
  '仅在实现目的所需期限内保存数据，并采取租户隔离、加密和访问控制。': 'We retain data only as long as necessary and apply tenant isolation, encryption, and access controls.',
  '您可申请查阅、更正、删除或撤回信任设备；跨境处理将依法履行适用义务。': 'You may request access, correction, deletion, or removal of trusted devices. Cross-border processing follows applicable legal requirements.',
  '本文以《个人信息保护法》和《网络数据安全管理条例》为一般合规基线，需由正式法律顾问复核。': 'This document uses applicable personal-information and network-data regulations as a general compliance baseline and must be reviewed by qualified legal counsel.',
  'CCMAX（对接专用）': 'CCMAX (Integration Only)',
  'Claude Code Max 20x组': 'Claude Code Max 20x Plan',
  'Claude kiro 95缓存（对接专用）': 'Claude Kiro 95% Cache (Integration Only)',
  'Claude特价组': 'Claude Discount Plan',
  'DeepSeek分组': 'DeepSeek Plan',
  'GPT Plus+Pro组': 'GPT Plus + Pro Plan',
  'GPT Plus稳定组': 'GPT Plus Stable Plan',
  'GPT Pro纯血组': 'GPT Pro Dedicated Plan',
  'GPT纯自建号池（对接专用）': 'GPT First-Party Account Pool (Integration Only)',
  'GPT低价分组': 'GPT Budget Plan', 'GPT对话分组': 'GPT Chat Plan',
  'Grok Free分组': 'Grok Free Plan', 'Grok Heavy分组': 'Grok Heavy Plan',
  '智谱GLM分组': 'Zhipu GLM Plan', '月之暗面Kimi分组': 'Moonshot Kimi Plan',
  '标准': 'Standard', '默认挡位': 'Default tier', '对接专用': 'Integration only',
  '当前公开路由已验证可调用': 'Current public route verified available',
  '多个平台路由': 'Multi-platform routing', 'USD / 次': 'USD / request',
  '超稳纯Pro池 满血稳定': 'Ultra-stable dedicated Pro pool',
  '仅Grok-4.5、4.6可用': 'Grok 4.5 and 4.6 only',
  '绝对稳定 蹬就完了': 'Highly stable, ready for sustained use',
  '满血Claude 最新模型可用 低价速刷内购号已成历史': 'Full-capability Claude with the latest models; built for reliable use.',
  '全模型可用 非映射 无工具调用 无缓存 目前只适用于对话场景': 'All models available; direct routing without tools or cache, currently for chat workloads.',
  '限时低价 猛猛蹬 5.6系列可用': 'Limited-time value pricing with GPT 5.6 series support.',
  '主Plus号池+Pro号池兜底 性价比高 稳定性up': 'Primary Plus pool with Pro fallback for strong value and reliability.',
  '最新deepseek-v4-flash/pro可用': 'Latest DeepSeek V4 Flash and Pro models available.',
  '最新glm-5.3可用': 'Latest GLM 5.3 available.', '最新kimi-k3可用': 'Latest Kimi K3 available.',
  'Claude kiro渠道 90高缓存 高性价比': 'Claude Kiro route with 90% cache efficiency and strong value.',
  'Grok会员号 可生图生视频': 'Grok subscription pool with image and video generation.',
}

const englishCopy: Readonly<Record<string, string>> = Object.freeze({
  ...generatedEnglishCopy,
  ...curatedEnglishCopy,
})

const normalizeCopy = (value: string) => value.replace(/\s+/gu, ' ').trim()
const escapeRegExp = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&')
const templateToken = /\$\{[^{}]*\}|\{\{[^{}]*\}\}/gu

type CopyPattern = { regex: RegExp; replace: (match: RegExpMatchArray) => string }

function compileCopyPattern(source: string, target: string): CopyPattern | null {
  const tokens = [...source.matchAll(templateToken)]
  if (!tokens.length) return null
  let cursor = 0
  const fragments: string[] = []
  for (const token of tokens) {
    const literal = source.slice(cursor, token.index).replace(/\s+/gu, ' ')
    fragments.push(escapeRegExp(literal).replace(/\\ /gu, '\\s+'), '(.*?)')
    cursor = (token.index ?? 0) + token[0].length
  }
  fragments.push(escapeRegExp(source.slice(cursor).replace(/\s+/gu, ' ')).replace(/\\ /gu, '\\s+'))
  const sourceTokens = tokens.map((token) => token[0])
  return {
    regex: new RegExp(`^${fragments.join('')}$`, 'u'),
    replace: (match) => target.replace(templateToken, (token) => {
      const sourceIndex = sourceTokens.indexOf(token)
      return match[sourceIndex >= 0 ? sourceIndex + 1 : 1] ?? ''
    }),
  }
}

const copyPatterns = Object.entries(englishCopy)
  .map(([source, target]) => compileCopyPattern(source, target))
  .filter((pattern): pattern is CopyPattern => pattern !== null)

const fragmentCopy = Object.entries(englishCopy)
  .filter(([source, target]) => /[\u3400-\u9fff\uf900-\ufaff]/u.test(source)
    && !/[\u3400-\u9fff\uf900-\ufaff]/u.test(target)
    && (source.length >= 2 || curatedEnglishCopy[source] !== undefined)
    && source.length <= 160
    && !/[{}[\]`=]/u.test(source))
  .sort(([left], [right]) => right.length - left.length)

export function toEnglishCopy(value: string): string {
  const normalized = normalizeCopy(value)
  const exact = englishCopy[normalized]
  if (exact) return exact
  let translated = normalized
  for (const pattern of copyPatterns) {
    const match = normalized.match(pattern.regex)
    if (match) {
      const patternTranslation = pattern.replace(match)
      if (patternTranslation !== normalized) {
        translated = patternTranslation
        break
      }
    }
  }
  for (const [source, target] of fragmentCopy) {
    if (translated.includes(source)) translated = translated.replaceAll(source, target)
  }
  if (translated !== normalized) return translated
  return value
}

export function localizeCopy(value: string): string {
  return isEnglish.value ? toEnglishCopy(value) : value
}

type TranslatedText = { original: string; translated: string }
const translatedNodes = new WeakMap<Text, TranslatedText>()

function translateTextNode(node: Text) {
  if (!node.parentElement || node.parentElement.closest('script,style,[data-no-auto-i18n]')) return
  if (!isEnglish.value) {
    const record = translatedNodes.get(node)
    if (record !== undefined) {
      node.data = record.original
      translatedNodes.delete(node)
    }
    return
  }
  const raw = node.data
  const record = translatedNodes.get(node)
  if (record?.translated === raw) return
  const translated = toEnglishCopy(raw)
  if (translated === raw) return
  const leading = raw.match(/^\s*/u)?.[0] ?? ''
  const trailing = raw.match(/\s*$/u)?.[0] ?? ''
  const localized = `${leading}${translated}${trailing}`
  translatedNodes.set(node, { original: raw, translated: localized })
  node.data = localized
}

function translateAttributes(element: Element) {
  for (const name of ['placeholder', 'title', 'aria-label', 'alt']) {
    const raw = element.getAttribute(name)
    if (!raw) continue
    const originalKey = `data-i18n-original-${name}`
    if (isEnglish.value) {
      const previousTranslation = element.getAttribute(`data-i18n-translated-${name}`)
      if (previousTranslation === raw) continue
      const translated = toEnglishCopy(raw)
      if (translated !== raw) {
        element.setAttribute(originalKey, raw)
        element.setAttribute(`data-i18n-translated-${name}`, translated)
        element.setAttribute(name, translated)
      }
    } else if (element.hasAttribute(originalKey)) {
      element.setAttribute(name, element.getAttribute(originalKey) || '')
      element.removeAttribute(originalKey)
      element.removeAttribute(`data-i18n-translated-${name}`)
    }
  }
}

function localizeTree(root: Node) {
  if (root.nodeType === Node.TEXT_NODE) translateTextNode(root as Text)
  if (root.nodeType === Node.ELEMENT_NODE) translateAttributes(root as Element)
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT | NodeFilter.SHOW_ELEMENT)
  let node: Node | null
  while ((node = walker.nextNode())) {
    if (node.nodeType === Node.TEXT_NODE) translateTextNode(node as Text)
    else translateAttributes(node as Element)
  }
}

export function installDomLocalization() {
  if (typeof document === 'undefined') return () => undefined
  let scheduled = false
  const refresh = () => {
    if (scheduled) return
    scheduled = true
    queueMicrotask(() => {
      scheduled = false
      localizeTree(document.body)
      if (isEnglish.value) document.title = toEnglishCopy(document.title)
    })
  }
  const observer = new MutationObserver(refresh)
  observer.observe(document.body, { childList: true, subtree: true, characterData: true })
  window.addEventListener('locale-changed', refresh)
  refresh()
  return () => { observer.disconnect(); window.removeEventListener('locale-changed', refresh) }
}
