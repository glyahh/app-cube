import myAxios from '@/request'
import type { AgentComposerSettings, AgentModelOption } from './types'

export interface SessionSettings {
  mode: AgentComposerSettings['mode']
  profileId: string
  effort: AgentComposerSettings['effort']
  policyRevision: string
}
export interface ToolPermission {
  id: string
  editId: string
  actionHash: string
  action: string
  highRisk: boolean
  status: string
  revision: string
  detailText: string
  display: { title: string; object: string; reason: string }
}
export interface CandidateReview {
  editId: string
  hash: string
  risk: string
  status: string
  revision: string
  body: {
    changes: Array<{
      path: string
      before: unknown
      after: unknown
      beforeText: string | null
      afterText: string | null
      truncated?: boolean
    }>
  }
}
export interface CurrentEdit {
  state: string
  outcome?: string
  editId?: string
  permissionId?: string
  review?: CandidateReview
}
export interface ModelPreference {
  profileId: string | null
  effort: AgentComposerSettings['effort']
  revision: string | null
  configured: boolean
}
export interface ModelConfig {
  preference: ModelPreference
  models: Array<AgentModelOption & { configured: boolean }>
  /** 普通应用的授权模式；导入应用为 null，改用导入设置接口。 */
  mode: AgentComposerSettings['mode'] | null
  sandboxMode: AgentSandboxMode | null
  policyRevision: string | null
}

/** 把 axios 的 5xx / 网络错误换成用户能看懂的文案，其余原样抛出。 */
function readable(error: unknown): never {
  const status = (error as { response?: { status?: number } })?.response?.status
  if (status === undefined) throw new Error('无法连接后端服务，请确认后端已启动。')
  if (status >= 500) throw new Error('后端服务异常，请稍后重试。')
  throw error
}

/** 普通应用沿用登录态与归属校验，不走导入 CSRF 门禁。 */
async function plain<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const body = (
    await myAxios
      .request<{ code: number; message?: string; data: T }>({
        url: path,
        method,
        data,
        timeout: 30_000,
      })
      .catch(readable)
  ).data
  if (body.code !== 20000) throw new Error(body.message || '操作失败，请重试。')
  return body.data
}

async function call<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const csrf = method === 'GET' ? undefined : await call<string>('/project/import/csrf')
  const body = (
    await myAxios
      .request<{ code: number; message?: string; data: T }>({
        url: path,
        method,
        data,
        timeout: 30_000,
        headers: csrf ? { 'X-Project-Import-CSRF': csrf } : {},
      })
      .catch(readable)
  ).data
  if (body.code !== 20000) throw new Error(body.message || '操作失败，请重试。')
  return body.data
}
export const settings = (appId: string) =>
  call<SessionSettings>(`/project/import/${appId}/agent-settings`)
export const save = (appId: string, current: SessionSettings, next: AgentComposerSettings) =>
  call<SessionSettings>(`/project/import/${appId}/agent-settings`, 'PUT', {
    policyRevision: current.policyRevision,
    mode: next.mode,
    profileId: next.modelId,
    effort: next.effort,
  })
export const active = (appId: string) => call<CurrentEdit>(`/project/import/${appId}/agent-current`)
export const permission = (appId: string, id: string) =>
  call<ToolPermission>(`/project/import/${appId}/agent-permissions/${id}`)
export const decideTool = (appId: string, current: ToolPermission, decision: string) =>
  call<ToolPermission>(
    `/project/import/${appId}/agent-permissions/${current.id}/decision`,
    'POST',
    {
      requestId: crypto.randomUUID(),
      actionHash: current.actionHash,
      revision: current.revision,
      decision,
    },
  )
export const decideReview = (review: CandidateReview, decision: string) =>
  call<CandidateReview>(`/project/import/agent-edits/${review.editId}/review/decision`, 'POST', {
    requestId: crypto.randomUUID(),
    reviewHash: review.hash,
    revision: review.revision,
    decision,
  })
/** 所有应用统一的模型目录与偏好入口。 */
export const modelConfig = (appId: string) => plain<ModelConfig>(`/app/${appId}/agent-model`)
export const saveProjectModel = (
  appId: string,
  revision: string | null,
  profileId: string,
  effort: AgentComposerSettings['effort'],
) => plain<ModelPreference>(`/app/${appId}/agent-model`, 'PUT', { revision, profileId, effort })
/** 普通应用的授权模式与沙箱档位；导入应用走 /project/import 下的设置接口。 */
export type AgentSandboxMode = 'READ_ONLY' | 'WORKSPACE_WRITE'
export interface EditSettings {
  appId: string
  threadId: string
  mode: AgentComposerSettings['mode']
  sandboxMode: AgentSandboxMode
  policyRevision: string
  /** 后端冻结的会话代次与到期时间；前端暂未使用，但保持类型与服务端一致。 */
  sessionEpoch?: string
  sessionExpiresAt?: string
}
export const saveEditMode = (
  appId: string,
  revision: string | null,
  mode: AgentComposerSettings['mode'],
  sandboxMode: AgentSandboxMode = 'WORKSPACE_WRITE',
) =>
  plain<EditSettings>(`/app/${appId}/agent-settings`, 'PUT', {
    policyRevision: revision ?? '0',
    mode,
    sandboxMode,
  })

/** 普通应用当前是否有待批准动作；前端轮询它来显示卡片。 */
export const projectPending = (appId: string) =>
  plain<ToolPermission | null>(`/app/${appId}/agent-permissions/pending`)
/** 普通应用的工具许可：动作详情与风险都来自服务端，前端只提交决定。 */
export const decideProjectTool = (appId: string, current: ToolPermission, decision: string) =>
  plain<ToolPermission>(`/app/${appId}/agent-permissions/${current.id}/decision`, 'POST', {
    requestId: crypto.randomUUID(),
    actionHash: current.actionHash,
    revision: current.revision,
    decision,
  })
/** 普通应用的候选审阅：拒绝会按账本回滚到本轮开始前。 */
export interface ProjectReview {
  reviewId: string
  sessionId: string
  reviewHash: string
  risk: string
  status: string
  revision: string
  body: {
    candidateHash: string
    changes: Array<{
      path: string
      change: string
      beforeHash: string | null
      afterHash: string | null
      truncated: boolean
      beforeText?: string
      afterText?: string
    }>
  }
}
/** 该应用最近一条候选审阅；普通应用靠它显示整批确认卡片。 */
export const projectLatestReview = (appId: string) =>
  plain<ProjectReview | null>(`/app/${appId}/agent-permissions/reviews/latest`)
export const projectReview = (appId: string, reviewId: string) =>
  plain<ProjectReview>(`/app/${appId}/agent-permissions/reviews/${reviewId}`)
export const decideProjectReview = (appId: string, review: ProjectReview, decision: string) =>
  plain<ProjectReview>(
    `/app/${appId}/agent-permissions/reviews/${review.reviewId}/decision`,
    'POST',
    {
      requestId: crypto.randomUUID(),
      reviewHash: review.reviewHash,
      revision: review.revision,
      decision,
    },
  )
