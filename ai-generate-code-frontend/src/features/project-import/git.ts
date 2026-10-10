import myAxios from '@/request'
import { createManifest, validateFiles, type FileMetadata, type ImportOptions } from './manifest'
import type { CredentialContext, CredentialView } from './credentials'

const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const decimal = /^(0|[1-9]\d*)$/
const statuses = ['RESOLVING_SOURCE', 'WAITING_BRANCH', 'READING_CONTENT', 'WAITING_CONFIRMATION', 'CANCELLING', 'CANCELLED', 'FAILED', 'STOP_FAILED', 'RECOVERY_REQUIRED', 'SUCCEEDED']
export interface GitSourceView {
  taskId: string; revision: string; status: string; branch: string | null; commit: string | null
  branches: Array<{ name: string; commit: string }>; mainTaskCreated: boolean
  contentHash: string | null; error: string | null; cleanup: string
  credentialId: string | null; credentialVersion: string | null
  definition: { appName: string; options: ImportOptions; source: { provider: CredentialView['provider']; cloneUrl: string } }
}
export interface GitMainView {
  taskId: string; revision: string; status: string; appName: string; contentHash: string | null
  manifest: { included: FileMetadata[]; excluded: Array<FileMetadata & { reason: string }> }
}

function current(context: CredentialContext) {
  if (context.signal.aborted || !context.isCurrent()) throw new DOMException('请求已取消', 'AbortError')
  if (!/^[1-9]\d*$/.test(context.ownerId)) throw new Error('请先登录。')
}
function reference(id: string) { if (!uuid.test(id)) throw new Error('来源任务无效。') }
function revision(value: unknown): string {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('任务版本无效。')
  if (!decimal.test(String(value))) throw new Error('任务版本无效。')
  return String(value)
}
function options(value: ImportOptions): ImportOptions {
  if (!value || [value.skipDependencies, value.skipBuildOutputs, value.includeSensitive].some(item => typeof item !== 'boolean')) throw new Error('导入范围无效。')
  return { skipDependencies: value.skipDependencies, skipBuildOutputs: value.skipBuildOutputs, includeSensitive: value.includeSensitive }
}
function sourceRepository(provider: CredentialView['provider'], input: string) {
  try {
    if (!['GitHub', 'Gitee'].includes(provider) || input.length > 512 || /[^\x21-\x7e]/.test(input)) throw new Error()
    const url = new URL(input), host = provider === 'GitHub' ? 'github.com' : 'gitee.com'
    const parts = url.pathname.replace(/\/$/, '').split('/')
    const name = parts[2]?.replace(/\.git$/, '')
    if (url.protocol !== 'https:' || url.hostname !== host || url.username || url.password || url.search || url.hash ||
      (url.port && url.port !== '443') || parts.length !== 3 || !/^[A-Za-z0-9][A-Za-z0-9_-]{0,99}$/.test(parts[1]!) ||
      !name || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/.test(name) || name.includes('..')) throw new Error()
    return { name, cloneUrl: `https://${host}/${parts[1]}/${name}.git` }
  } catch { throw new Error('请输入对应平台的 HTTPS 仓库地址。') }
}
export function repository(provider: CredentialView['provider'], input: string) {
  if (provider !== 'GitHub') throw new Error('仅支持 GitHub 仓库导入。')
  return sourceRepository(provider, input)
}
async function call<T>(context: CredentialContext, path: string, method = 'GET', data?: unknown): Promise<T> {
  current(context)
  let csrf: string | undefined
  if (method !== 'GET') csrf = await call<string>(context, '/csrf')
  current(context)
  let response
  try {
    response = await myAxios.request<{ code: number; data: T }>({ url: `/project/import${path}`, method, data,
      signal: context.signal, timeout: 30_000, headers: csrf ? { 'X-Project-Import-CSRF': csrf } : {} })
  } catch { current(context); throw new Error('来源请求失败，请重试。') }
  current(context)
  if (response.data.code !== 20000) {
    const error = new Error(response.data.code === 40900 ? '任务已变化，请重新读取。' :
      response.data.code === 40100 ? '请先登录。' : response.data.code === 40400 ? '来源任务不存在。' : '来源操作失败，请检查后重试。')
    if(path === '/git/tasks' && [40000,40100,40300,40400,40900,42900].includes(response.data.code)) error.name = 'SourceCreationRejected'
    throw error
  }
  return response.data.data
}
function view(input: GitSourceView): GitSourceView {
  if (!input || !statuses.includes(input.status) || typeof input.mainTaskCreated !== 'boolean' || !Array.isArray(input.branches) || input.branches.length > 20_000) throw new Error('来源结果无效。')
  reference(input.taskId)
  const branches = input.branches.map(item => {
    if (typeof item.name !== 'string' || item.name.length > 255 || !/^[0-9a-f]{40}$/.test(item.commit)) throw new Error('分支结果无效。')
    return { name: item.name, commit: item.commit }
  })
  if (typeof input.definition?.appName !== 'string' || input.definition.appName.length > 80 ||
    (input.branch !== null && (typeof input.branch !== 'string' || input.branch.length > 255)) || new Set(branches.map(item => item.name)).size !== branches.length ||
    (input.commit !== null && !/^[0-9a-f]{40}$/.test(input.commit)) || (input.contentHash !== null && !/^[0-9a-f]{64}$/.test(input.contentHash)) ||
    (input.error !== null && !/^[A-Z0-9_]{1,80}$/.test(input.error)) || !/^[A-Z_]{1,40}$/.test(input.cleanup)) throw new Error('来源结果无效。')
  const source = input.definition?.source
  sourceRepository(source?.provider, source?.cloneUrl)
  if ((input.credentialId === null) !== (input.credentialVersion === null)) throw new Error('凭据关联无效。')
  if (input.credentialId !== null) { reference(input.credentialId); if (revision(input.credentialVersion) === '0') throw new Error('凭据版本无效。') }
  return { taskId: input.taskId, revision: revision(input.revision), status: input.status, branches, branch: input.branch, commit: input.commit,
    credentialId: input.credentialId, credentialVersion: input.credentialVersion === null ? null : revision(input.credentialVersion),
    contentHash: input.contentHash, error: input.error, cleanup: input.cleanup, mainTaskCreated: input.mainTaskCreated,
    definition: { appName: input.definition.appName, options: options(input.definition.options), source: { provider: source.provider, cloneUrl: source.cloneUrl } } }
}
export async function prepareGit(context: CredentialContext, provider: CredentialView['provider'], url: string, scope: ImportOptions, credential?: CredentialView, requestId?: string) {
  if (requestId) reference(requestId)
  const parsed = repository(provider, url)
  if (credential && (credential.provider !== provider || credential.state !== 'ACTIVE')) throw new Error('请选择当前平台的有效凭据。')
  const result = view(await call<GitSourceView>(context, '/git/tasks', 'POST', { ownerId: context.ownerId, provider, repositoryUrl: parsed.cloneUrl,
    appName: parsed.name, options: options(scope), ...(requestId ? { requestId } : {}), ...(credential ? { credentialId: credential.id, credentialVersion: credential.version } : {}) }))
  if(result.definition.source.provider !== provider || result.definition.source.cloneUrl !== parsed.cloneUrl || result.definition.appName !== parsed.name ||
    JSON.stringify(result.definition.options) !== JSON.stringify(options(scope)) || result.credentialId !== (credential?.id ?? null) ||
    result.credentialVersion !== (credential?.version ?? null)) throw new Error('来源结果关联错误。')
  return result
}
export async function getGitByRequest(context: CredentialContext, requestId: string) {
  reference(requestId)
  return view(await call<GitSourceView>(context, `/git/tasks/request/${requestId}`))
}
export async function getGitResult(context: CredentialContext, task: GitSourceView) {
  if (task.status !== 'SUCCEEDED') throw new Error('导入尚未完成。')
  const result = await call<{appId:string;taskId:string}>(context, `/tasks/${task.taskId}/result`)
  if (result.taskId !== task.taskId || typeof result.appId !== 'string' || !/^[1-9]\d*$/.test(result.appId)) throw new Error('导入结果关联错误。')
  return { appId: result.appId, taskId: result.taskId }
}
export async function getGit(context: CredentialContext, id: string) {
  reference(id); const result = view(await call<GitSourceView>(context, `/git/tasks/${id}`))
  if (result.taskId !== id) throw new Error('来源关联错误。')
  return result
}
export async function selectGitBranch(context: CredentialContext, task: GitSourceView, branch: string) {
  reference(task.taskId)
  if (task.definition.source.provider !== 'GitHub') throw new Error('该来源已停用。')
  if (task.status !== 'WAITING_BRANCH' || !task.branches.some(item => item.name === branch)) throw new Error('请选择当前仓库的一个分支。')
  const result = view(await call<GitSourceView>(context, `/git/tasks/${task.taskId}/branch`, 'POST', { ownerId: context.ownerId, revision: revision(task.revision), branch }))
  if (result.taskId !== task.taskId) throw new Error('来源关联错误。')
  return result
}
export async function rebuildGit(context: CredentialContext, task: GitSourceView, appName: string, scope: ImportOptions) {
  reference(task.taskId)
  if (task.definition.source.provider !== 'GitHub') throw new Error('该来源已停用。')
  if (task.status !== 'WAITING_CONFIRMATION' || !appName.trim() || appName.trim().length > 80 || /[\x00-\x1f\x7f]/.test(appName)) throw new Error('名称或任务状态无效。')
  const result = view(await call<GitSourceView>(context, `/git/tasks/${task.taskId}/range`, 'POST', { ownerId: context.ownerId, revision: revision(task.revision), appName: appName.trim(), options: options(scope) }))
  if (result.taskId !== task.taskId || result.commit !== task.commit || result.branch !== task.branch) throw new Error('固定内容关联错误。')
  return result
}
export async function getGitMain(context: CredentialContext, task: GitSourceView): Promise<GitMainView> {
  const input = await call<Omit<GitMainView,'manifest'> & {manifest:{included:FileMetadata[];excluded:Array<{file:FileMetadata;reason:string}>}}>(context, `/tasks/${task.taskId}`)
  if (!input || input.taskId !== task.taskId || !input.manifest || !Array.isArray(input.manifest.included) || !Array.isArray(input.manifest.excluded)) throw new Error('内容清单关联错误。')
  const entry = (file: FileMetadata): FileMetadata => ({ path: file.path, size: Number(file.size) })
  const included = input.manifest.included.map(entry), excluded = input.manifest.excluded.map(item => ({ ...entry(item.file), reason: item.reason }))
  const error = validateFiles([...included, ...excluded])
  if (error) throw new Error(error)
  return { taskId: input.taskId, revision: revision(input.revision), status: input.status, appName: input.appName, contentHash: input.contentHash, manifest: { included, excluded } }
}
export async function commitGit(context: CredentialContext, task: GitSourceView, appName: string, scope: ImportOptions) {
  if (task.definition.source.provider !== 'GitHub') throw new Error('该来源已停用。')
  const latest = await getGit(context, task.taskId), main = await getGitMain(context, latest)
  if (latest.definition.source.provider !== 'GitHub') throw new Error('该来源已停用。')
  if (latest.status !== 'WAITING_CONFIRMATION' || main.status !== 'WAITING_CONFIRMATION' || latest.commit !== task.commit ||
    latest.branch !== task.branch || main.contentHash !== latest.contentHash || !main.contentHash || !/^[0-9a-f]{64}$/.test(main.contentHash) ||
    main.appName !== appName.trim() || latest.definition.appName !== appName.trim() || JSON.stringify(options(latest.definition.options)) !== JSON.stringify(options(scope))) throw new Error('确认内容已变化，请重新读取。')
  const expected = createManifest([...main.manifest.included, ...main.manifest.excluded], scope).included
  if (expected.length !== main.manifest.included.length || expected.some(file => !main.manifest.included.some(item => item.path === file.path && item.size === file.size))) throw new Error('确认范围不一致。')
  const result = await call<{ appId: string; taskId: string }>(context, `/tasks/${task.taskId}/commit`, 'POST', { revision: main.revision, contentHash: main.contentHash })
  if (result.taskId !== task.taskId || typeof result.appId !== 'string' || !/^[1-9]\d*$/.test(result.appId)) throw new Error('导入结果关联错误，请查看任务。')
  return { appId: result.appId, taskId: result.taskId }
}
export async function cancelGit(context: CredentialContext, task: GitSourceView) {
  const latest = await getGit(context, task.taskId)
  if (latest.status === 'SUCCEEDED') throw new Error('应用已导入，不能取消。')
  const result = view(await call<GitSourceView>(context, `/git/tasks/${task.taskId}/cancel?revision=${latest.revision}`, 'POST'))
  if (result.taskId !== task.taskId || result.status !== 'CANCELLED' || result.cleanup !== 'DONE') throw new Error('停止或清理未确认，请保留任务后重试。')
  return result
}
export async function waitGit(context: CredentialContext, initial: GitSourceView) {
  let task = initial
  const deadline = Date.now() + 360_000
  while (['RESOLVING_SOURCE', 'READING_CONTENT'].includes(task.status)) {
    current(context)
    if (Date.now() > deadline) throw new Error('读取尚未结束，请取消后重试。')
    await new Promise<void>((resolve, reject) => {
      const stop = () => { clearTimeout(timer); reject(new DOMException('请求已取消', 'AbortError')) }
      const timer = setTimeout(() => { context.signal.removeEventListener('abort', stop); resolve() }, 350)
      context.signal.addEventListener('abort', stop, { once: true })
    })
    task = await getGit(context, initial.taskId)
    if (initial.commit && task.commit !== initial.commit) throw new Error('固定内容版本已变化。')
  }
  if (!['WAITING_BRANCH', 'WAITING_CONFIRMATION', 'SUCCEEDED'].includes(task.status)) {
    const messages: Record<string, string> = { AUTH_INVALID: 'Token 无效，请替换后重新导入。', AUTH_FORBIDDEN: 'Token 无仓库访问权限。', AUTH_RATE_LIMITED: '平台请求受限，请稍后重试。' }
    throw new Error(messages[task.error ?? ''] ?? `读取失败${task.error ? `（${task.error}）` : ''}，请取消后重试。`)
  }
  return task
}
