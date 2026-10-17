import myAxios from '@/request'
import type { AgentComposerSettings, AgentModelOption } from './types'

export interface SessionSettings {
  mode: AgentComposerSettings['mode']
  profileId: string
  effort: AgentComposerSettings['effort']
  policyRevision: string
}
export interface ToolPermission {
  id: string; editId: string; actionHash: string; action: string; highRisk: boolean
  status: string; revision: string; detailText: string
  display: { title: string; object: string; reason: string }
}
export interface CandidateReview {
  editId: string; hash: string; risk: string; status: string; revision: string
  body: { changes: Array<{ path: string; before: unknown; after: unknown; beforeText: string | null; afterText: string | null }> }
}
export interface CurrentEdit { state: string; outcome?: string; editId?: string; permissionId?: string; review?: CandidateReview }

async function call<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const csrf = method === 'GET' ? undefined : await call<string>('/project/import/csrf')
  const body = (await myAxios.request<{ code: number; message?: string; data: T }>({
    url: path, method, data, timeout: 30_000,
    headers: csrf ? { 'X-Project-Import-CSRF': csrf } : {},
  })).data
  if (body.code !== 20000) throw new Error(body.message || '操作失败，请重试。')
  return body.data
}
export const settings = (appId: string) => call<SessionSettings>(`/project/import/${appId}/agent-settings`)
export const models = (appId: string) => call<Array<AgentModelOption & { configured: boolean }>>(`/project/import/${appId}/agent-models`)
export const save = (appId: string, current: SessionSettings, next: AgentComposerSettings) =>
  call<SessionSettings>(`/project/import/${appId}/agent-settings`, 'PUT', {
    policyRevision: current.policyRevision, mode: next.mode, profileId: next.modelId, effort: next.effort,
  })
export const active = (appId: string) => call<CurrentEdit>(`/project/import/${appId}/agent-current`)
export const permission = (appId: string, id: string) => call<ToolPermission>(`/project/import/${appId}/agent-permissions/${id}`)
export const decideTool = (appId: string, current: ToolPermission, decision: string) =>
  call<ToolPermission>(`/project/import/${appId}/agent-permissions/${current.id}/decision`, 'POST', {
    requestId: crypto.randomUUID(), actionHash: current.actionHash, revision: current.revision, decision,
  })
export const decideReview = (review: CandidateReview, decision: string) =>
  call<CandidateReview>(`/project/import/agent-edits/${review.editId}/review/decision`, 'POST', {
    requestId: crypto.randomUUID(), reviewHash: review.hash, revision: review.revision, decision,
  })
