<template>
  <div class="admin-console">
    <section class="console-toolbar">
      <div>
        <h2>{{ config.title }}</h2>
        <p>{{ config.description }}</p>
      </div>
      <div class="toolbar-actions">
        <el-input v-model="query" clearable :placeholder="module === 'audit' ? '搜索 Trace、用户或模型' : '搜索当前列表'" :prefix-icon="Search" @keyup.enter="module === 'audit' ? loadAuditLogs() : undefined" />
        <el-button :icon="Refresh" @click="load">刷新</el-button>
        <el-button v-if="config.createLabel" type="primary" :icon="Plus" @click="openCreate">
          {{ config.createLabel }}
        </el-button>
      </div>
    </section>

    <section v-if="metrics.length" class="metric-grid admin-metrics">
      <article v-for="metric in metrics" :key="metric.label" class="metric-card">
        <span>{{ metric.label }}</span>
        <div>
          <strong>{{ metric.value }}</strong>
          <em :class="`tone-${metric.tone}`">{{ metric.badge }}</em>
        </div>
      </article>
    </section>

    <template v-if="module === 'dashboard'">
      <section class="admin-grid">
        <article class="panel">
          <div class="panel-head">
            <h3>渠道健康</h3>
            <el-tag type="success">{{ rows.length }} 个渠道</el-tag>
          </div>
          <el-table :data="dashboard.channelHealth || []" size="small">
            <el-table-column prop="name" label="渠道" min-width="160" />
            <el-table-column prop="type" label="类型" width="120" />
            <el-table-column prop="health_status" label="健康" width="120">
              <template #default="{ row }">
                <el-tag :type="healthType(row.health_status)">{{ row.health_status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="weight" label="权重" width="90" />
          </el-table>
        </article>
        <article class="panel">
          <div class="panel-head">
            <h3>风险队列</h3>
            <el-tag type="warning">{{ (dashboard.riskQueue || []).length }} 条</el-tag>
          </div>
          <el-empty v-if="!(dashboard.riskQueue || []).length" description="暂无风险" />
          <div v-else class="risk-list">
            <div v-for="risk in dashboard.riskQueue" :key="risk.title">
              <span></span>
              <p><strong>{{ risk.title }}</strong><small>{{ risk.type }} / {{ risk.severity }}</small></p>
            </div>
          </div>
        </article>
      </section>
      <section class="panel dashboard-usage-panel">
        <div v-if="dashboardUsageError" class="module-error-state">
          <span>{{ dashboardUsageError }}</span>
          <el-button link type="primary" @click="loadDashboardUsage">重试用量图表</el-button>
        </div>
        <AdminUsageCharts :data="dashboardUsage" :kpi-totals="dashboardTodayUsage.totals" :pie-rows="dashboardTodayUsage.dailyByModel" title="近 7 日模型 Token、成本与收益（上方及饼图为今日）" />
      </section>
    </template>

    <template v-else-if="module === 'finance'">
      <div v-if="financeErrors.summary" class="module-error-state">
        <span>{{ financeErrors.summary }}</span>
        <el-button link type="primary" @click="loadFinanceSection('summary')">重试财务汇总</el-button>
      </div>
      <section class="admin-grid">
        <article class="panel wide-panel">
          <div class="panel-head">
            <h3>钱包流水</h3>
            <el-button type="primary" :icon="Plus" @click="openCreate">生成兑换码</el-button>
          </div>
          <div v-if="financeErrors.transactions" class="module-error-state">
            <span>{{ financeErrors.transactions }}</span>
            <el-button link type="primary" @click="loadFinanceSection('transactions')">重试</el-button>
          </div>
          <el-table :data="filteredRows" v-loading="loading">
            <el-table-column prop="username" label="用户" min-width="120" />
            <el-table-column prop="type" label="类型" width="120" />
            <el-table-column label="金额(CNY)" width="150">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column label="余额(CNY)" width="150">
              <template #default="{ row }">{{ money(row.balance_after) }}</template>
            </el-table-column>
            <el-table-column prop="channel" label="渠道" width="120" />
            <el-table-column prop="remark" label="备注" min-width="180" />
            <el-table-column prop="created_at" label="时间" min-width="180" />
          </el-table>
        </article>
        <article class="panel">
          <div class="panel-head">
            <h3>兑换码</h3>
          </div>
          <div v-if="financeErrors.codes" class="module-error-state">
            <span>{{ financeErrors.codes }}</span>
            <el-button link type="primary" @click="loadFinanceSection('codes')">重试</el-button>
          </div>
          <el-table :data="secondaryRows" size="small">
            <el-table-column prop="code" label="兑换码" min-width="150" />
            <el-table-column label="额度(CNY)" width="140">
              <template #default="{ row }">{{ money(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="used_count" label="已用" width="90" />
            <el-table-column prop="max_uses" label="上限" width="90" />
          </el-table>
        </article>
        <article class="panel wide-panel">
          <div class="panel-head">
            <div><h3>充值套餐</h3><small>金额和赠送比例将作为订单快照保存</small></div>
            <el-button type="primary" @click="openRechargePlan()">新增套餐</el-button>
          </div>
          <div v-if="financeErrors.plans" class="module-error-state">
            <span>{{ financeErrors.plans }}</span>
            <el-button link type="primary" @click="loadFinanceSection('plans')">重试</el-button>
          </div>
          <el-table :data="rechargePlans" size="small">
            <el-table-column prop="name" label="套餐" min-width="150" />
            <el-table-column label="售价(CNY)" width="150"><template #default="{ row }">{{ money(row.amount) }}</template></el-table-column>
            <el-table-column prop="bonus_percent" label="赠送比例" width="120"><template #default="{ row }">{{ row.bonus_percent }}%</template></el-table-column>
            <el-table-column prop="sort_order" label="排序" width="90" />
            <el-table-column label="启用" width="90"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '是' : '否' }}</el-tag></template></el-table-column>
            <el-table-column label="操作" width="140"><template #default="{ row }">
              <el-button link type="primary" @click="openRechargePlan(row)">编辑</el-button>
              <el-button link type="danger" @click="removeRechargePlan(row)">删除</el-button>
            </template></el-table-column>
          </el-table>
        </article>
      </section>
      <el-dialog v-model="rechargePlanVisible" :title="rechargePlanForm.id ? '编辑充值套餐' : '新增充值套餐'" width="480px">
        <el-form label-position="top">
          <el-form-item label="套餐名称"><el-input v-model="rechargePlanForm.name" maxlength="120" /></el-form-item>
          <el-form-item label="售价（内部单位，10,000 = ¥1）"><el-input-number v-model="rechargePlanForm.amount" :min="1" :step="10000" style="width:100%" /></el-form-item>
          <el-form-item label="赠送比例 %"><el-input-number v-model="rechargePlanForm.bonusPercent" :min="0" :max="1000" style="width:100%" /></el-form-item>
          <el-form-item label="排序"><el-input-number v-model="rechargePlanForm.sortOrder" :min="0" style="width:100%" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="rechargePlanForm.enabled" /></el-form-item>
        </el-form>
        <template #footer><el-button @click="rechargePlanVisible=false">取消</el-button><el-button type="primary" :loading="rechargePlanSaving" @click="saveRechargePlan">保存</el-button></template>
      </el-dialog>
    </template>

    <template v-else-if="module === 'settings'">
      <section class="admin-grid">
        <article class="panel">
          <div class="panel-head">
            <h3>系统配置</h3>
            <el-button type="primary" :icon="Plus" @click="openCreate">保存配置</el-button>
          </div>
          <el-table :data="filteredRows" v-loading="loading">
            <el-table-column prop="setting_key" label="键" min-width="180" />
            <el-table-column prop="setting_value" label="值" min-width="220" />
            <el-table-column prop="description" label="说明" min-width="200" />
            <el-table-column label="操作" width="100">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              </template>
            </el-table-column>
          </el-table>
        </article>
        <article class="panel">
          <div class="panel-head">
            <h3>成本报表</h3>
            <el-tag>实时聚合</el-tag>
          </div>
          <div class="report-summary">
            <div><span>模型收入</span><strong>{{ formatUsd(report.revenue) }}</strong></div>
            <div><span>模型成本</span><strong>{{ formatUsd(report.cost) }}</strong></div>
            <div><span>毛利率</span><strong>{{ percent(report.grossMargin) }}</strong></div>
            <div><span>P95 延迟</span><strong>{{ report.p95LatencyMs || 0 }} ms</strong></div>
          </div>
          <el-table :data="report.models || []" size="small">
            <el-table-column prop="model" label="模型" min-width="160" />
            <el-table-column prop="requests" label="请求" width="100" />
            <el-table-column prop="tokens" label="Token" width="120" />
            <el-table-column label="模型收入(USD)" width="150">
              <template #default="{ row }">{{ formatUsd(row.revenue) }}</template>
            </el-table-column>
          </el-table>
        </article>
      </section>
    </template>

    <template v-else>
      <section class="panel">
        <div v-if="module === 'audit'" class="admin-usage-analytics">
          <el-alert title="请先选择个人用户或企业用户；企业审计需要继续选择企业后才能搜索成员和模型。" type="info" :closable="false" show-icon />
          <div class="usage-filter-row">
            <el-select v-model="adminUsageFilters.audienceType" placeholder="第一步：选择用户类型" @change="handleAudienceChange">
              <el-option label="个人用户" value="PERSONAL" /><el-option label="企业用户" value="COMPANY" />
            </el-select>
            <el-select v-if="adminUsageFilters.audienceType === 'COMPANY'" v-model="adminUsageFilters.organizationId" clearable filterable remote :remote-method="loadAuditOptions" placeholder="选择或搜索企业" @change="handleOrganizationChange">
              <el-option v-for="item in auditOptions.organizations" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
            <el-select v-model="adminUsageFilters.userId" clearable filterable remote :remote-method="loadAuditOptions" :disabled="!auditFilterReady" placeholder="选择或搜索用户">
              <el-option v-for="item in auditOptions.users" :key="item.id" :label="`${item.username}${item.email ? ` · ${item.email}` : ''}`" :value="item.id" />
            </el-select>
            <el-select v-model="adminUsageFilters.model" clearable filterable remote :remote-method="loadAuditOptions" :disabled="!auditFilterReady" placeholder="选择或搜索模型">
              <el-option v-for="item in auditOptions.models" :key="item.model" :label="item.model" :value="item.model" />
            </el-select>
            <el-date-picker v-model="adminUsageFilters.range" type="daterange" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期" />
            <el-button type="primary" :disabled="!auditFilterReady" @click="applyAuditFilters">查询审计</el-button>
          </div>
          <div v-if="adminUsageError" class="module-error-state"><span>{{ adminUsageError }}</span><el-button link type="primary" @click="loadAdminUsage">重试统计</el-button></div>
          <AdminUsageCharts :data="adminUsage" title="筛选范围内的模型用量与收益" show-table />
          <div v-if="auditLogsError" class="module-error-state"><span>{{ auditLogsError }}</span><el-button link type="primary" @click="loadAuditLogs">重试日志</el-button></div>
        </div>
        <div v-if="module === 'security'" class="security-workspace">
          <el-tabs v-model="securityTab">
            <el-tab-pane label="通用策略" name="policies">
              <el-alert title="通用策略用于限流、告警和人工审核；敏感词请在独立词库中配置。" type="info" :closable="false" show-icon />
              <el-table :data="rows" size="small" max-height="540" style="margin-top:14px">
                <el-table-column prop="name" label="策略" min-width="160" />
                <el-table-column prop="scope" label="范围" min-width="140" />
                <el-table-column prop="action" label="动作" width="130" />
                <el-table-column prop="threshold_value" label="阈值" min-width="160" />
                <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="valueOf(row, 'enabled') ? 'success' : 'info'">{{ valueOf(row, 'enabled') ? '启用' : '停用' }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="openEdit(row)">编辑</el-button></template></el-table-column>
              </el-table>
            </el-tab-pane>
            <el-tab-pane label="敏感词库" name="words">
              <el-alert
                title="敏感词不是系统自动认定的固定名单：只有管理员保存并启用的词条才会生效，内置示例均保持停用。"
                type="warning"
                :closable="false"
                show-icon
              />
              <div class="sensitive-guide-grid">
                <article><strong>怎么匹配</strong><span>“包含”匹配文本中的任意位置；“完整”只匹配整个文本。匹配前会统一大小写和全角字符。</span></article>
                <article><strong>命中后做什么</strong><span>阻断会拒绝请求；告警和审核会放行请求并记录安全事件。</span></article>
                <article><strong>在哪里生效</strong><span>可配置为全局、指定企业、指定用户或指定 API Key，范围越具体影响越小。</span></article>
                <article><strong>建议分类</strong><span>违法交易、凭证泄露、隐私信息、暴力威胁、色情及未成年人、业务自定义。</span></article>
              </div>
              <div class="security-toolbar">
                <div><strong>实际生效的敏感词</strong><span>模板默认停用；只检测文本，不保存原始 Prompt。</span></div>
                <div><el-button @click="bulkVisible = true">批量导入</el-button><el-button type="primary" @click="openSensitiveWord()">新增词条</el-button></div>
              </div>
              <el-table :data="sensitiveWords" size="small" max-height="520">
                <el-table-column prop="term" label="词条" min-width="170" />
                <el-table-column prop="category" label="分类" min-width="130" />
                <el-table-column label="匹配" width="100"><template #default="{ row }">{{ valueOf(row, 'match_mode') === 'EXACT' ? '完整' : '包含' }}</template></el-table-column>
                <el-table-column prop="action" label="动作" width="100" />
                <el-table-column label="范围" min-width="140"><template #default="{ row }">{{ sensitiveScopeLabel(row) }}</template></el-table-column>
                <el-table-column label="状态" width="90"><template #default="{ row }"><el-tag :type="valueOf(row, 'enabled') ? 'success' : 'info'">{{ valueOf(row, 'enabled') ? '启用' : '停用' }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="130"><template #default="{ row }"><el-button link type="primary" @click="openSensitiveWord(row)">编辑</el-button><el-button link type="danger" @click="removeSensitiveWord(row)">删除</el-button></template></el-table-column>
              </el-table>
              <div class="sensitive-test-box">
                <div><strong>匹配测试</strong><span>测试不会写入安全事件。</span></div>
                <el-input v-model="sensitiveTestText" type="textarea" :rows="3" placeholder="输入一段文本验证当前已启用词条" />
                <el-button :disabled="!sensitiveTestText.trim()" @click="testSensitiveText">开始测试</el-button>
                <div v-if="sensitiveTestMatches.length" class="tag-row"><el-tag v-for="item in sensitiveTestMatches" :key="item.id" type="warning">{{ item.term }} · {{ item.action }}</el-tag></div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="安全事件" name="events">
              <el-table :data="securityEvents" size="small" max-height="580">
                <el-table-column prop="created_at" label="时间" min-width="170" />
                <el-table-column prop="trace_id" label="Trace ID" min-width="150" />
                <el-table-column prop="username" label="用户" min-width="120" />
                <el-table-column prop="model" label="模型" min-width="160" />
                <el-table-column prop="category" label="分类" min-width="130" />
                <el-table-column prop="matched_term" label="命中词条" min-width="160" />
                <el-table-column prop="action" label="动作" width="100" />
              </el-table>
            </el-tab-pane>
          </el-tabs>
        </div>
        <div v-if="module === 'models'" class="model-filter-bar">
          <el-select v-model="modelProviderFilter" clearable placeholder="按模型厂商筛选" style="width: 220px">
            <el-option v-for="provider in modelProviderOptions" :key="provider" :label="provider" :value="provider" />
          </el-select>
          <el-tag type="success">{{ callablePublicModelCount }} 个前台可用模型</el-tag>
          <el-tag>{{ callableRouteCount }} / {{ filteredRows.length }} 条路由可调用</el-tag>
        </div>
        <el-alert
          v-if="module === 'models' && rows.length > 0 && callableRouteCount === 0"
          title="当前没有可供用户调用的模型。请启用模型映射，并确认绑定渠道已启用、已配置 API Key 且健康检查通过。"
          type="warning"
          :closable="false"
          show-icon
          class="model-availability-alert"
        />
        <div v-if="module !== 'security'" class="admin-table-shell" :class="`admin-table-${module}`">
        <el-table :data="displayRows" v-loading="loading" row-key="id" scrollbar-always-on :max-height="gatewayTableMaxHeight">
          <el-table-column v-for="column in config.columns" :key="column.prop" :prop="column.prop" :label="column.label" :min-width="column.minWidth" :width="column.width" :fixed="gatewayModules.includes(module) && column === config.columns[0] ? 'left' : undefined">
            <template #default="{ row }">
              <el-tag v-if="column.kind === 'status'" :type="statusType(getValue(row, column.prop))">{{ getValue(row, column.prop) }}</el-tag>
              <el-tag v-else-if="column.kind === 'bool'" :type="getValue(row, column.prop) ? 'success' : 'info'">{{ getValue(row, column.prop) ? '启用' : '停用' }}</el-tag>
              <el-tag v-else-if="column.kind === 'callable'" :type="getValue(row, column.prop) ? 'success' : 'warning'">
                {{ getValue(row, column.prop) ? '可调用' : '不可调用' }}
              </el-tag>
              <span v-else-if="column.kind === 'money'">{{ money(getValue(row, column.prop)) }}</span>
              <span v-else-if="column.kind === 'modelMoney'">{{ formatUsd(getValue(row, column.prop)) }}</span>
              <span v-else-if="column.kind === 'rate'">{{ rateMoney(getValue(row, column.prop)) }}</span>
              <span v-else-if="column.kind === 'profit'" :class="{ 'negative-profit': routeHasLoss(row) }">{{ routeProfitSummary(row) }}</span>
              <div v-else-if="column.kind === 'tierRange'" class="tier-table-cell">
                <span v-for="(line, index) in tierRangeLines(row)" :key="index">{{ line }}</span>
              </div>
              <div v-else-if="column.kind === 'tierOfficial'" class="tier-table-cell">
                <span v-for="(line, index) in tierPriceLines(row, 'official')" :key="index">{{ line }}</span>
              </div>
              <div v-else-if="column.kind === 'tierCost'" class="tier-table-cell">
                <span v-for="(line, index) in tierPriceLines(row, 'cost')" :key="index">{{ line }}</span>
              </div>
              <div v-else-if="column.kind === 'tierSale'" class="tier-table-cell">
                <span v-for="(line, index) in tierPriceLines(row, 'sale')" :key="index">{{ line }}</span>
              </div>
              <div v-else-if="column.kind === 'tierProfit'" class="tier-table-cell" :class="{ 'negative-profit': routeHasTierLoss(row) }">
                <span v-for="(line, index) in tierProfitLines(row)" :key="index">{{ line }}</span>
              </div>
              <span v-else>{{ display(getValue(row, column.prop)) }}</span>
            </template>
          </el-table-column>
          <el-table-column v-if="config.editable" label="操作" :width="module === 'channels' ? 300 : 220" fixed="right">
            <template #default="{ row }">
              <el-button v-if="module === 'channels'" link type="primary" @click="testChannel(row)">测试</el-button>
              <el-button v-if="module === 'channels'" link type="success" @click="openModelDiscovery(row)">同步模型</el-button>
              <el-button v-if="module === 'users'" link type="primary" @click="openAdjust(row)">调账</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="config.deletable" link type="danger" @click="removeRow(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        </div>
        <el-pagination
          v-if="module === 'models'"
          v-model:current-page="modelPage"
          v-model:page-size="modelPageSize"
          class="admin-pagination"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50, 100]"
          :total="filteredRows.length"
        />
        <el-pagination v-if="module === 'audit'" v-model:current-page="auditPage" v-model:page-size="auditPageSize" class="admin-pagination" layout="total, sizes, prev, pager, next" :page-sizes="[20, 50, 100, 200]" :total="auditTotal" @change="loadAuditLogs" />
        <el-collapse
          v-if="module === 'channels'"
          v-model="channelLedgerSections"
          class="channel-test-ledger"
        >
          <el-collapse-item name="test-ledger">
            <template #title>
              <div class="channel-ledger-title">
                <div>
                  <h3>管理员测试账单</h3>
                  <span>展开查看渠道测试产生的 Token、成本和错误记录</span>
                </div>
                <el-tag>{{ testRows.length }} 条</el-tag>
              </div>
            </template>
            <el-table :data="testRows" size="small" :max-height="420" class="channel-ledger-table">
              <el-table-column prop="tested_at" label="时间" min-width="170" />
              <el-table-column prop="channel_name" label="渠道" min-width="150" />
              <el-table-column prop="model_name" label="模型" min-width="180" />
              <el-table-column prop="status" label="状态" width="110" />
              <el-table-column prop="prompt_tokens" label="输入 Token" width="120" />
              <el-table-column prop="completion_tokens" label="输出 Token" width="120" />
              <el-table-column prop="cached_tokens" label="缓存 Token" width="120" />
              <el-table-column label="估算成本(USD)" width="150">
                <template #default="{ row }">{{ formatUsd(row.estimated_cost_amount) }}</template>
              </el-table-column>
              <el-table-column prop="latency_ms" label="耗时(ms)" width="110" />
              <el-table-column prop="error_message" label="错误" min-width="260" />
            </el-table>
          </el-collapse-item>
        </el-collapse>
      </section>
    </template>

    <el-drawer v-model="drawerVisible" :title="drawerTitle" :size="module === 'channels' ? 'min(1320px, 96vw)' : '520px'">
      <el-alert
        v-if="module === 'users'"
        title="用户余额不能在基本资料中修改；请关闭后使用列表中的“调账”操作，以便留下原因和财务流水。"
        type="warning"
        :closable="false"
        show-icon
        class="drawer-alert"
      />
      <el-alert
        v-if="module === 'models'"
        title="模型只有在映射已发布、渠道已启用、API Key 已配置且健康检查通过时，才会显示在模型广场和用户控制台。"
        type="info"
        :closable="false"
        show-icon
        class="drawer-alert"
      />
      <el-form :model="form" label-position="top">
        <el-form-item v-for="field in activeFields" :key="field.prop" :label="field.label">
          <div v-if="module === 'channels' && field.prop === 'models'" class="channel-model-manager">
            <el-select
              v-model="activePricingModel"
              filterable
              allow-create
              default-first-option
              placeholder="选择要配置的模型，或输入名称后按回车添加"
              class="channel-model-select"
              @change="selectOrCreatePricingModel"
            >
              <el-option v-for="model in selectedChannelModels" :key="model" :label="model" :value="model">
                <div class="model-option-row"><span>{{ model }}</span><el-tag v-if="isPricingDirty(model)" size="small" type="warning">未保存</el-tag></div>
              </el-option>
            </el-select>
            <div class="channel-model-manager-actions">
              <el-button
                type="primary"
                plain
                :loading="editorDiscoveryLoading"
                :disabled="!editingId"
                @click="discoverChannelModelsForEditor(editingId, selectedChannelModels.length === 0, true)"
              >自动识别上游模型</el-button>
              <el-button :disabled="channelModelOptions.length === 0" @click="addAllDiscoveredModels">加入全部识别结果</el-button>
              <el-button type="danger" plain :disabled="!activePricingModel" @click="removeActivePricingModel">删除当前模型</el-button>
            </div>
            <p v-if="!editingId" class="form-hint">新渠道保存后会自动识别模型；也可以先手工输入模型名称。</p>
            <p v-else-if="editorDiscoveryError" class="form-hint model-discovery-error">{{ editorDiscoveryError }}，仍可手工增加或删除。</p>
            <p v-else class="form-hint">清单内共 {{ selectedChannelModels.length }} 个模型；下方只显示当前模型配置，切换不会丢失未保存内容。</p>
          </div>
          <el-switch v-else-if="field.type === 'switch'" v-model="form[field.prop]" />
          <el-input-number v-else-if="field.type === 'number'" v-model="form[field.prop]" :min="field.min ?? 0" :step="field.step ?? 1" />
          <el-select v-else-if="field.type === 'select'" v-model="form[field.prop]" filterable>
            <el-option v-for="option in field.options || []" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input v-else-if="field.type === 'textarea'" v-model="form[field.prop]" type="textarea" :rows="4" />
          <el-input v-else v-model="form[field.prop]" :type="field.type === 'password' ? 'password' : 'text'" :show-password="field.type === 'password'" />
        </el-form-item>
        <section v-if="module === 'channels'" class="channel-pricing-editor">
          <div class="channel-pricing-head">
            <div>
              <h3>逐模型采购成本与销售定价</h3>
              <p>已根据模型清单生成 {{ channelModelPricing.length }} 条路由；每个模型单独保存，切换模型不会丢失草稿。</p>
            </div>
            <el-button v-if="channelModelPricing.length > 1" @click="copyFirstPricingToAll">复制第一行定价到全部</el-button>
          </div>
          <el-alert
            title="同一供应商的多个 API Key 请统一添加到当前渠道的凭证池。系统会按优先级、健康状态和并发情况自动选择凭证，无需创建重复渠道。"
            type="info"
            :closable="false"
            show-icon
          />
          <section v-if="editingId" class="credential-pool-card">
            <div class="credential-pool-head">
              <div><strong>渠道凭证池</strong><span>集中维护同一供应商的多个 API Key</span></div>
              <el-button type="primary" plain @click="openCredential()">添加凭证</el-button>
            </div>
            <el-table :data="channelCredentials" size="small" empty-text="暂无独立凭证，将回退到渠道基础 API Key">
              <el-table-column prop="name" label="名称" min-width="150" />
              <el-table-column prop="secretPreview" label="API Key" min-width="130" />
              <el-table-column prop="priority" label="优先级" width="90" />
              <el-table-column prop="healthStatus" label="健康" width="110" />
              <el-table-column label="状态" width="85"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag></template></el-table-column>
              <el-table-column label="操作" width="180" fixed="right"><template #default="{ row }">
                <el-button link type="success" :loading="credentialTestingId === row.id" @click="testCredential(row)">测试</el-button>
                <el-button link type="primary" @click="openCredential(row)">编辑</el-button>
                <el-button link type="danger" @click="removeCredential(row)">删除</el-button>
              </template></el-table-column>
            </el-table>
          </section>
          <el-empty v-if="channelModelPricing.length === 0" description="请先在上方模型清单中填写模型名称，可用逗号、顿号或换行分隔" :image-size="72" />
          <article v-for="pricing in activePricingRows" :key="pricing.channelModelName" class="channel-model-pricing-card">
            <div class="channel-model-pricing-title">
              <div>
                <strong>{{ pricing.channelModelName }}</strong>
                <span>自动公开为同名模型</span>
              </div>
              <div class="channel-model-pricing-switches">
                <el-tag :type="isPricingDirty(pricing.channelModelName) ? 'warning' : 'success'">{{ isPricingDirty(pricing.channelModelName) ? '未保存' : '已保存' }}</el-tag>
                <el-switch v-model="pricing.billingEnabled" active-text="计费" />
                <el-switch v-model="pricing.enabled" active-text="发布" />
                <el-button type="primary" :loading="modelPricingSaving" :disabled="!editingId" @click="saveCurrentModelPricing">保存当前模型</el-button>
              </div>
            </div>
            <div class="channel-price-grid">
              <label>路由优先级<el-input-number v-model="pricing.priority" :min="-10000" :max="10000" :step="10" /></label>
              <label>模型流量比例 %<el-input-number v-model="pricing.trafficPercent" :min="1" :max="100" /></label>
              <label>计费单位<el-select v-model="pricing.pricingUnit"><el-option v-for="unit in pricingUnitOptions" :key="unit.value" :label="unit.label" :value="unit.value" /></el-select></label>
              <label>计费模式<el-select v-model="pricing.billingMode"><el-option label="付费" value="PAID" /><el-option label="免费开发预览" value="FREE_PREVIEW" /><el-option label="停用" value="DISABLED" /></el-select></label>
              <label>价格状态<el-select v-model="pricing.pricingStatus"><el-option label="已核验" value="VERIFIED" /><el-option label="人工估算" value="ESTIMATED" /><el-option label="免费预览" value="FREE_PREVIEW" /><el-option label="待配置" value="PENDING" /></el-select></label>
            </div>
            <div v-if="pricing.pricingUnit === 'TOKEN'" class="price-tier-toolbar">
              <div>
                <strong>上下文价格挡位</strong>
                <span>模型价格统一使用 USD；最后一挡自动覆盖超过前一阈值的上下文。</span>
              </div>
              <el-button type="primary" plain @click="addPriceTier(pricing)">新增挡位</el-button>
            </div>
            <article v-for="(tier, tierIndex) in pricing.pricingUnit === 'TOKEN' ? pricing.priceTiers : []" :key="`${pricing.channelModelName}-${tierIndex}`" class="price-tier-card">
              <div class="price-tier-head">
                <div class="tier-identity">
                  <label>挡位名称<el-input v-model="tier.tierName" maxlength="120" /></label>
                  <label v-if="tierIndex < pricing.priceTiers.length - 1">
                    上下文上限（Token）
                    <el-input-number v-model="tier.maxContextTokens" :min="1" :max="100000000" :step="1000" />
                  </label>
                  <label v-else>上下文上限<el-input model-value="不限制（最终挡位）" disabled /></label>
                </div>
                <div class="tier-head-actions">
                  <el-tag>{{ tierRangeLabel(pricing, tierIndex) }}</el-tag>
                  <el-button v-if="pricing.priceTiers.length > 1" link type="danger" @click="removePriceTier(pricing, tierIndex)">删除挡位</el-button>
                </div>
              </div>
              <div class="tier-price-groups">
                <section class="tier-price-group official-group">
                  <div class="price-group-head">
                    <strong>官网基准</strong>
                    <div class="price-group-meta">
                      <el-input v-model="tier.officialGroupName" maxlength="120" placeholder="价格组名称" />
                      <el-select v-model="tier.officialPriceUnit" class="price-unit-select" aria-label="官网价格单位">
                        <el-option label="M" value="M" />
                        <el-option label="KB" value="KB" />
                      </el-select>
                      <el-input v-model="tier.officialPriceSuffix" maxlength="120" placeholder="价格后缀，如 USD / 1M Token" />
                    </div>
                  </div>
                  <div class="price-dimension-grid">
                    <label>输入<el-input-number v-model="tier.officialInputPrice" :min="0" :step="0.000001" /></label>
                    <label>输出<el-input-number v-model="tier.officialOutputPrice" :min="0" :step="0.000001" /></label>
                    <label>缓存读取<el-input-number v-model="tier.officialCacheReadPrice" :min="0" :step="0.000001" /></label>
                    <label>缓存写入<el-input-number v-model="tier.officialCacheWritePrice" :min="0" :step="0.000001" /></label>
                  </div>
                </section>
                <section class="tier-price-group cost-group">
                  <div class="price-group-head">
                    <strong>采购成本</strong>
                    <div class="price-group-meta">
                      <el-input v-model="tier.costGroupName" maxlength="120" placeholder="例如：供应商 A 0.5 倍 Key" />
                      <el-select v-model="tier.costPriceUnit" class="price-unit-select" aria-label="成本价格单位">
                        <el-option label="M" value="M" />
                        <el-option label="KB" value="KB" />
                      </el-select>
                      <el-input v-model="tier.costPriceSuffix" maxlength="120" placeholder="价格后缀，如 USD / 1M Token" />
                    </div>
                  </div>
                  <div class="price-dimension-grid">
                    <label>输入（{{ tierCostMultiplier(tier, 'input') }}）<el-input-number v-model="tier.costInputPrice" :min="0" :step="0.000001" /></label>
                    <label>输出（{{ tierCostMultiplier(tier, 'output') }}）<el-input-number v-model="tier.costOutputPrice" :min="0" :step="0.000001" /></label>
                    <label>缓存读取（{{ tierCostMultiplier(tier, 'cacheRead') }}）<el-input-number v-model="tier.costCacheReadPrice" :min="0" :step="0.000001" /></label>
                    <label>缓存写入（{{ tierCostMultiplier(tier, 'cacheWrite') }}）<el-input-number v-model="tier.costCacheWritePrice" :min="0" :step="0.000001" /></label>
                  </div>
                </section>
                <section class="tier-price-group sale-group">
                  <div class="price-group-head">
                    <strong>本站售价</strong>
                    <div class="price-group-meta">
                      <el-input v-model="tier.saleGroupName" maxlength="120" placeholder="例如：标准零售价" />
                      <el-select v-model="tier.salePriceUnit" class="price-unit-select" aria-label="售价价格单位">
                        <el-option label="M" value="M" />
                        <el-option label="KB" value="KB" />
                      </el-select>
                      <el-input v-model="tier.salePriceSuffix" maxlength="120" placeholder="价格后缀，如 USD / 1M Token" />
                    </div>
                  </div>
                  <div class="price-dimension-grid">
                    <label>输入<el-input-number v-model="tier.saleInputPrice" :min="0" :step="0.000001" /></label>
                    <label>输出<el-input-number v-model="tier.saleOutputPrice" :min="0" :step="0.000001" /></label>
                    <label>缓存读取<el-input-number v-model="tier.saleCacheReadPrice" :min="0" :step="0.000001" /></label>
                    <label>缓存写入<el-input-number v-model="tier.saleCacheWritePrice" :min="0" :step="0.000001" /></label>
                  </div>
                </section>
              </div>
              <div class="channel-price-actions">
                <span>销售价格独立配置；用户侧展示销售价与成本/官方倍率。</span>
                <span :class="{ loss: tierProfit(tier).hasLoss }">
                  每百万 Token 毛利：输入 {{ decimalMoney(tierProfit(tier).input) }} / 输出 {{ decimalMoney(tierProfit(tier).output) }} / 缓存读取 {{ decimalMoney(tierProfit(tier).cacheRead) }} / 缓存写入 {{ decimalMoney(tierProfit(tier).cacheWrite) }}
                </span>
              </div>
            </article>
            <article v-if="pricing.pricingUnit !== 'TOKEN'" class="unit-price-card">
              <div class="unit-price-head"><strong>{{ pricingUnitLabel(pricing.pricingUnit) }}计费</strong><span>非 Token 模型只维护单一单位价格</span></div>
              <div class="unit-price-grid">
                <label>官网基准（{{ pricingUnitSuffix(pricing.pricingUnit) }}）<el-input-number v-model="pricing.officialUnitPrice" :min="0" :step="0.000001" /></label>
                <label>采购成本（{{ pricingUnitSuffix(pricing.pricingUnit) }}）<el-input-number v-model="pricing.costUnitPrice" :min="0" :step="0.000001" /></label>
                <label>本站售价（{{ pricingUnitSuffix(pricing.pricingUnit) }}）<el-input-number v-model="pricing.saleUnitPrice" :min="0" :step="0.000001" /></label>
              </div>
              <div class="unit-price-grid metadata">
                <label>价格说明<el-input v-model="pricing.pricingMessage" maxlength="500" placeholder="例如：尚未设置按秒售价" /></label>
                <label>价格来源<el-input v-model="pricing.pricingSourceUrl" maxlength="1000" placeholder="https://..." /></label>
              </div>
              <el-alert v-if="pricing.billingMode === 'FREE_PREVIEW'" title="免费开发预览不会扣除余额，但仍执行 API Key 配额、限流与安全策略。" type="warning" :closable="false" />
            </article>
          </article>
        </section>
      </el-form>
      <template #footer>
        <template v-if="module === 'channels'">
          <el-button @click="returnFromChannelEditor">返回列表</el-button>
          <el-button type="primary" :loading="saving" @click="saveChannelBase">保存渠道基础信息</el-button>
        </template>
        <template v-else>
          <el-button @click="drawerVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
        </template>
      </template>
    </el-drawer>

    <el-dialog v-model="credentialVisible" :title="credentialForm.id ? '编辑渠道凭证' : '添加渠道凭证'" width="560px">
      <el-form label-position="top">
        <el-form-item label="凭证名称" required><el-input v-model="credentialForm.name" maxlength="160" /></el-form-item>
        <el-form-item :label="credentialForm.id ? '替换 API Key（留空保持不变）' : 'API Key'" required><el-input v-model="credentialForm.secret" type="password" show-password /></el-form-item>
        <div class="credential-form-grid">
          <el-form-item label="优先级"><el-input-number v-model="credentialForm.priority" :min="-10000" :max="10000" /></el-form-item>
          <el-form-item label="权重"><el-input-number v-model="credentialForm.weight" :min="1" :max="10000" /></el-form-item>
          <el-form-item label="RPM 限制"><el-input-number v-model="credentialForm.rpmLimit" :min="0" /></el-form-item>
          <el-form-item label="TPM 限制"><el-input-number v-model="credentialForm.tpmLimit" :min="0" /></el-form-item>
          <el-form-item label="并发限制"><el-input-number v-model="credentialForm.concurrencyLimit" :min="0" /></el-form-item>
          <el-form-item label="启用"><el-switch v-model="credentialForm.enabled" /></el-form-item>
        </div>
      </el-form>
      <template #footer><el-button @click="credentialVisible=false">取消</el-button><el-button type="primary" :loading="credentialSaving" @click="saveCredential">保存凭证</el-button></template>
    </el-dialog>

    <el-dialog v-model="adjustVisible" title="用户手工调账" width="420px">
      <el-form label-position="top">
        <el-form-item label="用户">
          <el-input :model-value="adjustTarget?.username" disabled />
        </el-form-item>
        <el-form-item label="调整金额（10,000 amount units = ¥1.00 CNY）">
          <el-input-number v-model="adjustForm.amount" :step="10000" :min="-1000000000000" :max="1000000000000" />
          <div class="field-hint">当前调整折合：{{ money(adjustForm.amount) }}</div>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="adjustForm.reason" type="textarea" maxlength="240" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjustVisible = false">取消</el-button>
        <el-button type="primary" :loading="adjustSaving" @click="saveAdjust">确认调账</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="testVisible" title="模型测试 Python 代码" width="900px">
      <el-form label-position="top">
        <el-form-item label="测试模型">
          <el-select
            v-model="testForm.providerModelName"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
            @change="refreshTestPython"
          >
            <el-option
              v-for="model in testModelOptions"
              :key="model"
              :label="model"
              :value="model"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="Prompt">
          <el-input v-model="testForm.prompt" />
        </el-form-item>
        <el-form-item label="超时时间（秒）">
          <el-input-number v-model="testForm.timeoutSeconds" :min="3" :max="120" />
        </el-form-item>
        <el-form-item label="内置探针预览（仅展示，不会提交执行）">
          <el-input v-model="testForm.pythonCode" type="textarea" :rows="24" spellcheck="false" readonly />
        </el-form-item>
      </el-form>
      <section v-if="testResult" class="test-result-panel">
        <div class="panel-head">
          <h3>测试结果</h3>
          <el-tag :type="testResult.status === 'SUCCESS' ? 'success' : 'danger'">{{ testResult.status }}</el-tag>
        </div>
        <div class="test-result-grid">
          <div><span>模型</span><strong>{{ testResult.model }}</strong></div>
          <div><span>耗时</span><strong>{{ testResult.latencyMs || 0 }} ms</strong></div>
          <div><span>输入 Token</span><strong>{{ testResult.usage?.promptTokens || 0 }}</strong></div>
          <div><span>输出 Token</span><strong>{{ testResult.usage?.completionTokens || 0 }}</strong></div>
          <div><span>缓存读取 Token</span><strong>{{ testResult.usage?.cacheReadTokens || 0 }}</strong></div>
          <div><span>缓存写入 Token</span><strong>{{ testResult.usage?.cacheWriteTokens || 0 }}</strong></div>
          <div><span>估算成本(USD)</span><strong>{{ formatUsd(testResult.estimatedCostAmount) }}</strong></div>
        </div>
        <el-form label-position="top" class="test-io">
          <el-form-item label="输入">
            <el-input :model-value="testForm.prompt" type="textarea" :rows="3" readonly />
          </el-form-item>
          <el-form-item label="输出">
            <el-input :model-value="testResult.sampleText || testResult.error || ''" type="textarea" :rows="5" readonly />
          </el-form-item>
        </el-form>
      </section>
      <template #footer>
        <el-button @click="testVisible = false">取消</el-button>
        <el-button type="primary" :loading="testRunning" @click="runChannelTest">确认运行</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="discoveryVisible" title="上游模型发现与同步" width="760px">
      <el-skeleton v-if="discoveryLoading" :rows="7" animated />
      <template v-else-if="discoveryResult">
        <el-alert
          title="同步只新增映射，不删除或覆盖现有定价。新模型默认停用并标记为待定价。"
          type="info"
          :closable="false"
          show-icon
        />
        <div class="discovery-summary">
          <div><span>渠道</span><strong>{{ discoveryResult.channelName }}</strong></div>
          <div><span>上游模型</span><strong>{{ discoveryResult.models?.length || 0 }}</strong></div>
          <div><span>已有映射</span><strong>{{ discoveryResult.existingCount || 0 }}</strong></div>
          <div><span>待新增</span><strong>{{ discoveryResult.missingCount || 0 }}</strong></div>
        </div>
        <el-table :data="discoveryResult.missingModels || []" max-height="360" size="small">
          <el-table-column type="index" width="70" />
          <el-table-column label="待同步模型">
            <template #default="{ row }"><code>{{ row }}</code></template>
          </el-table-column>
        </el-table>
        <el-switch
          v-model="activateDiscoveredModels"
          active-text="同步后立即启用（计费仍关闭，需尽快配置价格）"
          inactive-text="安全模式：同步后保持停用"
          class="discovery-switch"
        />
      </template>
      <template #footer>
        <el-button @click="discoveryVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="syncRunning"
          :disabled="discoveryLoading || !discoveryResult"
          @click="syncDiscoveredModels"
        >确认同步</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="sensitiveVisible"
      :title="sensitiveEditingId ? '编辑敏感词' : '新增敏感词'"
      width="560px"
    >
      <el-form :model="sensitiveForm" label-position="top" @submit.prevent="saveSensitiveWord">
        <el-form-item label="词条" required><el-input v-model="sensitiveForm.term" maxlength="255" /></el-form-item>
        <el-form-item label="分类" required><el-select v-model="sensitiveForm.category" filterable allow-create style="width:100%"><el-option v-for="category in sensitiveCategories" :key="category" :label="category" :value="category" /></el-select></el-form-item>
        <div class="sensitive-form-grid">
          <el-form-item label="匹配方式"><el-select v-model="sensitiveForm.matchMode"><el-option label="包含匹配" value="CONTAINS" /><el-option label="完整匹配" value="EXACT" /></el-select></el-form-item>
          <el-form-item label="命中动作"><el-select v-model="sensitiveForm.action"><el-option label="阻断" value="BLOCK" /><el-option label="告警并放行" value="WARN" /><el-option label="审核并放行" value="REVIEW" /></el-select></el-form-item>
          <el-form-item label="作用范围"><el-select v-model="sensitiveForm.scopeType" @change="sensitiveForm.scopeId = ''"><el-option label="全站" value="GLOBAL" /><el-option label="企业" value="ORGANIZATION" /><el-option label="用户" value="USER" /><el-option label="API Token" value="TOKEN" /></el-select></el-form-item>
          <el-form-item v-if="sensitiveForm.scopeType !== 'GLOBAL'" label="作用目标" required><el-select v-model="sensitiveForm.scopeId" filterable style="width:100%"><el-option v-for="item in sensitiveTargetOptions" :key="item.value" :label="item.label" :value="item.value" /></el-select></el-form-item>
        </div>
        <el-form-item label="备注"><el-input v-model="sensitiveForm.note" type="textarea" maxlength="500" show-word-limit /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="sensitiveForm.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="sensitiveVisible=false">取消</el-button><el-button type="primary" :loading="sensitiveSaving" @click="saveSensitiveWord">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="bulkVisible" title="批量导入敏感词" width="520px">
      <el-alert title="一行一个词条，最多 200 条；批量导入默认归入“业务自定义”，并保持停用。" type="info" :closable="false" />
      <el-input v-model="bulkTerms" type="textarea" :rows="12" placeholder="词条一&#10;词条二" />
      <template #footer><el-button @click="bulkVisible=false">取消</el-button><el-button type="primary" @click="bulkSensitiveWords">确认导入</el-button></template>
    </el-dialog>

    <el-dialog
      v-model="issuedSecretVisible"
      title="API Key 已生成（仅显示一次）"
      width="560px"
      :close-on-click-modal="false"
      :close-on-press-escape="false"
      @closed="issuedSecret = ''"
    >
      <el-alert
        title="列表中仅保留脱敏预览。关闭后无法再查看完整 Key，请立即保存到密码管理器。"
        type="warning"
        :closable="false"
        show-icon
      />
      <div class="issued-secret">
        <code>{{ issuedSecret }}</code>
        <el-button type="primary" @click="copyIssuedSecret">复制 Key</el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="issuedSecretVisible = false">我已安全保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import http, { getHttpErrorMessage, getHttpErrorNotice } from '@/utils/http'
import { formatCny, formatPerMillionUsd, formatUsd } from '@/utils/money'
const AdminUsageCharts = defineAsyncComponent(() => import('@/components/AdminUsageCharts.vue'))

type ModuleKey = 'dashboard' | 'users' | 'channels' | 'models' | 'tokens' | 'audit' | 'finance' | 'security' | 'settings'
type Column = {
  prop: string
  label: string
  width?: number
  minWidth?: number
  kind?: 'status' | 'bool' | 'callable' | 'money' | 'modelMoney' | 'rate' | 'profit'
    | 'tierRange' | 'tierOfficial' | 'tierCost' | 'tierSale' | 'tierProfit'
}
type Field = { prop: string; label: string; type?: 'text' | 'password' | 'textarea' | 'number' | 'switch' | 'select'; min?: number; step?: number; options?: Array<{ label: string; value: any }> }
type Config = {
  title: string
  description: string
  endpoint?: string
  createLabel?: string
  columns: Column[]
  fields: Field[]
  editable?: boolean
  deletable?: boolean
}

const props = defineProps<{ module: ModuleKey }>()

const module = computed(() => props.module)
const gatewayModules: ModuleKey[] = ['channels', 'models']
const gatewayTableMaxHeight = computed(() => gatewayModules.includes(module.value) ? 'calc(100vh - 310px)' : undefined)
const loading = ref(false)
const saving = ref(false)
const query = ref('')
const rows = ref<any[]>([])
const secondaryRows = ref<any[]>([])
const rechargePlans = ref<any[]>([])
type FinanceSection = 'summary' | 'transactions' | 'codes' | 'plans'
const financeErrors = reactive<Record<FinanceSection, string>>({ summary: '', transactions: '', codes: '', plans: '' })
const rechargePlanVisible = ref(false)
const rechargePlanSaving = ref(false)
const rechargePlanForm = reactive({ id: null as number | null, name: '', amount: 500000, bonusPercent: 0, sortOrder: 100, enabled: true })
const adminToday = new Date()
const adminMonthAgo = new Date(adminToday); adminMonthAgo.setDate(adminToday.getDate() - 29)
const adminUsageFilters = reactive({
  range: [adminMonthAgo.toISOString().slice(0, 10), adminToday.toISOString().slice(0, 10)] as string[],
  audienceType: '', organizationId: '' as string | number, userId: '' as string | number, model: ''
})
const adminUsage = ref<any>({ daily: [], dailyByModel: [], totals: {}, tokenComposition: [] })
const dashboardUsage = ref<any>({ daily: [], dailyByModel: [], totals: {} })
const dashboardTodayUsage = ref<any>({ totals: {} })
const dashboardUsageError = ref('')
const adminUsageError = ref('')
const auditLogsError = ref('')
const auditOptions = reactive<{ organizations: any[]; users: any[]; models: any[] }>({ organizations: [], users: [], models: [] })
const auditPage = ref(1)
const auditPageSize = ref(50)
const auditTotal = ref(0)
const testRows = ref<any[]>([])
const channelLedgerSections = ref<string[]>([])
const dashboard = ref<Record<string, any>>({})
const report = ref<Record<string, any>>({})
const drawerVisible = ref(false)
const editingId = ref<number | null>(null)
const form = reactive<Record<string, any>>({})
const adjustVisible = ref(false)
const adjustSaving = ref(false)
const adjustTarget = ref<any | null>(null)
const adjustForm = reactive({ amount: 0, reason: '' })
const testVisible = ref(false)
const testRunning = ref(false)
const testTarget = ref<any | null>(null)
const testForm = reactive({
  providerModelName: '',
  prompt: '你是什么模型',
  timeoutSeconds: 20,
  pythonCode: ''
})
const testResult = ref<any | null>(null)
const testModelOptions = ref<string[]>([])
const discoveryVisible = ref(false)
const discoveryLoading = ref(false)
const syncRunning = ref(false)
const discoveryTarget = ref<any | null>(null)
const discoveryResult = ref<any | null>(null)
const activateDiscoveredModels = ref(false)
const channelModelOptions = ref<string[]>([])
const editorDiscoveryLoading = ref(false)
const editorDiscoveryError = ref('')
const providerCatalog = ref<any[]>([])
const modelProviderFilter = ref('')
const modelPage = ref(1)
const modelPageSize = ref(20)
const issuedSecretVisible = ref(false)
const issuedSecret = ref('')
const securityTab = ref('policies')
const sensitiveWords = ref<any[]>([])
const securityEvents = ref<any[]>([])
const sensitiveVisible = ref(false)
const sensitiveSaving = ref(false)
const sensitiveEditingId = ref<number | null>(null)
const sensitiveForm = reactive({ term: '', category: '业务自定义', matchMode: 'CONTAINS', action: 'BLOCK', scopeType: 'GLOBAL', scopeId: '' as string | number, note: '', enabled: false })
const bulkVisible = ref(false)
const bulkTerms = ref('')
const sensitiveTestText = ref('')
const sensitiveTestMatches = ref<any[]>([])
const securityTargets = reactive<{ organizations: any[]; users: any[]; tokens: any[] }>({ organizations: [], users: [], tokens: [] })

type ChannelModelPricing = {
  id?: number
  publicModelName: string
  channelModelName: string
  priority: number
  enabled: boolean
  priceRatio: number
  costPerMillion: number
  inputPricePerMillion: number
  outputPricePerMillion: number
  cachedPricePerMillion: number
  inputCostPerMillion: number
  outputCostPerMillion: number
  cachedCostPerMillion: number
  billingEnabled: boolean
  trafficPercent: number
  capabilityTags?: string
  pricingUnit: 'TOKEN' | 'SECOND' | 'IMAGE' | 'MINUTE' | 'CHARACTER' | 'TASK'
  billingMode: 'PAID' | 'FREE_PREVIEW' | 'DISABLED'
  pricingStatus: 'VERIFIED' | 'ESTIMATED' | 'FREE_PREVIEW' | 'PENDING'
  pricingMessage: string
  pricingSourceUrl: string
  officialUnitPrice: number
  costUnitPrice: number
  saleUnitPrice: number
  priceTiers: ModelPriceTier[]
}

type ModelPriceTier = {
  id?: number
  modelMappingId?: number
  tierName: string
  maxContextTokens: number | null
  sortOrder: number
  officialGroupName: string
  officialInputPrice: number
  officialOutputPrice: number
  officialCacheReadPrice: number
  officialCacheWritePrice: number
  officialPriceUnit: 'M' | 'KB'
  officialPriceSuffix: string
  costGroupName: string
  costInputPrice: number
  costOutputPrice: number
  costCacheReadPrice: number
  costCacheWritePrice: number
  costPriceUnit: 'M' | 'KB'
  costPriceSuffix: string
  saleGroupName: string
  saleInputPrice: number
  saleOutputPrice: number
  saleCacheReadPrice: number
  saleCacheWritePrice: number
  salePriceUnit: 'M' | 'KB'
  salePriceSuffix: string
}

const channelModelPricing = ref<ChannelModelPricing[]>([])
const pricingUnitOptions = [
  { value: 'TOKEN', label: 'Token' }, { value: 'SECOND', label: '视频 / 秒' },
  { value: 'IMAGE', label: '图片 / 张' }, { value: 'MINUTE', label: '音频 / 分钟' },
  { value: 'CHARACTER', label: '语音 / 千字符' }, { value: 'TASK', label: '任务 / 次' }
] as const
const channelCredentials = ref<any[]>([])
const credentialVisible = ref(false)
const credentialSaving = ref(false)
const credentialTestingId = ref<number | null>(null)
const credentialForm = reactive({ id: null as number | null, name: '', secret: '', priority: 0, weight: 100, rpmLimit: 0, tpmLimit: 0, concurrencyLimit: 0, enabled: true })
const activePricingModel = ref('')
const modelPricingSaving = ref(false)
const savedPricingSnapshots = reactive<Record<string, string>>({})
const activePricingRows = computed(() => channelModelPricing.value.filter(item => item.channelModelName === activePricingModel.value).slice(0, 1))
const auditFilterReady = computed(() => Boolean(adminUsageFilters.audienceType)
  && (adminUsageFilters.audienceType !== 'COMPANY' || Boolean(adminUsageFilters.organizationId)))
const dirtyPricingCount = computed(() => channelModelPricing.value.filter(item => isPricingDirty(item.channelModelName)).length)

const configs: Record<ModuleKey, Config> = {
  dashboard: {
    title: '运营总览',
    description: '平台请求、收入、成本、渠道健康和风险队列的实时快照。',
    endpoint: '/admin/api/dashboard',
    columns: [],
    fields: []
  },
  users: {
    title: '用户与分组',
    description: '管理用户状态、角色、计费分组、余额和手工调账。',
    endpoint: '/admin/api/users',
    columns: [
      { prop: 'username', label: '用户', minWidth: 150 },
      { prop: 'email', label: '邮箱', minWidth: 180 },
      { prop: 'role', label: '角色', width: 100 },
      { prop: 'status', label: '状态', width: 110, kind: 'status' },
      { prop: 'group_name', label: '分组', minWidth: 130 },
      { prop: 'balance', label: '余额', width: 120, kind: 'money' },
      { prop: 'token_count', label: 'Key', width: 90 },
      { prop: 'request_count', label: '请求', width: 100 }
    ],
    fields: [
      { prop: 'role', label: '角色', type: 'select', options: [{ label: 'USER', value: 'USER' }, { label: 'ADMIN', value: 'ADMIN' }] },
      { prop: 'status', label: '状态', type: 'select', options: [{ label: 'ACTIVE', value: 'ACTIVE' }, { label: 'SUSPENDED', value: 'SUSPENDED' }] },
      { prop: 'groupId', label: '分组 ID', type: 'number' }
    ],
    editable: true
  },
  channels: {
    title: '渠道治理',
    description: '维护供应商接入、密钥、权重、限流、健康状态和冷却策略。',
    endpoint: '/admin/api/channels',
    createLabel: '新建渠道',
    columns: [
      { prop: 'name', label: '渠道', minWidth: 170 },
      { prop: 'type', label: '类型', width: 130 },
      { prop: 'baseUrl', label: 'Base URL', minWidth: 220 },
      { prop: 'groupName', label: '分组', width: 120 },
      { prop: 'weight', label: '权重', width: 90 },
      { prop: 'rpmLimit', label: 'RPM', width: 90 },
      { prop: 'tpmLimit', label: 'TPM', width: 100 },
      { prop: 'consecutiveFailures', label: '连续失败', width: 100 },
      { prop: 'averageLatencyMs', label: '平均延迟', width: 110 },
      { prop: 'healthStatus', label: '健康', width: 120, kind: 'status' },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: [
      { prop: 'name', label: '渠道名称' },
      { prop: 'type', label: '协议类型', type: 'select', options: ['openai', 'haoee', 'deepseek', 'anthropic', 'gemini', 'xai', 'openrouter', 'qwen', 'kimi', 'glm', 'mistral', 'meta', 'nvidia'].map(value => ({ label: value, value })) },
      { prop: 'baseUrl', label: 'Base URL' },
      { prop: 'apiKey', label: 'API Key', type: 'password' },
      { prop: 'models', label: '模型清单', type: 'textarea' },
      { prop: 'groupName', label: '渠道分组' },
      { prop: 'weight', label: '权重', type: 'number', min: 1 },
      { prop: 'rpmLimit', label: 'RPM 限制', type: 'number' },
      { prop: 'tpmLimit', label: 'TPM 限制', type: 'number' },
      { prop: 'autoDisable', label: '连续失败自动熔断', type: 'switch' },
      { prop: 'failureThreshold', label: '熔断失败阈值', type: 'number', min: 1 },
      { prop: 'cooldownSeconds', label: '熔断冷却秒数', type: 'number', min: 5 },
      { prop: 'enabled', label: '启用', type: 'switch' }
    ],
    editable: true,
    deletable: true
  },
  models: {
    title: '模型与定价',
    description: '由渠道治理中的模型清单与逐模型定价自动生成。这里仅展示路由、售价、上游成本和毛利。',
    endpoint: '/admin/api/models',
    columns: [
      { prop: 'publicModelName', label: '公开模型', minWidth: 180 },
      { prop: 'channelModelName', label: '渠道模型', minWidth: 180 },
      { prop: 'channel.name', label: '渠道', minWidth: 150 },
      { prop: 'priority', label: '优先级', width: 90 },
      { prop: 'priceRatio', label: '倍率', width: 90 },
      { prop: 'priceTiers', label: '上下文挡位', minWidth: 220, kind: 'tierRange' },
      { prop: 'officialPrices', label: '官网 输入/输出/读/写', minWidth: 310, kind: 'tierOfficial' },
      { prop: 'costPrices', label: '成本 输入/输出/读/写', minWidth: 310, kind: 'tierCost' },
      { prop: 'salePrices', label: '售价 输入/输出/读/写', minWidth: 310, kind: 'tierSale' },
      { prop: 'tierProfit', label: '毛利 输入/输出/读/写', minWidth: 310, kind: 'tierProfit' },
      { prop: 'billingEnabled', label: '计费', width: 90, kind: 'bool' },
      { prop: 'trafficPercent', label: '灰度', width: 90 },
      { prop: 'callable', label: '前台调用', width: 110, kind: 'callable' },
      { prop: 'availabilityMessage', label: '可用性说明', minWidth: 190 },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: []
  },
  tokens: {
    title: 'Token 与权限',
    description: '管理平台 Key、用户归属、额度、过期时间、模型范围和 IP 白名单。',
    endpoint: '/admin/api/tokens',
    createLabel: '生成 Token',
    columns: [
      { prop: 'name', label: '名称', minWidth: 150 },
      { prop: 'keyPreview', label: 'Key（脱敏）', minWidth: 220 },
      { prop: 'userId', label: '用户ID', width: 100 },
      { prop: 'usedQuota', label: '已用', width: 110 },
      { prop: 'totalQuota', label: '总额度', width: 110 },
      { prop: 'allowedModels', label: '模型范围', minWidth: 160 },
      { prop: 'ipWhitelist', label: 'IP 白名单', minWidth: 160 },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: [
      { prop: 'name', label: '名称' },
      { prop: 'userId', label: '用户 ID', type: 'number' },
      { prop: 'totalQuota', label: '总额度', type: 'number', step: 10000 },
      { prop: 'allowedModels', label: '允许模型，逗号分隔' },
      { prop: 'ipWhitelist', label: 'IP 白名单，逗号分隔' },
      { prop: 'description', label: '说明', type: 'textarea' },
      { prop: 'enabled', label: '启用', type: 'switch' }
    ],
    editable: true,
    deletable: true
  },
  audit: {
    title: '调用审计',
    description: '排查请求链路、失败原因、成本、售价、延迟和管理员写操作。',
    endpoint: '/admin/api/audit/request-logs',
    columns: [
      { prop: 'trace_id', label: 'Trace ID', minWidth: 140 },
      { prop: 'username', label: '用户', minWidth: 120 },
      { prop: 'model', label: '模型', minWidth: 160 },
      { prop: 'channel_name', label: '渠道', minWidth: 140 },
      { prop: 'total_tokens', label: 'Token', width: 110 },
      { prop: 'sale_amount', label: '模型售价(USD)', width: 140, kind: 'modelMoney' },
      { prop: 'latency_ms', label: '延迟', width: 100 },
      { prop: 'status', label: '状态', width: 110, kind: 'status' },
      { prop: 'error_message', label: '错误', minWidth: 220 }
    ],
    fields: []
  },
  finance: {
    title: '钱包财务',
    description: '查看充值、消费、调账流水，并生成可控兑换码。',
    endpoint: '/admin/api/finance/transactions',
    createLabel: '生成兑换码',
    columns: [],
    fields: [
      { prop: 'code', label: '兑换码，不填自动生成' },
      { prop: 'amount', label: '额度', type: 'number', step: 10000 },
      { prop: 'maxUses', label: '最大使用次数', type: 'number', min: 1 }
    ]
  },
  security: {
    title: '安全策略',
    description: '配置全局、分组、用户和 Token 级限流、拦截、告警策略。',
    endpoint: '/admin/api/security/policies',
    createLabel: '新增策略',
    columns: [
      { prop: 'name', label: '策略', minWidth: 160 },
      { prop: 'scope', label: '范围', minWidth: 140 },
      { prop: 'action', label: '动作', width: 130 },
      { prop: 'threshold_value', label: '阈值', minWidth: 160 },
      { prop: 'enabled', label: '状态', width: 100, kind: 'bool' }
    ],
    fields: [
      { prop: 'name', label: '策略名称' },
      { prop: 'scope', label: '范围' },
      { prop: 'action', label: '动作', type: 'select', options: ['RATE_LIMIT', 'BLOCK', 'WARN', 'REVIEW'].map(value => ({ label: value, value })) },
      { prop: 'threshold', label: '阈值' },
      { prop: 'enabled', label: '启用', type: 'switch' }
    ],
    editable: true
  },
  settings: {
    title: '系统配置与报表',
    description: '维护站点配置、注册策略、公告，并查看模型和渠道维度报表。',
    endpoint: '/admin/api/settings',
    createLabel: '新增配置',
    columns: [],
    fields: [
      { prop: 'key', label: '配置键' },
      { prop: 'value', label: '配置值', type: 'textarea' },
      { prop: 'description', label: '说明' }
    ],
    editable: true
  }
}

const config = computed(() => configs[module.value])
const activeFields = computed(() => config.value.fields)
const drawerTitle = computed(() => editingId.value ? `编辑${config.value.title}` : config.value.createLabel || config.value.title)
const selectedChannelModels = computed(() => splitChannelModels(form.models))

const filteredRows = computed(() => {
  const needle = query.value.trim().toLowerCase()
  let data = [...rows.value]
  if (module.value === 'models') {
    data = data.sort((a, b) => {
      const left = `${a.channel?.type || ''}/${a.publicModelName || ''}`
      const right = `${b.channel?.type || ''}/${b.publicModelName || ''}`
      return left.localeCompare(right)
    })
    if (modelProviderFilter.value) {
      data = data.filter(row => (row.channel?.type || '').toLowerCase() === modelProviderFilter.value)
    }
  }
  if (!needle) return data
  return data.filter(row => JSON.stringify(row).toLowerCase().includes(needle))
})

const displayRows = computed(() => {
  if (module.value !== 'models') return filteredRows.value
  const start = (modelPage.value - 1) * modelPageSize.value
  return filteredRows.value.slice(start, start + modelPageSize.value)
})

const modelProviderOptions = computed(() => {
  return [...new Set(rows.value
    .map(row => (row.channel?.type || '').toLowerCase())
    .filter(Boolean))]
    .sort()
})

const callableRouteCount = computed(() => rows.value.filter(row => row.callable).length)
const callablePublicModelCount = computed(() => new Set(rows.value
  .filter(row => row.callable && row.publicModelName)
  .map(row => row.publicModelName)).size)

const metrics = computed(() => {
  if (module.value === 'dashboard') {
    const m = dashboard.value.metrics || {}
    return [
      { label: '总请求', value: number(m.requests), badge: `${percent(m.successRate)} 成功`, tone: 'green' },
      { label: '收入', value: money(m.revenue), badge: `成本 ${money(m.cost)}`, tone: 'blue' },
      { label: '毛利率', value: percent(m.grossMargin), badge: '实时估算', tone: 'orange' },
      { label: '待处理订单', value: number(m.pendingOrders), badge: `${number(m.activeUsers)} 用户`, tone: 'purple' }
    ]
  }
  if (module.value === 'finance') {
    const m = dashboard.value.finance || {}
    return [
      { label: '入账', value: money(m.income), badge: '充值/兑换/调账', tone: 'green' },
      { label: '消费', value: money(m.spending), badge: 'API 扣费', tone: 'orange' },
      { label: '用户余额', value: money(m.userBalance), badge: '全站余额', tone: 'blue' },
      { label: '兑换码', value: number(m.activeRedeemCodes), badge: '启用中', tone: 'purple' }
    ]
  }
  return []
})

watch(module, () => {
  query.value = ''
  modelProviderFilter.value = ''
  modelPage.value = 1
  channelLedgerSections.value = []
  load()
})

watch([query, modelProviderFilter, modelPageSize], () => {
  modelPage.value = 1
})

watch(() => form.type, value => {
  if (module.value !== 'channels' || editingId.value || form.baseUrl) return
  const provider = providerCatalog.value.find(item => item.type === value)
  if (provider?.defaultBaseUrl) form.baseUrl = provider.defaultBaseUrl
})

watch(() => form.models, value => {
  if (module.value === 'channels' && drawerVisible.value) {
    syncChannelPricingRows(value)
  }
})

watch([() => testForm.prompt, () => testForm.timeoutSeconds], () => {
  if (testTarget.value) refreshTestPython()
})

onMounted(load)

async function loadFinanceSection(section: FinanceSection) {
  financeErrors[section] = ''
  const endpoints: Record<FinanceSection, string> = {
    summary: '/api/admin/api/finance/summary',
    transactions: '/api/admin/api/finance/transactions',
    codes: '/api/admin/api/finance/redeem-codes',
    plans: '/api/admin/api/finance/recharge-plans'
  }
  const labels: Record<FinanceSection, string> = {
    summary: '财务汇总', transactions: '钱包流水', codes: '兑换码', plans: '充值套餐'
  }
  try {
    const response = await http.get(endpoints[section])
    if (section === 'summary') dashboard.value.finance = response.data || {}
    else if (section === 'transactions') rows.value = response.data || []
    else if (section === 'codes') secondaryRows.value = response.data || []
    else rechargePlans.value = response.data || []
  } catch (error: unknown) {
    financeErrors[section] = getHttpErrorNotice(error, `${labels[section]}加载失败`)
  }
}

async function loadFinance() {
  await Promise.all((['summary', 'transactions', 'codes', 'plans'] as FinanceSection[])
    .map(section => loadFinanceSection(section)))
}

async function load() {
  loading.value = true
  try {
    rows.value = []
    secondaryRows.value = []
    testRows.value = []
    if (module.value === 'dashboard') {
      const [dashboardResult] = await Promise.allSettled([
        http.get('/api/admin/api/dashboard'),
        loadDashboardUsage()
      ])
      if (dashboardResult.status === 'rejected') throw dashboardResult.reason
      dashboard.value = dashboardResult.value.data
      rows.value = dashboardResult.value.data.channelHealth || []
    } else if (module.value === 'finance') {
      await loadFinance()
    } else if (module.value === 'settings') {
      const [settings, reports] = await Promise.all([
        http.get('/api/admin/api/settings'),
        http.get('/api/admin/api/reports')
      ])
      rows.value = settings.data
      report.value = reports.data
    } else if (module.value === 'models') {
      const [modelRes, channelRes] = await Promise.all([
        http.get('/api/admin/api/models'),
        http.get('/api/admin/api/channels')
      ])
      rows.value = modelRes.data || []
      const channelField = configs.models.fields.find(field => field.prop === 'channelId')
      if (channelField) {
        channelField.options = (channelRes.data || []).map((channel: any) => ({
          label: `${channel.name} · ${channel.type} · ${channel.enabled ? channel.healthStatus : 'DISABLED'}`,
          value: channel.id
        }))
      }
    } else if (module.value === 'audit') {
      await loadAuditOptions()
      if (auditFilterReady.value) await Promise.allSettled([loadAdminUsage(), loadAuditLogs()])
      else {
        rows.value = []
        adminUsage.value = { daily: [], dailyByModel: [], totals: {}, tokenComposition: [] }
      }
    } else if (module.value === 'security') {
      const [policyRes, wordsRes, eventsRes, targetRes, tokenRes] = await Promise.all([
        http.get('/api/admin/api/security/policies'),
        http.get('/api/admin/api/security/sensitive-words'),
        http.get('/api/admin/api/security/events'),
        http.get('/api/admin/api/audit/filter-options'),
        http.get('/api/admin/api/tokens')
      ])
      rows.value = policyRes.data || []
      sensitiveWords.value = wordsRes.data || []
      securityEvents.value = eventsRes.data || []
      securityTargets.organizations = targetRes.data?.organizations || []
      securityTargets.users = targetRes.data?.users || []
      securityTargets.tokens = tokenRes.data || []
    } else if (config.value.endpoint) {
      const res = await http.get(`/api${config.value.endpoint}`)
      rows.value = res.data
      if (module.value === 'channels') {
        const [testLogRes, providerRes] = await Promise.all([
          http.get('/api/admin/api/channels/test-logs'),
          http.get('/api/admin/api/channels/providers')
        ])
        testRows.value = testLogRes.data
        providerCatalog.value = providerRes.data || []
        const providerField = configs.channels.fields.find(field => field.prop === 'type')
        if (providerField) {
          providerField.options = providerCatalog.value.map(item => ({
            label: `${item.name} · ${item.protocol}`,
            value: item.type
          }))
        }
      }
    }
  } catch (error: any) {
    ElMessage.error(getHttpErrorNotice(error, '加载后台数据失败'))
  } finally {
    loading.value = false
  }
}

async function loadDashboardUsage() {
  dashboardUsageError.value = ''
  const weekAgo = new Date(); weekAgo.setDate(weekAgo.getDate() - 6)
  const today = localDate(new Date())
  const [rangeResult, todayResult] = await Promise.allSettled([
    http.get('/api/admin/api/usage/analytics', { params: { from: localDate(weekAgo), to: today } }),
    http.get('/api/admin/api/usage/analytics', { params: { from: today, to: today } })
  ])
  const errors: string[] = []
  if (rangeResult.status === 'fulfilled') dashboardUsage.value = rangeResult.value.data || { daily: [], dailyByModel: [], totals: {} }
  else errors.push(getHttpErrorNotice(rangeResult.reason, '近 7 日趋势加载失败'))
  if (todayResult.status === 'fulfilled') dashboardTodayUsage.value = todayResult.value.data || { totals: {}, dailyByModel: [] }
  else errors.push(getHttpErrorNotice(todayResult.reason, '今日收益加载失败'))
  dashboardUsageError.value = errors.join('；')
}

async function loadAdminUsage() {
  if (!auditFilterReady.value) return
  adminUsageError.value = ''
  try {
    const response = await http.get('/api/admin/api/usage/analytics', { params: {
      from: adminUsageFilters.range?.[0], to: adminUsageFilters.range?.[1],
      audienceType: adminUsageFilters.audienceType,
      organizationId: adminUsageFilters.organizationId || undefined,
      userId: adminUsageFilters.userId || undefined, model: adminUsageFilters.model || undefined
    } })
    adminUsage.value = response.data || { daily: [], dailyByModel: [], totals: {}, tokenComposition: [] }
  } catch (error: unknown) {
    adminUsageError.value = getHttpErrorNotice(error, '用量统计加载失败')
  }
}

async function loadAuditLogs() {
  if (!auditFilterReady.value) return
  auditLogsError.value = ''
  try {
    const response = await http.get('/api/admin/api/audit/request-logs', { params: {
      from: adminUsageFilters.range?.[0], to: adminUsageFilters.range?.[1],
      audienceType: adminUsageFilters.audienceType,
      organizationId: adminUsageFilters.organizationId || undefined,
      userId: adminUsageFilters.userId || undefined, model: adminUsageFilters.model || undefined,
      query: query.value || undefined, page: auditPage.value, pageSize: auditPageSize.value
    } })
    rows.value = response.data?.items || []
    auditTotal.value = Number(response.data?.total || 0)
  } catch (error: unknown) {
    auditLogsError.value = getHttpErrorNotice(error, '调用日志加载失败')
  }
}

async function loadAuditOptions(search = '') {
  const response = await http.get('/api/admin/api/audit/filter-options', { params: {
    audienceType: adminUsageFilters.audienceType || undefined,
    organizationId: adminUsageFilters.organizationId || undefined,
    query: search || undefined
  } })
  auditOptions.organizations = response.data?.organizations || []
  auditOptions.users = response.data?.users || []
  auditOptions.models = response.data?.models || []
}

async function handleAudienceChange() {
  adminUsageFilters.organizationId = ''
  adminUsageFilters.userId = ''
  adminUsageFilters.model = ''
  rows.value = []
  auditTotal.value = 0
  adminUsage.value = { daily: [], dailyByModel: [], totals: {}, tokenComposition: [] }
  adminUsageError.value = ''
  auditLogsError.value = ''
  await loadAuditOptions()
}

async function handleOrganizationChange() {
  adminUsageFilters.userId = ''
  adminUsageFilters.model = ''
  rows.value = []
  auditTotal.value = 0
  adminUsage.value = { daily: [], dailyByModel: [], totals: {}, tokenComposition: [] }
  await loadAuditOptions()
}

async function applyAuditFilters() {
  if (!auditFilterReady.value) {
    ElMessage.warning(adminUsageFilters.audienceType === 'COMPANY' ? '请先选择企业' : '请先选择个人用户或企业用户')
    return
  }
  auditPage.value = 1
  await Promise.allSettled([loadAdminUsage(), loadAuditLogs()])
}

function localDate(date: Date) {
  const year = date.getFullYear(), month = String(date.getMonth() + 1).padStart(2, '0'), day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

const sensitiveCategories = ['违法交易', '凭证泄露', '隐私与个人信息', '暴力与威胁', '色情及未成年人', '业务自定义']
const sensitiveTargetOptions = computed(() => {
  if (sensitiveForm.scopeType === 'ORGANIZATION') return securityTargets.organizations.map(item => ({ label: item.name, value: item.id }))
  if (sensitiveForm.scopeType === 'USER') return securityTargets.users.map(item => ({ label: `${item.username}${item.email ? ` · ${item.email}` : ''}`, value: item.id }))
  if (sensitiveForm.scopeType === 'TOKEN') return securityTargets.tokens.map(item => ({ label: `${item.name || `Token #${item.id}`} · ${item.keyPreview || ''}`, value: item.id }))
  return []
})

function valueOf(row: any, key: string) { return row?.[key] ?? row?.[key.toUpperCase()] }
function sensitiveScopeLabel(row: any) {
  const scope = String(valueOf(row, 'scope_type') || 'GLOBAL')
  const labels: Record<string, string> = { GLOBAL: '全站', ORGANIZATION: '企业', USER: '用户', TOKEN: 'API Token' }
  const id = valueOf(row, 'scope_id')
  return `${labels[scope] || scope}${id ? ` #${id}` : ''}`
}

function openSensitiveWord(row?: any) {
  sensitiveEditingId.value = row ? Number(valueOf(row, 'id')) : null
  Object.assign(sensitiveForm, row ? {
    term: valueOf(row, 'term') || '', category: valueOf(row, 'category') || '业务自定义',
    matchMode: valueOf(row, 'match_mode') || 'CONTAINS', action: valueOf(row, 'action') || 'BLOCK',
    scopeType: valueOf(row, 'scope_type') || 'GLOBAL', scopeId: valueOf(row, 'scope_id') || '',
    note: valueOf(row, 'note') || '', enabled: Boolean(valueOf(row, 'enabled'))
  } : { term: '', category: '业务自定义', matchMode: 'CONTAINS', action: 'BLOCK', scopeType: 'GLOBAL', scopeId: '', note: '', enabled: false })
  sensitiveVisible.value = true
}

async function saveSensitiveWord() {
  if (!sensitiveForm.term.trim() || !sensitiveForm.category.trim()) return ElMessage.warning('请填写词条和分类')
  if (sensitiveForm.scopeType !== 'GLOBAL' && !sensitiveForm.scopeId) return ElMessage.warning('请选择作用目标')
  sensitiveSaving.value = true
  try {
    await http.post('/api/admin/api/security/sensitive-words', sensitiveEditingId.value ? { ...sensitiveForm, id: sensitiveEditingId.value } : sensitiveForm)
    sensitiveVisible.value = false
    ElMessage.success('敏感词已保存')
    await load()
    securityTab.value = 'words'
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '敏感词保存失败')) }
  finally { sensitiveSaving.value = false }
}

async function removeSensitiveWord(row: any) {
  try { await ElMessageBox.confirm(`确认删除敏感词“${valueOf(row, 'term')}”？`, '删除敏感词', { type: 'warning' }) }
  catch { return }
  await http.delete(`/api/admin/api/security/sensitive-words/${valueOf(row, 'id')}`)
  ElMessage.success('敏感词已删除')
  await load(); securityTab.value = 'words'
}

async function bulkSensitiveWords() {
  if (!bulkTerms.value.trim()) return ElMessage.warning('请输入需要导入的词条')
  try {
    const response = await http.post('/api/admin/api/security/sensitive-words/bulk', { terms: bulkTerms.value, category: '业务自定义', matchMode: 'CONTAINS', action: 'BLOCK', scopeType: 'GLOBAL', enabled: false })
    ElMessage.success(`已导入 ${response.data?.created || 0} 条停用词条`)
    bulkTerms.value = ''; bulkVisible.value = false; await load(); securityTab.value = 'words'
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '批量导入失败')) }
}

async function testSensitiveText() {
  try {
    const response = await http.post('/api/admin/api/security/sensitive-words/test', { text: sensitiveTestText.value })
    sensitiveTestMatches.value = response.data || []
    if (!sensitiveTestMatches.value.length) ElMessage.success('未命中当前已启用词条')
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '匹配测试失败')) }
}

function adminUsageMetric(row: any, key: string) { return Number(row?.[key] ?? row?.[key.toUpperCase()] ?? 0) }
const adminUsageDays = computed(() => {
  const daily = adminUsage.value.daily || []
  const max = Math.max(1, ...daily.map((row: any) => adminUsageMetric(row, 'total_tokens')))
  return daily.map((row: any) => {
    const total = adminUsageMetric(row, 'total_tokens')
    return { date: String(row.usage_day ?? row.USAGE_DAY ?? ''), total, height: Math.max(total ? 8 : 0, total / max * 100), parts: [
      { name: '输入未命中', className: 'miss', value: adminUsageMetric(row, 'cache_miss_tokens') },
      { name: '缓存命中', className: 'hit', value: adminUsageMetric(row, 'cache_read_tokens') },
      { name: '缓存写入', className: 'write', value: adminUsageMetric(row, 'cache_write_tokens') },
      { name: '输出', className: 'output', value: adminUsageMetric(row, 'completion_tokens') }
    ] }
  })
})
const adminUsageComposition = computed(() => (adminUsage.value.tokenComposition || []).map((slice: any, index: number) => ({
  name: slice.name, value: Number(slice.value || 0), color: ['#78ad30', '#35a7a0', '#e6a23c', '#6b7fd7'][index % 4]
})))
const adminUsageTotal = computed(() => adminUsageComposition.value.reduce((sum: number, slice: any) => sum + slice.value, 0))
const adminUsagePieStyle = computed(() => {
  const total = Math.max(1, adminUsageTotal.value); let cursor = 0
  const stops = adminUsageComposition.value.map((slice: any) => { const start = cursor; cursor += slice.value / total * 100; return `${slice.color} ${start}% ${cursor}%` })
  return { background: `conic-gradient(${stops.length ? stops.join(',') : '#e9eeeb 0 100%'})` }
})
function compact(value: number) { return Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 1 }).format(value || 0) }

function openRechargePlan(row?: any) {
  Object.assign(rechargePlanForm, row ? {
    id: row.id, name: row.name, amount: Number(row.amount), bonusPercent: Number(row.bonus_percent),
    sortOrder: Number(row.sort_order), enabled: Boolean(row.enabled)
  } : { id: null, name: '', amount: 500000, bonusPercent: 0, sortOrder: 100, enabled: true })
  rechargePlanVisible.value = true
}

async function saveRechargePlan() {
  if (!rechargePlanForm.name.trim()) return ElMessage.warning('请输入套餐名称')
  rechargePlanSaving.value = true
  try {
    const payload = { ...rechargePlanForm, id: undefined }
    if (rechargePlanForm.id) await http.put(`/api/admin/api/finance/recharge-plans/${rechargePlanForm.id}`, payload)
    else await http.post('/api/admin/api/finance/recharge-plans', payload)
    rechargePlanVisible.value = false
    ElMessage.success('充值套餐已保存')
    await load()
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '充值套餐保存失败')) }
  finally { rechargePlanSaving.value = false }
}

async function removeRechargePlan(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除充值套餐“${row.name}”？历史订单不会受影响。`, '删除套餐', { type: 'warning' })
    await http.delete(`/api/admin/api/finance/recharge-plans/${row.id}`)
    ElMessage.success('充值套餐已删除')
    await load()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(getHttpErrorMessage(error, '充值套餐删除失败'))
  }
}

function openCreate() {
  editingId.value = null
  Object.keys(form).forEach(key => delete form[key])
  for (const field of activeFields.value) {
    form[field.prop] = field.type === 'switch' ? true : field.type === 'number' ? 0 : ''
  }
  if (module.value === 'channels') {
    channelModelPricing.value = []
    channelCredentials.value = []
    activePricingModel.value = ''
    clearPricingSnapshots()
    channelModelOptions.value = []
    editorDiscoveryError.value = ''
    Object.assign(form, {
      type: 'openai-compatible',
      groupName: 'default',
      weight: 100,
      rpmLimit: 0,
      tpmLimit: 0,
      autoDisable: true,
      failureThreshold: 3,
      cooldownSeconds: 60,
      enabled: true
    })
  }
  drawerVisible.value = true
}

function openEdit(row: any) {
  editingId.value = row.id
  let selectDiscoveredWhenEmpty = false
  Object.keys(form).forEach(key => delete form[key])
  for (const field of activeFields.value) {
    const sourceKey = field.prop === 'key' ? 'setting_key' : field.prop === 'value' ? 'setting_value' : field.prop
    form[field.prop] = row[sourceKey] ?? row[field.prop] ?? ''
  }
  if (module.value === 'users') form.groupId = row.group_id ?? row.groupId ?? null
  if (module.value === 'channels') {
    channelModelPricing.value = (row.modelPricing || []).map((item: any) => normalizePricing(item.channelModelName, item))
    const mappedModels = channelModelPricing.value.map(item => item.channelModelName)
    const localModels = uniqueStrings([...splitChannelModels(form.models), ...mappedModels])
    selectDiscoveredWhenEmpty = localModels.length === 0
    form.models = localModels.join('\n')
    channelModelOptions.value = localModels
    editorDiscoveryError.value = ''
    syncChannelPricingRows(form.models)
    activePricingModel.value = channelModelPricing.value[0]?.channelModelName || ''
    markAllPricingSaved()
    void loadCredentials(row.id)
  }
  drawerVisible.value = true
  if (module.value === 'channels') void discoverChannelModelsForEditor(row.id, selectDiscoveredWhenEmpty, false)
}

async function save() {
  saving.value = true
  try {
    const payload = activeFormPayload()
    if (module.value === 'models') {
      await write(`/api/admin/api/models${editingId.value ? `/${editingId.value}` : ''}`, payload)
    } else if (module.value === 'tokens') {
      const response = await write(`/api/admin/api/tokens${editingId.value ? `/${editingId.value}` : ''}`, payload)
      if (!editingId.value) {
        const secret = typeof response.data?.secret === 'string' ? response.data.secret : ''
        if (secret) {
          issuedSecret.value = secret
          issuedSecretVisible.value = true
        } else {
          ElMessage.warning('API Key 已生成，但服务端未返回一次性密钥；请删除并重新生成')
        }
      }
    } else if (module.value === 'users' && editingId.value) {
      // Balance is intentionally excluded. It can only be changed through the
      // dedicated adjustment endpoint, which records a reason and ledger row.
      await http.put(`/api/admin/api/users/${editingId.value}`, payload)
    } else if (module.value === 'finance') {
      await http.post('/api/admin/api/finance/redeem-codes', payload)
    } else if (module.value === 'security') {
      await http.post('/api/admin/api/security/policies', editingId.value ? { ...payload, id: editingId.value } : payload)
    } else if (module.value === 'settings') {
      await http.put('/api/admin/api/settings', payload)
    }
    ElMessage.success('保存成功')
    drawerVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(getHttpErrorNotice(error, '保存失败'))
  } finally {
    saving.value = false
  }
}

function activeFormPayload() {
  const payload = Object.fromEntries(activeFields.value.map(field => [field.prop, form[field.prop]]))
  if (module.value === 'channels') {
    payload.modelPricing = channelModelPricing.value.map(pricingPayload)
  }
  return payload
}

function pricingPayload(item: ChannelModelPricing) {
  return {
    ...item,
    billingEnabled: item.billingMode !== 'DISABLED',
    priceTiers: item.pricingUnit === 'TOKEN' ? item.priceTiers.map((tier, index) => ({
      ...tier,
      sortOrder: index,
      officialPriceUnit: normalizePriceUnit(tier.officialPriceUnit, 'M'),
      costPriceUnit: normalizePriceUnit(tier.costPriceUnit, 'M'),
      salePriceUnit: normalizePriceUnit(tier.salePriceUnit, 'M'),
      officialPriceSuffix: String(tier.officialPriceSuffix || 'USD / 1M Token').trim(),
      costPriceSuffix: String(tier.costPriceSuffix || 'USD / 1M Token').trim(),
      salePriceSuffix: String(tier.salePriceSuffix || 'USD / 1M Token').trim(),
      maxContextTokens: index === item.priceTiers.length - 1
        ? null
        : Math.max(1, Number(tier.maxContextTokens || 1))
    })) : []
  }
}

function pricingSnapshot(item: ChannelModelPricing) {
  return JSON.stringify(pricingPayload(item))
}

function clearPricingSnapshots() {
  Object.keys(savedPricingSnapshots).forEach(key => delete savedPricingSnapshots[key])
}

function markPricingSaved(item: ChannelModelPricing) {
  savedPricingSnapshots[item.channelModelName] = pricingSnapshot(item)
}

function markAllPricingSaved() {
  clearPricingSnapshots()
  channelModelPricing.value.forEach(markPricingSaved)
}

function isPricingDirty(model: string) {
  const item = channelModelPricing.value.find(row => row.channelModelName === model)
  if (!item) return false
  return savedPricingSnapshots[model] !== pricingSnapshot(item)
}

async function saveChannelBase() {
  saving.value = true
  try {
    const creating = !editingId.value
    const payload = Object.fromEntries(activeFields.value.map(field => [field.prop, form[field.prop]]))
    const shouldAutoDiscover = creating && splitChannelModels(payload.models).length === 0
    const response = creating
      ? await http.post('/api/admin/api/channels', payload)
      : await http.put(`/api/admin/api/channels/${editingId.value}`, payload)
    const channelId = Number(response.data?.id || editingId.value || 0)
    if (!channelId) throw new Error('服务端未返回渠道 ID')
    editingId.value = channelId
    let discoveryMessage = ''
    if (shouldAutoDiscover) {
      try {
        const syncResponse = await http.post(`/api/admin/api/channels/${channelId}/sync-models`, { activateNew: false }, { timeout: 45_000 })
        discoveryMessage = `，已自动识别 ${Number(syncResponse.data?.models?.length || 0)} 个模型`
      } catch (error: unknown) {
        ElMessage.warning(getHttpErrorMessage(error, '渠道基础信息已保存，但自动识别模型失败，可手工添加'))
      }
    }
    await refreshChannelEditor(channelId)
    ElMessage.success(`渠道基础信息已保存${discoveryMessage}`)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorNotice(error, '渠道基础信息保存失败'))
  } finally {
    saving.value = false
  }
}

async function refreshChannelEditor(channelId: number) {
  const response = await http.get('/api/admin/api/channels')
  rows.value = response.data || []
  const latest = rows.value.find(row => Number(row.id) === channelId)
  if (!latest) return
  const dirtyModels = new Set(channelModelPricing.value
    .filter(item => isPricingDirty(item.channelModelName))
    .map(item => item.channelModelName))
  const drafts = new Map(channelModelPricing.value.map(item => [item.channelModelName, item]))
  const serverPricing = (latest.modelPricing || []).map((item: any) => normalizePricing(item.channelModelName, item))
  channelModelPricing.value = serverPricing.map((serverItem: ChannelModelPricing) => {
    const draft = drafts.get(serverItem.channelModelName)
    if (!draft || !dirtyModels.has(serverItem.channelModelName)) {
      markPricingSaved(serverItem)
      return serverItem
    }
    return { ...draft, id: serverItem.id }
  })
  form.models = String(latest.models || serverPricing.map((item: ChannelModelPricing) => item.channelModelName).join('\n'))
  channelModelOptions.value = uniqueStrings([...channelModelOptions.value, ...splitChannelModels(form.models)])
  if (!channelModelPricing.value.some(item => item.channelModelName === activePricingModel.value)) {
    activePricingModel.value = channelModelPricing.value[0]?.channelModelName || ''
  }
  await loadCredentials(channelId)
}

async function loadCredentials(channelId: number) {
  try { channelCredentials.value = (await http.get(`/admin/api/channels/${channelId}/credentials`)).data || [] }
  catch (error: unknown) { channelCredentials.value = []; ElMessage.error(getHttpErrorMessage(error, '凭证池加载失败')) }
}

function openCredential(row?: any) {
  Object.assign(credentialForm, row ? {
    id: row.id, name: row.name, secret: '', priority: Number(row.priority || 0), weight: Number(row.weight || 100),
    rpmLimit: Number(row.rpmLimit || 0), tpmLimit: Number(row.tpmLimit || 0),
    concurrencyLimit: Number(row.concurrencyLimit || 0), enabled: Boolean(row.enabled)
  } : { id: null, name: '', secret: '', priority: 0, weight: 100, rpmLimit: 0, tpmLimit: 0, concurrencyLimit: 0, enabled: true })
  credentialVisible.value = true
}

async function saveCredential() {
  if (!editingId.value || !credentialForm.name.trim()) return ElMessage.warning('请填写凭证名称')
  if (!credentialForm.id && !credentialForm.secret.trim()) return ElMessage.warning('请填写 API Key')
  credentialSaving.value = true
  try {
    const payload = { ...credentialForm, id: undefined }
    if (credentialForm.id) await http.put(`/admin/api/channels/${editingId.value}/credentials/${credentialForm.id}`, payload)
    else await http.post(`/admin/api/channels/${editingId.value}/credentials`, payload)
    credentialVisible.value = false
    await loadCredentials(editingId.value)
    ElMessage.success('渠道凭证已保存')
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '凭证保存失败')) }
  finally { credentialSaving.value = false }
}

async function testCredential(row: any) {
  if (!editingId.value) return
  credentialTestingId.value = row.id
  try {
    const { data } = await http.post(`/admin/api/channels/${editingId.value}/credentials/${row.id}/test`)
    ElMessage.success(`凭证测试成功（${Number(data?.latencyMs || 0)} ms）`)
    await loadCredentials(editingId.value)
  } catch (error: unknown) { ElMessage.error(getHttpErrorMessage(error, '凭证测试失败')) }
  finally { credentialTestingId.value = null }
}

async function removeCredential(row: any) {
  if (!editingId.value) return
  try {
    await ElMessageBox.confirm(`确认删除凭证“${row.name}”？`, '删除凭证', { type: 'warning' })
    await http.delete(`/admin/api/channels/${editingId.value}/credentials/${row.id}`)
    await loadCredentials(editingId.value)
    ElMessage.success('渠道凭证已删除')
  } catch (error: any) { if (error !== 'cancel' && error !== 'close') ElMessage.error(getHttpErrorMessage(error, '凭证删除失败')) }
}

async function saveCurrentModelPricing() {
  const pricing = activePricingRows.value[0]
  if (!editingId.value) {
    ElMessage.warning('请先保存渠道基础信息，再保存当前模型价格')
    return
  }
  if (!pricing) return
  modelPricingSaving.value = true
  try {
    const response = await http.put(`/api/admin/api/channels/${editingId.value}/model-pricing`, pricingPayload(pricing))
    const updated = normalizePricing(response.data?.channelModelName || pricing.channelModelName, response.data || pricing)
    const index = channelModelPricing.value.findIndex(item => item.channelModelName === pricing.channelModelName)
    if (index >= 0) channelModelPricing.value.splice(index, 1, updated)
    form.models = uniqueStrings([...selectedChannelModels.value, updated.channelModelName]).join('\n')
    markPricingSaved(updated)
    activePricingModel.value = updated.channelModelName
    ElMessage.success(`${updated.channelModelName} 已保存，您可以继续编辑或手动返回`)
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorNotice(error, '当前模型保存失败'))
  } finally {
    modelPricingSaving.value = false
  }
}

async function returnFromChannelEditor() {
  if (dirtyPricingCount.value > 0) {
    try {
      await ElMessageBox.confirm(`还有 ${dirtyPricingCount.value} 个模型价格未保存，确认返回列表？`, '存在未保存价格', {
        type: 'warning', confirmButtonText: '仍然返回', cancelButtonText: '继续编辑'
      })
    } catch { return }
  }
  drawerVisible.value = false
  await load()
}

function splitChannelModels(value: unknown): string[] {
  return uniqueStrings(String(value || '')
    .split(/[,，、\r\n]+/)
    .map(item => item.trim())
    .filter(Boolean))
}

function syncChannelPricingRows(value: unknown) {
  const existing = new Map(channelModelPricing.value.map(item => [item.channelModelName, item]))
  channelModelPricing.value = splitChannelModels(value).map(model => existing.get(model) || normalizePricing(model))
  if (!channelModelPricing.value.some(item => item.channelModelName === activePricingModel.value)) {
    activePricingModel.value = channelModelPricing.value[0]?.channelModelName || ''
  }
}

function selectOrCreatePricingModel(model: string) {
  const normalized = String(model || '').trim()
  if (!normalized) return
  if (!selectedChannelModels.value.includes(normalized)) {
    form.models = uniqueStrings([...selectedChannelModels.value, normalized]).join('\n')
    channelModelOptions.value = uniqueStrings([...channelModelOptions.value, normalized])
  }
  activePricingModel.value = normalized
}

async function removeActivePricingModel() {
  if (!activePricingModel.value) return
  const removed = activePricingModel.value
  const pricing = channelModelPricing.value.find(item => item.channelModelName === removed)
  try {
    await ElMessageBox.confirm(`确认从当前渠道删除模型“${removed}”？已保存的价格挡位也会删除。`, '删除渠道模型', {
      type: 'warning', confirmButtonText: '确认删除'
    })
  } catch { return }
  if (editingId.value && pricing?.id) {
    try {
      await http.delete(`/api/admin/api/channels/${editingId.value}/model-pricing/${pricing.id}`)
    } catch (error: unknown) {
      ElMessage.error(getHttpErrorNotice(error, '模型删除失败'))
      return
    }
  }
  const remaining = selectedChannelModels.value.filter(model => model !== removed)
  form.models = remaining.join('\n')
  channelModelOptions.value = channelModelOptions.value.filter(model => model !== removed)
  delete savedPricingSnapshots[removed]
  activePricingModel.value = remaining[0] || ''
  ElMessage.success(`${removed} 已删除`)
}

function normalizePricing(model: string, source: Partial<ChannelModelPricing> = {}): ChannelModelPricing {
  const legacy = legacyPriceTier(source)
  const sourceTiers = Array.isArray(source.priceTiers) && source.priceTiers.length
    ? source.priceTiers
    : [legacy]
  return {
    id: source.id,
    publicModelName: model,
    channelModelName: model,
    priority: Number(source.priority ?? 10),
    enabled: source.enabled ?? false,
    priceRatio: decimal(source.priceRatio, 1),
    costPerMillion: decimal(source.costPerMillion, 0),
    inputPricePerMillion: decimal(source.inputPricePerMillion, 0),
    outputPricePerMillion: decimal(source.outputPricePerMillion, 0),
    cachedPricePerMillion: decimal(source.cachedPricePerMillion, 0),
    inputCostPerMillion: decimal(source.inputCostPerMillion, 0),
    outputCostPerMillion: decimal(source.outputCostPerMillion, 0),
    cachedCostPerMillion: decimal(source.cachedCostPerMillion, 0),
    billingEnabled: source.billingEnabled ?? false,
    trafficPercent: Number(source.trafficPercent ?? 100),
    capabilityTags: source.capabilityTags || '',
    pricingUnit: String(source.pricingUnit || 'TOKEN').toUpperCase() as ChannelModelPricing['pricingUnit'],
    billingMode: String(source.billingMode || (source.billingEnabled ? 'PAID' : 'DISABLED')).toUpperCase() as ChannelModelPricing['billingMode'],
    pricingStatus: String(source.pricingStatus || 'PENDING').toUpperCase() as ChannelModelPricing['pricingStatus'],
    pricingMessage: String(source.pricingMessage || ''),
    pricingSourceUrl: String(source.pricingSourceUrl || ''),
    officialUnitPrice: decimal(source.officialUnitPrice, 0),
    costUnitPrice: decimal(source.costUnitPrice, 0),
    saleUnitPrice: decimal(source.saleUnitPrice, 0),
    priceTiers: sourceTiers.map((tier, index) => normalizePriceTier(tier, index, sourceTiers.length, legacy))
  }
}

function pricingUnitLabel(unit: string) {
  return pricingUnitOptions.find(item => item.value === unit)?.label || unit
}

function pricingUnitSuffix(unit: string) {
  return ({ SECOND: 'USD / 秒', IMAGE: 'USD / 张', MINUTE: 'USD / 分钟', CHARACTER: 'USD / 千字符', TASK: 'USD / 次' } as Record<string, string>)[unit] || 'USD / 单位'
}

function legacyPriceTier(source: Partial<ChannelModelPricing>): ModelPriceTier {
  return {
    tierName: '默认挡位',
    maxContextTokens: null,
    sortOrder: 0,
    officialGroupName: '官网价格（待补充）',
    officialInputPrice: 0,
    officialOutputPrice: 0,
    officialCacheReadPrice: 0,
    officialCacheWritePrice: 0,
    officialPriceUnit: 'M',
    officialPriceSuffix: 'USD / 1M Token',
    costGroupName: '采购成本',
    costInputPrice: decimal(source.inputCostPerMillion ?? source.costPerMillion, 0),
    costOutputPrice: decimal(source.outputCostPerMillion ?? source.costPerMillion, 0),
    costCacheReadPrice: decimal(source.cachedCostPerMillion, 0),
    costCacheWritePrice: 0,
    costPriceUnit: 'M',
    costPriceSuffix: 'USD / 1M Token',
    saleGroupName: '本站售价',
    saleInputPrice: decimal(source.inputPricePerMillion, 0),
    saleOutputPrice: decimal(source.outputPricePerMillion, 0),
    saleCacheReadPrice: decimal(source.cachedPricePerMillion, 0),
    saleCacheWritePrice: 0,
    salePriceUnit: 'M',
    salePriceSuffix: 'USD / 1M Token'
  }
}

function normalizePriceTier(source: Partial<ModelPriceTier>, index: number, count: number, fallback: ModelPriceTier): ModelPriceTier {
  return {
    id: source.id,
    modelMappingId: source.modelMappingId,
    tierName: String(source.tierName || (index === count - 1 ? '长上下文挡位' : `上下文挡位 ${index + 1}`)),
    maxContextTokens: index === count - 1 ? null : Math.max(1, Number(source.maxContextTokens || 1)),
    sortOrder: index,
    officialGroupName: String(source.officialGroupName || fallback.officialGroupName),
    officialInputPrice: decimal(source.officialInputPrice, fallback.officialInputPrice),
    officialOutputPrice: decimal(source.officialOutputPrice, fallback.officialOutputPrice),
    officialCacheReadPrice: decimal(source.officialCacheReadPrice, fallback.officialCacheReadPrice),
    officialCacheWritePrice: decimal(source.officialCacheWritePrice, fallback.officialCacheWritePrice),
    officialPriceUnit: normalizePriceUnit(source.officialPriceUnit, fallback.officialPriceUnit),
    officialPriceSuffix: String(source.officialPriceSuffix || fallback.officialPriceSuffix),
    costGroupName: String(source.costGroupName || fallback.costGroupName),
    costInputPrice: decimal(source.costInputPrice, fallback.costInputPrice),
    costOutputPrice: decimal(source.costOutputPrice, fallback.costOutputPrice),
    costCacheReadPrice: decimal(source.costCacheReadPrice, fallback.costCacheReadPrice),
    costCacheWritePrice: decimal(source.costCacheWritePrice, fallback.costCacheWritePrice),
    costPriceUnit: normalizePriceUnit(source.costPriceUnit, fallback.costPriceUnit),
    costPriceSuffix: String(source.costPriceSuffix || fallback.costPriceSuffix),
    saleGroupName: String(source.saleGroupName || fallback.saleGroupName),
    saleInputPrice: decimal(source.saleInputPrice, fallback.saleInputPrice),
    saleOutputPrice: decimal(source.saleOutputPrice, fallback.saleOutputPrice),
    saleCacheReadPrice: decimal(source.saleCacheReadPrice, fallback.saleCacheReadPrice),
    saleCacheWritePrice: decimal(source.saleCacheWritePrice, fallback.saleCacheWritePrice),
    salePriceUnit: normalizePriceUnit(source.salePriceUnit, fallback.salePriceUnit),
    salePriceSuffix: String(source.salePriceSuffix || fallback.salePriceSuffix)
  }
}

function normalizePriceUnit(value: unknown, fallback: 'M' | 'KB'): 'M' | 'KB' {
  return String(value || fallback).toUpperCase() === 'KB' ? 'KB' : 'M'
}

function addPriceTier(pricing: ChannelModelPricing) {
  const tiers = pricing.priceTiers
  const last = tiers[tiers.length - 1]
  const previousMax = tiers.length > 1 ? Number(tiers[tiers.length - 2].maxContextTokens || 0) : 0
  const suggestedMax = previousMax ? Math.min(100_000_000, previousMax * 2) : 128_000
  if (last && suggestedMax <= previousMax) {
    ElMessage.warning('上下文挡位上限已达到 100,000,000 Token')
    return
  }
  if (last) last.maxContextTokens = suggestedMax
  const source = last || legacyPriceTier(pricing)
  tiers.push(normalizePriceTier({
    ...source,
    id: undefined,
    modelMappingId: undefined,
    tierName: '长上下文挡位',
    maxContextTokens: null
  }, tiers.length, tiers.length + 1, source))
  tiers.forEach((tier, index) => { tier.sortOrder = index })
}

function removePriceTier(pricing: ChannelModelPricing, index: number) {
  pricing.priceTiers.splice(index, 1)
  pricing.priceTiers.forEach((tier, tierIndex) => {
    tier.sortOrder = tierIndex
    if (tierIndex === pricing.priceTiers.length - 1) tier.maxContextTokens = null
  })
}

function tierCostMultiplier(tier: ModelPriceTier, dimension: 'input' | 'output' | 'cacheRead' | 'cacheWrite') {
  const official = Number(dimension === 'input' ? tier.officialInputPrice
    : dimension === 'output' ? tier.officialOutputPrice
      : dimension === 'cacheRead' ? tier.officialCacheReadPrice : tier.officialCacheWritePrice)
  const cost = Number(dimension === 'input' ? tier.costInputPrice
    : dimension === 'output' ? tier.costOutputPrice
      : dimension === 'cacheRead' ? tier.costCacheReadPrice : tier.costCacheWritePrice)
  if (official <= 0) return '未设置官方价'
  const unitFactor = (unit: string) => unit === 'KB' ? 1000 : 1
  return `${roundPrice(cost * unitFactor(tier.costPriceUnit) / (official * unitFactor(tier.officialPriceUnit)))}×`
}

function copyFirstPricingToAll() {
  const first = channelModelPricing.value[0]
  if (!first) return
  channelModelPricing.value = channelModelPricing.value.map(item => normalizePricing(item.channelModelName, {
    ...first,
    publicModelName: item.channelModelName,
    channelModelName: item.channelModelName,
    priceTiers: first.priceTiers.map(tier => ({ ...tier, id: undefined, modelMappingId: undefined }))
  }))
}

function tierProfit(tier: ModelPriceTier) {
  const input = Number(tier.saleInputPrice || 0) - Number(tier.costInputPrice || 0)
  const output = Number(tier.saleOutputPrice || 0) - Number(tier.costOutputPrice || 0)
  const cacheRead = Number(tier.saleCacheReadPrice || 0) - Number(tier.costCacheReadPrice || 0)
  const cacheWrite = Number(tier.saleCacheWritePrice || 0) - Number(tier.costCacheWritePrice || 0)
  return { input, output, cacheRead, cacheWrite, hasLoss: input < 0 || output < 0 || cacheRead < 0 || cacheWrite < 0 }
}

function decimal(value: unknown, fallback: number): number {
  const parsed = Number(value)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : fallback
}

function roundPrice(value: number): number {
  return Math.round(value * 1_000_000) / 1_000_000
}

function decimalMoney(value: number): string {
  return `¥${roundPrice(value).toFixed(6).replace(/\.?0+$/, '') || '0'}`
}

function formatTokenLimit(value: number): string {
  return Number(value || 0).toLocaleString('zh-CN')
}

function tierRangeLabel(pricing: ChannelModelPricing, index: number): string {
  const tier = pricing.priceTiers[index]
  const previous = index > 0 ? Number(pricing.priceTiers[index - 1].maxContextTokens || 0) : 0
  if (tier.maxContextTokens == null) return previous ? `> ${formatTokenLimit(previous)} Token` : '全部上下文'
  return `${previous ? `${formatTokenLimit(previous + 1)} – ` : '≤ '}${formatTokenLimit(tier.maxContextTokens)} Token`
}

function rowPriceTiers(row: any): ModelPriceTier[] {
  const legacy = legacyPriceTier(row || {})
  const tiers = Array.isArray(row?.priceTiers) && row.priceTiers.length ? row.priceTiers : [legacy]
  return tiers.map((tier: Partial<ModelPriceTier>, index: number) => normalizePriceTier(tier, index, tiers.length, legacy))
}

function tierRangeLines(row: any): string[] {
  const pricing = { ...row, priceTiers: rowPriceTiers(row) } as ChannelModelPricing
  return pricing.priceTiers.map((tier, index) => `${tier.tierName}：${tierRangeLabel(pricing, index)}`)
}

function tierPriceLines(row: any, group: 'official' | 'cost' | 'sale'): string[] {
  return rowPriceTiers(row).map(tier => {
    const name = group === 'official' ? tier.officialGroupName : group === 'cost' ? tier.costGroupName : tier.saleGroupName
    const unit = group === 'official' ? tier.officialPriceUnit : group === 'cost' ? tier.costPriceUnit : tier.salePriceUnit
    const suffix = group === 'official' ? tier.officialPriceSuffix : group === 'cost' ? tier.costPriceSuffix : tier.salePriceSuffix
    const prices = group === 'official'
      ? [tier.officialInputPrice, tier.officialOutputPrice, tier.officialCacheReadPrice, tier.officialCacheWritePrice]
      : group === 'cost'
        ? [tier.costInputPrice, tier.costOutputPrice, tier.costCacheReadPrice, tier.costCacheWritePrice]
        : [tier.saleInputPrice, tier.saleOutputPrice, tier.saleCacheReadPrice, tier.saleCacheWritePrice]
    return `${tier.tierName} · ${name} [${unit}]：${prices.map(decimalMoney).join(' / ')}（${suffix}）`
  })
}

function tierProfitLines(row: any): string[] {
  return rowPriceTiers(row).map(tier => {
    const profit = tierProfit(tier)
    return `${tier.tierName}：${[profit.input, profit.output, profit.cacheRead, profit.cacheWrite].map(decimalMoney).join(' / ')}`
  })
}

function routeHasTierLoss(row: any): boolean {
  return rowPriceTiers(row).some(tier => tierProfit(tier).hasLoss)
}

function routeProfit(row: any) {
  const input = Number(row.inputPricePerMillion || 0) - Number(row.inputCostPerMillion || row.costPerMillion || 0)
  const output = Number(row.outputPricePerMillion || 0) - Number(row.outputCostPerMillion || row.costPerMillion || 0)
  const cached = Number(row.cachedPricePerMillion || 0) - Number(row.cachedCostPerMillion || 0)
  return { input, output, cached }
}

function routeHasLoss(row: any): boolean {
  const profit = routeProfit(row)
  return profit.input < 0 || profit.output < 0 || profit.cached < 0
}

function routeProfitSummary(row: any): string {
  const profit = routeProfit(row)
  return `${decimalMoney(profit.input)} / ${decimalMoney(profit.output)} / ${decimalMoney(profit.cached)}`
}

async function write(url: string, payload: Record<string, any>) {
  if (editingId.value) {
    return http.put(url, payload)
  }
  return http.post(url, payload)
}

async function copyIssuedSecret() {
  if (!issuedSecret.value) return
  try {
    await navigator.clipboard.writeText(issuedSecret.value)
    ElMessage.success('API Key 已复制')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
}

async function removeRow(row: any) {
  await ElMessageBox.confirm('确认删除该记录？此操作不可恢复。', '删除确认', { type: 'warning' })
  const urls: Partial<Record<ModuleKey, string>> = {
    channels: `/api/admin/api/channels/${row.id}`,
    models: `/api/admin/api/models/${row.id}`,
    tokens: `/api/admin/api/tokens/${row.id}`
  }
  const url = urls[module.value]
  if (!url) return
  await http.delete(url)
  ElMessage.success('删除成功')
  await load()
}

async function testChannel(row: any) {
  testModelOptions.value = await loadChannelTestModels(row)
  const providerModelName = testModelOptions.value[0]
  testTarget.value = row
  testForm.providerModelName = providerModelName || ''
  testForm.prompt = '你是什么模型'
  testForm.timeoutSeconds = row.type === 'nvidia' ? 120 : 20
  refreshTestPython()
  testResult.value = null
  testVisible.value = true
}

async function openModelDiscovery(row: any) {
  discoveryTarget.value = row
  discoveryResult.value = null
  activateDiscoveredModels.value = false
  discoveryVisible.value = true
  discoveryLoading.value = true
  try {
    const response = await http.post(`/api/admin/api/channels/${row.id}/discover-models`, {}, { timeout: 40_000 })
    discoveryResult.value = response.data
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '上游模型发现失败'))
  } finally {
    discoveryLoading.value = false
  }
}

async function discoverChannelModelsForEditor(channelId: number | null, selectWhenEmpty = false, announce = false) {
  if (!channelId || editorDiscoveryLoading.value) return
  editorDiscoveryLoading.value = true
  editorDiscoveryError.value = ''
  try {
    const response = await http.post(`/api/admin/api/channels/${channelId}/discover-models`, {}, { timeout: 40_000 })
    const discovered = uniqueStrings(Array.isArray(response.data?.models) ? response.data.models : [])
    channelModelOptions.value = uniqueStrings([...selectedChannelModels.value, ...discovered])
    if (selectWhenEmpty && selectedChannelModels.value.length === 0) {
      form.models = discovered.join('\n')
      activePricingModel.value = discovered[0] || ''
    }
    if (announce) ElMessage.success(`已识别 ${discovered.length} 个上游模型`)
  } catch (error: unknown) {
    editorDiscoveryError.value = getHttpErrorMessage(error, '自动识别失败')
    if (announce) ElMessage.error(editorDiscoveryError.value)
  } finally {
    editorDiscoveryLoading.value = false
  }
}

function addAllDiscoveredModels() {
  const models = uniqueStrings([...selectedChannelModels.value, ...channelModelOptions.value])
  form.models = models.join('\n')
  if (!activePricingModel.value) activePricingModel.value = models[0] || ''
}

async function syncDiscoveredModels() {
  if (!discoveryTarget.value) return
  syncRunning.value = true
  try {
    const response = await http.post(
      `/api/admin/api/channels/${discoveryTarget.value.id}/sync-models`,
      { activateNew: activateDiscoveredModels.value },
      { timeout: 45_000 }
    )
    const created = Number(response.data?.created || 0)
    ElMessage.success(`同步完成：新增 ${created} 个模型映射`)
    discoveryVisible.value = false
    await load()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '模型同步失败'))
  } finally {
    syncRunning.value = false
  }
}

async function loadChannelTestModels(row: any) {
  const fromChannel = splitModels(row.models)
  try {
    const res = await http.get('/api/admin/api/models')
    const fromMappings = (res.data || [])
      .filter((item: any) => Number(item.channelId) === Number(row.id))
      .map((item: any) => item.channelModelName || item.publicModelName)
      .filter(Boolean)
    return uniqueStrings([...fromMappings, ...fromChannel])
  } catch {
    return fromChannel
  }
}

function splitModels(value: any) {
  return String(value || '')
    .split(/[,，\n]/)
    .map(item => item.trim())
    .filter(Boolean)
}

function uniqueStrings(values: string[]) {
  return [...new Set(values.map(value => value.trim()).filter(Boolean))]
}

function refreshTestPython() {
  if (!testTarget.value) return
  testForm.pythonCode = buildProbePython(testTarget.value, testForm.providerModelName, testForm.prompt, testForm.timeoutSeconds)
}

async function runChannelTest() {
  if (!testTarget.value) return
  testRunning.value = true
  try {
    const row = testTarget.value
    // The Python preview is presentation-only. Never send executable source to
    // the server; the backend owns the fixed probe implementation.
    const payload = {
      providerModelName: testForm.providerModelName,
      prompt: testForm.prompt,
      timeoutSeconds: testForm.timeoutSeconds
    }
    const res = await http.post(`/api/admin/api/channels/${row.id}/test-model`, payload, {
      timeout: (testForm.timeoutSeconds + 10) * 1000
    })
    testResult.value = res.data
    const usage = res.data.usage || {}
    const message = `模型测试：${res.data.status}，${res.data.latencyMs || 0}ms，输入 ${usage.promptTokens || 0} / 输出 ${usage.completionTokens || 0}`
    if (res.data.status === 'SUCCESS') {
      ElMessage.success(message)
    } else {
      ElMessage.error(`${message}${res.data.error ? `，错误：${res.data.error}` : ''}`)
    }
    await load()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '模型测试失败'))
  } finally {
    testRunning.value = false
  }
}

function buildProbePython(row: any, model: string, promptValue: string, timeoutValue: number) {
  const provider = row.type || 'openai-compatible'
  const baseUrl = row.baseUrl || ''
  return `#!/usr/bin/env python3
import json
import os
import sys
import time
import urllib.error
import urllib.request

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

provider = os.environ.get("CHANNEL_PROVIDER", ${JSON.stringify(provider)})
base_url = os.environ.get("CHANNEL_BASE_URL", ${JSON.stringify(baseUrl)}).rstrip("/")
api_key = os.environ["CHANNEL_API_KEY"]
model = os.environ.get("CHANNEL_MODEL", ${JSON.stringify(model)})
prompt = os.environ.get("CHANNEL_PROMPT", ${JSON.stringify(promptValue || '你是什么模型')})
timeout = int(os.environ.get("CHANNEL_TIMEOUT", ${JSON.stringify(String(timeoutValue || 20))}))

def chat_url(base, provider):
    if base.endswith("/chat/completions"):
        return base
    if provider == "deepseek" or "api.deepseek.com" in base:
        return base + "/chat/completions"
    if base.endswith("/v1"):
        return base + "/chat/completions"
    return base + "/v1/chat/completions"

payload = {
    "model": model,
    "messages": [
        {"role": "system", "content": "You are a helpful assistant"},
        {"role": "user", "content": prompt}
    ],
    "stream": False,
    "max_tokens": 64
}

if provider == "deepseek" or model.startswith("deepseek-"):
    payload["reasoning_effort"] = "high"
    payload["thinking"] = {"type": "enabled"}

if provider == "nvidia" or model.startswith(("z-ai/", "google/gemma-")):
    payload["temperature"] = 1
    payload["top_p"] = 0.95 if model.startswith("google/gemma-") else 1
    payload["max_tokens"] = 16384 if model.startswith("z-ai/glm-") else 512
if model.startswith("google/gemma-"):
    payload["chat_template_kwargs"] = {"enable_thinking": True}
if model.startswith("z-ai/glm-"):
    payload["seed"] = 42

started = time.time()
try:
    if provider == "nvidia":
        payload["stream"] = True
        req = urllib.request.Request(
            chat_url(base_url, provider),
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": "Bearer " + api_key,
                "Content-Type": "application/json",
                "Accept": "text/event-stream"
            },
            method="POST"
        )
        chunks = []
        usage = {}
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            for raw_line in resp:
                line = raw_line.decode("utf-8", errors="replace").strip()
                if not line or line.startswith(":"):
                    continue
                if line.startswith("data:"):
                    line = line[5:].strip()
                if line == "[DONE]":
                    break
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if event.get("usage"):
                    usage = event.get("usage") or {}
                choices = event.get("choices") or []
                if choices:
                    delta = (choices[0] or {}).get("delta") or {}
                    text = delta.get("content") or delta.get("reasoning_content")
                    if text:
                        chunks.append(str(text))
                        if len("".join(chunks)) >= 300:
                            break
        content = "".join(chunks)
    else:
        req = urllib.request.Request(
            chat_url(base_url, provider),
            data=json.dumps(payload).encode("utf-8"),
            headers={
                "Authorization": "Bearer " + api_key,
                "Content-Type": "application/json"
            },
            method="POST"
        )
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.loads(resp.read().decode("utf-8", errors="replace"))
        usage = data.get("usage") or {}
        message = ((data.get("choices") or [{}])[0].get("message") or {})
        content = message.get("content") or ""
    details = usage.get("prompt_tokens_details") or {}
    cache_read_tokens = max(
        int(details.get("cached_tokens") or 0),
        int(usage.get("cache_read_input_tokens") or 0)
    )
    cache_write_tokens = int(usage.get("cache_creation_input_tokens") or 0)
    print(json.dumps({
        "status": "SUCCESS",
        "latencyMs": int((time.time() - started) * 1000),
        "model": model,
        "usage": {
            "promptTokens": int(usage.get("prompt_tokens") or 0),
            "completionTokens": int(usage.get("completion_tokens") or 0),
            "cachedTokens": cache_read_tokens + cache_write_tokens,
            "cacheReadTokens": cache_read_tokens,
            "cacheWriteTokens": cache_write_tokens
        },
        "sampleText": str(content)[:500],
        "error": None,
        "exitCode": 0
    }, ensure_ascii=False))
except urllib.error.HTTPError as exc:
    body = exc.read().decode("utf-8", errors="replace")
    print(json.dumps({
        "status": "AUTH_FAILED" if exc.code in (401, 403) else "FAILED",
        "latencyMs": int((time.time() - started) * 1000),
        "model": model,
        "usage": {"promptTokens": 0, "completionTokens": 0, "cachedTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0},
        "sampleText": "",
        "error": "HTTP " + str(exc.code) + ": " + body[:1000],
        "exitCode": exc.code
    }, ensure_ascii=False))
except Exception as exc:
    print(json.dumps({
        "status": "FAILED",
        "latencyMs": int((time.time() - started) * 1000),
        "model": model,
        "usage": {"promptTokens": 0, "completionTokens": 0, "cachedTokens": 0, "cacheReadTokens": 0, "cacheWriteTokens": 0},
        "sampleText": "",
        "error": str(exc),
        "exitCode": 1
    }, ensure_ascii=False))
`
}

function openAdjust(row: any) {
  adjustTarget.value = row
  adjustForm.amount = 0
  adjustForm.reason = ''
  adjustVisible.value = true
}

async function saveAdjust() {
  if (!adjustTarget.value) return
  if (!Number.isFinite(adjustForm.amount) || adjustForm.amount === 0) {
    ElMessage.warning('调整金额不能为 0')
    return
  }
  const reason = adjustForm.reason.trim()
  if (!reason) {
    ElMessage.warning('请填写调账原因')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认将用户 ${adjustTarget.value.username} 的余额调整 ${adjustForm.amount > 0 ? '+' : ''}${adjustForm.amount.toLocaleString()} amount units（${money(adjustForm.amount)}）？`,
      '高风险财务操作',
      { type: 'warning', confirmButtonText: '确认调账' }
    )
  } catch {
    return
  }
  adjustSaving.value = true
  try {
    await http.post(`/api/admin/api/users/${adjustTarget.value.id}/adjust-balance`, {
      amount: adjustForm.amount,
      reason
    })
    ElMessage.success('调账成功，已写入财务流水')
    adjustVisible.value = false
    await load()
  } catch (error: unknown) {
    ElMessage.error(getHttpErrorMessage(error, '调账失败'))
  } finally {
    adjustSaving.value = false
  }
}

function display(value: any) {
  if (value === null || value === undefined || value === '') return '-'
  if (typeof value === 'object') return value.name || JSON.stringify(value)
  return value
}

function getValue(row: any, prop: string) {
  return prop.split('.').reduce((acc, key) => acc == null ? undefined : acc[key], row)
}

function number(value: any) {
  return Number(value || 0).toLocaleString()
}

function money(value: any) {
  return formatCny(typeof value === 'string' || typeof value === 'number' || typeof value === 'bigint' ? value : 0)
}

function rateMoney(value: any) {
  return formatPerMillionUsd(typeof value === 'string' || typeof value === 'number' || typeof value === 'bigint' ? value : 0)
}

function percent(value: any) {
  return `${Number(value || 0).toFixed(1)}%`
}

function statusType(value: string) {
  if (['SUCCESS', 'ACTIVE', 'HEALTHY', 'FULFILLED', true].includes(value as any)) return 'success'
  if (['FAILED', 'SUSPENDED', 'DISABLED', 'CANCELLED'].includes(value)) return 'danger'
  if (['PENDING', 'CONFIRMED', 'DEGRADED', 'WARN', 'REVIEW'].includes(value)) return 'warning'
  return 'info'
}

function healthType(value: string) {
  return value === 'HEALTHY' ? 'success' : value === 'DEGRADED' ? 'warning' : 'danger'
}
</script>

<style scoped>
.admin-console {
  display: flex;
  flex-direction: column;
  gap: 18px;
  min-width: 0;
}

.console-toolbar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 18px;
}

.console-toolbar > div {
  min-width: 0;
}

.console-toolbar h2 {
  margin: 0;
  font-size: 24px;
  color: #111827;
}

.console-toolbar p {
  margin: 8px 0 0;
  color: #6b7280;
}

.toolbar-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  max-width: 100%;
}

.toolbar-actions .el-input {
  width: 260px;
}

.admin-metrics {
  margin-bottom: 0;
}

.panel {
  min-width: 0;
  overflow: hidden;
  padding: 18px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 12px 28px rgba(15, 23, 42, 0.05);
}

.panel :deep(.el-table) {
  width: 100%;
}

.dashboard-usage-panel,
.admin-usage-analytics {
  display: grid;
  gap: 16px;
}

.usage-filter-row {
  display: grid;
  grid-template-columns: minmax(250px, 1.3fr) repeat(4, minmax(150px, .8fr)) auto;
  gap: 9px;
  align-items: center;
}

.usage-filter-row :deep(.el-date-editor),
.usage-filter-row :deep(.el-select) {
  width: 100%;
}

.security-workspace { min-width: 0; }
.security-toolbar,
.sensitive-test-box > div:first-child {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.security-toolbar { margin: 14px 0; }
.security-toolbar span,
.sensitive-test-box span { display: block; margin-top: 4px; color: #71829b; font-size: 12px; }
.security-toolbar > div:last-child { display: flex; gap: 8px; }
.sensitive-test-box { display: grid; gap: 10px; margin-top: 18px; padding: 16px; border: 1px solid #d8e5f7; border-radius: 12px; background: #f7fbff; }
.sensitive-form-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 0 12px; }
.sensitive-form-grid :deep(.el-select) { width: 100%; }

.admin-table-shell {
  width: 100%;
  min-width: 0;
  overflow: hidden;
}

.admin-table-shell :deep(.el-table__inner-wrapper),
.admin-table-shell :deep(.el-table__body-wrapper),
.admin-table-shell :deep(.el-scrollbar) {
  min-width: 0;
}

.admin-table-shell :deep(.el-scrollbar__bar.is-horizontal) {
  height: 10px;
  opacity: 1;
}

.admin-table-shell :deep(.el-scrollbar__thumb) {
  background-color: #64748b;
}

.admin-table-channels :deep(.el-table__body),
.admin-table-channels :deep(.el-table__header) {
  min-width: 1400px;
}

.admin-table-models :deep(.el-table__body),
.admin-table-models :deep(.el-table__header) {
  min-width: 2600px;
}

.admin-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(360px, 0.62fr);
  gap: 18px;
}

.wide-panel {
  min-width: 0;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.panel-head h3 {
  margin: 0;
  color: #111827;
  font-size: 18px;
}

.risk-list {
  display: grid;
  gap: 10px;
}

.risk-list div {
  display: grid;
  grid-template-columns: 10px 1fr;
  gap: 10px;
  align-items: start;
  padding: 12px;
  border-radius: 8px;
  background: #fff7ed;
}

.risk-list span {
  width: 9px;
  height: 9px;
  margin-top: 6px;
  border-radius: 50%;
  background: #f97316;
}

.risk-list p {
  margin: 0;
}

.risk-list small {
  display: block;
  margin-top: 4px;
  color: #9a3412;
}

.tabs-panel {
  padding-top: 8px;
}

.report-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 16px;
}

.report-summary div {
  min-height: 76px;
  padding: 12px;
  border-radius: 8px;
  background: #f8fafc;
}

.report-summary span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.report-summary strong {
  display: block;
  margin-top: 10px;
  color: #111827;
  font-size: 22px;
}

.drawer-alert,
.field-hint {
  margin-bottom: 16px;
}

.field-hint {
  width: 100%;
  color: #64748b;
  font-size: 12px;
}

.discovery-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 18px 0;
}

.discovery-summary div {
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #f8fafc;
}

.discovery-summary span,
.discovery-summary strong {
  display: block;
}

.discovery-summary span {
  color: #64748b;
  font-size: 12px;
}

.discovery-summary strong {
  margin-top: 6px;
  color: #0f172a;
}

.discovery-switch {
  margin-top: 18px;
}

.channel-model-manager {
  display: grid;
  width: 100%;
  gap: 10px;
}

.channel-model-select {
  width: 100%;
}

.model-option-row {
  display: flex;
  min-width: 0;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.model-option-row span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
}

.channel-model-manager-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.channel-model-manager-actions .el-button + .el-button {
  margin-left: 0;
}

.channel-model-manager .form-hint {
  margin: 0;
  color: #64748b;
  font-size: 12px;
}

.channel-model-manager .model-discovery-error {
  color: #b45309;
}

.issued-secret {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  margin-top: 18px;
}

.issued-secret code {
  overflow-wrap: anywhere;
  padding: 14px;
  border-radius: 8px;
  background: #0f172a;
  color: #e2e8f0;
}

.channel-pricing-editor {
  display: grid;
  gap: 16px;
  margin-top: 22px;
  padding-top: 20px;
  border-top: 1px solid #e2e8f0;
}

.channel-pricing-head,
.channel-model-pricing-title,
.price-tier-toolbar,
.price-tier-head,
.price-group-head,
.channel-price-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.channel-pricing-head h3,
.channel-pricing-head p {
  margin: 0;
}

.channel-pricing-head p,
.price-tier-toolbar span,
.channel-model-pricing-title span {
  display: block;
  margin-top: 5px;
  color: #64748b;
  font-size: 12px;
}

.channel-model-pricing-card {
  display: grid;
  gap: 16px;
  padding: 18px;
  border: 1px solid #cbd5e1;
  border-radius: 12px;
  background: #f8fafc;
}

.credential-pool-card {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid #cfe0f6;
  border-radius: 12px;
  background: #f7fbff;
}

.credential-pool-head { display:flex;align-items:center;justify-content:space-between;gap:12px; }
.credential-pool-head div { display:grid;gap:3px; }
.credential-pool-head strong { color:#17345f;font-size:14px; }
.credential-pool-head span { color:#64748b;font-size:11px; }
.credential-form-grid { display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px; }
.unit-price-card { display:grid;gap:14px;padding:16px;border:1px solid #b9d8f5;border-radius:12px;background:#fff; }
.unit-price-head { display:flex;align-items:center;justify-content:space-between;gap:12px; }
.unit-price-head strong { color:#17345f; }
.unit-price-head span { color:#64748b;font-size:12px; }
.unit-price-grid { display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px; }
.unit-price-grid.metadata { grid-template-columns:repeat(2,minmax(0,1fr)); }
.unit-price-grid label { display:grid;gap:6px;min-width:0;color:#475569;font-size:12px; }
.unit-price-grid :deep(.el-input-number) { width:100%; }

.channel-model-pricing-title strong {
  display: block;
  color: #0f172a;
  font-size: 18px;
}

.channel-model-pricing-switches,
.tier-head-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
}

.channel-price-grid,
.tier-identity {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.channel-price-grid label,
.tier-identity label,
.price-dimension-grid label {
  display: grid;
  gap: 6px;
  min-width: 0;
  color: #475569;
  font-size: 12px;
}

.channel-price-grid :deep(.el-input-number),
.tier-identity :deep(.el-input-number),
.price-dimension-grid :deep(.el-input-number) {
  width: 100%;
}

.price-tier-toolbar {
  align-items: flex-end;
  padding-top: 6px;
}

.price-tier-card {
  display: grid;
  gap: 14px;
  padding: 16px;
  border: 1px solid #dbeafe;
  border-radius: 10px;
  background: #fff;
}

.price-tier-head {
  align-items: flex-end;
}

.tier-identity {
  flex: 1;
  grid-template-columns: repeat(2, minmax(180px, 1fr));
}

.tier-price-groups {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.tier-price-group {
  display: grid;
  align-content: start;
  gap: 12px;
  min-width: 0;
  padding: 12px;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
}

.official-group { background: #f8fafc; }
.cost-group { background: #fff7ed; border-color: #fed7aa; }
.sale-group { background: #f0fdf4; border-color: #bbf7d0; }

.price-group-head {
  align-items: center;
}

.tier-price-group .price-group-head {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  align-items: stretch;
  gap: 8px;
}

.price-group-head strong {
  flex: 0 0 auto;
  color: #0f172a;
  font-size: 12px;
}

.price-group-meta {
  display: grid;
  grid-template-columns: minmax(0, .8fr) 54px minmax(0, 1.2fr);
  flex: 1;
  gap: 5px;
  min-width: 0;
  width: 100%;
}

.price-group-meta > * { min-width: 0; }
.price-group-meta :deep(.el-input__wrapper),
.price-group-meta :deep(.el-select__wrapper) { min-width: 0; padding-left: 6px; padding-right: 6px; }
.price-group-meta :deep(.el-input__inner) { font-size: 11px; }

.price-unit-select {
  width: 54px;
}

.price-dimension-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.channel-price-actions {
  align-items: flex-start;
  color: #475569;
  font-size: 12px;
}

.channel-price-actions .loss,
.negative-profit {
  color: #dc2626;
}

.sensitive-guide-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin: 14px 0 18px;
}

.sensitive-guide-grid article {
  min-width: 0;
  padding: 12px;
  border: 1px solid #dbe7f8;
  border-radius: 10px;
  background: #f8fbff;
}

.sensitive-guide-grid strong,
.sensitive-guide-grid span {
  display: block;
}

.sensitive-guide-grid strong { color: #17345f; font-size: 13px; }
.sensitive-guide-grid span { margin-top: 5px; color: #64748b; font-size: 12px; line-height: 1.55; }

.tier-table-cell {
  display: grid;
  gap: 8px;
  line-height: 1.45;
  white-space: normal;
}

.tier-table-cell span + span {
  padding-top: 8px;
  border-top: 1px dashed #e2e8f0;
}

@media (max-width: 1200px) {
  .console-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .toolbar-actions {
    width: 100%;
  }

  .toolbar-actions .el-input {
    flex: 1 1 260px;
    width: auto;
    min-width: 0;
  }

  .admin-grid {
    grid-template-columns: minmax(0, 1fr);
  }

  .discovery-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .tier-price-groups {
    grid-template-columns: minmax(0, 1fr);
  }

  .usage-filter-row { grid-template-columns: repeat(2,minmax(0,1fr)); }

  .sensitive-guide-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 680px) {
  .toolbar-actions > * {
    flex: 1 1 100%;
    margin-left: 0 !important;
  }

  .discovery-summary,
  .report-summary,
  .issued-secret {
    grid-template-columns: minmax(0, 1fr);
  }

  .channel-pricing-head,
  .channel-model-pricing-title,
  .price-tier-toolbar,
  .price-tier-head,
  .price-group-head,
  .channel-price-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .channel-price-grid,
  .tier-identity,
  .price-dimension-grid,
  .credential-form-grid,
  .unit-price-grid,
  .unit-price-grid.metadata {
    grid-template-columns: minmax(0, 1fr);
  }

  .price-group-meta {
    width: 100%;
    grid-template-columns: minmax(0, 1fr);
  }

  .price-unit-select {
    width: 100%;
  }

  .usage-filter-row,
  .sensitive-form-grid,
  .sensitive-guide-grid { grid-template-columns: minmax(0,1fr); }

  .channel-model-pricing-card,
  .price-tier-card,
  .tier-price-group {
    padding: 12px;
  }
}
</style>
