# 线上模型真实调用报告（2026-09-28）

- 对 `https://api.linknux.com` 发起真实计费路径请求，而不是仅查询目录或历史日志。测试使用临时、限额身份；测试结束后全部停用，未记录密钥、提示词以外的响应内容或图像。
- 文本：`/v1/chat/completions`，简短输入，`max_tokens=16`；仅 Responses 模型走 `/v1/responses`；图像走 `/v1/images/generations`，单张 1024×1024。低并发顺序请求。
- 145 个公开模型均至少请求一次。**82 个文本模型得到 HTTP 200 且有 choices**；46 个文本模型未通过，3 个最后一次超时（其中 1 个此前曾成功）；2 个 Responses 和 12 个图像模型本轮全部未通过。HTTP 200 只证明这次接口有结果，不保证持续可用、文本非空或质量。
- 测试账户 4–9 的本站记录合计销售额 657 分、估算采购成本 585 分（CNY）；这些是测试额度和本站估算，不能视为上游最终账单。

## 上线后复测与当前目录状态（同日）

- PR #55 修复 New API 图片通用路由；PR #56 将该渠道图片生成的上游响应超时从通用 90 秒单独延长至 180 秒，两次发布均通过 CI 与线上健康检查。这里的逐模型表格仍保留**修复前的首次实测**，不能据此判断现在的图片路由状态。
- 上线后使用独立限额身份真实调用：`gpt-image-2-1K` HTTP 200、返回 1 张图片（约 53 秒）；`gpt-image-2` HTTP 200、返回 1 张图片（约 85 秒）。这只证明本次生成成功，不保证持续可用。
- `gpt-image-2-2K` 两次未通过，先后收到 502（上游 403）及 HTTP 500（约 121 秒）；`gpt-image-2-4K` 两次未通过，先后收到 502（上游 502）及 HTTP 500（约 122 秒）。后两次 500 的具体上游／本站边界未确认，不将其臆断为模型永久下线。
- 已在生产中暂停 14 个明确上游 404、17 个明确上游 403 的 AiAPIBank 映射，以及上述 2 个连续失败的 New API 图片型号（共 35 条映射、33 个公开模型 ID）；不删除数据，以便上游权限／支持恢复后复测启用。仅一次 400/502 或偶发超时的其余模型未批量下架。当前启用计费映射对应 **112 个不同公开模型 ID**；这不等于 112 个都经本轮证实可用。
- 上线后测试身份 10–18 均已停用，相关 Key 全部禁用。超时请求按不确定状态保留预留，不把它们当成功或自行退款；测试账号与真实客户隔离。

## 主要原因与处理

1. 上游明确 `model_not_found`（例如 Kimi k2.5、DeepSeek v4 flash、多个 Claude 旧别名）：本站上架映射与当前分组实际支持不一致；不能通过重试本站解决。
2. 上游 `GROUP_DISABLED` 与图像 `Image generation is not enabled for this group`：对应 AiAPIBank 分组或图像权限未开放；应暂停相关映射，待上游授权后复测恢复。
3. New API 的 4 个 `gpt-image-2*` 模型返回本站 503，因通用协议路由遗漏 `new-api` 图片渠道；代码修复并通过单元测试，**此表仍是修复前实测**，上线后须再次请求验证。
4. 其余上游 400/502、偶发超时不能仅凭一次请求断言永久不可用；需结合上游账号、模型参数及低频复测。
5. 个别 GPT 上游将极短请求计为 4,394 或 22,000+ 输入 token，曾触发临时 API Key 的真实用量额度 429；这属于上游报告用量/计费与短输入不相称的风险，额度 429 的尝试已用新测试身份重测，不计入模型失败。

## 逐模型结果

| 公开模型 | 协议 | 本轮结果 | 归因 / 备注 |
|---|---|---|---|
| `aiapibank/claude-max20/claude-fable-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-fable-5-1` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-haiku-4-5` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/claude-max20/claude-haiku-4-5-20251001` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-opus-4-5` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/claude-max20/claude-opus-4-5-20251101` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/claude-max20/claude-opus-4-6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-opus-4-7` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-opus-4-8` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-opus-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-sonnet-4-5` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/claude-max20/claude-sonnet-4-5-20250929` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/claude-max20/claude-sonnet-4-6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-max20/claude-sonnet-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-fable-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-haiku-4-5-20251001` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-opus-4-5-20251101` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-opus-4-6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-opus-4-7` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-opus-4-8` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-opus-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-sonnet-4-5-20250929` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-sonnet-4-6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/claude-special/claude-sonnet-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/deepseek/deepseek-v4-flash` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/deepseek/deepseek-v4-flash-0731` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/deepseek/deepseek-v4-flash-vision-exp` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/deepseek/deepseek-v4-pro` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/deepseek/deepseek-v4-pro-0813` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/glm/glm-5.2` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/glm/glm-5.3` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/glm/glm-5.3-flash` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-low/codex-auto-review` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-low/gpt-5.4` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-low/gpt-5.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-low/gpt-5.6` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-low/gpt-5.6-sol` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-low/gpt-5.6-terra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-low/gpt-6-astra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-low/gpt-image-2` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-plus-pro/codex-auto-review` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-5.3-codex` | Responses | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-plus-pro/gpt-5.3-codex-spark` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-plus-pro/gpt-5.4` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-plus-pro/gpt-5.4-mini` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-5.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-5.6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-5.6-luna` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/gpt-plus-pro/gpt-5.6-sol` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-5.6-terra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-6-astra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-pro/gpt-image-1` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-plus-pro/gpt-image-1.5` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-plus-pro/gpt-image-2` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-plus-stable/codex-auto-review` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-5.3-codex` | Responses | 未通过（上游 503） | 上游暂不可用 |
| `aiapibank/gpt-plus-stable/gpt-5.3-codex-spark` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-plus-stable/gpt-5.4` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-plus-stable/gpt-5.4-mini` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-5.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-5.6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-5.6-luna` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/gpt-plus-stable/gpt-5.6-sol` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-5.6-terra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-6-astra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-plus-stable/gpt-image-2` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-pro/codex-auto-review` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-pro/gpt-5.3-codex-spark` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-pro/gpt-5.4` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-pro/gpt-5.4-mini` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/gpt-pro/gpt-5.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-pro/gpt-5.6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-pro/gpt-5.6-luna` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/gpt-pro/gpt-5.6-sol` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-pro/gpt-5.6-terra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-pro/gpt-6-astra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/gpt-pro/gpt-image-1` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-pro/gpt-image-1.5` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/gpt-pro/gpt-image-2` | 图像 | 未通过（上游 403） | 上游拒绝：对应分组停用或未开放图像权限 |
| `aiapibank/grok-low/grok-4.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/grok-low/grok-4.6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-38/codex-auto-review` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-38/gpt-5.4` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/group-38/gpt-5.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-38/gpt-5.6-sol` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-38/gpt-5.6-terra` | Chat | 待复测（超时） | 45 秒内无确定结果 |
| `aiapibank/group-59/claude-fable-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-fable-5-1` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-haiku-4-5-20251001` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-opus-4-5-20251101` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/group-59/claude-opus-4-6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-opus-4-7` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-opus-4-8` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-opus-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-sonnet-4-5-20250929` | Chat | 未通过（上游 404） | 上游分组不支持该模型 |
| `aiapibank/group-59/claude-sonnet-4-6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-59/claude-sonnet-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-60/claude-fable-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-60/claude-fable-5-1` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-60/claude-haiku-4-5-20251001` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-opus-4-5-20251101` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-opus-4-6` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-opus-4-7` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-opus-4-8` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-opus-5` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-sonnet-4-5-20250929` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-sonnet-4-6` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-60/claude-sonnet-5` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/group-67/codex-auto-review` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.3-codex-spark` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.4` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.4-mini` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.5` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.6` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.6-luna` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.6-sol` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-67/gpt-5.6-terra` | Chat | 未通过（上游 403） | 上游 GROUP_DISABLED，分组停用 |
| `aiapibank/group-69/grok-4.5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/group-69/grok-4.6` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/kimi/kimi-k2.5` | Chat | 未通过（上游 502） | 上游网关错误，需上游排查 |
| `aiapibank/kimi/kimi-k2.6` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/kimi/kimi-k2.7-code` | Chat | 通过（HTTP 200） | 有响应对象 |
| `aiapibank/kimi/kimi-k3` | Chat | 通过（HTTP 200） | 有响应对象 |
| `claude-fable-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `claude-opus-4-7` | Chat | 通过（HTTP 200） | 有响应对象 |
| `claude-opus-4-8` | Chat | 未通过（上游 400） | 上游拒绝请求；具体参数/账号需复核 |
| `claude-opus-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `claude-sonnet-5` | Chat | 通过（HTTP 200） | 有响应对象 |
| `deepseek-v4-pro` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gemini-3-flash-preview` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gemini-3.1-pro-preview` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gemini-3.5-flash` | Chat | 待复测（超时） | 45 秒内无确定结果 |
| `glm-5.2` | Chat | 未通过（上游 400） | 上游拒绝请求；具体参数/账号需复核 |
| `glm-5.3` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gpt-5.4` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gpt-5.4-mini` | Chat | 未通过（上游 400） | 上游拒绝请求；具体参数/账号需复核 |
| `gpt-5.6-luna` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gpt-5.6-sol` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gpt-5.6-terra` | Chat | 通过（HTTP 200） | 有响应对象 |
| `gpt-6-astra` | Chat | 待复测（超时） | 45 秒内无确定结果 |
| `gpt-image-2` | 图像 | 未通过（本站 503） | 当前图片路由遗漏 New API（待修复上线复测） |
| `gpt-image-2-1K` | 图像 | 未通过（本站 503） | 当前图片路由遗漏 New API（待修复上线复测） |
| `gpt-image-2-2K` | 图像 | 未通过（本站 503） | 当前图片路由遗漏 New API（待修复上线复测） |
| `gpt-image-2-4K` | 图像 | 未通过（本站 503） | 当前图片路由遗漏 New API（待修复上线复测） |
| `kimi-k3` | Chat | 通过（HTTP 200） | 有响应对象 |

## 验证口径

- 请勿将本报告的 502 直接解释为“模型永久下线”：本站统一返回 502，上游原始状态已通过测试期间日志及本站日志归类。
- 超时模型需要不同时间窗口复测；图片模型的上线后真实计费路径结果见上文，`2K`／`4K` 仍待上游修复与复测。
- 临时测试身份均已停用；未通过关闭或更改真实客户账号。
