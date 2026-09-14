/**
 * Agent 流式传输：**默认 WebSocket，失败或超时降级到 SSE**。
 *
 * 为什么默认 WebSocket：新 Harness 有「流进行中往服务端发数据」的需求
 * （取消、以及将来的计划审批）。纯单向的 token 流 SSE 就够，一旦要双向，
 * SSE 就得靠额外的 REST 端点绕。
 *
 * 为什么保留 SSE：WebSocket 要经过 Upgrade 握手，中间任一跳不支持就废
 * （企业防火墙、只注册 POST 的网关、部分负载均衡器）。SSE 是普通 HTTP，
 * 穿透性更好，浏览器 EventSource 还自带重连。
 *
 * 两条路共用同一套事件名与载荷形状，所以上层只认事件、不关心走的是哪条。
 */

export type AgentTransportKind = 'websocket' | 'sse'

/** WebSocket 的最小接口。抽出来是为了单测能塞假实现。 */
export interface WebSocketLike {
  readyState: number
  send(data: string): void
  close(): void
  onopen: ((ev: unknown) => void) | null
  onmessage: ((ev: { data: unknown }) => void) | null
  onerror: ((ev: unknown) => void) | null
  onclose: ((ev: unknown) => void) | null
}

/** EventSource 的最小接口。 */
export interface EventSourceLike {
  close(): void
  addEventListener(type: string, listener: (ev: { data?: string }) => void): void
  onerror: ((ev: unknown) => void) | null
}

export interface TransportFactories {
  createWebSocket: (url: string) => WebSocketLike
  createEventSource: (url: string) => EventSourceLike
}

export interface AgentStreamOptions {
  appId: string | number
  message: string
  /** WebSocket 多久没 open 就降级。默认 3 秒。 */
  wsTimeoutMs?: number
}

export interface AgentStreamHandlers {
  /** 收到一帧。payload 已解析成对象（解析失败时是空对象）。 */
  onEvent: (name: string, payload: Record<string, unknown>) => void
  /** 确定走哪条传输时回调一次。用于埋点与排障。 */
  onTransport?: (kind: AgentTransportKind) => void
  onError?: (error: unknown) => void
}

export interface AgentStream {
  /** 主动关闭。会同时停掉当前传输并取消降级定时器。 */
  close(): void
  /** 当前实际使用的传输；还没确定时为 null。 */
  transport(): AgentTransportKind | null
}

/**
 * 服务端会推的事件名。
 *
 * SSE 的具名事件不会触发 `onmessage`，必须逐个 `addEventListener`，
 * 所以这份清单要和后端 `AgentEvent` 里的常量保持一致。
 */
export const AGENT_EVENT_NAMES = [
  'run.started',
  'status',
  'heartbeat',
  'text.delta',
  'text.message',
  'thinking.started',
  'thinking.delta',
  'thinking.completed',
  'todo.delta',
  'step',
  'tool.started',
  'tool.finished',
  'artifact.changed',
  'artifact.manifest',
  'error',
  'run.completed',
  'business-error',
  'done',
] as const

/** 把 `/api` 这类基址转成 WebSocket 基址（同源、协议跟着页面走）。 */
export function toWebSocketUrl(apiBase: string, path: string, origin?: string): string {
  const base = origin ?? (typeof window !== 'undefined' ? window.location.origin : '')
  const proto = base.startsWith('https:') ? 'wss:' : 'ws:'
  const host = base.replace(/^https?:\/\//, '')
  return `${proto}//${host}${apiBase.replace(/\/$/, '')}${path}`
}

/** 拼 SSE 地址。message 走 URL 编码，中文不能裸传。 */
export function buildSseUrl(apiBase: string, options: AgentStreamOptions): string {
  const query = new URLSearchParams({
    appId: String(options.appId),
    message: options.message,
  })
  return `${apiBase.replace(/\/$/, '')}/chat/generate/stream?${query.toString()}`
}

/** 解析一帧。解析不了就返回空载荷 —— 坏帧不该中断整条流。 */
export function parseFrame(raw: unknown): { name: string; payload: Record<string, unknown> } | null {
  if (typeof raw !== 'string' || !raw.trim()) return null
  try {
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return null
    const name = typeof parsed.event === 'string' ? parsed.event : ''
    if (!name) return null
    return { name, payload: parsed as Record<string, unknown> }
  } catch {
    return null
  }
}

const defaultFactories: TransportFactories = {
  createWebSocket: (url) => new WebSocket(url) as unknown as WebSocketLike,
  createEventSource: (url) => new EventSource(url, { withCredentials: true }) as unknown as EventSourceLike,
}

export function openAgentStream(
  apiBase: string,
  options: AgentStreamOptions,
  handlers: AgentStreamHandlers,
  factories: TransportFactories = defaultFactories,
): AgentStream {
  let transport: AgentTransportKind | null = null
  let closed = false
  let ws: WebSocketLike | null = null
  let es: EventSourceLike | null = null
  let fallbackTimer: ReturnType<typeof setTimeout> | null = null

  const cleanup = () => {
    if (fallbackTimer !== null) {
      clearTimeout(fallbackTimer)
      fallbackTimer = null
    }
  }

  const emit = (name: string, payload: Record<string, unknown>) => {
    if (closed) return
    handlers.onEvent(name, payload)
  }

  const startSse = () => {
    if (closed || transport === 'sse') return
    transport = 'sse'
    handlers.onTransport?.('sse')

    es = factories.createEventSource(buildSseUrl(apiBase, options))
    // 具名事件必须逐个注册；漏一个那个事件就静默收不到
    for (const name of AGENT_EVENT_NAMES) {
      es.addEventListener(name, (ev: { data?: string }) => {
        const frame = parseFrame(ev?.data)
        emit(frame ? frame.name : name, frame ? frame.payload : {})
      })
    }
    es.onerror = (error) => {
      if (closed) return
      handlers.onError?.(error)
    }
  }

  const startWebSocket = () => {
    const url = toWebSocketUrl(apiBase, '/ws/agent')
    try {
      ws = factories.createWebSocket(url)
    } catch (error) {
      // 构造就失败（比如环境里没有 WebSocket）—— 直接降级
      handlers.onError?.(error)
      startSse()
      return
    }

    // 超时降级。这条不能省：某些网络下 WebSocket 不会立刻报错，
    // 而是一直挂着重试，用户看到的是「卡住」。
    fallbackTimer = setTimeout(() => {
      if (closed || transport) return
      try {
        ws?.close()
      } catch {
        // 关不掉也无所谓，后面会走 SSE
      }
      ws = null
      startSse()
    }, options.wsTimeoutMs ?? 3000)

    ws.onopen = () => {
      if (closed) return
      cleanup()
      transport = 'websocket'
      handlers.onTransport?.('websocket')
      ws?.send(
        JSON.stringify({
          action: 'start',
          appId: String(options.appId),
          message: options.message,
        }),
      )
    }

    ws.onmessage = (ev) => {
      const frame = parseFrame(ev?.data)
      if (frame) emit(frame.name, frame.payload)
    }

    ws.onerror = (error) => {
      if (closed) return
      if (!transport) {
        // 还没 open 就出错 —— 降级，不算失败
        cleanup()
        startSse()
        return
      }
      handlers.onError?.(error)
    }

    ws.onclose = () => {
      if (closed) return
      if (!transport) {
        // 没连上就关了 —— 降级
        cleanup()
        startSse()
        return
      }
      // 连上过又断了。**不自动降级重连**：重连会把这一轮重新跑一遍，
      // 产生重复副作用（重复写文件、重复烧 token）。
      handlers.onError?.(new Error('WebSocket 连接已断开'))
    }
  }

  startWebSocket()

  return {
    transport: () => transport,
    close: () => {
      closed = true
      cleanup()
      try {
        ws?.close()
      } catch {
        // ignore
      }
      try {
        es?.close()
      } catch {
        // ignore
      }
    },
  }
}
