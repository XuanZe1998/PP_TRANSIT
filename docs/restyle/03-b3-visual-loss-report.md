# B3 视觉损失清单（供架构师修订与工程师补回）

> 由主理人脚本实测生成。只读诊断，未修改任何文件。

## 一、总量

| 项 | 数量 |
| --- | --- |
| 改造前 `style.css` 中有视觉声明的 class | 457 |
| 改造后仍在某处保留视觉声明的 class | 220 |
| **孤儿类（视觉声明全部消失）** | **237** |
| 其中「被模板引用」且非 EP 前缀（**需补回**） | **124** |
| 其中「未被模板引用」或 EP 前缀（可判定为死类） | 113 |

## 二、需补回的类（按归属页面分组）

每条给出：`class` — 改造前 `style.css` 中的视觉声明原文（含选择器）

### components\DeveloperDocs.vue

- **`.docs-code-head`**
  - `.developer-docs header h2,
.docs-code-head strong,
.docs-checklist b` → `color: #edf5ff !important;`
  - `#app .developer-docs header h2,
#app .docs-code-head strong,
#app .docs-checklist b` → `color: var(--ui-text) !important;`


### components\PagedTable.vue

- **`.paged-table`**
  - `.el-table,
.el-table__expanded-cell,
.paged-table,
.admin-table-shell` → `--el-table-bg-color: transparent; --el-table-tr-bg-color: rgba(9, 21, 45, 0.54); --el-table-header-bg-color: rgba(22, 44, 82, 0.9); --el-table-row-hover-bg-color: rgba(39, 79, 148, 0.34); --el-table-border-color: rgba(98, 137, 208, 0.18); --el-table-text-color: #b6c5d9; --el-table-header-text-color: #9db0cc; color: #b6c5d9 !important; background: transparent !important;`
  - `#app .el-table,
#app .el-table__expanded-cell,
#app .paged-table,
#app .admin-table-shell` → `--el-table-bg-color: #ffffff; --el-table-tr-bg-color: #ffffff; --el-table-header-bg-color: #eef5ff; --el-table-row-hover-bg-color: #f4f8fd; --el-table-border-color: #dce6f2; --el-table-text-color: #294766; --el-table-header-text-color: #385270; color: #294766 !important; background: #ffffff !important;`


### components\ProfileCenter.vue

- **`.oauth-binding`**
  - `.quick-action,
.activity-list > div,
.session-list > div,
.oauth-binding,
.access-item,
.response-content,
.playground-raw-collapse,
.playground-usage-summary` → `border-color: rgba(100, 141, 215, .2) !important; background: rgba(9, 23, 50, .7) !important; color: #c0cee1 !important;`
  - `#app .user-console-page .sidebar-balance,
#app .user-console-page .console-hero,
#app .admin-table-shell,
#app .security-card,
#app .settings-card,
#app .report-card,
#app .quick-action,
#app .activity-list > div,
#app .session-list > div,
#app .oauth-binding,
#app .access-item,
#app .response-content,
#app .playground-raw-collapse,
#app .playground-usage-summary` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important;`


### layouts\PublicLayout.vue

- **`.brand-image`**
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`

- **`.site-brand-mark`**
  - `.site-brand-mark` → `display: grid; width: 34px; height: 34px; place-items: center; border-radius: 8px; background: #0f172a; color: #ffffff; font-size: 15px;`
  - `.public-site.home-theme .site-brand-mark` → `width: 36px; height: 36px; border-radius: 10px; background: rgba(255, 255, 255, 0.98); box-shadow: 0 0 24px rgba(37, 181, 255, 0.22);`
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`
  - `.public-site.home-theme .site-brand-mark` → `width: 38px; height: 38px; padding: 0; border-radius: 0; background: transparent !important; box-shadow: none;`


### views\AdminConsole.vue

- **`.channel-ledger-table`**
  - `.channel-ledger-table` → `margin-top: 8px; border: 1px solid #e2e8f0; border-radius: 8px;`

- **`.channel-ledger-title`**
  - `.channel-ledger-title h3` → `margin: 0; color: #1e293b; font-size: 16px;`
  - `.channel-ledger-title span` → `display: block; margin-top: 4px; color: #64748b; font-size: 12px; line-height: 1.5;`
  - `#app .admin-shell .brand-title,
#app .admin-shell .admin-header h1,
#app .admin-shell h2,
#app .admin-shell h3,
#app .admin-shell h4,
#app .admin-shell .panel-title,
#app .admin-shell .channel-ledger-title,
#app .admin-shell .price-group-head,
#app .admin-shell .report-title` → `color: var(--admin-text) !important; text-shadow: none !important;`

- **`.model-filter-bar`**
  - `.model-filter-bar` → `display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-bottom: 14px; color: #64748b;`

- **`.module-error-state`**
  - `.overview-inline-error,
.module-error-state` → `display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; padding: 11px 13px; border: 1px solid #fed7aa; border-radius: 8px; background: #fff7ed; color: #9a3412; font-size: 13px;`

- **`.tag-row`**
  - `.metric-card em,
.tag-row span` → `display: inline-flex; align-items: center; min-height: 24px; padding: 3px 10px; border-radius: 999px; font-style: normal; font-size: 12px; font-weight: 600;`
  - `.tone-blue,
.tag-row span` → `color: #0566f2; background: rgba(5, 102, 242, 0.1);`

- **`.test-io`**
  - `.code-panel,
pre,
.developer-docs > pre,
.test-io,
.playground-answer-scroll,
.playground-raw-collapse` → `border-color: var(--tech-line) !important; background: #f8fafc !important; color: #1e3a5f !important; box-shadow: none !important;`
  - `#app pre,
#app .code-panel,
#app .developer-docs > pre,
#app .test-io,
#app .playground-answer-scroll,
#app .playground-raw-collapse` → `border: 1px solid var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: #1e3a5f !important; box-shadow: none !important;`

- **`.test-result-grid`**
  - `.test-result-grid div` → `display: grid; gap: 4px; padding: 10px; border: 1px solid #e2e8f0; border-radius: 6px; background: #fff;`
  - `.test-result-grid span` → `color: #64748b; font-size: 12px;`
  - `.test-result-grid strong` → `color: #0f172a;`

- **`.test-result-panel`**
  - `.test-result-panel` → `margin-top: 18px; padding: 16px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.service-card,
.market-card,
.market-filter-panel,
.market-toolbar,
.market-guide,
.docs-shell,
.docs-sidebar,
.docs-content,
.agent-card,
.composer-card,
.work-card,
.first-time-card,
.stage-card,
.detail-panel,
.tier-panel,
.buy-panel,
.login-panel,
.vmcard-panel,
.form-card,
.test-result-panel,
.credential-pool-card,
.channel-model-pricing-card,
.price-tier-card,
.unit-price-card,
.usage-visual-card,
.el-card` → `border-color: var(--tech-line) !important; background: linear-gradient(145deg, rgba(14, 29, 60, 0.94), rgba(8, 19, 42, 0.92)) !important; color: var(--tech-text) !important; box-shadow: var(--tech-glow) !important; backdrop-filter: blur(14px);`
  - `#app .admin-shell :is(
  .panel,
  .gateway-panel,
  .admin-table-shell,
  .security-card,
  .settings-card,
  .report-card,
  .admin-evolution,
  .config-tabs,
  .form-card,
  .service-card,
  .vmcard-panel,
  .probe-form,
  .credential-pool-card,
  .channel-model-pricing-card,
  .price-tier-card,
  .unit-price-card,
  .usage-visual-card,
  .test-result-panel,
  .el-card
)` → `border-color: var(--admin-line) !important; background: var(--admin-surface) !important; background-image: none !important; color: var(--admin-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`

- **`.tone-green`**
  - `.tone-green` → `color: #059669; background: rgba(16, 185, 129, 0.12);`

- **`.tone-orange`**
  - `.tone-orange` → `color: #ea580c; background: rgba(249, 115, 22, 0.12);`


### views\AdminOtherServices.vue

- **`.price-hint`**
  - `#app .admin-shell .brand-subtitle,
#app .admin-shell .admin-header p,
#app .admin-shell .panel-head p,
#app .admin-shell .heading p,
#app .admin-shell .toolbar p,
#app .admin-shell .field-hint,
#app .admin-shell .form-hint,
#app .admin-shell .form-tip,
#app .admin-shell .price-hint,
#app .admin-shell .security-hint,
#app .admin-shell .muted` → `color: var(--admin-muted) !important;`


### views\AdminProductCommerce.vue

- **`.panel-title`**
  - `#app .admin-shell .brand-title,
#app .admin-shell .admin-header h1,
#app .admin-shell h2,
#app .admin-shell h3,
#app .admin-shell h4,
#app .admin-shell .panel-title,
#app .admin-shell .channel-ledger-title,
#app .admin-shell .price-group-head,
#app .admin-shell .report-title` → `color: var(--admin-text) !important; text-shadow: none !important;`


### views\AgentConsole.vue

- **`.brand-image`**
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`

- **`.site-brand-mark`**
  - `.site-brand-mark` → `display: grid; width: 34px; height: 34px; place-items: center; border-radius: 8px; background: #0f172a; color: #ffffff; font-size: 15px;`
  - `.public-site.home-theme .site-brand-mark` → `width: 36px; height: 36px; border-radius: 10px; background: rgba(255, 255, 255, 0.98); box-shadow: 0 0 24px rgba(37, 181, 255, 0.22);`
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`
  - `.public-site.home-theme .site-brand-mark` → `width: 38px; height: 38px; padding: 0; border-radius: 0; background: transparent !important; box-shadow: none;`


### views\CreativeStudio.vue

- **`.creation-layout`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`

- **`.work-grid`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`


### views\DocsPage.vue

- **`.site-section`**
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section,
.market-page,
.pricing-page,
.other-services-page,
.docs-page` → `color: var(--tech-text);`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`


### views\FlowScreen.vue

- **`.feature-card`**
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card` → `border-color: var(--tech-line) !important; background: var(--tech-surface) !important; box-shadow: var(--tech-glow) !important; backdrop-filter: blur(12px);`
  - `.metric-card,
.feature-card,
.panel` → `border: 1px solid #e2e8f0; border-radius: 8px; background: rgba(255, 255, 255, 0.98); box-shadow: 0 14px 30px rgba(15, 23, 42, 0.06);`
  - `.feature-card h2,
.panel h2` → `margin: 0; color: #0f172a; font-size: 18px;`
  - `.feature-card p` → `margin: 12px 0 18px; color: #64748b; line-height: 1.65;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.service-card,
.market-card,
.market-filter-panel,
.market-toolbar,
.market-guide,
.docs-shell,
.docs-sidebar,
.docs-content,
.agent-card,
.composer-card,
.work-card,
.first-time-card,
.stage-card,
.detail-panel,
.tier-panel,
.buy-panel,
.login-panel,
.vmcard-panel,
.form-card,
.test-result-panel,
.credential-pool-card,
.channel-model-pricing-card,
.price-tier-card,
.unit-price-card,
.usage-visual-card,
.el-card` → `border-color: var(--tech-line) !important; background: linear-gradient(145deg, rgba(14, 29, 60, 0.94), rgba(8, 19, 42, 0.92)) !important; color: var(--tech-text) !important; box-shadow: var(--tech-glow) !important; backdrop-filter: blur(14px);`
  - `.metric-card:hover,
.console-stat:hover,
.market-card:hover,
.pricing-card:hover,
.service-card:hover,
.feature-card:hover` → `border-color: rgba(57, 196, 255, 0.48) !important; box-shadow: 0 18px 48px rgba(0,0,0,.3), 0 0 24px rgba(34, 151, 255, 0.09) !important;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`

- **`.flow-brand`**
  - `.flow-brand strong` → `display: block; color: #ffffff; font-size: 17px;`
  - `.flow-brand span` → `display: block; margin-top: 2px; color: #94a3b8; font-size: 12px;`
  - `.flow-brand strong,
.flow-brand span,
.nav-item,
.health-card span,
.health-card small,
.health-card strong` → `color: var(--tech-text) !important;`

- **`.flow-shell`**
  - `.public-site,
.user-console-page,
.organization-page,
.creative-studio,
.flow-shell,
.admin-shell` → `color: var(--tech-text); background: transparent !important;`
  - `.flow-shell` → `min-height: 100dvh; display: flex; background: #f4f7fb;`
  - `.public-site:not(.home-theme),
.user-console-page,
.organization-page,
.creative-studio,
.flow-shell,
.admin-shell,
.admin-login,
.agent-page,
.legal-page` → `color: var(--tech-text) !important; background: radial-gradient(circle at 94% 0, rgba(69, 74, 194, 0.14), transparent 25%), radial-gradient(circle at 2% 92%, rgba(14, 165, 193, 0.08), transparent 24%), linear-gradient(rgba(55, 105, 181, 0.035) 1px, transparent 1px), linear-gradient(90deg, rgba(55, 105, 181, 0.035) 1px, transparent 1px), #050916 !important; background-size: auto, auto, 52px 52px, 52px 52px, auto !important;`
  - `#app > .public-site,
#app > .user-console-page,
#app > .organization-page,
#app > .creative-studio,
#app > .flow-shell,
#app > .admin-shell,
#app > .admin-login,
#app > .agent-page,
#app > .legal-page` → `position: relative; z-index: 1; background: rgba(3, 7, 19, .34) !important;`
  - `.public-site,
.public-site.home-theme,
.user-console-page,
.organization-page,
.creative-studio,
.flow-shell,
.admin-shell,
.admin-login,
.agent-page,
.legal-page` → `color: var(--tech-text) !important; background: var(--tech-bg) !important; background-image: none !important;`

- **`.flow-sidebar`**
  - `.flow-sidebar` → `width: 248px; min-height: 100dvh; padding: 22px 20px; background: #0e1421; color: #e5edf7; flex-shrink: 0;`
  - `.site-nav,
.studio-nav,
.flow-topbar,
.admin-header,
.agent-header,
.admin-aside,
.flow-sidebar` → `border-color: var(--tech-line) !important; background: #ffffff !important; color: var(--tech-text) !important; box-shadow: 0 4px 16px rgba(51, 75, 105, 0.07) !important; backdrop-filter: none !important;`

- **`.flow-topbar`**
  - `.site-nav,
.studio-nav,
.flow-topbar,
.admin-header` → `border-color: var(--tech-line) !important; background: rgba(249, 252, 255, 0.86) !important; box-shadow: 0 8px 30px rgba(20, 72, 145, 0.06); backdrop-filter: blur(18px);`
  - `.flow-topbar` → `min-height: 92px; display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 22px 32px; background: rgba(255, 255, 255, 0.9); border-bottom: 1px solid #e2e8f0;`
  - `.public-site:not(.home-theme) .site-nav,
.studio-nav,
.flow-topbar,
.admin-header` → `border-color: rgba(105, 143, 215, 0.2) !important; background: rgba(6, 13, 29, 0.82) !important; box-shadow: 0 12px 35px rgba(0, 0, 0, 0.22) !important; backdrop-filter: blur(20px);`
  - `.site-nav,
.studio-nav,
.flow-topbar,
.admin-header,
.agent-header,
.admin-aside,
.flow-sidebar` → `border-color: var(--tech-line) !important; background: #ffffff !important; color: var(--tech-text) !important; box-shadow: 0 4px 16px rgba(51, 75, 105, 0.07) !important; backdrop-filter: none !important;`

- **`.health-card`**
  - `.health-card` → `margin-top: 28px; padding: 16px; border-radius: 8px; background: rgba(255, 255, 255, 0.07);`
  - `.health-card span,
.health-card small` → `display: block; color: #94a3b8; font-size: 12px;`
  - `.health-card strong` → `display: block; margin: 8px 0; color: #ffffff; font-size: 30px;`
  - `.metric-card,
.console-stat,
.services-count,
.health-card,
.account-summary-grid > div,
.report-summary > div,
.gateway-metrics > div,
.vmcard-status-grid > article,
.studio-stat` → `border-color: rgba(98, 144, 230, 0.24) !important; background: linear-gradient(145deg, rgba(20, 42, 82, 0.86), rgba(9, 23, 50, 0.86)) !important; color: var(--tech-text) !important; box-shadow: inset 0 1px rgba(255,255,255,.04), 0 14px 34px rgba(0,0,0,.2) !important;`
  - `.flow-brand strong,
.flow-brand span,
.nav-item,
.health-card span,
.health-card small,
.health-card strong` → `color: var(--tech-text) !important;`

- **`.nav-dot`**
  - `.nav-dot` → `width: 16px; height: 16px; border-radius: 5px; background: #94a3b8; flex-shrink: 0;`
  - `.nav-item.active .nav-dot` → `background: #0ea5e9;`

- **`.nav-item`**
  - `.nav-item` → `width: 100%; min-height: 40px; display: flex; align-items: center; gap: 10px; padding: 9px 12px; border: 0; border-radius: 6px; background: transparent; color: #cbd5e1; text-align: left; cursor: pointer;`
  - `.nav-item:hover,
.nav-item.active` → `background: rgba(5, 102, 242, 0.2); color: #ffffff;`
  - `.nav-item.active .nav-dot` → `background: #0ea5e9;`
  - `.flow-brand strong,
.flow-brand span,
.nav-item,
.health-card span,
.health-card small,
.health-card strong` → `color: var(--tech-text) !important;`
  - `.nav-item:hover,
.nav-item.active,
.public-site:not(.home-theme) .site-links button:hover,
.public-site:not(.home-theme) .site-links button.active,
.creative-studio .mode-rail > button:hover,
.creative-studio .mode-rail > button.active,
.market-filter-options button:hover,
.market-filter-options button.active` → `background: #eaf2ff !important; background-image: none !important; color: #174b8f !important;`

- **`.tag-row`**
  - `.metric-card em,
.tag-row span` → `display: inline-flex; align-items: center; min-height: 24px; padding: 3px 10px; border-radius: 999px; font-style: normal; font-size: 12px; font-weight: 600;`
  - `.tone-blue,
.tag-row span` → `color: #0566f2; background: rgba(5, 102, 242, 0.1);`

- **`.topbar-copy`**
  - `.topbar-copy h1` → `margin: 0; color: #0f172a; font-size: 26px; line-height: 1.18;`
  - `.topbar-copy p` → `max-width: 760px; margin: 8px 0 0; color: #64748b; font-size: 13px;`


### views\HomePage.vue

- **`.constellation`**
  - `.constellation` → `position: absolute; padding-left: 42px; color: #93afd2; font-size: 12px;`
  - `.constellation::before` → `left: 0; top: 50%; width: 29px; height: 29px; border: 1px solid rgba(71, 177, 255, .78); transform: translateY(-50%) rotate(30deg); box-shadow: 0 0 15px rgba(62, 167, 255, .3), inset 0 0 12px rgba(57, 108, 255, .22);`
  - `.constellation::after` → `left: 13px; top: 50%; width: 5px; height: 5px; border-radius: 50%; background: #64e5ff; box-shadow: 0 0 10px #46cfff;`

- **`.grid-icon`**
  - `.grid-icon i` → `width: 5px; height: 5px; border: 1px solid #7ddcff; border-radius: 1px;`

- **`.hero-actions`**
  - `.public-site.home-theme .hero-actions .el-button` → `min-height: 54px; margin-left: 0; padding: 0 25px; border-radius: 10px; font-weight: 700;`
  - `.public-site.home-theme .hero-actions .hero-primary` → `border-color: #3d78ff !important; background: linear-gradient(135deg, #29baff, #4056ff) !important; color: #fff !important; box-shadow: 0 12px 36px rgba(42, 104, 255, 0.34), inset 0 1px rgba(255, 255, 255, 0.24) !important;`
  - `.public-site.home-theme .hero-actions .hero-primary:hover` → `transform: translateY(-1px); background: linear-gradient(135deg, #42ceff, #5265ff) !important; box-shadow: 0 16px 42px rgba(42, 104, 255, 0.42) !important;`
  - `.public-site.home-theme .hero-actions .hero-primary > span` → `margin-left: 8px; font-size: 20px;`
  - `.public-site.home-theme .hero-actions .hero-secondary` → `border-color: rgba(112, 151, 231, 0.52) !important; background: rgba(8, 16, 37, 0.66) !important; color: #edf4ff !important; box-shadow: inset 0 0 22px rgba(72, 112, 214, 0.06) !important;`
  - `.public-site.home-theme .hero-actions .hero-secondary:hover` → `border-color: #38caff !important; background: rgba(22, 39, 78, 0.76) !important;`
  - `.public-site.home-theme .hero-actions .el-button` → `min-width: 190px; min-height: 56px; border-radius: 12px; font-size: 17px;`
  - `#app .public-site.home-theme .site-auth-button,
#app .public-site.home-theme .hero-actions button.el-button.hero-secondary,
#app .el-button:not(.el-button--primary):not(.el-button--success):not(.el-button--warning):not(.el-button--danger):not(.is-link)` → `border-color: var(--tech-line) !important; background: #ffffff !important; color: #294766 !important; box-shadow: none !important;`

- **`.hero-atmosphere`**
  - `.hero-atmosphere .orbit` → `position: absolute; display: block; border: 1px solid rgba(61, 115, 255, 0.36); border-radius: 50%; transform: rotate(-10deg);`
  - `.hero-atmosphere .signal` → `position: absolute; width: 7px; height: 7px; border-radius: 50%; background: #39cfff; box-shadow: 0 0 18px 5px rgba(35, 168, 255, 0.38);`
  - `.hero-atmosphere .signal-two` → `top: 12%; right: 18%; background: #606cff;`
  - `.hero-atmosphere .orbit` → `opacity: 0.45;`
  - `.public-site.home-theme .site-hero::before,
.public-site.home-theme .site-hero::after,
.hero-atmosphere,
.model-constellations` → `display: none !important; background: none !important; content: none !important;`

- **`.hero-feature-grid`**
  - `.hero-feature-grid article` → `display: flex; min-width: 0; min-height: 76px; align-items: center; gap: 10px; padding: 13px 12px; border: 1px solid rgba(77, 143, 230, .38); border-radius: 11px; background: linear-gradient(145deg, rgba(13, 31, 62, .88), rgba(6, 18, 39, .78)); box-shadow: inset 0 1px rgba(255,255,255,.035), 0 12px 30px rgba(0,0,0,.2); backdrop-filter: blur(11px);`
  - `.hero-feature-grid article > b` → `color: #43cfff; font-size: 27px; font-weight: 500; text-shadow: 0 0 14px rgba(48, 197, 255, .5);`
  - `.hero-feature-grid article strong` → `color: #e8f2ff; font-size: 14px; white-space: nowrap;`
  - `.hero-feature-grid article small` → `color: #7185a6; font-size: 10px; white-space: nowrap;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`
  - `.public-site.home-theme .hero-feature-grid article strong,
.public-site.home-theme .product-toolbar strong,
.public-site.home-theme .product-card strong,
.public-site.home-theme .route-list strong` → `color: var(--tech-text) !important;`
  - `.public-site.home-theme .hero-feature-grid article small,
.public-site.home-theme .product-toolbar small,
.public-site.home-theme .product-card small,
.public-site.home-theme .route-list small,
.public-site.home-theme .hero-footnote p,
.public-site.home-theme .hero-footnote small` → `color: var(--tech-muted) !important;`

- **`.hero-footnote`**
  - `.hero-footnote` → `position: absolute; z-index: 2; left: 42px; bottom: 34px; display: flex; align-items: flex-start; gap: 14px; color: #a5b7d4; font-size: 11px;`
  - `.hero-footnote > span` → `width: 27px; height: 1px; margin-top: 7px; background: #4489ff;`
  - `.hero-footnote small` → `display: block; margin-top: 8px; color: #506281; font-family: "Cascadia Code", Consolas, monospace; font-size: 8px; letter-spacing: 0.3em;`
  - `.public-site.home-theme .hero-feature-grid article small,
.public-site.home-theme .product-toolbar small,
.public-site.home-theme .product-card small,
.public-site.home-theme .route-list small,
.public-site.home-theme .hero-footnote p,
.public-site.home-theme .hero-footnote small` → `color: var(--tech-muted) !important;`

- **`.hero-primary`**
  - `.public-site.home-theme .hero-actions .hero-primary` → `border-color: #3d78ff !important; background: linear-gradient(135deg, #29baff, #4056ff) !important; color: #fff !important; box-shadow: 0 12px 36px rgba(42, 104, 255, 0.34), inset 0 1px rgba(255, 255, 255, 0.24) !important;`
  - `.public-site.home-theme .hero-actions .hero-primary:hover` → `transform: translateY(-1px); background: linear-gradient(135deg, #42ceff, #5265ff) !important; box-shadow: 0 16px 42px rgba(42, 104, 255, 0.42) !important;`
  - `.public-site.home-theme .hero-actions .hero-primary > span` → `margin-left: 8px; font-size: 20px;`

- **`.hero-product`**
  - `.hero-product` → `overflow: hidden; border: 1px solid #c8dff7; border-radius: 8px; background: linear-gradient(rgba(41, 112, 198, 0.06) 1px, transparent 1px), linear-gradient(90deg, rgba(41, 112, 198, 0.06) 1px, transparent 1px), radial-gradient(circle at 82% 4%, rgba(0, 174, 214, 0.1), transparent 38%), #f4f9ff; background-size: 24px 24px, 24px 24px, auto, auto; box-shadow: 0 24px 60px rgba(35, 91, 158, 0.12), 0 0 0 1px rgba(77, 179, 255, 0.06) inset;`
  - `.public-site.home-theme .hero-product` → `position: relative; z-index: 3; overflow: visible; border: 1px solid rgba(102, 141, 255, 0.68); border-radius: 20px; background: linear-gradient(rgba(67, 113, 203, 0.045) 1px, transparent 1px), linear-gradient(90deg, rgba(67, 113, 203, 0.045) 1px, transparent 1px), linear-gradient(145deg, rgba(20, 39, 81, 0.9), rgba(5, 15, 37, 0.94)); background-size: 32px 32px, 32px 32px, auto; box-shadow: 0 40px 90px rgba(0, 0, 0, 0.48), 0 0 40px rgba(39, 84, 224, 0.12), inset 0 1px rgba(255, 255, 255, 0.08); backdrop-filter: blur(22px); transform: perspective(1400px) rotateY(-1.8deg);`
  - `.public-site.home-theme .hero-product::before` → `position: absolute; z-index: -1; inset: -1px; border-radius: inherit; background: linear-gradient(120deg, rgba(53, 216, 255, 0.12), transparent 38%, rgba(108, 87, 255, 0.18)); content: '';`
  - `.public-site.home-theme .hero-product` → `z-index: 5; width: 100%; max-width: 700px; min-width: 0; padding: 0; border-radius: 19px; border-color: rgba(65, 141, 242, .72); background: linear-gradient(145deg, rgba(9, 28, 59, .96), rgba(4, 16, 37, .94)); box-shadow: 0 30px 90px rgba(0,0,0,.46), 0 0 45px rgba(42, 124, 245, .13), inset 0 1px rgba(156, 213, 255, .08);`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`

- **`.hero-promise`**
  - `.public-site.home-theme .hero-copy > p.hero-promise` → `margin-top: 8px; color: #d8e5f8; font-size: 15px; font-weight: 650; letter-spacing: .01em;`

- **`.hero-secondary`**
  - `.public-site.home-theme .hero-actions .hero-secondary` → `border-color: rgba(112, 151, 231, 0.52) !important; background: rgba(8, 16, 37, 0.66) !important; color: #edf4ff !important; box-shadow: inset 0 0 22px rgba(72, 112, 214, 0.06) !important;`
  - `.public-site.home-theme .hero-actions .hero-secondary:hover` → `border-color: #38caff !important; background: rgba(22, 39, 78, 0.76) !important;`
  - `#app .public-site.home-theme .site-auth-button,
#app .public-site.home-theme .hero-actions button.el-button.hero-secondary,
#app .el-button:not(.el-button--primary):not(.el-button--success):not(.el-button--warning):not(.el-button--danger):not(.is-link)` → `border-color: var(--tech-line) !important; background: #ffffff !important; color: #294766 !important; box-shadow: none !important;`

- **`.model-constellations`**
  - `.public-site.home-theme .site-hero::before,
.public-site.home-theme .site-hero::after,
.hero-atmosphere,
.model-constellations` → `display: none !important; background: none !important; content: none !important;`

- **`.orbit`**
  - `.hero-atmosphere .orbit` → `position: absolute; display: block; border: 1px solid rgba(61, 115, 255, 0.36); border-radius: 50%; transform: rotate(-10deg);`
  - `.hero-atmosphere .orbit` → `opacity: 0.45;`

- **`.output-stack`**
  - `.source-stack article, .output-stack article` → `display: flex; height: 43px; align-items: center; gap: 10px; padding: 0 11px; border: 1px solid rgba(77, 135, 213, .32); border-radius: 8px; background: rgba(13, 35, 70, .9); box-shadow: inset 0 1px rgba(255,255,255,.025);`
  - `.source-stack strong, .output-stack strong` → `color: #e6effd; font-size: 12px;`
  - `.source-stack i, .output-stack i` → `width: 7px; height: 7px; margin-left: auto; border-radius: 50%; background: #39e99c; box-shadow: 0 0 10px #39e99c; animation: route-status-pulse 2s ease-in-out infinite;`
  - `.output-stack article > b` → `width: 25px; color: #d9ebff; font-size: 14px;`
  - `.source-stack strong, .output-stack strong` → `font-size: 10px;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.routing-lines path,
.source-stack i,
.output-stack i,
.routing-chart i,
.market-status-dot,
.provider-pill i` → `filter: none !important; box-shadow: none !important;`

- **`.product-logo`**
  - `.public-site.home-theme .product-toolbar .product-logo` → `display: grid; width: 32px; height: 32px; place-items: center; border-radius: 9px; background: linear-gradient(145deg, #2e8dff, #554fff); color: #fff; box-shadow: 0 0 22px rgba(63, 111, 255, 0.38);`

- **`.product-toolbar`**
  - `.product-toolbar` → `display: flex; align-items: center; gap: 8px; min-height: 48px; padding: 0 18px; border-bottom: 1px solid #d5e6f8; background: rgba(235, 245, 255, 0.92);`
  - `.product-toolbar span` → `width: 10px; height: 10px; border-radius: 50%; background: #64748b;`
  - `.product-toolbar strong` → `margin-left: 8px; color: #173b67; font-size: 13px;`
  - `.public-site.home-theme .product-toolbar` → `min-height: 68px; padding: 0 24px; border-bottom-color: rgba(111, 150, 224, 0.17); border-radius: 20px 20px 0 0; background: rgba(9, 19, 43, 0.58);`
  - `.public-site.home-theme .product-toolbar .product-logo` → `display: grid; width: 32px; height: 32px; place-items: center; border-radius: 9px; background: linear-gradient(145deg, #2e8dff, #554fff); color: #fff; box-shadow: 0 0 22px rgba(63, 111, 255, 0.38);`
  - `.public-site.home-theme .product-toolbar strong` → `margin-left: 2px; color: #edf5ff; font-size: 16px;`
  - `.public-site.home-theme .product-toolbar em` → `display: inline-flex; align-items: center; gap: 6px; margin-left: 5px; padding: 5px 8px; border: 1px solid rgba(55, 227, 184, 0.16); border-radius: 999px; background: rgba(19, 130, 110, 0.12); color: #45e4bb; font-size: 10px; font-style: normal; font-weight: 800;`
  - `.public-site.home-theme .product-toolbar em i,
.public-site.home-theme .route-list header b i,
.public-site.home-theme .route-list > div > b i` → `display: inline-block; width: 6px; height: 6px; border-radius: 50%; background: #35e7b1; box-shadow: 0 0 9px #35e7b1;`
  - `.public-site.home-theme .product-toolbar small` → `margin-left: auto; color: #7589aa; font-size: 10px;`
  - `#app .public-site.home-theme .product-toolbar em` → `background: #e8f8f1 !important; color: #08734f !important;`
  - `#app .public-site.home-theme .product-toolbar button > span` → `width: auto; height: auto; border-radius: 0; background: transparent !important; color: inherit !important;`
  - `.public-site.home-theme .product-toolbar` → `min-height: 78px; padding: 0 24px; border-bottom: 1px solid rgba(85, 138, 218, .23); background: rgba(10, 30, 62, .58);`
  - `.public-site.home-theme .product-toolbar button` → `margin-left: auto; padding: 10px 15px; border: 1px solid rgba(91, 158, 244, .55); border-radius: 8px; background: rgba(10, 27, 57, .75); color: #bed1e9; cursor: pointer;`
  - `.public-site.home-theme .product-toolbar button:hover` → `border-color: #45d8ff; color: #fff;`
  - `.public-site.home-theme .site-nav,
.public-site:not(.home-theme) .site-nav,
.public-site.home-theme .product-toolbar,
.public-site.home-theme .product-toolbar button,
.creative-studio .back-main-button,
.el-dialog,
.el-drawer,
.el-popper,
.el-message-box` → `border-color: var(--tech-line) !important; background: #ffffff !important; color: var(--tech-text) !important; box-shadow: 0 10px 30px rgba(51, 75, 105, 0.12) !important; backdrop-filter: none !important;`
  - `.public-site.home-theme .hero-feature-grid article strong,
.public-site.home-theme .product-toolbar strong,
.public-site.home-theme .product-card strong,
.public-site.home-theme .route-list strong` → `color: var(--tech-text) !important;`
  - `.public-site.home-theme .hero-feature-grid article small,
.public-site.home-theme .product-toolbar small,
.public-site.home-theme .product-card small,
.public-site.home-theme .route-list small,
.public-site.home-theme .hero-footnote p,
.public-site.home-theme .hero-footnote small` → `color: var(--tech-muted) !important;`

- **`.routing-core`**
  - `.routing-core span` → `display: grid; width: 88px; height: 88px; place-items: center; border: 2px solid #42cfff; border-radius: 18px; background: radial-gradient(circle, rgba(37, 119, 231, .6), rgba(5, 21, 48, .96)); color: #36cfff; font-size: 48px; text-shadow: 0 0 19px rgba(49, 213, 255, .9); box-shadow: 0 0 30px rgba(49, 164, 255, .34), inset 0 0 22px rgba(59, 118, 255, .26); animation: routing-core-glow 2.8s ease-in-out infinite;`
  - `.routing-core strong` → `color: #a9c1de; font-size: 13px;`
  - `.routing-core span` → `width: 62px; height: 62px; border-radius: 14px; font-size: 34px;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.routing-core span` → `border-color: #93c5fd !important; background: #eff6ff !important; color: #2563eb !important; box-shadow: none !important; text-shadow: none !important;`

- **`.routing-lines`**
  - `.routing-lines path` → `fill: none; stroke: rgba(55, 194, 255, .84); stroke-width: 1.35; stroke-dasharray: 7 7; filter: drop-shadow(0 0 5px rgba(40, 177, 255, .48)); animation: topology-flow 5s linear infinite;`
  - `.routing-lines path,
.source-stack i,
.output-stack i,
.routing-chart i,
.market-status-dot,
.provider-pill i` → `filter: none !important; box-shadow: none !important;`

- **`.routing-stats`**
  - `.routing-stats` → `display: grid; grid-template-columns: 110px 110px 125px minmax(120px, 1fr); min-height: 84px; margin: 0 22px 20px; align-items: center; border: 1px solid rgba(68, 126, 203, .26); border-radius: 10px; background: rgba(7, 23, 49, .78);`
  - `.routing-stats strong` → `color: #f0f6ff; font-size: 18px;`
  - `.routing-stats small` → `color: #7185a4; font-size: 10px;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`

- **`.signal`**
  - `.hero-atmosphere .signal` → `position: absolute; width: 7px; height: 7px; border-radius: 50%; background: #39cfff; box-shadow: 0 0 18px 5px rgba(35, 168, 255, 0.38);`

- **`.signal-two`**
  - `.hero-atmosphere .signal-two` → `top: 12%; right: 18%; background: #606cff;`

- **`.site-hero`**
  - `.public-site.home-theme .site-hero::after` → `position: absolute; z-index: -2; width: 620px; height: 620px; top: 14%; right: -10%; border-radius: 50%; background: rgba(44, 86, 255, 0.08); filter: blur(80px); content: '';`
  - `.public-site.home-theme .site-hero::before,
.public-site.home-theme .site-hero::after,
.hero-atmosphere,
.model-constellations` → `display: none !important; background: none !important; content: none !important;`

- **`.source-stack`**
  - `.source-stack article, .output-stack article` → `display: flex; height: 43px; align-items: center; gap: 10px; padding: 0 11px; border: 1px solid rgba(77, 135, 213, .32); border-radius: 8px; background: rgba(13, 35, 70, .9); box-shadow: inset 0 1px rgba(255,255,255,.025);`
  - `.source-stack strong, .output-stack strong` → `color: #e6effd; font-size: 12px;`
  - `.source-stack small` → `margin-left: auto; color: #98a9c1; font-size: 10px;`
  - `.source-stack i, .output-stack i` → `width: 7px; height: 7px; margin-left: auto; border-radius: 50%; background: #39e99c; box-shadow: 0 0 10px #39e99c; animation: route-status-pulse 2s ease-in-out infinite;`
  - `.source-stack strong, .output-stack strong` → `font-size: 10px;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`
  - `.routing-lines path,
.source-stack i,
.output-stack i,
.routing-chart i,
.market-status-dot,
.provider-pill i` → `filter: none !important; box-shadow: none !important;`


### views\ModelGateway.vue

- **`.gateway-workbench`**
  - `#app .playground-billing-panel .playground-result-head p,
#app .usage-visual-card header span,
#app .usage-pie small,
#app .usage-kpis span,
#app .usage-chart-toolbar span,
#app .usage-table-title span,
#app .gateway-workbench .heading p,
#app .panel-head small,
#app .organization-page .page-heading p,
#app .agent-card > p,
#app .vmcard-title-row p` → `color: var(--ui-muted) !important;`


### views\ModelMarket.vue

- **`.available`**
  - `.market-badge.available` → `background: #eef7e8; color: #497517;`
  - `#app .market-badge.available,
#app .status-chip.succeeded` → `border-color: #a9ddc8 !important; background: #e8f8f1 !important; color: #08734f !important;`

- **`.hero-actions`**
  - `.public-site.home-theme .hero-actions .el-button` → `min-height: 54px; margin-left: 0; padding: 0 25px; border-radius: 10px; font-weight: 700;`
  - `.public-site.home-theme .hero-actions .hero-primary` → `border-color: #3d78ff !important; background: linear-gradient(135deg, #29baff, #4056ff) !important; color: #fff !important; box-shadow: 0 12px 36px rgba(42, 104, 255, 0.34), inset 0 1px rgba(255, 255, 255, 0.24) !important;`
  - `.public-site.home-theme .hero-actions .hero-primary:hover` → `transform: translateY(-1px); background: linear-gradient(135deg, #42ceff, #5265ff) !important; box-shadow: 0 16px 42px rgba(42, 104, 255, 0.42) !important;`
  - `.public-site.home-theme .hero-actions .hero-primary > span` → `margin-left: 8px; font-size: 20px;`
  - `.public-site.home-theme .hero-actions .hero-secondary` → `border-color: rgba(112, 151, 231, 0.52) !important; background: rgba(8, 16, 37, 0.66) !important; color: #edf4ff !important; box-shadow: inset 0 0 22px rgba(72, 112, 214, 0.06) !important;`
  - `.public-site.home-theme .hero-actions .hero-secondary:hover` → `border-color: #38caff !important; background: rgba(22, 39, 78, 0.76) !important;`
  - `.public-site.home-theme .hero-actions .el-button` → `min-width: 190px; min-height: 56px; border-radius: 12px; font-size: 17px;`
  - `#app .public-site.home-theme .site-auth-button,
#app .public-site.home-theme .hero-actions button.el-button.hero-secondary,
#app .el-button:not(.el-button--primary):not(.el-button--success):not(.el-button--warning):not(.el-button--danger):not(.is-link)` → `border-color: var(--tech-line) !important; background: #ffffff !important; color: #294766 !important; box-shadow: none !important;`

- **`.market-active-filters`**
  - `.market-active-filters` → `display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin: 0 0 14px; color: #69766f; font-size: 12px;`
  - `.market-active-filters button` → `padding: 5px 9px; border: 1px solid #cfe0d5; border-radius: 999px; background: #f3f9f5; color: #285a3e; cursor: pointer;`

- **`.market-badge`**
  - `.market-badge` → `display: inline-flex; min-height: 19px; align-items: center; padding: 1px 6px; border-radius: 5px; font-size: 10px; font-weight: 700;`
  - `.market-badge.available` → `background: #eef7e8; color: #497517;`
  - `.market-badge.nvidia` → `background: #edf4ff; color: #3169a7;`
  - `.market-badge,
.status-chip,
.console-status-note,
.services-count,
.route-chip,
.model-tag` → `border-color: rgba(77, 156, 255, .28) !important; background: rgba(32, 85, 164, .2) !important; color: #80cbff !important;`
  - `#app .el-tag,
#app .market-badge,
#app .console-status-note,
#app .services-count,
#app .route-chip,
#app .model-tag` → `border-color: #bfd5ee !important; background: #eaf2ff !important; color: #174b8f !important;`
  - `#app .market-badge.available,
#app .status-chip.succeeded` → `border-color: #a9ddc8 !important; background: #e8f8f1 !important; color: #08734f !important;`

- **`.market-card-actions`**
  - `.market-card-actions .el-button` → `width: 100%; min-height: 28px; padding: 5px 7px; margin-left: 0; font-size: 11px;`

- **`.market-card-desc`**
  - `.market-card-desc` → `display: -webkit-box; min-height: 29px; overflow: hidden; margin: 4px 0 0; color: #536273; font-size: 11px; line-height: 1.32; -webkit-box-orient: vertical; -webkit-line-clamp: 2;`
  - `small,
.muted,
.metric-card > span,
.console-stat > span,
.market-card-desc,
.service-card-meta,
.card-description` → `color: #8295b5;`

- **`.market-card-details`**
  - `.market-card-details > summary` → `display: flex; min-height: 32px; align-items: center; justify-content: space-between; gap: 10px; padding: 6px 10px; border: 1px solid #d9e5ef; border-radius: 8px; background: #f3f7fb; color: #244a70; cursor: pointer; font-size: 11px; font-weight: 700; list-style: none;`
  - `.market-card-details > summary:focus-visible` → `outline: 2px solid #2d75c7; outline-offset: 2px;`
  - `#app .market-card-details > summary` → `border-color: #c9d9ea !important; background: #eef5fc !important; color: #174b78 !important;`
  - `#app .market-card-details > summary:hover` → `border-color: #9dbddd !important; background: #e3effb !important; color: #0d4f9f !important;`

- **`.market-catalog-shell`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`

- **`.market-details-chevron`**
  - `.market-details-chevron` → `font-size: 16px; line-height: 1; transition: transform 160ms ease;`

- **`.market-empty`**
  - `.market-empty` → `padding: 48px 0; border: 1px solid #e3e8f0; border-radius: 8px; background: #ffffff;`
  - `.market-meta,
.market-card footer,
.market-empty` → `border-color: rgba(98, 139, 212, .18) !important; background: rgba(6, 17, 37, .76) !important;`
  - `.metric-card,
.console-stat,
.services-count,
.market-stats article,
.market-meta,
.market-card footer,
.market-empty,
.docs-endpoints div,
.creative-studio .newbie-guide,
.creative-studio .storyboard-helper,
.creative-studio .storyboard-helper li,
.creative-studio .connection-help,
.creative-studio .prompt-box,
.creative-studio .works-skeleton i,
.service-image-placeholder,
.pricing-disclosure,
.market-error,
.market-fallback-note` → `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;`

- **`.market-fallback-note`**
  - `.market-error,
.market-fallback-note` → `margin-bottom: 14px; padding: 12px 14px; border-radius: 8px; font-size: 14px;`
  - `.market-fallback-note` → `border: 1px solid #bfdbfe; background: #eff6ff; color: #1d4ed8;`
  - `.pricing-disclosure,
.market-error,
.market-fallback-note` → `border: 1px solid rgba(94, 141, 224, .24) !important; background: rgba(15, 34, 69, .74) !important; color: #97aac6 !important;`
  - `.metric-card,
.console-stat,
.services-count,
.market-stats article,
.market-meta,
.market-card footer,
.market-empty,
.docs-endpoints div,
.creative-studio .newbie-guide,
.creative-studio .storyboard-helper,
.creative-studio .storyboard-helper li,
.creative-studio .connection-help,
.creative-studio .prompt-box,
.creative-studio .works-skeleton i,
.service-image-placeholder,
.pricing-disclosure,
.market-error,
.market-fallback-note` → `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;`

- **`.market-filter-group`**
  - `.market-filter-group h3` → `margin: 0 0 10px; color: #455249; font-size: 12px; font-weight: 800; letter-spacing: 0.04em; text-transform: uppercase;`
  - `.market-filter-panel > header h2,
.market-filter-group h3,
.market-availability-row,
.market-filter-options button` → `color: #a9bbd2 !important;`
  - `#app .market-filter-panel h2,
#app .market-filter-group h3,
#app .market-guide h2` → `color: var(--ui-text) !important;`

- **`.market-filter-icon`**
  - `.market-filter-icon` → `display: grid; width: 22px; height: 22px; place-items: center; border-radius: 5px; background: #eef6e7; color: #4f7b1b; font-weight: 800;`
  - `#app .market-filter-icon` → `background: #eaf2ff !important; color: #174b8f !important;`
  - `.market-hero-label img,
.market-filter-icon` → `background: rgba(38, 139, 220, .18) !important; color: #53d9ff !important;`

- **`.market-filter-options`**
  - `.market-availability-row b,
.market-filter-options b` → `color: #89948d; font-size: 12px;`
  - `.market-filter-options button` → `display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 10px; padding: 8px; border: 0; border-radius: 7px; background: transparent; color: #3b4840; cursor: pointer; text-align: left; transition: color 150ms ease, background 150ms ease;`
  - `.market-filter-options button:hover,
.market-filter-options button.active` → `background: #f0f7e9; color: #3f6811;`
  - `.market-filter-options img,
.market-filter-options i` → `width: 20px; height: 20px; flex: 0 0 20px; border-radius: 5px; object-fit: contain;`
  - `.market-filter-options i` → `display: grid; place-items: center; background: #edf1ee; color: #53645a; font-size: 10px; font-style: normal; font-weight: 800;`
  - `.market-filter-options em` → `overflow: hidden; font-size: 13px; font-style: normal; font-weight: 600; text-overflow: ellipsis; white-space: nowrap;`
  - `#app .public-site .site-links button,
#app .market-filter-panel > header button,
#app .market-filter-options button` → `color: #3f536c !important;`
  - `#app .market-filter-panel > header button:disabled,
#app .market-filter-options button:disabled` → `color: #52657e !important;`
  - `.market-filter-panel > header h2,
.market-filter-group h3,
.market-availability-row,
.market-filter-options button` → `color: #a9bbd2 !important;`
  - `.market-filter-panel > header button,
.market-filter-options button:hover,
.market-filter-options button.active` → `color: #50d7ff !important;`
  - `.market-filter-options button:hover,
.market-filter-options button.active` → `background: rgba(40, 91, 170, .24) !important;`
  - `.market-filter-options i` → `background: rgba(28, 57, 105, .66) !important; color: #86a5cc !important;`
  - `.nav-item:hover,
.nav-item.active,
.public-site:not(.home-theme) .site-links button:hover,
.public-site:not(.home-theme) .site-links button.active,
.creative-studio .mode-rail > button:hover,
.creative-studio .mode-rail > button.active,
.market-filter-options button:hover,
.market-filter-options button.active` → `background: #eaf2ff !important; background-image: none !important; color: #174b8f !important;`
  - `#app .market-filter-options button` → `color: #40566f !important;`
  - `#app .market-filter-options button:hover,
#app .market-filter-options button.active` → `border-color: #bfd5ee !important; background: #eaf2ff !important; color: #174b8f !important;`
  - `#app .market-filter-options i` → `background: #e4edf8 !important; color: #385270 !important;`

- **`.market-filter-panel`**
  - `.market-filter-panel` → `position: sticky; top: 82px; align-self: stretch; max-height: none; overflow-x: hidden; overflow-y: auto; border: 1px solid #dfe6e1; border-radius: 12px; background: #ffffff;`
  - `.market-filter-panel > header h2` → `margin: 0; color: #142019; font-size: 16px;`
  - `.market-filter-panel > header button` → `padding: 4px 0; border: 0; background: transparent; color: #4f7b1b; cursor: pointer; font-size: 12px; font-weight: 700;`
  - `.market-filter-panel > header button:disabled` → `color: #b7c0ba; cursor: not-allowed;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.service-card,
.market-card,
.market-filter-panel,
.market-toolbar,
.market-guide,
.docs-shell,
.docs-sidebar,
.docs-content,
.agent-card,
.composer-card,
.work-card,
.first-time-card,
.stage-card,
.detail-panel,
.tier-panel,
.buy-panel,
.login-panel,
.vmcard-panel,
.form-card,
.test-result-panel,
.credential-pool-card,
.channel-model-pricing-card,
.price-tier-card,
.unit-price-card,
.usage-visual-card,
.el-card` → `border-color: var(--tech-line) !important; background: linear-gradient(145deg, rgba(14, 29, 60, 0.94), rgba(8, 19, 42, 0.92)) !important; color: var(--tech-text) !important; box-shadow: var(--tech-glow) !important; backdrop-filter: blur(14px);`
  - `#app .public-site .site-links button,
#app .market-filter-panel > header button,
#app .market-filter-options button` → `color: #3f536c !important;`
  - `#app .market-filter-panel > header button:disabled,
#app .market-filter-options button:disabled` → `color: #52657e !important;`
  - `.market-filter-panel > header h2,
.market-filter-group h3,
.market-availability-row,
.market-filter-options button` → `color: #a9bbd2 !important;`
  - `.market-filter-panel > header button,
.market-filter-options button:hover,
.market-filter-options button.active` → `color: #50d7ff !important;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`
  - `#app .market-hero-label span,
#app .market-filter-panel > header button` → `color: #0d5b9f !important;`
  - `#app .market-filter-panel h2,
#app .market-filter-group h3,
#app .market-guide h2` → `color: var(--ui-text) !important;`

- **`.market-grid`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`

- **`.market-hero`**
  - `.market-hero` → `display: grid; grid-template-columns: minmax(0, 1fr) minmax(360px, 0.68fr); gap: 28px; align-items: center; overflow: hidden; margin-bottom: 18px; padding: 32px; border: 1px solid #1e2a23; border-radius: 14px; background: radial-gradient(circle at 82% 15%, rgba(118, 185, 0, 0.23), transparent 28%), radial-gradient(circle at 8% 100%, rgba(118, 185, 0, 0.08), transparent 34%), linear-gradient(135deg, #0b110d 0%, #111a14 58%, #0a0f0c 100%); box-shadow: 0 24px 54px rgba(8, 20, 12, 0.18);`
  - `.market-hero h1` → `max-width: 720px; margin: 0; color: #ffffff; font-size: 42px; line-height: 1.14; letter-spacing: -0.025em;`
  - `.market-hero p` → `max-width: 700px; margin: 14px 0 0; color: #aab8ae; font-size: 16px; line-height: 1.75;`
  - `.market-hero h1` → `font-size: 32px;`
  - `.market-hero` → `border-color: rgba(70, 140, 240, .3) !important; background: radial-gradient(circle at 84% 8%, rgba(46, 117, 255, .25), transparent 31%), radial-gradient(circle at 10% 100%, rgba(34, 211, 238, .11), transparent 37%), linear-gradient(135deg, #08152f 0%, #0b1c3b 56%, #080f25 100%) !important; box-shadow: 0 24px 58px rgba(0,0,0,.32), inset 0 1px rgba(255,255,255,.035) !important;`
  - `.panel,
.console-panel,
.gateway-panel,
.pricing-card,
.model-tile,
.feature-card,
.other-service-card,
.el-card,
.market-hero,
.market-filter-panel,
.market-card,
.developer-docs,
.model-sale-pricing,
.sale-price-grid article,
.unit-sale-price,
.price-tier,
.pricing-pending,
.pricing-free,
.context-price-note,
.creative-studio .mode-rail,
.creative-studio .composer-card,
.creative-studio .template-grid > button,
.creative-studio .login-empty,
.creative-studio .no-works,
.creative-studio .connection-list article,
.public-site.home-theme .hero-product,
.hero-feature-grid article,
.source-stack article,
.output-stack article,
.routing-stats` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important; backdrop-filter: none !important;`

- **`.market-hero-label`**
  - `.market-hero-label` → `display: inline-flex; align-items: center; gap: 10px; margin-bottom: 12px; color: #c8d4ca; font-size: 13px; font-weight: 700; letter-spacing: 0.04em;`
  - `.market-hero-label img` → `width: 25px; height: 25px; padding: 3px; object-fit: contain; border-radius: 6px; background: rgba(118, 185, 0, 0.14);`
  - `.eyebrow,
.market-hero-label,
.services-kicker,
.studio-kicker` → `color: #45d8ff !important;`
  - `.market-hero-label img,
.market-filter-icon` → `background: rgba(38, 139, 220, .18) !important; color: #53d9ff !important;`
  - `#app .market-hero-label span,
#app .market-filter-panel > header button` → `color: #0d5b9f !important;`

- **`.market-meta`**
  - `.market-meta` → `display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); overflow: hidden; margin: 0; border: 1px solid #e4eae6; border-radius: 9px; background: #ffffff;`
  - `.market-meta dt` → `color: #8a958e; font-size: 10px; line-height: 1.25;`
  - `.market-meta dd` → `margin: 2px 0 0; overflow-wrap: anywhere; color: #334139; font-size: 11px; font-weight: 700; line-height: 1.3;`
  - `.market-publisher strong,
.market-card h3,
.market-meta dd` → `color: #e3edfb !important;`
  - `.market-publisher small,
.market-public-id,
.market-meta dt` → `color: #8295b3 !important;`
  - `.market-meta,
.market-card footer,
.market-empty` → `border-color: rgba(98, 139, 212, .18) !important; background: rgba(6, 17, 37, .76) !important;`
  - `.metric-card,
.console-stat,
.services-count,
.market-stats article,
.market-meta,
.market-card footer,
.market-empty,
.docs-endpoints div,
.creative-studio .newbie-guide,
.creative-studio .storyboard-helper,
.creative-studio .storyboard-helper li,
.creative-studio .connection-help,
.creative-studio .prompt-box,
.creative-studio .works-skeleton i,
.service-image-placeholder,
.pricing-disclosure,
.market-error,
.market-fallback-note` → `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`
  - `#app .market-guide p:not(.eyebrow),
#app .market-availability-row,
#app .market-result-count,
#app .market-public-id,
#app .market-meta dt` → `color: var(--ui-muted) !important;`

- **`.market-page`**
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section,
.market-page,
.pricing-page,
.other-services-page,
.docs-page` → `color: var(--tech-text);`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`

- **`.market-publisher`**
  - `.market-publisher strong` → `color: #26362c; font-size: 12px;`
  - `.market-publisher small` → `color: #929d96; font-size: 10px;`
  - `.market-publisher strong,
.market-card h3,
.market-meta dd` → `color: #e3edfb !important;`
  - `.market-publisher small,
.market-public-id,
.market-meta dt` → `color: #8295b3 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`

- **`.market-publisher-fallback`**
  - `.market-publisher-fallback` → `display: grid; width: 100%; height: 100%; place-items: center; color: #4c5c52; font-size: 11px; font-weight: 800;`

- **`.market-publisher-icon`**
  - `.market-publisher-icon` → `display: grid; width: 24px; height: 24px; flex: 0 0 24px; place-items: center; overflow: hidden; border: 1px solid #e1e7e3; border-radius: 8px; background: #f7faf8;`
  - `.market-publisher-icon` → `border-color: rgba(101, 142, 216, .22) !important; background: rgba(20, 39, 75, .74) !important;`

- **`.market-result-count`**
  - `.market-result-count` → `min-width: 92px; color: #647069; font-size: 13px;`
  - `.market-result-count strong` → `color: #17251c; font-size: 20px;`
  - `.market-stats span,
.market-result-count` → `color: #8295b4 !important;`
  - `.market-stats strong,
.market-result-count strong` → `color: #edf6ff !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`
  - `#app .metric-card strong,
#app .console-stat strong,
#app .services-count strong,
#app .market-stats strong,
#app .market-result-count strong,
#app .account-summary-grid strong,
#app .report-summary strong,
#app .gateway-metrics strong,
#app .vmcard-status-grid strong,
#app .studio-stat strong` → `color: var(--ui-text) !important; text-shadow: none !important;`
  - `#app .market-guide p:not(.eyebrow),
#app .market-availability-row,
#app .market-result-count,
#app .market-public-id,
#app .market-meta dt` → `color: var(--ui-muted) !important;`
  - `#app .market-toolbar .market-result-count,
#app .market-toolbar .market-result-count span` → `color: var(--ui-muted) !important;`
  - `#app .market-toolbar .market-result-count strong` → `color: var(--ui-text) !important;`

- **`.market-tags`**
  - `.market-tags span` → `display: inline-flex; align-items: center; min-height: 18px; padding: 1px 6px; border-radius: 999px; border: 1px solid #e0e7e2; background: #f8faf8; color: #4a5a50; font-size: 9px; font-weight: 600;`
  - `#app .market-tags span` → `border-color: #d5e0ec !important; background: #f1f5f9 !important; color: #334155 !important;`
  - `.market-tags span` → `border-color: rgba(87, 144, 232, .24) !important; background: rgba(27, 61, 119, .25) !important; color: #9ec5eb !important;`

- **`.site-section`**
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section,
.market-page,
.pricing-page,
.other-services-page,
.docs-page` → `color: var(--tech-text);`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`


### views\OrganizationConsole.vue

- **`.organization-page`**
  - `.public-site,
.user-console-page,
.organization-page,
.creative-studio,
.flow-shell,
.admin-shell` → `color: var(--tech-text); background: transparent !important;`
  - `.public-site:not(.home-theme),
.user-console-page,
.organization-page,
.creative-studio,
.flow-shell,
.admin-shell,
.admin-login,
.agent-page,
.legal-page` → `color: var(--tech-text) !important; background: radial-gradient(circle at 94% 0, rgba(69, 74, 194, 0.14), transparent 25%), radial-gradient(circle at 2% 92%, rgba(14, 165, 193, 0.08), transparent 24%), linear-gradient(rgba(55, 105, 181, 0.035) 1px, transparent 1px), linear-gradient(90deg, rgba(55, 105, 181, 0.035) 1px, transparent 1px), #050916 !important; background-size: auto, auto, 52px 52px, 52px 52px, auto !important;`
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`
  - `.public-site:not(.home-theme)::before,
.creative-studio::before,
.organization-page::before,
.agent-page::before` → `position: fixed; z-index: -1; width: 72vw; height: 1px; top: 34vh; left: -12vw; background: linear-gradient(90deg, transparent, rgba(35, 209, 238, .2), rgba(91, 88, 243, .16), transparent); box-shadow: 0 0 28px rgba(44, 126, 255, .14); content: ''; transform: rotate(-11deg); pointer-events: none;`
  - `.public-site:not(.home-theme)::before,
  .creative-studio::before,
  .organization-page::before,
  .agent-page::before` → `opacity: .55;`
  - `#app > .public-site,
#app > .user-console-page,
#app > .organization-page,
#app > .creative-studio,
#app > .flow-shell,
#app > .admin-shell,
#app > .admin-login,
#app > .agent-page,
#app > .legal-page` → `position: relative; z-index: 1; background: rgba(3, 7, 19, .34) !important;`
  - `.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.organization-page h1,
.organization-page h2,
.organization-page h3,
.agent-page h1,
.agent-page h2,
.agent-page h3` → `color: #edf5ff !important;`
  - `.public-site,
.public-site.home-theme,
.user-console-page,
.organization-page,
.creative-studio,
.flow-shell,
.admin-shell,
.admin-login,
.agent-page,
.legal-page` → `color: var(--tech-text) !important; background: var(--tech-bg) !important; background-image: none !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`
  - `#app .playground-billing-panel .playground-result-head p,
#app .usage-visual-card header span,
#app .usage-pie small,
#app .usage-kpis span,
#app .usage-chart-toolbar span,
#app .usage-table-title span,
#app .gateway-workbench .heading p,
#app .panel-head small,
#app .organization-page .page-heading p,
#app .agent-card > p,
#app .vmcard-title-row p` → `color: var(--ui-muted) !important;`


### views\OtherServices.vue

- **`.service-card-meta`**
  - `small,
.muted,
.metric-card > span,
.console-stat > span,
.market-card-desc,
.service-card-meta,
.card-description` → `color: #8295b5;`
  - `#app .other-services-page .service-image-placeholder small,
#app .other-services-page .service-card-meta` → `color: var(--ui-muted) !important;`

- **`.service-image`**
  - `.service-image` → `aspect-ratio: 16 / 9; overflow: hidden; background: #eef3f8;`
  - `#app .other-services-page .service-image,
#app .other-services-page .service-image-placeholder` → `background: #f4f8fd !important; background-image: none !important;`

- **`.service-image-placeholder`**
  - `.service-image-placeholder` → `width: 100%; height: 100%; display: grid; place-content: center; gap: 7px; text-align: center; background: linear-gradient(135deg, #f8fafc, #edf3fa); color: #64748b;`
  - `.service-image-placeholder span` → `font-weight: 800;`
  - `.service-image-placeholder small` → `color: #94a3b8;`
  - `.service-image-placeholder` → `background: radial-gradient(circle at 70% 20%, rgba(70, 91, 230, .25), transparent 34%), linear-gradient(145deg, #101f41, #09152e) !important; color: #8298b8 !important;`
  - `.service-image-placeholder span` → `color: #58d8ff !important;`
  - `.metric-card,
.console-stat,
.services-count,
.market-stats article,
.market-meta,
.market-card footer,
.market-empty,
.docs-endpoints div,
.creative-studio .newbie-guide,
.creative-studio .storyboard-helper,
.creative-studio .storyboard-helper li,
.creative-studio .connection-help,
.creative-studio .prompt-box,
.creative-studio .works-skeleton i,
.service-image-placeholder,
.pricing-disclosure,
.market-error,
.market-fallback-note` → `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;`
  - `#app .other-services-page .service-image,
#app .other-services-page .service-image-placeholder` → `background: #f4f8fd !important; background-image: none !important;`
  - `#app .other-services-page .service-image-placeholder` → `color: var(--ui-muted) !important;`
  - `#app .other-services-page .service-image-placeholder span,
#app .other-services-page .service-index` → `color: #0d5b9f !important;`
  - `#app .other-services-page .service-image-placeholder small,
#app .other-services-page .service-card-meta` → `color: var(--ui-muted) !important;`
  - `#app .admin-shell :is(
  .metric-card,
  .ops-grid article,
  .sensitive-test-box,
  .sensitive-guide-grid article,
  .price-group,
  .service-image-placeholder,
  .vmcard-status-grid > article,
  .account-summary-grid > div,
  .report-summary > div,
  .gateway-metrics > div
)` → `border-color: var(--admin-line) !important; background: var(--admin-surface-soft) !important; background-image: none !important; color: var(--admin-text) !important; box-shadow: none !important;`

- **`.service-index`**
  - `.service-index` → `color: #0b63ce; font-size: 11px; font-weight: 800; letter-spacing: 0.08em;`
  - `#app .other-services-page .service-image-placeholder span,
#app .other-services-page .service-index` → `color: #0d5b9f !important;`

- **`.services-count`**
  - `.metric-card,
.console-stat,
.services-count` → `border-color: var(--tech-line) !important; background: linear-gradient(145deg, rgba(255,255,255,.96), rgba(238,247,255,.88)) !important; box-shadow: 0 12px 30px rgba(23, 105, 255, 0.07) !important;`
  - `.metric-card strong,
.console-stat strong,
.account-summary-grid strong,
.report-summary strong,
.gateway-metrics strong,
.services-count strong` → `font-family: "JetBrains Mono", "Cascadia Code", "SFMono-Regular", Consolas, monospace; font-variant-numeric: tabular-nums;`
  - `.services-count` → `padding: 18px; border: 1px solid #dbe7f5; border-radius: 8px; background: #fff; box-shadow: 0 14px 32px rgba(15, 23, 42, 0.06);`
  - `.services-count span,
.services-count small` → `display: block; color: #64748b;`
  - `.services-count strong` → `display: block; margin: 4px 0; color: #0b63ce; font-size: 36px;`
  - `.metric-card,
.console-stat,
.services-count,
.health-card,
.account-summary-grid > div,
.report-summary > div,
.gateway-metrics > div,
.vmcard-status-grid > article,
.studio-stat` → `border-color: rgba(98, 144, 230, 0.24) !important; background: linear-gradient(145deg, rgba(20, 42, 82, 0.86), rgba(9, 23, 50, 0.86)) !important; color: var(--tech-text) !important; box-shadow: inset 0 1px rgba(255,255,255,.04), 0 14px 34px rgba(0,0,0,.2) !important;`
  - `.metric-card strong,
.console-stat strong,
.services-count strong,
.market-card h3,
.pricing-card h2,
.service-card h3,
.work-card h3,
.panel h2,
.panel h3,
.console-panel h2,
.console-panel h3,
.el-card h2,
.el-card h3` → `color: #edf5ff !important;`
  - `.market-badge,
.status-chip,
.console-status-note,
.services-count,
.route-chip,
.model-tag` → `border-color: rgba(77, 156, 255, .28) !important; background: rgba(32, 85, 164, .2) !important; color: #80cbff !important;`
  - `#app .el-tag,
#app .market-badge,
#app .console-status-note,
#app .services-count,
#app .route-chip,
#app .model-tag` → `border-color: #bfd5ee !important; background: #eaf2ff !important; color: #174b8f !important;`
  - `.metric-card,
.console-stat,
.services-count,
.market-stats article,
.market-meta,
.market-card footer,
.market-empty,
.docs-endpoints div,
.creative-studio .newbie-guide,
.creative-studio .storyboard-helper,
.creative-studio .storyboard-helper li,
.creative-studio .connection-help,
.creative-studio .prompt-box,
.creative-studio .works-skeleton i,
.service-image-placeholder,
.pricing-disclosure,
.market-error,
.market-fallback-note` → `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;`
  - `#app .metric-card strong,
#app .console-stat strong,
#app .services-count strong,
#app .market-stats strong,
#app .market-result-count strong,
#app .account-summary-grid strong,
#app .report-summary strong,
#app .gateway-metrics strong,
#app .vmcard-status-grid strong,
#app .studio-stat strong` → `color: var(--ui-text) !important; text-shadow: none !important;`
  - `#app .metric-card > span,
#app .console-stat > span:not(.console-status-note),
#app .services-count > span,
#app .list-pagination,
#app .el-pagination__total,
#app .el-pagination__jump,
#app .el-empty__description p,
#app .el-table__empty-text,
#app .admin-login p` → `color: var(--ui-muted) !important;`
  - `#app .services-count small` → `color: var(--ui-muted) !important;`

- **`.services-grid`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`

- **`.services-hero`**
  - `.services-hero h1` → `margin: 0; color: #111827; font-size: 42px; line-height: 1.12;`
  - `.services-hero > div > p:not(.eyebrow)` → `max-width: 680px; margin: 14px 0 0; color: #536273; font-size: 16px; line-height: 1.75;`
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`

- **`.site-section`**
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section,
.market-page,
.pricing-page,
.other-services-page,
.docs-page` → `color: var(--tech-text);`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`


### views\PricingPage.vue

- **`.pricing-disclosure`**
  - `.pricing-disclosure` → `margin: 0 0 18px; padding: 12px 14px; border: 1px solid #bfdbfe; border-radius: 8px; background: #eff6ff; color: #1e40af; line-height: 1.7;`
  - `.pricing-disclosure,
.market-error,
.market-fallback-note` → `border: 1px solid rgba(94, 141, 224, .24) !important; background: rgba(15, 34, 69, .74) !important; color: #97aac6 !important;`
  - `.metric-card,
.console-stat,
.services-count,
.market-stats article,
.market-meta,
.market-card footer,
.market-empty,
.docs-endpoints div,
.creative-studio .newbie-guide,
.creative-studio .storyboard-helper,
.creative-studio .storyboard-helper li,
.creative-studio .connection-help,
.creative-studio .prompt-box,
.creative-studio .works-skeleton i,
.service-image-placeholder,
.pricing-disclosure,
.market-error,
.market-fallback-note` → `border-color: var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: var(--tech-text) !important; box-shadow: none !important;`

- **`.pricing-grid`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`

- **`.product-pricing-cover`**
  - `.product-pricing-cover` → `aspect-ratio: 16 / 9; margin-bottom: 18px; background: #e2e8f0;`

- **`.section-head`**
  - `.section-head h2,
.split-band h2,
.docs-band h2` → `max-width: 680px; margin: 0; color: #111827; font-size: 34px; line-height: 1.18; letter-spacing: 0;`

- **`.site-section`**
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section,
.market-page,
.pricing-page,
.other-services-page,
.docs-page` → `color: var(--tech-text);`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`


### views\ProductItem.vue

- **`.detail-layout`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`


### views\SubscriptionServices.vue

- **`.site-section`**
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.site-section,
.market-page,
.pricing-page,
.other-services-page,
.docs-page` → `color: var(--tech-text);`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p` → `color: #8fa2c0 !important;`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`
  - `.site-section p,
.market-page p,
.pricing-page p,
.other-services-page p,
.docs-page p,
.legal-page p,
.organization-page p,
.agent-page p,
.admin-header p,
.brand-subtitle,
.panel-head p,
.console-hero p,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.market-stats span,
.market-result-count,
.market-publisher small,
.market-public-id,
.market-meta dt,
.creative-studio .intro-copy,
.creative-studio .mode-rail small,
.creative-studio .rail-tip,
.creative-studio .field-label small,
.creative-studio .reference-section p,
.creative-studio .settings-grid label > span,
.creative-studio .composer-bottom small,
.creative-studio .template-grid p,
.creative-studio .login-empty p,
.source-stack small,
.routing-stats small` → `color: var(--tech-muted) !important;`


### views\UserConsole.vue

- **`.access-row`**
  - `.access-row` → `display: grid; grid-template-columns: 100px minmax(0, 1fr) auto; align-items: center; gap: 12px; min-height: 52px; padding: 10px 12px; border: 1px solid #edf2f7; border-radius: 10px; background: #f8fafc;`
  - `.access-row span` → `color: #64748b; font-size: 14px;`
  - `.access-row code` → `overflow: hidden; color: #0f172a; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace; font-size: 13px; text-overflow: ellipsis; white-space: nowrap;`

- **`.account-summary-grid`**
  - `.metric-card strong,
.console-stat strong,
.account-summary-grid strong,
.report-summary strong,
.gateway-metrics strong,
.services-count strong` → `font-family: "JetBrains Mono", "Cascadia Code", "SFMono-Regular", Consolas, monospace; font-variant-numeric: tabular-nums;`
  - `.account-summary-grid div` → `padding: 12px; border: 1px solid #edf2f7; border-radius: 8px; background: #f8fafc;`
  - `.account-summary-grid span` → `display: block; color: #64748b; font-size: 12px;`
  - `.account-summary-grid strong` → `display: block; margin-top: 7px; color: #111827; font-size: 21px;`
  - `.metric-card,
.console-stat,
.services-count,
.health-card,
.account-summary-grid > div,
.report-summary > div,
.gateway-metrics > div,
.vmcard-status-grid > article,
.studio-stat` → `border-color: rgba(98, 144, 230, 0.24) !important; background: linear-gradient(145deg, rgba(20, 42, 82, 0.86), rgba(9, 23, 50, 0.86)) !important; color: var(--tech-text) !important; box-shadow: inset 0 1px rgba(255,255,255,.04), 0 14px 34px rgba(0,0,0,.2) !important;`
  - `#app .metric-card strong,
#app .console-stat strong,
#app .services-count strong,
#app .market-stats strong,
#app .market-result-count strong,
#app .account-summary-grid strong,
#app .report-summary strong,
#app .gateway-metrics strong,
#app .vmcard-status-grid strong,
#app .studio-stat strong` → `color: var(--ui-text) !important; text-shadow: none !important;`
  - `#app .market-guide,
#app .account-summary-grid > div,
#app .report-summary > div,
#app .gateway-metrics > div,
#app .vmcard-status-grid > article,
#app .studio-stat` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--ui-text) !important; box-shadow: 0 8px 24px rgba(51, 75, 105, 0.08) !important;`
  - `#app .admin-shell :is(
  .metric-card,
  .ops-grid article,
  .sensitive-test-box,
  .sensitive-guide-grid article,
  .price-group,
  .service-image-placeholder,
  .vmcard-status-grid > article,
  .account-summary-grid > div,
  .report-summary > div,
  .gateway-metrics > div
)` → `border-color: var(--admin-line) !important; background: var(--admin-surface-soft) !important; background-image: none !important; color: var(--admin-text) !important; box-shadow: none !important;`

- **`.brand-image`**
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`

- **`.console-brand`**
  - `.console-brand strong,
.console-brand small` → `line-height: 1.2;`
  - `.console-brand small` → `color: #64748b; font-size: 12px; font-weight: 500;`
  - `.public-site:not(.home-theme) .site-brand,
.studio-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.legal-page h1,
.organization-page h1,
.agent-page h1` → `color: #eef5ff !important;`
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`
  - `.public-site.home-theme .site-brand,
.public-site.home-theme .hero-copy > p.hero-brandline,
.public-site.home-theme .hero-copy h1,
.public-site.home-theme .hero-copy > p:not(.hero-brandline),
.public-site:not(.home-theme) .site-brand,
.studio-brand,
.item-brand,
.brand-title,
.console-brand,
.admin-header h1,
.site-section h1,
.site-section h2,
.site-section h3,
.market-page h1,
.pricing-page h1,
.docs-page h1,
.services-hero h1,
.creative-studio h1,
.creative-studio .studio-brand,
.creative-studio .studio-intro h1,
.creative-studio .composer-head h2,
.creative-studio .section-title h2,
.creative-studio .template-grid h3,
.creative-studio .work-card-body h3,
.creative-studio .field-label label,
.creative-studio .mode-rail b,
.legal-page h1,
.organization-page h1,
.agent-page h1,
.admin-login h1,
.admin-shell h1,
.admin-shell h2,
.admin-shell h3,
.user-console-page h1,
.user-console-page h2,
.user-console-page h3,
.market-publisher strong,
.market-card h3,
.market-meta dd,
.model-sale-pricing header strong,
.sale-price-grid strong,
.unit-sale-price strong,
.pricing-pending strong,
.pricing-free strong,
.context-price-note strong,
.source-stack strong,
.output-stack strong,
.routing-core strong,
.routing-stats strong` → `color: var(--tech-text) !important; text-shadow: none !important;`

- **`.console-hero-sub`**
  - `.console-hero .console-hero-sub` → `display: block; margin-top: 8px; color: #536273; line-height: 1.6;`

- **`.console-status-note`**
  - `.console-status-note` → `width: fit-content; max-width: 100%; padding: 4px 8px; border: 1px solid #d9e5f5; border-radius: 6px; background: #f5f8fc; color: #42566f; font-size: 12px; font-weight: 650; line-height: 1.4;`
  - `.console-status-note.is-blue` → `border-color: #c9dcfb; background: #f1f6ff; color: #174f9d;`
  - `.console-status-note.is-green` → `border-color: #cce9df; background: #f1faf6; color: #176b50;`
  - `.console-status-note.is-orange` → `border-color: #f4dcc4; background: #fff8f1; color: #94511b;`
  - `.console-status-note.is-purple` → `border-color: #ded7f8; background: #f7f5ff; color: #5d479c;`
  - `.market-badge,
.status-chip,
.console-status-note,
.services-count,
.route-chip,
.model-tag` → `border-color: rgba(77, 156, 255, .28) !important; background: rgba(32, 85, 164, .2) !important; color: #80cbff !important;`
  - `#app .el-tag,
#app .market-badge,
#app .console-status-note,
#app .services-count,
#app .route-chip,
#app .model-tag` → `border-color: #bfd5ee !important; background: #eaf2ff !important; color: #174b8f !important;`
  - `#app .metric-card > span,
#app .console-stat > span:not(.console-status-note),
#app .services-count > span,
#app .list-pagination,
#app .el-pagination__total,
#app .el-pagination__jump,
#app .el-empty__description p,
#app .el-table__empty-text,
#app .admin-login p` → `color: var(--ui-muted) !important;`

- **`.created-key-secret`**
  - `.created-key-secret` → `margin-top: 18px; padding: 14px; border: 1px solid #bfdbfe; border-radius: 8px; background: #eff6ff;`
  - `.created-key-secret code` → `min-width: 0; flex: 1; overflow-wrap: anywhere; color: #0f172a; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;`

- **`.is-blue`**
  - `.console-status-note.is-blue` → `border-color: #c9dcfb; background: #f1f6ff; color: #174f9d;`

- **`.is-green`**
  - `.console-status-note.is-green` → `border-color: #cce9df; background: #f1faf6; color: #176b50;`

- **`.is-orange`**
  - `.console-status-note.is-orange` → `border-color: #f4dcc4; background: #fff8f1; color: #94511b;`

- **`.is-purple`**
  - `.console-status-note.is-purple` → `border-color: #ded7f8; background: #f7f5ff; color: #5d479c;`

- **`.module-error-state`**
  - `.overview-inline-error,
.module-error-state` → `display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; padding: 11px 13px; border: 1px solid #fed7aa; border-radius: 8px; background: #fff7ed; color: #9a3412; font-size: 13px;`

- **`.overview-inline-error`**
  - `.overview-inline-error,
.module-error-state` → `display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 14px; padding: 11px 13px; border: 1px solid #fed7aa; border-radius: 8px; background: #fff7ed; color: #9a3412; font-size: 13px;`

- **`.playground-answer-scroll`**
  - `.playground-answer-scroll` → `max-height: 420px; margin-top: 16px; overflow: auto; border: 1px solid rgba(148, 163, 184, 0.18); border-radius: 8px; background: #0d1321;`
  - `.playground-answer-scroll pre` → `overflow: visible; margin: 0; padding: 20px; border-radius: 0; white-space: pre-wrap; overflow-wrap: anywhere;`
  - `.code-panel,
pre,
.developer-docs > pre,
.test-io,
.playground-answer-scroll,
.playground-raw-collapse` → `border-color: var(--tech-line) !important; background: #f8fafc !important; color: #1e3a5f !important; box-shadow: none !important;`
  - `#app pre,
#app .code-panel,
#app .developer-docs > pre,
#app .test-io,
#app .playground-answer-scroll,
#app .playground-raw-collapse` → `border: 1px solid var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: #1e3a5f !important; box-shadow: none !important;`

- **`.playground-billing-note`**
  - `.playground-billing-note` → `margin: 12px 0 0; color: #71839a; font-size: 11px; line-height: 1.6;`

- **`.playground-billing-panel`**
  - `.playground-billing-panel` → `border-color: #d7e4f7 !important; background: rgba(255, 255, 255, 0.92) !important;`
  - `.playground-billing-panel h2` → `margin-bottom: 4px; color: #142944;`
  - `.playground-billing-panel .playground-result-head p` → `margin: 0; color: #667a95; font-size: 12px;`
  - `#app .playground-billing-panel .playground-result-head p,
#app .usage-visual-card header span,
#app .usage-pie small,
#app .usage-kpis span,
#app .usage-chart-toolbar span,
#app .usage-table-title span,
#app .gateway-workbench .heading p,
#app .panel-head small,
#app .organization-page .page-heading p,
#app .agent-card > p,
#app .vmcard-title-row p` → `color: var(--ui-muted) !important;`

- **`.playground-cost-breakdown`**
  - `.playground-cost-breakdown` → `display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 12px; color: #526984; font-size: 12px;`

- **`.playground-model-empty`**
  - `.playground-model-empty` → `padding: 14px 16px; color: #94a3b8; text-align: center;`

- **`.playground-model-summary`**
  - `.playground-model-summary span` → `padding: 4px 8px; border: 1px solid #dbe4ef; border-radius: 999px; background: #f8fafc; color: #526173; font-size: 11px; line-height: 1.35;`

- **`.playground-rate-grid`**
  - `.playground-usage-grid span,
.playground-rate-grid span` → `display: block; color: #667a95; font-size: 11px;`
  - `.playground-rate-grid div` → `min-width: 0; padding: 10px 12px; border-radius: 7px; border: 1px solid #e0e9f5; background: #ffffff;`
  - `.playground-rate-grid b` → `display: block; overflow-wrap: anywhere; margin-top: 5px; color: #263e5d; font-size: 12px;`

- **`.playground-raw-collapse`**
  - `.playground-raw-collapse` → `margin-top: 16px; overflow: hidden; border: 1px solid rgba(148, 163, 184, 0.18); border-radius: 8px; --el-collapse-border-color: transparent; --el-collapse-header-bg-color: #eef5ff; --el-collapse-header-text-color: #1d3b62; --el-collapse-content-bg-color: #0b1220; --el-collapse-content-text-color: #cbd5e1;`
  - `.quick-action,
.activity-list > div,
.session-list > div,
.oauth-binding,
.access-item,
.response-content,
.playground-raw-collapse,
.playground-usage-summary` → `border-color: rgba(100, 141, 215, .2) !important; background: rgba(9, 23, 50, .7) !important; color: #c0cee1 !important;`
  - `#app .user-console-page .sidebar-balance,
#app .user-console-page .console-hero,
#app .admin-table-shell,
#app .security-card,
#app .settings-card,
#app .report-card,
#app .quick-action,
#app .activity-list > div,
#app .session-list > div,
#app .oauth-binding,
#app .access-item,
#app .response-content,
#app .playground-raw-collapse,
#app .playground-usage-summary` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important;`
  - `.code-panel,
pre,
.developer-docs > pre,
.test-io,
.playground-answer-scroll,
.playground-raw-collapse` → `border-color: var(--tech-line) !important; background: #f8fafc !important; color: #1e3a5f !important; box-shadow: none !important;`
  - `#app pre,
#app .code-panel,
#app .developer-docs > pre,
#app .test-io,
#app .playground-answer-scroll,
#app .playground-raw-collapse` → `border: 1px solid var(--tech-line) !important; background: #f8fafc !important; background-image: none !important; color: #1e3a5f !important; box-shadow: none !important;`

- **`.playground-raw-scroll`**
  - `.playground-raw-scroll pre` → `overflow: visible; margin: 0; padding: 16px; border-radius: 0; white-space: pre-wrap; overflow-wrap: anywhere;`

- **`.playground-result-head`**
  - `.playground-billing-panel .playground-result-head p` → `margin: 0; color: #667a95; font-size: 12px;`
  - `#app .playground-billing-panel .playground-result-head p,
#app .usage-visual-card header span,
#app .usage-pie small,
#app .usage-kpis span,
#app .usage-chart-toolbar span,
#app .usage-table-title span,
#app .gateway-workbench .heading p,
#app .panel-head small,
#app .organization-page .page-heading p,
#app .agent-card > p,
#app .vmcard-title-row p` → `color: var(--ui-muted) !important;`

- **`.playground-usage-grid`**
  - `.playground-usage-grid article` → `min-width: 0; padding: 12px; border: 1px solid #dfe9f6; border-radius: 8px; background: #ffffff;`
  - `.playground-usage-grid article.total-cost` → `border-color: #bdd8fa; background: #eef6ff;`
  - `.playground-usage-grid span,
.playground-rate-grid span` → `display: block; color: #667a95; font-size: 11px;`
  - `.playground-usage-grid strong` → `display: block; overflow-wrap: anywhere; margin-top: 7px; color: #102541; font-size: 18px;`
  - `.playground-usage-grid .total-cost strong` → `color: #0755c9;`
  - `.playground-usage-grid strong` → `margin-top: 4px; font-size: 15px;`

- **`.playground-usage-summary`**
  - `.playground-usage-summary` → `margin-top: 16px; padding: 16px; border: 1px solid #dbe7f6; border-radius: 10px; background: #f6f9fd;`
  - `.quick-action,
.activity-list > div,
.session-list > div,
.oauth-binding,
.access-item,
.response-content,
.playground-raw-collapse,
.playground-usage-summary` → `border-color: rgba(100, 141, 215, .2) !important; background: rgba(9, 23, 50, .7) !important; color: #c0cee1 !important;`
  - `#app .user-console-page .sidebar-balance,
#app .user-console-page .console-hero,
#app .admin-table-shell,
#app .security-card,
#app .settings-card,
#app .report-card,
#app .quick-action,
#app .activity-list > div,
#app .session-list > div,
#app .oauth-binding,
#app .access-item,
#app .response-content,
#app .playground-raw-collapse,
#app .playground-usage-summary` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important;`

- **`.polished`**
  - `.console-stat.polished` → `min-height: 142px; display: grid; align-content: space-between; box-shadow: 0 14px 32px rgba(15, 23, 42, 0.06);`

- **`.quick-list`**
  - `.user-nav button,
.quick-list button` → `display: flex; align-items: center; gap: 10px; min-height: 42px; border: 0; border-radius: 8px; background: transparent; color: #526070; cursor: pointer;`
  - `.user-nav button.active,
.user-nav button:hover,
.quick-list button:hover` → `background: #eef5ff; color: #0b63ce;`
  - `.quick-list.refined strong` → `color: #111827;`
  - `.quick-list.refined small` → `color: #64748b;`
  - `.quick-list button` → `justify-content: flex-start; padding: 0 12px; border: 1px solid #e3e8f0;`

- **`.refined`**
  - `.quick-list.refined strong` → `color: #111827;`
  - `.quick-list.refined small` → `color: #64748b;`

- **`.response-panel`**
  - `.docs-band pre,
.response-panel pre` → `overflow: auto; margin: 0; padding: 20px; border-radius: 8px; background: #0d1321; color: #dbeafe; line-height: 1.7;`
  - `.response-panel` → `border-color: #d7e4f7 !important; background: rgba(255, 255, 255, 0.92) !important;`
  - `.response-panel h2` → `color: #142944;`

- **`.sidebar-balance`**
  - `.sidebar-balance` → `display: grid; gap: 10px; margin-top: 22px; padding: 16px; border: 1px solid #e3e8f0; border-radius: 8px; background: #f8fafc;`
  - `.sidebar-balance span` → `color: #64748b; font-size: 13px;`
  - `.sidebar-balance strong` → `color: #111827; font-size: 28px;`
  - `.sidebar-balance` → `gap: 7px; padding: 8px 4px; border: 0; background: transparent;`
  - `.user-console-page .sidebar-balance,
.user-console-page .console-hero` → `border-color: rgba(103, 143, 216, .22) !important; background: rgba(10, 23, 49, .72) !important;`
  - `#app .user-console-page .sidebar-balance,
#app .user-console-page .console-hero,
#app .admin-table-shell,
#app .security-card,
#app .settings-card,
#app .report-card,
#app .quick-action,
#app .activity-list > div,
#app .session-list > div,
#app .oauth-binding,
#app .access-item,
#app .response-content,
#app .playground-raw-collapse,
#app .playground-usage-summary` → `border-color: var(--tech-line) !important; background: #ffffff !important; background-image: none !important; color: var(--tech-text) !important;`
  - `#app .el-button span,
#app .sidebar-balance .el-button span` → `color: inherit !important;`

- **`.site-brand-mark`**
  - `.site-brand-mark` → `display: grid; width: 34px; height: 34px; place-items: center; border-radius: 8px; background: #0f172a; color: #ffffff; font-size: 15px;`
  - `.public-site.home-theme .site-brand-mark` → `width: 36px; height: 36px; border-radius: 10px; background: rgba(255, 255, 255, 0.98); box-shadow: 0 0 24px rgba(37, 181, 255, 0.22);`
  - `.public-site:not(.home-theme) .site-brand-mark,
.console-brand .brand-image,
.brand-mark` → `background: rgba(255, 255, 255, 0.96) !important; box-shadow: 0 0 24px rgba(32, 174, 255, 0.17);`
  - `.public-site.home-theme .site-brand-mark` → `width: 38px; height: 38px; padding: 0; border-radius: 0; background: transparent !important; box-shadow: none;`

- **`.total-cost`**
  - `.playground-usage-grid article.total-cost` → `border-color: #bdd8fa; background: #eef6ff;`
  - `.playground-usage-grid .total-cost strong` → `color: #0755c9;`

- **`.usage-day-bar`**
  - `.usage-day-bar` → `display: flex; min-height: 2px; overflow: hidden; flex-direction: column-reverse; border-radius: 5px 5px 2px 2px; background: #edf1ee;`
  - `.usage-day-bar .miss` → `background: #78ad30;`
  - `.usage-day-bar .hit` → `background: #35a7a0;`
  - `.usage-day-bar .write` → `background: #e6a23c;`
  - `.usage-day-bar .output` → `background: #6b7fd7;`

- **`.usage-table-scroll`**
  - `.usage-table-scroll::-webkit-scrollbar-thumb` → `border-radius: 999px; background: #9aabc0;`
  - `.usage-table-scroll::-webkit-scrollbar-track` → `background: #edf2f7;`

- **`.user-console`**
  - `.user-console` → `min-height: 100vh; min-height: 100svh; min-height: 100dvh; display: grid; grid-template-columns: 248px minmax(0, 1fr); background: #f6f8fb;`

- **`.user-main`**
  - `.admin-shell .admin-main,
.user-console-page .user-main` → `background: rgba(3, 8, 21, .5) !important; backdrop-filter: blur(2px);`
  - `.admin-shell .admin-main,
.user-console-page .user-main` → `background: var(--tech-bg) !important; backdrop-filter: none !important;`

- **`.user-nav`**
  - `.user-nav button,
.quick-list button` → `display: flex; align-items: center; gap: 10px; min-height: 42px; border: 0; border-radius: 8px; background: transparent; color: #526070; cursor: pointer;`
  - `.user-nav button.active,
.user-nav button:hover,
.quick-list button:hover` → `background: #eef5ff; color: #0b63ce;`
  - `.user-console-page .user-nav button` → `color: #8699b8 !important;`
  - `.user-console-page .user-nav button.active,
.user-console-page .user-nav button:hover` → `border-color: rgba(69, 160, 255, .28) !important; background: linear-gradient(90deg, rgba(31, 109, 209, .28), rgba(75, 79, 207, .13)) !important; color: #dcf3ff !important;`
  - `#app .user-console-page .user-nav button,
#app .admin-shell .admin-menu .el-menu-item,
#app .admin-shell .admin-menu .el-sub-menu__title` → `color: #52657e !important;`
  - `#app .user-console-page .user-nav button.active,
#app .user-console-page .user-nav button:hover,
#app .admin-shell .admin-menu .el-menu-item.is-active,
#app .admin-shell .admin-menu .el-menu-item:hover,
#app .admin-shell .admin-menu .el-sub-menu__title:hover` → `border-color: #bfd5ee !important; background: #eaf2ff !important; background-image: none !important; color: #174b8f !important;`

- **`.wallet-layout`**
  - `.market-catalog-shell,
.market-grid,
.pricing-grid,
.services-grid,
.creation-layout,
.work-grid,
.detail-layout,
.wallet-layout,
.organization-page,
.agent-page` → `background: transparent !important;`


## 三、可判定为死类（未被模板引用 / EP 前缀），建议确认后丢弃

- `.access-item`
- `.agreement-content`
- `.auth-hint`
- `.auth-side`
- `.bar-chart`
- `.card-description`
- `.console-content`
- `.data-wave`
- `.database`
- `.docs-band`
- `.docs-content`
- `.docs-page`
- `.docs-shell`
- `.docs-sidebar`
- `.el-alert__close-btn`
- `.el-alert__content`
- `.el-alert__icon`
- `.el-button--success`
- `.el-button--warning`
- `.el-checkbox__inner`
- `.el-checkbox__input`
- `.el-collapse`
- `.el-collapse-item__header`
- `.el-collapse-item__wrap`
- `.el-descriptions__content`
- `.el-descriptions__label`
- `.el-divider__text`
- `.el-dropdown-menu__item`
- `.el-dropdown__popper`
- `.el-input-group__append`
- `.el-input-group__prepend`
- `.el-input-number`
- `.el-input-number__decrease`
- `.el-input-number__increase`
- `.el-input__count`
- `.el-input__prefix-inner`
- `.el-input__suffix-inner`
- `.el-loading-mask`
- `.el-message--error`
- `.el-message--success`
- `.el-message--warning`
- `.el-message__closeBtn`
- `.el-message__icon`
- `.el-pagination__jump`
- `.el-pagination__total`
- `.el-popover`
- `.el-popper`
- `.el-radio-button`
- `.el-radio-button__inner`
- `.el-radio-button__original-radio`
- `.el-radio__input`
- `.el-select__caret`
- `.el-select__popper`
- `.el-select__selected-item`
- `.el-skeleton__item`
- `.el-switch__core`
- `.el-table__expanded-cell`
- `.el-tabs__active-bar`
- `.el-tabs__nav-wrap`
- `.el-tag__content`
- `.enhanced`
- `.gray`
- `.heading`
- `.hero-proof`
- `.hit`
- `.identifier-status`
- `.indigo`
- `.is-checked`
- `.is-hovering`
- `.is-indeterminate`
- `.legal-content`
- `.logo-strip`
- `.market-availability-row`
- `.market-error`
- `.market-status-dot`
- `.metric-icon`
- `.miss`
- `.model-dot`
- `.model-tag`
- `.model-tile`
- `.nvidia`
- `.orange`
- `.other-service-card`
- `.output`
- `.pending`
- `.pink`
- `.price-group`
- `.pricing-page`
- `.product-card`
- `.purple`
- `.quick-action`
- `.red`
- `.response-code`
- `.response-content`
- `.route-chip`
- `.route-icon`
- `.route-list`
- `.services-kicker`
- `.sparkline`
- `.split-band`
- `.studio-kicker`
- `.studio-stat`
- `.teal`
- `.tone-blue`
- `.tone-gray`
- `.tone-purple`
- `.tone-red`
- `.usage-chart`
- `.user-header`
- `.vmcard-title-row`
- `.wave-ribbon`
- `.workflow-list`
- `.write`
