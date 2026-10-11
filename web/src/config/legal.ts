export const legalLinks = [
  { path: '/terms', kind: 'terms', zh: '服务条款', en: 'Terms of service' },
  { path: '/privacy', kind: 'privacy', zh: '隐私政策', en: 'Privacy policy' },
  { path: '/refund', kind: 'refund', zh: '退款与取消', en: 'Refunds and cancellation' },
  { path: '/ai-data', kind: 'ai_data', zh: 'AI 数据处理', en: 'AI data processing' },
  { path: '/subprocessors', kind: 'subprocessors', zh: '子处理方', en: 'Subprocessors' },
  { path: '/cookies', kind: 'cookies', zh: 'Cookie 政策', en: 'Cookie policy' },
  { path: '/rights', kind: 'rights', zh: '隐私权利申请', en: 'Privacy requests' },
  { path: '/support', kind: 'support', zh: '帮助与投诉', en: 'Help and complaints' },
  { path: '/security', kind: 'security', zh: '安全说明', en: 'Security information' }
] as const
export type LegalKind = typeof legalLinks[number]['kind']
