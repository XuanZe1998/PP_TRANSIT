# MaPay 支付接入

## 范围

钱包充值和服务商城共用支付意图，新订单的 `paymentProvider` 固定为 `MAPAY`。首版只允许 `alipay` 和 `wxpay`。MaPay 文档未提供退款 API，因此本项目没有退款按钮、退款任务或自动退款接口。

## 私有配置

```yaml
mapay:
  enabled: true
  base-url: https://mzf.mapay.cn
  merchant-id: "<MAPAY_PID>"
  merchant-key: "<MAPAY_KEY>"
  notify-url: "https://api.example.com/webhooks/mapay"
  return-url: "https://example.com/payment/result"
  site-name: Linknux
  allowed-methods: [alipay, wxpay]
  request-timeout-seconds: 15
```

对应环境变量为 `MAPAY_ENABLED`、`MAPAY_BASE_URL`、`MAPAY_PID`、`MAPAY_KEY`、`MAPAY_NOTIFY_URL`、`MAPAY_RETURN_URL`、`MAPAY_SITE_NAME`、`MAPAY_ALLOWED_METHODS` 和 `MAPAY_REQUEST_TIMEOUT_SECONDS`。真实 KEY 只能放在未追踪的 `config/application-local.yaml` 或部署密钥管理器中。

生产服务实际读取 `/etc/api-transit/application-local.yaml`；构建产物和 GitHub 自动发布不会同步本机被忽略的私有配置。启用收款前须单独核对该文件中的 MaPay 配置以及进程环境变量，不能仅检查业务收款开关。2026-11-02 核实旧 `mzf.mapay.cc` 会将接口请求重定向到新站首页并丢失路径，当前网关使用 `https://mzf.mapay.cn`，不依赖跨域重定向传递商户凭据。

如果商户使用指定渠道，在私有配置的 `mapay.channel-ids` 中按付款方式设置正整数渠道 ID，例如 `alipay: 123`、`wxpay: 456`。ID 必须来自该商户的 `/xpay/user-channels`，不能使用文档示例或其他商户的渠道。客户端会将对应的 `channel_id` 一并签名；未配置的方式继续使用 MaPay 自动选择。套餐有效、商户启用不代表收款账号可用，应分别验证渠道及实际创建付款入口。

## 支付流程

1. 用户以 `Idempotency-Key` 创建充值订单或服务订单。
2. 客户端展示单笔订单金额、支付币种、付款方式和当前交易披露，用户主动勾选确认后，对 `POST /payment-intents/{id}/start` 或 `POST /service-orders/{id}/payment` 同时发送新的 `Idempotency-Key` 与 `X-Checkout-Disclosure`（来自 `/public/legal` 的 `checkout_disclosure_id`）。未开放结账、披露加载失败或缺少版本时，不允许发起付款；每次重新打开确认弹窗重新读取披露，清除旧勾选。
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

## 仅本地模拟结账

在仅连接本机测试数据库的开发进程中同时设置：

```properties
server.address=127.0.0.1
payment.local-test-mode=true
payment.local-checkout-bypass=true
mapay.enabled=false
dujiao-next.allow-purchases=false
vmcard.enabled=false
```

`local-checkout-bypass` 默认关闭，且只有模拟支付开启、真实支付禁用、服务显式绑定回环地址时才生效。`/public/legal` 会返回 `local_checkout_test_mode=true` 和 `checkout_ready=true`；真实收款开关、审核状态及缺失资料保持原值，不修改 `system_settings`，也不伪造法律同意记录。

本地页面明确显示测试提示，省略协议勾选；确认支付后直接模拟成功，不展示真实付款二维码（包括已有付款链接的待支付订单）。登录、订单归属、库存及幂等校验仍然保留。模拟支付仍会改变本地订单、钱包和库存数据，不要连接生产数据库或开启供应商真实采购。停止使用上述启动参数即恢复正常结账限制。

## 生产就绪检查

- `mapay.enabled=true` 且商户 PID、KEY、通知地址齐全，不能用本地测试支付模式代替真实通道。
- `/public/legal` 的 `payments_enabled`、`checkout_ready` 均为 `true`；公开信息须由运营方完善并审核，不得为通过支付请求自动批准条款或开启收款。
- 2026-10-17 排查发现：服务订单前端漏传 `X-Checkout-Disclosure`，被必需请求头校验拦截为 HTTP 400 / `Invalid request`，尚未调用支付网关。生产环境同时未启用支付通道、未开放结账。另核实生产跨域预检未允许该请求头，因此前后端需一起修复 CORS 白名单（保持限定来源，不使用通配符）。补齐请求契约不等于已启用生产收款；发布和私有配置变更需单独确认。

## 人工联调

### 运营方显式关闭公开资料结账门槛

线上付款由 `commerce.payments_enabled` 单独控制，旧 `commerce.publication_review_required` 不再影响结账。公开资料审核状态独立保留，不自动批准或伪造运营资料。当前协议确认、披露版本、支付通道配置、订单鉴权、金额和回调签名校验保持有效。

CI 不发起真实支付。部署时配置 PID、KEY 和公网 HTTPS 回调地址，使用 MaPay 允许的最小金额完成一次创建支付、异步回调和主动查单闭环，再核对钱包流水或服务交付只发生一次。

固定原价场景还须验证两笔不同商户订单号、相同金额的未支付请求。部分云端通道会为同额待支付订单递增金额，即使商户全局随机范围为 0～0，仍不能据此认定通道支持固定原价。应比较 `PaymentIntentService.start` 全流程返回值，并核对网关创建与查单金额均等于本地 `settlement_amount_cents`；仅成功生成二维码不能视为支付启动验证通过。若通道改价，需在供应商侧关闭递增或更换支持固定金额的通道，保留金额、签名与结算校验，不得将本地订单改成网关浮动后的价格。已有网关流水中的金额不会随配置自动恢复，切换设置后须用新的未支付订单验证。

官方文档：[API 支付](https://mzf.mapay.cn/docs/epay_mapi.md)、[异步通知](https://mzf.mapay.cn/docs/epay_notify.md)、[MD5 签名](https://mzf.mapay.cn/docs/epay_md5.md)、[订单查询](https://mzf.mapay.cn/docs/epay/api/order_one.md)。
