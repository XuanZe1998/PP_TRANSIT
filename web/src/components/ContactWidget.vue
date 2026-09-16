<template>
  <aside v-if="total > 0" class="contact-widget" :class="{ open }" aria-label="联系方式">
    <button class="contact-trigger" type="button" :aria-expanded="open" @click="open = !open">
      <el-icon><ChatDotRound /></el-icon>
      <span>{{ copy.title }}</span>
      <b>{{ total }}</b>
    </button>
    <div v-show="open" class="contact-card">
      <header>
        <div><strong>{{ copy.title }}</strong><small>{{ copy.subtitle }}</small></div>
        <button type="button" :aria-label="copy.close" @click="open = false">×</button>
      </header>
      <div class="contact-list">
        <button v-for="item in items" :key="item.id" type="button" @click="copyNumber(item.number)">
          <span>{{ item.channel }}</span>
          <strong>{{ item.number }}</strong>
          <small>{{ copy.copy }}</small>
        </button>
      </div>
      <ListPagination
        v-model:page="paging.page"
        v-model:size="paging.size"
        v-model:all="paging.all"
        :total="total"
        @change="load"
      />
    </div>
  </aside>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ChatDotRound } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import ListPagination from '@/components/ListPagination.vue'
import http from '@/utils/http'
import { isEnglish } from '@/i18n/locale'
import { useListPage } from '@/utils/listPage'

type ContactMethod = { id: number; channel: string; number: string }

const items = ref<ContactMethod[]>([])
const total = ref(0)
const paging = useListPage('contact-widget-public')
const open = ref(true)
const copy = computed(() => isEnglish.value
  ? { title: 'Contact us', subtitle: 'We are here when you need help', copy: 'Copy', copied: 'Copied', close: 'Close' }
  : { title: '联系我们', subtitle: '有问题，随时找我们', copy: '点击复制', copied: '已复制', close: '关闭' })

async function load() {
  try {
    const { data } = await http.get('/api/public/contact-methods', {
      params: { page: paging.page, size: paging.size, all: paging.all },
    })
    items.value = data.items || []
    total.value = Number(data.total || 0)
    paging.page = Number(data.page || 1)
  } catch {
    if (paging.all) {
      paging.all = false
      return load()
    }
    items.value = []
    total.value = 0
  }
}

async function copyNumber(number: string) {
  try {
    await navigator.clipboard.writeText(number)
    ElMessage.success(copy.value.copied)
  } catch {
    ElMessage.info(number)
  }
}

onMounted(load)
</script>

<style scoped>
.contact-widget { position: fixed; z-index: 1200; right: 18px; bottom: 64px; display: grid; justify-items: end; gap: 10px; font-family: system-ui, sans-serif; }
.contact-trigger { min-height: 48px; display: flex; align-items: center; gap: 9px; padding: 0 16px; border: 1px solid rgba(95, 169, 255, .72); border-radius: 999px; color: #fff; background: linear-gradient(135deg, #1769ff, #00a9cc); box-shadow: 0 14px 34px rgba(23, 105, 255, .34); cursor: pointer; font-weight: 700; }
.contact-trigger b { min-width: 20px; height: 20px; display: grid; place-items: center; border-radius: 10px; background: rgba(255,255,255,.2); font-size: 11px; }
.contact-card { width: min(360px, calc(100vw - 28px)); overflow: hidden; border: 1px solid #c9dbf2; border-radius: 16px; background: rgba(255,255,255,.98); box-shadow: 0 22px 54px rgba(17, 50, 94, .22); backdrop-filter: blur(18px); }
.contact-card header { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px; color: #fff; background: linear-gradient(135deg, #1769ff, #00a9cc); }
.contact-card header div { display: grid; gap: 3px; }
.contact-card header strong { font-size: 17px; }
.contact-card header small { color: rgba(255,255,255,.82); }
.contact-card header button { width: 30px; height: 30px; border: 0; border-radius: 50%; color: #fff; background: rgba(255,255,255,.14); cursor: pointer; font-size: 22px; line-height: 1; }
.contact-list { max-height: 360px; overflow-y: auto; padding: 8px; }
.contact-list > button { width: 100%; display: grid; grid-template-columns: minmax(68px, auto) 1fr auto; align-items: center; gap: 10px; padding: 12px 10px; border: 0; border-bottom: 1px solid #e8eef7; background: transparent; color: #1f334d; cursor: pointer; text-align: left; }
.contact-list > button:last-child { border-bottom: 0; }
.contact-list > button:hover { border-radius: 10px; background: #edf6ff; }
.contact-list span { color: #1769ff; font-weight: 700; }
.contact-list strong { min-width: 0; overflow-wrap: anywhere; }
.contact-list small { color: #7a8ba3; white-space: nowrap; }
.contact-card :deep(.list-pagination) { justify-content: center; padding: 10px 8px 14px; border-top: 1px solid #edf1f7; font-size: 12px; }
.contact-card :deep(.list-pagination .el-select) { width: 116px !important; }
@media (max-width: 640px) {
  .contact-widget { right: 14px; bottom: 58px; }
  .contact-widget:not(.open) .contact-trigger span, .contact-widget:not(.open) .contact-trigger b { display: none; }
  .contact-list > button { grid-template-columns: 64px 1fr; }
  .contact-list small { display: none; }
}
</style>
