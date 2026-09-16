<template>
  <router-view />
  <AuthDialog v-if="showAuthDialog" />
  <AgreementGate v-if="showAgreementGate" />
  <ContactWidget v-if="showContactWidget" />
  <LocaleSwitch />
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'
import LocaleSwitch from '@/components/LocaleSwitch.vue'

const route = useRoute()
const AuthDialog = defineAsyncComponent(() => import('@/components/AuthDialog.vue'))
const AgreementGate = defineAsyncComponent(() => import('@/components/AgreementGate.vue'))
const ContactWidget = defineAsyncComponent(() => import('@/components/ContactWidget.vue'))
const showAuthDialog = computed(() => route.query.auth === 'login' || route.query.auth === 'register' || route.query.auth === 'reset')
const showAgreementGate = computed(() => route.meta.role === 'user')
const showContactWidget = computed(() => !route.path.startsWith('/admin'))
</script>
