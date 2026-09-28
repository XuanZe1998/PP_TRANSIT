# 线上模型可用性审计（2026-09-28）

只读快照：公开目录 `https://api.linknux.com/public/models` 与生产库 `logs`、`gateway_publication_requests`。**已上架/渠道健康/已核价不等于实时推理可用**。截至 2026-09-28 07:07（生产数据库时间），过去 14 天无调用日志；最新成功是 2026-09-11，最新失败是 2026-09-04。未发起可能计费的逐模型推理请求，故今日可用/不可用均不能逐一确认。

公开目录 145 项：历史成功 11、历史失败但未见成功 0、无成功/失败记录 134；**今日实测确认 0 项**。历史成功不保证当前可用，历史失败不保证当前仍故障。

## 已发布模型逐项状态

| 模型 ID | 路由数 | 历史证据 | 最后成功 | 最后失败 |
| --- | ---: | --- | --- | --- |
| `aiapibank/claude-max20/claude-fable-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-fable-5-1` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-haiku-4-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-haiku-4-5-20251001` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-opus-4-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-opus-4-5-20251101` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-opus-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-opus-4-7` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-opus-4-8` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-opus-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-sonnet-4-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-sonnet-4-5-20250929` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-sonnet-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-max20/claude-sonnet-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-fable-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-haiku-4-5-20251001` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-opus-4-5-20251101` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-opus-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-opus-4-7` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-opus-4-8` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-opus-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-sonnet-4-5-20250929` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-sonnet-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/claude-special/claude-sonnet-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/deepseek/deepseek-v4-flash` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/deepseek/deepseek-v4-flash-0731` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/deepseek/deepseek-v4-flash-vision-exp` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/deepseek/deepseek-v4-pro` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/deepseek/deepseek-v4-pro-0813` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/glm/glm-5.2` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/glm/glm-5.3` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/glm/glm-5.3-flash` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/codex-auto-review` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-5.4` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-5.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-5.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-5.6-sol` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-5.6-terra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-6-astra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-low/gpt-image-2` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/codex-auto-review` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.3-codex` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.3-codex-spark` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.4` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.4-mini` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.6-luna` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.6-sol` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-5.6-terra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-6-astra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-image-1` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-image-1.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-pro/gpt-image-2` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/codex-auto-review` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.3-codex` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.3-codex-spark` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.4` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.4-mini` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.6-luna` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.6-sol` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-5.6-terra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-6-astra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-plus-stable/gpt-image-2` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/codex-auto-review` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.3-codex-spark` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.4` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.4-mini` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.6-luna` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.6-sol` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-5.6-terra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-6-astra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-image-1` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-image-1.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/gpt-pro/gpt-image-2` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/grok-low/grok-4.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/grok-low/grok-4.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-38/codex-auto-review` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-38/gpt-5.4` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-38/gpt-5.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-38/gpt-5.6-sol` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-38/gpt-5.6-terra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-fable-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-fable-5-1` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-haiku-4-5-20251001` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-opus-4-5-20251101` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-opus-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-opus-4-7` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-opus-4-8` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-opus-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-sonnet-4-5-20250929` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-sonnet-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-59/claude-sonnet-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-fable-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-fable-5-1` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-haiku-4-5-20251001` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-opus-4-5-20251101` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-opus-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-opus-4-7` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-opus-4-8` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-opus-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-sonnet-4-5-20250929` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-sonnet-4-6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-60/claude-sonnet-5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/codex-auto-review` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.3-codex-spark` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.4` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.4-mini` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.6-luna` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.6-sol` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-67/gpt-5.6-terra` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-69/grok-4.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/group-69/grok-4.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/kimi/kimi-k2.5` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/kimi/kimi-k2.6` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/kimi/kimi-k2.7-code` | 1 | 无成功/失败实测记录 | — | — |
| `aiapibank/kimi/kimi-k3` | 1 | 无成功/失败实测记录 | — | — |
| `claude-fable-5` | 2 | 无成功/失败实测记录 | — | — |
| `claude-opus-4-7` | 2 | 历史成功（非实时） | 2026-08-29 05:55:43 | 2026-08-29 03:13:06 |
| `claude-opus-4-8` | 3 | 历史成功（非实时） | 2026-08-31 14:13:04 | — |
| `claude-opus-5` | 2 | 无成功/失败实测记录 | — | — |
| `claude-sonnet-5` | 2 | 无成功/失败实测记录 | — | — |
| `deepseek-v4-pro` | 1 | 历史成功（非实时） | 2026-08-29 05:55:45 | — |
| `gemini-3-flash-preview` | 1 | 历史成功（非实时） | 2026-08-29 05:58:08 | 2026-08-29 05:55:46 |
| `gemini-3.1-pro-preview` | 1 | 历史成功（非实时） | 2026-08-29 05:58:17 | 2026-08-29 05:55:49 |
| `gemini-3.5-flash` | 1 | 历史成功（非实时） | 2026-08-29 05:58:26 | 2026-08-29 05:55:53 |
| `glm-5.2` | 1 | 无成功/失败实测记录 | — | — |
| `glm-5.3` | 1 | 历史成功（非实时） | 2026-08-29 09:51:32 | — |
| `gpt-5.4` | 1 | 历史成功（非实时） | 2026-09-01 11:48:04 | 2026-09-04 14:46:04 |
| `gpt-5.4-mini` | 2 | 无成功/失败实测记录 | — | — |
| `gpt-5.6-luna` | 1 | 无成功/失败实测记录 | — | — |
| `gpt-5.6-sol` | 1 | 历史成功（非实时） | 2026-09-11 01:33:43 | 2026-09-01 11:58:58 |
| `gpt-5.6-terra` | 3 | 历史成功（非实时） | 2026-08-31 16:20:31 | 2026-09-04 14:41:35 |
| `gpt-6-astra` | 1 | 无成功/失败实测记录 | — | — |
| `gpt-image-2` | 2 | 无成功/失败实测记录 | — | — |
| `gpt-image-2-1K` | 2 | 无成功/失败实测记录 | — | — |
| `gpt-image-2-2K` | 2 | 无成功/失败实测记录 | — | — |
| `gpt-image-2-4K` | 2 | 无成功/失败实测记录 | — | — |
| `kimi-k3` | 1 | 历史成功（非实时） | 2026-08-29 05:55:59 | — |

## 尚未发布的路由（与同名其他已发布路由不同）

| 模型 ID | 来源 | 当前阻塞原因 |
| --- | --- | --- |
| `doubao-seed-2-0-mini-260215` | 好易智算 | 等待报价核验：缺少关键销售价格 |
| `doubao-seed-2-0-pro-260215` | 好易智算 | 等待报价核验：缺少关键销售价格 |
| `gpt-5.4` | New API | 上游目录尚未确认此模型可用，等待后续完整同步 |
| `gpt-5.5` | New API | 等待报价核验：上游动态价格格式未识别，未执行表达式 |
| `gpt-5.5` | New API | 等待报价核验：上游动态价格格式未识别，未执行表达式 |
| `gpt-5.6-sol` | New API | 等待报价核验：上游动态价格格式未识别，未执行表达式 |
| `gpt-5.6-sol` | New API | 等待报价核验：上游动态价格格式未识别，未执行表达式 |
| `gpt-6-astra` | 好易智算 | 等待报价核验：缺少关键销售价格 |
| `qwen3.7-max` | 好易智算 | 等待报价核验：缺少关键销售价格 |
| `qwen3.8-max` | 好易智算 | 等待报价核验：缺少关键销售价格 |

另有 2 条 `WAITING` 发布请求引用已不存在的映射 ID（511、608），属于孤儿记录，不是额外模型。

## 失败原因与处置

- 8 月底至 9 月初的历史失败主要为上游 429 限流，包括 `gpt-5.4`、`gpt-5.6-terra`、`gpt-5.6-sol`；少量为上游 5xx。不能仅据旧错误禁用当前路由，需受控复测。
- 未发布路由被明确价格/上游目录校验拦截；不得为了提高“可用”数量而绕过核价、编造价格或强行发布。动态价格解析需获得可核验的上游价格样本和规则。
- 公开 API 的 `available=true` 仅表示已上架、有可路由配置，不代表近期成功。旧版本把无验证记录强制显示为 `AVAILABLE` 且页面硬编码“已验证可调用”；此次改为“已上架 · 未实测”。
- 如需确认 145 个模型今日实际可用性，需要受控、可能计费的逐协议请求和预算/凭据；本报告没有此类证据。
