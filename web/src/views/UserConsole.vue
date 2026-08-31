<template>
  <div class="public-site user-console-page">
    <div class="user-console user-console-embedded">
      <aside class="user-sidebar">
        <button class="site-brand console-brand" title="用户工作台" aria-label="用户工作台" @click="go('/console')">
          <img class="site-brand-mark brand-image" src="/brand/linknux-mark-192.png" alt="" />
          <span>
            <strong>用户工作台</strong>
            <small>{{ currentUser?.username || '已登录账号' }}</small>
          </span>
        </button>

        <nav class="user-nav" aria-label="User console navigation">
          <button
            v-for="item in navItems"
            :key="item.path"
            :class="{ active: isActive(item.path) }"
            :title="item.label"
            :aria-label="item.label"
            @click="go(item.path)"
          >
            <component :is="item.icon" />
            <span>{{ item.label }}</span>
          </button>
        </nav>

        <section class="sidebar-balance">
          <span>账户余额</span>
          <strong>{{ money(balance) }}</strong>
          <el-button class="sidebar-recharge" size="small" type="primary" aria-label="充值或购买" title="充值或购买" @click="openRechargePanel"><el-icon><Wallet /></el-icon><span>充值 / 购买</span></el-button>
          <el-button class="sidebar-home" size="small" text aria-label="返回首页" title="返回首页" @click="go('/')"><el-icon><HomeFilled /></el-icon><span>返回首页</span></el-button>
          <el-button class="sidebar-logout" size="small" text aria-label="退出登录" title="退出登录" @click="logout"><el-icon><SwitchButton /></el-icon><span>退出登录</span></el-button>
        </section>
      </aside>

      <main class="user-main">
        <header class="console-hero">
          <div>
            <p class="eyebrow">{{ current.eyebrow }}</p>
            <h1>{{ current.title }}</h1>
            <span>{{ current.subtitle }}</span>
          </div>
          <div class="console-hero-actions">
            <el-button @click="go('/market')">
              <el-icon><Compass /></el-icon>
              模型广场
            </el-button>
            <el-button type="primary" @click="handlePrimaryAction">
              <el-icon><component :is="primaryAction.icon" /></el-icon>
              {{ primaryAction.label }}
            </el-button>
            <AccountMenu />
          </div>
        </header>

        <section v-if="current.key === 'overview'" class="console-overview">
          <div class="console-metrics">
            <article v-for="metric in metrics" :key="metric.label" class="console-stat polished">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
              <span :class="['console-status-note', `is-${metric.tone}`]">{{ metric.badge }}</span>
            </article>
          </div>

          <section class="console-panel usage-panel">
            <div class="panel-head">
              <div>
                <h2>Token 消耗趋势</h2>
                <p>近 90 天输入、输出及缓存 Token；默认显示最后 14 天，可拖动时间轴。</p>
              </div>
              <el-button size="small" @click="go('/console/logs')">查看日志</el-button>
            </div>
            <div v-if="overviewUsageError" class="overview-inline-error">
              <span>{{ overviewUsageError }}</span>
              <el-button link type="primary" @click="loadOverviewUsage">重试</el-button>
            </div>
            <UsageTimelineChart v-if="overviewUsageAnalytics.daily?.length" :daily="overviewUsageAnalytics.daily" :days="90" :visible-days="14" />
            <el-empty v-else-if="!overviewUsageError" description="近 90 天暂无调用记录" :image-size="68" />
          </section>

          <section class="console-panel account-panel">
            <div class="panel-head">
              <div>
                <h2>余额与本月费用</h2>
                <p>钱包以人民币结算，模型消费金额以美元记录。</p>
              </div>
              <el-button size="small" type="primary" @click="openRechargePanel">充值</el-button>
            </div>
            <div class="account-summary-grid">
              <div><span>可用余额</span><strong>{{ money(balance) }}</strong></div>
              <div><span>本月钱包扣款</span><strong>{{ money(monthlyAmount) }}</strong></div>
              <div><span>累计 Token</span><strong>{{ compactNumber(totalTokensUsed) }}</strong></div>
            </div>
          </section>

          <section class="console-panel access-panel">
            <div class="panel-head">
              <div>
                <h2>接入信息</h2>
                <p>在 OpenAI 兼容 SDK 中配置以下 Base URL 和 API Key 即可调用。</p>
              </div>
              <el-button size="small" @click="go('/console/keys')">管理 Key</el-button>
            </div>
            <div class="access-grid">
              <div class="access-row">
                <span>接入 URL</span>
                <code>{{ gatewayBaseUrl }}</code>
                <el-button size="small" @click="copyText(gatewayBaseUrl, '接入 URL 已复制')">复制</el-button>
              </div>
              <div class="access-row">
                <span>API Key</span>
                <code>{{ primaryAccessKey ? keyPreview(primaryAccessKey) : '暂无可用 Key' }}</code>
                <el-button v-if="primaryAccessKey" size="small" disabled title="完整 API Key 仅在创建成功时显示一次">仅创建时可见</el-button>
                <el-button v-else size="small" type="primary" @click="openCreateKey">创建 Key</el-button>
              </div>
            </div>
          </section>

          <section class="console-panel quick-panel">
            <div class="panel-head">
              <div>
                <h2>快捷入口</h2>
                <p>高频操作放在这里，减少来回跳转。</p>
              </div>
            </div>
            <div class="quick-list refined">
              <button v-for="action in quickActions" :key="action.label" @click="go(action.path)">
                <component :is="action.icon" />
                <span>
                  <strong>{{ action.label }}</strong>
                  <small>{{ action.desc }}</small>
                </span>
              </button>
            </div>
          </section>

          <section class="console-panel activity-panel">
            <div class="panel-head">
              <div>
                <h2>最近活动</h2>
                <p>请求、购买和配置变更的摘要。</p>
              </div>
            </div>
            <div class="activity-list">
              <el-empty v-if="activities.length === 0" description="暂无调用记录" />
              <div v-for="item in activities" :key="item.title">
                <span :class="item.tone"></span>
                <p>
                  <strong>{{ item.title }}</strong>
                  <small>{{ item.time }} · {{ item.desc }}</small>
                </p>
              </div>
            </div>
          </section>
        </section>

        <section v-else-if="current.key === 'keys'" class="console-panel">
          <div class="panel-head">
            <div>
              <h2>API Key 管理</h2>
              <p>按环境拆分 Key，控制额度、模型范围和启停状态。</p>
            </div>
            <el-button type="primary" @click="openCreateKey">新建 Key</el-button>
          </div>
          <el-table :data="keys" border>
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="key" label="Key" min-width="220" />
            <el-table-column prop="quota" label="额度" min-width="140" />
            <el-table-column prop="models" label="模型范围" min-width="170" />
            <el-table-column prop="lastUsed" label="最近调用" min-width="140" />
            <el-table-column prop="status" label="状态" min-width="100">
              <template #default="{ row }">
                <el-tag :type="row.status === '启用' ? 'success' : 'info'">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-else-if="current.key === 'playground'" class="playground-layout">
          <section class="console-panel playground-request-panel">
            <div class="panel-head">
              <div>
                <h2>在线调试</h2>
                <p>选择 Key 和模型，快速验证调用效果。</p>
              </div>
            </div>
            <el-form label-position="top">
              <el-form-item label="API Key">
                <el-select
                  v-model="playground.tokenId"
                  placeholder="选择当前账户的 API Key"
                  style="width: 100%"
                  @change="syncPlaygroundModel()"
                >
                  <el-option
                    v-for="token in dashboard.tokens"
                    :key="token.id"
                    :label="`${token.name || `Key #${token.id}`} (${keyPreview(token)})`"
                    :value="token.id"
                    :disabled="!token.enabled"
                  />
                </el-select>
              </el-form-item>
              <el-form-item label="模型">
                <el-select v-model="playground.model" filterable placeholder="选择后台已发布的可调用模型" style="width: 100%">
                  <el-option
                    v-for="model in playgroundModels"
                    :key="model.publicName"
                    :label="`${model.publicName} · ${model.sourceName || '平台智能路由'}`"
                    :value="model.publicName"
                  />
                  <template #empty>
                    <div class="playground-model-empty">当前 API Key 没有可调用模型</div>
                  </template>
                </el-select>
                <div v-if="selectedModelDetail" class="playground-model-detail">
                  <div class="playground-model-summary"><span>{{ selectedModelDetail.sourceName || '平台智能路由' }}</span><span>{{ selectedModelDetail.routeCount }} 条可用路由</span></div>
                  <ModelSalePricing :model="selectedModelDetail" compact />
                </div>
              </el-form-item>
              <el-alert
                v-if="dashboard.modelCatalog.length === 0"
                title="管理员尚未发布可调用模型，或所有模型渠道当前不可用。"
                type="warning"
                :closable="false"
                show-icon
              />
              <el-form-item label="Prompt">
                <el-input
                  v-model="playground.prompt"
                  type="textarea"
                  :rows="6"
                  maxlength="20000"
                  show-word-limit
                  placeholder="输入需要发送给模型的内容"
                />
              </el-form-item>
              <el-button type="primary" class="full-button" :loading="playgroundLoading" @click="sendPlaygroundRequest">
                发送请求
              </el-button>
            </el-form>
          </section>
          <section class="console-panel response-panel">
            <div class="playground-result-head">
              <h2>模型回答</h2>
            </div>
            <el-alert
              v-if="playgroundError"
              class="playground-result-error"
              type="error"
              :title="playgroundError"
              :closable="false"
              show-icon
            />
            <el-empty
              v-else-if="!playgroundResponse"
              description="发送请求后在这里查看模型回答"
            />
            <div v-else class="playground-answer-scroll">
              <pre>{{ playgroundResponse }}</pre>
            </div>
            <el-collapse
              v-if="playgroundRawResponse"
              v-model="playgroundRawSections"
              class="playground-raw-collapse"
            >
              <el-collapse-item title="查看原始响应 JSON" name="raw">
                <div class="playground-raw-scroll">
                  <pre>{{ playgroundRawResponse }}</pre>
                </div>
              </el-collapse-item>
            </el-collapse>
          </section>

          <section class="console-panel playground-billing-panel">
            <div class="playground-result-head">
              <div>
                <h2>本次用量与费用</h2>
                <p>Token、单价与扣费均由服务端计算。</p>
              </div>
              <el-tag v-if="playgroundUsage?.estimated" type="warning">Token 为服务端估算</el-tag>
              <el-tag v-else-if="playgroundUsage" type="success">服务端已结算</el-tag>
            </div>
            <el-empty v-if="!playgroundUsage" description="请求完成后显示 Token、单价与费用" :image-size="76" />
            <section v-else class="playground-usage-summary">
              <div class="playground-usage-grid">
                <article>
                  <span>输入 Token</span>
                  <strong>{{ number(playgroundUsage.promptTokens) }}</strong>
                </article>
                <article>
                  <span>输出 Token</span>
                  <strong>{{ number(playgroundUsage.completionTokens) }}</strong>
                </article>
                <article>
                  <span>缓存读取 Token</span>
                  <strong>{{ number(playgroundUsage.cacheReadTokens) }}</strong>
                </article>
                <article>
                  <span>缓存写入 Token</span>
                  <strong>{{ number(playgroundUsage.cacheWriteTokens) }}</strong>
                </article>
                <article>
                  <span>缓存合计 Token</span>
                  <strong>{{ number(playgroundUsage.cachedTokens) }}</strong>
                </article>
                <article class="total-cost">
                  <span>本次实际费用</span>
                  <strong>{{ formatUsd(playgroundUsage.totalAmount) }}</strong>
                </article>
              </div>
              <div class="playground-rate-grid">
                <div><span>计价挡位</span><b>{{ playgroundUsage.priceTier || '默认挡位' }}</b></div>
                <div><span>售价组 / 单位</span><b>{{ playgroundUsage.saleGroupName || '本站售价' }} · {{ playgroundUsage.priceUnit || 'M' }}</b></div>
                <div><span>价格后缀</span><b>{{ playgroundUsage.priceSuffix || 'USD / 1M Token' }}</b></div>
                <div><span>输入/未命中单价</span><b>{{ formatPerMillionUsd(playgroundUsage.inputPricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
                <div><span>输出单价</span><b>{{ formatPerMillionUsd(playgroundUsage.outputPricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
                <div><span>缓存命中单价</span><b>{{ formatPerMillionUsd(playgroundUsage.cacheReadPricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
                <div><span>缓存写入单价</span><b>{{ formatPerMillionUsd(playgroundUsage.cacheWritePricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
              </div>
              <div class="playground-cost-breakdown">
                <span>输入费用 {{ formatUsd(playgroundUsage.inputAmount) }}</span>
                <span>输出费用 {{ formatUsd(playgroundUsage.outputAmount) }}</span>
                <span>缓存命中费用 {{ formatUsd(playgroundUsage.cacheReadAmount) }}</span>
                <span>缓存写入费用 {{ formatUsd(playgroundUsage.cacheWriteAmount) }}</span>
              </div>
              <p class="playground-billing-note">
                Token、单价和扣费均由服务端根据管理员模型配置计算并落账，浏览器不参与计费。
              </p>
            </section>
          </section>
        </section>

        <ProfileCenter v-else-if="current.key === 'profile'" />
        <DeveloperDocs v-else-if="current.key === 'docs'" />

        <section v-else-if="current.key === 'logs'" class="console-panel" v-loading="billingLoading">
          <div class="panel-head">
            <div>
              <h2>模型账单明细</h2>
              <p>按模型、Key 和日期聚合输入、输出、缓存命中 token 及扣费。</p>
            </div>
            <el-button @click="loadBilling">刷新</el-button>
          </div>
          <div class="usage-filter-row">
            <el-date-picker v-model="usageRange" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" @change="applyBillingFilters" />
            <el-select v-model="usageModel" clearable placeholder="全部模型" @change="applyBillingFilters">
              <el-option v-for="model in dashboard.models || []" :key="model" :label="model" :value="model" />
            </el-select>
          </div>
          <div v-if="billingDataError" class="module-error-state">
            <span>{{ billingDataError }}</span>
            <el-button link type="primary" @click="loadBilling">重新加载账单</el-button>
          </div>
          <div v-if="usageAnalyticsError" class="module-error-state">
            <span>{{ usageAnalyticsError }}</span>
            <el-button link type="primary" @click="loadUsageAnalytics">重新加载图表</el-button>
          </div>
          <div v-if="!usageAnalyticsError" class="usage-visual-grid" v-loading="usageAnalyticsLoading">
            <article class="usage-visual-card usage-bars-card">
              <header><strong>单日 Token 消耗</strong><span>堆叠显示输入未命中、缓存命中/写入和输出</span></header>
              <div class="usage-stacked-chart">
                <div v-for="day in usageDays" :key="day.date" class="usage-day-column">
                  <div class="usage-day-bar" :title="`${day.date} · ${number(day.total)} Token`" :style="{ height: `${day.height}%` }">
                    <i v-for="part in day.parts" :key="part.name" :class="part.className" :style="{ flexGrow: part.value || 0 }"></i>
                  </div>
                  <small>{{ day.date.slice(5) }}</small>
                </div>
              </div>
            </article>
            <article class="usage-visual-card usage-pie-card">
              <header><strong>Token 构成</strong><span>当前筛选范围</span></header>
              <div class="usage-pie-wrap"><div class="usage-pie" :style="usagePieStyle"><b>{{ compactNumber(usageTotalTokens) }}</b><small>Token</small></div>
                <ul><li v-for="slice in usageComposition" :key="slice.name"><i :style="{ background: slice.color }"></i><span>{{ slice.name }}</span><b>{{ number(slice.value) }}</b></li></ul>
              </div>
              <footer>模型消费总额 <strong>{{ formatUsd(usageMetric(usageAnalytics.totals, 'total_amount')) }}</strong></footer>
            </article>
          </div>
          <div class="usage-table-scroll" tabindex="0" aria-label="账单汇总横向滚动区域">
          <el-table :data="billingSummary" border scrollbar-always-on style="min-width: 1260px">
            <el-table-column prop="model" label="模型" min-width="180" />
            <el-table-column prop="request_count" label="请求" width="90" />
            <el-table-column prop="prompt_tokens" label="输入 Token" width="130" />
            <el-table-column prop="completion_tokens" label="输出 Token" width="130" />
            <el-table-column prop="cached_tokens" label="缓存 Token" width="130" />
            <el-table-column label="输入费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.input_amount) }}</template>
            </el-table-column>
            <el-table-column label="输出费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.output_amount) }}</template>
            </el-table-column>
            <el-table-column label="缓存费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.cached_amount) }}</template>
            </el-table-column>
            <el-table-column label="总费用(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.total_amount) }}</template>
            </el-table-column>
          </el-table>
          </div>

          <div class="usage-table-scroll billing-detail-table" tabindex="0" aria-label="用量明细横向滚动区域">
          <el-table :data="billingRows" border scrollbar-always-on style="min-width: 1390px">
            <el-table-column prop="created_at" label="时间" min-width="170" />
            <el-table-column prop="trace_id" label="Trace ID" min-width="150" />
            <el-table-column prop="token_name" label="Key" min-width="140" />
            <el-table-column prop="model" label="模型" min-width="160" />
            <el-table-column prop="prompt_tokens" label="输入" width="90" />
            <el-table-column prop="completion_tokens" label="输出" width="90" />
            <el-table-column prop="cached_tokens" label="缓存" width="90" />
            <el-table-column label="模型费用(USD)" width="140">
              <template #default="{ row }">{{ formatUsd(row.total_amount) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="error_message" label="错误" min-width="220" />
          </el-table>
          </div>
          <el-pagination
            v-model:current-page="billingPage"
            v-model:page-size="billingPageSize"
            class="billing-pagination"
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="billingTotal"
            @current-change="loadBilling"
            @size-change="handleBillingPageSizeChange"
          />
        </section>

        <section v-else-if="current.key === 'wallet'" class="wallet-layout" v-loading="walletLoading">
          <div class="console-metrics wallet-metrics">
            <article class="console-stat polished">
              <span>可用余额</span>
              <strong>{{ formatCny(wallet.balance) }}</strong>
              <span class="console-status-note is-green">CNY · 10,000 units = ¥1</span>
            </article>
            <article class="console-stat polished">
              <span>本月消耗</span>
              <strong>{{ formatCny(wallet.monthSpend) }}</strong>
              <span class="console-status-note is-orange">按真实账单汇总</span>
            </article>
            <article class="console-stat polished">
              <span>赠送额度</span>
              <strong>{{ formatCny(wallet.giftBalance) }}</strong>
              <span class="console-status-note is-blue">CNY 等值额度</span>
            </article>
            <article class="console-stat polished">
              <span>可开票金额</span>
              <strong>{{ formatCny(wallet.invoiceableAmount) }}</strong>
              <span class="console-status-note is-purple">以服务端账本为准</span>
            </article>
          </div>

          <section class="console-panel redeem-panel">
            <div class="panel-head">
              <div>
                <h2>兑换码充值</h2>
                <p>平台暂不开放未经验证的直接加款；可使用管理员签发的兑换码入账。</p>
              </div>
            </div>
            <div class="redeem-row">
              <el-input
                id="redeem-code"
                v-model="redeemCode"
                maxlength="128"
                clearable
                autocomplete="off"
                placeholder="输入兑换码"
                @keyup.enter="redeemWalletCode"
              />
              <el-button type="primary" :loading="redeeming" @click="redeemWalletCode">立即兑换</el-button>
            </div>
          </section>

          <section class="console-panel wallet-plans">
            <div class="panel-head">
              <div>
                <h2>额度方案</h2>
                <p>以下金额均按 CNY 展示；支付购买尚未开放时不会创建虚假充值。</p>
              </div>
            </div>
            <el-empty v-if="!wallet.plans.length" description="暂无可购买额度方案" />
            <div v-else class="wallet-plan-grid">
              <article v-for="plan in wallet.plans" :key="plan.id">
                <strong>{{ plan.name }}</strong>
                <span>{{ formatMoneyDto(plan.paymentMoney) }}</span>
                <small>赠送 {{ Number(plan.bonus || 0).toFixed(2) }}% · 到账 {{ formatMoneyDto(plan.totalCreditMoney) }}</small>
                <el-button type="primary" :loading="rechargeSubmitting" @click="openRecharge(plan)">购买</el-button>
              </article>
            </div>
          </section>

          <section class="console-panel wallet-transactions">
            <div class="panel-head"><div><h2>充值订单</h2><p>金额由服务端套餐快照确定，付款后可下载账单和收据。</p></div></div>
            <el-table :data="rechargeOrders" border empty-text="暂无充值订单">
              <el-table-column prop="orderNo" label="订单号" min-width="220" />
              <el-table-column prop="planName" label="套餐" min-width="130" />
              <el-table-column label="实付" width="130"><template #default="{ row }">{{ formatMoneyDto(row.paymentMoney) }}</template></el-table-column>
              <el-table-column label="到账" width="130"><template #default="{ row }">{{ formatMoneyDto(row.totalCreditMoney) }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="120" />
              <el-table-column label="操作" min-width="210"><template #default="{ row }">
                <el-button v-if="row.invoiceRequested" link type="primary" @click="downloadRecharge(row.id, 'invoice')">账单</el-button>
                <el-button v-if="['PAID','REFUNDED'].includes(row.status)" link type="success" @click="downloadRecharge(row.id, 'receipt')">收据</el-button>
              </template></el-table-column>
            </el-table>
          </section>

          <section class="console-panel wallet-transactions">
            <div class="panel-head">
              <div>
                <h2>余额流水</h2>
                <p>金额与余额均按 10,000 amount units = ¥1.00 CNY 换算。</p>
              </div>
              <el-button :loading="walletLoading" @click="loadWallet">刷新</el-button>
            </div>
            <el-table :data="wallet.transactions" border empty-text="暂无余额流水">
              <el-table-column prop="created_at" label="时间" min-width="180" />
              <el-table-column prop="type" label="类型" width="130" />
              <el-table-column label="变动金额" width="150">
                <template #default="{ row }">{{ formatSignedCny(row.amount) }}</template>
              </el-table-column>
              <el-table-column label="变动后余额" width="150">
                <template #default="{ row }">{{ formatCny(row.balance_after) }}</template>
              </el-table-column>
              <el-table-column prop="channel" label="渠道" width="130" />
              <el-table-column prop="remark" label="备注" min-width="220" />
            </el-table>
          </section>

          <el-drawer v-model="rechargeVisible" title="充值方案" size="min(680px, 94vw)" destroy-on-close>
            <div class="recharge-drawer-plans">
              <button v-for="plan in wallet.plans" :key="plan.id" type="button"
                :class="{ selected: selectedRechargePlan?.id === plan.id }" @click="selectedRechargePlan = plan">
                <span><strong>{{ plan.name }}</strong><small>赠送 {{ Number(plan.bonus || 0).toFixed(2) }}%</small></span>
                <b>{{ formatMoneyDto(plan.paymentMoney) }}</b>
                <small class="recharge-credit-note">到账 {{ formatMoneyDto(plan.totalCreditMoney) }}</small>
              </button>
            </div>
            <el-form label-position="top">
              <el-alert v-if="selectedRechargePlan" type="info" :closable="false" :title="`实付 ${formatMoneyDto(selectedRechargePlan.paymentMoney)}，到账 ${formatMoneyDto(selectedRechargePlan.totalCreditMoney)}`" />
              <el-form-item label="支付方式"><el-radio-group v-model="rechargeForm.paymentMethod"><el-radio value="alipay">支付宝</el-radio><el-radio value="wxpay">微信支付</el-radio></el-radio-group></el-form-item>
              <el-form-item label="需要开具账单 / 发票"><el-switch v-model="rechargeForm.needInvoice" /></el-form-item>
              <template v-if="rechargeForm.needInvoice">
                <el-form-item label="姓名或公司名称"><el-input v-model="rechargeForm.billingName" /></el-form-item>
                <el-form-item label="接收邮箱"><el-input v-model="rechargeForm.contactEmail" /></el-form-item>
                <el-form-item label="详细地址"><el-input v-model="rechargeForm.billingAddressLine1" placeholder="省/市以下的街道、门牌、小区等" /></el-form-item>
                <el-row :gutter="12"><el-col :span="12"><el-form-item label="区/县"><el-input v-model="rechargeForm.billingDistrict" /></el-form-item></el-col><el-col :span="12"><el-form-item label="城市"><el-input v-model="rechargeForm.billingCity" /></el-form-item></el-col></el-row>
                <el-row :gutter="12"><el-col :span="12"><el-form-item label="省份"><el-input v-model="rechargeForm.billingProvince" /></el-form-item></el-col><el-col :span="12"><el-form-item label="邮编"><el-input v-model="rechargeForm.billingPostalCode" /></el-form-item></el-col></el-row>
                <el-form-item label="国家/地区"><el-input v-model="rechargeForm.billingCountry" /></el-form-item>
              </template>
            </el-form>
            <template #footer><el-button @click="rechargeVisible=false">取消</el-button><el-button type="primary" :disabled="!selectedRechargePlan" :loading="rechargeSubmitting" @click="submitRecharge">立即支付</el-button></template>
          </el-drawer>
        </section>

        <section v-else class="console-panel">
          <div class="panel-head">
            <div>
              <h2>{{ current.title }}</h2>
              <p>{{ current.subtitle }}</p>
            </div>
            <el-button type="primary" @click="go(current.actionPath)">{{ current.action }}</el-button>
          </div>
          <el-table :data="genericRows" border>
            <el-table-column prop="name" label="项目" min-width="180" />
            <el-table-column prop="desc" label="说明" min-width="260" />
            <el-table-column prop="status" label="状态" min-width="120" />
          </el-table>
        </section>
      </main>

      <el-dialog v-model="createKeyVisible" title="新建 API Key" width="480px" destroy-on-close>
        <el-form label-position="top" @submit.prevent="createKey">
          <el-form-item label="名称" required>
            <el-input
              v-model="createKeyForm.name"
              maxlength="80"
              show-word-limit
              placeholder="例如：生产环境"
            />
          </el-form-item>
          <el-form-item label="Token 调用额度">
            <el-input-number
              v-model="createKeyForm.totalQuota"
              :min="0"
              :max="1000000000"
              :step="10000"
              controls-position="right"
              style="width: 100%"
            />
            <div class="form-hint">单位为 Token；填写 0 表示不单独限制此 Key 的 Token 额度。账户余额仍会按模型定价扣减。</div>
          </el-form-item>
          <el-form-item label="授权全部模型">
            <el-switch v-model="createKeyForm.allowAllModels" />
          </el-form-item>
          <el-form-item v-if="!createKeyForm.allowAllModels" label="授权模型" required>
            <el-cascader
              v-model="createKeyForm.allowedModelIds"
              :options="modelGrantOptions"
              :props="{ multiple: true, emitPath: false }"
              collapse-tags
              collapse-tags-tooltip
              filterable
              clearable
              placeholder="按来源 / 功能 / 厂家选择模型"
              style="width: 100%"
            />
            <div class="form-hint">仅所选模型会出现在此 Key 的 /v1/models 中。</div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="createKeyVisible = false">取消</el-button>
          <el-button type="primary" :loading="createKeySubmitting" @click="createKey">创建</el-button>
        </template>
      </el-dialog>

      <el-dialog
        v-model="createdKeyVisible"
        title="API Key 创建成功（仅显示一次）"
        width="560px"
        :close-on-click-modal="false"
        :close-on-press-escape="false"
        @closed="clearCreatedKey"
      >
        <el-alert
          title="关闭后将无法再次查看完整 Key，请立即复制并保存在密码管理器中。"
          type="warning"
          :closable="false"
          show-icon
        />
        <div class="created-key-secret">
          <code>{{ createdKeySecret }}</code>
          <el-button type="primary" @click="copyText(createdKeySecret, 'API Key 已复制')">复制 Key</el-button>
        </div>
        <template #footer>
          <el-button type="primary" @click="createdKeyVisible = false">我已安全保存</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Compass, DataLine, Document, HomeFilled, Key, Monitor, Promotion, ShoppingCart, SwitchButton, Tickets, Wallet, User } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { clearAuth, getUser } from '@/utils/auth'
import http, { createIdempotencyKey, getHttpErrorMessage, getHttpErrorNotice } from '@/utils/http'
import { formatCny, formatPerMillionUsd, formatSignedCny, formatUsd, type AmountUnits } from '@/utils/money'
import ModelSalePricing from '@/components/ModelSalePricing.vue'
import DeveloperDocs from '@/components/DeveloperDocs.vue'
import ProfileCenter from '@/components/ProfileCenter.vue'
import AccountMenu from '@/components/AccountMenu.vue'
import UsageTimelineChart from '@/components/UsageTimelineChart.vue'
import {
  modelScopeLabel,
  modelsAllowedForToken,
  normalizeModelCatalog,
  type CallableModel,
} from '@/utils/modelCatalog'

const router = useRouter()
const route = useRoute()
const currentUser = computed(() => getUser())
const dashboard = ref<any>({ stats: {}, tokens: [], recentLogs: [], models: [], modelCatalog: [] as CallableModel[] })
const billingRows = ref<any[]>([])
const billingSummary = ref<any[]>([])
const billingPage = ref(1)
const billingPageSize = ref(20)
const billingTotal = ref(0)
const usageAnalytics = ref<any>({ daily: [], totals: {}, tokenComposition: [] })
const billingLoading = ref(false)
const billingDataError = ref('')
const usageAnalyticsLoading = ref(false)
const usageAnalyticsError = ref('')
const overviewUsageAnalytics = ref<any>({ daily: [] })
const overviewUsageError = ref('')
const today = new Date()
const monthAgo = new Date(today); monthAgo.setDate(today.getDate() - 29)
const ninetyDaysAgo = new Date(today); ninetyDaysAgo.setDate(today.getDate() - 89)
const usageRange = ref<[string, string]>([monthAgo.toISOString().slice(0, 10), today.toISOString().slice(0, 10)])
const usageModel = ref('')
const playgroundLoading = ref(false)
const playgroundResponse = ref('')
const playgroundRawResponse = ref('')
const playgroundError = ref('')
const playgroundRawSections = ref<string[]>([])
type PlaygroundUsage = {
  promptTokens: number
  completionTokens: number
  cachedTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  estimated: boolean
  priceTier: string
  saleGroupName: string
  priceUnit: string
  priceSuffix: string
  inputPricePerMillion: number | string
  outputPricePerMillion: number | string
  cachedPricePerMillion: number | string
  cacheReadPricePerMillion: number | string
  cacheWritePricePerMillion: number | string
  inputAmount: AmountUnits
  outputAmount: AmountUnits
  cachedAmount: AmountUnits
  cacheReadAmount: AmountUnits
  cacheWriteAmount: AmountUnits
  totalAmount: AmountUnits
}
const playgroundUsage = ref<PlaygroundUsage | null>(null)
const playground = ref({
  tokenId: undefined as number | undefined,
  model: '',
  prompt: ''
})
const createKeyVisible = ref(false)
const createKeySubmitting = ref(false)
const createdKeyVisible = ref(false)
const createdKeySecret = ref('')
const createKeyForm = ref({
  name: '',
  totalQuota: 0,
  allowAllModels: true,
  allowedModelIds: [] as string[]
})
const modelGrantOptions = computed(() => {
  const sources = new Map<string, any>()
  for (const model of dashboard.value.modelCatalog || []) {
    const sourceKey = model.source || 'other'
    if (!sources.has(sourceKey)) sources.set(sourceKey, {
      value: sourceKey, label: model.sourceName || sourceKey, children: new Map<string, any>()
    })
    const source = sources.get(sourceKey)
    const capability = model.capability || 'text'
    if (!source.children.has(capability)) source.children.set(capability, {
      value: `${sourceKey}:${capability}`, label: capability, children: new Map<string, any>()
    })
    const capabilityNode = source.children.get(capability)
    const vendor = model.vendor || 'unknown'
    if (!capabilityNode.children.has(vendor)) capabilityNode.children.set(vendor, {
      value: `${sourceKey}:${capability}:${vendor}`, label: vendor, children: []
    })
    capabilityNode.children.get(vendor).children.push({ value: model.publicName, label: model.publicName })
  }
  return [...sources.values()].map(source => ({ ...source, children: [...source.children.values()].map((capability: any) => ({
    ...capability, children: [...capability.children.values()]
  })) }))
})
const walletLoading = ref(false)
const redeeming = ref(false)
const redeemCode = ref('')
type MoneyDto = { amount: number | string; currency: string; scale: number }
type RechargePlan = { id: number | string; name: string; amount: AmountUnits; bonus?: number; paymentMoney?: MoneyDto; totalCreditMoney?: MoneyDto }
const rechargeVisible = ref(false)
const rechargeSubmitting = ref(false)
const selectedRechargePlan = ref<RechargePlan | null>(null)
const rechargeOrders = ref<Array<Record<string, any>>>([])
const rechargeForm = ref({ needInvoice: false, billingName: '', contactEmail: '', billingAddressLine1: '', billingDistrict: '', billingCity: '', billingProvince: '', billingPostalCode: '', billingCountry: 'China', paymentMethod: 'alipay' })
let paymentPollTimer: number | null = null
const wallet = ref({
  balance: 0 as AmountUnits,
  monthSpend: 0 as AmountUnits,
  giftBalance: 0 as AmountUnits,
  invoiceableAmount: 0 as AmountUnits,
  transactions: [] as Array<Record<string, any>>,
  plans: [] as RechargePlan[]
})

const navItems = [
  { key: 'overview', label: '总览', path: '/console', icon: DataLine, title: '业务总览', eyebrow: '用户中心', subtitle: '查看账户额度、调用趋势、快捷入口和最近活动。', action: '刷新', actionPath: '/console' },
  { key: 'keys', label: 'API Key', path: '/console/keys', icon: Key, title: 'API Key 管理', eyebrow: '凭证管理', subtitle: '创建和管理不同环境的调用凭证。', action: '新建 Key', actionPath: '/console/keys' },
  { key: 'playground', label: '在线调试', path: '/console/playground', icon: Monitor, title: '在线调试', eyebrow: '请求测试', subtitle: '无需写代码，直接验证模型返回。', action: '发送请求', actionPath: '/console/playground' },
  { key: 'logs', label: '用量日志', path: '/console/logs', icon: Tickets, title: '用量日志', eyebrow: '审计记录', subtitle: '按 Key、模型、日期追踪请求消耗。', action: '导出 CSV', actionPath: '/console/logs' },
  { key: 'wallet', label: '钱包充值', path: '/console/wallet', icon: Wallet, title: '钱包充值', eyebrow: '余额中心', subtitle: '充值余额、查看消耗和账单状态。', action: '去充值', actionPath: '/pricing' },
  { key: 'agent', label: '代理中心', path: '/console/agent', icon: Promotion, title: '代理中心', eyebrow: '合作伙伴', subtitle: '查看邀请客户、返利、佣金和提现。', action: '进入代理中心', actionPath: '/console/agent' },
  { key: 'docs', label: '文档 SDK', path: '/console/docs', icon: Document, title: '开发文档', eyebrow: '接入指南', subtitle: '在工作台内查看多客户端配置和调用方式。', action: '查看文档', actionPath: '/console/docs' },
  { key: 'profile', label: '个人中心', path: '/console/profile', icon: User, title: '个人中心', eyebrow: '账户安全', subtitle: '管理头像、联系方式、密码、第三方账号和登录设备。', action: '保存信息', actionPath: '/console/profile' }
]

const current = computed(() => navItems.find(item => item.path === route.path) || navItems[0])
const gatewayBaseUrl = computed(() => {
  const configured = String(import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '')
  const origin = configured || window.location.origin
  return `${origin.replace(/\/$/, '')}/v1`
})
const primaryAccessKey = computed(() => {
  const tokens = dashboard.value.tokens || []
  return tokens.find((token: any) => token.enabled) || tokens[0] || null
})
const selectedPlaygroundToken = computed(() => (dashboard.value.tokens || [])
  .find((token: any) => Number(token.id) === Number(playground.value.tokenId)) || null)
const playgroundModels = computed<CallableModel[]>(() => modelsAllowedForToken(
  dashboard.value.modelCatalog || [],
  selectedPlaygroundToken.value,
))
const selectedModelDetail = computed<CallableModel | null>(() => playgroundModels.value
  .find(model => model.publicName === playground.value.model) || null)
const primaryAction = computed(() => {
  if (current.value.key === 'overview') return { label: '在线调试', path: '/console/playground', icon: Monitor }
  if (current.value.key === 'wallet') return { label: '兑换码充值', path: '/console/wallet', icon: Wallet }
  return { label: current.value.action, path: current.value.actionPath, icon: current.value.icon }
})

const go = (path: string) => router.push(path)
const copyText = async (value: string, message = '已复制') => {
  if (!value) return
  try {
    await navigator.clipboard.writeText(value)
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = value
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
  }
  ElMessage.success(message)
}
const handlePrimaryAction = () => {
  if (current.value.key === 'keys') {
    openCreateKey()
    return
  }
  if (current.value.key === 'playground') {
    sendPlaygroundRequest()
    return
  }
  if (current.value.key === 'wallet') {
    openRechargePanel()
    return
  }
  go(primaryAction.value.path)
}

const openCreateKey = () => {
  clearCreatedKey()
  createKeyForm.value = { name: '', totalQuota: 0, allowAllModels: true, allowedModelIds: [] }
  createKeyVisible.value = true
}

const createKey = async () => {
  const name = createKeyForm.value.name.trim()
  if (!name) {
    ElMessage.warning('请输入 Key 名称')
    return
  }
  if (!createKeyForm.value.allowAllModels && createKeyForm.value.allowedModelIds.length === 0) {
    ElMessage.warning('请至少选择一个授权模型')
    return
  }
  createKeySubmitting.value = true
  try {
    const response = await http.post('/api/user/tokens', {
      name,
      totalQuota: createKeyForm.value.totalQuota,
      allowAllModels: createKeyForm.value.allowAllModels,
      allowedModelIds: createKeyForm.value.allowedModelIds
    })
    createKeyVisible.value = false
    const secret = typeof response.data?.secret === 'string' ? response.data.secret : ''
    if (secret) {
      createdKeySecret.value = secret
      createdKeyVisible.value = true
      ElMessage.success('API Key 已创建，请立即复制保存')
    } else {
      ElMessage.warning('API Key 已创建，但服务端未返回一次性密钥；请立即轮换该 Key')
    }
    void loadDashboard().catch(error => {
      ElMessage.warning(getHttpErrorMessage(error, 'Key 已创建，但列表刷新失败'))
    })
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, 'API Key 创建失败'))
  } finally {
    createKeySubmitting.value = false
  }
}

const clearCreatedKey = () => {
  createdKeySecret.value = ''
}
const isActive = (path: string) => route.path === path
const logout = () => {
  clearAuth()
  router.push('/')
}

async function loadDashboard() {
  const res = await http.get('/api/user/dashboard')
  const modelCatalog = normalizeModelCatalog(res.data?.modelCatalog, res.data?.models)
  dashboard.value = {
    stats: res.data?.stats || {},
    tokens: res.data?.tokens || [],
    recentLogs: res.data?.recentLogs || [],
    models: modelCatalog.map(model => model.publicName),
    modelCatalog
  }
  const selectedTokenStillEnabled = dashboard.value.tokens.some((token: any) =>
    Number(token.id) === Number(playground.value.tokenId) && token.enabled)
  if (!selectedTokenStillEnabled) {
    playground.value.tokenId = dashboard.value.tokens.find((token: any) => token.enabled)?.id
  }
  const requestedModel = route.query.model?.toString().trim()
  if (requestedModel && dashboard.value.models.includes(requestedModel)) {
    const matchingToken = dashboard.value.tokens.find((token: any) =>
      token.enabled && modelsAllowedForToken(modelCatalog, token)
        .some(model => model.publicName === requestedModel))
    if (matchingToken) playground.value.tokenId = matchingToken.id
  }
  syncPlaygroundModel(requestedModel || playground.value.model)
}

function syncPlaygroundModel(preferredModel = playground.value.model) {
  const available = playgroundModels.value
  if (preferredModel && available.some(model => model.publicName === preferredModel)) {
    playground.value.model = preferredModel
    return
  }
  playground.value.model = available[0]?.publicName || ''
}

async function sendPlaygroundRequest() {
  if (!playground.value.tokenId) {
    ElMessage.warning('请先选择当前账户的 API Key')
    return
  }
  if (!playground.value.model) {
    ElMessage.warning('请先选择模型')
    return
  }
  if (!playground.value.prompt.trim()) {
    ElMessage.warning('请输入 Prompt')
    return
  }
  playgroundLoading.value = true
  playgroundResponse.value = ''
  playgroundRawResponse.value = ''
  playgroundError.value = ''
  playgroundRawSections.value = []
  playgroundUsage.value = null
  try {
    const response = await http.post('/api/user/playground', playground.value, { timeout: 120_000 })
    playgroundResponse.value = extractPlaygroundResult(response.data)
    playgroundRawResponse.value = formatPlaygroundRawResponse(response.data)
    playgroundUsage.value = extractPlaygroundUsage(response.data)
    if (!playgroundResponse.value) {
      playgroundError.value = '服务端没有返回可展示的模型回答'
    }
    await loadDashboard()
  } catch (error: any) {
    const payload = error?.response?.data
    playgroundError.value = getHttpErrorMessage(error, '模型调用失败')
    playgroundRawResponse.value = JSON.stringify(payload || { error: '请求失败' }, null, 2)
    playgroundUsage.value = null
  } finally {
    playgroundLoading.value = false
  }
}

function extractPlaygroundResult(data: any) {
  const choice = data?.choices?.[0]
  const message = choice?.message
  const candidates = [
    message?.content,
    message?.reasoning_content,
    message?.reasoning,
    choice?.text,
    data?.output_text
  ]
  for (const candidate of candidates) {
    const text = extractTextContent(candidate)
    if (text) return text
  }
  return ''
}

function extractTextContent(content: any): string {
  if (typeof content === 'string') return content.trim()
  if (!Array.isArray(content)) return ''
  return content
    .map(part => typeof part === 'string' ? part : part?.text)
    .filter((part): part is string => typeof part === 'string' && Boolean(part.trim()))
    .join('\n')
    .trim()
}

function formatPlaygroundRawResponse(data: any) {
  if (!data || typeof data !== 'object') return JSON.stringify(data ?? null, null, 2)
  const responseOnly = { ...data }
  delete responseOnly.usage
  delete responseOnly.billing
  return JSON.stringify(responseOnly, null, 2)
}

function extractPlaygroundUsage(data: any): PlaygroundUsage | null {
  const usage = data?.usage
  const billing = data?.billing
  if (!usage || !billing) return null
  const promptTokens = Math.max(0, Number(usage.prompt_tokens || 0))
  const completionTokens = Math.max(0, Number(usage.completion_tokens || 0))
  const cachedFromDetails = Number(usage.prompt_tokens_details?.cached_tokens || 0)
    + Number(usage.cache_read_input_tokens || 0)
    + Number(usage.cache_creation_input_tokens || 0)
  const cacheReadTokens = Math.max(0,
    Number(billing.cache_read_tokens ?? usage.prompt_tokens_details?.cached_tokens ?? usage.cache_read_input_tokens ?? 0))
  const cacheWriteTokens = Math.max(0,
    Number(billing.cache_write_tokens ?? usage.cache_creation_input_tokens ?? 0))
  return {
    promptTokens,
    completionTokens,
    cachedTokens: Math.max(0, Number(billing.cached_tokens ?? cachedFromDetails)),
    cacheReadTokens,
    cacheWriteTokens,
    estimated: Boolean(usage.estimated),
    priceTier: String(billing.price_tier || '默认挡位'),
    saleGroupName: String(billing.sale_group_name || '本站售价'),
    priceUnit: String(billing.price_unit || 'M'),
    priceSuffix: String(billing.price_suffix || 'USD / 1M Token'),
    inputPricePerMillion: billing.input_price_per_million ?? 0,
    outputPricePerMillion: billing.output_price_per_million ?? 0,
    cachedPricePerMillion: billing.cached_price_per_million ?? 0,
    cacheReadPricePerMillion: billing.cache_read_price_per_million ?? billing.cached_price_per_million ?? 0,
    cacheWritePricePerMillion: billing.cache_write_price_per_million ?? 0,
    inputAmount: billing.input_amount ?? 0,
    outputAmount: billing.output_amount ?? 0,
    cachedAmount: billing.cached_amount ?? 0,
    cacheReadAmount: billing.cache_read_amount ?? 0,
    cacheWriteAmount: billing.cache_write_amount ?? 0,
    totalAmount: billing.total_amount ?? 0
  }
}

async function loadBilling() {
  if (current.value.key !== 'logs') return
  billingLoading.value = true
  billingDataError.value = ''
  usageAnalyticsError.value = ''
  const [summaryResult, logsResult, analyticsResult] = await Promise.allSettled([
    http.get('/api/user/billing/summary', { params: { model: usageModel.value || undefined, startDate: usageRange.value?.[0], endDate: usageRange.value?.[1] } }),
    http.get('/api/user/billing/logs/page', { params: { page: billingPage.value, size: billingPageSize.value, model: usageModel.value || undefined, startDate: usageRange.value?.[0], endDate: usageRange.value?.[1] } }),
    http.get('/api/user/usage/analytics', { params: { from: usageRange.value?.[0], to: usageRange.value?.[1], model: usageModel.value || undefined } })
  ])
  const dataErrors: string[] = []
  if (summaryResult.status === 'fulfilled') billingSummary.value = summaryResult.value.data || []
  else dataErrors.push(`账单汇总：${getHttpErrorNotice(summaryResult.reason, '加载失败')}`)
  if (logsResult.status === 'fulfilled') {
    billingRows.value = logsResult.value.data?.items || []
    billingTotal.value = Number(logsResult.value.data?.total || 0)
  }
  else dataErrors.push(`用量明细：${getHttpErrorNotice(logsResult.reason, '加载失败')}`)
  billingDataError.value = dataErrors.join('；')
  if (analyticsResult.status === 'fulfilled') {
    usageAnalytics.value = analyticsResult.value.data || { daily: [], totals: {}, tokenComposition: [] }
  } else {
    usageAnalyticsError.value = getHttpErrorNotice(analyticsResult.reason, '用量图表加载失败')
  }
  billingLoading.value = false
}

function applyBillingFilters() {
  billingPage.value = 1
  void loadBilling()
}

function handleBillingPageSizeChange() {
  billingPage.value = 1
  void loadBilling()
}

async function loadUsageAnalytics() {
  if (current.value.key !== 'logs') return
  usageAnalyticsLoading.value = true
  usageAnalyticsError.value = ''
  try {
    const response = await http.get('/api/user/usage/analytics', {
      params: { from: usageRange.value?.[0], to: usageRange.value?.[1], model: usageModel.value || undefined }
    })
    usageAnalytics.value = response.data || { daily: [], totals: {}, tokenComposition: [] }
  } catch (error: unknown) {
    usageAnalyticsError.value = getHttpErrorNotice(error, '用量图表加载失败')
  } finally {
    usageAnalyticsLoading.value = false
  }
}

async function loadOverviewUsage() {
  if (current.value.key !== 'overview') return
  overviewUsageError.value = ''
  try {
    const response = await http.get('/api/user/usage/analytics', {
      params: { from: ninetyDaysAgo.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10) }
    })
    overviewUsageAnalytics.value = response.data || { daily: [] }
  } catch (error: unknown) {
    overviewUsageError.value = getHttpErrorNotice(error, '近 90 天用量加载失败')
  }
}

function usageMetric(row: any, key: string) {
  if (!row) return 0
  return Number(row[key] ?? row[key.toUpperCase()] ?? 0)
}

const usageDays = computed(() => {
  const rows = Array.isArray(usageAnalytics.value.daily) ? usageAnalytics.value.daily : []
  const max = Math.max(1, ...rows.map((row: any) => usageMetric(row, 'total_tokens')))
  return rows.map((row: any) => {
    const total = usageMetric(row, 'total_tokens')
    return {
      date: String(row.usage_day ?? row.USAGE_DAY ?? ''), total,
      height: Math.max(total ? 8 : 0, total / max * 100),
      parts: [
        { name: '输入未命中', className: 'miss', value: usageMetric(row, 'cache_miss_tokens') },
        { name: '缓存命中', className: 'hit', value: usageMetric(row, 'cache_read_tokens') },
        { name: '缓存写入', className: 'write', value: usageMetric(row, 'cache_write_tokens') },
        { name: '输出', className: 'output', value: usageMetric(row, 'completion_tokens') }
      ]
    }
  })
})
const usageComposition = computed(() => {
  const colors = ['#78ad30', '#35a7a0', '#e6a23c', '#6b7fd7']
  return (usageAnalytics.value.tokenComposition || []).map((slice: any, index: number) => ({
    name: slice.name, value: Number(slice.value || 0), color: colors[index % colors.length]
  }))
})
const usageTotalTokens = computed(() => usageComposition.value.reduce((sum: number, slice: any) => sum + slice.value, 0))
const usagePieStyle = computed(() => {
  const total = Math.max(1, usageTotalTokens.value)
  let cursor = 0
  const stops = usageComposition.value.map((slice: any) => {
    const start = cursor; cursor += slice.value / total * 100
    return `${slice.color} ${start}% ${cursor}%`
  })
  return { background: `conic-gradient(${stops.length ? stops.join(',') : '#e9eeeb 0 100%'})` }
})

async function loadWallet() {
  if (current.value.key !== 'wallet') return
  walletLoading.value = true
  try {
    const [response, ordersResponse] = await Promise.all([
      http.get('/api/platform/user/wallet'),
      http.get('/api/platform/user/recharge-orders')
    ])
    wallet.value = {
      balance: amountValue(response.data?.balance),
      monthSpend: amountValue(response.data?.monthSpend),
      giftBalance: amountValue(response.data?.giftBalance),
      invoiceableAmount: amountValue(response.data?.invoiceableAmount),
      transactions: Array.isArray(response.data?.transactions) ? response.data.transactions : [],
      plans: Array.isArray(response.data?.plans) ? response.data.plans : []
    }
    rechargeOrders.value = Array.isArray(ordersResponse.data) ? ordersResponse.data : []
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '钱包数据加载失败'))
  } finally {
    walletLoading.value = false
  }
}

function formatMoneyDto(money?: MoneyDto | null) {
  if (!money || !Number(money.scale)) return '—'
  return `${money.currency || 'CNY'} ${(Number(money.amount) / Number(money.scale)).toFixed(2)}`
}

function openRecharge(plan: RechargePlan) {
  selectedRechargePlan.value = plan
  rechargeForm.value.contactEmail = String(dashboard.value?.profile?.email || dashboard.value?.user?.email || '')
  rechargeVisible.value = true
}

async function openRechargePanel() {
  if (route.path !== '/console/wallet') await router.push('/console/wallet')
  if (!wallet.value.plans.length) await loadWallet()
  selectedRechargePlan.value = wallet.value.plans[0] || null
  rechargeVisible.value = true
}

async function submitRecharge() {
  if (!selectedRechargePlan.value) return
  if (rechargeForm.value.needInvoice && Object.entries(rechargeForm.value)
    .some(([key, value]) => !['paymentMethod', 'needInvoice'].includes(key) && !String(value).trim())) {
    ElMessage.warning('请完整填写账单信息')
    return
  }
  rechargeSubmitting.value = true
  const paymentWindow = window.open('', '_blank')
  try {
    const created = await http.post('/api/platform/user/recharge-orders',
      { planId: selectedRechargePlan.value.id, ...rechargeForm.value },
      { headers: { 'Idempotency-Key': createIdempotencyKey('wallet-recharge-order') } })
    const intentId = created.data?.paymentIntent?.id
    if (!intentId) throw new Error('支付意图创建失败')
    const started = await http.post(`/api/payment-intents/${intentId}/start`, {},
      { headers: { 'Idempotency-Key': createIdempotencyKey(`payment-start-${intentId}`) } })
    rechargeVisible.value = false
    if (started.data?.paymentUrl) paymentWindow?.location.replace(started.data.paymentUrl)
    else paymentWindow?.close()
    ElMessage.success(started.data?.status === 'PAID' ? '充值已到账' : '充值订单已创建，请完成付款')
    await Promise.all([loadWallet(), loadDashboard()])
    if (started.data?.status !== 'PAID') pollPayment(intentId)
  } catch (error: unknown) {
    paymentWindow?.close()
    ElMessage.error(getHttpErrorMessage(error, error instanceof Error ? error.message : '充值订单创建失败'))
  } finally {
    rechargeSubmitting.value = false
  }
}

function pollPayment(intentId: number) {
  if (paymentPollTimer !== null) window.clearInterval(paymentPollTimer)
  let attempts = 0
  paymentPollTimer = window.setInterval(async () => {
    if (++attempts > 100) {
      if (paymentPollTimer !== null) window.clearInterval(paymentPollTimer)
      paymentPollTimer = null
      return
    }
    try {
      const response = await http.post(`/api/payment-intents/${intentId}/query`, {},
        { headers: { 'Idempotency-Key': createIdempotencyKey(`payment-query-${intentId}`) } })
      if (response.data?.status === 'PAID') {
        if (paymentPollTimer !== null) window.clearInterval(paymentPollTimer)
        paymentPollTimer = null
        ElMessage.success('支付已确认，充值额度已到账')
        await Promise.all([loadWallet(), loadDashboard()])
      }
    } catch { /* callback may not be visible to the query endpoint yet */ }
  }, 3000)
}

async function downloadRecharge(id: number, type: 'invoice' | 'receipt') {
  try {
    const response = await http.get(`/api/platform/user/recharge-orders/${id}/${type}`, { responseType: 'blob' })
    const url = URL.createObjectURL(response.data)
    const anchor = document.createElement('a'); anchor.href = url; anchor.download = `${type}-${id}.pdf`; anchor.click()
    URL.revokeObjectURL(url)
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '文件下载失败')) }
}

async function redeemWalletCode() {
  const code = redeemCode.value.trim()
  if (!code) {
    ElMessage.warning('请输入兑换码')
    return
  }
  redeeming.value = true
  try {
    const response = await http.post('/api/platform/user/wallet/redeem', { code })
    redeemCode.value = ''
    ElMessage.success(`兑换成功，到账 ${formatCny(response.data?.amount || 0)}`)
    await Promise.all([loadWallet(), loadDashboard()])
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '兑换失败，请检查兑换码'))
  } finally {
    redeeming.value = false
  }
}

onMounted(() => {
  loadDashboard().catch(error => ElMessage.error(getHttpErrorMessage(error, '控制台数据加载失败')))
  loadBilling()
  loadOverviewUsage()
  loadWallet()
})
watch(() => route.path, () => {
  loadDashboard().catch(error => ElMessage.error(getHttpErrorMessage(error, '控制台数据加载失败')))
  loadBilling()
  loadOverviewUsage()
  loadWallet()
})
onBeforeUnmount(() => { if (paymentPollTimer !== null) window.clearInterval(paymentPollTimer) })

const stats = computed(() => dashboard.value.stats || {})
const balance = computed<AmountUnits>(() => amountValue(stats.value.balance))
const monthlyAmount = computed<AmountUnits>(() => amountValue(stats.value.monthlyAmount))
const enabledTokenCount = computed(() => Number(stats.value.enabledTokenCount || 0))
const tokenCount = computed(() => Number(stats.value.tokenCount ?? dashboard.value.tokens?.length ?? 0))
const todayRequestCount = computed(() => Number(stats.value.todayRequestCount || 0))
const totalTokensUsed = computed(() => Number(stats.value.totalTokensUsed || 0))
const requestCount = computed(() => Number(stats.value.requestCount || 0))

const amountValue = (value: unknown): AmountUnits => {
  if (typeof value === 'bigint' || typeof value === 'number' || typeof value === 'string') return value
  return 0
}
const money = (value: unknown) => formatCny(amountValue(value))
const number = (value: unknown) => Number(value || 0).toLocaleString('zh-CN')
const compactNumber = (value: unknown) => Intl.NumberFormat('zh-CN', {
  notation: 'compact',
  maximumFractionDigits: 1
}).format(Number(value || 0))
const maskKey = (value: string) => {
  if (!value) return ''
  if (value.length <= 12) return `${value.slice(0, 4)}••••`
  return `${value.slice(0, 8)}••••${value.slice(-4)}`
}
const keyPreview = (token: Record<string, any>) => {
  const preview = String(token?.keyPreview || '')
  if (preview) return preview
  const legacyValue = String(token?.key || '')
  return legacyValue ? maskKey(legacyValue) : 'sk-at-••••'
}
const formatDateTime = (value: string) => {
  if (!value) return '从未调用'
  return new Date(value).toLocaleString('zh-CN', { hour12: false })
}

const metrics = computed(() => [
  { label: '当前余额', value: money(balance.value), badge: '可用', tone: 'green' },
  { label: '今日请求', value: number(todayRequestCount.value), badge: `${number(requestCount.value)} 总请求`, tone: 'blue' },
  { label: 'Token 消耗', value: compactNumber(totalTokensUsed.value), badge: '累计', tone: 'orange' },
  { label: '可用 Key', value: number(enabledTokenCount.value), badge: `${number(tokenCount.value)} 总数`, tone: 'purple' }
])

const quickActions = [
  { label: '创建 Key', desc: '为新项目分配调用凭证', path: '/console/keys', icon: Key },
  { label: '在线调试', desc: '验证模型、路由和响应', path: '/console/playground', icon: Monitor },
  { label: '模型广场', desc: '查看可用模型和供应商', path: '/market', icon: Compass },
  { label: '购买服务', desc: '在其他服务中选择并创建订单', path: '/services', icon: ShoppingCart }
]

const activities = computed(() => (dashboard.value.recentLogs || []).slice(0, 6).map((log: any) => ({
  title: `${log.model || '未知模型'} ${log.status || 'UNKNOWN'}`,
  desc: `${log.totalTokens || 0} tokens`,
  time: formatDateTime(log.createdAt),
  tone: String(log.status || '').toUpperCase() === 'SUCCESS' ? 'green' : 'orange'
})))

const keys = computed(() => (dashboard.value.tokens || []).map((token: any) => ({
  name: token.name || `Key #${token.id}`,
  key: keyPreview(token),
  quota: `${number(token.usedQuota)} / ${number(token.totalQuota)}`,
  models: token.allowAllModels
    ? '全部可用模型'
    : (Array.isArray(token.allowedModelIds) && token.allowedModelIds.length
      ? token.allowedModelIds.join(', ')
      : modelScopeLabel(token.allowedModels)),
  lastUsed: token.requestCount > 0 ? `${number(token.requestCount)} 次 / ${number(token.totalTokens)} tokens` : '从未调用',
  status: token.enabled ? '启用' : '停用'
})))

const genericRows = [
  { name: 'OpenAI 兼容调用', desc: 'Base URL 指向平台网关即可复用 SDK。', status: '可用' },
  { name: '余额扣费', desc: '按模型倍率和 token 消耗自动扣减余额。', status: '启用' },
  { name: '日志审计', desc: '记录请求、模型、耗时、Token 和错误原因。', status: '运行中' }
]
</script>
