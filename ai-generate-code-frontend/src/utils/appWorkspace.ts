/**
 * 项目工作区描述客户端。
 *
 * <p>预览入口必须由服务端读真实产物后决定，不能按创建时选的 codeGenType 推断 ——
 * 类型是 vue 的目录里未必有 dist/index.html（一次纯静态页面需求可能被关键词路由判成
 * vue），前端照着取 dist/index.html 就会指向一个不存在的文件，预览区空白。
 */

/** 后端工作区描述返回结构 */
export interface AppWorkspaceInfo {
  appId?: number
  codeGenType?: string
  /** 实际产物目录名，如 vue_project_431379639885025280 */
  workspaceDirName?: string
  hasSource?: boolean
  /** 可直接打开的静态入口，相对产物目录；没有可用入口时为 null */
  staticEntry?: string | null
  /** 当前可用能力标识，目前只有 preview */
  capabilities?: string[]
  unsupportedReason?: string | null
  /** 已启用的看板功能：metrics 监控 / logs 日志 / knowledge 知识图谱 */
  dashboardFeatures?: string[]
}

export interface LoadAppWorkspaceOpts {
  /** 应用 ID */
  appId: string
  /** API base URL（默认 /api） */
  apiBaseUrl?: string
}

/** 拆工作区接口的统一响应信封 */
async function readWorkspaceEnvelope(res: Response, action: string): Promise<AppWorkspaceInfo> {
  if (!res.ok) {
    throw new Error(`${action}失败 (HTTP ${res.status})`)
  }

  const body: { code: number; data?: AppWorkspaceInfo; message?: string } = await res.json()

  if (body.code !== 0 && body.code !== 20000) {
    throw new Error(body.message || `${action}失败`)
  }

  return body.data ?? {}
}

/**
 * 读取服务端对项目工作区的描述。
 *
 * 调用 GET /api/app/{appId}/workspace。接口失败时抛出异常，由调用方决定降级策略。
 */
export async function loadAppWorkspace(opts: LoadAppWorkspaceOpts): Promise<AppWorkspaceInfo> {
  const { appId, apiBaseUrl = '/api' } = opts

  const base = apiBaseUrl.replace(/\/$/, '')
  const url = `${base}/app/${encodeURIComponent(appId)}/workspace`

  const res = await fetch(url, {
    method: 'GET',
    credentials: 'include',
  })

  return readWorkspaceEnvelope(res, '获取项目工作区描述')
}

export interface UpdateDashboardFeaturesOpts {
  appId: string
  /** 要启用的看板功能标识；空数组表示全部关闭 */
  features: string[]
  apiBaseUrl?: string
}

/**
 * 修改项目的看板功能开关。
 *
 * 调用 PUT /api/app/{appId}/dashboard-features，返回更新后的工作区描述。
 */
export async function updateDashboardFeatures(
  opts: UpdateDashboardFeaturesOpts,
): Promise<AppWorkspaceInfo> {
  const { appId, features, apiBaseUrl = '/api' } = opts

  const base = apiBaseUrl.replace(/\/$/, '')
  const url = `${base}/app/${encodeURIComponent(appId)}/dashboard-features`

  const res = await fetch(url, {
    method: 'PUT',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ features }),
  })

  return readWorkspaceEnvelope(res, '保存看板设置')
}
