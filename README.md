---
AIGC:
  ContentProducer: '001191110102MAD55U9H0F10002'
  ContentPropagator: '001191110102MAD55U9H0F10002'
  Label: '1'
  ProduceID: 'a6c3ae9a-27a5-45ee-94a3-cdd244ccf140'
  PropagateID: 'a6c3ae9a-27a5-45ee-94a3-cdd244ccf140'
  ReservedCode1: 'd80a40c6-64ab-4a35-8173-6cd8eec85154'
  ReservedCode2: 'd80a40c6-64ab-4a35-8173-6cd8eec85154'
---

# Linknux

面向开发者与团队的一站式 AI 能力平台，统一连接主流模型，提供智能路由、用量治理、创作工具与企业协作。

面向多供应商大模型的统一网关与运营后台。后端采用 Spring Boot 3 / MyBatis-Plus，前端采用 Vue 3 / Element Plus；对外提供 OpenAI Chat Completions 兼容接口，并将 Anthropic、Gemini 与多种 OpenAI-compatible 上游统一到同一个模型、路由、计费和审计体系。

## 已实现能力

- 渠道后台：加密保存上游密钥、供应商模板、连接测试、RPM/TPM、权重、健康状态与冷却熔断。
- 模型后台：公开模型与上游模型映射、优先级、流量比例、输入/输出/缓存售价与成本。
- 模型同步：使用渠道凭据读取上游模型目录；只新增、不覆盖现有价格、不删除本地数据，新模型默认停用。
- 智能路由：严格优先级分层，同级按 `渠道权重 × 模型流量比例` 加权选择；可重试错误自动切换备用渠道。
- 稳定性：连续失败计数、自动冷却、成功恢复、平均延迟、错误摘要及 Prometheus 指标。
- 商业闭环：API Key 权限、用户钱包、请求预授权、实际用量结算、成本/毛利和审计日志。
- API 兼容：Chat Completions、Responses、Embedding、Rerank、图片、语音和统一异步任务接口；好易智算 Chat 支持真实 SSE 直通。
- 企业账户：Owner/管理员/财务/成员角色、24 小时一次性邀请、主钱包额度划拨、成员独立钱包和精细用量报表。
- 多 Key 凭证池：一个渠道可配置多个加密上游 Key，按健康、容量和实时并发选择，Redis 提供多实例 RPM 与负载共享。
- 管理后台：用户、渠道、模型、Token、调用审计、财务、安全策略、系统配置和报表。

## 本地启动

环境要求：JDK 17、MySQL 8、Redis 6+、Node.js 20+。

1. 复制本地配置模板：

   ```powershell
   Copy-Item config/application-local.example.yaml config/application-local.yaml
   Copy-Item config/.env.example config/.env.local
   ```

2. 编辑 `config/application-local.yaml` 和 `config/.env.local`。`security.jwt.secret` 至少 32 个字符；`security.data-encryption-key` 必须是 Base64 编码的 32 字节随机值。好易智算 Key 配置在 `haoee.api-key`，不要求使用环境变量。完整说明见 [统一配置目录](config/README.md)。

3. 启动后端：

   ```powershell
   .\mvnw.cmd spring-boot:run
   ```

4. 启动前端：

   ```powershell
   Set-Location web
   npm ci
   npm run dev
   ```

默认后端端口为 `8089`，前端开发服务器由 Vite 启动。管理员首次启动由 `security.bootstrap-admin.username` / `security.bootstrap-admin.password` 创建；创建成功后应从私有配置中移除这两项。生产环境仍可使用对应环境变量覆盖本地 YAML。

## 模型鉴别（可选）

平台内置独立的模型鉴别能力（基于 BazaarLink LLMprobe-engine，以 Node.js sidecar 进程运行），可对任意 OpenAI 兼容端点执行质量、安全、完整性探针，输出 0-100 评分报告与身份鉴别结论（用于检测模型调包 / 降级）。

该特性默认关闭（fail-closed）。启用步骤：

1. 启动 Node.js sidecar（零依赖，仅需 Node 20+）：

   ```powershell
   node model-probe/src/server.js
   ```

   默认监听 `http://127.0.0.1:9891`，可通过 `MODEL_PROBE_PORT` / `MODEL_PROBE_HOST` 覆盖。

2. 在后端配置开启并指向 sidecar（可写回 `config/application-local.yaml` 或环境变量）：

   ```yaml
   model-probe:
     enabled: true
     sidecar-url: http://127.0.0.1:9891
     timeout-seconds: 900
     admin-enabled: true
     user-enabled: true
   ```

3. 访问入口：管理后台“审计与安全 → 模型鉴别”（`/admin/model-probe`）；用户控制台“模型鉴别”（`/console/model-probe`）。

> 许可提示：LLMprobe-engine 使用 AGPL-3.0 协议，以独立 sidecar 进程运行并与主程序隔离。对外提供该功能（含通过网络）需遵守 AGPL 开源义务，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 接入一个模型供应商

1. 登录 `/admin`，进入“渠道治理”。
2. 选择协议类型，填写 Base URL、API Key 与模型清单，设置权重、限流和熔断阈值。
3. 渠道表单会为模型清单中的每个模型生成独立定价卡片；填写输入/输出/缓存的上游成本、销售价格与售卖倍率后一起保存。
4. 保存渠道时会在同一个事务中自动新增、更新或删除该渠道的模型映射，“模型与定价”只负责展示自动生成的路由、成本、售价与毛利。
5. 点击“测试”，默认 Prompt 为“你是什么模型”，也可以输入任意 Prompt；测试成功后模型即可按发布状态进入前台目录。

同一供应商有多个 API Key 时，在渠道的“凭证池”中添加多个 Key；凭证独立限流、健康和冷却，调用时优先选择并发最低且仍有容量的凭证。不同供应商或不同采购成本仍建立独立渠道路由。

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

好易智算路由的 SSE 为上游 token 级直通，支持背压和客户端断开；结束时从最终 usage 事件结算。未确认上游是否接受的超时会标记为 `UNKNOWN`，不会盲目切换渠道或释放预占资金。

好易智算的部署、灰度和接口清单见 [企业网关说明](docs/HAOEE_ENTERPRISE_GATEWAY.md)。

## 卡密自动发货

1. 建议在生产私有配置中设置 `service-orders.redemption-allowed-hosts`，只填写可信的兑换站域名（逗号分隔）；配置后将强制白名单校验。
2. 后台进入“服务与订单”，新增服务时选择“卡密自动发货”，填写白名单内的 HTTPS 兑换地址。
3. 在同一个新增服务弹窗内粘贴初始卡密库存；后续也可在“服务订单 → 商品与履约配置”中继续补库。英文逗号、中文逗号、顿号、换行、制表符和空格都可作为分隔符。

卡密库存使用 `DATA_ENCRYPTION_KEY` 加密，并用指纹防止重复导入；下单时事务预留，仅在服务端确认付款成功后交付。管理库存列表不返回明文。兑换入口是项目内的 `/services/:id/redeem`，它不传递卡密、不使用 iframe，只经后端白名单复核后返回一次性 HTTPS 跳转。

## 安全要求

- 仓库不提供任何可用的支付、虚拟卡、JWT、OAuth 或上游模型凭据默认值。
- `config/application-local.yaml`、`config/.env.local`、上传文件、日志和构建产物已忽略；不要把真实凭据提交到 Git。
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