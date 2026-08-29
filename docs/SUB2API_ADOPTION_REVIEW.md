# sub2api 功能采纳评估

评估基线：Wei-Shaw/sub2api 固定提交 [`b5827cfd54d58c248a9480b800444d0b40f0c6ea`](https://github.com/Wei-Shaw/sub2api/commit/b5827cfd54d58c248a9480b800444d0b40f0c6ea)，评估日期 2026-08-30。

Linknux 保持 Spring Boot、Vue、MySQL、Redis 与现有部署链路。本文记录的是功能设计采纳，不表示引入 sub2api 的 Go 运行时。当前实现优先独立重写；如未来改写具体上游代码，必须在对应文件保留版权、许可证与修改说明。

| 模块 | 决定 | Linknux 对应实现与边界 |
|---|---|---|
| Accounts | Adopt / Merge | `provider_credentials` 保留原 ID 并升级为上游账号；增加平台、认证类型、加密凭证包、OAuth 有效期、分组、代理、成本模式、模型范围、临时不可调度与错误分类。旧渠道凭证 API 继续可用。 |
| Account Groups | Adopt / Merge | 账号 `account_group`、账号路由绑定和现有渠道/模型映射共同工作，不另建重复模型目录。 |
| Proxies | Phased | 新增 `upstream_proxies` 管理基础，覆盖 HTTP/HTTPS/SOCKS5、加密认证、备用代理、直连回退、到期和探测状态；创建与探测均执行协议、DNS、私网、回环、链路本地和组播校验。账号到实际出站客户端的绑定随各供应商适配器灰度，未绑定前不声称流量已走代理。 |
| Scheduler / Session | Adopt / Merge | 顺序为兼容性与状态过滤、优先级、当前负载、健康延迟、LRU、权重；Redis 保存并发、RPM 和 2 小时粘性。支持 `previous_response_id`、请求体/请求头 `session_id` 与 `X-Session-Id`。 |
| OpenAI / Codex | Phased | API Key 调度已兼容；OAuth 总开关默认关闭，管理 API 只在显式启用后开放标准授权配置。Responses、Chat、Images 继续走现有协议入口。 |
| Claude | Phased | 现有 Anthropic Messages 与 API Key 继续工作；OAuth、缓存用量配额刷新需要供应商客户端配置后灰度。 |
| Gemini / Antigravity | Phased | 现有 Gemini 网关与 API Key 可用；OAuth、Antigravity 配额映射通过统一账号扩展点后续灰度。 |
| Grok | Phased | 账号领域与平台枚举已预留；OAuth、搜索、图片、音频需要标准供应商配置后灰度。 |
| Model Plaza / Groups / Composite Routes | Merge | 保留现有模型广场和多单位价格；新增登录用户预计返利后价格，不公开账号身份和成本。实际账单只认结算快照。 |
| Pricing / Billing / Profit Control | Merge | 沿用钱包预授权、实际用量结算和销售/成本快照；代理结算使用整数金额、basis points 与最低 20% 平台销售毛利保护。成本不可靠账号不得用于启用后的代理流量。 |
| Affiliate | Adopt / Redesign | 单级代理申请、审核、等级、邀请码、不可变绑定、客户即时返利、7 天冻结佣金、转余额、最低 ¥100 人工提现；充值不计佣。 |
| Ops Dashboard / Realtime / Alerts | Adopt | 新增 5 分钟 QPS/TPS、P50/P95/P99、成功率、账号健康、开放告警、任务心跳和 SSE 实时接口。 |
| Channel Monitor | Adopt | 新增渠道健康快照、7/15 天可用率数据、分钟汇总和 `/public/status`；主动外部探测仍复用经过 SSRF 校验的现有渠道测试。 |
| Usage / Audit / Cleanup | Merge | 继续使用 `logs` 与 `usage_hourly`；新增账号维度分钟指标、任务心跳和 31/90 天保留任务。 |
| Payments / Promo / Redeem | Keep | 保留 AnyiPay、支付意图、兑换码和优惠券；仅采用唯一业务事件和幂等审计思想。 |
| Announcements | Adopt | 新增定向公告、有效期、用户已读状态及用户/管理员接口。 |
| Backup / Data Management | Adopt with boundary | 后台可查看并登记备份请求；真正的数据库备份继续由受限服务器发布脚本执行，恢复只允许服务器运维，Web 应用不持有恢复权限。 |
| Risk Control / Error Passthrough | Merge | 新增鉴权、限流、配额、过载、代理、请求、不确定结果分类；仅明确安全的前置错误允许切换，可能已被上游接受的请求不重放。继续使用现有敏感词、安全事件和脱敏。 |
| Batch Image | Merge patterns | 继续使用现有创作任务；只复用任务心跳、恢复、冻结资金对账思想，不建立重复任务系统。 |
| Subscriptions | Defer | 与现有钱包模式边界未统一，暂不启用。 |
| Plugins / iframe | Defer | 与 CSP、凭证隔离和供应链边界冲突，暂缓。 |
| Self-update | Reject | 不允许应用自更新；继续使用可回滚 CI/CD、版本软链和数据库备份。 |
| Fingerprint spoofing / risk evasion | Reject | 不迁移。Linknux 只允许合法持有或明确授权账号通过标准协议接入。 |

## 许可证与来源

- sub2api 原项目采用 GNU Lesser General Public License v3.0；固定提交的许可证副本保存在 `docs/licenses/sub2api-LGPL-3.0.txt`。
- 第三方声明见仓库根目录 `THIRD_PARTY_NOTICES.md`。
- 本阶段没有复制 Go 源文件；领域表、Java 服务、Vue 页面与测试均为 Linknux 架构下的独立实现。

## 功能开关

- `LINKNUX_PROVIDER_ACCOUNTS_ENABLED=false`
- `LINKNUX_PROVIDER_OAUTH_ENABLED=false`
- `LINKNUX_OPS_ENABLED=false`
- `LINKNUX_AGENT_ENABLED=false`

账号池 OAuth 与代理结算在生产环境默认关闭。启用顺序必须遵循：迁移与回填 → 现有 API Key 新调度 → 运维观测 → 分平台 OAuth 灰度 → 完整结算周期核对 → 代理返利。
