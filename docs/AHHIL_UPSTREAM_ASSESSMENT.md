# AHHil 上游接入探查

探查日期：2026-09-05。目标站点：https://ahhilai.top/ 。

## 结论

协议层面可以作为本项目的 OpenAI-compatible 渠道接入，无需新增基础聊天适配器。已成功登录并读取账号信息、账号模型目录和可选分组；尚未使用推理 API Key 完成真实模型请求，因此此结论不代表模型可用性、身份、流式行为或计费已通过验证。

本站为 New API 系统。公开 `/api/status` 声明 Base URL 为 `https://ahhilai.top`；`/api/pricing` 声明以下接口：

| 类型 | 接口 |
| --- | --- |
| Chat Completions | POST /v1/chat/completions |
| Anthropic Messages | POST /v1/messages |
| Responses | POST /v1/responses |

## 模型目录

公开价格目录有 28 个模型；登录账号的 `/api/user/models` 返回其中 26 个。下表均为站点声明的模型 ID，不代表已鉴别其底层模型。

| 系列 | 账号目录中的模型 ID |
| --- | --- |
| GPT（7） | gpt-6-astra、gpt-5.6-sol、gpt-5.6-terra、gpt-5.6-luna、gpt-5.5、gpt-5.4、gpt-5.4-mini |
| Claude（12） | claude-fable-5-1、claude-fable-5、claude-opus-5、claude-opus-5-thinking、claude-opus-4-8、claude-opus-4-8-high、claude-opus-4-8-medium、claude-opus-4-8-xhigh、claude-opus-4-8-max、claude-opus-4-7、claude-sonnet-5、claude-sonnet-4-6 |
| Grok（2） | grok-4.5、grok-4.6 |
| 图片（4） | gpt-image-2、gpt-image-2-1K、gpt-image-2-2K、gpt-image-2-4K |
| 视频（1） | seedance-2.0 |

公开目录另外包含 `deepseek-v4-flash`、`deepseek-v4-pro`，但本账号模型目录没有返回它们，不能按已获权限处理。

账号返回状态为 1，历史请求数为 0，额度为 150000；站点 `quota_per_unit` 为 500000，故折合 0.3 个站内额度单位。账号目录不等于每个令牌分组均能调用全部模型。

## 本项目配置方式

在管理后台的渠道治理中新增渠道：

```text
名称：AHHil / 对应分组名称
供应商类型：openai-compatible
协议：OPENAI
Base URL：https://ahhilai.top
API Key：该站点为相应分组签发的推理令牌
模型：通过渠道模型发现读取，并按需选择
```

账号登录密码和登录 access token 不应替代推理 API Key。建议不同分组建立不同渠道，以便分别记录成本、健康状态、限流及路由优先级。

代码依据：

- `src/main/java/com/transit/provider/OpenAiCompatibleGateway.java` 已支持 `openai-compatible`，使用 Bearer 鉴权并拼接 `/v1/chat/completions`。
- `src/main/java/com/transit/service/ModelDiscoveryService.java` 使用 Bearer 鉴权读取 `/v1/models`，支持有无 `/v1` 后缀的 Base URL，并可默认停用新同步模型。
- 新同步模型初始价格为零、计费关闭，需要先配置成本和售价，再发布。

## 分组和计费注意点

登录后读取的分组倍率包括 GPT-Prox20 0.2、GPT-Prox20(企业渠道) 0.25、GPT-Plus 0.12、GPT-Team 0.07、Claude-cursor 0.4、Claude-Max 1.35、Claude-kiro(90%缓存) 0.15、image2 0.07、image2(原生4K) 0.12。倍率不是每百万 token 的最终人民币价格。

部分 GPT 模型使用 `tiered_expr`，按 `service_tier` 区分普通和 priority 价格。DeepSeek 的公开价格还包含时段变化。不可只复制 `model_ratio`；本项目固定输入/输出/缓存成本不足以完整表达这些动态规则，精确计费需要进一步适配，或限定请求档位并核对最终账单。

图片目录采用按次计费（`quota_type=1`）。图片和视频虽然被目录标为 `openai`，但这一标签不能证明它们支持标准图片或视频 API；尤其 `seedance-2.0` 仅列于 Bug 测试分组，应先验证实际请求体和结果格式。

## 尚未完成的上线验证

1. 使用选定分组的推理令牌验证 `GET /v1/models` 的实际模型范围。
2. 小规模验证聊天普通返回、SSE、usage、缓存字段、工具调用，以及所需的 Responses / Messages 协议。
3. 对图片和视频分别确认实际端点、计费单位和任务查询方式。
4. 核对分组采购成本后配置本项目定价，完成一次本项目网关到上游的端到端请求再发布。

本次未创建令牌、未发起收费推理、未改动现有渠道或运行配置。报告不含账号密码、会话令牌或 API Key。

来源：站点公开 `/api/status`、`/api/pricing`；登录后的 `/api/user/self`、`/api/user/models`、`/api/user/self/groups`；本项目当前工作区代码。
