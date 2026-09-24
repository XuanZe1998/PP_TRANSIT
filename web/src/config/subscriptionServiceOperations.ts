export type SubscriptionFieldType = 'string' | 'int' | 'float' | 'array' | 'text'

export type SubscriptionField = {
  name: string
  label: string
  type?: SubscriptionFieldType
  required?: boolean
  placeholder?: string
}

export type SubscriptionOperation = {
  key: string
  group: string
  label: string
  path: string
  paged?: boolean
  mutation?: boolean
  fields: SubscriptionField[]
}

const f = (name: string, label: string, type: SubscriptionFieldType = 'string', required = false, placeholder = ''): SubscriptionField =>
  ({ name, label, type, required, placeholder })
const tradeNo = [f('trade_no', '订单号', 'string', true)]
const complaintNo = [f('complaint_no', '投诉单号', 'string', true)]
const goodsNo = [f('goods_no', '商品编号', 'string', true)]
const listFilters = [f('status', '状态', 'int'), f('keywords', '搜索关键词')]
const messageFields = [...complaintNo, f('content', '留言内容', 'text'), f('images', '图片 URL（每行一个）', 'array')]

export const subscriptionOperations: SubscriptionOperation[] = [
  { key: 'goods_list', group: '商品', label: '商品列表', path: '/openApi/goods/list', paged: true, fields: [f('keywords', '商品编号 / 名称'), f('source', '来源', 'string', false, 'local / supply')] },
  { key: 'goods_detail', group: '商品', label: '商品详情', path: '/openApi/goods/detail', fields: goodsNo },
  { key: 'goods_category_list', group: '商品分类', label: '商品分类查询', path: '/openApi/goodsCategory/list', paged: true, fields: [f('keywords', '分类编号 / 名称'), f('status', '状态', 'int', false, '1 启用 / 0 停用')] },
  { key: 'goods_category_create', group: '商品分类', label: '商品分类创建', path: '/openApi/goodsCategory/create', mutation: true, fields: [f('name', '分类名称', 'string', true), f('image', '封面图 URL'), f('sort', '排序', 'int')] },
  { key: 'goods_category_change_status', group: '商品分类', label: '商品分类状态更新', path: '/openApi/goodsCategory/changeStatus', mutation: true, fields: [f('goods_category_no', '分类编号', 'string', true), f('status', '状态', 'int', true, '1 启用 / 0 停用')] },
  { key: 'source_category_tree', group: '平台货源分类', label: '平台货源分类树', path: '/openApi/sourceCategory/tree', fields: [] },
  { key: 'goods_create', group: '商品操作', label: '创建或更新商品', path: '/openApi/goods/create', mutation: true, fields: [
    f('goods_no', '商品编号（留空新建）'), f('name', '商品标题（铺货商品留空）'), f('image', '封面图 URL（铺货商品留空）'),
    f('description', '商品说明', 'text'), f('delivery_method', '发货方式', 'int', false, '1 卡密 / 2 内容 / 3 人工'), f('delivery_order', '发卡顺序', 'int'),
    f('limit_quantity', '起购数量', 'int'), f('warning_stock_quantity', '库存预警数', 'int'), f('instruction', '卡密使用说明', 'text'),
    f('content', '内容发货内容', 'text'), f('manual_instruction', '人工发货说明', 'text'), f('price', '售价', 'float', true), f('cost_price', '成本价', 'float'),
    f('agent_status', '代理状态', 'int'), f('agent_price', '代理底价', 'float'), f('source_category_id', '货源分类 ID', 'int'),
    f('goods_category_no', '店内分类编号'), f('sort', '排序', 'int'), f('markup_rate', '铺货加价百分比', 'float')
  ] },
  { key: 'goods_change_status', group: '商品操作', label: '更新商品状态', path: '/openApi/goods/changeStatus', mutation: true, fields: [...goodsNo, f('status', '状态', 'int', true, '1 上架 / 0 下架')] },
  { key: 'goods_delete', group: '商品操作', label: '删除商品', path: '/openApi/goods/delete', mutation: true, fields: goodsNo },
  { key: 'goods_cover', group: '商品操作', label: '生成文字封面', path: '/openApi/goods/cover', mutation: true, fields: [f('text', '封面文字', 'string', true)] },
  { key: 'stock_batch_add', group: '卡密库存', label: '上传卡密', path: '/openApi/stockSecretBatch/add', mutation: true, fields: [...goodsNo, f('content', '卡密文本', 'text', true), f('first', '优先售出', 'int'), f('remove_repeat', '去重', 'int'), f('split_type', '分割方式', 'int'), f('expire_time', '过期 Unix 秒', 'int')] },
  { key: 'stock_list', group: '卡密库存', label: '卡密查询', path: '/openApi/stockSecret/list', paged: true, fields: [...goodsNo, ...listFilters, f('first', '是否优先销售', 'int')] },
  { key: 'stock_batch_list', group: '卡密库存', label: '批次查询', path: '/openApi/stockSecretBatch/list', paged: true, fields: [f('goods_no', '商品编号'), ...listFilters] },
  { key: 'stock_invalidate', group: '卡密库存', label: '作废卡密', path: '/openApi/stockSecret/invalidate', mutation: true, fields: [...goodsNo, f('ids', '卡密 ID（每行一个）', 'array', true), f('invalid_reason', '作废原因')] },
  { key: 'stock_cancel_invalidate', group: '卡密库存', label: '取消作废', path: '/openApi/stockSecret/cancelInvalidate', mutation: true, fields: [...goodsNo, f('ids', '卡密 ID（每行一个）', 'array', true)] },
  { key: 'stock_set_first', group: '卡密库存', label: '设置优先', path: '/openApi/stockSecret/setFirst', mutation: true, fields: [...goodsNo, f('ids', '卡密 ID（每行一个）', 'array', true), f('first', '优先状态', 'int', true)] },
  { key: 'stock_set_expire', group: '卡密库存', label: '设置过期', path: '/openApi/stockSecret/setExpire', mutation: true, fields: [...goodsNo, f('ids', '卡密 ID（每行一个）', 'array', true), f('expire_time', '过期 Unix 秒', 'int', true)] },
  { key: 'stock_invalidate_all', group: '卡密库存', label: '全部作废', path: '/openApi/stockSecret/invalidateAll', mutation: true, fields: [...goodsNo, f('invalid_reason', '作废原因')] },
  { key: 'purchase_quote', group: '采购', label: '询价', path: '/openApi/purchase/quotePrice', fields: [...goodsNo, f('quantity', '数量', 'int')] },
  { key: 'purchase_create', group: '采购', label: '下单', path: '/openApi/purchase/create', mutation: true, fields: [...goodsNo, f('quantity', '数量', 'int'), f('remark', '备注', 'text')] },
  { key: 'purchase_list', group: '采购', label: '采购记录', path: '/openApi/purchase/list', paged: true, fields: listFilters },
  { key: 'purchase_query', group: '采购', label: '查单', path: '/openApi/purchase/query', fields: tradeNo },
  { key: 'purchase_complaint_apply', group: '采购', label: '发起投诉', path: '/openApi/purchase/applyComplaint', mutation: true, fields: [...tradeNo, f('reason', '投诉原因', 'string', true), f('description', '补充说明', 'text'), f('images', '凭证图片 URL（每行一个）', 'array', true)] },
  { key: 'purchase_complaint_detail', group: '采购', label: '投诉详情', path: '/openApi/purchase/complaintDetail', fields: complaintNo },
  { key: 'purchase_complaint_messages', group: '采购', label: '投诉留言列表', path: '/openApi/purchase/complaintMessageList', fields: complaintNo },
  { key: 'purchase_complaint_send', group: '采购', label: '投诉留言', path: '/openApi/purchase/complaintSendMessage', mutation: true, fields: messageFields },
  { key: 'purchase_complaint_cancel', group: '采购', label: '撤销投诉', path: '/openApi/purchase/complaintCancel', mutation: true, fields: complaintNo },
  { key: 'purchase_complaint_platform', group: '采购', label: '申请平台介入', path: '/openApi/purchase/complaintApplyPlatform', mutation: true, fields: complaintNo },
  { key: 'order_list', group: '订单管理', label: '订单列表', path: '/openApi/order/list', paged: true, fields: [...listFilters, f('platform', '来源平台', 'string', false, 'shop / api / purchase'), f('card_no', '已售卡密'), f('start_time', '开始 Unix 秒', 'int'), f('end_time', '结束 Unix 秒', 'int')] },
  { key: 'order_info', group: '订单管理', label: '订单详情', path: '/openApi/order/info', fields: tradeNo },
  { key: 'order_deliver', group: '订单管理', label: '手动发货', path: '/openApi/order/deliver', mutation: true, fields: tradeNo },
  { key: 'order_refund', group: '订单管理', label: '退款', path: '/openApi/order/refund', mutation: true, fields: tradeNo },
  { key: 'order_statistics', group: '订单管理', label: '订单统计', path: '/openApi/order/statistics', fields: [] },
  { key: 'buyer_black_add', group: '订单管理', label: '订单买家拉黑', path: '/openApi/channelBuyerBlack/addFromOrder', mutation: true, fields: tradeNo },
  { key: 'buyer_black_delete', group: '订单管理', label: '订单买家解黑', path: '/openApi/channelBuyerBlack/delete', mutation: true, fields: tradeNo },
  { key: 'complaint_list', group: '订单投诉', label: '投诉列表', path: '/openApi/complaint/list', paged: true, fields: listFilters },
  { key: 'complaint_detail', group: '订单投诉', label: '投诉详情', path: '/openApi/complaint/detail', fields: complaintNo },
  { key: 'complaint_messages', group: '订单投诉', label: '投诉留言列表', path: '/openApi/complaint/messageList', fields: complaintNo },
  { key: 'complaint_send', group: '订单投诉', label: '投诉留言', path: '/openApi/complaint/sendMessage', mutation: true, fields: messageFields },
  { key: 'complaint_refund', group: '订单投诉', label: '同意退款', path: '/openApi/complaint/refund', mutation: true, fields: [...complaintNo, f('remark', '备注', 'text')] },
  { key: 'complaint_mark_done', group: '订单投诉', label: '标记完成', path: '/openApi/complaint/markDone', mutation: true, fields: [...complaintNo, f('reason', '完成说明', 'text', true), f('images', '凭证图片 URL（每行一个）', 'array')] },
  { key: 'merchant_balance', group: '账户', label: '钱包余额', path: '/openApi/merchant/balance', fields: [] },
  { key: 'upload_image', group: '通用功能', label: '上传图片', path: '/openApi/upload/image', mutation: true, fields: [f('image', '图片 Base64 / Data URL', 'text', true)] }
]
