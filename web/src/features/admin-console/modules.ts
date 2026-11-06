export type ModuleKey = 'dashboard' | 'users' | 'channels' | 'models' | 'tokens' | 'audit' | 'finance' | 'security' | 'settings'
export type Column = {
  prop: string
  label: string
  width?: number
  minWidth?: number
  kind?: 'status' | 'bool' | 'callable' | 'money' | 'modelMoney' | 'rate' | 'profit' | 'percent'
    | 'tierRange' | 'tierOfficial' | 'tierCost' | 'tierSale' | 'tierProfit'
}
export type Field = { prop: string; label: string; type?: 'text' | 'password' | 'textarea' | 'number' | 'switch' | 'select'; min?: number; step?: number; options?: Array<{ label: string; value: any }> }
export type Config = {
  title: string
  description: string
  endpoint?: string
  createLabel?: string
  columns: Column[]
  fields: Field[]
  editable?: boolean
  deletable?: boolean
}

export function createAdminConfigs() {
const configs: Record<ModuleKey, Config> = {
  dashboard: {
    title: '运营总览',
    description: '平台请求、收入、成本、渠道健康和风险队列的实时快照。',
    endpoint: '/admin/api/dashboard',
    columns: [],
    fields: []
  },
  users: {
    title: '用户与分组',
    description: '管理用户状态、个人/企业组织、组织钱包、计费分组和手工调账。',
    endpoint: '/admin/api/users',
    columns: [
      { prop: 'username', label: '用户', minWidth: 150 },
      { prop: 'email', label: '邮箱', minWidth: 180 },
      { prop: 'account_type', label: '账户类型', width: 110 },
      { prop: 'organization_name', label: '默认组织', minWidth: 180 },
      { prop: 'organization_type', label: '组织类型', width: 110 },
      { prop: 'organization_role', label: '组织角色', width: 110 },
      { prop: 'organization_count', label: '组织数', width: 90 },
      { prop: 'wallet_balance', label: '组织钱包', width: 130, kind: 'money' },
      { prop: 'wallet_held_balance', label: '预占金额', width: 120, kind: 'money' },
      { prop: 'last_login_ip', label: '最近登录 IP', minWidth: 150 },
      { prop: 'last_login_ip_at', label: '登录时间', minWidth: 170 },
      { prop: 'login_ip_count', label: '历史 IP', width: 90 },
      { prop: 'role', label: '角色', width: 100 },
      { prop: 'status', label: '状态', width: 110, kind: 'status' },
      { prop: 'group_name', label: '分组', minWidth: 130 },
      { prop: 'balance', label: '余额', width: 120, kind: 'money' },
      { prop: 'invoice_enabled', label: '账单 / 发票', width: 110, kind: 'bool' },
      { prop: 'token_count', label: 'Key', width: 90 },
      { prop: 'request_count', label: '请求', width: 100 }
    ],
    fields: [
      { prop: 'role', label: '角色', type: 'select', options: [{ label: 'USER', value: 'USER' }, { label: 'ADMIN', value: 'ADMIN' }] },
      { prop: 'status', label: '状态', type: 'select', options: [{ label: 'ACTIVE', value: 'ACTIVE' }, { label: 'SUSPENDED', value: 'SUSPENDED' }] },
      { prop: 'groupId', label: '分组 ID', type: 'number' },
      { prop: 'invoiceEnabled', label: '开放账单 / 发票申请', type: 'switch' }
    ],
    editable: true
  },
  channels: {
    title: '渠道治理',
    description: '维护供应商接入、密钥、权重、限流、健康状态和冷却策略。',
    endpoint: '/admin/api/channels',
    createLabel: '新建渠道',
    columns: [
      { prop: 'name', label: '渠道', minWidth: 170 },
      { prop: 'type', label: '类型', width: 130 },
      { prop: 'baseUrl', label: 'Base URL', minWidth: 220 },
      { prop: 'groupName', label: '分组', width: 120 },
      { prop: 'weight', label: '权重', width: 90 },
      { prop: 'rpmLimit', label: 'RPM', width: 90 },
      { prop: 'tpmLimit', label: 'TPM', width: 100 },
      { prop: 'consecutiveFailures', label: '连续失败', width: 100 },
      { prop: 'averageLatencyMs', label: '平均延迟', width: 110 },
      { prop: 'healthStatus', label: '健康', width: 120, kind: 'status' },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: [
      { prop: 'name', label: '渠道名称' },
      { prop: 'type', label: '协议类型', type: 'select', options: ['openai', 'haoee', 'deepseek', 'anthropic', 'gemini', 'xai', 'openrouter', 'qwen', 'kimi', 'glm', 'mistral', 'meta', 'nvidia'].map(value => ({ label: value, value })) },
      { prop: 'baseUrl', label: 'Base URL' },
      { prop: 'apiKey', label: 'API Key', type: 'password' },
      { prop: 'models', label: '模型清单', type: 'textarea' },
      { prop: 'groupName', label: '渠道分组' },
      { prop: 'weight', label: '权重', type: 'number', min: 1 },
      { prop: 'rpmLimit', label: 'RPM 限制', type: 'number' },
      { prop: 'tpmLimit', label: 'TPM 限制', type: 'number' },
      { prop: 'autoDisable', label: '连续失败自动熔断', type: 'switch' },
      { prop: 'failureThreshold', label: '熔断失败阈值', type: 'number', min: 1 },
      { prop: 'cooldownSeconds', label: '熔断冷却秒数', type: 'number', min: 5 },
      { prop: 'enabled', label: '启用', type: 'switch' }
    ],
    editable: true,
    deletable: true
  },
  models: {
    title: '模型与定价',
    description: '由渠道治理中的模型清单与逐模型定价自动生成。这里仅展示路由、售价、上游成本和毛利。',
    endpoint: '/admin/api/models',
    columns: [
      { prop: 'publicModelName', label: '公开模型', minWidth: 180 },
      { prop: 'channelModelName', label: '渠道模型', minWidth: 180 },
      { prop: 'channel.name', label: '渠道', minWidth: 150 },
      { prop: 'priority', label: '优先级', width: 90 },
      { prop: 'priceRatio', label: '倍率', width: 90 },
      { prop: 'priceTiers', label: '上下文挡位', minWidth: 220, kind: 'tierRange' },
      { prop: 'officialPrices', label: '官网 输入/输出/读/写', minWidth: 310, kind: 'tierOfficial' },
      { prop: 'costPrices', label: '成本 输入/输出/读/写', minWidth: 310, kind: 'tierCost' },
      { prop: 'salePrices', label: '售价 输入/输出/读/写', minWidth: 310, kind: 'tierSale' },
      { prop: 'tierProfit', label: '毛利 输入/输出/读/写', minWidth: 310, kind: 'tierProfit' },
      { prop: 'billingEnabled', label: '计费', width: 90, kind: 'bool' },
      { prop: 'trafficPercent', label: '灰度', width: 90 },
      { prop: 'callable', label: '前台调用', width: 110, kind: 'callable' },
      { prop: 'availabilityMessage', label: '可用性说明', minWidth: 190 },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: []
  },
  tokens: {
    title: 'Token 与权限',
    description: '管理平台 Key、用户归属、额度、过期时间、模型范围和 IP 白名单。',
    endpoint: '/admin/api/tokens',
    createLabel: '生成 Token',
    columns: [
      { prop: 'name', label: '名称', minWidth: 150 },
      { prop: 'keyPreview', label: 'Key（脱敏）', minWidth: 220 },
      { prop: 'userId', label: '用户ID', width: 100 },
      { prop: 'usedQuota', label: '已用', width: 110 },
      { prop: 'totalQuota', label: '总额度', width: 110 },
      { prop: 'allowedModels', label: '模型范围', minWidth: 160 },
      { prop: 'ipWhitelist', label: 'IP 白名单', minWidth: 160 },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: [
      { prop: 'name', label: '名称' },
      { prop: 'userId', label: '用户 ID', type: 'number' },
      { prop: 'totalQuota', label: '总额度', type: 'number', step: 10000 },
      { prop: 'allowedModels', label: '允许模型，逗号分隔' },
      { prop: 'ipWhitelist', label: 'IP 白名单，逗号分隔' },
      { prop: 'description', label: '说明', type: 'textarea' },
      { prop: 'enabled', label: '启用', type: 'switch' }
    ],
    editable: true,
    deletable: true
  },
  audit: {
    title: '调用审计',
    description: '排查请求链路、失败原因、成本、售价、延迟和管理员写操作。',
    endpoint: '/admin/api/audit/request-logs',
    columns: [
      { prop: 'trace_id', label: 'Trace ID', minWidth: 140 },
      { prop: 'created_at', label: '请求时间', minWidth: 170 },
      { prop: 'username', label: '用户', minWidth: 120 },
      { prop: 'model', label: '模型', minWidth: 160 },
      { prop: 'channel_name', label: '渠道', minWidth: 140 },
      { prop: 'total_tokens', label: 'Token', width: 110 },
      { prop: 'sale_amount', label: '模型售价(USD)', width: 140, kind: 'modelMoney' },
      { prop: 'cost_amount', label: '成本(USD)', width: 125, kind: 'modelMoney' },
      { prop: 'gross_profit', label: '毛利(USD)', width: 125, kind: 'modelMoney' },
      { prop: 'request_margin', label: '毛利率', width: 100, kind: 'percent' },
      { prop: 'latency_ms', label: '延迟', width: 100 },
      { prop: 'status', label: '状态', width: 110, kind: 'status' },
      { prop: 'error_message', label: '错误', minWidth: 220 }
    ],
    fields: []
  },
  finance: {
    title: '钱包财务',
    description: '查看充值、消费、调账流水，并生成可控兑换码。',
    endpoint: '/admin/api/finance/transactions',
    createLabel: '生成兑换码',
    columns: [],
    fields: [
      { prop: 'code', label: '兑换码，不填自动生成' },
      { prop: 'amount', label: '额度', type: 'number', step: 10000 },
      { prop: 'maxUses', label: '最大使用次数', type: 'number', min: 1 }
    ]
  },
  security: {
    title: '安全策略',
    description: '配置全局、分组、用户和 Token 级限流、拦截、告警策略。',
    endpoint: '/admin/api/security/policies',
    createLabel: '新增策略',
    columns: [
      { prop: 'name', label: '策略', minWidth: 160 },
      { prop: 'scope', label: '范围', minWidth: 140 },
      { prop: 'action', label: '动作', width: 130 },
      { prop: 'threshold_value', label: '阈值', minWidth: 160 },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: [
      { prop: 'name', label: '策略名称' },
      { prop: 'scope', label: '范围' },
      { prop: 'action', label: '动作', type: 'select', options: ['RATE_LIMIT', 'BLOCK', 'WARN', 'REVIEW'].map(value => ({ label: value, value })) },
      { prop: 'threshold', label: '阈值' },
      { prop: 'enabled', label: '启用', type: 'switch' }
    ],
    editable: true
  },
  settings: {
    title: '系统配置与报表',
    description: '维护站点配置、注册策略、公告，并查看模型和渠道维度报表。',
    endpoint: '/admin/api/settings',
    createLabel: '新增配置',
    columns: [],
    fields: [
      { prop: 'key', label: '配置键' },
      { prop: 'value', label: '配置值', type: 'textarea' },
      { prop: 'description', label: '说明' }
    ],
    editable: true
  }
}

return configs
}
