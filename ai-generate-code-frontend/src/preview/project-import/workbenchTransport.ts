import request from '@/request'
import type { FileMetadata } from './manifest'
import type { AgentStreamHandlers, AgentStreamOptions } from '../../utils/agentStreamTransport'

interface PreviewProject {
  appId: string
  taskId: string
  name: string
  files: FileMetadata[]
}

let project: PreviewProject | undefined
export const workbenchCalls: Array<{ method: string; path: string }> = []

export function setWorkbenchProject(value?: PreviewProject) {
  project = value ? { ...value, files: value.files.map((file) => ({ ...file })) } : undefined
}

function requireProject(id: string) {
  if (!project || project.appId !== id) throw new Error('工程会话已失效，请重新导入。')
  return project
}

function resolveRequest(raw: string, method: string, params: Record<string, unknown> = {}) {
  // 1. 本页只允许内存工程的明确读取；未知能力不调用真实后端。
  const url = new URL(raw, window.location.origin)
  if (url.origin !== window.location.origin) throw new Error('当前无法访问此服务。')
  for (const [key, value] of Object.entries(params)) url.searchParams.set(key, String(value))
  const path = url.pathname.replace(/^\/api(?=\/)/, '')
  workbenchCalls.push({ method, path })
  if (path === '/app/get/vo' && method === 'GET') {
    const current = requireProject(url.searchParams.get('id') ?? '')
    return {
      id: current.appId,
      appName: current.name,
      userId: 'preview-user',
      codeGenType: 'auto',
      initPrompt: '',
      hasGeneratedCode: true,
      isBeta: 0,
    }
  }
  const history = path.match(/^\/chatHistory\/(app|roundCount)\/([^/]+)$/)
  if (history && method === 'GET') {
    requireProject(history[2]!)
    return history[1] === 'app' ? { records: [], totalRow: 0 } : 0
  }
  if (path === '/app/loop/list/vo') {
    requireProject(url.searchParams.get('appId') ?? '')
    return []
  }
  if (path === '/loop/my/list/page/vo') return { records: [], totalRow: 0 }
  const files = path.match(/^\/app\/static\/project-files\/([^/]+)$/)
  if (files && method === 'GET') {
    requireProject(files[1]!)
    // 不用合成代码冒充用户文件内容，当前步骤只接入工作台。
    return []
  }
  const app = path.match(
    /^\/app\/([^/]+)\/(workspace|runtime\/status|runtime\/heartbeat|runtime\/incidents)$/,
  )
  if (app) {
    requireProject(app[1]!)
    if (app[2] === 'workspace' && method === 'GET')
      return { hasSource: true, staticEntry: null, capabilities: [], dashboardFeatures: [] }
    if (app[2] === 'runtime/status' && method === 'GET')
      return { status: 'STOPPED', running: false, supported: null }
    if (app[2] === 'runtime/heartbeat' && method === 'POST') return true
    if (app[2] === 'runtime/incidents' && method === 'GET') return []
  }
  throw new Error('当前无法完成此操作，请稍后重试。')
}

/** 仅独立预览入口调用；生产入口不安装这些传输替身。 */
export function installWorkbenchTransport() {
  // 1. 已有生成客户端仍使用原调用方式，响应只来自页面内存。
  request.defaults.adapter = async (config) => ({
    data: {
      code: 0,
      data: resolveRequest(config.url ?? '', (config.method ?? 'GET').toUpperCase(), config.params),
    },
    status: 200,
    statusText: 'OK',
    headers: {},
    config,
  })
  // 2. 手写 fetch 客户端也隔离；同源静态资源仍按原路径加载。
  const originalFetch = window.fetch.bind(window)
  window.fetch = async (input, init) => {
    const raw = input instanceof Request ? input.url : String(input)
    const url = new URL(raw, window.location.href)
    if (url.origin !== window.location.origin) throw new Error('当前无法访问此服务。')
    if (!url.pathname.startsWith('/api/')) return originalFetch(input, init)
    const method = (init?.method ?? (input instanceof Request ? input.method : 'GET')).toUpperCase()
    try {
      return Response.json({ code: 0, data: resolveRequest(raw, method) })
    } catch (error) {
      return Response.json({ code: 40000, message: (error as Error).message }, { status: 400 })
    }
  }
}

/** 流式交互替身只在隔离 Vite 配置中替换，不建立 WebSocket/SSE 或调用模型。 */
export function openAgentStream(
  _base: string,
  options: AgentStreamOptions,
  handlers: AgentStreamHandlers,
) {
  const current = requireProject(String(options.appId))
  workbenchCalls.push({ method: 'LOCAL', path: `/chat/${current.appId}` })
  const timer = setTimeout(() => {
    if (project?.appId !== current.appId || project.taskId !== current.taskId) return
    handlers.onEvent('text.delta', {
      content: `当前工程包含 ${current.files.length} 个文件。你想先调整哪个页面或功能？`,
    })
    handlers.onEvent('done', {})
  }, 600)
  return { close: () => clearTimeout(timer), transport: () => null }
}
