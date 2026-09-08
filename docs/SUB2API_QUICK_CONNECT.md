# sub2api 上游一键接入

管理员可在「模型网关 → 接入上游」选择「sub2api 站点（自动识别）」，填入上游站点地址和一枚推理 API Key。地址可以是站点根地址，也可以带 `/v1`、`/v1/models` 或 `/v1/chat/completions`。

## 识别与导入

1. 用该 Key 请求 `GET /v1/models`，只导入这枚 Key 实际可见的模型。
2. 优先请求 `GET /v1/sub2api/billing`，严格校验 `object=sub2api.key_billing`、`schema_version` 与计费范围。
3. sub2api 的 simple mode 或较早分支没有 Key 计费自描述时，改用不带凭据的 `GET /api/v1/settings/public` 校验兼容特征。
4. 创建独立的站点分组和模型映射；站点、分组、模型仍分开管理，不用 `sub2api` 来代替站点身份。

同一 sub2api 站点的不同分组需要各自创建 API Key，然后分别接入。第二枚 Key 接入时可选择已有站点，以便在网关工作台中统一管理。

## 安全与价格

- Key 走现有渠道凭据加密流程；请求、异常、同步任务和审计记录不包含凭据。
- 发现阶段拒绝跟随重定向携带 Key，且继续受上游 URL/SSRF 策略限制。
- sub2api 的 Key 自描述只返回有效分组倍率，不返回每模型的实际采购单价。因此新模型默认「待核价」且不发布，管理员核对采购价和售价后才能开放调用。
- 自动同步不覆盖手工价格。新模型会以未发布状态加入；模型连续两次完整目录缺失才会停用。

## 运行时协议

适配器使用 sub2api 的 Bearer Key，并直接转发它支持的 OpenAI Chat Completions / Responses、Anthropic Messages 以及 Gemini 原生路径。模型名根据 Key 目录推断可用协议，管理员可在模型设置中收紧或补充。

## 管理接口

- `POST /admin/api/gateway/onboard/sub2api/preview`：校验站点与 Key，返回模型、检测到的分组倍率和待核价预览。
- `POST /admin/api/gateway/onboard/sub2api/connect`：重新校验权限后创建未发布分组，并入队一次持久化同步任务。
- 后续统一使用 `/admin/api/gateway/groups`、`/admin/api/gateway/models`、`/admin/api/gateway/jobs` 的服务端分页管理。

## 上游契约依据

- [sub2api 网关路由](https://github.com/Wei-Shaw/sub2api/blob/main/backend/internal/server/routes/gateway.go)
- [sub2api Key 计费自描述](https://github.com/Wei-Shaw/sub2api/blob/main/backend/internal/handler/gateway_key_billing.go)
