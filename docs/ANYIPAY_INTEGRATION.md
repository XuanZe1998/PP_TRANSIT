# 安易付 V2 接入说明

> Dujiao-Next 共用安易付商户的部署方案见
> [`DUJIAO_NEXT_ANYIPAY.md`](./DUJIAO_NEXT_ANYIPAY.md)。

本项目已按 `https://pay.52td.cn/doc/index.html` 的 V2 协议接入安易付。请求使用
`application/x-www-form-urlencoded`，成功响应和回调均使用平台公钥进行
`SHA256withRSA` 验签。

## 启用配置

在外部配置文件或密钥管理服务中配置：

```yaml
anyipay:
  enabled: true
  allow-money-mutations: false
  base-url: https://pay.52td.cn
  merchant-id: "1001"
  merchant-private-key: "<商户 PKCS#8 私钥 Base64>"
  platform-public-key: "<平台 X.509 公钥 Base64>"
  notify-url: https://your-domain.example/webhooks/anyipay
  return-url: https://your-domain.example/services
  default-payment-type: alipay
  # 订单创建时锁定；示例值来自 2026-07-20 ECB 参考交叉汇率。
  usd-cny-rate: 6.76693506
```

密钥可以填纯 Base64，也可以填带 PEM 头尾的内容。不要将真实密钥提交到 Git。

安易付 `money` 字段的单位是人民币元。CNY 服务按原金额付款；USD 服务按
`usd-cny-rate` 换算，并在创建订单时保存人民币支付金额和汇率快照。发起付款、
主动查询和异步回调都使用该快照验单，不会因稍后修改配置汇率而改变已有订单。

## 项目接口

用户付款流程：

- `POST /service-orders`：只创建 `PENDING` 待支付订单并锁定人民币支付金额。
- `POST /service-orders/{id}/payment`：用户点击支付后创建或获取支付链接。
- `POST /service-orders/{id}/payment/query`：主动查询付款结果并同步本地订单状态。
- `GET /webhooks/anyipay`：安易付异步通知地址；成功处理后返回 `success`。

管理员接口：

- `GET /admin/payment/anyipay/merchant`：商户信息。
- `GET /admin/payment/anyipay/orders?offset=0&limit=50&status=1`：商户订单列表。
- `POST /admin/payment/anyipay/refund`：订单退款。
- `POST /admin/payment/anyipay/refund/query`：退款查询。
- `POST /admin/payment/anyipay/close`：关闭订单。
- `POST /admin/payment/anyipay/transfer`：发起代付。
- `POST /admin/payment/anyipay/transfer/query`：代付查询。
- `GET /admin/payment/anyipay/transfer/balance`：代付可用余额。

退款、关单和代付等资金操作均需要管理员身份，并且还需要将
`allow-money-mutations` 显式设为 `true`，再在安易付商户后台开启对应 API 开关。

## 安全校验

成功付款回调只有同时通过以下检查才会把本地订单更新为 `PAID`：

1. RSA 签名正确；
2. 回调时间戳与服务器时间相差不超过 300 秒；
3. `pid` 与本地商户号一致；
4. `out_trade_no` 对应本地订单；
5. 回调 `money` 与订单保存的人民币支付金额完全一致；
6. `trade_status` 为 `TRADE_SUCCESS`。
