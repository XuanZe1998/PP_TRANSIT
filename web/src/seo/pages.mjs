export const SITE_ORIGIN = 'https://linknux.com'

// Public, reviewed copy only. Do not freeze model inventories, prices, keys or account data.
export const publicPages = [
  {
    path: '/',
    zh: {
      title: 'Linknux | 面向开发者与团队的统一 AI API 平台',
      description: 'Linknux 为开发者与团队提供 OpenAI 兼容的统一 AI 接入入口。查看模型、接入文档和价格，了解多模型接入与用量管理。',
      heading: '一个接口，万般智能。',
      summary: '从 GPT、Claude、Gemini 到 DeepSeek、通义千问、智谱 GLM、Kimi，以 OpenAI 兼容协议接入不断生长的全球模型生态。',
      detail: '一把密钥，一个端点，一张清晰账单。',
    },
    en: {
      title: 'Linknux | One OpenAI-compatible AI API for developers and teams',
      description: 'Explore Linknux for unified, OpenAI-compatible AI access. Read integration docs, explore models and review pricing before connecting your application.',
      heading: 'One API. Every AI Model.',
      summary: 'Access GPT, Claude, Gemini, DeepSeek, Qwen, GLM, Kimi and more through one OpenAI-compatible API.',
      detail: 'One API key. One endpoint. One bill.',
    },
  },
  {
    path: '/docs',
    zh: {
      title: 'Linknux 接入文档 | OpenAI 兼容 API 与客户端配置',
      description: '查看 Linknux 接入步骤、Base URL、API Key 与模型 ID 配置说明。核验模型权限后发送最小请求，并确认客户端所需的接口格式。',
      heading: 'Linknux 开发者接入文档',
      summary: '使用 OpenAI 兼容接口连接你的应用。先核对 Base URL、API Key 与模型 ID，再确认客户端需要的是基础地址还是完整端点。',
      detail: '先检查模型权限，再发送最小请求。不同模型与协议的参数支持范围应以实际配置和响应为准。',
    },
    en: {
      title: 'Linknux integration docs | OpenAI-compatible API and client setup',
      description: 'Read Linknux integration steps for the base URL, API key and model ID. Verify model permissions and endpoint compatibility before sending a minimal request.',
      heading: 'Linknux developer integration docs',
      summary: 'Connect your application through an OpenAI-compatible interface. Check the base URL, API key and model ID, and whether your client requires a base address or a full endpoint.',
      detail: 'Verify model permissions, then send a minimal request. Parameter support depends on the model, protocol, configuration and actual response.',
    },
  },
  {
    path: '/pricing',
    zh: {
      title: 'Linknux 价格与计费 | 模型用量、钱包与充值说明',
      description: '了解 Linknux 模型用量与钱包充值说明。模型费率、支付币种、实付金额与到账额度以当前公开模型信息和付款前订单为准。',
      heading: 'Linknux 价格与计费说明',
      summary: '查看所需模型的当前费率与账户中可用的钱包充值方案，按实际模型用量管理预算。',
      detail: '付款前核对订单的币种、金额与到账额度。模型调用单独计费，一次性钱包充值不代表订阅或自动续费。',
    },
    en: {
      title: 'Linknux pricing and billing | Model usage and wallet funding',
      description: 'Understand Linknux model usage and wallet funding. Confirm current model rates, payment currency, final charge and credited amount before payment.',
      heading: 'Linknux pricing and billing',
      summary: 'Review current model rates and wallet funding plans available in your account, and budget for actual model usage.',
      detail: 'Confirm the currency, final charge and credited amount before payment. Model usage is billed separately; a one-time top-up does not imply a subscription or automatic renewal.',
    },
  },
]

export function getPublicPage(path) {
  const normalized = path.length > 1 ? path.replace(/\/$/, '') : path
  return publicPages.find(page => page.path === normalized)
}

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char])
}

export function renderHead(page, english = false) {
  const copy = english ? page.en : page.zh
  const canonical = SITE_ORIGIN + page.path
  return `<title>${escapeHtml(copy.title)}</title>
    <meta name="description" content="${escapeHtml(copy.description)}">
    <link rel="canonical" href="${canonical}">
    <meta property="og:type" content="website">
    <meta property="og:title" content="${escapeHtml(copy.title)}">
    <meta property="og:description" content="${escapeHtml(copy.description)}">
    <meta property="og:url" content="${canonical}">
    <meta name="twitter:card" content="summary">`
}

export function renderStaticBody(page) {
  const copy = page.zh
  return `<main class="seo-public-shell">
    <a href="/">Linknux</a>
    <h1>${escapeHtml(copy.heading)}</h1>
    <p>${escapeHtml(copy.summary)}</p><p>${escapeHtml(copy.detail)}</p>
    <nav aria-label="Linknux"><a href="/docs">接入文档</a> · <a href="/market">模型市场</a> · <a href="/pricing">价格与计费</a></nav>
  </main>`
}
