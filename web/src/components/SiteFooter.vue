<template>
  <footer class="site-legal-footer">
    <div class="footer-inner">
      <div class="footer-identity"><strong>{{ siteConfig.name }}</strong><span v-if="operator">{{ operator }}</span><span v-if="legal?.registration">{{ legal.registration }}</span><span v-if="address">{{ address }}</span><a v-if="legal?.contact_email" :href="`mailto:${legal.contact_email}`">{{ legal.contact_email }}</a></div>
      <nav :aria-label="en ? 'Legal and support' : '法律与支持'">
        <router-link v-for="item in legalLinks" :key="item.path" :to="item.path">{{ en ? item.en : item.zh }}</router-link>
        <button v-if="analyticsConfigured" type="button" @click="openCookiePreferences">{{ en ? 'Cookie preferences' : 'Cookie 偏好设置' }}</button>
      </nav>
    </div>
  </footer>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import http from '@/utils/http'
import { siteConfig } from '@/config/site'
import { isEnglish } from '@/i18n/locale'
import { legalLinks } from '@/config/legal'
import { analyticsConfigured, openCookiePreferences } from '@/utils/cookieConsent'
const en = isEnglish
const legal = ref<Record<string, string> | null>(null)
const operator = computed(() => legal.value?.operator === 'LinkNux API 服务平台' ? '' : legal.value?.operator)
const address = computed(() => legal.value?.address === '请以运营主体公示信息为准' ? '' : legal.value?.address)
onMounted(async () => { try { legal.value = (await http.get('/api/public/legal')).data } catch { /* Keep links available during an API outage. */ } })
</script>
<style scoped>
.site-legal-footer{background:#0c1933;color:#d5e1f2;padding:36px 20px;font-size:13px}.footer-inner{max-width:1200px;margin:auto;display:flex;justify-content:space-between;gap:36px}.footer-identity{display:flex;flex-direction:column;gap:7px;max-width:400px}.footer-identity strong{font-size:18px;color:#fff}.footer-identity a,.site-legal-footer nav a,.site-legal-footer button{color:#d5e1f2;text-decoration:none}.site-legal-footer nav{display:flex;flex-wrap:wrap;justify-content:flex-end;gap:12px 20px;max-width:600px}.site-legal-footer button{background:none;border:0;padding:0;cursor:pointer;font:inherit}.site-legal-footer a:hover,.site-legal-footer button:hover{text-decoration:underline}@media(max-width:700px){.footer-inner{flex-direction:column}.site-legal-footer nav{justify-content:flex-start}}
</style>
