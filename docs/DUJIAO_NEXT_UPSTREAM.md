# Dujiao-Next 成品服务上游采购

## 资金与订单流

```text
顾客 -> 本站 MaPay -> 本站确认收款
                         -> 异步调用 Dujiao-Next OpenAPI
                         -> 扣除本站上游钱包余额
                         -> 回调/轮询取回卡密或交付内容
```

顾客付款和上游采购是两笔独立资金流。本站不会把顾客的支付凭证或 MaPay 商户密钥发给上游。

## 私有配置

```yaml
dujiao-next:
  enabled: true
  allow-purchases: false
  base-url: https://www.cccrad.uk
  api-key: "<API_KEY>"
  api-secret: "<API_SECRET>"
  callback-url: https://api.example.com/api/v1/upstream/callback
```

先保持 `allow-purchases: false`，完成连接测试、商品映射和一笔受控的端到端订单后再开启。`API Secret` 只能保存在后端私有配置或密钥管理器中。

## 可靠性边界

- 本地 `order_no` 作为 `downstream_order_no`，防止重复创建上游订单。
- 创建失败按退避策略重试；上游受理后同时使用回调和定时查单。
- 交付内容使用本站 `security.data-encryption-key` 加密存储。
- 上游余额不足、库存不足或超过重试上限时，本地订单保留“已付款”事实并进入 `REVIEW_REQUIRED`，由管理员重试或补充交付。
- 本项目没有自动退款能力，不会以采购失败覆盖已验证的支付事实。

## 相关接口

- `POST /admin/api/dujiao-next/ping`：管理员连接测试。
- `GET /admin/api/dujiao-next/products`：管理员拉取上游商品和 SKU。
- `POST /api/v1/upstream/callback`：上游回调，使用 Dujiao-Next HMAC-SHA256 验签。
