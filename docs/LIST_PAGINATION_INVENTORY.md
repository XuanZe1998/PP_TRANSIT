# 业务列表分页清单

默认20，可选10/20/50/100；全部仅限总数≤200，服务端校验。每个列表使用稳定标识记住选择；固定表单选项和导航无需分页。

网关使用数据库 COUNT + LIMIT/OFFSET。旧集合接口保留兼容入口：listPage/listPath/listCurrent/listSize/listAll；该兼容层在授权后分页，仍会读取原集合。高容量旧接口后续应逐个下推到数据库，避免集合装载开销。前端派生的小数据集本地分页。

| 文件 | 列表标识 | 数据 | 方式 |
|---|---|---|---|
| web/src/components/AdminUsageCharts.vue | AdminUsageCharts-1 | `dailyByModel` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/components/GatewayModelEditor.vue | gateway-model-price-tiers | `model.priceTiers||[]` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/components/GatewayPricingEditor.vue | GatewayPricingEditor-1 | `preview.changes` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/components/GatewayPricingEditor.vue | 'pricing-tiers-'+row.id | `row.tiers` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/components/ModelProbePanel.vue | ModelProbePanel-1 | `tasks` | 页面服务端分页 |
| web/src/components/ModelProbePanel.vue | ModelProbePanel-1 | `页面数据` | 页面服务端分页 |
| web/src/components/NewApiConnect.vue | NewApiConnect-1 | `selectedPrices`（New API / sub2api 导入预览） | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-1 | `agents` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-2 | `withdrawals` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-3 | `serviceCosts` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-4 | `oauthClients` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-5 | `accounts` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-6 | `priceTemplates` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-7 | `proxies` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-8 | `ops.heartbeats||[]` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminAgents.vue | AdminAgents-9 | `backups` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-1 | `dashboard.channelHealth || []` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-2 | `rows` | 页面服务端分页 |
| web/src/views/AdminConsole.vue | AdminConsole-1 | `页面数据` | 页面服务端分页 |
| web/src/views/AdminConsole.vue | AdminConsole-3 | `secondaryRows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-4 | `rechargePlans` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-5 | `filteredRows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-6 | `report.models || []` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-7 | `rows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-8 | `sensitiveWords` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-9 | `securityEvents` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-10 | `displayRows` | 页面服务端分页 |
| web/src/views/AdminConsole.vue | AdminConsole-2 | `页面数据` | 页面服务端分页 |
| web/src/views/AdminConsole.vue | AdminConsole-3 | `页面数据` | 页面服务端分页 |
| web/src/views/AdminConsole.vue | AdminConsole-11 | `testRows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-12 | `filteredModelMarketDisplayItems` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-13 | `channelCredentials` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminConsole.vue | AdminConsole-14 | `discoveryResult.missingModels || []` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminCreativeConfig.vue | AdminCreativeConfig-1 | `connections` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminOtherServices.vue | AdminOtherServices-1 | `services` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminProductCommerce.vue | AdminProductCommerce-1 | `inventory` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminProductCommerce.vue | AdminProductCommerce-2 | `coupons` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminServiceOrders.vue | AdminServiceOrders-1 | `displayOrders` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminVmCardTest.vue | AdminVmCardTest-1 | `productCodes` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminVmCardTest.vue | AdminVmCardTest-2 | `savedCards` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminVmCardTest.vue | AdminVmCardTest-3 | `events` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AgentConsole.vue | AgentConsole-1 | `summary.withdrawals || []` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/FlowScreen.vue | FlowScreen.vue-1 | `screen.cards` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/FlowScreen.vue | FlowScreen-1 | `tableRows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/ModelGateway.vue | ModelGateway-1 | `rows` | 页面服务端分页 |
| web/src/views/ModelGateway.vue | ModelGateway-2 | `rows` | 页面服务端分页 |
| web/src/views/ModelGateway.vue | ModelGateway-3 | `rows` | 页面服务端分页 |
| web/src/views/ModelGateway.vue | ModelGateway-4 | `batchResults` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/ModelMarket.vue | ModelMarket-1 | `页面数据` | 页面服务端分页 |
| web/src/views/OrganizationConsole.vue | OrganizationConsole-1 | `members` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/OrganizationConsole.vue | OrganizationConsole-2 | `tokens` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/OrganizationConsole.vue | OrganizationConsole-3 | `usage` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/OtherServices.vue | OtherServices.vue-1 | `services` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/OtherServices.vue | OtherServices-1 | `orders` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/OtherServices.vue | OtherServices.vue-2 | `orderDetail.deliveryItems` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole.vue-1 | `activities` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-1 | `keys` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-2 | `billingSummary` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-3 | `billingRows` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole-1 | `页面数据` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole.vue-2 | `wallet.plans` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-4 | `rechargeOrders` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-5 | `wallet.transactions` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole-2 | `页面数据` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole.vue-3 | `wallet.plans` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-6 | `genericRows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/components/ModelPriceComparisonDialog.vue | model-comparison-offers | `comparison.offers` | 统一分页；按报价列翻页 |

2026-09-06：模型网关分组列表增加前台名称直接编辑、测试健康状态；继续使用 ModelGateway-1 服务端 COUNT 分页。加价预览继续使用 GatewayPricingEditor-1 与阶梯 PagedTable，增加采购价、实际变价数与规则覆盖原因，不改变分页上限。

2026-09-06：模型网关增加 AiAPIBank 上游分组同步入口。分组目录任务使用 gateway_sync_jobs 的 GROUPS 类型和站点锁；运行记录沿用 ModelGateway-3 服务端 COUNT + LIMIT/OFFSET 分页，单条任务详情不属于集合。新分组仅后台显示，填写 Key 后自动核验目录和采购价，首次有效目录发布可计费模型。完整目录中缺失的分组立即清理；分页、空目录、错误响应不触发清理。

2026-09-07：模型与定价列表的分组列改为展示实际“上游分组”，并增加上游分层价格说明；仍沿用 ModelGateway-2 服务端 COUNT + LIMIT/OFFSET 分页，筛选、排序和总数口径不变。

### AiAPIBank 模型广场分组发现

- 网关站点/分组仍使用服务端 COUNT + LIMIT/OFFSET 和现有分页组件，默认20，可选10/20/50/100，全部上限200。
- 分组发现读取 `/api/v1/model-plaza`。站点未授权时使用匿名响应；管理员完成 AiAPIBank 账号授权后，任务使用短期 Access Token 读取账号可见目录，包括账号已获权限的“对接专用”分组。
- 账号密码只用于首次登录请求，不写入配置或数据库。登录成功后仅以用途绑定 AES-GCM 加密保存 Refresh Token；Access Token 只在内存中短期缓存。TOTP 登录只暂存加密挑战令牌，验证成功后立即清除。
- AiAPIBank 分组发现按 `aiapibank.sync-cron` 定时执行，默认每天 03:20（Asia/Tokyo）。刷新令牌失效时任务失败并要求管理员重新授权，不降级为匿名响应以免把不完整目录误报为完整结果。
- 模型广场目录缺失不删除已有分组、Key、模型或售价；空目录、重复分组、截断标记和错误响应均拒绝应用。
- 新发现分组只创建待配置占位；每个分组仍需单独配置调用 Key，再核验模型与采购价后开放调用。
