<template>
        <section class="wallet-layout" v-loading="walletLoading">
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
            <div class="wallet-plan-grid">
              <PagedList :data="wallet.plans" list-id="UserConsole.vue-2" v-slot="{items:pagedItems}"><article v-for="plan in pagedItems" :key="plan.id">
                <strong>{{ plan.name }}</strong>
                <span>{{ formatMoneyDto(plan.paymentMoney) }}</span>
                <small>赠送 {{ Number(plan.bonus || 0).toFixed(2) }}% · 到账 {{ formatMoneyDto(plan.totalCreditMoney) }}</small>
                <el-button type="primary" :loading="rechargeSubmitting" @click="openRecharge(plan)">购买</el-button>
              </article></PagedList>
              <article>
                <strong>自定义充值</strong>
                <span>任意金额</span>
                <small>充值金额必须大于 ¥0.00，不参与套餐赠送</small>
                <el-button type="primary" :loading="rechargeSubmitting" @click="openCustomRecharge">自定义金额</el-button>
              </article>
            </div>
          </section>

          <section class="console-panel wallet-transactions">
            <div class="panel-head"><div><h2>充值订单</h2><p>金额由服务端套餐快照确定，付款后可下载账单和收据。</p></div></div>
            <PagedTable :data="rechargeOrders" border empty-text="暂无充值订单" list-id="UserConsole-4" pagination="external">
              <el-table-column prop="orderNo" label="订单号" min-width="220" />
              <el-table-column prop="planName" label="套餐" min-width="130" />
              <el-table-column label="实付" width="130"><template #default="{ row }">{{ formatMoneyDto(row.paymentMoney) }}</template></el-table-column>
              <el-table-column label="到账" width="130"><template #default="{ row }">{{ formatMoneyDto(row.totalCreditMoney) }}</template></el-table-column>
              <el-table-column prop="status" label="状态" width="120" />
              <el-table-column label="操作" min-width="210"><template #default="{ row }">
                <el-button v-if="row.invoiceRequested" link type="primary" @click="downloadRecharge(row.id, 'invoice')">账单</el-button>
                <el-button v-if="['PAID','REFUNDED'].includes(row.status)" link type="success" @click="downloadRecharge(row.id, 'receipt')">收据</el-button>
              </template></el-table-column>
            </PagedTable>
            <SelectablePagination
              v-model:current-page="rechargeOrderPage"
              v-model:page-size="rechargeOrderPageSize"
              layout="total, sizes, prev, pager, next, jumper"
              :page-sizes="[10, 20, 50, 100]"
              :total="rechargeOrderTotal"
              list-id="UserConsole-recharge-orders"
              @current-change="loadWallet"
              @size-change="handleRechargeOrderPageSizeChange"
            />
          </section>

          <section class="console-panel wallet-transactions">
            <div class="panel-head">
              <div>
                <h2>余额流水</h2>
                <p>金额与余额均按 10,000 amount units = ¥1.00 CNY 换算。</p>
              </div>
              <el-button :loading="walletLoading" @click="loadWallet">刷新</el-button>
            </div>
            <PagedTable :data="wallet.transactions" border empty-text="暂无余额流水" list-id="UserConsole-5" pagination="external">
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
            </PagedTable>
            <SelectablePagination
              v-model:current-page="walletTransactionPage"
              v-model:page-size="walletTransactionPageSize"
              class="billing-pagination"
              layout="total, sizes, prev, pager, next, jumper"
              :page-sizes="[10, 20, 50, 100]"
              :total="walletTransactionTotal"
              @current-change="loadWallet"
              @size-change="handleWalletTransactionPageSizeChange"
             list-id="UserConsole-2"/>
          </section>

          <el-dialog
            v-model="rechargeVisible"
            class="recharge-dialog"
            modal-class="recharge-overlay"
            title="充值方案"
            width="620px"
            destroy-on-close
            :close-on-click-modal="!rechargeSubmitting"
            :close-on-press-escape="!rechargeSubmitting"
            :show-close="!rechargeSubmitting"
          >
            <div class="recharge-choices">
              <PagedList v-if="wallet.plans.length" :data="wallet.plans" list-id="UserConsole.vue-3" v-slot="{ items }">
                <button
                  v-for="plan in items"
                  :key="plan.id"
                  type="button"
                  class="recharge-choice"
                  :aria-pressed="selectedRechargePlan?.id === plan.id"
                  :disabled="rechargeSubmitting"
                  @click="selectRechargePlan(plan)"
                >
                  <span><strong>{{ plan.name }}</strong><small>赠送 {{ Number(plan.bonus || 0).toFixed(2) }}%</small></span>
                  <b>{{ formatMoneyDto(plan.paymentMoney) }}</b>
                </button>
              </PagedList>
              <button
                type="button"
                class="recharge-choice recharge-choice-custom"
                :aria-pressed="customRecharge"
                :disabled="rechargeSubmitting"
                @click="selectCustomRecharge"
              >
                <strong>自定义充值</strong>
                <small>无赠送</small>
              </button>
            </div>
            <el-form class="recharge-form" label-position="top" :disabled="rechargeSubmitting" @submit.prevent>
              <dl v-if="selectedRechargePlan" class="recharge-totals">
                <div><dt>实付</dt><dd>{{ formatMoneyDto(selectedRechargePlan.paymentMoney) }}</dd></div>
                <div><dt>到账</dt><dd>{{ formatMoneyDto(selectedRechargePlan.totalCreditMoney) }}</dd></div>
              </dl>
              <el-form-item v-if="customRecharge" required>
                <template #label><span>充值金额</span> ({{ displayCurrency }})</template>
                <el-input-number v-model="customRechargeAmount" :min="0.01" :precision="2" :controls="false" class="recharge-amount" />
                <div class="form-hint">金额必须大于 0，最多保留两位小数。</div>
              </el-form-item>
              <el-form-item label="支付方式">
                <el-radio-group v-model="rechargeForm.paymentMethod" class="recharge-methods">
                  <el-radio border value="alipay">支付宝</el-radio>
                  <el-radio border value="wxpay">微信支付</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item v-if="wallet.invoiceEnabled" label="需要开具账单 / 发票"><el-switch v-model="rechargeForm.needInvoice" /></el-form-item>
              <template v-if="wallet.invoiceEnabled && rechargeForm.needInvoice">
                <el-form-item label="姓名或公司名称"><el-input v-model="rechargeForm.billingName" /></el-form-item>
                <el-form-item label="接收邮箱"><el-input v-model="rechargeForm.contactEmail" /></el-form-item>
                <el-form-item label="详细地址"><el-input v-model="rechargeForm.billingAddressLine1" placeholder="省/市以下的街道、门牌、小区等" /></el-form-item>
                <el-row :gutter="12"><el-col :span="12"><el-form-item label="区/县"><el-input v-model="rechargeForm.billingDistrict" /></el-form-item></el-col><el-col :span="12"><el-form-item label="城市"><el-input v-model="rechargeForm.billingCity" /></el-form-item></el-col></el-row>
                <el-row :gutter="12"><el-col :span="12"><el-form-item label="省份"><el-input v-model="rechargeForm.billingProvince" /></el-form-item></el-col><el-col :span="12"><el-form-item label="邮编"><el-input v-model="rechargeForm.billingPostalCode" /></el-form-item></el-col></el-row>
                <el-form-item label="国家/地区"><el-input v-model="rechargeForm.billingCountry" /></el-form-item>
              </template>
              <div class="recharge-disclosure">
                <p>模型调用按实际用量另行扣费；税费、汇率和最终金额请以支付订单为准。</p>
                <p class="recharge-policy-links">
                  <router-link to="/refund" target="_blank">退款与取消</router-link>
                  <router-link to="/terms" target="_blank">服务条款</router-link>
                  <router-link to="/ai-data" target="_blank">AI 数据处理</router-link>
                </p>
              </div>
            </el-form>
            <template #footer>
              <div class="recharge-consent">
                <el-alert v-if="rechargeLegalLoading" type="info" :closable="false" title="正在加载交易条件…" />
                <el-alert v-else-if="rechargeLegal?.checkout_ready === false" type="warning" :closable="false" title="在线收款开关已关闭，请联系平台客服。" />
                <el-alert v-else-if="!rechargeCheckoutReady" type="warning" :closable="false" title="交易条件暂不可用，请稍后重试。" />
                <el-checkbox v-model="checkoutAcknowledged" :disabled="!rechargeCheckoutReady || rechargeSubmitting">我已阅读本次交易条件、退款与取消政策</el-checkbox>
              </div>
              <div class="recharge-actions">
                <el-button :disabled="rechargeSubmitting" @click="rechargeVisible = false">取消</el-button>
                <el-button type="primary" :disabled="!canSubmitRecharge" :loading="rechargeSubmitting" @click="submitRecharge">立即支付</el-button>
              </div>
            </template>
          </el-dialog>
        </section>


</template>
<script setup lang="ts">
import PagedTable from '@/components/PagedTable.vue';
import PagedList from '@/components/PagedList.vue';
import SelectablePagination from '@/components/SelectablePagination.vue';
import { formatCny, formatSignedCny } from '@/utils/money';
import { displayCurrency } from '@/i18n/locale';
import type { useUserWallet } from './useUserWallet';
const props = defineProps<{ state: ReturnType<typeof useUserWallet> }>()
const {
  walletLoading,
  walletTransactionPage,
  walletTransactionPageSize,
  walletTransactionTotal,
  redeeming,
  redeemCode,
  rechargeVisible,
  rechargeSubmitting,
  rechargeLegal,
  rechargeLegalLoading,
  checkoutAcknowledged,
  selectedRechargePlan,
  customRecharge,
  customRechargeAmount,
  rechargeCheckoutReady,
  canSubmitRecharge,
  rechargeOrders,
  rechargeOrderPage,
  rechargeOrderPageSize,
  rechargeOrderTotal,
  rechargeForm,
  wallet,
  loadWallet,
  formatMoneyDto,
  openRecharge,
  handleWalletTransactionPageSizeChange,
  handleRechargeOrderPageSizeChange,
  openCustomRecharge,
  selectRechargePlan,
  selectCustomRecharge,
  submitRecharge,
  downloadRecharge,
  redeemWalletCode,
} = props.state
</script>

<style scoped>
:global(#app .recharge-dialog.el-dialog) { padding: 0; overflow: hidden; }
:global(#app .recharge-dialog .el-dialog__header) { padding: 16px 20px; }
:global(#app .recharge-dialog .el-dialog__body) { padding: 16px 20px; max-height: none; }
:global(#app .recharge-dialog .el-dialog__footer) { padding: 10px 20px 14px; }

.recharge-choices {
  display: grid;
  gap: 8px;
}

.recharge-choice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  min-height: 52px;
  padding: 10px 14px;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  color: var(--el-text-color-primary);
  font: inherit;
  text-align: start;
  cursor: pointer;
}

.recharge-choice span {
  display: grid;
  gap: 3px;
  overflow-wrap: anywhere;
}

.recharge-choice small { color: var(--el-text-color-secondary); }
.recharge-choice b { flex-shrink: 0; }
.recharge-choice[aria-pressed="true"] {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}
.recharge-choice:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.recharge-choice:disabled { cursor: default; }
.recharge-choices :deep(.list-pagination) { padding: 0; gap: 8px; }
.recharge-form { margin-top: 16px; }
.recharge-amount { width: 100%; }

.recharge-totals {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 16px;
  padding: 12px 14px;
  border-radius: 10px;
  background: var(--el-fill-color-light);
}
.recharge-totals dt { color: var(--el-text-color-secondary); font-size: 13px; }
.recharge-totals dd { margin: 4px 0 0; color: var(--el-text-color-primary); font-size: 18px; font-weight: 700; overflow-wrap: anywhere; }
.recharge-methods { width: 100%; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.recharge-methods :deep(.el-radio) { justify-content: center; margin: 0; min-width: 0; min-height: 40px; }

.recharge-disclosure {
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 10px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
}
.recharge-disclosure p { margin: 0 0 8px; }
.recharge-policy-links { display: flex; flex-wrap: wrap; gap: 4px 14px; }
.recharge-policy-links a { color: var(--el-color-primary); }
.recharge-policy-links:last-child { margin-bottom: 0; }
.recharge-consent { margin-bottom: 6px; text-align: start; }
.recharge-consent :deep(.el-checkbox) { height: auto; min-height: 32px; max-width: 100%; white-space: normal; }
.recharge-consent :deep(.el-checkbox__label) { line-height: 1.5; }
.recharge-actions { display: flex; justify-content: flex-end; gap: 8px; }
.recharge-actions :deep(.el-button + .el-button) { margin: 0; }
:global(#app .recharge-dialog .recharge-actions .el-button--primary.is-disabled) {
  opacity: .5 !important;
  box-shadow: none !important;
}
@media (max-width: 520px) {
  :global(.recharge-overlay .el-overlay-dialog) { padding-bottom: 56px; }
  :global(#app .recharge-dialog.el-dialog) { max-height: calc(100dvh - 68px); }
  .recharge-actions :deep(.el-button--primary) { flex: 1; }
}
</style>
