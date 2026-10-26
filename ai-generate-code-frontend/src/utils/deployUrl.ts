/** 本地开发时后端返回的 deploy URL 可能缺端口，统一补齐（默认 8124） */
export const DEPLOY_FALLBACK_PORT = String(import.meta.env.VITE_DEPLOY_FALLBACK_PORT ?? '8124')

/**
 * 归一化部署访问 URL：补全协议、localhost 端口、相对路径等。
 * 与首页部署弹窗逻辑一致，供对话页等复用。
 */
export function normalizeDeployUrl(rawUrl: unknown): string {
  const raw = String(rawUrl ?? '').trim()
  if (!raw) return ''

  // 相对路径（如 /api/deploy/{key}/）：必须基于当前站点 origin 解析，避免误走 http:///path
  if (raw.startsWith('/')) {
    try {
      const u = new URL(raw, window.location.origin)
      if (u.hostname === 'localhost' && !u.port) {
        u.port = DEPLOY_FALLBACK_PORT
      }
      return u.toString()
    } catch {
      // fall through
    }
  }

  try {
    const abs =
      raw.startsWith('http://') || raw.startsWith('https://')
        ? new URL(raw)
        : new URL(raw.startsWith('//') ? `${window.location.protocol}${raw}` : `http://${raw}`)
    if (abs.hostname === 'localhost' && !abs.port) {
      abs.port = DEPLOY_FALLBACK_PORT
    }
    return abs.toString()
  } catch {
    try {
      return new URL(raw, window.location.origin).toString()
    } catch {
      return raw
    }
  }
}

/**
 * 根据 deployKey 与 API 前缀拼接部署访问地址（与后端 AppController#buildDeployUrlFromRequest 一致）。
 */
export function buildDeployUrlFromKey(
  deployKey: string | null | undefined,
  apiBaseUrl: string,
): string {
  if (deployKey === undefined || deployKey === null || String(deployKey).trim() === '') return ''
  const base = apiBaseUrl.replace(/\/$/, '')
  const relative = `${base}/deploy/${String(deployKey).trim()}/`
  return normalizeDeployUrl(relative)
}
