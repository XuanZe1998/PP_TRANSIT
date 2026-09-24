package com.transit.service;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public enum SubscriptionServiceOperation {
    GOODS_LIST("goods_list", "/openApi/goods/list", "商品", "商品列表", true, false),
    GOODS_DETAIL("goods_detail", "/openApi/goods/detail", "商品", "商品详情", false, false),
    GOODS_CATEGORY_LIST("goods_category_list", "/openApi/goodsCategory/list", "商品分类", "商品分类查询", true, false),
    GOODS_CATEGORY_CREATE("goods_category_create", "/openApi/goodsCategory/create", "商品分类", "商品分类创建", false, true),
    GOODS_CATEGORY_CHANGE_STATUS("goods_category_change_status", "/openApi/goodsCategory/changeStatus", "商品分类", "商品分类状态更新", false, true),
    SOURCE_CATEGORY_TREE("source_category_tree", "/openApi/sourceCategory/tree", "平台货源分类", "平台货源分类树", false, false),
    GOODS_CREATE("goods_create", "/openApi/goods/create", "商品操作", "创建或更新商品", false, true),
    GOODS_CHANGE_STATUS("goods_change_status", "/openApi/goods/changeStatus", "商品操作", "更新商品状态", false, true),
    GOODS_DELETE("goods_delete", "/openApi/goods/delete", "商品操作", "删除商品", false, true),
    GOODS_COVER("goods_cover", "/openApi/goods/cover", "商品操作", "生成文字封面", false, true),
    STOCK_BATCH_ADD("stock_batch_add", "/openApi/stockSecretBatch/add", "卡密库存", "上传卡密", false, true),
    STOCK_LIST("stock_list", "/openApi/stockSecret/list", "卡密库存", "卡密查询", true, false),
    STOCK_BATCH_LIST("stock_batch_list", "/openApi/stockSecretBatch/list", "卡密库存", "批次查询", true, false),
    STOCK_INVALIDATE("stock_invalidate", "/openApi/stockSecret/invalidate", "卡密库存", "作废卡密", false, true),
    STOCK_CANCEL_INVALIDATE("stock_cancel_invalidate", "/openApi/stockSecret/cancelInvalidate", "卡密库存", "取消作废", false, true),
    STOCK_SET_FIRST("stock_set_first", "/openApi/stockSecret/setFirst", "卡密库存", "设置优先", false, true),
    STOCK_SET_EXPIRE("stock_set_expire", "/openApi/stockSecret/setExpire", "卡密库存", "设置过期", false, true),
    STOCK_INVALIDATE_ALL("stock_invalidate_all", "/openApi/stockSecret/invalidateAll", "卡密库存", "全部作废", false, true),
    PURCHASE_QUOTE("purchase_quote", "/openApi/purchase/quotePrice", "采购", "询价", false, false),
    PURCHASE_CREATE("purchase_create", "/openApi/purchase/create", "采购", "下单", false, true),
    PURCHASE_LIST("purchase_list", "/openApi/purchase/list", "采购", "采购记录", true, false),
    PURCHASE_QUERY("purchase_query", "/openApi/purchase/query", "采购", "查单", false, false),
    PURCHASE_COMPLAINT_APPLY("purchase_complaint_apply", "/openApi/purchase/applyComplaint", "采购", "发起投诉", false, true),
    PURCHASE_COMPLAINT_DETAIL("purchase_complaint_detail", "/openApi/purchase/complaintDetail", "采购", "投诉详情", false, false),
    PURCHASE_COMPLAINT_MESSAGES("purchase_complaint_messages", "/openApi/purchase/complaintMessageList", "采购", "投诉留言列表", false, false),
    PURCHASE_COMPLAINT_SEND("purchase_complaint_send", "/openApi/purchase/complaintSendMessage", "采购", "投诉留言", false, true),
    PURCHASE_COMPLAINT_CANCEL("purchase_complaint_cancel", "/openApi/purchase/complaintCancel", "采购", "撤销投诉", false, true),
    PURCHASE_COMPLAINT_PLATFORM("purchase_complaint_platform", "/openApi/purchase/complaintApplyPlatform", "采购", "申请平台介入", false, true),
    ORDER_LIST("order_list", "/openApi/order/list", "订单管理", "订单列表", true, false),
    ORDER_INFO("order_info", "/openApi/order/info", "订单管理", "订单详情", false, false),
    ORDER_DELIVER("order_deliver", "/openApi/order/deliver", "订单管理", "手动发货", false, true),
    ORDER_REFUND("order_refund", "/openApi/order/refund", "订单管理", "退款", false, true),
    ORDER_STATISTICS("order_statistics", "/openApi/order/statistics", "订单管理", "订单统计", false, false),
    BUYER_BLACK_ADD("buyer_black_add", "/openApi/channelBuyerBlack/addFromOrder", "订单管理", "订单买家拉黑", false, true),
    BUYER_BLACK_DELETE("buyer_black_delete", "/openApi/channelBuyerBlack/delete", "订单管理", "订单买家解黑", false, true),
    COMPLAINT_LIST("complaint_list", "/openApi/complaint/list", "订单投诉", "投诉列表", true, false),
    COMPLAINT_DETAIL("complaint_detail", "/openApi/complaint/detail", "订单投诉", "投诉详情", false, false),
    COMPLAINT_MESSAGES("complaint_messages", "/openApi/complaint/messageList", "订单投诉", "投诉留言列表", false, false),
    COMPLAINT_SEND("complaint_send", "/openApi/complaint/sendMessage", "订单投诉", "投诉留言", false, true),
    COMPLAINT_REFUND("complaint_refund", "/openApi/complaint/refund", "订单投诉", "同意退款", false, true),
    COMPLAINT_MARK_DONE("complaint_mark_done", "/openApi/complaint/markDone", "订单投诉", "标记完成", false, true),
    MERCHANT_BALANCE("merchant_balance", "/openApi/merchant/balance", "账户", "钱包余额", false, false),
    UPLOAD_IMAGE("upload_image", "/openApi/upload/image", "通用功能", "上传图片", false, true);

    private static final Map<String, SubscriptionServiceOperation> BY_KEY = new LinkedHashMap<>();

    static {
        Arrays.stream(values()).forEach(value -> BY_KEY.put(value.key, value));
    }

    private final String key;
    private final String path;
    private final String group;
    private final String label;
    private final boolean paged;
    private final boolean mutation;

    SubscriptionServiceOperation(String key, String path, String group, String label,
                                 boolean paged, boolean mutation) {
        this.key = key;
        this.path = path;
        this.group = group;
        this.label = label;
        this.paged = paged;
        this.mutation = mutation;
    }

    public String key() { return key; }
    public String path() { return path; }
    public String group() { return group; }
    public String label() { return label; }
    public boolean paged() { return paged; }
    public boolean mutation() { return mutation; }

    public static Optional<SubscriptionServiceOperation> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
