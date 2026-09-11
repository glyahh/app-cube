import { afterEach, describe, expect, it, vi } from 'vitest'

import { createStreamRenderScheduler } from '../streamRenderScheduler'

interface RafHarness {
  requestAnimationFrame: (callback: FrameRequestCallback) => number
  cancelAnimationFrame: (handle: number) => void
  runFrame: (now?: number) => void
}

function createRafHarness(): RafHarness {
  let nextHandle = 1
  let callback: FrameRequestCallback | undefined

  return {
    requestAnimationFrame(nextCallback) {
      callback = nextCallback
      return nextHandle++
    },
    cancelAnimationFrame() {
      callback = undefined
    },
    runFrame(now = 0) {
      const current = callback
      callback = undefined
      current?.(now)
    },
  }
}

describe('StreamRenderScheduler', () => {
  afterEach(() => vi.useRealTimers())

  it('commits at most once per stream in a frame and keeps streams fair', () => {
    const raf = createRafHarness()
    const commits: Array<[string, string]> = []
    const scheduler = createStreamRenderScheduler({
      onCommit: (streamId, chunk) => commits.push([streamId, chunk]),
      requestAnimationFrame: raf.requestAnimationFrame,
      cancelAnimationFrame: raf.cancelAnimationFrame,
      now: () => 0,
    })

    scheduler.enqueue('a', 'a1')
    scheduler.enqueue('a', 'a2')
    scheduler.enqueue('b', 'b1')
    scheduler.enqueue('b', 'b2')
    raf.runFrame()

    expect(commits).toEqual([
      ['a', 'a1a2'],
      ['b', 'b1b2'],
    ])
  })

  it('limits each frame to 32 KiB and drains the rest on a later frame', () => {
    const raf = createRafHarness()
    const commits: Array<[string, string]> = []
    const input = 'x'.repeat(40 * 1024)
    const scheduler = createStreamRenderScheduler({
      onCommit: (streamId, chunk) => commits.push([streamId, chunk]),
      requestAnimationFrame: raf.requestAnimationFrame,
      cancelAnimationFrame: raf.cancelAnimationFrame,
      now: () => 0,
    })

    scheduler.enqueue('a', input)
    raf.runFrame()
    expect(commits[0]?.[1].length).toBe(32 * 1024)
    expect(commits).toHaveLength(1)

    raf.runFrame()
    expect(commits[1]?.[1].length).toBe(8 * 1024)
    expect(commits).toHaveLength(2)
  })

  it('flushes one stream synchronously and removes its queued data', () => {
    const raf = createRafHarness()
    const commits: Array<[string, string]> = []
    const scheduler = createStreamRenderScheduler({
      onCommit: (streamId, chunk) => commits.push([streamId, chunk]),
      requestAnimationFrame: raf.requestAnimationFrame,
      cancelAnimationFrame: raf.cancelAnimationFrame,
    })

    scheduler.enqueue('a', 'first')
    scheduler.enqueue('a', 'second')
    scheduler.enqueue('b', 'kept')
    scheduler.flush('a')
    scheduler.remove('a')
    raf.runFrame()

    expect(commits).toEqual([
      ['a', 'firstsecond'],
      ['b', 'kept'],
    ])
  })

  it('cancels pending callbacks and ignores work after dispose', () => {
    const raf = createRafHarness()
    const commits: Array<[string, string]> = []
    const scheduler = createStreamRenderScheduler({
      onCommit: (streamId, chunk) => commits.push([streamId, chunk]),
      requestAnimationFrame: raf.requestAnimationFrame,
      cancelAnimationFrame: raf.cancelAnimationFrame,
    })

    scheduler.enqueue('a', 'queued')
    scheduler.dispose()
    raf.runFrame()
    scheduler.enqueue('a', 'late')
    scheduler.flush('a')

    expect(commits).toEqual([])
  })

  it('uses a timer fallback when requestAnimationFrame is unavailable', () => {
    vi.useFakeTimers()
    const commits: Array<[string, string]> = []
    const scheduler = createStreamRenderScheduler({
      onCommit: (streamId, chunk) => commits.push([streamId, chunk]),
      requestAnimationFrame: undefined,
      setTimeout,
      clearTimeout,
    })

    scheduler.enqueue('a', 'timer')
    expect(commits).toEqual([])
    vi.advanceTimersByTime(16)
    expect(commits).toEqual([['a', 'timer']])
  })
})
