<template>
  <main class="legal-page" v-loading="loading">
    <router-link to="/" class="back">← 返回首页</router-link>
    <article v-if="legal">
      <p class="eyebrow">LEGAL</p><h1>{{ kind==='terms'?'用户协议':'隐私政策' }}</h1>
      <p class="meta">版本 {{ version }} · 生效日期 {{ legal.effective_date }}</p>
      <el-alert type="warning" :closable="false" title="当前文本为产品合规基线，上线运营前仍需正式法律顾问复核。" />
      <p v-for="paragraph in paragraphs" :key="paragraph">{{ paragraph }}</p>
      <h2>运营与联系</h2><p>运营主体：{{ legal.operator }}<br>联系邮箱：{{ legal.contact_email }}<br>联系地址：{{ legal.address }}</p>
      <p class="sources">合规参考：<a href="https://www.npc.gov.cn/npc/c2/c30834/202108/t20210820_313088.html" target="_blank" rel="noopener noreferrer">《个人信息保护法》</a> · <a href="https://www.cac.gov.cn/2024-09/30/c_1729384452307680.htm" target="_blank" rel="noopener noreferrer">《网络数据安全管理条例》</a></p>
    </article>
    <el-result v-else-if="error" icon="warning" title="暂时无法加载协议内容" :sub-title="error">
      <template #extra><el-button type="primary" @click="loadLegal">重新加载</el-button></template>
    </el-result>
  </main>
</template>
<script setup lang="ts">
import { computed,onMounted,ref } from 'vue';import { useRoute } from 'vue-router';import http from '@/utils/http'
const route=useRoute(),loading=ref(true),legal=ref<any>(null),error=ref(''),kind=computed(()=>route.meta.legalKind==='privacy'?'privacy':'terms')
const version=computed(()=>kind.value==='terms'?legal.value?.terms_version:legal.value?.privacy_version)
const paragraphs=computed(()=>String(kind.value==='terms'?legal.value?.terms:legal.value?.privacy).split('。').filter(Boolean).map(item=>`${item}。`))
async function loadLegal(){loading.value=true;error.value='';try{legal.value=(await http.get('/api/public/legal')).data}catch{legal.value=null;error.value='请检查网络连接后重试，或联系平台客服。'}finally{loading.value=false}}
onMounted(loadLegal)
</script>
<style scoped>.legal-page{min-height:100vh;padding:38px 20px;background:#f5f8fc;color:#18314f}.legal-page article{max-width:880px;margin:18px auto;padding:42px;border:1px solid #dce8f7;border-radius:18px;background:#fff;box-shadow:0 18px 50px rgba(29,70,115,.08)}.back{display:block;max-width:880px;margin:auto;color:#2563eb;text-decoration:none}.eyebrow{color:#2563eb;font-size:12px;font-weight:800;letter-spacing:.15em}h1{font-size:36px}.meta,.sources{color:#64748b}article>p{line-height:1.9}h2{margin-top:28px}a{color:#2563eb}@media(max-width:640px){.legal-page article{padding:24px}h1{font-size:28px}}</style>
