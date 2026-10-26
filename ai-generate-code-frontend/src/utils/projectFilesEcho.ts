export interface GeneratedFileItem {
  path: string
  language?: string
  content: string
  updatedAt: number
}

/** 后端返回的原始项目文件条目 */
interface ProjectFileRaw {
  path: string
  language?: string
  content: string
  updatedAt: number | string
}

/** 前端回显不展示的锁文件（磁盘保留，构建正常使用） */
const LOCK_FILE_PATTERNS = ['package-lock.json', 'yarn.lock', 'pnpm-lock.yaml']

function isLockFile(filePath: string): boolean {
  const normalized = filePath.replace(/\\/g, '/')
  const baseName = normalized.split('/').pop() ?? ''
  return LOCK_FILE_PATTERNS.includes(baseName)
}

/** 对任意文件路径列表过滤锁文件，返回过滤后的列表 */
export function filterLockFiles<T extends { path: string }>(items: T[]): T[] {
  return items.filter((item) => !isLockFile(item.path))
}

export interface LoadProjectFilesEchoOpts {
  /** 应用 ID */
  appId: string
  /** API base URL（默认 /api） */
  apiBaseUrl?: string
}

/**
 * 从后端新接口获取项目文件快照，不再依赖聊天历史导出或工具输出解析。
 *
 * 调用 GET /api/app/static/project-files/{appId}，返回磁盘上当前存在的全部文本代码文件。
 * 接口失败时抛出异常，由调用方决定降级策略。
 */
export async function loadProjectFilesEchoFromDisk(
  opts: LoadProjectFilesEchoOpts,
): Promise<GeneratedFileItem[]> {
  const { appId, apiBaseUrl = '/api' } = opts

  const base = apiBaseUrl.replace(/\/$/, '')
  const url = `${base}/app/static/project-files/${encodeURIComponent(appId)}`

  const res = await fetch(url, {
    method: 'GET',
    credentials: 'include',
  })

  if (!res.ok) {
    throw new Error(`获取项目文件失败 (HTTP ${res.status})`)
  }

  const body: { code: number; data?: ProjectFileRaw[]; message?: string } = await res.json()

  if (body.code !== 0 && body.code !== 20000) {
    throw new Error(body.message || '获取项目文件失败')
  }

  const rawList: ProjectFileRaw[] = body.data ?? []

  const items: GeneratedFileItem[] = rawList
    .filter((raw) => !isLockFile(raw.path))
    .map((raw) => ({
      path: raw.path,
      language: raw.language,
      content: raw.content,
      updatedAt: Number(raw.updatedAt) || 0,
    }))

  items.sort((a, b) => a.path.localeCompare(b.path))
  return items
}
