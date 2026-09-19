/**
 * 应用运行时接口客户端（启动 / 停止 / 心跳 / 状态 / 日志 / 指标）。
 *
 * 与 appWorkspace.ts 同样的手写 fetch 风格：这些接口不在 openapi 生成范围内，
 * 且需要绕开生成客户端的严格参数类型。
 */

/** 受管进程描述 */
export interface RuntimeProcessInfo {
  name: string
  command?: string
  port?: number | null
}

/** 运行状态（后端 RuntimeStatusVO） */
export interface RuntimeStatus {
  appId?: number
  runNo?: number | null
  status?: string
  running?: boolean
  gatewayPort?: number | null
  kind?: string | null
  processes?: RuntimeProcessInfo[]
  sourceVersion?: string | null
  currentVersion?: string | null
  /** 运行版本落后：改了代码但没重新运行 */
  stale?: boolean
  startedAt?: string | null
  stoppedAt?: string | null
  exitCode?: number | null
  errorMessage?: string | null
  /**
   * 这个项目的形态能不能跑。null 表示「还没有运行记录，无从判断」——
   * 和 false（明确认不出来）是两回事，别把两者当成一个。
   */
  supported?: boolean | null
}

export interface MetricPoint {
  at: string
  cpuPercent: number | null
  memoryBytes: number | null
  memoryLimitBytes: number | null
}

export interface RuntimeMetrics {
  points: MetricPoint[]
  windowMinutes: number
  /** 每秒请求数（经平台网关统计）；无流量为 null */
  qps?: number | null
  /** 5xx 占比；无流量为 null */
  errorRatePercent?: number | null
  sampleRequests?: number
}

export interface RuntimeLogs {
  runNo: number
  content: string
  truncated: boolean
  totalBytes: number
}

interface BaseOpts {
  appId: string
  apiBaseUrl?: string
}

function baseUrlOf(apiBaseUrl?: string): string {
  return (apiBaseUrl ?? '/api').replace(/\/$/, '')
}

async function readEnvelope<T>(res: Response, action: string): Promise<T> {
  if (!res.ok) {
    throw new Error(`${action}失败 (HTTP ${res.status})`)
  }
  const body: { code: number; data?: T; message?: string } = await res.json()
  if (body.code !== 0 && body.code !== 20000) {
    throw new Error(body.message || `${action}失败`)
  }
  return body.data as T
}

/** 查询运行状态 */
export async function loadRuntimeStatus(opts: BaseOpts): Promise<RuntimeStatus> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/status`
  const res = await fetch(url, { method: 'GET', credentials: 'include' })
  return readEnvelope<RuntimeStatus>(res, '查询运行状态')
}

/** 启动运行。已在运行时后端直接返回当前状态，不会重复创建进程。 */
export async function startRuntime(opts: BaseOpts): Promise<RuntimeStatus> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/start`
  const res = await fetch(url, { method: 'POST', credentials: 'include' })
  return readEnvelope<RuntimeStatus>(res, '启动应用')
}

/** 停止运行 */
export async function stopRuntime(opts: BaseOpts): Promise<boolean> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/stop`
  const res = await fetch(url, { method: 'POST', credentials: 'include' })
  return readEnvelope<boolean>(res, '停止应用')
}

/**
 * 发送运行心跳。
 *
 * 服务端据此判断「还有页面在看」，最后一个页面离开后保留一段时间再自动停止。
 * 失败不抛错：心跳丢一次不该打断用户操作。
 */
export async function sendRuntimeHeartbeat(opts: BaseOpts): Promise<void> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/heartbeat`
  try {
    await fetch(url, { method: 'POST', credentials: 'include' })
  } catch {
    // 忽略：下一次心跳会补上
  }
}

/** 读取运行日志；runNo 省略表示最近一次运行 */
export async function loadRuntimeLogs(
  opts: BaseOpts & { runNo?: number; maxBytes?: number },
): Promise<RuntimeLogs> {
  const params = new URLSearchParams()
  if (opts.runNo != null) {
    params.set('runNo', String(opts.runNo))
  }
  if (opts.maxBytes != null) {
    params.set('maxBytes', String(opts.maxBytes))
  }
  const query = params.toString()
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/logs${query ? `?${query}` : ''}`
  const res = await fetch(url, { method: 'GET', credentials: 'include' })
  return readEnvelope<RuntimeLogs>(res, '读取运行日志')
}

/** 读取运行指标（CPU / 内存时间序列） */
export async function loadRuntimeMetrics(opts: BaseOpts): Promise<RuntimeMetrics> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/metrics`
  const res = await fetch(url, { method: 'GET', credentials: 'include' })
  return readEnvelope<RuntimeMetrics>(res, '读取运行指标')
}

/** 运行异常事件（后端 AppRuntimeIncident） */
export interface RuntimeIncident {
  id: number
  appId?: number
  runNo?: number
  kind?: 'STARTUP_FAILURE' | 'RUNTIME_FAILURE'
  status?: 'OPEN' | 'DISMISSED' | 'REPAIRING' | 'REPAIRED'
  summary?: string
  detail?: string | null
  exitCode?: number | null
  detectedAt?: string | null
}

/** 读取待处理的运行异常 */
export async function loadOpenIncidents(opts: BaseOpts): Promise<RuntimeIncident[]> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/incidents?onlyOpen=true`
  const res = await fetch(url, { method: 'GET', credentials: 'include' })
  const data = await readEnvelope<RuntimeIncident[]>(res, '读取运行异常')
  return data ?? []
}

/** 关闭异常提示；记录仍然保留在服务端 */
export async function dismissIncident(
  opts: BaseOpts & { incidentId: number },
): Promise<boolean> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/incidents/${encodeURIComponent(String(opts.incidentId))}/dismiss`
  const res = await fetch(url, { method: 'POST', credentials: 'include' })
  return readEnvelope<boolean>(res, '关闭提示')
}

/**
 * 同意修复：服务端返回脱敏后的诊断提示词。
 *
 * 拿到的提示词由调用方通过现有生成对话发送 —— 修复因此和用户自己发消息走同一条链，
 * 在原对话里可见，也自然受项目的写锁串行保护。
 */
export async function prepareIncidentRepair(
  opts: BaseOpts & { incidentId: number },
): Promise<string> {
  const url = `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/incidents/${encodeURIComponent(String(opts.incidentId))}/repair`
  const res = await fetch(url, { method: 'POST', credentials: 'include' })
  const data = await readEnvelope<{ prompt?: string }>(res, '发起修复')
  return data?.prompt ?? ''
}

/** 经鉴权网关访问应用的地址（平台内部与 API 调用用） */
export function buildGatewayUrl(opts: BaseOpts & { path?: string }): string {
  const suffix = (opts.path ?? '/').replace(/^\//, '')
  return `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/runtime/gateway/${suffix}`
}

/**
 * 预览地址。
 *
 * 走容器直连的宿主端口而不是网关：`127.0.0.1:{port}` 与平台页面（localhost）
 * 是不同来源，生成的应用因此拿不到平台会话凭据 —— 这是 Plan 明确要求的隔离。
 * 端口号只通过鉴权后的状态接口下发，客户端不能自己指定目标。
 */
export function buildRuntimePreviewUrl(gatewayPort: number): string {
  return `http://127.0.0.1:${gatewayPort}/`
}
