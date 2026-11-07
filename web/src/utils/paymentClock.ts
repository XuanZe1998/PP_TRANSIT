import { onBeforeUnmount, onMounted, ref } from 'vue'

/** Keep payment deadlines correct after a background tab or suspended computer resumes. */
export function usePaymentClock() {
  const now = ref(Date.now())
  const sync = () => { now.value = Date.now() }
  let timer: ReturnType<typeof setInterval> | undefined
  onMounted(() => {
    timer = setInterval(sync, 1000)
    window.addEventListener('focus', sync)
    document.addEventListener('visibilitychange', sync)
  })
  onBeforeUnmount(() => {
    clearInterval(timer)
    window.removeEventListener('focus', sync)
    document.removeEventListener('visibilitychange', sync)
  })
  return now
}
