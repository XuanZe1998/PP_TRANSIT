# 业务列表分页清单

2026-11-04：全站视觉整理统一列表卡片、表格表头与单元格、空状态和移动分页的样式；现有列表 ID、共享分页组件、服务端查询/排序与“全部不超过200条”契约保持不变，不新增业务集合或分页接口。核查范围见 `docs/UI_UX_DESIGN_SYSTEM.md`。

2026-11-02：原“其他服务”更名“AI订阅”，删除独立的 16688“订阅服务”模块及前后台集合接口。AI订阅目录、订单和后台管理沿用既有 API、稳定列表 ID、服务端 COUNT 分页与 10/20/50/100 选项；订单提示条、按钮样式和页脚链接排版不新增业务集合。

2026-11-02：服务目录的创建订单弹窗区分待支付订单确认与实际付款门禁，不新增或修改业务集合接口；目录与订单继续沿用既有服务端分页参数及共享分页组件。

2026-10-23：`XiaoyunqueStudio.vue` 小云雀作品记录使用 `GET /creative/xiaoyunque/runs`，数据库以相同用户与创作要求搜索条件执行 COUNT + LIMIT/OFFSET，按创建时间和 ID 倒序；`ListPagination` 默认10、可选10/20/50/100，“全部”由服务端限制为不超过200条。`GET /creative/xiaoyunque/runs/{id}/media` 对所属任务的已保存完整媒体结果进行服务端分页，参数及“全部”上限相同。视频模型、比例、清晰度为固定表单选项；素材选择仅显示本次上传数量和最近文件名，不新增素材集合展示。

默认10，可选10/20/50/100；全部仅限总数≤200，服务端校验。每个列表使用稳定标识记住选择；固定表单选项和导航无需分页。

首页统计使用 `/public/models/summary`，仅返回完整公开模型目录的 `total` 和去重 `publisherCount`；不返回业务列表，不再下载前100条详情推算厂商数量。模型市场列表继续使用原分页接口。

网关使用数据库 COUNT + LIMIT/OFFSET。旧集合接口保留兼容入口：listPage/listPath/listCurrent/listSize/listAll；该兼容层在授权后分页，仍会读取原集合。高容量旧接口后续应逐个下推到数据库，避免集合装载开销。前端派生的小数据集本地分页。

网关自动发布状态、等待原因和下次重试时间随 `/admin/api/gateway/models` 同页返回，保持 COUNT + LIMIT/OFFSET 和相同筛选。批量处理结果沿用 `ModelGateway-4` 的 PagedTable，每批服务端最多200条。服务档位价格沿用 `gateway-model-price-tiers` 分页表格。

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
| web/src/views/AdminVmCardTest.vue | AdminVmCardTest-1 | `productCodes` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminVmCardTest.vue | AdminVmCardTest-2 | `savedCards` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminVmCardTest.vue | AdminVmCardTest-3 | `events` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/AdminContactMethods.vue | AdminContactMethods-1 | `contact_methods` | 独立接口数据库 COUNT + LIMIT/OFFSET；默认10，可选10/20/50/100，全部上限200 |
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
| web/src/views/UserConsole.vue | UserConsole.vue-1 | `activities` | 统一组件 `PagedList`；空状态单独显示，非空记录维持默认10条分页；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-1 | `keys` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-2 | `billingSummary` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/views/UserConsole.vue | UserConsole-3 | `billingRows` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole-1 | `页面数据` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole-5 | `wallet.transactions` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole-2 | `页面数据` | 页面服务端分页 |
| web/src/views/UserConsole.vue | UserConsole-6 | `genericRows` | 统一组件；集合来源使用兼容分页，本地派生数据分页 |
| web/src/components/ModelPriceComparisonDialog.vue | model-comparison-offers | `comparison.offers` | 统一分页；按报价列翻页 |
| web/src/components/ContactWidget.vue | contact-widget-public | `contact_methods` | 公共接口数据库 COUNT + LIMIT/OFFSET；默认10，可选10/20/50/100，全部上限200 |

2026-09-06：模型网关分组列表增加前台名称直接编辑、测试健康状态；继续使用 ModelGateway-1 服务端 COUNT 分页。加价预览继续使用 GatewayPricingEditor-1 与阶梯 PagedTable，增加采购价、实际变价数与规则覆盖原因，不改变分页上限。

2026-09-06：模型网关增加 AiAPIBank 上游分组同步入口。分组目录任务使用 gateway_sync_jobs 的 GROUPS 类型和站点锁；运行记录沿用 ModelGateway-3 服务端 COUNT + LIMIT/OFFSET 分页，单条任务详情不属于集合。新分组仅后台显示，填写 Key 后自动核验目录和采购价，首次有效目录发布可计费模型。完整目录中缺失的分组立即清理；分页、空目录、错误响应不触发清理。

2026-09-07：模型与定价列表的分组列改为展示实际“上游分组”，并增加上游分层价格说明；仍沿用 ModelGateway-2 服务端 COUNT + LIMIT/OFFSET 分页，筛选、排序和总数口径不变。

### AiAPIBank 模型广场分组发现

- 网关站点/分组仍使用服务端 COUNT + LIMIT/OFFSET 和现有分页组件，默认10，可选10/20/50/100，全部上限200。
- 分组发现读取 `/api/v1/model-plaza`。站点未授权时使用匿名响应；管理员完成 AiAPIBank 账号授权后，任务使用短期 Access Token 读取账号可见目录，包括账号已获权限的“对接专用”分组。
- 账号密码只用于首次登录请求，不写入配置或数据库。登录成功后仅以用途绑定 AES-GCM 加密保存 Refresh Token；Access Token 只在内存中短期缓存。TOTP 登录只暂存加密挑战令牌，验证成功后立即清除。
- AiAPIBank 分组发现按 `aiapibank.sync-cron` 定时执行，默认每天 03:20（Asia/Tokyo）。刷新令牌失效时任务失败并要求管理员重新授权，不降级为匿名响应以免把不完整目录误报为完整结果。
- 模型广场目录缺失不删除已有分组、Key、模型或售价；空目录、重复分组、截断标记和错误响应均拒绝应用。
- 新发现分组只创建待配置占位；每个分组仍需单独配置调用 Key，再核验模型与采购价后开放调用。

2026-09-09：公共模型目录的“渠道 / 路由”筛选统一以 `upstream_sites` 站点身份聚合；分组只保留凭据、同步、套餐与定价职责，不再生成独立公开渠道代号。`GET /public/models` 及 `/public/models/facets` 继续在同一完整候选集上做服务端筛选与分页，路由筛选值不再因同站点分组数量膨胀；启动修复会把同站点同名历史分组映射收敛到站点身份。


2026-09-22：MaPay 替换后恢复钱包充值和完整服务商城。以下列表均使用数据库 `COUNT + LIMIT/OFFSET`，授权/筛选条件在 COUNT 和数据查询中一致，默认 10，只接受 10/20/50/100：

| 页面 / 接口 | 列表 | 分页方式 |
| --- | --- | --- |
| `OtherServices.vue` / `GET /public/other-services` | 公开服务目录 | `ListPagination`，`listPage=true` |
| `OtherServices.vue` / `GET /service-orders` | 用户服务订单 | `PagedTable` + `ListPagination`，服务端状态筛选 |
| `UserConsole.vue` / `GET /platform/user/recharge-orders` | 用户充值订单 | `PagedTable` + `SelectablePagination` |
| `AdminOtherServices.vue` / `GET /admin/api/other-services` | 管理服务目录 | `PagedTable` + `ListPagination` |
| `AdminServiceOrders.vue` / `GET /service-orders/admin/orders` | 管理服务订单 | `PagedTable` + `ListPagination`，服务端按账号邮箱精确筛选，COUNT 与数据查询使用相同条件。批量操作选择当前页，服务端每次最多接收 100 笔。 |
| `AdminProductCommerce.vue` / inventory API | 加密库存 | `PagedTable` + `ListPagination` |
| `AdminProductCommerce.vue` / coupons API | 优惠码 | `PagedTable` + `ListPagination` |
| `AdminPaymentIntents.vue` / `GET /admin/payment-intents` | 支付记录 | `PagedTable` + `ListPagination`，服务端搜索/状态筛选，返回 `total/page/size/items` |

订单交付内容是单个订单的有界结果，仅在不超过 200 条时允许本地“全部”展示。

2026-09-24：服务兑换域名后台列表 `AdminOtherServices.vue` / `GET /admin/api/other-services/redemption-hosts` 使用 `PagedTable` + `ListPagination`，数据库按相同搜索条件 COUNT + LIMIT/OFFSET，默认10，仅接受10/20/50/100，不提供“全部”；增删单条域名即时生效。


2026-09-28：服务目录“卡密管理”直接按服务 ID 打开共享 `ServiceInventoryManager`，商品与履约配置复用该组件。

| 页面 / 接口 | 列表 | 分页方式 |
| --- | --- | --- |
| `ServiceInventoryManager.vue` / `GET /admin/api/other-services/{id}/inventory`（兼容 `/service-orders/admin/services/{serviceId}/inventory`） | 本站加密卡密库存 | `PagedTable` + `ListPagination`；`listPage=true`，数据库 COUNT + LIMIT/OFFSET；服务端状态和 `q` 搜索（ID、掩码尾号、订单号）；默认10，可选10/20/50/100，无“全部” |
| `AdminServiceOrders.vue` / `GET /service-orders/admin/orders` | 卡密关联服务订单 | 现有分页契约新增 `serviceId` 服务端筛选，COUNT 与数据查询使用相同条件；按 `orderId` 调用单条详情接口不依赖当前列表页 |

库存只在本站自动发货服务开放；上游采购服务显示来源说明与服务订单入口。库存统计不受搜索和分页截断影响。完整卡密仅通过管理员主动 POST 单条查看／复制返回，列表不含密文、指纹和明文。批量删除仅针对当前页最多100个选中 ID，不提供隐式“删除全部筛选结果”。

2026-09-29：`ContactWidget.vue` 联系方式列表改为全站（公开、用户、管理后台）共享悬浮入口，默认折叠且可拖动；展开后仍使用 `ListPagination`，公共接口继续按一致条件 COUNT + LIMIT/OFFSET，默认10、可选10/20/50/100，“全部”仅限服务端不超过200条。首页四张功能介绍卡属于固定展示文案，不是业务数据列表。

## 2026-10-18 master 合并复核
2026-11-04：新增“邀请有礼”和“邀请返佣管理”。`ReferralConsole.vue` 的商品、邀请关系、返佣明细、提现记录和管理员退款记录统一使用 `PagedTable` 与 `ListPagination`。用户接口 `/user/referrals/lists/{kind}` 与管理员接口 `/admin/api/referrals/lists/{kind}` 使用服务端 COUNT + LIMIT/OFFSET，返回 total/page/size/items；授权、筛选条件复用于计数及数据查询。默认10条，可选10/20/50/100，不提供全部。批量审核最多100笔，选择限当前页。完整收款信息经领取后主动单条查看，列表仅显示脱敏账号。


保留 master 的后台卡密订单精确定位、网关删除预览和分页筛选实现；合并线上快照的完整列表清单。服务支付确认弹窗只显示一笔订单，不新增业务集合。

2026-10-17：服务订单“去支付”新增单笔订单的交易披露确认弹窗，不新增集合接口。公开服务目录和用户订单仍使用现有服务端 `listPage=true`、`COUNT + LIMIT/OFFSET` 与 `ListPagination` / `PagedTable`，默认10、可选10/20/50/100，不提供“全部”；付款后按当前页重新加载订单。

2026-11-04：钱包充值弹窗的套餐卡片继续使用 `PagedList`（`UserConsole.vue-3`）和钱包接口的兼容分页，默认10条，可选10/20/50/100，最多200条时才可选“全部”。自定义金额和两种付款方式为固定选项，单独展示。
