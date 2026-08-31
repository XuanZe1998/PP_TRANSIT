# Linknux sub2api 模式

本实现以 `Wei-Shaw/sub2api@b5827cfd` 的账号池行为为参考，在现有 Java/Vue 架构中独立实现，不运行或复制其 Go 服务。账号授权只对管理员开放；API 用户仍只持有本站 API Key。

## 上线前配置

部署环境只必须提供 `DATA_ENCRYPTION_KEY`（Base64 编码的 32 字节主密钥）。主密钥不能与密文一起存入数据库，应使用部署 Secret/KMS 注入，且在已保存配置或 Token 后不能直接更换。

各平台的 Client ID、Client Secret、回调根地址、OAuth 端点、模型/探测端点、推理 API 根地址和 Scope 在管理后台“代理、号池与运维 → 上游账号 → OAuth Client 配置”中填写。整个配置文档使用独立 AAD 域的 AES-256-GCM 加密后存入 `upstream_oauth_client_configs`；Client Secret 不会通过管理 API 回显。

数据库配置优先于旧环境变量。一旦某平台存在数据库行，即使它被禁用、不完整或密文损坏，也不会回退到 YAML/环境变量，以避免运维关闭失效。旧的 `*_OAUTH_*` 环境变量仅作为无数据库配置时的兼容入口。

## 管理流程

1. 在“OAuth Client 配置”中填写平台配置，保存并执行端点可达性检查，再开启平台。
2. 在“上游账号”创建价格规则。同名规则组成一个模板组，匹配顺序为精确名、glob、`*`，同级按 priority。
3. 点击“授权订阅账号”，选择平台、价格模板组、账号分组、代理与模型范围。
4. 自动回调成功后，系统依次交换 Token、校验 nonce、查询身份/订阅、发现模型并执行真实探测，然后自动创建该平台唯一的托管渠道。
5. 只有匹配可靠成本和售价的模型会启用；其余模型以“待定价”状态保留。
6. 前端或官方 CLI 使用本站 Base URL 和本站 API Key。上游 access/refresh token 不会通过任何管理接口返回。

主要管理接口位于 `/admin/api/provider-oauth-clients`、`/admin/api/provider-accounts` 与 `/admin/api/provider-price-templates`。Provider OAuth 回调为 `/upstream/oauth/callback/{platform}`。公共兼容入口包括 OpenAI `/v1/**`、Anthropic Messages、Gemini `/v1beta/**`、`/antigravity/**` 与 `/backend-api/codex/**`。
