<template>
  <button class="locale-switch" type="button" :title="t('switchLanguage')" :aria-label="t('switchLanguage')" data-no-auto-i18n @click="toggleLocale">
    <span :class="{ active: !isEnglish }">ZH</span>
    <i aria-hidden="true"></i>
    <span :class="{ active: isEnglish }">EN</span>
  </button>
</template>

<script setup lang="ts">
import { isEnglish, t, toggleLocale } from '@/i18n/locale'
</script>

<style scoped>
.locale-switch {
  position: fixed;
  z-index: 2600;
  bottom: 20px;
  right: 18px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 34px;
  padding: 0 10px;
  border: 1px solid rgba(112, 145, 202, .4);
  border-radius: 999px;
  background: rgba(8, 17, 38, .82);
  color: #91a2bd;
  box-shadow: 0 8px 24px rgba(3, 10, 28, .2);
  backdrop-filter: blur(14px);
  cursor: pointer;
  font: 600 12px/1 system-ui, sans-serif;
}
.locale-switch i { width: 1px; height: 13px; background: currentColor; opacity: .35; }
.locale-switch span.active { color: #fff; }
.locale-switch:hover, .locale-switch:focus-visible { border-color: #58a6ff; outline: none; }
@media (max-width: 760px) { .locale-switch { right: 14px; bottom: 14px; } }
</style>
