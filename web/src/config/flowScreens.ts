export type FlowRole = 'public' | 'user' | 'admin'

export type Metric = {
  label: string
  value: string
  badge: string
  tone: 'blue' | 'green' | 'orange' | 'red' | 'purple' | 'gray'
}

export type TableBlock = {
  title: string
  columns: string[]
  rows: string[][]
}

export type FeatureCard = {
  title: string
  description: string
  tags: string[]
}

export type FlowScreenConfig = {
  key: string
  order: number
  path: string
  role: FlowRole
  title: string
  subtitle: string
  activeNav?: string
  primaryAction: string
  metrics?: Metric[]
  cards?: FeatureCard[]
  table?: TableBlock
  code?: string
  form?: { label: string; value: string }[]
}

export const publicNav = [
  { label: '产品总览', path: '/' },
  { label: '模型市场', path: '/market' },
  { label: '文档 SDK', path: '/console/docs' },
  { label: '登录', path: '/login' }
]

export const userNav = [
  { label: '总览', path: '/console' },
  { label: '模型市场', path: '/market' },
  { label: 'API Key', path: '/console/keys' },
  { label: '在线调试', path: '/console/playground' },
  { label: '用量日志', path: '/console/logs' },
  { label: '钱包充值', path: '/console/wallet' },
  { label: '服务订单', path: '/services' },
  { label: '文档 SDK', path: '/docs' },
  { label: '账户安全', path: '/console/security' }
]

export const adminNav = [
  { label: '运营总览', path: '/admin' },
  { label: '用户管理', path: '/admin/users' },
  { label: '渠道治理', path: '/admin/channels' },
  { label: '模型与定价', path: '/admin/models' },
  { label: '令牌配额', path: '/admin/tokens' },
  { label: '日志审计', path: '/admin/audit-logs' },
  { label: '财务订单', path: '/admin/finance' },
  { label: '服务与订单', path: '/admin/other-services' },
  { label: 'OAuth 应用', path: '/admin/oauth' },
  { label: '系统设置', path: '/admin/settings' },
  { label: '安全策略', path: '/admin/security' },
  { label: '报表导出', path: '/admin/reports' },
  { label: '集成导出', path: '/admin/integrations' }
]

const gatewayMetrics: Metric[] = [
  { label: '供应商', value: '12+', badge: '已接入', tone: 'blue' },
  { label: '可用模型', value: '300+', badge: '同步', tone: 'green' },
  { label: 'SLA', value: '99.9%', badge: '承诺', tone: 'green' },
  { label: '成本节省', value: '38%', badge: '估算', tone: 'orange' }
]

const opsMetrics: Metric[] = [
  { label: '总请求', value: '1.28M', badge: '+12%', tone: 'green' },
  { label: '收入', value: '¥42.8K', badge: '+9%', tone: 'blue' },
  { label: '成功率', value: '99.2%', badge: '稳定', tone: 'green' },
  { label: '异常', value: '4,218', badge: '处理', tone: 'red' }
]

export const flowScreens: FlowScreenConfig[] = [
  {
    key: 'home',
    order: 1,
    path: '/',
    role: 'public',
    title: '首页 / 产品总览',
    subtitle: '面向开发者与团队的一站式 AI 能力平台，统一连接主流模型，提供智能路由、用量治理、创作工具与企业协作。',
    primaryAction: '立即接入',
    metrics: gatewayMetrics,
    cards: [
      { title: '统一协议入口', description: '对外保持 OpenAI Chat Completions 风格，屏蔽 Claude、Gemini、DeepSeek 等供应商差异。', tags: ['OpenAI SDK', '流式响应', '错误码统一'] },
      { title: '运营后台', description: '集中管理渠道、模型映射、令牌、余额、订单和审计日志。', tags: ['渠道故障切换', 'Token 配额', '用量报表'] },
      { title: '成本治理', description: '支持模型倍率、用户分组倍率、渠道成本价和毛利分析。', tags: ['模型定价', '成本分析', '财务流水'] }
    ],
    code: 'POST /v1/chat/completions\nAuthorization: Bearer YOUR_API_KEY\nmodel: claude-sonnet -> 已配置的健康渠道'
  },
  {
    key: 'auth',
    order: 2,
    path: '/login',
    role: 'public',
    title: '登录 / 注册 / OAuth',
    subtitle: '支持账号密码、第三方 OAuth、管理员初始化、注册开关与邀请码策略。',
    primaryAction: '登录控制台',
    form: [
      { label: '邮箱 / 用户名', value: 'team@example.com' },
      { label: '密码', value: '••••••••' },
      { label: '登录方式', value: '密码 / GitHub / Google / 企业 SSO' }
    ],
    cards: [
      { title: '注册策略', description: '管理员可以选择开放注册、邀请码注册、邮箱白名单或关闭注册。', tags: ['开放注册', '邀请码', '邮箱白名单'] },
      { title: '安全登录', description: '登录失败风控、会话超时、OAuth 绑定和退出撤销。', tags: ['OAuth', 'SSO', '会话撤销'] }
    ]
  },
  {
    key: 'market',
    order: 3,
    path: '/market',
    role: 'user',
    activeNav: '模型市场',
    title: '模型市场 / 公开目录',
    subtitle: '查看公开模型、价格倍率、上下文、能力标签和示例代码。',
    primaryAction: '申请模型',
    cards: [
      { title: 'gpt-4o-mini', description: '倍率 1x，上下文 128K，适合低延迟通用对话。', tags: ['OpenAI', 'Chat', '低延迟'] },
      { title: 'claude-sonnet', description: '倍率 3x，上下文 200K，适合高质量文本与代码。', tags: ['Anthropic', 'Code', '长文本'] },
      { title: 'gemini-2.5-pro', description: '倍率 2x，上下文 1M，适合长上下文与多模态任务。', tags: ['Gemini', '多模态', '长上下文'] },
      { title: 'deepseek-chat', description: '倍率 0.5x，中文与成本优化场景优先。', tags: ['DeepSeek', '低成本', '中文'] }
    ]
  },
  {
    key: 'user-overview',
    order: 4,
    path: '/console',
    role: 'user',
    activeNav: '总览',
    title: '用户总览',
    subtitle: '余额、Key、请求质量、最近消费和公告集中展示。',
    primaryAction: '充值',
    metrics: [
      { label: '余额', value: '¥428.60', badge: '可用', tone: 'green' },
      { label: '今日请求', value: '18,240', badge: '+12%', tone: 'blue' },
      { label: 'Token 消耗', value: '9.2M', badge: '+8%', tone: 'orange' },
      { label: '可用 Key', value: '4', badge: '启用', tone: 'green' }
    ],
    cards: [
      { title: '公告', description: 'DeepSeek 渠道维护、新模型上架、充值活动和流式响应支持。', tags: ['维护通知', '新模型', '充值赠送'] },
      { title: '快速入口', description: '从总览进入 Key 管理、在线调试、钱包充值和用量日志。', tags: ['API Key', 'Playground', '账单'] }
    ]
  },
  {
    key: 'api-keys',
    order: 5,
    path: '/console/keys',
    role: 'user',
    activeNav: 'API Key',
    title: 'API Key 管理',
    subtitle: '创建、复制、停用、限额、过期、IP 白名单、模型范围限制。',
    primaryAction: '创建 Key',
    metrics: [
      { label: '总 Key', value: '6', badge: '4 启用', tone: 'green' },
      { label: '总额度', value: '12M', badge: '剩余 42%', tone: 'blue' },
      { label: '过期预警', value: '1', badge: '7 天内', tone: 'orange' },
      { label: 'IP 限制', value: '3', badge: '已配置', tone: 'purple' }
    ],
    table: {
      title: '密钥列表',
      columns: ['名称', 'Key', '额度', '模型范围', 'IP 白名单', '状态'],
      rows: [
        ['生产', 'sk-prod-••••', '8M / 12M', '全部', '10.0.0.0/8', '启用'],
        ['测试', 'sk-test-••••', '1M / 5M', '低价组', '不限', '启用'],
        ['临时', 'sk-demo-••••', '0 / 100K', 'gpt-4o-mini', '192.168.1.2', '停用']
      ]
    }
  },
  {
    key: 'playground',
    order: 6,
    path: '/console/playground',
    role: 'user',
    activeNav: '在线调试',
    title: '在线调试 / 调用示例',
    subtitle: '内置 Playground、流式开关、参数面板和 cURL/JS/Python 示例生成。',
    primaryAction: '发送请求',
    form: [
      { label: 'Key', value: '生产环境 Key' },
      { label: '模型', value: 'deepseek-chat' },
      { label: 'temperature', value: '0.7' },
      { label: 'stream', value: 'true' },
      { label: 'Prompt', value: '解释多模型平台的故障切换机制' }
    ],
    code: 'curl https://api.example.com/v1/chat/completions \\\n  -H "Authorization: Bearer sk-..." \\\n  -d "{ model, messages, stream }"'
  },
  {
    key: 'usage-logs',
    order: 7,
    path: '/console/logs',
    role: 'user',
    activeNav: '用量日志',
    title: '用量日志 / 明细',
    subtitle: '按 Key、模型、状态、日期过滤，支持慢请求和失败原因排查。',
    primaryAction: '导出 CSV',
    table: {
      title: '调用明细',
      columns: ['时间', 'Key', '模型', '耗时', 'Token', '费用', '状态'],
      rows: [
        ['12:04', '生产', 'claude-sonnet', '1.8s', '12,480', '¥0.82', '成功'],
        ['12:01', '测试', 'deepseek-chat', '0.9s', '2,102', '¥0.03', '成功'],
        ['11:58', '临时', 'gpt-4o-mini', '-', '0', '¥0.00', '异常']
      ]
    }
  },
  {
    key: 'wallet',
    order: 8,
    path: '/console/wallet',
    role: 'user',
    activeNav: '钱包充值',
    title: '钱包充值 / 兑换码',
    subtitle: '余额、充值套餐、兑换码、发票申请和账单流水。',
    primaryAction: '充值',
    metrics: [
      { label: '当前余额', value: '¥428.60', badge: '可用', tone: 'green' },
      { label: '本月消费', value: '¥1,240', badge: '+18%', tone: 'orange' },
      { label: '赠送额度', value: '¥86', badge: '活动', tone: 'blue' },
      { label: '发票金额', value: '¥800', badge: '可开', tone: 'purple' }
    ],
    cards: [
      { title: '充值套餐', description: '支持支付宝、微信、Stripe、手工入账和充值赠送活动。', tags: ['¥50 入门包', '¥200 +3%', '¥500 +8%'] },
      { title: '兑换码', description: '用户输入兑换码后自动增加余额或赠送额度。', tags: ['批量码', '有效期', '使用次数'] }
    ],
    table: {
      title: '钱包流水',
      columns: ['时间', '类型', '金额', '备注'],
      rows: [
        ['06-30', '充值', '+¥500', '支付宝'],
        ['06-29', '消费', '-¥34.2', 'API 调用'],
        ['06-28', '兑换', '+¥20', '活动码']
      ]
    }
  },
  {
    key: 'user-service-orders',
    order: 9,
    path: '/services',
    role: 'user',
    activeNav: '服务订单',
    title: '服务订单',
    subtitle: '会员服务商品、下单、凭证下载、订单状态和履约备注。',
    primaryAction: '创建订单',
    cards: [
      { title: 'ChatGPT Plus 月度', description: '人工履约，订单凭证下载，不保存第三方账号密码。', tags: ['¥168', '凭证', '人工确认'] },
      { title: 'Claude Pro 月度', description: '适合高质量文本和代码场景的会员服务商品。', tags: ['¥198', '履约备注', '售后'] }
    ],
    table: {
      title: '我的订单',
      columns: ['订单', '商品', '金额', '状态', '凭证'],
      rows: [
        ['PO-18', 'ChatGPT Plus', '¥168', '待确认', '下载'],
        ['PO-17', 'Claude Pro', '¥198', '已履约', '下载']
      ]
    }
  },
  {
    key: 'docs',
    order: 10,
    path: '/docs',
    role: 'user',
    activeNav: '文档 SDK',
    title: '文档 / SDK 接入',
    subtitle: 'Base URL、鉴权、兼容 SDK、错误码、流式响应和最佳实践。',
    primaryAction: '复制 Base URL',
    cards: [
      { title: '快速开始', description: '使用 OpenAI SDK 指向平台 Base URL 即可调用公开模型。', tags: ['Base URL', 'Bearer Token', 'Chat Completions'] },
      { title: '能力文档', description: '覆盖模型列表、错误码、流式响应、成本优化和安全建议。', tags: ['错误码', 'Stream', '限流'] }
    ],
    code: 'Base URL: https://api.example.com/v1\nOpenAI SDK: client.chat.completions.create(...)\n规划支持 Chat / Embeddings / Images / Rerank'
  },
  {
    key: 'security',
    order: 11,
    path: '/console/security',
    role: 'user',
    activeNav: '账户安全',
    title: '账户安全 / 通知',
    subtitle: '个人资料、密码、二次验证、登录设备、通知订阅和 Webhook。',
    primaryAction: '保存',
    form: [
      { label: '用户名', value: 'team-a' },
      { label: '邮箱', value: 'team@example.com' },
      { label: '企业名称', value: 'Transit Lab' },
      { label: '通知邮箱', value: 'ops@example.com' }
    ],
    cards: [
      { title: '安全项', description: '支持修改密码、2FA、登录设备管理和调用 Webhook。', tags: ['2FA', '设备管理', 'Webhook'] }
    ]
  },
  {
    key: 'admin-overview',
    order: 12,
    path: '/admin',
    role: 'admin',
    activeNav: '运营总览',
    title: '运营总览',
    subtitle: '平台级请求、收入、健康、风险、渠道和用户增长。',
    primaryAction: '新建渠道',
    metrics: opsMetrics,
    cards: [
      { title: '风险队列', description: '渠道余额低、失败率升高、用户超限、订单待履约。', tags: ['余额低', '失败率', '待履约'] },
      { title: '流量趋势', description: '按小时聚合请求量、收入、失败和重试请求。', tags: ['请求量', '收入', '重试'] }
    ]
  },
  {
    key: 'admin-users',
    order: 13,
    path: '/admin/users',
    role: 'admin',
    activeNav: '用户管理',
    title: '用户管理 / 分组',
    subtitle: '用户、角色、分组倍率、余额、封禁、邀请关系和实名备注。',
    primaryAction: '创建用户',
    table: {
      title: '用户列表',
      columns: ['用户', '角色', '分组', '余额', '倍率', '状态'],
      rows: [
        ['team-a', '普通', '企业组', '¥428', '0.8x', '启用'],
        ['ops', '管理员', '内部', '¥999', '0x', '启用'],
        ['guest', '普通', '游客', '¥0', '1.2x', '异常']
      ]
    }
  },
  {
    key: 'admin-channels',
    order: 14,
    path: '/admin/channels',
    role: 'admin',
    activeNav: '渠道治理',
    title: '渠道治理',
    subtitle: '供应商渠道、Key 池、权重、健康检查、冷却、失败切换和限流。',
    primaryAction: '新建渠道',
    table: {
      title: '渠道列表',
      columns: ['渠道', '类型', '分组', '权重', 'RPM/TPM', '健康', '冷却'],
      rows: [
        ['OpenAI Primary', 'openai', 'premium', '100', '500/2M', '健康', '0m'],
        ['Claude Backup', 'anthropic', 'premium', '80', '200/1M', '健康', '0m'],
        ['DeepSeek CN', 'deepseek', 'default', '60', '800/4M', '降级', '5m']
      ]
    }
  },
  {
    key: 'admin-models',
    order: 15,
    path: '/admin/models',
    role: 'admin',
    activeNav: '模型与定价',
    title: '模型与定价',
    subtitle: '公开模型、渠道模型、倍率、成本单价、灰度比例和能力标签。',
    primaryAction: '同步模型',
    table: {
      title: '模型定价',
      columns: ['公开模型', '渠道模型', '分组', '倍率', '成本', '灰度', '状态'],
      rows: [
        ['gpt-4o-mini', 'gpt-4o-mini', 'default', '1x', '$0.15/M', '100%', '启用'],
        ['claude-sonnet', 'claude-4-sonnet', 'premium', '3x', '$3/M', '80%', '启用'],
        ['deepseek-chat', 'deepseek-chat', 'low-cost', '0.5x', '$0.07/M', '50%', '降级']
      ]
    }
  },
  {
    key: 'admin-tokens',
    order: 16,
    path: '/admin/tokens',
    role: 'admin',
    activeNav: '令牌配额',
    title: '令牌与配额',
    subtitle: '平台 Token、用户 Key、额度、过期、IP 白名单、模型权限。',
    primaryAction: '生成 Token',
    table: {
      title: '令牌列表',
      columns: ['名称', '归属', '类型', '额度', '过期', '权限', '状态'],
      rows: [
        ['财务系统', 'finance', '服务端', '20M', '2026-12', '全部', '启用'],
        ['演示 Key', 'guest', '用户', '100K', '7 天', '低价模型', '启用'],
        ['旧系统', 'legacy', '服务端', '0', '已过期', '全部', '异常']
      ]
    }
  },
  {
    key: 'admin-audit',
    order: 17,
    path: '/admin/audit-logs',
    role: 'admin',
    activeNav: '日志审计',
    title: '日志审计 / 追踪',
    subtitle: '请求日志、错误栈、追踪 ID、慢请求、模型身份校验和敏感词审计。',
    primaryAction: '导出日志',
    table: {
      title: '审计日志',
      columns: ['追踪 ID', '用户', '模型', '渠道', '耗时', '费用', '结果'],
      rows: [
        ['req_9f24', 'team-a', 'gpt-4o-mini', 'OpenAI', '0.8s', '¥0.12', '成功'],
        ['req_9f1d', 'guest', 'deepseek-chat', 'DeepSeek', '-', '¥0', '异常'],
        ['req_9e92', 'finance', 'claude-sonnet', 'Anthropic', '2.4s', '¥1.20', '成功']
      ]
    }
  },
  {
    key: 'admin-finance',
    order: 18,
    path: '/admin/finance',
    role: 'admin',
    activeNav: '财务订单',
    title: '财务 / 充值 / 兑换码',
    subtitle: '充值订单、兑换码、钱包流水、退款、发票和支付渠道配置。',
    primaryAction: '生成兑换码',
    cards: [
      { title: '支付渠道', description: '支付宝、微信、Stripe、人工入账和手工调账。', tags: ['支付宝', '微信', 'Stripe'] },
      { title: '兑换码', description: '批量生成、设置有效期、面额、使用次数和用户范围。', tags: ['批量生成', '有效期', '面额'] }
    ],
    table: {
      title: '充值订单',
      columns: ['订单', '用户', '金额', '渠道', '状态'],
      rows: [
        ['R-1001', 'team-a', '¥500', '支付宝', '成功'],
        ['R-1002', 'guest', '¥50', '兑换码', '成功']
      ]
    }
  },
  {
    key: 'admin-services',
    order: 19,
    path: '/admin/other-services',
    role: 'admin',
    activeNav: '服务与订单',
    title: '服务目录 / 订单履约',
    subtitle: '商品配置、订单确认、凭证生成、履约备注和售后状态。',
    primaryAction: '新建商品',
    table: {
      title: '服务订单',
      columns: ['订单', '用户', '商品', '金额', '状态', '履约备注'],
      rows: [
        ['PO-18', 'team-a', 'ChatGPT Plus', '¥168', '待确认', '等待付款凭证'],
        ['PO-17', 'dev', 'Claude Pro', '¥198', '已履约', '已发送凭证']
      ]
    }
  },
  {
    key: 'admin-oauth',
    order: 20,
    path: '/admin/oauth',
    role: 'admin',
    activeNav: 'OAuth 应用',
    title: 'OAuth 应用 / SSO',
    subtitle: '第三方登录、授权客户端、回调地址、令牌撤销和企业 SSO 规划。',
    primaryAction: '新建应用',
    table: {
      title: 'OAuth Clients',
      columns: ['应用', 'Client ID', '回调地址', '授权范围', '状态'],
      rows: [
        ['内部门户', 'cli_23', '/oauth/callback', 'profile token', '启用'],
        ['GitHub 登录', 'gh_18', '/oauth/callback/github', 'login', '启用']
      ]
    }
  },
  {
    key: 'admin-settings',
    order: 21,
    path: '/admin/settings',
    role: 'admin',
    activeNav: '系统设置',
    title: '系统设置 / 公告',
    subtitle: '站点品牌、注册策略、公告、主题、国际化、邮件和对象存储。',
    primaryAction: '保存设置',
    cards: [
      { title: '站点设置', description: '站点名称、Logo、公告横幅、暗色主题、中英文切换。', tags: ['Logo', '公告', '主题'] },
      { title: '运行配置', description: 'SMTP 邮件、对象存储、Redis 缓存、备份恢复和环境变量检查。', tags: ['SMTP', '对象存储', '备份'] }
    ]
  },
  {
    key: 'admin-security',
    order: 22,
    path: '/admin/security',
    role: 'admin',
    activeNav: '安全策略',
    title: '安全策略 / 限流',
    subtitle: '全局限速、模型白名单、敏感词、Prompt 防注入、密钥泄露检测。',
    primaryAction: '新增策略',
    table: {
      title: '策略列表',
      columns: ['策略', '范围', '动作', '阈值', '状态'],
      rows: [
        ['RPM 限制', 'default 组', '限流', '500/min', '启用'],
        ['敏感词', '全站', '阻断', '命中即拒绝', '启用'],
        ['Prompt 注入', '企业组', '告警', '高风险', '启用'],
        ['模型身份校验', '高价模型', '复核', '抽样 5%', '降级']
      ]
    }
  },
  {
    key: 'admin-reports',
    order: 23,
    path: '/admin/reports',
    role: 'admin',
    activeNav: '报表导出',
    title: '报表导出 / 成本分析',
    subtitle: '按用户、模型、渠道、日期、分组导出成本、利润、延迟和失败率。',
    primaryAction: '生成报表',
    metrics: [
      { label: '毛利率', value: '32%', badge: '估算', tone: 'green' },
      { label: 'P95 延迟', value: '2.4s', badge: '稳定', tone: 'blue' },
      { label: '高价模型占比', value: '18%', badge: '可控', tone: 'orange' },
      { label: '失败成本', value: '¥128', badge: '下降', tone: 'green' }
    ],
    table: {
      title: '报表模板',
      columns: ['维度', '指标', '周期', '导出'],
      rows: [
        ['用户 / 分组', '费用 / Token', '日 / 周 / 月', 'CSV'],
        ['模型 / 渠道', '延迟 / 成功率', '小时 / 日', 'XLSX'],
        ['订单 / 钱包', '收入 / 退款', '月', 'PDF']
      ]
    }
  },
  {
    key: 'admin-integrations',
    order: 24,
    path: '/admin/integrations',
    role: 'admin',
    activeNav: '集成导出',
    title: '集成导出 / 客户端适配',
    subtitle: '一键导出到 Cherry Studio、Claude Code Router、CLI 代理和 OpenAI SDK 配置。',
    primaryAction: '生成配置',
    cards: [
      { title: '客户端导出', description: '生成 OpenAI SDK .env、Cherry Studio、Claude Code Router 和 CLI 配置。', tags: ['OpenAI SDK', 'Cherry Studio', 'Claude Code Router'] },
      { title: '兼容性验证', description: '检查 Base URL、Key、模型列表、流式响应和余额价格。', tags: ['可达性', '模型同步', '流式测试'] }
    ]
  }
]

export const screenByKey = Object.fromEntries(flowScreens.map(screen => [screen.key, screen])) as Record<string, FlowScreenConfig>
