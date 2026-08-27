<template>
  <main class="redemption-redirect">
    <section class="redirect-card" aria-live="polite">
      <div class="redirect-icon">卡</div>
      <p class="eyebrow">卡密兑换</p>
      <h1>正在前往兑换网站</h1>
      <p>本页不读取、保存或传递您的卡密，只负责跳转到管理员预先配置的 HTTPS 兑换地址。</p>
      <el-button type="primary" size="large" @click="redirect">立即继续</el-button>
      <router-link to="/services">返回服务目录</router-link>
    </section>
  </main>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const serviceId = String(route.params.id || '')

function redirect() {
  if (!/^\d+$/.test(serviceId)) {
    window.location.replace('/services')
    return
  }
  window.location.replace(`/api/public/other-services/${encodeURIComponent(serviceId)}/redeem`)
}

onMounted(() => window.setTimeout(redirect, 500))
</script>

<style scoped>
.redemption-redirect {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background: radial-gradient(circle at top, #eff6ff, #f8fafc 55%);
}

.redirect-card {
  width: min(520px, 100%);
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 16px;
  padding: 48px;
  border: 1px solid #dbeafe;
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 24px 70px rgb(15 23 42 / 10%);
  text-align: center;
}

.redirect-icon {
  width: 64px;
  height: 64px;
  display: grid;
  place-items: center;
  border-radius: 18px;
  background: #2563eb;
  color: #fff;
  font-size: 26px;
  font-weight: 700;
}

.redirect-card h1,
.redirect-card p {
  margin: 0;
}

.redirect-card p {
  color: #64748b;
  line-height: 1.75;
}
</style>
