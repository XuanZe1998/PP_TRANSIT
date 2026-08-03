<template>
  <div class="public-site user-console-page">
    <header class="site-nav">
      <button class="site-brand" @click="go('/')">
        <span class="site-brand-mark">A</span>
        <span>API Transit</span>
      </button>
      <nav class="site-links" aria-label="User primary navigation">
        <button :class="{ active: isActive('/') }" @click="go('/')">首页</button>
        <button :class="{ active: isActive('/market') }" @click="go('/market')">模型广场</button>
        <button :class="{ active: isActive('/studio') }" @click="go('/studio')">AI创作</button>
        <button :class="{ active: isActive('/services') }" @click="go('/services')">其他服务</button>
        <button :class="{ active: isActive('/pricing') }" @click="go('/pricing')">套餐价格</button>
        <button :class="{ active: isActive('/docs') }" @click="go('/docs')">开发文档</button>
        <button :class="{ active: route.path.startsWith('/console') }" @click="go('/console')">控制台</button>
      </nav>
      <div class="site-actions">
        <el-button @click="go('/pricing')">购买商品</el-button>
        <el-button type="primary" @click="logout">退出登录</el-button>
      </div>
    </header>

    <div class="user-console user-console-embedded">
      <aside class="user-sidebar">
        <button class="site-brand console-brand" @click="go('/console')">
          <span class="site-brand-mark">A</span>
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
            @click="go(item.path)"
          >
            <component :is="item.icon" />
            <span>{{ item.label }}</span>
          </button>
        </nav>

        <section class="sidebar-balance">
          <span>账户余额</span>
          <strong>{{ money(balance) }}</strong>
          <el-button size="small" type="primary" @click="go('/pricing')">充值 / 购买</el-button>
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
          </div>
        </header>

        <section v-if="current.key === 'overview'" class="console-overview">
          <div class="console-metrics">
            <article v-for="metric in metrics" :key="metric.label" class="console-stat polished">
              <span>{{ metric.label }}</span>
              <strong>{{ metric.value }}</strong>
              <em :class="metric.tone">{{ metric.badge }}</em>
            </article>
          </div>

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
                <el-button
                  v-if="primaryAccessKey"
                  size="small"
                  disabled
                  title="完整 API Key 仅在创建成功时显示一次"
                >
                  仅创建时可见
                </el-button>
                <el-button v-else size="small" type="primary" @click="openCreateKey">创建 Key</el-button>
              </div>
            </div>
          </section>

          <section class="console-panel usage-panel">
            <div class="panel-head">
              <div>
                <h2>调用趋势</h2>
                <p>近 12 天请求量，按用户 API Key 汇总。</p>
              </div>
              <el-button size="small" @click="go('/console/logs')">查看日志</el-button>
            </div>
            <div class="usage-chart enhanced">
              <span v-for="(bar, index) in bars" :key="index" :style="{ height: `${bar}%` }">
                <i>{{ index + 1 }}</i>
              </span>
            </div>
          </section>

          <section class="console-panel quota-panel">
            <div class="panel-head">
              <div>
                <h2>额度概览</h2>
                <p>当前账户可用余额与本月消耗。</p>
              </div>
              <strong>{{ quotaPercent }}%</strong>
            </div>
            <el-progress :percentage="quotaPercent" :stroke-width="14" :show-text="false" />
            <div class="quota-grid">
              <span><b>{{ money(balance) }}</b> 可用余额</span>
              <span><b>{{ money(monthlyAmount) }}</b> 本月消耗</span>
              <span><b>{{ number(enabledTokenCount) }}</b> 可用 Key</span>
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
          <section class="console-panel">
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
                    :label="`${model.publicName} · ${model.type}`"
                    :value="model.publicName"
                  />
                  <template #empty>
                    <div class="playground-model-empty">当前 API Key 没有可调用模型</div>
                  </template>
                </el-select>
                <div v-if="selectedModelDetail" class="playground-model-summary">
                  <span>{{ selectedModelDetail.type }}</span>
                  <span>{{ selectedModelDetail.routeCount }} 条可用路由</span>
                  <span>输入 {{ formatPerMillionCny(selectedModelDetail.minInputPricePerMillion) }}</span>
                  <span>输出 {{ formatPerMillionCny(selectedModelDetail.minOutputPricePerMillion) }}</span>
                  <span>缓存读 {{ formatPerMillionCny(selectedModelDetail.minCacheReadPricePerMillion) }}</span>
                  <span>缓存写 {{ formatPerMillionCny(selectedModelDetail.minCacheWritePricePerMillion) }}</span>
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
                  :rows="8"
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

          <section v-if="playgroundUsage" class="console-panel playground-billing-panel">
            <div class="playground-result-head">
              <div>
                <h2>本次用量与费用</h2>
                <p>Token、单价与扣费均由服务端计算。</p>
              </div>
              <el-tag v-if="playgroundUsage.estimated" type="warning">Token 为服务端估算</el-tag>
              <el-tag v-else type="success">服务端已结算</el-tag>
            </div>
            <section class="playground-usage-summary">
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
                  <strong>{{ formatCny(playgroundUsage.totalAmount) }}</strong>
                </article>
              </div>
              <div class="playground-rate-grid">
                <div><span>计价挡位</span><b>{{ playgroundUsage.priceTier || '默认挡位' }}</b></div>
                <div><span>售价组 / 单位</span><b>{{ playgroundUsage.saleGroupName || '本站售价' }} · {{ playgroundUsage.priceUnit || 'M' }}</b></div>
                <div><span>价格后缀</span><b>{{ playgroundUsage.priceSuffix || 'CNY / 1M Token' }}</b></div>
                <div><span>输入单价</span><b>{{ formatPerMillionCny(playgroundUsage.inputPricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
                <div><span>输出单价</span><b>{{ formatPerMillionCny(playgroundUsage.outputPricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
                <div><span>缓存读取单价</span><b>{{ formatPerMillionCny(playgroundUsage.cacheReadPricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
                <div><span>缓存写入单价</span><b>{{ formatPerMillionCny(playgroundUsage.cacheWritePricePerMillion, playgroundUsage.priceUnit, playgroundUsage.priceSuffix) }}</b></div>
              </div>
              <div class="playground-cost-breakdown">
                <span>输入费用 {{ formatCny(playgroundUsage.inputAmount) }}</span>
                <span>输出费用 {{ formatCny(playgroundUsage.outputAmount) }}</span>
                <span>缓存读取费用 {{ formatCny(playgroundUsage.cacheReadAmount) }}</span>
                <span>缓存写入费用 {{ formatCny(playgroundUsage.cacheWriteAmount) }}</span>
              </div>
              <p class="playground-billing-note">
                Token、单价和扣费均由服务端根据管理员模型配置计算并落账，浏览器不参与计费。
              </p>
            </section>
          </section>
        </section>

        <section v-else-if="current.key === 'logs'" class="console-panel">
          <div class="panel-head">
            <div>
              <h2>模型账单明细</h2>
              <p>按模型、Key 和日期聚合输入、输出、缓存命中 token 及扣费。</p>
            </div>
            <el-button @click="loadBilling">刷新</el-button>
          </div>
          <el-table :data="billingSummary" border>
            <el-table-column prop="model" label="模型" min-width="180" />
            <el-table-column prop="request_count" label="请求" width="90" />
            <el-table-column prop="prompt_tokens" label="输入 Token" width="130" />
            <el-table-column prop="completion_tokens" label="输出 Token" width="130" />
            <el-table-column prop="cached_tokens" label="缓存 Token" width="130" />
            <el-table-column label="输入费用(CNY)" width="150">
              <template #default="{ row }">{{ formatCny(row.input_amount) }}</template>
            </el-table-column>
            <el-table-column label="输出费用(CNY)" width="150">
              <template #default="{ row }">{{ formatCny(row.output_amount) }}</template>
            </el-table-column>
            <el-table-column label="缓存费用(CNY)" width="150">
              <template #default="{ row }">{{ formatCny(row.cached_amount) }}</template>
            </el-table-column>
            <el-table-column label="总费用(CNY)" width="150">
              <template #default="{ row }">{{ formatCny(row.total_amount) }}</template>
            </el-table-column>
          </el-table>

          <el-table :data="billingRows" border class="billing-detail-table">
            <el-table-column prop="created_at" label="时间" min-width="170" />
            <el-table-column prop="trace_id" label="Trace ID" min-width="150" />
            <el-table-column prop="token_name" label="Key" min-width="140" />
            <el-table-column prop="model" label="模型" min-width="160" />
            <el-table-column prop="prompt_tokens" label="输入" width="90" />
            <el-table-column prop="completion_tokens" label="输出" width="90" />
            <el-table-column prop="cached_tokens" label="缓存" width="90" />
            <el-table-column label="扣费(CNY)" width="140">
              <template #default="{ row }">{{ formatCny(row.total_amount) }}</template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="110" />
            <el-table-column prop="error_message" label="错误" min-width="220" />
          </el-table>
        </section>

        <section v-else-if="current.key === 'wallet'" class="wallet-layout" v-loading="walletLoading">
          <div class="console-metrics wallet-metrics">
            <article class="console-stat polished">
              <span>可用余额</span>
              <strong>{{ formatCny(wallet.balance) }}</strong>
              <em class="green">CNY · 10,000 units = ¥1</em>
            </article>
            <article class="console-stat polished">
              <span>本月消耗</span>
              <strong>{{ formatCny(wallet.monthSpend) }}</strong>
              <em class="orange">按真实账单汇总</em>
            </article>
            <article class="console-stat polished">
              <span>赠送额度</span>
              <strong>{{ formatCny(wallet.giftBalance) }}</strong>
              <em class="blue">CNY 等值额度</em>
            </article>
            <article class="console-stat polished">
              <span>可开票金额</span>
              <strong>{{ formatCny(wallet.invoiceableAmount) }}</strong>
              <em class="purple">以服务端账本为准</em>
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
                <span>{{ formatCny(plan.amount) }}</span>
                <small>赠送 {{ Number(plan.bonus || 0).toFixed(2) }}%</small>
              </article>
            </div>
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Compass, DataLine, Document, Key, Monitor, ShoppingCart, Tickets, Wallet } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { clearAuth, getUser } from '@/utils/auth'
import http, { getHttpErrorMessage } from '@/utils/http'
import { formatCny, formatPerMillionCny, formatSignedCny, type AmountUnits } from '@/utils/money'
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
  totalQuota: 0
})
const walletLoading = ref(false)
const redeeming = ref(false)
const redeemCode = ref('')
const wallet = ref({
  balance: 0 as AmountUnits,
  monthSpend: 0 as AmountUnits,
  giftBalance: 0 as AmountUnits,
  invoiceableAmount: 0 as AmountUnits,
  transactions: [] as Array<Record<string, any>>,
  plans: [] as Array<{ id: number | string; name: string; amount: AmountUnits; bonus?: number }>
})

const navItems = [
  { key: 'overview', label: '总览', path: '/console', icon: DataLine, title: '业务总览', eyebrow: '用户中心', subtitle: '查看账户额度、调用趋势、快捷入口和最近活动。', action: '刷新', actionPath: '/console' },
  { key: 'keys', label: 'API Key', path: '/console/keys', icon: Key, title: 'API Key 管理', eyebrow: '凭证管理', subtitle: '创建和管理不同环境的调用凭证。', action: '新建 Key', actionPath: '/console/keys' },
  { key: 'playground', label: '在线调试', path: '/console/playground', icon: Monitor, title: '在线调试', eyebrow: '请求测试', subtitle: '无需写代码，直接验证模型返回。', action: '发送请求', actionPath: '/console/playground' },
  { key: 'logs', label: '用量日志', path: '/console/logs', icon: Tickets, title: '用量日志', eyebrow: '审计记录', subtitle: '按 Key、模型、日期追踪请求消耗。', action: '导出 CSV', actionPath: '/console/logs' },
  { key: 'wallet', label: '钱包充值', path: '/console/wallet', icon: Wallet, title: '钱包充值', eyebrow: '余额中心', subtitle: '充值余额、查看消耗和账单状态。', action: '去充值', actionPath: '/pricing' },
  { key: 'docs', label: '文档 SDK', path: '/docs', icon: Document, title: '开发文档', eyebrow: '接入指南', subtitle: '查看 Base URL、SDK 示例和模型调用方式。', action: '查看文档', actionPath: '/docs' }
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
    document.getElementById('redeem-code')?.focus()
    return
  }
  go(primaryAction.value.path)
}

const openCreateKey = () => {
  clearCreatedKey()
  createKeyForm.value = { name: '', totalQuota: 0 }
  createKeyVisible.value = true
}

const createKey = async () => {
  const name = createKeyForm.value.name.trim()
  if (!name) {
    ElMessage.warning('请输入 Key 名称')
    return
  }
  createKeySubmitting.value = true
  try {
    const response = await http.post('/api/user/tokens', {
      name,
      totalQuota: createKeyForm.value.totalQuota
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
    priceSuffix: String(billing.price_suffix || 'CNY / 1M Token'),
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
  const [summaryRes, logsRes] = await Promise.all([
    http.get('/api/user/billing/summary'),
    http.get('/api/user/billing/logs')
  ])
  billingSummary.value = summaryRes.data || []
  billingRows.value = logsRes.data || []
}

async function loadWallet() {
  if (current.value.key !== 'wallet') return
  walletLoading.value = true
  try {
    const response = await http.get('/api/platform/user/wallet')
    wallet.value = {
      balance: amountValue(response.data?.balance),
      monthSpend: amountValue(response.data?.monthSpend),
      giftBalance: amountValue(response.data?.giftBalance),
      invoiceableAmount: amountValue(response.data?.invoiceableAmount),
      transactions: Array.isArray(response.data?.transactions) ? response.data.transactions : [],
      plans: Array.isArray(response.data?.plans) ? response.data.plans : []
    }
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '钱包数据加载失败'))
  } finally {
    walletLoading.value = false
  }
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
  loadBilling().catch(error => ElMessage.error(getHttpErrorMessage(error, '账单加载失败')))
  loadWallet()
})
watch(() => route.path, () => {
  loadDashboard().catch(error => ElMessage.error(getHttpErrorMessage(error, '控制台数据加载失败')))
  loadBilling().catch(error => ElMessage.error(getHttpErrorMessage(error, '账单加载失败')))
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

const bars = computed(() => {
  const days = Array.from({ length: 12 }, (_, index) => {
    const date = new Date()
    date.setDate(date.getDate() - (11 - index))
    return date.toISOString().slice(0, 10)
  })
  const countByDay = new Map(days.map(day => [day, 0]))
  for (const log of dashboard.value.recentLogs || []) {
    if (!log.createdAt) continue
    const day = String(log.createdAt).slice(0, 10)
    if (countByDay.has(day)) {
      countByDay.set(day, (countByDay.get(day) || 0) + 1)
    }
  }
  const values = days.map(day => countByDay.get(day) || 0)
  const max = Math.max(...values, 0)
  return values.map(value => (max === 0 ? 0 : Math.max(8, Math.round((value / max) * 92))))
})

const quotaPercent = computed(() => {
  const currentBalance = Number(balance.value)
  const currentMonth = Number(monthlyAmount.value)
  const total = currentBalance + currentMonth
  if (total <= 0) return 0
  return Math.min(100, Math.round((currentBalance / total) * 100))
})

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
  models: modelScopeLabel(token.allowedModels),
  lastUsed: token.requestCount > 0 ? `${number(token.requestCount)} 次 / ${number(token.totalTokens)} tokens` : '从未调用',
  status: token.enabled ? '启用' : '停用'
})))

const genericRows = [
  { name: 'OpenAI 兼容调用', desc: 'Base URL 指向平台网关即可复用 SDK。', status: '可用' },
  { name: '余额扣费', desc: '按模型倍率和 token 消耗自动扣减余额。', status: '启用' },
  { name: '日志审计', desc: '记录请求、模型、耗时、Token 和错误原因。', status: '运行中' }
]
</script>
