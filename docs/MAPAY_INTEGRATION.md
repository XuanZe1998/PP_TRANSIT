# MaPay 支付接入

## 范围

钱包充值和服务商城共用支付意图，新订单的 `paymentProvider` 固定为 `MAPAY`。首版只允许 `alipay` 和 `wxpay`。MaPay 文档未提供退款 API，因此本项目没有退款按钮、退款任务或自动退款接口。

## 私有配置

```yaml
mapay:
  enabled: true
  base-url: https://mzf.mapay.cc
  merchant-id: "<MAPAY_PID>"
  merchant-key: "<MAPAY_KEY>"
  notify-url: "https://api.example.com/webhooks/mapay"
  return-url: "https://example.com/payment/result"
  site-name: Linknux
  allowed-methods: [alipay, wxpay]
  request-timeout-seconds: 15
```

对应环境变量为 `MAPAY_ENABLED`、`MAPAY_BASE_URL`、`MAPAY_PID`、`MAPAY_KEY`、`MAPAY_NOTIFY_URL`、`MAPAY_RETURN_URL`、`MAPAY_SITE_NAME`、`MAPAY_ALLOWED_METHODS` 和 `MAPAY_REQUEST_TIMEOUT_SECONDS`。真实 KEY 只能放在未追踪的 `config/application-local.yaml` 或部署密钥管理器中。

## 支付流程

1. 用户以 `Idempotency-Key` 创建充值订单或服务订单。
2. 客户端对 `POST /payment-intents/{id}/start` 发送新的 `Idempotency-Key`。
3. 服务端调用 `/xpay/epay/mapi.php`，将 `payurl` / `qrcode` / `urlscheme` 映射为 `REDIRECT` / `QRCODE` / `URL_SCHEME`。
4. MaPay 以 GET 或 POST 请求 `/webhooks/mapay`。服务端验证 MD5 签名、PID、订单号、两位 CNY 金额、支付方式、`param` 命名空间和 `TRADE_SUCCESS`。
5. 条件更新首次成功时才结算。重复回调返回纯文本 `success`；验证或结算失败返回 `fail`。
6. 定时任务通过 `/xpay/epay/api.php?act=order` 对账。前端轮询只读本地状态；支付返回页或用户主动刷新时才触发一次主动查单。

签名实现会剔除空值以及 `sign` / `sign_type`，按字段名 ASCII 排序，以 `a=b&...` 拼接商户密钥后计算小写 MD5。回调签名使用常量时间比较，重复字段直接拒绝。

## 订单与履约边界

- 金额、币种、汇率、优惠和赠送额度在创建订单时快照；MaPay 金额一律两位 CNY。
- 充值的本金和赠送额度分别使用唯一业务引用写入钱包流水，并在同一事务内更新用户余额和组织钱包镜像；重复通知不会重复入账。
- 服务订单只触发一次库存、卡密或 Dujiao-Next 采购。
- 已过期订单后续收到可验证付款仍记为已付。库存或优惠资源已释放时，履约状态为 `REVIEW_REQUIRED`，不会丢弃付款事实。
- 历史已付/已退款记录只读保留；历史提供方的未支付意图在一次性迁移中取消并释放资源。

## 人工联调

CI 不发起真实支付。部署时配置 PID、KEY 和公网 HTTPS 回调地址，使用 MaPay 允许的最小金额完成一次创建支付、异步回调和主动查单闭环，再核对钱包流水或服务交付只发生一次。

官方文档：[API 支付](https://mzf.mapay.cc/docs/epay_mapi.md)、[异步通知](https://mzf.mapay.cc/docs/epay_notify.md)、[MD5 签名](https://mzf.mapay.cc/docs/epay_md5.md)、[订单查询](https://mzf.mapay.cc/docs/epay/api/order_one.md)。
