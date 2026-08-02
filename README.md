# API Transit Station

面向多供应商大模型的统一网关与运营后台。后端采用 Spring Boot 3 / MyBatis-Plus，前端采用 Vue 3 / Element Plus；对外提供 OpenAI Chat Completions 兼容接口，并将 Anthropic、Gemini 与多种 OpenAI-compatible 上游统一到同一个模型、路由、计费和审计体系。

## 已实现能力

- 渠道后台：加密保存上游密钥、供应商模板、连接测试、RPM/TPM、权重、健康状态与冷却熔断。
- 模型后台：公开模型与上游模型映射、优先级、流量比例、输入/输出/缓存售价与成本。
- 模型同步：使用渠道凭据读取上游模型目录；只新增、不覆盖现有价格、不删除本地数据，新模型默认停用。
- 智能路由：严格优先级分层，同级按 `渠道权重 × 模型流量比例` 加权选择；可重试错误自动切换备用渠道。
- 稳定性：连续失败计数、自动冷却、成功恢复、平均延迟、错误摘要及 Prometheus 指标。
- 商业闭环：API Key 权限、用户钱包、请求预授权、实际用量结算、成本/毛利和审计日志。
- API 兼容：`GET /v1/models`、`POST /v1/chat/completions`，包含 `stream=true` SSE 兼容输出。
- 管理后台：用户、渠道、模型、Token、调用审计、财务、安全策略、系统配置和报表。

## 本地启动

环境要求：JDK 17、MySQL 5.7+、Node.js 20+。

1. 复制本地配置模板：

   ```powershell
   Copy-Item config/application-local.example.yaml config/application-local.yaml
   ```

2. 设置必要环境变量。`JWT_SECRET` 至少 32 个字符；`DATA_ENCRYPTION_KEY` 必须是 Base64 编码的 32 字节随机值。

   ```powershell
   $env:JWT_SECRET = '<replace-with-a-random-secret-at-least-32-characters>'
   $env:DATA_ENCRYPTION_KEY = '<replace-with-base64-encoded-32-byte-key>'
   $env:BOOTSTRAP_ADMIN_USERNAME = 'admin'
   $env:BOOTSTRAP_ADMIN_PASSWORD = '<replace-with-a-strong-password>'
   ```

3. 启动后端：

   ```powershell
   .\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
   ```

4. 启动前端：

   ```powershell
   Set-Location web
   npm ci
   npm run dev
   ```

默认后端端口为 `8089`，前端开发服务器由 Vite 启动。管理员首次启动由 `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD` 创建；创建成功后应从运行环境移除这两个变量。

## 接入一个模型供应商

1. 登录 `/admin`，进入“渠道治理”。
2. 选择协议类型，填写 Base URL、API Key 与模型清单，设置权重、限流和熔断阈值。
3. 渠道表单会为模型清单中的每个模型生成独立定价卡片；填写输入/输出/缓存的上游成本、销售价格与售卖倍率后一起保存。
4. 保存渠道时会在同一个事务中自动新增、更新或删除该渠道的模型映射，“模型与定价”只负责展示自动生成的路由、成本、售价与毛利。
5. 点击“测试”，默认 Prompt 为“你是什么模型”，也可以输入任意 Prompt；测试成功后模型即可按发布状态进入前台目录。

同一供应商有多个 API Key 时，为每个 Key 新建一条渠道，使用相同的渠道分组、Base URL 和模型名称，并分别填写该 Key 对应的采购成本。相同公开模型会自动形成多条货源路由：高优先级先使用，同优先级按 `渠道权重 × 模型流量比例` 分流；每次调用按实际命中的渠道成本结算，再按本站售价向用户计费，从而准确记录转售差价与毛利。

同一个公开模型可以配置多条映射。高优先级映射始终先于低优先级；同优先级映射才按权重分流。渠道到达连续失败阈值后进入 `COOLDOWN`，冷却到期允许探测性请求，成功后恢复为 `HEALTHY`。

后台的“前台调用”状态是最终发布判定。只有模型映射已发布，且绑定渠道已启用、已配置 API Key、健康状态为 `HEALTHY` / `DEGRADED`（或冷却已到期）时，模型才会同时出现在：

- 公共模型市场 `/market` 与 `GET /public/models`；
- 用户控制台“在线调试”的模型下拉框；
- 使用 API Key 查询的 OpenAI 兼容 `GET /v1/models`。

用户在模型市场点击“立即调用”会进入 `/console/playground` 并自动选中该模型；如果 API Key 配置了 `allowedModels`，控制台与 `GET /v1/models` 都只返回该 Key 有权调用的模型。真实调用统一走 `POST /v1/chat/completions`，服务端仍会再次验证模型权限、渠道状态、额度、限流与计费，不能通过前端参数绕过。

## API 示例

```bash
curl http://127.0.0.1:8089/v1/chat/completions \
  -H "Authorization: Bearer sk-at-REPLACE_ME" \
  -H "Content-Type: application/json" \
  -d '{"model":"your-public-model","messages":[{"role":"user","content":"hello"}]}'
```

流式兼容：

```bash
curl -N http://127.0.0.1:8089/v1/chat/completions \
  -H "Authorization: Bearer sk-at-REPLACE_ME" \
  -H "Content-Type: application/json" \
  -d '{"model":"your-public-model","stream":true,"messages":[{"role":"user","content":"hello"}]}'
```

当前 SSE 会在上游完整响应完成、结算成功后按 OpenAI chunk 格式输出，因此兼容流式客户端，但不是上游 token 级直通流。这样可以确保故障切换、额度结算和审计不会被半途输出绕过。

## 安全要求

- 仓库不提供任何可用的支付、虚拟卡、JWT、OAuth 或上游模型凭据默认值。
- `config/application-local.yaml`、`.env`、上传文件、日志和构建产物已忽略；不要把真实凭据提交到 Git。
- 渠道 API Key 使用 `DATA_ENCRYPTION_KEY` 加密，管理 API 只返回脱敏预览。
- 上游 Base URL 默认禁止私网、回环和其他 SSRF 高风险地址；仅在受控开发环境设置 `ALLOW_PRIVATE_UPSTREAMS=true`。
- AnyiPay、VMCard、ShopGPT 和创作供应商均应保持默认关闭，配置完成并完成合规审查后再分别启用。
- 如果历史提交或工作区曾包含真实凭据，应立即在对应供应商控制台轮换；仅从当前文件删除不能使旧凭据失效。

## 验证

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify
Set-Location web
npm test
npm run build
```

更详细的设计取舍与参考项目能力映射见 [docs/NEW_API_OPTIMIZATION.md](docs/NEW_API_OPTIMIZATION.md)。

## 参考与许可

架构设计参考了 [QuantumNous/new-api](https://github.com/QuantumNous/new-api) 的渠道治理、优先级/权重路由、模型同步、自动禁用和多协议网关思路。本项目按自身 Java/Vue 架构独立实现；引入第三方代码前请单独核对其 AGPL-3.0 许可义务。
