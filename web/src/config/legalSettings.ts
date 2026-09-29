export type LegalSettingField = { key: string; label: string; hint: string; multiline?: boolean }
export type LegalSettingGroup = { title: string; fields: LegalSettingField[] }

// Fixed disclosure form, not a user-generated list. Do not supply fabricated operator facts. Draft policy bodies still require review.
export const legalSettingGroups: LegalSettingGroup[] = [
  { title: '经营者与联系信息', fields: [
    { key: 'legal.operator', label: '经营者名称', hint: '填写真实经营者身份；不要把品牌名当作法定身份。' },
    { key: 'legal.address', label: '联系地址', hint: '填写愿意公开、且可用于正式联系的真实地址。' },
    { key: 'legal.registration', label: '登记信息', hint: '仅填写真实有效的登记编号或登记说明；没有营业执照时保持空白，不要编造。' },
    { key: 'legal.jurisdiction', label: '经营所在地', hint: '填写实际经营所在地及适用司法辖区。' },
    { key: 'legal.contact_email', label: '联系邮箱', hint: '填写你控制且能收到投诉或权利请求的邮箱。' }
  ] },
  { title: '中文政策正文', fields: [
    { key: 'legal.terms', label: '服务条款', hint: '如实说明账号、计费、限制、终止与争议规则。', multiline: true },
    { key: 'legal.privacy', label: '隐私政策', hint: '如实说明数据收集、用途、保留、接收方及权利。', multiline: true },
    { key: 'legal.refund', label: '退款与取消', hint: '如实说明适用服务、流程和期限。', multiline: true },
    { key: 'legal.ai_data', label: 'AI 数据处理', hint: '核对上游、处理地区、日志及删除方式。', multiline: true },
    { key: 'legal.rights', label: '用户权利', hint: '说明申请渠道和处理流程。', multiline: true },
    { key: 'legal.support', label: '支持与投诉', hint: '说明支持时间、渠道和争议处理。', multiline: true }
  ] },
  { title: '版本与生效日期', fields: [
    { key: 'legal.terms_version', label: '服务条款版本', hint: '首次发布或正文更新时填写新的版本标识。' },
    { key: 'legal.privacy_version', label: '隐私政策版本', hint: '正文更新后提升版本，以便用户重新确认。' },
    { key: 'legal.effective_date', label: '生效日期', hint: '填写实际生效日期，例如 YYYY-MM-DD；不要倒填。' }
  ] },
  { title: '英文政策正文', fields: [
    { key: 'legal.terms_en', label: 'Terms of service', hint: '与中文条款保持一致，并按目标市场审阅。', multiline: true },
    { key: 'legal.privacy_en', label: 'Privacy policy', hint: '与中文政策保持一致，并按目标市场审阅。', multiline: true },
    { key: 'legal.refund_en', label: 'Refunds and cancellation', hint: '与中文退款政策保持一致。', multiline: true },
    { key: 'legal.ai_data_en', label: 'AI data processing', hint: '与中文 AI 数据说明保持一致。', multiline: true },
    { key: 'legal.rights_en', label: 'User rights', hint: '与中文权利说明保持一致。', multiline: true },
    { key: 'legal.support_en', label: 'Support and complaints', hint: '与中文支持说明保持一致。', multiline: true }
  ] },
  { title: '其他中文政策', fields: [
    { key: 'legal.cookies', label: 'Cookie 与本地存储', hint: '核对浏览器存储、统计供应商和撤回方式。', multiline: true },
    { key: 'legal.security', label: '安全说明', hint: '只写实际部署验证过的措施，不编造认证或 SLA。', multiline: true },
    { key: 'legal.subprocessors', label: '子处理方说明', hint: '补充实际法律名称、用途、地区、保留规则与转移保障。', multiline: true }
  ] },
  { title: '其他英文政策', fields: [
    { key: 'legal.cookies_en', label: 'Cookies and browser storage', hint: '与中文存储政策保持一致。', multiline: true },
    { key: 'legal.security_en', label: 'Security information', hint: '与中文安全说明保持一致。', multiline: true },
    { key: 'legal.subprocessors_en', label: 'Subprocessor information', hint: '与中文实际供应商清单保持一致。', multiline: true }
  ] }
]

export const allLegalSettingKeys = legalSettingGroups.flatMap(group => group.fields.map(field => field.key))
const additionalPolicyKeys = new Set(['legal.cookies', 'legal.cookies_en', 'legal.security', 'legal.security_en', 'legal.subprocessors', 'legal.subprocessors_en'])
export const requiredLegalSettingKeys = allLegalSettingKeys.filter(key => !additionalPolicyKeys.has(key))
