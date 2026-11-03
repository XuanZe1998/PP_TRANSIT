const orderStatuses: Record<string, string> = {
  PENDING: '待付款',
  CONFIRMED: '已确认',
  PAID: '已付款',
  FULFILLED: '已履约',
  FAILED: '失败',
  CANCELLED: '已取消',
  EXPIRED: '已过期',
  REFUND_PENDING: '退款处理中',
  REFUNDED: '已退款'
}

const fulfillmentStatuses: Record<string, string> = {
  PENDING: '待交付',
  RESERVED: '已预留',
  RELEASED: '已释放预留',
  SETTLING: '交付处理中',
  COMPLETED: '已交付',
  FAILED: '交付失败',
  REVIEW_REQUIRED: '待人工处理',
  PROCUREMENT_PENDING: '待采购',
  PROCUREMENT_ACCEPTED: '已受理采购',
  PROCUREMENT_RETRY: '待重试采购',
  PROCUREMENT_POLL_RETRY: '待重新查询采购结果'
}

const supplierStatuses: Record<string, string> = {
  PENDING: '待处理',
  PENDING_PAYMENT: '待付款',
  PAID: '已付款',
  PROCESSING: '处理中',
  PARTIALLY_DELIVERED: '部分交付',
  DELIVERED: '已交付',
  COMPLETED: '已完成',
  CANCELED: '已取消',
  CANCELLED: '已取消',
  FAILED: '失败',
  REFUNDED: '已退款'
}

function label(statuses: Record<string, string>, status?: string | null) {
  const code = status?.trim().toUpperCase()
  if (!code) return '暂无状态'
  return statuses[code] || '未知状态'
}

export const orderStatusLabel = (status?: string | null) => label(orderStatuses, status)
export const fulfillmentStatusLabel = (status?: string | null) => label(fulfillmentStatuses, status)
export const supplierStatusLabel = (status?: string | null) => label(supplierStatuses, status)
