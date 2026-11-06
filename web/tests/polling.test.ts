import { afterEach, expect, it, vi } from 'vitest'
import { createPollingTask } from '../src/utils/polling'

afterEach(() => vi.useRealTimers())

it('does not overlap slow requests and ignores duplicate starts', async () => {
  vi.useFakeTimers()
  let finish!: () => void
  const run = vi.fn(() => new Promise<void>(resolve => { finish = resolve }))
  const poll = createPollingTask({ interval: 10, maxAttempts: 3, run })
  poll.start(1)
  poll.start(1)
  await vi.advanceTimersByTimeAsync(100)
  expect(run).toHaveBeenCalledTimes(1)
  finish()
  await vi.advanceTimersByTimeAsync(10)
  expect(run).toHaveBeenCalledTimes(2)
  poll.dispose()
  finish()
  await vi.advanceTimersByTimeAsync(100)
  expect(run).toHaveBeenCalledTimes(2)
})

it('invalidates in-flight results when replaced and cannot restart after disposal', async () => {
  vi.useFakeTimers()
  const accepted: number[] = []
  const completions: (() => void)[] = []
  const poll = createPollingTask<number>({ interval: 10, maxAttempts: 3,
    async run(id, context) {
      await new Promise<void>(resolve => completions.push(resolve))
      if (!context.isCurrent()) return
      accepted.push(id)
      context.stop()
    }
  })
  poll.start(1)
  await vi.advanceTimersByTimeAsync(10)
  poll.start(2)
  await vi.advanceTimersByTimeAsync(10)
  completions[0]()
  completions[1]()
  await vi.advanceTimersByTimeAsync(10)
  expect(accepted).toEqual([2])
  poll.dispose()
  poll.start(3)
  await vi.advanceTimersByTimeAsync(100)
  expect(completions).toHaveLength(2)
})

it('bounds repeated network failures and notifies only once', async () => {
  vi.useFakeTimers()
  const run = vi.fn().mockRejectedValue(new Error('offline'))
  const onExhausted = vi.fn()
  const poll = createPollingTask({ interval: 10, maxAttempts: 3, run, onExhausted })
  poll.start(1)
  await vi.advanceTimersByTimeAsync(100)
  expect(run).toHaveBeenCalledTimes(3)
  expect(onExhausted).toHaveBeenCalledTimes(1)
})
