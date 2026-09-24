# 16688 订阅服务接入

本模块对接 `https://www.16688.com.cn/openApi`，接口定义以 [Open API 对接文档](https://www.16688.com.cn/api/purchase/docs/) 为准。前端不持有 `app_id` 或 `secret`，公共参数、时间戳和 MD5 签名全部由后端生成。

## 模块入口

- 前台 `/subscriptions`：分页商品目录、商品详情和登录后实时询价。
- 管理后台 `/admin/subscription-services`：商品、分类、平台货源分类、卡密库存、采购、订单、投诉、账户和图片上传的全部 43 个操作。
- 旧 `/pricing` 地址只保留到 `/subscriptions` 的兼容跳转，原“套餐价格”页面和导航已删除。

## 配置

生产环境使用 Secret/KMS/Vault 注入以下环境变量：

```text
SUBSCRIPTION_SERVICE_ENABLED=true
SUBSCRIPTION_SERVICE_ALLOW_MUTATIONS=false
SUBSCRIPTION_SERVICE_GATEWAY=https://www.16688.com.cn/openApi
SUBSCRIPTION_SERVICE_APP_ID=...
SUBSCRIPTION_SERVICE_SECRET=...
```

本地可使用已被 Git 忽略的 `config/subscription-service.local.yaml`。连接和余额核验完成前保持 `allow-mutations: false`。

## 安全与分页

- 后端只允许 `SubscriptionServiceOperation` 中的固定路径，浏览器不能传入任意上游 URL。
- 普通用户只能查看目录、详情和询价；会修改上游数据或消耗上游钱包的操作仅管理员可用。
- 写操作必须携带 `Idempotency-Key`，完成后只记录操作类型，不记录卡密、图片 Base64 或其他请求内容。
- 所有上游分页列表默认 10 条，仅接受 10/20/50/100，且要求上游返回可信 `total`。未分页投诉留言超过 200 条时拒绝将结果标记为完整集合。
