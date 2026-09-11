import { describe, expect, it, vi } from 'vitest'

import {
  AGENT_EVENT_NAMES,
  buildSseUrl,
  openAgentStream,
  parseFrame,
  toWebSocketUrl,
  type EventSourceLike,
  type TransportFactories,
  type WebSocketLike,
} from '../agentStreamTransport'

/** 可控的假 WebSocket。 */
class FakeWebSocket implements WebSocketLike {
  readyState = 0
  sent: string[] = []
  closed = false
  onopen: ((ev: unknown) => void) | null = null
  onmessage: ((ev: { data: unknown }) => void) | null = null
  onerror: ((ev: unknown) => void) | null = null
  onclose: ((ev: unknown) => void) | null = null

  constructor(public url: string) {}

  send(data: string) {
    this.sent.push(data)
  }
  close() {
    this.closed = true
  }
  open() {
    this.readyState = 1
    this.onopen?.({})
  }
  message(payload: unknown) {
    this.onmessage?.({ data: typeof payload === 'string' ? payload : JSON.stringify(payload) })
  }
  fail() {
    this.onerror?.({})
  }
  drop() {
    this.onclose?.({})
  }
}

/** 可控的假 EventSource。 */
class FakeEventSource implements EventSourceLike {
  listeners = new Map<string, Array<(ev: { data?: string }) => void>>()
  closed = false
  onerror: ((ev: unknown) => void) | null = null

  constructor(public url: string) {}

  addEventListener(type: string, listener: (ev: { data?: string }) => void) {
    const list = this.listeners.get(type) ?? []
    list.push(listener)
    this.listeners.set(type, list)
  }
  close() {
    this.closed = true
  }
  emit(type: string, data: unknown) {
    const raw = typeof data === 'string' ? data : JSON.stringify(data)
    for (const fn of this.listeners.get(type) ?? []) fn({ data: raw })
  }
}

function harness() {
  const sockets: FakeWebSocket[] = []
  const sources: FakeEventSource[] = []
  const factories: TransportFactories = {
    createWebSocket: (url) => {
      const ws = new FakeWebSocket(url)
      sockets.push(ws)
      return ws
    },
    createEventSource: (url) => {
      const es = new FakeEventSource(url)
      sources.push(es)
      return es
    },
  }
  return { factories, sockets, sources }
}

function collect() {
  const events: Array<{ name: string; payload: Record<string, unknown> }> = []
  const transports: string[] = []
  const errors: unknown[] = []
  return {
    events,
    transports,
    errors,
    handlers: {
      onEvent: (name: string, payload: Record<string, unknown>) => events.push({ name, payload }),
      onTransport: (kind: string) => transports.push(kind),
      onError: (error: unknown) => errors.push(error),
    },
  }
}

// ─────────────────────────── 地址构造 ───────────────────────────

describe('toWebSocketUrl', () => {
  it('http 页面转 ws，https 页面转 wss', () => {
    expect(toWebSocketUrl('/api', '/ws/agent', 'http://localhost:5173')).toBe(
      'ws://localhost:5173/api/ws/agent',
    )
    expect(toWebSocketUrl('/api', '/ws/agent', 'https://example.com')).toBe(
      'wss://example.com/api/ws/agent',
    )
  })

  it('去掉基址末尾的斜杠，避免出现双斜杠', () => {
    expect(toWebSocketUrl('/api/', '/ws/agent', 'http://localhost:5173')).toBe(
      'ws://localhost:5173/api/ws/agent',
    )
  })
})

describe('buildSseUrl', () => {
  it('中文消息走 URL 编码', () => {
    const url = buildSseUrl('/api', { appId: 1, message: '做个登录页' })
    expect(url.startsWith('/api/chat/generate/stream?')).toBe(true)
    expect(url).toContain('appId=1')
    expect(decodeURIComponent(url)).toContain('message=做个登录页')
    expect(url).not.toContain('做个登录页')
  })
})

describe('parseFrame', () => {
  it('取出事件名与载荷', () => {
    const frame = parseFrame('{"event":"text.delta","content":"hi","seq":2}')
    expect(frame?.name).toBe('text.delta')
    expect(frame?.payload.content).toBe('hi')
    expect(frame?.payload.seq).toBe(2)
  })

  it('坏帧返回 null 而不是抛异常', () => {
    expect(parseFrame('not json')).toBeNull()
    expect(parseFrame('')).toBeNull()
    expect(parseFrame(undefined)).toBeNull()
    expect(parseFrame('[1,2]')).toBeNull()
  })

  it('没有 event 字段的帧被丢弃', () => {
    expect(parseFrame('{"runId":"x"}')).toBeNull()
  })
})

// ─────────────────────────── WebSocket 正常路径 ───────────────────────────

describe('openAgentStream · WebSocket 优先', () => {
  it('连上后发 start 指令，并按事件名分发', () => {
    const { factories, sockets } = harness()
    const c = collect()

    openAgentStream('/api', { appId: 7, message: '做个页面' }, c.handlers, factories)

    expect(sockets).toHaveLength(1)
    expect(sockets[0].url).toContain('/api/ws/agent')

    sockets[0].open()
    const startAction = JSON.parse(sockets[0].sent[0])
    expect(startAction).toMatchObject({ action: 'start', appId: '7', message: '做个页面' })

    sockets[0].message({ event: 'text.delta', content: '你' })
    sockets[0].message({ event: 'run.completed', status: 'succeeded' })

    expect(c.transports).toEqual(['websocket'])
    expect(c.events.map((e) => e.name)).toEqual(['text.delta', 'run.completed'])
    expect(c.errors).toHaveLength(0)
  })

  it('心跳事件照常透传，交给上层忽略', () => {
    const { factories, sockets } = harness()
    const c = collect()
    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].open()
    sockets[0].message({ event: 'heartbeat', ts: 1 })

    expect(c.events[0].name).toBe('heartbeat')
  })

  it('坏帧被跳过，不影响后续帧', () => {
    const { factories, sockets } = harness()
    const c = collect()
    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].open()

    sockets[0].message('这不是 json')
    sockets[0].message({ event: 'text.delta', content: 'ok' })

    expect(c.events.map((e) => e.name)).toEqual(['text.delta'])
  })
})

// ─────────────────────────── 降级 ───────────────────────────

describe('openAgentStream · 降级到 SSE', () => {
  it('超时未 open 就降级 —— 不能一直挂着重试', () => {
    vi.useFakeTimers()
    try {
      const { factories, sockets, sources } = harness()
      const c = collect()

      openAgentStream('/api', { appId: 1, message: 'x', wsTimeoutMs: 3000 }, c.handlers, factories)
      expect(sockets).toHaveLength(1)
      expect(sources).toHaveLength(0)

      vi.advanceTimersByTime(3000)

      expect(sources).toHaveLength(1)
      expect(c.transports).toEqual(['sse'])
      expect(sockets[0].closed).toBe(true)
    } finally {
      vi.useRealTimers()
    }
  })

  it('open 之前报错就降级，不算失败', () => {
    const { factories, sockets, sources } = harness()
    const c = collect()

    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].fail()

    expect(sources).toHaveLength(1)
    expect(c.transports).toEqual(['sse'])
    expect(c.errors).toHaveLength(0)
  })

  it('open 之前就关闭也降级', () => {
    const { factories, sockets, sources } = harness()
    const c = collect()

    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].drop()

    expect(sources).toHaveLength(1)
  })

  it('WebSocket 构造函数抛异常时直接走 SSE', () => {
    const { sources } = harness()
    const c = collect()
    const throwing: TransportFactories = {
      createWebSocket: () => {
        throw new Error('WebSocket 不可用')
      },
      createEventSource: (url) => {
        const es = new FakeEventSource(url)
        sources.push(es)
        return es
      },
    }

    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, throwing)

    expect(sources).toHaveLength(1)
    expect(c.transports).toEqual(['sse'])
  })

  it('连上之后断开**不**自动降级重连 —— 重连会把这一轮重跑一遍', () => {
    const { factories, sockets, sources } = harness()
    const c = collect()

    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].open()
    sockets[0].drop()

    expect(sources).toHaveLength(0)
    expect(c.errors).toHaveLength(1)
  })

  it('SSE 降级路径注册了全部具名事件', () => {
    const { factories, sockets, sources } = harness()
    const c = collect()

    openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].fail()

    // 具名事件不会触发 onmessage，漏注册就等于静默收不到
    for (const name of AGENT_EVENT_NAMES) {
      expect(sources[0].listeners.has(name)).toBe(true)
    }
  })

  it('SSE 收到各事件后按同一套名字分发', () => {
    const { factories, sockets, sources } = harness()
    const c = collect()

    openAgentStream('/api', { appId: 1, message: '做一个页面' }, c.handlers, factories)
    sockets[0].fail()

    sources[0].emit('text.delta', { event: 'text.delta', content: '你' })
    sources[0].emit('done', {})

    expect(c.events.map((e) => e.name)).toEqual(['text.delta', 'done'])
  })
})

// ─────────────────────────── 关闭 ───────────────────────────

describe('openAgentStream · 关闭', () => {
  it('close 会同时关掉 WebSocket 与降级定时器', () => {
    vi.useFakeTimers()
    try {
      const { factories, sockets, sources } = harness()
      const c = collect()

      const stream = openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
      stream.close()
      vi.advanceTimersByTime(5000)

      expect(sockets[0].closed).toBe(true)
      expect(sources).toHaveLength(0)
    } finally {
      vi.useRealTimers()
    }
  })

  it('close 之后关掉 SSE 且不再分发事件', () => {
    const { factories, sockets, sources } = harness()
    const c = collect()

    const stream = openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    sockets[0].fail()
    stream.close()
    sources[0].emit('text.delta', { event: 'text.delta', content: '迟到' })

    expect(sources[0].closed).toBe(true)
    expect(c.events).toHaveLength(0)
  })

  it('transport() 在确定之前返回 null', () => {
    const { factories } = harness()
    const c = collect()
    const stream = openAgentStream('/api', { appId: 1, message: 'x' }, c.handlers, factories)
    expect(stream.transport()).toBeNull()
  })
})
