# Dujiao-Next 与安易付 V2 融合方案

## 结论

采用“共享安易付商户，订单域和回调完全隔离”的直连方案：

```text
本项目 成品服务订单 -> 安易付 V2 -> /webhooks/anyipay -> service_orders
Dujiao-Next 订单  -> 安易付 V2 -> Dujiao-Next 回调 -> 独角数卡订单/库存/发货
```

不让 Dujiao-Next 经过本项目二次转发支付请求。这样可以减少一次网络跳转、一层签名转换和一套
中间订单映射，同时保留 Dujiao-Next 自带的库存扣减、卡密发货和幂等回调处理。

Dujiao-Next 当前的 Epay provider 同时支持 V1/MD5 和 V2/RSA，V2 使用的路径与安易付文档一致：
`/api/pay/create` 和 `/api/pay/submit`。参考上游实现：
<https://github.com/dujiao-next/dujiao-next/blob/main/internal/payment/epay/epay.go>

## Dujiao-Next 配置

参考模板：[`config/dujiao-next-anyipay-v2.example.json`](../config/dujiao-next-anyipay-v2.example.json)。

在 Dujiao-Next 后台新建 Epay 支付通道，并填入：

- provider: `epay`
- Epay version: `v2`
- gateway URL: `https://pay.52td.cn`
- API path: `/api/pay/create`
- sign type: `RSA`
- method: `web`
- merchant ID: 安易付商户号
- private key: 商户 PKCS#8 RSA 私钥
- platform public key: 安易付平台 X.509 RSA 公钥
- notify URL: Dujiao-Next 后台生成/展示的 Epay 回调地址
- return URL: Dujiao-Next 的支付返回地址

`notify_url` 绝对不能填成本项目的 `/webhooks/anyipay`。本项目的回调只允许 `param=payment-intent:<id>`
的 成品服务订单，Dujiao-Next 回调会被拒绝。

支付宝、微信和 QQ 支付建议分别建立通道，对应 `channel_type` 为 `alipay`、`wxpay`、`qqpay`。

## 本项目配置

本项目继续使用：

```yaml
anyipay:
  enabled: true
  base-url: https://pay.52td.cn
  merchant-id: "<SAME_MERCHANT_ID>"
  merchant-private-key: "<SAME_MERCHANT_PRIVATE_KEY>"
  platform-public-key: "<SAME_PLATFORM_PUBLIC_KEY>"
  notify-url: https://modelhub.example.com/webhooks/anyipay
  return-url: https://modelhub.example.com/services
```

两套系统可以使用同一安易付商户凭据，但必须保持：

1. 成品服务订单号使用 `PLUS...` 前缀；
2. Dujiao-Next 订单号不得使用 `PLUS` 前缀；
3. 每笔订单将回调指向订单所属系统；
4. 两台服务器都使用 NTP 保持时钟准确，避免 300 秒验签窗口失效。

## 退款和对账边界

- 成品服务订单的查单、退款、关单由本项目管理端执行。
- Dujiao-Next 订单优先在 Dujiao-Next 中执行退款/关单；如果改为在安易付后台手工退款，必须同步更新
  Dujiao-Next 订单状态。
- 对账时按商户订单号前缀分账，`PLUS...` 归本项目，其余归 Dujiao-Next。
- 不将一笔 Dujiao-Next 订单再复制成 `service_orders`；否则会产生双订单、重复回调和重复发货风险。

## 上线顺序

1. 在 Dujiao-Next 后台创建但暂不启用 Epay V2 通道。
2. 检查回调 URL 为 HTTPS，且公网可访问。
3. 配置 RSA 密钥，使用 Dujiao-Next 自带的通道测试功能验证签名。
4. 先只启用一种支付方式，在获得授权的测试环境或最低金额下完成一笔端到端验证。
5. 确认安易付订单、Dujiao-Next 订单、库存扣减和一次性发货结果一致后，再开启其他支付方式。

## 旧版独角数卡

旧版 `assimon/dujiaoka` 的 `YipayController` 使用 V1/MD5，不能直接填入 V2 RSA 私钥。上游项目已停止维护，
新部署不建议选择旧版。如必须保留旧版，应使用安易付 V1 商户密钥和 V1 接口，不与本项目的 V2 RSA 配置混用。

旧版实现参考：
<https://github.com/assimon/dujiaoka/blob/master/app/Http/Controllers/Pay/YipayController.php>
