<template>
  <aside v-if="total" ref="widget" class="contact-widget" :class="{ open, dragging }" :style="positionStyle" aria-label="联系方式">
    <button class="contact-trigger" type="button" :aria-expanded="open" :title="copy.drag" @pointerdown="startDrag" @click="toggle">
      <el-icon><ChatDotRound /></el-icon>
      <span>{{ copy.title }}</span>
      <b>{{ total }}</b>
    </button>
    <div v-show="open" class="contact-card">
      <header :title="copy.drag" @pointerdown="startDrag">
        <div><strong>{{ copy.title }}</strong><small>{{ copy.subtitle }}</small></div>
        <button type="button" :aria-label="copy.close" @pointerdown.stop @click="close">×</button>
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
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
const open = ref(false)
const widget = ref<HTMLElement | null>(null)
const dragging = ref(false)
const position = ref({ right: 18, bottom: 64 })
const positionStyle = computed(() => ({ right: `${position.value.right}px`, bottom: `${position.value.bottom}px` }))
const storageKey = 'contact-widget-position-v1'
let dragStart: { pointerId: number; x: number; y: number; right: number; bottom: number; trigger: boolean } | null = null
let suppressClick = false
let suppressTimer: number | undefined

const copy = computed(() => isEnglish.value
  ? { title: 'Contact us', subtitle: 'We are here when you need help', copy: 'Copy', copied: 'Copied', close: 'Close', drag: 'Drag to move' }
  : { title: '联系我们', subtitle: '有问题，随时找我们', copy: '点击复制', copied: '已复制', close: '关闭', drag: '拖动以调整位置' })

async function load() {
  try {
    const { data } = await http.get('/api/public/contact-methods', {
      params: { page: paging.page, size: paging.size, all: paging.all },
    })
    items.value = data.items || []
    total.value = Number(data.total || 0)
    paging.page = Number(data.page || 1)
    await nextTick()
    clampPosition()
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

function clampPosition() {
  if (!widget.value) return
  const margin = 8
  position.value = {
    right: Math.max(margin, Math.min(position.value.right, window.innerWidth - widget.value.offsetWidth - margin)),
    bottom: Math.max(margin, Math.min(position.value.bottom, window.innerHeight - widget.value.offsetHeight - margin)),
  }
}

async function toggle() {
  if (suppressClick) {
    suppressClick = false
    return
  }
  open.value = !open.value
  await nextTick()
  clampPosition()
}

async function close() {
  open.value = false
  await nextTick()
  clampPosition()
}

function startDrag(event: PointerEvent) {
  if (event.button !== 0 || (event.currentTarget instanceof HTMLElement && event.currentTarget.tagName === 'HEADER' && (event.target as HTMLElement).closest('button'))) return
  dragStart = {
    pointerId: event.pointerId, x: event.clientX, y: event.clientY,
    right: position.value.right, bottom: position.value.bottom,
    trigger: (event.currentTarget as HTMLElement).classList.contains('contact-trigger'),
  }
  window.addEventListener('pointermove', moveDrag)
  window.addEventListener('pointerup', endDrag)
  window.addEventListener('pointercancel', endDrag)
}

function moveDrag(event: PointerEvent) {
  if (!dragStart || event.pointerId !== dragStart.pointerId) return
  const dx = event.clientX - dragStart.x
  const dy = event.clientY - dragStart.y
  if (!dragging.value && Math.hypot(dx, dy) < 5) return
  dragging.value = true
  event.preventDefault()
  position.value = { right: dragStart.right - dx, bottom: dragStart.bottom - dy }
  clampPosition()
}

function endDrag(event: PointerEvent) {
  if (!dragStart || event.pointerId !== dragStart.pointerId) return
  if (dragging.value) {
    if (dragStart.trigger) {
      suppressClick = true
      window.clearTimeout(suppressTimer)
      suppressTimer = window.setTimeout(() => { suppressClick = false }, 0)
    }
    try { localStorage.setItem(storageKey, JSON.stringify(position.value)) } catch { /* storage unavailable */ }
  }
  dragStart = null
  dragging.value = false
  window.removeEventListener('pointermove', moveDrag)
  window.removeEventListener('pointerup', endDrag)
  window.removeEventListener('pointercancel', endDrag)
}

onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || 'null')
    if (Number.isFinite(saved?.right) && Number.isFinite(saved?.bottom)) {
      position.value = { right: saved.right, bottom: saved.bottom }
    }
  } catch { /* use the default position */ }
  window.addEventListener('resize', clampPosition)
  void load()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', clampPosition)
  window.removeEventListener('pointermove', moveDrag)
  window.removeEventListener('pointerup', endDrag)
  window.removeEventListener('pointercancel', endDrag)
  window.clearTimeout(suppressTimer)
})
</script>

<style scoped>
.contact-widget { position: fixed; z-index: 1200; display: flex; flex-direction: column-reverse; align-items: flex-end; gap: 10px; font-family: system-ui, sans-serif; }
.contact-trigger { min-height: 48px; display: flex; align-items: center; gap: 9px; padding: 0 16px; border: 1px solid rgba(95, 169, 255, .72); border-radius: 999px; color: #fff; background: linear-gradient(135deg, #1769ff, #00a9cc); box-shadow: 0 14px 34px rgba(23, 105, 255, .34); cursor: grab; touch-action: none; user-select: none; font-weight: 700; }
.contact-trigger b { min-width: 20px; height: 20px; display: grid; place-items: center; border-radius: 10px; background: rgba(255,255,255,.2); font-size: 11px; }
.contact-widget.dragging :is(.contact-trigger, .contact-card header) { cursor: grabbing; }
.contact-card { display: flex; flex-direction: column; max-height: calc(100dvh - 76px); width: min(360px, calc(100vw - 28px)); overflow: hidden; border: 1px solid #c9dbf2; border-radius: 16px; background: rgba(255,255,255,.98); box-shadow: 0 22px 54px rgba(17, 50, 94, .22); backdrop-filter: blur(18px); }
.contact-card header { display: flex; align-items: center; justify-content: space-between; padding: 16px 18px; cursor: grab; touch-action: none; user-select: none; color: #fff; background: linear-gradient(135deg, #1769ff, #00a9cc); }
.contact-card header div { display: grid; gap: 3px; }
.contact-card header strong { font-size: 17px; }
.contact-card header small { color: rgba(255,255,255,.82); }
.contact-card header button { width: 30px; height: 30px; border: 0; border-radius: 50%; color: #fff; background: rgba(255,255,255,.14); cursor: pointer; font-size: 22px; line-height: 1; }
.contact-list { min-height: 0; max-height: 360px; overflow-y: auto; padding: 8px; }
.contact-list > button { width: 100%; display: grid; grid-template-columns: minmax(68px, auto) 1fr auto; align-items: center; gap: 10px; padding: 12px 10px; border: 0; border-bottom: 1px solid #e8eef7; background: transparent; color: #1f334d; cursor: pointer; text-align: left; }
.contact-list > button:last-child { border-bottom: 0; }
.contact-list > button:hover { border-radius: 10px; background: #edf6ff; }
.contact-list span { color: #1769ff; font-weight: 700; }
.contact-list strong { min-width: 0; overflow-wrap: anywhere; }
.contact-list small { color: #7a8ba3; white-space: nowrap; }
.contact-card :deep(.list-pagination) { justify-content: center; padding: 10px 8px 14px; border-top: 1px solid #edf1f7; font-size: 12px; }
.contact-card :deep(.list-pagination .el-select) { width: 116px !important; }
@media (max-width: 640px) {
  .contact-widget:not(.open) .contact-trigger span, .contact-widget:not(.open) .contact-trigger b { display: none; }
  .contact-list > button { grid-template-columns: 64px 1fr; }
  .contact-list small { display: none; }
}
</style>
