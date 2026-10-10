import type { FileMetadata, ImportOptions } from './manifest'
import { validateFiles } from './manifest'
import myAxios from '@/request'

/** 浏览器内存中的来源文件，不进入日志或持久化存储。 */
export interface FolderImportRequest {
  ownerId: string
  appName: string
  options: ImportOptions
  files: Array<{ metadata: FileMetadata; file: File }>
}

export interface FolderImportResult {
  appId: string
  taskId: string
  cancellationOutcome?: 'ALREADY_SUCCEEDED'
}

interface TaskView {
  taskId: string
  revision: string | number
  status: string
  cleanup: string
  contentHash: string
  manifest: { included: Array<{ path: string; size: string | number; directory?: boolean }> }
}

async function call<T>(url: string, method: string, data?: unknown, signal?: AbortSignal, csrf?: string, binary = false): Promise<T> {
  const response = await myAxios.request<{ code: number; message?: string; data: T }>({
    url: `/project/import${url}`, method, data, signal, timeout: signal ? 600_000 : 15_000,
    headers: { ...(csrf ? { 'X-Project-Import-CSRF': csrf } : {}), ...(binary ? { 'Content-Type': 'application/octet-stream' } : {}) },
  })
  if (response.data.code !== 20000) throw new Error(response.data.message || '请求失败，请查看任务状态。')
  return response.data.data
}

function checkCancelled(signal: AbortSignal) {
  if (signal.aborted) throw new DOMException('导入已取消', 'AbortError')
}

function qualifiedResult(result: FolderImportResult, taskId: string): FolderImportResult {
  if (!/^\d+$/.test(result.appId) || result.taskId !== taskId) throw new Error('导入结果关联错误。')
  return { appId: result.appId, taskId: result.taskId }
}

export async function submitFolderImport(request: FolderImportRequest, signal: AbortSignal): Promise<FolderImportResult> {
  checkCancelled(signal)
  const error = validateFiles(request.files.map(entry => entry.metadata))
  if (error) throw new Error(error)
  const csrf = await call<string>('/csrf', 'GET', undefined, signal)
  let taskId: string | undefined
  try {
    let task = await call<TaskView>('/tasks', 'POST', {
      ownerId: request.ownerId, appName: request.appName, options: request.options,
      files: request.files.map(entry => entry.metadata),
    }, signal, csrf)
    taskId = task.taskId
    if (!taskId || !task.manifest?.included?.length) throw new Error('导入任务返回无效。')
    const source = new Map(request.files.map(entry => [entry.metadata.path, entry]))
    for (const [index, metadata] of task.manifest.included.entries()) {
      checkCancelled(signal)
      const entry = source.get(metadata.path)
      if (!entry || entry.file.size !== Number(metadata.size) || !!entry.metadata.directory !== !!metadata.directory) throw new Error('服务端清单与所选文件不一致。')
      const updated: TaskView = await call<TaskView>(`/tasks/${taskId}/files/${index}?revision=${task.revision}`, 'PUT', entry.file, signal, csrf, true)
      if (updated.taskId !== taskId) throw new Error('导入任务关联错误。')
      task = updated
    }
    checkCancelled(signal)
    if (task.status !== 'WAITING_CONFIRMATION' || !/^[0-9a-f]{64}$/.test(task.contentHash)) throw new Error('内容封存未完成。')
    const result = await call<FolderImportResult>(`/tasks/${taskId}/commit`, 'POST', { revision: task.revision, contentHash: task.contentHash }, signal, csrf)
    return qualifiedResult(result, taskId)
  } catch (cause) {
    if (!signal.aborted || !taskId) throw cause
    // 关闭本地请求不等于取消后端任务；使用未撤销的短请求确认实际结果。
    let state: TaskView
    try {
      state = await call<TaskView>(`/tasks/${taskId}`, 'GET')
      if (state.status === 'SUCCEEDED') return { ...qualifiedResult(await call<FolderImportResult>(`/tasks/${taskId}/result`, 'GET'), taskId), cancellationOutcome: 'ALREADY_SUCCEEDED' }
      state = await call<TaskView>(`/tasks/${taskId}/cancel?revision=${state.revision}`, 'POST', undefined, undefined, csrf)
    } catch { throw new Error('取消结果未确认，请查看导入任务。') }
    if (state.status !== 'CANCELLED') throw new Error('停止未确认，请查看导入任务。')
    if (state.cleanup !== 'DONE') throw new Error('导入已取消，暂存清理失败。')
    throw new DOMException('导入已取消', 'AbortError')
  }
}

export async function loadImportPreferences(signal: AbortSignal): Promise<ImportOptions> {
  return call<ImportOptions>('/preferences', 'GET', undefined, signal)
}

export async function saveImportPreferences(options: ImportOptions, signal: AbortSignal, ownerId: string): Promise<void> {
  if (signal.aborted) throw new DOMException('请求已取消', 'AbortError')
  const csrf = await call<string>('/csrf', 'GET', undefined, signal)
  await call('/preferences', 'PUT', { ownerId, ...options }, signal, csrf)
}
