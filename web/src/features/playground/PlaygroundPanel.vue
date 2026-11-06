<template>
        <section class="playground-layout">
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

</template>
<script setup lang="ts">
import ModelSalePricing from '@/components/ModelSalePricing.vue'
import { number, keyPreview } from '@/features/user-console/format'
import { formatUsd, formatPerMillionUsd } from '@/utils/money'
import type { usePlayground } from './usePlayground'
const props = defineProps<{ state: ReturnType<typeof usePlayground>; dashboard: any }>()
const { playground, playgroundLoading, playgroundResponse, playgroundRawResponse, playgroundError, playgroundRawSections, playgroundUsage, playgroundModels, selectedModelDetail, syncPlaygroundModel, sendPlaygroundRequest } = props.state
</script>
