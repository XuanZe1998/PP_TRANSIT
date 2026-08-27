# 好易智算与企业模型网关

## 启用

生产环境必须配置 MySQL 8、Redis、`JWT_SECRET`、`DATA_ENCRYPTION_KEY` 和数据库密码。好易智算默认关闭且模型默认不发布：

```text
HAOEE_ENABLED=true
HAOEE_API_KEY=<upstream-key>
HAOEE_BASE_URL=https://maas.haoee.com
HAOEE_ACTIVATE_MODELS=false
```

启动后会建立来源为 `haoee` 的渠道、加密凭证及经审核的版本化模型清单。管理员完成价格和协议核验后再启用映射；灰度阶段保持 `HAOEE_ACTIVATE_MODELS=false`，只给测试 API Key 授权相应模型。

## API

- `GET /v1/models`：只返回调用 Key 已授权且存在健康路由的模型，并带来源、厂家、能力、输入输出模态、协议和计价单位。
- `POST /v1/chat/completions`、`POST /v1/responses`
- `POST /v1/embeddings`、`POST /v1/reranks`
- `POST /v1/images/generations`
- `POST /v1/audio/transcriptions`、`POST /v1/audio/speech`
- `POST /v1/tasks`、`GET /v1/tasks/{id}`：视频、音乐和其他异步协议。

适配器对好易智算固定发送 `Authorization` 与 `ModelName`，Embedding 默认使用 `/compatible-mode/v1/embeddings`，Rerank 默认使用 `/compatible-api/v1/reranks`。异步任务创建必须发送 8–160 字符的 `Idempotency-Key`。

## 企业账户与计量

用户升级后自动拥有个人组织及钱包。公司 Owner 可在 `/console/organization` 邀请成员、划拨或收回未消费额度，并按成员、Key、来源和模型查看输入、输出、缓存命中、缓存写入、缓存未命中、请求、成功率、费用及毛利。缓存未命中定义为 `max(input - cacheRead, 0)`。

钱包先预占最大费用，再按最终 usage 结算差额。同步调用失败会释放预占；上游接受状态不明确的超时以及中断流式调用会保留为 `UNKNOWN`，等待查询或人工对账，防止重复生成和重复扣费。

## 运维

Redis 故障时实例内限流继续工作，数据库仍是钱包、账本和任务事实来源。Prometheus 只使用来源、模型、协议、结果和错误类型等低基数标签。Flyway 记录版本化迁移；滚动升级期间保留兼容修复器完成 expand-contract 回填，稳定后应按后续迁移逐步移除旧修复逻辑。
