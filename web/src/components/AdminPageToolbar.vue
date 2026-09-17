<template>
  <section class="console-toolbar admin-page-toolbar">
    <div class="admin-page-toolbar__heading">
      <h1>{{ title }}</h1>
      <p>{{ description }}</p>
    </div>
    <div v-if="$slots.filters || $slots.actions" class="admin-page-toolbar__controls">
      <div v-if="$slots.filters" class="admin-page-toolbar__filters">
        <slot name="filters" />
      </div>
      <div v-if="$slots.actions" class="admin-page-toolbar__actions">
        <slot name="actions" />
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  description: string
}>()
</script>

<style scoped>
.admin-page-toolbar {
  display: grid;
  gap: 18px;
  min-width: 0;
  padding: 20px;
  border: 1px solid var(--admin-line, var(--el-border-color));
  border-radius: 14px;
  background: var(--admin-surface, var(--el-bg-color));
}

.admin-page-toolbar__heading,
.admin-page-toolbar__controls,
.admin-page-toolbar__filters,
.admin-page-toolbar__actions {
  min-width: 0;
}

.admin-page-toolbar__heading h1 {
  margin: 0;
  color: var(--admin-text, var(--el-text-color-primary));
  font-size: 24px;
  line-height: 1.25;
}

.admin-page-toolbar__heading p {
  margin: 8px 0 0;
  color: var(--admin-muted, var(--el-text-color-secondary));
  line-height: 1.6;
}

.admin-page-toolbar__controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-line, var(--el-border-color));
}

.admin-page-toolbar__filters,
.admin-page-toolbar__actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.admin-page-toolbar__filters {
  flex: 1 1 auto;
}

.admin-page-toolbar__actions {
  flex: 0 1 auto;
  justify-content: flex-end;
}

@media (max-width: 980px) {
  .admin-page-toolbar__controls {
    align-items: stretch;
    flex-direction: column;
  }

  .admin-page-toolbar__actions {
    justify-content: flex-start;
  }
}

@media (max-width: 700px) {
  .admin-page-toolbar {
    gap: 14px;
    padding: 16px;
  }

  .admin-page-toolbar__filters,
  .admin-page-toolbar__actions {
    align-items: stretch;
    flex-direction: column;
  }

  .admin-page-toolbar__filters > :deep(*),
  .admin-page-toolbar__actions > :deep(*) {
    width: 100% !important;
    margin-left: 0 !important;
  }
}
</style>
