type PollContext = { isCurrent: () => boolean; stop: () => void }

/** One asynchronous request at a time; stopped/replaced requests cannot update their successor. */
export function createPollingTask<T>(options: {
  interval: number
  maxAttempts: number
  run: (target: T, context: PollContext) => Promise<void>
  onExhausted?: () => void
}) {
  let timer: ReturnType<typeof setTimeout> | undefined
  let generation = 0
  let active = false
  let disposed = false
  let target: T

  function stop() {
    generation++
    active = false
    clearTimeout(timer)
    timer = undefined
  }

  function start(next: T) {
    if (disposed || (active && Object.is(next, target))) return
    stop()
    active = true
    target = next
    const version = generation
    let attempts = 0
    const isCurrent = () => !disposed && active && generation === version
    const context = { isCurrent, stop: () => { if (isCurrent()) stop() } }
    const tick = async () => {
      if (!isCurrent()) return
      attempts++
      try { await options.run(next, context) } catch { /* Retry transient polling failures within the bound. */ }
      if (!isCurrent()) return
      if (attempts >= options.maxAttempts) {
        stop()
        options.onExhausted?.()
      } else timer = setTimeout(tick, options.interval)
    }
    timer = setTimeout(tick, options.interval)
  }

  return { start, stop, dispose: () => { disposed = true; stop() } }
}
