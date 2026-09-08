# Dujiao-Next 成品服务上游采购

## 资金与订单流

```text
顾客 -> 本站支付商户 -> 本站确认收款
                              -> 异步调用 cccrad.uk Dujiao-Next OpenAPI
                              -> 扣除本站在 cccrad.uk 的钱包余额
                              -> 回调/轮询取回卡密或交付内容
```

顾客付款和上游采购是两笔独立资金流。本站不会把顾客的支付凭证或支付商户密钥发给上游。

## 私有配置

将下列配置写入未纳入 Git 的 `config/application-local.yaml` 或生产环境变量：

```yaml
dujiao-next:
  enabled: true
  # 先保持 false，连接测试和商品映射完成后再打开。
  allow-purchases: false
  base-url: https://www.cccrad.uk
  api-key: "<API_KEY>"
  api-secret: "<API_SECRET>"
  callback-url: https://api.linknux.com/api/v1/upstream/callback
```

`API Secret` 只能保存在后端私有配置/密钥管理器中，禁止放入前端环境变量、数据库明文字段或 Git。

## 管理员配置流程

1. 重启后端，进入“服务与订单 -> 新增/编辑服务”。
2. 交付来源选择“`cccrad.uk` 自动采购”。
3. 点击“测试连接”，确认返回站点名、余额和币种。
4. 点击“从上游刷新”，选择商品/SKU，再设置本站售价。
5. 先用测试商品完成一笔端到端订单。
6. 确认收款、上游扣款、交付内容一致后，把 `allow-purchases` 改为 `true`。

## 可靠性与退款边界

- 本地 `order_no` 作为 `downstream_order_no`，上游重复请求不会重复创建订单。
- 创建失败按指数退避重试；上游受理后同时使用回调和定时查单。
- 已交付内容使用本站 `security.data-encryption-key` 加密存储。
- 上游余额不足、库存不足或业务失败时，订单保留为已付款+采购失败；临时错误会在重试上限内自动重试，最终失败后由管理员检查并退款。
- 本地退款前会先取消或隔离上游采购；上游不允许取消时，本地退款也会被拒绝。

## 相关接口

- `POST /admin/api/dujiao-next/ping`：管理员连接测试。
- `GET /admin/api/dujiao-next/products`：管理员拉取上游商品和 SKU。
- `POST /api/v1/upstream/callback`：上游回调，使用 Dujiao-Next HMAC-SHA256 验签。

官方协议文档：<https://dujiao-next.com/api/integration-open-api>
