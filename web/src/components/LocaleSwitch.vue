<template>
  <button
    ref="switchButton"
    class="locale-switch"
    :class="{ 'is-dragging': dragging }"
    :style="position && { left: `${position.x}px`, top: `${position.y}px`, right: 'auto', bottom: 'auto' }"
    type="button"
    :title="t('switchLanguage')"
    :aria-label="t('switchLanguage')"
    data-no-auto-i18n
    @pointerdown="beginDrag"
    @pointermove="moveDrag"
    @pointerup="endDrag"
    @pointercancel="endDrag"
    @lostpointercapture="endDrag"
    @click="activate"
  >
    <span :class="{ active: !isEnglish }">ZH</span>
    <i aria-hidden="true"></i>
    <span :class="{ active: isEnglish }">EN</span>
  </button>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { isEnglish, t, toggleLocale } from '@/i18n/locale'

const storageKey = 'linknux.locale-switch-position'
const edge = 8
const switchButton = ref<HTMLButtonElement | null>(null)
const position = ref<{ x: number; y: number } | null>(null)
const dragging = ref(false)
let gesture: { id: number; startX: number; startY: number; left: number; top: number } | null = null
let suppressClick = false

function place(x: number, y: number) {
  const button = switchButton.value
  if (!button) return
  position.value = {
    x: Math.max(edge, Math.min(x, window.innerWidth - button.offsetWidth - edge)),
    y: Math.max(edge, Math.min(y, window.innerHeight - button.offsetHeight - edge)),
  }
}

function beginDrag(event: PointerEvent) {
  if (event.button !== 0 || !event.isPrimary || gesture) return
  const button = event.currentTarget as HTMLButtonElement
  const target = event.target as Element
  const bounds = button.getBoundingClientRect()
  gesture = { id: event.pointerId, startX: event.clientX, startY: event.clientY, left: bounds.left, top: bounds.top }
  suppressClick = false
  // Keep the original tap target when the gesture starts on either label.
  target.setPointerCapture(event.pointerId)
}

function moveDrag(event: PointerEvent) {
  if (gesture?.id !== event.pointerId) return
  const dx = event.clientX - gesture.startX
  const dy = event.clientY - gesture.startY
  if (!dragging.value && Math.hypot(dx, dy) < 5) return
  dragging.value = true
  suppressClick = true
  place(gesture.left + dx, gesture.top + dy)
}

function endDrag(event: PointerEvent) {
  if (gesture?.id !== event.pointerId) return
  gesture = null
  if (!dragging.value) return
  dragging.value = false
  try { localStorage.setItem(storageKey, JSON.stringify(position.value)) } catch { /* Storage is optional. */ }
}

function activate(event: MouseEvent) {
  if (!suppressClick || event.detail === 0) toggleLocale()
  suppressClick = false
}

function keepOnScreen() {
  if (position.value) place(position.value.x, position.value.y)
}

onMounted(() => {
  try {
    const saved = JSON.parse(localStorage.getItem(storageKey) || 'null')
    if (Number.isFinite(saved?.x) && Number.isFinite(saved?.y)) place(saved.x, saved.y)
  } catch { /* Storage is optional. */ }
  window.addEventListener('resize', keepOnScreen)
})
onBeforeUnmount(() => window.removeEventListener('resize', keepOnScreen))
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
  cursor: grab;
  touch-action: none;
  user-select: none;
  font: 600 12px/1 system-ui, sans-serif;
}
.locale-switch.is-dragging { cursor: grabbing; }
.locale-switch i { width: 1px; height: 13px; background: currentColor; opacity: .35; }
.locale-switch span.active { color: #fff; }
.locale-switch:hover, .locale-switch:focus-visible { border-color: #58a6ff; outline: none; }
@media (max-width: 760px) { .locale-switch { right: 14px; bottom: 14px; } }
</style>
