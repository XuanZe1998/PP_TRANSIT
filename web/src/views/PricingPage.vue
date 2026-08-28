<template>
  <section id="pricing" class="site-section">
    <div class="section-head">
      <div>
        <p class="eyebrow">套餐价格</p>
        <h2>充值方案从后端配置读取，未配置前不展示虚假价格。</h2>
      </div>
    </div>
    <p class="pricing-disclosure">
      模型输入、输出、缓存命中与写入价格统一以 USD 展示；钱包充值和实际扣款以 CNY 结算。
      下单前请以登录后钱包中的实时方案和模型账单为准。
    </p>
    <div class="pricing-grid">
      <article v-for="plan in plans" :key="plan.name" class="pricing-card">
        <h3>{{ plan.name }}</h3>
        <strong>{{ plan.price }}</strong>
        <p>{{ plan.desc }}</p>
        <el-button :type="plan.primary ? 'primary' : 'default'" @click="router.push('/console/wallet')">选择套餐</el-button>
      </article>
      <article v-if="shopGptEnabled" class="pricing-card product-pricing-card">
        <div class="product-pricing-cover">
          <img src="https://shopgpt.plus/assets/cache/general/image/202603191955477249694.jpg" alt="GPT RT Plus 成品号（欧洲渠道）" />
        </div>
        <h3>GPT RT Plus 成品号（欧洲渠道）</h3>
        <strong>启用后实时询价</strong>
        <p>该外部商品只有在运营方显式开启功能开关后才展示，价格以商品页服务端询价为准。</p>
        <el-button type="primary" @click="router.push('/item')">查看商品</el-button>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
const shopGptEnabled = import.meta.env.VITE_ENABLE_SHOPGPT === 'true'
const plans = [
  { name: '自助额度', price: '以钱包实时方案为准', desc: '登录后查看管理员已启用的充值方案；未启用支付时仅支持受控兑换码。', primary: true },
  { name: '企业方案', price: '当前未配置公开价格', desc: '需由运营方配置专属渠道、计费和审计规则后再对外销售。', primary: false }
]
</script>
