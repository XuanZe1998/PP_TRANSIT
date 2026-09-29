<template>
  <main class="legal-page" v-loading="loading">
    <router-link to="/" class="back">← {{ en ? 'Back to home' : '返回首页' }}</router-link>
    <article v-if="legal" data-no-auto-i18n>
      <p class="eyebrow">LINKNUX · {{ en ? 'INFORMATION' : '公开信息' }}</p>
      <h1>{{ title }}</h1>
      <p class="meta">{{ legal.publication_ready ? (en ? 'Effective date' : '生效日期') : (en ? 'Proposed effective date' : '拟生效日期') }} {{ legal.effective_date }}<template v-if="kind === 'terms' || kind === 'privacy'"> · {{ legal.publication_ready ? (en ? 'Version' : '版本') : (en ? 'Proposed version' : '拟发布版本') }} {{ legal[`${kind}_version`] }}</template></p>
      <el-alert v-if="!legal.publication_ready" type="warning" :closable="false" :title="en ? 'Draft information: the operator has not completed and approved all required disclosures.' : '运营方尚未完成并审核公开信息；此页面不能作为已发布的正式条款。'" />
      <el-alert v-if="!content" type="warning" :closable="false" :title="en ? 'Content is not yet published. Please contact support before purchasing or submitting sensitive data.' : '正文尚未发布。购买或提交敏感数据前请联系运营方。'" />
      <div v-else class="legal-copy"><template v-for="(paragraph, index) in paragraphs" :key="index"><h2 v-if="isSectionHeading(paragraph)">{{ paragraph }}</h2><p v-else>{{ paragraph }}</p></template></div>
      <h2>{{ en ? 'Operator and contact' : '运营与联系' }}</h2>
      <dl>
        <template v-if="operator"><dt>{{ en ? 'Operator identity' : '经营者身份' }}</dt><dd>{{ operator }}</dd></template>
        <template v-if="registration"><dt>{{ en ? 'Registration' : '注册信息' }}</dt><dd>{{ registration }}</dd></template>
        <template v-if="jurisdiction"><dt>{{ en ? 'Jurisdiction' : '注册地' }}</dt><dd>{{ jurisdiction }}</dd></template>
        <template v-if="address"><dt>{{ en ? 'Address' : '地址' }}</dt><dd>{{ address }}</dd></template>
        <template v-if="legal.contact_email"><dt>{{ en ? 'Contact' : '联系邮箱' }}</dt><dd><a :href="`mailto:${legal.contact_email}`">{{ legal.contact_email }}</a></dd></template>
      </dl>
      <nav class="legal-links" :aria-label="en ? 'Related disclosures' : '相关公开信息'">
        <router-link v-for="item in related" :key="item.path" :to="item.path">{{ en ? item.en : item.zh }}</router-link>
      </nav>
    </article>
    <el-result v-else-if="error" icon="warning" :title="en ? 'Unable to load this document' : '暂时无法加载公开信息'" :sub-title="error">
      <template #extra><el-button type="primary" @click="loadLegal">{{ en ? 'Retry' : '重新加载' }}</el-button></template>
    </el-result>
  </main>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/utils/http'
import { isEnglish } from '@/i18n/locale'
import { legalLinks, type LegalKind } from '@/config/legal'

const route = useRoute()
const en = isEnglish
const loading = ref(true)
const legal = ref<Record<string, any> | null>(null)
const error = ref('')
const kind = computed(() => (route.meta.legalKind || 'terms') as LegalKind)
const title = computed(() => {
  const item = legalLinks.find(link => link.kind === kind.value)
  return en.value ? item?.en : item?.zh
})
const content = computed(() => String(legal.value?.[kind.value + (en.value ? '_en' : '')] || '').trim())
const paragraphs = computed(() => content.value.split(/\n\s*\n|\n/).map(p => p.trim()).filter(Boolean))
function isSectionHeading(paragraph: string) { return /^\d+\.\s.{1,90}$/.test(paragraph) }
const related = computed(() => legalLinks.filter(link => link.kind !== kind.value))
const operator = computed(() => legal.value?.operator === 'LinkNux API 服务平台' ? '' : (en.value ? legal.value?.operator_en || legal.value?.operator : legal.value?.operator))
const address = computed(() => legal.value?.address === '请以运营主体公示信息为准' ? '' : legal.value?.address)
const registration = computed(() => en.value ? legal.value?.registration_en || legal.value?.registration : legal.value?.registration)
const jurisdiction = computed(() => en.value ? legal.value?.jurisdiction_en || legal.value?.jurisdiction : legal.value?.jurisdiction)
async function loadLegal() {
  loading.value = true
  error.value = ''
  try { legal.value = (await http.get('/api/public/legal')).data }
  catch { legal.value = null; error.value = en.value ? 'Check your connection or contact support.' : '请检查网络连接后重试，或联系平台客服。' }
  finally { loading.value = false }
}
watch(() => route.path, loadLegal, { immediate: true })
</script>
<style scoped>
.legal-page{min-height:100vh;padding:38px 20px;background:#f5f8fc;color:#18314f}.legal-page article{max-width:880px;margin:18px auto;padding:42px;border:1px solid #dce8f7;border-radius:18px;background:#fff;box-shadow:0 18px 50px rgba(29,70,115,.08)}.back{display:block;max-width:880px;margin:auto;color:#2563eb;text-decoration:none}.eyebrow{color:#2563eb;font-size:12px;font-weight:800;letter-spacing:.15em}h1{font-size:36px}.meta{color:#64748b}.legal-copy{white-space:pre-wrap;line-height:1.9;margin-top:26px}.legal-copy p{margin:16px 0}h2{margin-top:28px}dl{display:grid;grid-template-columns:140px 1fr;gap:10px}dt{font-weight:700}dd{margin:0;overflow-wrap:anywhere}a{color:#2563eb}.legal-links{display:flex;flex-wrap:wrap;gap:12px;margin-top:32px;border-top:1px solid #e2e8f0;padding-top:20px}@media(max-width:640px){.legal-page article{padding:24px}h1{font-size:28px}dl{grid-template-columns:1fr}}
</style>
