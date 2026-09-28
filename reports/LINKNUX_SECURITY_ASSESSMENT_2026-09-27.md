# Linknux 线上安全检查报告（低侵入）

> **最新状态（UTC 2026-09-28 01:08；东京当地 2026-09-28）：** F1/F2 已修复，F3 HSTS 为 1 天，F4 前端 CSP 仍是 Report-Only。佣金/返利钱包同步修复 PR #51（`7d6dff56`）已合并，后端、前端与生产 deploy 任务均成功；本轮匿名复测首页/登录 GET/管理 API/Actuator 为 200/405/401/403。新发现的自动成片钱包计费不一致已有候选修复，PR #52 **为草稿、未合并、未部署**；本地全量 392 tests、0 failures/errors、5 skipped，新计费回归 12/12。历史账对账、worker 取消/重试竞态、生产 MySQL 和真实账户验收仍待完成，不能据此称站点整体安全或所有测试完成。早期段落保留历史记录，请以各段时间和文末记录为准。

- 检查日期：2026-09-27（线上响应时间为 UTC 13:54–13:59）
- 范围：`https://linknux.com`、`https://api.linknux.com`；配合当前仓库静态代码与部署配置核对。
- 方法：低频 HTTP GET/HEAD/OPTIONS、TLS 握手、少量常见敏感路径的存在性检查；没有登录、撞库、支付、数据修改、压力测试、自动化目录爆破或利用尝试。
- 结论：在本次受限范围内，未证实可直接读取私有数据或未经授权调用管理接口。发现 1 项高优先级的代理 IP 信任链风险（代码/配置级，尚未以真实账户在线利用验证），以及 3 项低优先级问题。不能据此判定站点整体安全或不存在其他漏洞。

## 发现

### F1 高：客户端可预置 X-Forwarded-For，应用信任链取最左侧地址

**证据与影响**：仓库 `deploy/nginx/linknux.conf:93` 使用 `$proxy_add_x_forwarded_for`，会保留客户端原有的 `X-Forwarded-For` 并追加 Nginx 看到的地址；`src/main/java/com/transit/service/ClientIpResolver.java:23-27` 因后端连接来自受信任的环回代理，直接取该头的最左侧。`src/main/java/com/transit/controller/AuthController.java:66-73` 将该地址用于登录和新地址验证，`src/main/java/com/transit/service/AuthService.java:177` 按地址是否可信决定是否发出二次验证。若线上实际采用此配置，外部请求者可自行选择被记录的 IP；在已知账户密码且能猜到/获知用户曾信任 IP 的前提下，可能绕过“新登录 IP”验证，也会污染登录/IP 审计记录。其他启用 `TRUST_FORWARDED_HEADERS` 的支付/网关路径也需单独核对。

**验证级别**：代码和部署配置组合证实；未向登录端点提交任何凭据，也未以真实账户验证绕过。检查前 `ClientIpResolverTests` 的 3 项只覆盖“可信代理提供首个字面 IP”，没有覆盖“公网客户端预置 XFF 后由 Nginx 追加”的攻击链。

**修复**：若 Nginx 是唯一可信边缘代理，改为 `proxy_set_header X-Forwarded-For $remote_addr;`，覆盖而非透传用户提供的值；若前面还有 CDN/负载均衡，则仅信任指定网段，按从右到左的可信代理链解析。统一各控制器取 IP 实现，并增加从公网携带伪造 XFF 到登录挑战的回归测试。部署后用自有测试账户验证不同真实网络不能凭自定义 XFF 命中既有可信 IP。

### F2 低：不支持的登录 HTTP 方法被错误映射为 500

**线上实证**：`GET https://api.linknux.com/auth/login` 和 `HEAD` 均返回 500；GET 响应仅含通用 `internal_error` 和 requestId，没有堆栈泄露。该路由在 `AuthController.java:66` 仅支持 POST；`GatewayExceptionHandler.java:89-95` 的兜底异常处理返回 500，未单独处理方法不支持异常。错误状态可能产生无谓错误日志/告警，妨碍对真正服务端故障的识别；不等同于认证绕过。

**修复**：对 `HttpRequestMethodNotSupportedException` 返回 405 与 `Allow`，补充 GET/HEAD/OPTIONS 回归测试，确认合法 POST 行为不变。

### F3 低：HSTS 仅持续 300 秒

**线上实证**：网页及 API 的 HTTPS 响应均为 `Strict-Transport-Security: max-age=300`；HTTP 首页返回 301 到 HTTPS。TLS 1.3 握手成功，证书链验证成功，证书有效期截至 2026-11-25 05:30:08 UTC。短 HSTS 窗口降低了再次访问时的 HTTPS 强制保护效果。这是加固建议，不是已证实的降级攻击。

**修复**：确认所有需覆盖的子域均支持 HTTPS 后，分阶段提高到至少数月，再评估 `includeSubDomains` 与预加载；保留 HTTP→HTTPS 跳转。

### F4 低：前端 HTML 未返回 CSP

**线上实证**：`https://linknux.com/` 返回 `nosniff`、`X-Frame-Options: DENY`、Referrer-Policy，但无 `Content-Security-Policy`；API 响应已有 CSP。未发现或尝试证明 XSS，因此只是纵深防御缺口。

**修复**：先部署 `Content-Security-Policy-Report-Only` 观察前端资源依赖，之后针对前端单独收紧并启用 CSP；避免为兼容性直接开放 `unsafe-inline` 脚本。

## 已验证的有效控制

- `https://linknux.com/` 返回 200，HTTP 返回 301 到 HTTPS；两个域名 TLS 1.3 与有效证书可用。
- 网页的 `.env`、`.git/config`，以及 API 的 `.env` 均返回 404；API 的 `/actuator/health`、`/actuator/info`、`/actuator/prometheus` 返回 403；`/admin/api` 未授权返回 401。
- `/auth/login` 的 CORS 预检：`Origin: https://example.invalid` 返回 403 且无允许来源头；`Origin: https://linknux.com` 返回 200 并仅允许该来源。
- 测试的前端打包文件 `.map` 返回 404；未见直接暴露源映射。注意这只是单个打包文件抽样。
- 检查初期的定向测试 `mvnw.cmd -q -Dtest=ClientIpResolverTests test`：3 tests，0 failures，0 errors；后续新增伪造 XFF 回归并通过完整 `verify`，见复测节。

## 限制与后续

本次没有账号、生产日志/实时 Nginx 配置、服务器访问或明确的高强度测试窗口；仓库配置与实际线上运行配置可能有偏差。未评估账号权限横向访问、上传、支付回调签名、业务逻辑、依赖 CVE、DNS 重绑定、全站路由或抗 DDoS。优先确认 F1 实际部署配置并修复，再使用专用测试账号与预发布环境验证登录 IP 挑战；其次修复 F2，逐步完成 F3/F4 加固。

本报告不包含凭据、会话令牌或用户数据。检查过程未修改线上数据。

## 2026-09-27 复测与修复状态（UTC 14:26–14:27）

**线上仍未修复。** 以低频、只读请求复查：`linknux.com/` 为 200，HSTS 仍是 `max-age=300` 且未返回 CSP；`api.linknux.com/auth/login` 的 GET 仍为 500，API HSTS 仍是 300；`/actuator/health` 为 403，匿名 `/admin/api` 为 401。这些是实际线上响应，不是本地修复效果。F1 没有账户挑战或有效 Nginx 配置的线上证据，仍属于高优先级的**条件性风险**，不可写成已成功在线绕过。

**本地候选修复，尚未发布：** `ClientIpResolver` 只在受信任代理连接上读取由边缘代理覆盖的 `X-Real-IP`，不再使用可预置的 XFF 首项；Nginx 示例将 XFF 覆盖为 `$remote_addr`。相关 Chat、支付、订单及管理审计路径统一使用该解析器。方法不支持异常映射到 405 并携带 `Allow`。Nginx 示例将当前主机 HSTS 提升至 180 天，给前端添加不允许内联脚本的 CSP；未启用 `includeSubDomains` 或 preload。**这些修改不能替代生产服务器 `nginx -t`、备份/回滚、真实浏览器支付和 OAuth 验收。** 若前置 CDN/负载均衡存在，必须先重新设计可信代理网段与真实客户端 IP 传递，不能直接套用 `$remote_addr`。

**本地验证：** `mvnw.cmd -q verify` 通过（382 tests、0 failures、0 errors、6 skipped）；前端单测 100/100、构建通过；`npm audit --omit=dev` 的生产依赖计数为 0（仅在检测时和锁定版本范围内成立）。针对拟议 CSP 的 Playwright WebKit 冒烟测试覆盖首页、模型页、管理登录、用户资料及管理壳，未观察到策略违规；API 在测试中是模拟响应，且未覆盖真实支付、第三方 OAuth、所有资源/用户路径。完整前端 E2E 在新增 CSP 测试前为 26/28，通过率不满：英文资料页仍有中文残留，1366×768 Playground 出现 30px 额外页面滚动；两项和本次安全改动无直接关联，但上线门槛需单独处理。拟议 CSP 的单项测试另外通过 1/1。未在生产机执行 `nginx -t`，亦未部署/重载服务。

**下一步验收顺序：** 先审查生产真实 Nginx/代理拓扑并备份；在预发布或维护窗口发布应用及配置，执行 `nginx -t` 后受控 reload，保留回滚；使用专用测试账户与两个独立真实出口验证伪造 XFF 不能命中可信 IP；复测登录 GET 405/Allow、HSTS/CSP、CORS、支付与 OAuth，监控异常。不能仅凭这里的低侵入检查宣称网站无漏洞。历史运维记录 `docs/LINKNUX_SECURITY_2026-09-26.md` 中的 9 月 24 日三次来源待核实 root 登录需由所有者结合主机日志继续排查；已记录 root 密码轮换和 SSH 加固，但本次未独立复核主机现态。

## 最新进展（UTC 14:39–14:46，替代上节「线上仍未修复」的时点结论）

只读读取生产 Nginx 的**有效配置**确认此前 API 代理确实使用 `$proxy_add_x_forwarded_for`，API 后端 8089 仅在本机监听；因此 F1 的配置前提已在生产得到证实，但未进行登录挑战绕过测试。随后仅对该代理头做了单行受控热修复：将 `X-Forwarded-For` 改为 `$remote_addr`，保留 `X-Real-IP $remote_addr`。修改前的原文件备份为 `/etc/nginx/sites-available/linknux.pre-xff-20260927T143959Z`；`nginx -t` 通过，reload 后读取有效配置确认新指令，首页/匿名管理/Actuator 冒烟状态分别为 200/401/403。UTC 14:41 外部再次确认首页 200。**这已在代理层切断伪造 XFF 首项传播；仍需专用测试账号从两种网络做端到端登录验证，不能声称绕过实测已通过。**

F2–F4 的生产状态尚未改变：UTC 14:41 登录 GET 仍为 500、两个域名仍返回 `max-age=300`，前端仍无 CSP。本地应用/前端候选修复仍未发布。完整前端 E2E 的两项失败已分别修复：动态登录 IP 说明使用显式双语文案，Playground 在 1366×768 视口保留三栏并避免整页多余滚动。重新构建后 WebKit E2E **29/29** 通过，前端单测 **100/100**。完整 `npm audit` 曾报告开发依赖 5 项（最高 high，生产依赖 0）；锁文件已更新到修复版本，`npm ci` 后重测 `npm audit` 为 **0 项**，单测和构建通过。生产并未安装这些前端依赖变更。

应用发布脚本坚持 clean `master == origin/master`；当前仓库为有用户未提交改动的功能分支，不能绕过该门槛直接替换线上应用。后续需将修复整理成独立可审核提交，经 CI 和受控发布流程发布，再复测 405、HSTS/CSP、OAuth/支付与 IP 挑战。9 月 26 日主机历史中的可疑登录待所有者独立核实。

## 复核与交付（UTC 2026-09-27 15:17；东京当地 9 月 28 日）

在最新 `origin/master` 的隔离工作树完成候选修复验证：`mvnw.cmd -q verify` 362 tests、0 failures、5 skipped；`npm run i18n:check` 覆盖 3835 条；前端单测 100/100、生产构建、WebKit E2E 29/29；`npm audit --audit-level=low` 为 0。修正了登录 IP 动态说明引发的国际化检查失败。候选修复提交为 `e8108f02`，公开仓库 PR #41；报告、凭据和主工作区其他未提交材料均未纳入提交。

低频线上复测：网页首页 200、HSTS 仍是 `max-age=300`，未观察到前端 CSP；登录 GET 仍 500，说明 F2–F4 **尚未部署**。API Actuator 健康端点 403。一次匿名 `/admin/api` 探测连接超时（curl 000），不据此推断服务授权行为；此前 401 的结果仍属先前时点记录。F1 已在生产 Nginx 覆写 XFF，但真实账号双出口的端到端验证仍未做。

Nginx 示例包含 180 天 HSTS 和强制前端 CSP，仅为待人工分阶段上线的候选；本次未用生产完整证书/include 对该候选文件做语法验证，也未完成真实 OAuth、支付、前端全路径兼容测试，**不得整份直接复制到生产**。本轮尝试隔离环境 Docker 拉取 Nginx 镜像时网络超时；SSH 管理身份未获认证，因此没有进行新的生产配置变更。待 PR CI、代码审查及发布流程完成后，再按受控计划复测 F2；F3/F4 应先报告模式/短 HSTS 阶段并进行真实业务验收。不能声称所有安全测试均已完成。

## 应用发布后的线上回归（UTC 2026-09-27 15:26）

PR #41 合并为 `0b7631e7`。master 自动发布第一次尝试的后端 CI 在 `ChatControllerStreamingTests.streamFlagProducesActualServerSentEvents` 上出现一次 SSE 响应头断言失败（362 tests，1 failure）；该测试此前本地与 PR CI 均通过。原 run 第 2 次执行后端、前端和 deploy 均成功，发布工作流 `36329292623` 最终为 success。这个偶发测试问题应单独追踪，不应隐藏首次失败。

发布后匿名、低频复测：`GET /` 200；`GET /auth/login` **405，Allow: POST**，F2 已在生产修复；`GET /public/models?size=1` 200；`GET /admin/api` 401；`GET /actuator/health` 403。F1 的 Nginx XFF 覆写仍以此前生产有效配置和热修复验证为依据，本轮未做真实账号 IP 挑战。首页仍为 `Strict-Transport-Security: max-age=300` 且无前端 CSP，F3/F4 **仍待分阶段 Nginx 发布**。不要将 PR 合并或应用发布成功误述为 Nginx 配置已上线。未进行 OAuth、支付、管理员登录或破坏性测试。

## 阶段性 Nginx 加固与浏览器复核（UTC 2026-09-27 15:45 后；东京当地 9 月 28 日）

**本节为最新生产状态，取代上文各时点关于「HSTS 仍为 300 秒、前端无 CSP」的陈述。** 早期章节保留作为变更轨迹，不表示当前配置。生产站点和 API 的 HSTS 已从 `max-age=300` 分阶段提高至 `max-age=86400`（1 天），暂不使用 `includeSubDomains` 或 `preload`。前端三个响应层级已添加 `Content-Security-Policy-Report-Only`；它仅观察、不阻止资源加载，**尚未启用强制 CSP**。API 代理此前的 `X-Forwarded-For $remote_addr` 覆写保持不变。F2 的应用 405 修复已随 PR #41 发布。

运维采用从生产原配置派生的阶段候选，而非直接套用仓库中 180 天 HSTS/强制 CSP 示例。生产原配置备份为 `/etc/nginx/sites-available/linknux.pre-hsts-csp-report-20260927T1545Z`；变更后配置 SHA-256 为 `a02efe42a065f50a325c88bb58d205d11c1ace3e960b3139df6c3f1fa1eaafc5`。隔离测试配置和正式配置的 `nginx -t` 均通过，reload 后外部低频回归：首页与 `/index.html` 200，均携带 HSTS 86400 和 CSP Report-Only、未携带强制 CSP；API 登录 GET 405、公开模型 200、Actuator 403，均携带 HSTS 86400。预设的三分钟自动回滚计时器在验证通过后已取消，计时器状态为 inactive。该短时回滚窗口不等于长期观测或端到端业务验收。

WebKit 浏览器在公开页面 `/market`、`/docs`、`/services` 获得 200，测试时未观察到 `securitypolicyviolation` 事件；`/`、`/subscriptions`、`/login`、`/admin/login` 的 `page.goto` 偶发 25 秒超时，结果**未确认**，不可计为通过。当前策略没有 `report-uri`/`report-to` 采集端点，因此不存在全站 CSP 违规遥测；局部浏览器观察的「0 事件」不能证明其他路径、账号、支付或 OAuth 场景兼容。测试机未安装 Playwright Chromium，未完成跨浏览器验证。

**遗留风险与验收门槛**：F1 的代理配置链已修复，但仍需专用账号从两个独立真实出口验证登录 IP 挑战；F3 仍处于 1 天 HSTS 阶段，F4 仍处于仅报告阶段。数月 HSTS 与强制 CSP 需先完成登录、支付和 OAuth 真实或沙箱路径的兼容测试、明确回滚和持续观察，再分阶段提升。生产阶段配置与仓库 `deploy/nginx/linknux.conf` 的 180 天/强制策略不一致，部署时**不得直接复制仓库示例**。本轮未进行高频扫描、利用、支付或状态修改，也不能声称全部安全测试完成。

### 后续低频复测（UTC 2026-09-27 15:54–15:56）

再次从外部检验：首页 GET 200 且返回 HSTS `max-age=86400`、CSP Report-Only，无强制前端 CSP；API 登录 GET 405/`Allow: POST`，Actuator 403，API 均携带 HSTS 86400。`HEAD /index.html` 已返回 200 与预期安全头，但客户端随后等待响应完成达到 20 秒超时，不计作成功的完整 HEAD 请求；需关注偶发延迟。WebKit 对 `/`、`/login` 的 `domcontentloaded` 导航分别得到 200、存在 `#app` 且未观察到 CSP 违规；`/subscriptions`、`/admin/login` 各重试两次仍在 20 秒导航超时，继续标为**未确认**，不能归因于 CSP。先前 `/market`、`/docs`、`/services` 的有限通过保持不变。此检查未使用认证状态，也未触发支付、OAuth 或写操作。

## 仓库阶段配置与 CI 稳定性修复（UTC 2026-09-27 16:00 后）

在隔离工作树将仓库 Nginx 示例改为当前生产的**阶段值**：网站/API 四处 HSTS 均为 86400 秒，前端三个响应层级均使用同一 CSP Report-Only。部署文档明确当前没有自动发布 Nginx 文件、报告收集端点及强制 CSP，禁止未完成登录/支付/OAuth 验收就提升策略。WebKit 本地 E2E 仍将该阶段策略**仅在测试环境强制应用**，并检查三个前端策略和四个 HSTS 值一致。另修复 SSE 异步响应测试的 CI 偶发竞争：确保内层流的第二阶段写入完成后再检查头与正文。

隔离工作树验证：SSE 定向测试连续 5 次通过；完整后端 `mvnw.cmd -q verify` 362 tests / 0 failures-or-errors / 5 skipped；前端国际化检查 3835 条、单测 100/100、构建、WebKit E2E 29/29、`npm audit --audit-level=low` 0 漏洞。新增一致性断言后，受影响的 CSP 定向 E2E 再次通过。代码提交仅含四个文件（`deploy/README.md`、`deploy/nginx/linknux.conf`、`ChatControllerStreamingTests.java`、`security-csp.spec.ts`），公开 PR #42：`https://github.com/XuanZe1998/PP_TRANSIT/pull/42`。因为 Git 直连一度不可达，使用 GitHub API 基于已核对的 `master` 上传同一 Git **树对象**；远端提交为 `9418612a`，树 SHA-1 `ed06ff64962238d6af39551f029bd24ee3293373` 与本地提交树一致。报告及主工作区其他未提交文件未上传。PR CI 此时仍在进行，不能声称已合并或已发布；即使 PR 合并，Nginx 示例也不由应用部署工作流自动上线。

## PR #42 合并、自动发布与最终匿名回归（UTC 2026-09-27 16:27）

PR #42 的前后端检查均通过，合并至 `master`，merge commit `b4eaf6e3`。自动工作流 `36332994780` 的 frontend、backend、deploy 三个 job 均为 **success**。部署后再次低频复核：首页 200 且返回 1 天 HSTS 与前端 CSP Report-Only；API 登录 GET 405/`Allow: POST`、公开模型 200、匿名管理接口 401、Actuator 403，API 均有 1 天 HSTS。只读核对生产 Nginx 文件 SHA-256 仍为 `a02efe42a065f50a325c88bb58d205d11c1ace3e960b3139df6c3f1fa1eaafc5`，确认本次应用部署没有覆盖阶段性 Nginx 配置。此类状态码/响应头冒烟不等于真实账户或支付/OAuth 流程已验收。

**结论与后续依赖**：F1 代理链配置已修、F2 405 已发布；F3 仍为阶段性 1 天 HSTS，F4 仍为报告模式。缺少专用测试账户、两个独立实际出口和支付/OAuth 沙箱，无法完成登录 IP 挑战与支付/OAuth 端到端测试，也就不应启用数月 HSTS、强制前端 CSP 或宣称全部安全测试完成。生产两条页面 `/subscriptions`、`/admin/login` 的 WebKit 导航超时仍未定因，应在可控测试环境继续诊断。仓库示例已对齐当前阶段，但未来每次提升都需再次语法验证、回滚准备和业务验收。公开仓库中不包含本报告或任何私有配置/凭据。

### 页面导航超时补充诊断（UTC 2026-09-27 16:27 后）

对 `/subscriptions` 与 `/admin/login` 各做一次普通 HTTP GET，均 200，首字节约 0.23–0.24 秒、下载约 1102 字节；这**不支持**将此前 WebKit 导航超时直接归因于 Nginx 返回首页 HTML 变慢。WebKit 改用 `waitUntil: commit` 时两个页面均曾取得 200 和初始 `#app` 容器、暂未捕获 CSP 违规，但各自在后续 8 秒内未形成非空应用内容；另一轮 `/admin/login` WebKit 导航在 15 秒内连 commit 都没有取得，未记录脚本响应或请求失败。表现具有不稳定性，可能涉及测试环境到静态资源的网络路径或前端启动流程，证据不足以定因或判定页面对真实用户持续不可用；不计入业务流程通过。应结合生产用户监控、浏览器 HAR/控制台与可复现环境再定位。CSP 仍是 Report-Only，本次没有观察到它阻断资源的证据。

### 公开页面跨浏览器补测（UTC 2026-09-27 16:40）

对生产 `/admin/login` 获取的 HTML 指向首屏 JS、四个预加载模块和 CSS。对这六个静态资源各做一次普通 GET（请求压缩响应），均 200，首字节约 0.19–0.30 秒、总耗时不超过 0.41 秒；不存在稳定可复现的 HTML/入口静态资源 404 或慢响应。使用本机已安装的 Chrome（Playwright Chromium 驱动，而非先前缺失的下载版 Chromium）访问 `/subscriptions`、`/admin/login`，曾分别得到 200、`document.readyState=complete`、有效订阅页面内容或可见管理员登录表单；所观察请求没有 HTTP 错误、脚本错误或 CSP 违规。**这提供了两条此前未确认路径的单次跨浏览器正向证据，但不覆盖实际支付、OAuth 或登录。**

同一浏览器的另一次 `/subscriptions` 导航仍曾在 20 秒 `domcontentloaded` 超时，WebKit 也有此前的不稳定结果；因此不能宣称延迟问题已消失或所有用户访问稳定，更不能以该现象证明 CSP 不兼容。当前证据支持「公开静态入口与两个页面在 Chrome 可正常加载，但自动化环境导航存在偶发超时」，需要现场浏览器 HAR/真实用户监测才能区分环境网络与生产间歇性问题。没有为这一未定因现象实施盲目代码或 Nginx 变更。

## 新发现：登录新 IP 挑战验证恒定 500（UTC 2026-09-27 / 东京时间 2026-09-28）

**级别：中（认证可用性）**。在本地真实 HTTP + H2 集成测试中，已有可信地址的账户从第二个地址登录会得到新 IP 验证挑战；但提交 `/auth/login/ip-verify` 时，`LoginIpService.verify` 将 `JdbcTemplate.queryForList` 的 `expires_at` 值直接强转为 `LocalDateTime`。实际 JDBC 返回 `java.sql.Timestamp`，抛出 `ClassCastException`，被全局兜底映射为 500。该错误发生于 IP 与验证码校验之前，因此若生产数据库驱动返回相同类型，新 IP 登录的验证流程无法完成；**这是代码与本地集成实证，未使用真实生产账户做端到端复现**。日志堆栈和原始用户数据未写入公开 PR。

**修复及回归**：将 `Timestamp` 显式转换为 `LocalDateTime`，兼容原本已是 `LocalDateTime` 的值。新增集成测试：首个地址成功登录建立信任；第二地址登录携带伪造旧地址 XFF 仍需挑战，数据库摘要绑定第二地址；旧地址提交挑战返回 403 且保持 PENDING；第二地址提交无效码到达验证码校验并返回 400，而非 500。定向测试通过；最终后端 `mvnw.cmd -q verify` 363 tests / 0 failures / 0 errors / 5 skipped，`git diff --check` 通过。该测试不等同于线上真实双出口与有效邮件验证码验收。

独立公开 PR #43（`https://github.com/XuanZe1998/PP_TRANSIT/pull/43`）仅含 `LoginIpService.java` 与 `AuthFlowIntegrationTests.java`。分支已变基到远端当前 `master` `b4eaf6e3`，PR head `0f8f8178`，文件列表复核为仅这两处。**此记录时 CI 仍在排队/运行，尚未合并或发布；生产仍可能存在该错误。** 发布后需以专用账户、两个实际出口与可控邮箱验证有效码登录、错误地址拒绝、挑战重放和审计状态。

### PR #43 合并、自动发布与匿名回归（UTC 2026-09-27 17:20 后）

PR #43 的前后端检查通过，合并提交 `715f041f`，合并时间 `2026-09-27T17:20:54Z`。`master` 工作流 `36336599973` 的 frontend、backend、deploy 均为 **success**；部署任务包括制品上传激活和公开端点验证。另从外部低频匿名复核：首页 200，前端 HSTS `max-age=86400` 且 CSP 仍为 Report-Only；API 登录 GET 405/`Allow: POST`、`/public/models?size=1` 200、匿名 `/admin/api` 401、`/actuator/health` 403，API HSTS 仍为一天。曾额外请求 `/models` 得到 401，这是需认证的不同路由，**不是**公开模型接口异常。

新 IP 验证的时间类型修复已随自动发布流程上线；但上述匿名响应不能证明真实邮箱验证码的成功登录或两个真实网络的 IP 绑定。本地 363 项测试及生产匿名冒烟各有其边界。完整验收仍需专用测试账户、可控邮箱与两个独立出口；支付/OAuth 需沙箱。HSTS 数月提升与前端强制 CSP 尚未进行，不应在业务验收前冒进。

### 新 IP 验证正向流程与重放补测（UTC 2026-09-27 17:43 后）

在隔离工作树新增完整的本地集成用例，用测试专用 `VerificationDeliveryService` spy 捕获随机生成的有效邮件验证码（不发送真实邮件、不把验证码写入报告）。先以旧 IP 登录，再以新 IP 触发挑战；携带**真实有效码**从旧 IP 验证得到 403，验证码仍为 PENDING；从新 IP 验证得到 access/refresh token，用 access token 请求个人资料为 200，新 IP 写入可信历史，挑战标为 CONSUMED；同一挑战重放得到 409。定向测试通过，最终 `mvnw.cmd -q verify` 共 364 tests / 0 failures / 0 errors / 5 skipped，`git diff --check` 通过。相比前一轮仅测试无效码，此用例实证了本地完整成功路径和一次性状态，但仍未代替生产邮箱真实投递或双出口测试。

仅修改 `AuthFlowIntegrationTests.java` 的公开 PR #44 已合并（merge `68ad10cb`，UTC `2026-09-27T17:43:49Z`）；`master` 工作流 `36338020108` 的 frontend、backend、deploy 均为 **success**。发布后单次低频匿名请求：首页 200、登录 GET 405、公开模型 200，HSTS 均为 `max-age=86400`。首页该次 GET 总时长约 7.31 秒，明显慢于此前常见的 0.2–0.4 秒样本；这是一次观察，不足以定位服务器、CDN 或本机网络原因，也不能说明修复引入回归。应以重复、受控的 RUM/HAR 与服务端时序核对，不盲目改代码。

**剩余线上验收依赖**：专用非生产价值账户、可控邮箱和两个真实独立出口；支付/OAuth 沙箱。当前没有这些条件，因此不声称“所有线上测试完成”，也不提升 HSTS 到数月或启用强制 CSP。

### 首页慢样本的低频时序复核（UTC 2026-09-27）

在此前单次 7.31 秒请求之后，从同一测试机间隔约 3 秒执行三次普通首页 GET，均为 200；DNS 0.005–0.043 秒、TCP 连接累计 0.063–0.097 秒、TLS 累计 0.144–0.157 秒、首字节 0.203–0.256 秒、总耗时 0.203–0.256 秒，目标 IP 均为 `207.57.122.109`。这三次未复现慢请求，不能排除间歇性异常；也没有证据支持把 7.31 秒归因于应用服务或本轮测试代码变更。继续建议 RUM/HAR 与服务器时序关联诊断，暂不据此改动代码。


### MaPay 签名通知 HTTP 回归（UTC 2026-09-27 17:56 后）

在隔离工作树新增 `MaPayWebhookIntegrationTests`，以测试专用商户配置和 H2 中的独立用户/钱包/充值订单，走真实 `/webhooks/mapay` HTTP POST、参数解析、签名验证和结算链路。未提供真实支付凭据、未发起生产交易。篡改金额导致签名不匹配、重新签名的错误商户、重复参数及签名正确但与订单金额不符，均返回 `fail`，订单与意图保持 PENDING、钱包无入账；正确签名返回 `success` 并入账一次，同一通知重放仍返回 `success` 而不重复入账。未发现此路径需修改的生产代码缺陷。

定向用例通过；完整 `mvnw.cmd -q verify` 为 365 tests / 0 failures / 0 errors / 5 skipped，`git diff --cached --check` 通过。仅含该测试文件的本地提交为 `d05f60c4`，分支 `codex/mapay-webhook-regression`。截至 UTC 17:59，GitHub API 可用但 Git over HTTPS 到 `github.com:443` 多次连接失败，**尚未推送、创建 PR、运行远端 CI 或发布**。不能把本地模拟签名回调称为真实支付沙箱/生产端到端验收；后者仍需要支付平台提供的沙箱商户与受控订单。

**同日后续更正（UTC 18:00 后）**：Git over HTTPS 仍连接失败，改经 GitHub API 从 `master` `68ad10cb` 建立同名远端分支，仅上传上述测试文件；远端提交 `414af860` 与本地等价文件提交 `d05f60c4` 的 SHA 不同。已创建 PR #45，文件列表复核**仅** `src/test/java/com/transit/service/MaPayWebhookIntegrationTests.java`；当前 CI 正在运行，未合并、未发布。此前“尚未推送、创建 PR”的叙述仅适用于 UTC 17:59 的状态，以上述后续状态为准。私有报告没有进入 PR。

### PR #45 合并、发布与匿名回归（UTC 2026-09-27 18:04 后）

PR #45 的两套前端/后端检查均成功，合并提交 `6a83c7da`，合并时间 `2026-09-27T18:04:08Z`。`master` 工作流 `36339267423` 的 frontend、backend、deploy 均为 **success**；部署步骤包含制品上传激活和公开端点验证。由于本轮仅新增测试，未改变生产业务行为。从测试机间隔发出三次只读请求：前端首页 200（约 0.247 秒）、API 登录 GET 405 / `Allow: POST`（约 0.365 秒）、公开模型 `?size=1` 200（约 0.653 秒）。网页与 API 的 HSTS 仍为 `max-age=86400`；前端 CSP 仍为 Report-Only，API 有强制 CSP。这些是匿名冒烟，不证明生产签名回调、钱包结算、真实邮件或双出口认证已完成验收。

**仍需用户侧测试条件**：可控邮箱、专用低价值账户、两个真实独立出口，以及支付/OAuth 沙箱和测试订单；在条件齐备前不触发生产资金流、不声称所有线上测试完成。HSTS 数月提升和前端强制 CSP 仍需单独的兼容性与回滚验证。

### 组织钱包转移守恒修复（UTC 2026-09-27 18:36；东京当地 9 月 28 日）

在隔离工作树从已发布的 `master` 进行代码审计和本地 H2 集成复现，发现**本地可复现的资金守恒缺陷**：接受企业邀请后，用户默认组织可指向企业 MEMBER 钱包；旧 `OrganizationService.create` 从 `users.balance` 为新 TREASURY 赋值，却对默认组织钱包的清零 UPDATE 不检查影响行数。如果原个人 TREASURY 仍有余额且 MEMBER 钱包余额不同，创建新组织可能在账面上复制余额。未在生产创建组织或操作真实资金，因此**不声称已证明线上可利用或产生损失**。同一流程的邀请到期字段在 H2 JDBC 下会返回 `Timestamp`，原 `LocalDateTime` 强转引发 `ClassCastException`。

修复采用事务内锁定并校验用户余额与自己的 TREASURY 钱包，调用已有钱包扣款服务从正确资金源扣除，然后创建新钱包；异常则整体回滚。兼容邀请到期字段的 JDBC 时间类型。回归覆盖真实邀请接受后创建组织、调用方陈旧快照、余额不一致和不足时的回滚、无旧钱包的遗留账户。定向测试通过；完整 `mvnw.cmd -q verify` 为 **370 tests、0 failures、0 errors、5 skipped**；差异检查通过。没有修改业务列表，分页清单及分页契约不涉及此变更。

仅三个代码/测试文件进入 PR #46，核对过文件列表，无私有报告、配置、凭据或日志。两套 PR 后端与前端检查通过；合并提交 `a3d22f47`，`master` 工作流 `36340998125` 的 frontend、backend、deploy 全部成功。发布后低频匿名 HEAD 回归：首页 200、API 登录 405/`Allow: POST`、公开模型 200；前端 HSTS 仍 `max-age=86400`，CSP 仍为 Report-Only。该匿名检查**不能验证生产钱包守恒、真实邮件、双出口 IP 挑战、支付/OAuth**；前述专用测试账户、受控邮箱、两个真实出口和沙箱条件仍未提供。未在线触发任何资金流，不将全部安全测试标为完成。

### 企业额度资金支撑与旧组织结算镜像（UTC 2026-09-27 18:53；东京当地 9 月 28 日）

继续审计前一轮组织钱包修复，在本地 H2 集成测试**修复前复现**两个相关问题：已给员工分配 4000 单位额度的企业，企业主创建下一企业后旧企业 TREASURY 余额由预期 4000 变为 0，员工额度仍显示 4000，失去资金支撑；之后旧企业的员工预留款释放会把企业主 `users.balance` 重设为旧企业余额（预期新默认钱包镜像 6000，实际 4000）。这均是本地代码路径复现；没有生产账户、支付或真实余额操作，也没有证实线上实际损失。

修复在锁定旧企业资金源后仅迁移尚未分配的可用金额；若员工额度总和超过原资金源则冲突回滚。旧组织的结算/释放仍调整其自身钱包，但只有当前首选的所有者 TREASURY（或当前默认 MEMBER 钱包）才同步用户余额镜像。两个新增集成回归覆盖额度资金支撑及真实 gateway reserve→release 路径。定向测试 13/13 通过；完整 `mvnw.cmd -q verify` **372 tests、0 failures、0 errors、5 skipped**；`git diff --check` 通过。没有修改业务列表或分页接口，分页清单无需变更。

PR #47 仅包含 `WalletBalanceService.java`、`GatewaySettlementService.java` 和 `OrganizationWalletTransferIntegrationTests.java`；两套 PR 前后端检查通过，合并提交 `fff7ffaf`。`master` 工作流 `36341999478` 的前端、后端及部署均成功。发布后低频匿名 HEAD：首页 200、API 登录 405 且 `Allow: POST`、公开模型 200；HSTS 仍 `max-age=86400`，前端 CSP 仍为 Report-Only。此冒烟无法证明生产企业额度、真实账户/邮件、双出口 IP 挑战及支付/OAuth 全链路行为，仍需专用低价值测试账户、受控邮箱、两个真实独立出口和支付/OAuth 沙箱。未将全部测试标为完成。


### 企业主自降级造成金库管理失能（2026-09-27 19:13 UTC；东京当地 9 月 28 日）

继续审计 `OrganizationService.updateMember`，本地 H2 集成测试在修复前**复现**：企业 `OWNER` 可以将自己的角色改为 `MEMBER`，事务成功提交，企业金库中仍有 10000 单位余额，但该组织再无可执行额度分配/回收的 `OWNER`；资金不等于被转走或消失。自停用请求原先依赖返回成员列表时的权限异常触发回滚，修复改为在写入前显式拒绝 `OWNER` 的角色和状态更改。此为已认证企业主自行操作导致的可用性/业务完整性问题，不是匿名权限提升；**未在生产账号或真实资金上尝试复现**。

新增回归覆盖自降级与自停用后角色、状态和余额保持不变。修复前定向测试 9 项中 1 项预期失败（自降级未抛异常）；修复后定向测试通过，后端完整 `mvnw.cmd -q verify` 为 **374 tests、0 failures、0 errors、5 skipped**；分页守卫 4/4 通过；因新中文错误文案导致的首轮前端 CI 失败已通过复用已有翻译文案解决，本地 `npm run i18n:check` 覆盖 3835 条。变更不涉及业务列表或集合接口，分页清单无需变更。

PR #48 仅涉及 `OrganizationService.java` 与 `OrganizationWalletTransferIntegrationTests.java`；两套 PR 前后端检查均通过，合并提交 `15307558b79bed9c65eefcb9237d128ddf9b3c79`。`master` 工作流 `36343338491` 的前端、后端及部署均成功。发布后从外部发出四次低频只读 HEAD：首页 200、API 登录 405/`Allow: POST`、公开模型 200、Actuator health 403；首页与 API HSTS 均为 `max-age=86400`，前端 CSP 仍仅 Report-Only。HEAD 冒烟验证服务存活与安全头，不验证生产企业主角色变更行为；没有执行在线资金或组织状态修改。

**总体限制不变**：完整线上验收仍需专用低价值账号、可控邮箱、两个真实独立出口，以及支付/OAuth 沙箱和可回滚测试订单。未完成这些条件前，不能宣称全部线上安全测试完成。数月 HSTS 和前端强制 CSP 仍须在业务兼容与回滚验证后分阶段上线。此私有报告不随 PR 提交。

### 企业成员状态与 API Key/钱包结算守卫（UTC 2026-09-27 19:37:22；东京当地 9 月 28 日）

本地 H2 集成回归在修复前复现：暂停的企业成员原 API Key 仍可识别；计费无法找到有效企业成员钱包时回退旧用户余额；管理员不能通过成员更新恢复暂停成员。修复后 `ApiKeyService.findBySecret` 要求组织、成员和钱包均有效；有组织 ID 时 `GatewaySettlementService.billingWallets` 不再回退旧余额；`OrganizationService.updateMember` 可定位未移除的暂停成员供有权的活跃管理员恢复。回归覆盖暂停、恢复、组织停用、预留与释放及余额/额度守恒。此处复现及修复验证均为本地测试，**未使用生产账户、生产 API Key 或真实余额**。

后端 `mvnw.cmd -q verify`：**376 tests、0 failures、0 errors、5 skipped**；分页守卫 4/4、前端 `npm run i18n:check` 通过。PR #49 仅含上述相关代码与集成测试四个文件，两套 PR 前后端 CI 均通过，合并提交为 `d6bb9e3382e75a86bbd7f0d57b14b10d0d40f6d2`。`master` 工作流 `36344773528` 的 backend、frontend、deploy 全部为 success，发布 SHA 与该合并提交一致。此变更未修改业务列表，分页清单无需变更。

发布后从外部仅做四次低频、只读 GET：`https://linknux.com/` 200（约 0.22 秒）；`https://api.linknux.com/auth/login` 405 且 `Allow: POST`（约 0.41 秒）；`/public/models?size=1` 200（约 0.84 秒）；`/actuator/health` 403（约 0.23 秒）。首页/API 均保留 HSTS `max-age=86400`；首页为 CSP **Report-Only**，未启用强制 CSP。匿名冒烟只能证明发布后公开端点及部分安全头正常，**不能证实生产成员暂停后 Key 失效或真实钱包结算**。

总体仍未完成线上全链路安全验收：需要专用低价值账户、可控邮箱、两个独立真实出口、支付/OAuth 沙箱和可回滚测试订单；HSTS 提升及前端强制 CSP 仍需兼容性与回滚验证。不得把本地回归或匿名线上冒烟描述为全部测试完成。私有报告不得进入公开 PR。

### 暂停成员额度被遗漏的资金支撑检查（UTC 2026-09-27 19:59:05；东京当地 9 月 28 日）

**级别：中（企业额度完整性/可用性；未证实实际资金损失）。** 审计发现：成员暂停时，其钱包余额及既有额度仍存在；但 `OrganizationService.allocate` 与 `WalletBalanceService.lockTransferableBalance` 的额度汇总仅计入 `ACTIVE` 成员。本地 H2 测试在修复前分别复现：金库 10000、暂停成员额度 8000 时，另一个成员仍可获分 3000，恢复后两者总额度超过金库；金库 10000、暂停员工额度 4000 时创建下一企业，旧金库被全部转出而非仅转出可用的 6000。此为额度承诺失去资金支撑，并非已证实生产资金被转走或攻击者凭空获得余额；没有在线账户或真实资金测试。已有活跃预留款的防重复分配回归在修复前即通过，因为预留同步扣减企业金库，本轮保留为防回归覆盖，不把它误报为另一个已复现漏洞。

两处汇总改为计入所有未移除成员（包括暂停），但仍要求钱包有效；恢复/移除行为保持原权限控制。回归同时验证超过上限被拒、剩余 2000 可正常分配、恢复后总额度不超过金库、新建企业仅迁移未承诺资金。修复后完整后端 `mvnw.cmd -q verify` 为 **379 tests、0 failures、0 errors、5 skipped**；前端分页守卫 4/4、`npm run i18n:check` 通过。未改业务列表，分页清单无需变更。

PR #50 文件清单仅为 `OrganizationService.java`、`WalletBalanceService.java`、`OrganizationWalletTransferIntegrationTests.java`，两套 PR 前后端 CI 均通过，合并提交 `0efc91a91d4709d422bb8891915a63381b2ca281`。`master` 工作流 `36346131825` 的 backend、frontend、deploy 均成功，发布 SHA 一致。发布后低频匿名 GET：网站首页 200、API 登录 405/`Allow: POST`、公开模型 200、Actuator health 403；首页/API HSTS 均为 `max-age=86400`，首页 CSP 仍为 **Report-Only**。这些只读冒烟不验证生产账户的暂停/恢复、额度或资金守恒，不能替代沙箱端到端测试。

**未完成事项不变**：专用低价值账户、受控邮箱、两个独立真实出口、支付/OAuth 沙箱及可回滚测试订单尚不可用；因此仍不能宣称所有线上安全测试完成。提升 HSTS 至数月或启用强制前端 CSP 前，应进行真实业务兼容和回滚验收。本报告保持私有，不随 PR 发布。

## 佣金/返利同步发布核验（UTC 2026-09-27 20:27；东京 2026-09-28 05:27）

- 原 H2 回归证实代理返利只改 `users.balance`，个人 `wallet_accounts.balance` 未同步。修复统一调用 `WalletBalanceService.credit()`；佣金转余额新增 ACTIVE 用户锁、同业务键的用户/金额校验、先判幂等再判余额，重复业务键冲突不再误报成功。
- PR #51 已 squash 合并为 `7d6dff56adcf25da35375b1f7c67ad148315c2b2`。GitHub Actions run `36347881841` 的 backend/frontend/deploy 均 success，deploy 完成于 UTC 2026-09-27 20:27:32。
- 上轮本地全量为 380 tests、0 failures/errors、5 skipped；本轮将新成片修复分支更新至包含 #51 的 master 后再次全量验证，结果见下节。
- 这证实发布流程成功，不等同于以真实生产账户复现并验证返利/提现/支付资金链路。本轮没有触发生产返利、佣金提现或真实支付。跨服务锁序与 MySQL 并发场景仍需独立验收。

## 新发现与候选修复：自动成片预留未同步钱包（UTC 2026-09-28 01:08）

**优先级：高（资金一致性，仓库/H2 证实；未在生产利用）。** 旧 `AutoMovieService.reserve/settle/releaseOpenReservations` 只修改 `users.balance`，没有同步钱包与冻结金额；异步 worker 调用的私有结算/释放方法没有独立事务代理。原失败回归：初始用户与个人金库各 20000，脚本预留 10000 后用户为 10000、钱包仍为 20000（预期 10000）。这可能使创作计费与钱包消费的资金视图分离；没有用真实账户在线验证可重复消费金额，因此不量化实际损失。

**候选变更（尚未上线）：**

1. 新 `CreativeBillingService`，预留、结算、退款均通过独立 Spring bean 的事务入口；锁定预留记录，条件变更状态，重复退款不再次增加余额。
2. 为 `creative_billing_reservations` 增加 nullable `wallet_account_id` / `funding_wallet_account_id`，通过现有 SchemaRepair 兼容旧表；退款使用原资金来源，不按退款时默认组织重新选择。
3. 个人/企业 owner 使用自有金库；企业员工同时冻结 allocation 与企业 treasury。员工 allocation 不写成个人余额镜像。无默认组织的 owner 仍选择自有金库，而非绕过钱包写用户余额。
4. 依据数据库用户状态拒绝停用账户的新扣款；原金库、账户或成员暂停后仍允许退回原钱包。删除非运行中的项目前释放剩余预留。
5. 历史金库与用户镜像不一致时拒绝新扣款（409，需对账），不静默用旧钱包覆盖历史用户扣款。旧无钱包 ID 的 reservation 仅保留原 users-only 退款兼容行为，**不是历史账迁移或全量对账完成**。
6. 锁钱包后再锁用户镜像；判断当前镜像金库时不再锁无关钱包，减少跨金库退款/充值锁序交叉。尚未据此宣称所有 MySQL 并发路径已验证。

**提交与发布状态：** 功能提交 `a593196b`，合并最新 master 后分支 HEAD `96d54f00`；PR #52（`codex/creative-billing-funding`）为 **draft/open**，未合并、未生产部署。报告、运行日志与凭据不在提交内。本次不修改业务集合接口或 UI 列表，分页清单无需新增条目；现有前端分页守卫随全量单测通过。

### 本轮验证

- `mvnw.cmd -q verify` 退出码 0：94 suites、392 tests、0 failures、0 errors、5 skipped（实际执行 387）。
- `AutoMovieBillingIntegrationTests` **12/12**：个人预留与取消；企业双钱包部分结算/暂停成员退款；历史不一致拒绝扣款；停用账户拒绝扣款；暂停金库/owner 退款镜像；个人 full/partial settlement 幂等；失败项目删除释放；legacy reservation 退款；企业 treasury 不足时 member 先扣部分事务回滚；无默认组织 owner；组织切换原路退款；两个并发退款只执行一次。
- 前端单测 **100/100、20 files**，含 `listPagination.test.ts`；国际化检查覆盖 **3837** 条；生产构建成功。本轮未重新跑完整浏览器 E2E，先前 29/29 仅属先前时间的结果。
- 5 个跳过测试：`VmCardProductCodeLiveDatabaseTests.synchronizesProviderProductsIntoTheConfiguredMysqlDatabase`，以及 `VmCardSandboxLiveTests` 的 credit-line discovery、prepaid lifecycle、existing card detail、sandbox account/products discovery。需要外部 MySQL/沙箱配置，不应称这些已通过。
- CI 最终核验：push run `36364704879` 与 PR run `36364736400` 的 backend/frontend 均通过；两组 deploy 均为 skipped（非 master，符合草稿修复未部署的预期）。

### 上线前剩余风险与门槛

- 已有 users-only 创作扣款、旧未完成预留与余额差异，需要在生产只读盘点、备份后制定可回滚对账方案；不能把一个镜像字段直接当权威覆盖另一方。
- 取消与已进入外部 provider 的 worker 竞态尚未完成专项回归：本补丁的并发退款测试只证明重复退款幂等，不证明取消后外部成本不会继续产生、项目状态不会被晚到 worker 改写。
- `settleVideo` 使用项目所有成功镜头时长计算当次 stage 成本；重试可能包含既往成功镜头，需绑定当前计费批次验证。仍属静态代码风险线索，不能声称已经利用/已经修复。
- 生产 MySQL 的行锁、隔离级别、schema 兼容与跨服务并发未实测。实际 provider 费用、支付/OAuth、新 IP 双出口验收需低价值专用账户、受控邮箱、两个出口和沙箱/回滚条件。
- 因上述门槛，#52 暂不合并/部署。不得以本地 H2/前端单测通过宣称全站资金链路安全。

## 最新匿名线上只读复测（UTC 2026-09-28 01:00；东京 10:00）

仅执行 4 个低频 GET，无登录、写操作、压力或支付：

| 请求 | 状态/观察 | 解释 |
|---|---|---|
| `https://linknux.com/` | 200；HSTS `max-age=86400`；`nosniff`；CSP Report-Only | 首页可达；浏览器 CSP 仍只报告、不强制 |
| `https://api.linknux.com/auth/login` | 405；`Allow: POST` | 登录错误方法状态修复仍有效 |
| `https://api.linknux.com/admin/api` | 401 | 此匿名管理请求被拒绝，不代表全部管理权限路径已验收 |
| `https://api.linknux.com/actuator/health` | 403 | 公网访问被拒绝；不据此断言后端内部健康或完整授权无缺陷 |

没有将前端 `/api/health` 的 SPA fallback 当作后端健康证据。本轮只读结果不能验证 #51 的真实返利行为，更不能验证未部署的 #52。
