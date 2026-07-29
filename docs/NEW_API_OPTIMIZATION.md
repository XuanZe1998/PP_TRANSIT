# 参考 new-api 的深度优化说明

本文记录当前项目对 `QuantumNous/new-api` 核心设计的映射，以及因技术栈、结算安全与项目规模而采用的实现边界。

## 能力映射

| new-api 思路 | 当前项目实现 |
| --- | --- |
| 渠道优先级与权重 | 模型映射提供优先级；同优先级使用渠道权重与流量比例加权随机 |
| 失败重试 | 401/408/409/429/5xx 与网络错误切换下一条候选路由 |
| 自动禁用渠道 | 连续失败阈值触发临时 `COOLDOWN`，到期允许恢复探测 |
| 渠道测试 | 固定、不可注入代码的认证探针，记录响应时间、Token、成本和错误 |
| 上游模型同步 | OpenAI-compatible、Anthropic、Gemini 目录发现；只新增且默认停用 |
| 模型映射 | 公开模型、上游模型、渠道、优先级、灰度、能力标签和独立价格 |
| 用量与成本 | 请求预留、实际结算、缓存 Token、售价、成本、毛利和 Trace ID |
| 可观测性 | 渠道成功/失败/熔断计数、延迟 Timer、健康字段和管理端账单 |
| OpenAI 兼容 | Models、Chat Completions、非流式与安全结算后的 SSE chunk |

## 路由状态机

1. 只选择已启用、已测试且凭据存在的渠道。
2. `HEALTHY` 与阈值以下的 `DEGRADED` 渠道可路由。
3. 连续失败达到阈值后进入 `COOLDOWN`，冷却期内不参与路由。
4. 冷却到期后允许请求作为恢复探针；成功清零连续失败并回到 `HEALTHY`。
5. `DISABLED` 与 `UNTESTED` 不参与路由，必须由管理员处理。

每个公开模型的候选映射先按优先级从高到低分层；同层按以下有效权重无放回排列：

```text
effective_weight = max(1, channel.weight) × max(1, mapping.traffic_percent)
```

首条路由失败且错误可重试时，按已生成的候选顺序切换。客户端 400 类输入错误不会污染渠道健康数据。

## 模型同步安全策略

- 目录请求使用服务端解密后的渠道凭据，凭据不会回传前端。
- Base URL 必须通过 SSRF 策略校验。
- 目录最多接收 500 个合法模型 ID，异常或不合法名称会被忽略。
- 同步不会删除模型、覆盖别名、优先级或任何价格字段。
- 新模型带有 `discovered,pricing-required` 标签，默认 `enabled=false`、`billingEnabled=false`。
- 管理员可显式选择立即启用，但这代表接受暂未计费的风险；商业环境应先配置定价。

## 当前边界

- 已完成的统一入口是 OpenAI Chat Completions；图片、音频、Embedding、Rerank、Realtime 和供应商原生入口仍需按业务优先级分阶段实现。
- SSE 目前是结算后的规范化分块，不是上游 token 级直通流。真正直通需要流式用量归集、客户端断连结算、半开路由和跨协议 chunk 转换共同落地。
- 熔断状态保存在主数据库，适合多实例共享；RPM/TPM 限流当前仍为进程内状态，多实例严格限流需要 Redis。
- `SchemaRepairService` 保持兼容现有部署。长期建议迁移到 Flyway/Liquibase 的版本化、可审计迁移。

## 后续优先级

1. Redis 分布式限流、幂等结算与渠道状态缓存。
2. 上游 token 级 SSE 透传和客户端断连结算。
3. Responses、Embedding、图片、音频、Rerank 与 Claude/Gemini 原生入口。
4. 批量渠道测试、定时健康任务、半开并发限制与告警通知。
5. Flyway 数据迁移、OpenAPI 文档和端到端容器化验收。
