<template>
  <div v-if="analyticsConfigured && choice === null" class="cookie-banner" role="region" :aria-label="en ? 'Cookie choices' : 'Cookie 选择'">
    <p>{{ en ? 'Optional analytics is off until you choose. Read our cookie policy.' : '可选统计脚本默认关闭，选择同意后才加载。请阅读 Cookie 政策。' }} <router-link to="/cookies">{{ en ? 'Details' : '详情' }}</router-link></p>
    <button @click="choose(false)">{{ en ? 'Reject optional' : '拒绝可选项' }}</button>
    <button @click="visible = true">{{ en ? 'Settings' : '设置' }}</button>
    <button @click="choose(true)">{{ en ? 'Allow analytics' : '同意统计' }}</button>
  </div>
  <el-dialog v-model="visible" :title="en ? 'Cookie preferences' : 'Cookie 偏好设置'" width="min(480px, 94vw)">
    <p>{{ en ? 'Essential storage supports login and security. Analytics is optional.' : '必要存储用于登录和安全；访问统计为可选项。' }}</p>
    <el-switch v-model="analytics" :disabled="!analyticsConfigured" :active-text="en ? 'Analytics' : '访问统计'" />
    <template #footer><el-button type="primary" @click="choose(analytics)">{{ en ? 'Save choice' : '保存选择' }}</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { isEnglish } from '@/i18n/locale'
import { analyticsConfigured, analyticsChoice, applyAnalyticsChoice } from '@/utils/cookieConsent'
const en = isEnglish
const choice = ref<boolean | null>(analyticsChoice())
const analytics = ref(choice.value === true)
const visible = ref(false)
function open() { analytics.value = choice.value === true; visible.value = true }
function choose(value: boolean) { applyAnalyticsChoice(value); choice.value = value; visible.value = false }
onMounted(() => { window.addEventListener('open-cookie-preferences', open); if (choice.value === true) applyAnalyticsChoice(true) })
onBeforeUnmount(() => window.removeEventListener('open-cookie-preferences', open))
</script>
<style scoped>
.cookie-banner{position:fixed;bottom:12px;left:12px;right:12px;z-index:2100;background:#fff;color:#17324d;border:1px solid #bdcde2;border-radius:12px;padding:16px;box-shadow:0 10px 35px #0003;display:flex;align-items:center;gap:12px;flex-wrap:wrap}.cookie-banner p{flex:1;min-width:240px;margin:0}.cookie-banner button{cursor:pointer;padding:8px 12px;border:1px solid #9cb9db;border-radius:7px;background:#eff6ff;color:#0b4292}.cookie-banner a{color:#1558ac}
</style>
