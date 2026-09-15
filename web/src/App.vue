<template>
  <router-view />
  <AuthDialog v-if="showAuthDialog" />
  <AgreementGate v-if="showAgreementGate" />
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const AuthDialog = defineAsyncComponent(() => import('@/components/AuthDialog.vue'))
const AgreementGate = defineAsyncComponent(() => import('@/components/AgreementGate.vue'))
const showAuthDialog = computed(() => route.query.auth === 'login' || route.query.auth === 'register' || route.query.auth === 'reset')
const showAgreementGate = computed(() => route.meta.role === 'user')
</script>
