<template>
  <router-view />
  <AuthDialog />
  <AgreementGate />
</template>

<script setup lang="ts">
import AuthDialog from '@/components/AuthDialog.vue'
import AgreementGate from '@/components/AgreementGate.vue'
</script>
