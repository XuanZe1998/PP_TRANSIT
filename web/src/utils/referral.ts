export const referralLabels: Record<string, string> = {
  ACTIVE: '正常', SUSPENDED: '已暂停', AWAITING_PAYMENT: '待付款',
  AWAITING_DELIVERY: '待交付', FROZEN: '待到账', AVAILABLE: '已到账',
  REVERSED: '已扣回', VOID: '已失效', INELIGIBLE: '未参与',
  PENDING: '待审核', APPROVED: '待打款', PAYING: '打款中', PAID: '已打款',
  REJECTED: '已驳回', CANCELLED: '已取消', ALIPAY: '支付宝', WECHAT: '微信',
  REGISTER: '邮箱注册', OAUTH_REGISTER: '第三方账号注册'
}
export const referralLabel = (value: string) => referralLabels[value] || '未知状态'
export const referralMoney = (value: number = 0) => '¥' + (value / 100).toFixed(2)
export function referralOffer(rule: { mode: string; fixed_cents: number; rate_bps: number }) {
  return rule.mode === 'FIXED' ? '每份最高返 ' + referralMoney(rule.fixed_cents) : '最高返商品实付的 ' + (rule.rate_bps / 100).toFixed(2) + '%'
}
export function referralDescription(rule: { mode: string; fixed_cents: number; rate_bps: number; display_text?: string }) {
  return rule.display_text?.trim() || referralOffer(rule) + '，仅适用于已审核通过的代理，以订单确认金额为准。'
}
