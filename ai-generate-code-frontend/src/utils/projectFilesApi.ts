/**
 * 项目工作区文件接口客户端（文件树 / 单文件读取 / 保存 / 下载）。
 *
 * 与 projectFilesEcho.ts 的分工：那个接口一次把整个工作区的文本文件**连同内容**全量返回，
 * 够做回显；文件树需要按需展开、只在选中时读内容、保存要带版本校验，所以另开一组接口。
 */

/** 文件树条目（后端 ProjectTreeEntryVO） */
export interface ProjectTreeEntry {
  name: string
  /** 相对工作区根，`/` 分隔 */
  path: string
  type: 'dir' | 'file'
  /** 文件字节数；目录为 null */
  size?: number | null
  /** 是否可作为文本编辑；目录为 null */
  isText?: boolean | null
  updatedAt?: number | null
}

/** 单个文件内容（后端 ProjectFileContentVO） */
export interface ProjectFileContent {
  path: string
  isText?: boolean
  size?: number
  language?: string
  /** 非文本文件为 null */
  content?: string | null
  /** 内容版本，保存时回传 */
  version?: string
  updatedAt?: number
}

interface BaseOpts {
  appId: string
  apiBaseUrl?: string
}

/** 保存冲突的业务码，与后端 ErrorCode.CONFLICT_ERROR 对应 */
export const PROJECT_FILE_CONFLICT_CODE = 40900

function baseUrlOf(apiBaseUrl?: string): string {
  return (apiBaseUrl ?? '/api').replace(/\/$/, '')
}

/**
 * 拆统一响应信封。
 *
 * 业务错误带上 code，调用方才能区分「版本冲突」和其他失败。
 */
async function readEnvelope<T>(res: Response, action: string): Promise<T> {
  if (!res.ok) {
    throw new Error(`${action}失败 (HTTP ${res.status})`)
  }
  const body: { code: number; data?: T; message?: string } = await res.json()
  if (body.code !== 0 && body.code !== 20000) {
    const err = new Error(body.message || `${action}失败`) as Error & { code?: number }
    err.code = body.code
    throw err
  }
  return body.data as T
}

/** 列出某个目录的直接子项；path 省略表示工作区根。 */
export async function loadProjectTree(
  opts: BaseOpts & { path?: string },
): Promise<ProjectTreeEntry[]> {
  const { appId, path, apiBaseUrl } = opts
  const query = path ? `?path=${encodeURIComponent(path)}` : ''
  const url = `${baseUrlOf(apiBaseUrl)}/app/${encodeURIComponent(appId)}/files/tree${query}`
  const res = await fetch(url, { method: 'GET', credentials: 'include' })
  const data = await readEnvelope<ProjectTreeEntry[]>(res, '读取文件树')
  return data ?? []
}

/** 读取单个文件；二进制文件返回的 content 为 null。 */
export async function loadProjectFileContent(
  opts: BaseOpts & { path: string },
): Promise<ProjectFileContent> {
  const { appId, path, apiBaseUrl } = opts
  const url = `${baseUrlOf(apiBaseUrl)}/app/${encodeURIComponent(appId)}/files/content?path=${encodeURIComponent(path)}`
  const res = await fetch(url, { method: 'GET', credentials: 'include' })
  return readEnvelope<ProjectFileContent>(res, '读取文件')
}

/**
 * 保存文本文件。
 *
 * baseVersion 是读取时拿到的版本；服务端比对不一致会抛 code = 40900，
 * 调用方据此保留草稿并提示重新读取，而不是覆盖掉 AI 刚写入的内容。
 */
export async function saveProjectFile(
  opts: BaseOpts & { path: string; content: string; baseVersion?: string },
): Promise<ProjectFileContent> {
  const { appId, path, content, baseVersion, apiBaseUrl } = opts
  const url = `${baseUrlOf(apiBaseUrl)}/app/${encodeURIComponent(appId)}/files/content`
  const res = await fetch(url, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ path, content, baseVersion }),
  })
  return readEnvelope<ProjectFileContent>(res, '保存文件')
}

/** 下载地址交给浏览器直连，不用 fetch —— 避免把整个文件读进内存再转 blob。 */
export function buildProjectFileDownloadUrl(opts: BaseOpts & { path: string }): string {
  const { appId, path, apiBaseUrl } = opts
  return `${baseUrlOf(apiBaseUrl)}/app/${encodeURIComponent(appId)}/files/download?path=${encodeURIComponent(path)}`
}

/** 判断错误是否为保存版本冲突。 */
export function isProjectFileConflict(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { code?: number }).code === PROJECT_FILE_CONFLICT_CODE
  )
}
