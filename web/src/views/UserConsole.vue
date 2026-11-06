<template>
  <div class="public-site user-console-page">
    <div class="user-console user-console-embedded">
      <header class="user-mobile-header">
        <button class="site-menu-toggle" type="button" aria-label="打开用户工作台菜单" @click="mobileMenuOpen = true">
          <el-icon><Menu /></el-icon>
        </button>
        <div>
          <strong>{{ current.label }}</strong>
          <small>{{ currentUser?.username || '用户工作台' }}</small>
        </div>
        <el-button size="small" type="primary" @click="openRechargePanel">充值</el-button>
      </header>

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

      <el-drawer
        v-model="mobileMenuOpen"
        class="user-mobile-drawer"
        direction="ltr"
        size="min(340px, 90vw)"
        :show-close="false"
        append-to-body
      >
        <template #header><span class="sr-only">用户工作台导航</span></template>
        <div class="workspace-mobile-menu">
          <div class="mobile-menu-head">
            <button class="site-brand console-brand" type="button" @click="go('/console')">
              <img class="site-brand-mark brand-image" src="/brand/linknux-mark-192.png" alt="" />
              <span><strong>用户工作台</strong><small>{{ currentUser?.username || '已登录账号' }}</small></span>
            </button>
            <button class="mobile-menu-close" type="button" aria-label="关闭用户工作台菜单" @click="mobileMenuOpen = false">
              <el-icon><Close /></el-icon>
            </button>
          </div>
          <div class="workspace-mobile-balance">
            <span>账户余额</span>
            <strong>{{ money(balance) }}</strong>
          </div>
          <nav class="workspace-mobile-links" aria-label="用户工作台移动导航">
            <button v-for="item in navItems" :key="item.path" :class="{ active: isActive(item.path) }" @click="go(item.path)">
              <component :is="item.icon" />
              <span>{{ item.label }}</span>
            </button>
          </nav>
          <div class="workspace-mobile-actions">
            <el-button type="primary" @click="openRechargePanel(); mobileMenuOpen = false">
              <el-icon><Wallet /></el-icon>充值 / 购买
            </el-button>
            <el-button @click="go('/')"><el-icon><HomeFilled /></el-icon>返回首页</el-button>
            <el-button type="danger" plain @click="logout"><el-icon><SwitchButton /></el-icon>退出登录</el-button>
          </div>
        </div>
      </el-drawer>

      <main class="user-main">
        <header class="console-hero">
          <div class="console-hero-copy">
            <p class="eyebrow"><i class="eyebrow-dot" aria-hidden="true"></i>{{ current.eyebrow }}</p>
            <h1>{{ current.title }}</h1>
            <span class="console-hero-sub">{{ current.subtitle }}</span>
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
            <article v-for="metric in metrics" :key="metric.label" class="console-stat polished" :data-tone="metric.tone">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
              <span :class="['console-status-note', `is-${metric.tone}`]">{{ metric.badge }}</span>
              <el-icon class="console-stat-icon" aria-hidden="true"><component :is="metric.icon" /></el-icon>
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
              <el-empty v-if="activities.length === 0" description="暂无调用记录" :image-size="72" />
              <PagedList v-else :data="activities" list-id="UserConsole.vue-1" v-slot="{items:pagedItems}"><div class="activity-row" v-for="item in pagedItems" :key="item.title">
                <span :class="item.tone"></span>
                <p>
                  <strong>{{ item.title }}</strong>
                  <small>{{ item.time }} · {{ item.desc }}</small>
                </p>
              </div></PagedList>
            </div>
          </section>
        </section>

        <UserKeysPanel v-else-if="current.key === 'keys'" :state="keysFeature" />

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
                    :label="model.providerGroup?.channel === 'AiAPIBank' ? `${model.upstreamModelName || model.publicName} · ${String(model.providerGroup.name || '')} · AiAPIBank` : `${model.publicName} · ${model.sourceName || '平台智能路由'}`"
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

        <UserUsagePanel v-else-if="current.key === 'logs'" :state="usageFeature" :models="dashboard.models || []" />

        <section v-else-if="current.key === 'model-probe'" class="console-panel">
          <ModelProbePanel endpoint="/user/model-probe" />
        </section>

        <UserWalletPanel v-else-if="current.key === 'wallet'" :state="walletFeature" />

        <section v-else class="console-panel">
          <div class="panel-head">
            <div>
              <h2>{{ current.title }}</h2>
              <p>{{ current.subtitle }}</p>
            </div>
            <el-button type="primary" @click="go(current.actionPath)">{{ current.action }}</el-button>
          </div>
          <PagedTable :data="genericRows" border list-id="UserConsole-6">
            <el-table-column prop="name" label="项目" min-width="180" />
            <el-table-column prop="desc" label="说明" min-width="260" />
            <el-table-column prop="status" label="状态" min-width="120" />
          </PagedTable>
        </section>
      </main>

      <UserKeyDialogs :state="keysFeature" />
      <PaymentActionDialog v-model="paymentDialogVisible" :action="paymentAction" :payment-method="rechargeForm.paymentMethod" />
    </div>
  </div>
</template>

<script setup lang="ts">
import UserKeysPanel from '@/features/user-console/UserKeysPanel.vue';
import UserKeyDialogs from '@/features/user-console/UserKeyDialogs.vue';
import UserUsagePanel from '@/features/user-console/UserUsagePanel.vue';
import UserWalletPanel from '@/features/user-console/UserWalletPanel.vue';
import { useUserKeys } from '@/features/user-console/useUserKeys';
import { useUserUsage } from '@/features/user-console/useUserUsage';
import { useUserWallet } from '@/features/user-console/useUserWallet';
import { amountValue, money, number, compactNumber, keyPreview, formatDateTime, copyText } from '@/features/user-console/format';

import PagedList from "@/components/PagedList.vue";

import PagedTable from '@/components/PagedTable.vue';
import PaymentActionDialog from '@/components/PaymentActionDialog.vue';

import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Close, Compass, DataLine, Document, HomeFilled, Key, MagicStick, Menu, Monitor, Promotion, ShoppingCart, SwitchButton, Tickets, Wallet, User } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { clearAuth, getUser } from '@/utils/auth';
import http, { getHttpErrorMessage } from '@/utils/http';
import { formatPerMillionUsd, formatUsd, type AmountUnits } from '@/utils/money';

import ModelSalePricing from '@/components/ModelSalePricing.vue';
import DeveloperDocs from '@/components/DeveloperDocs.vue';
import ProfileCenter from '@/components/ProfileCenter.vue';
import AccountMenu from '@/components/AccountMenu.vue';
import UsageTimelineChart from '@/components/UsageTimelineChart.vue';
import ModelProbePanel from '@/components/ModelProbePanel.vue';
import { modelsAllowedForToken, normalizeModelCatalog, type CallableModel } from '@/utils/modelCatalog';

const router = useRouter()
const route = useRoute()
const mobileMenuOpen = ref(false)
const currentUser = computed(() => getUser())
const dashboard = ref<any>({ stats: {}, tokens: [], recentLogs: [], models: [], modelCatalog: [] as CallableModel[] })
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
const navItems = [
  { key: 'overview', label: '总览', path: '/console', icon: DataLine, title: '业务总览', eyebrow: '用户中心', subtitle: '查看账户额度、调用趋势、快捷入口和最近活动。', action: '刷新', actionPath: '/console' },
  { key: 'keys', label: 'API Key', path: '/console/keys', icon: Key, title: 'API Key 管理', eyebrow: '凭证管理', subtitle: '创建和管理不同环境的调用凭证。', action: '新建 Key', actionPath: '/console/keys' },
  { key: 'playground', label: '在线调试', path: '/console/playground', icon: Monitor, title: '在线调试', eyebrow: '请求测试', subtitle: '无需写代码，直接验证模型返回。', action: '发送请求', actionPath: '/console/playground' },
  { key: 'logs', label: '用量日志', path: '/console/logs', icon: Tickets, title: '用量日志', eyebrow: '审计记录', subtitle: '按 Key、模型、日期追踪请求消耗。', action: '导出 CSV', actionPath: '/console/logs' },
  { key: 'wallet', label: '钱包充值', path: '/console/wallet', icon: Wallet, title: '钱包充值', eyebrow: '余额中心', subtitle: '充值余额、查看消耗和账单状态。', action: '去充值', actionPath: '/pricing' },
  { key: 'agent', label: '代理中心', path: '/console/agent', icon: Promotion, title: '代理中心', eyebrow: '合作伙伴', subtitle: '查看代理佣金与提现。', action: '进入代理中心', actionPath: '/console/agent' },
  { key: 'referrals', label: '邀请有礼', path: '/console/referrals', icon: Promotion, title: '邀请有礼', eyebrow: '邀请好友', subtitle: '邀请好友首购订阅，领取优惠券。', action: '进入邀请有礼', actionPath: '/console/referrals' },
  { key: 'docs', label: '文档 SDK', path: '/console/docs', icon: Document, title: '开发文档', eyebrow: '接入指南', subtitle: '在工作台内查看多客户端配置和调用方式。', action: '查看文档', actionPath: '/console/docs' },
  { key: 'model-probe', label: '模型鉴别', path: '/console/model-probe', icon: MagicStick, title: '模型鉴别', eyebrow: '质量与身份', subtitle: '对任意 OpenAI 兼容端点执行探针，检测模型调包 / 降级并获取 0-100 评分报告。', action: '开始鉴别', actionPath: '/console/model-probe' },
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
const walletFeature = useUserWallet(dashboard, current, loadDashboard)
const { loadWallet, openRechargePanel, paymentDialogVisible, paymentAction, rechargeForm } = walletFeature
const usageFeature = useUserUsage(current)
const { loadBilling, loadOverviewUsage, overviewUsageAnalytics, overviewUsageError } = usageFeature
const keysFeature = useUserKeys(dashboard, loadDashboard)
const { openCreateKey } = keysFeature

const primaryAction = computed(() => {
  if (current.value.key === 'overview') return { label: '在线调试', path: '/console/playground', icon: Monitor }
  if (current.value.key === 'wallet') return { label: '兑换码充值', path: '/console/wallet', icon: Wallet }
  return { label: current.value.action, path: current.value.actionPath, icon: current.value.icon }
})

const go = (path: string) => {
  mobileMenuOpen.value = false
  return router.push(path)
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

const stats = computed(() => dashboard.value.stats || {})
const balance = computed<AmountUnits>(() => amountValue(stats.value.balance))
const monthlyAmount = computed<AmountUnits>(() => amountValue(stats.value.monthlyAmount))
const enabledTokenCount = computed(() => Number(stats.value.enabledTokenCount || 0))
const tokenCount = computed(() => Number(stats.value.tokenCount ?? dashboard.value.tokens?.length ?? 0))
const todayRequestCount = computed(() => Number(stats.value.todayRequestCount || 0))
const totalTokensUsed = computed(() => Number(stats.value.totalTokensUsed || 0))
const requestCount = computed(() => Number(stats.value.requestCount || 0))

const metrics = computed(() => [
  { label: '当前余额', value: money(balance.value), badge: '可用', tone: 'green', icon: Wallet },
  { label: '今日请求', value: number(todayRequestCount.value), badge: `${number(requestCount.value)} 总请求`, tone: 'blue', icon: DataLine },
  { label: 'Token 消耗', value: compactNumber(totalTokensUsed.value), badge: '累计', tone: 'orange', icon: Tickets },
  { label: '可用 Key', value: number(enabledTokenCount.value), badge: `${number(tokenCount.value)} 总数`, tone: 'purple', icon: Key }
])

const quickActions = [
  { label: '创建 Key', desc: '为新项目分配调用凭证', path: '/console/keys', icon: Key },
  { label: '在线调试', desc: '验证模型、路由和响应', path: '/console/playground', icon: Monitor },
  { label: '模型广场', desc: '查看可用模型和供应商', path: '/market', icon: Compass },
  { label: '购买服务', desc: '在AI订阅中选择并创建订单', path: '/services', icon: ShoppingCart }
]

const activities = computed(() => (dashboard.value.recentLogs || []).slice(0, 6).map((log: any) => ({
  title: `${log.model || '未知模型'} ${log.status || 'UNKNOWN'}`,
  desc: `${log.totalTokens || 0} tokens`,
  time: formatDateTime(log.createdAt),
  tone: String(log.status || '').toUpperCase() === 'SUCCESS' ? 'green' : 'orange'
})))

const genericRows = [
  { name: 'OpenAI 兼容调用', desc: 'Base URL 指向平台网关即可复用 SDK。', status: '可用' },
  { name: '余额扣费', desc: '按模型倍率和 token 消耗自动扣减余额。', status: '启用' },
  { name: '日志审计', desc: '记录请求、模型、耗时、Token 和错误原因。', status: '运行中' }
]
</script>
