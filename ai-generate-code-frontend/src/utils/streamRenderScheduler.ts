export interface StreamRenderScheduler {
  enqueue(streamId: string, chunk: string): void
  flush(streamId: string): void
  remove(streamId: string): void
  dispose(): void
}

export interface StreamRenderSchedulerOptions {
  maxBytesPerFrame?: number
  maxWorkMs?: number
  scheduleFrame?: (callback: FrameRequestCallback) => number
  cancelFrame?: (handle: number) => void
  onChunk?: (streamId: string, chunk: string) => void
  onCommit?: (streamId: string, chunk: string) => void
  requestAnimationFrame?: (callback: FrameRequestCallback) => number
  setTimeout?: (callback: () => void, delay: number) => number
  clearTimeout?: (handle: number) => void
  now?: () => number
}

type Queue = { chunks: string[]; bytes: number }

const DEFAULT_MAX_BYTES_PER_FRAME = 32 * 1024
const DEFAULT_MAX_WORK_MS = 6

function byteLength(value: string): number {
  return typeof TextEncoder === 'undefined' ? value.length : new TextEncoder().encode(value).length
}

export function createStreamRenderScheduler(
  options: StreamRenderSchedulerOptions = {},
): StreamRenderScheduler {
  const queues = new Map<string, Queue>()
  const order: string[] = []
  const maxBytesPerFrame = options.maxBytesPerFrame ?? DEFAULT_MAX_BYTES_PER_FRAME
  const maxWorkMs = options.maxWorkMs ?? DEFAULT_MAX_WORK_MS
  const now = options.now ?? (() => (typeof performance !== 'undefined' ? performance.now() : Date.now()))
  const fallbackSetTimeout = options.setTimeout ?? ((callback, delay) => setTimeout(callback, delay) as unknown as number)
  const fallbackClearTimeout = options.clearTimeout ?? ((handle) => clearTimeout(handle))
  const scheduleFrame =
    options.scheduleFrame ??
    options.requestAnimationFrame ??
    ((callback) =>
      typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function'
        ? window.requestAnimationFrame(callback)
        : fallbackSetTimeout(() => callback(now()), 16))
  const cancelFrame =
    options.cancelFrame ??
    ((handle) => {
      if (typeof window !== 'undefined' && typeof window.cancelAnimationFrame === 'function') {
        window.cancelAnimationFrame(handle)
      } else {
        fallbackClearTimeout(handle)
      }
    })
  const onChunk = options.onCommit ?? options.onChunk ?? (() => undefined)
  let frameHandle: number | null = null
  let cursor = 0
  let disposed = false

  const schedule = () => {
    if (disposed || frameHandle != null || queues.size === 0) return
    frameHandle = scheduleFrame(() => {
      frameHandle = null
      const startedAt = now()
      let processedBytes = 0
      let visited = 0
      while (queues.size > 0 && visited < order.length) {
        if (processedBytes >= maxBytesPerFrame) break
        const streamId = order[cursor % order.length]
        if (streamId === undefined) break
        cursor = (cursor + 1) % Math.max(order.length, 1)
        visited += 1
        const queue = queues.get(streamId)
        if (!queue || queue.chunks.length === 0) continue
        const remainingBytes = maxBytesPerFrame - processedBytes
        const parts: string[] = []
        let streamBytes = 0
        while (queue.chunks.length > 0 && streamBytes < remainingBytes) {
          const source = queue.chunks[0] ?? ''
          const sourceBytes = byteLength(source)
          const available = remainingBytes - streamBytes
          const take = sourceBytes <= available ? source.length : takePrefixByBytes(source, available)
          parts.push(source.slice(0, take))
          if (take === source.length) queue.chunks.shift()
          else queue.chunks[0] = source.slice(take)
          streamBytes += byteLength(parts[parts.length - 1] ?? '')
          if (take < source.length) break
        }
        const chunk = parts.join('')
        queue.bytes = Math.max(0, queue.bytes - streamBytes)
        processedBytes += streamBytes
        if (chunk) onChunk(streamId, chunk)
        if (now() - startedAt >= maxWorkMs) break
      }
      for (let i = order.length - 1; i >= 0; i -= 1) {
        const id = order[i]
        if (id === undefined) continue
        const queue = queues.get(id)
        if (queue && queue.chunks.length === 0) {
          queues.delete(id)
          order.splice(i, 1)
        }
      }
      if (queues.size > 0) schedule()
    })
  }

  return {
    enqueue(streamId, chunk) {
      if (disposed || !streamId || !chunk) return
      let queue = queues.get(streamId)
      if (!queue) {
        queue = { chunks: [], bytes: 0 }
        queues.set(streamId, queue)
        order.push(streamId)
      }
      queue.chunks.push(chunk)
      queue.bytes += byteLength(chunk)
      schedule()
    },
    flush(streamId) {
      const queue = queues.get(streamId)
      if (!queue) return
      const chunk = queue.chunks.join('')
      if (chunk) onChunk(streamId, chunk)
      queue.chunks.length = 0
      queues.delete(streamId)
      const index = order.indexOf(streamId)
      if (index >= 0) order.splice(index, 1)
      if (order.length > 0) cursor %= order.length
    },
    remove(streamId) {
      queues.delete(streamId)
      const index = order.indexOf(streamId)
      if (index >= 0) order.splice(index, 1)
      if (order.length > 0) cursor %= order.length
    },
    dispose() {
      if (frameHandle != null) cancelFrame(frameHandle)
      frameHandle = null
      queues.clear()
      order.length = 0
      cursor = 0
      disposed = true
    },
  }
}

function takePrefixByBytes(value: string, maxBytes: number): number {
  if (maxBytes <= 0) return 0
  let bytes = 0
  let index = 0
  for (const char of value) {
    const size = byteLength(char)
    if (bytes + size > maxBytes) break
    bytes += size
    index += char.length
  }
  return Math.max(1, index)
}
