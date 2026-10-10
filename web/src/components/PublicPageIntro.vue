<template>
  <section v-if="copy" class="site-section public-page-intro">
    <h1>{{ copy.heading }}</h1>
    <p>{{ copy.summary }}</p>
    <p>{{ copy.detail }}</p>
  </section>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { isEnglish } from '@/i18n/locale'
import { getPublicPage } from '@/seo/pages.mjs'
const props = defineProps<{ pagePath: string }>()
const copy = computed(() => {
  const page = getPublicPage(props.pagePath)
  return page && (isEnglish.value ? page.en : page.zh)
})
</script>
<style scoped>
.public-page-intro h1{font-size:clamp(26px,4vw,38px);line-height:1.3;margin:0 0 16px}.public-page-intro p{color:var(--tech-muted);line-height:1.8;max-width:900px}
</style>
