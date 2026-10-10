import myAxios from '@/request'

export interface CredentialView {
  id: string
  provider: 'GitHub' | 'Gitee'
  name: string
  version: string
  revision: string
  state: 'ACTIVE' | 'DELETED'
}
export interface CredentialContext {
  ownerId: string
  signal: AbortSignal
  isCurrent: () => boolean
}
export interface CredentialChange {
  credential: CredentialView
  stops: Array<{ taskId: string; status: string; cleanup: string; error: string | null }>
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const decimal = /^(0|[1-9]\d*)$/

function current(context: CredentialContext) {
  if (context.signal.aborted || !context.isCurrent()) throw new DOMException('请求已取消', 'AbortError')
  if (!/^[1-9]\d*$/.test(context.ownerId)) throw new Error('请先登录。')
}

function view(input: CredentialView): CredentialView {
  if (!input || typeof input.id !== 'string' || !uuid.test(input.id) || !['GitHub', 'Gitee'].includes(input.provider) ||
    typeof input.name !== 'string' || input.name.length > 40 || typeof input.version !== 'string' || input.version === '0' ||
    typeof input.revision !== 'string' || !decimal.test(input.version) || !decimal.test(input.revision) ||
    !['ACTIVE', 'DELETED'].includes(input.state)) throw new Error('凭据结果无效，请刷新。')
  // 只接收元数据白名单，响应中的额外字段不能进入界面状态。
  return { id: input.id, provider: input.provider, name: input.name, version: input.version, revision: input.revision, state: input.state }
}

async function call<T>(context: CredentialContext, path: string, method: string, data?: unknown, csrf?: string): Promise<T> {
  current(context)
  let response
  try {
    response = await myAxios.request<{ code: number; data: T }>({
      url: `/project/import${path}`, method, data, signal: context.signal, timeout: 15_000,
      headers: csrf ? { 'X-Project-Import-CSRF': csrf } : {},
    })
  } catch {
    current(context)
    // 不向页面或日志传播含 Token 请求体的 Axios error/config。
    throw new Error('凭据请求失败，请重试。')
  }
  current(context)
  if (response.data.code !== 20000) {
    const message = response.data.code === 40900 ? '凭据已变化，请刷新。' :
      response.data.code === 40100 ? '请先登录。' : response.data.code === 40400 ? '凭据不存在。' : '凭据操作失败，请重试。'
    throw new Error(message)
  }
  return response.data.data
}

export async function loadCredentials(context: CredentialContext): Promise<CredentialView[]> {
  const rows = await call<CredentialView[]>(context, '/credentials', 'GET')
  if (!Array.isArray(rows)) throw new Error('凭据结果无效，请刷新。')
  return rows.map(view)
}

function validateName(name: string) {
  if (!name.trim() || name.length > 40 || /[\x00-\x1f\x7f]/.test(name)) throw new Error('备注限 40 字。')
}
function validateToken(token: string) {
  if (!token || token.length > 4096 || /[^\x21-\x7e]/.test(token)) throw new Error('Token 格式无效，最多 4096 字符。')
}
function validateReference(credential: CredentialView) {
  view(credential)
  if (credential.state !== 'ACTIVE') throw new Error('凭据不存在。')
}

export async function createCredential(context: CredentialContext, provider: CredentialView['provider'], name: string, token: string) {
  validateName(name); validateToken(token)
  if (provider !== 'GitHub') throw new Error('仅支持 GitHub 仓库导入。')
  const csrf = await call<string>(context, '/csrf', 'GET')
  return view(await call<CredentialView>(context, '/credentials', 'POST', { ownerId: context.ownerId, provider, name, token }, csrf))
}

export async function renameCredential(context: CredentialContext, credential: CredentialView, name: string) {
  validateReference(credential); validateName(name)
  if (credential.provider !== 'GitHub') throw new Error('该凭据已停用，仅可删除。')
  const csrf = await call<string>(context, '/csrf', 'GET')
  return view(await call<CredentialView>(context, `/credentials/${credential.id}/name`, 'PUT',
    { ownerId: context.ownerId, revision: credential.revision, name }, csrf))
}

export async function replaceCredential(context: CredentialContext, credential: CredentialView, token: string): Promise<CredentialChange> {
  validateReference(credential); validateToken(token)
  if (credential.provider !== 'GitHub') throw new Error('该凭据已停用，仅可删除。')
  const csrf = await call<string>(context, '/csrf', 'GET')
  return change(await call<CredentialChange>(context, `/credentials/${credential.id}/token`, 'PUT',
    { ownerId: context.ownerId, revision: credential.revision, token }, csrf))
}

export async function deleteCredential(context: CredentialContext, credential: CredentialView): Promise<CredentialChange> {
  validateReference(credential)
  const csrf = await call<string>(context, '/csrf', 'GET')
  return change(await call<CredentialChange>(context, `/credentials/${credential.id}?ownerId=${context.ownerId}&revision=${credential.revision}`, 'DELETE', undefined, csrf))
}

function change(input: CredentialChange): CredentialChange {
  if (!input || !Array.isArray(input.stops)) throw new Error('凭据停止结果未确认，请刷新。')
  const stops = input.stops.map(stop => {
    if (!uuid.test(stop.taskId) || !/^[A-Z_]{1,40}$/.test(stop.status) || !/^[A-Z_]{1,40}$/.test(stop.cleanup) ||
      (stop.error !== null && !/^[A-Z0-9_]{1,80}$/.test(stop.error))) throw new Error('凭据停止结果未确认，请刷新。')
    return { taskId: stop.taskId, status: stop.status, cleanup: stop.cleanup, error: stop.error }
  })
  return { credential: view(input.credential), stops }
}
