export type AgentPermissionMode = 'MANUAL' | 'GUARDED_AUTO' | 'AUTO'
export type AgentEffort = 'low' | 'medium' | 'high' | 'xhigh' | 'max'

export interface AgentModelOption {
  id: string
  name: string
  /** 真实模型名，界面优先展示；缺失时回退到 name。 */
  model?: string
  provider: string
  efforts: AgentEffort[]
}

export interface AgentComposerSettings {
  mode: AgentPermissionMode
  modelId: string
  effort: AgentEffort | null
}

export interface AgentPermissionRequest {
  id: string
  revision: string
  actionHash: string
  action: string
  object: string
  highRisk: boolean
  reason?: string
  detailTitle: string
  detailText: string
}

export type AgentToolDecision = 'APPROVE_ONCE' | 'APPROVE_SIMILAR' | 'REJECT'
export interface AgentToolDecisionEvent {
  requestId: string
  revision: string
  actionHash: string
  decision: AgentToolDecision
}

export interface AgentReviewFile {
  path: string
  change: 'ADDED' | 'MODIFIED' | 'DELETED'
  highRisk: boolean
  binary: boolean
}

export interface AgentChangeCandidate {
  id: string
  revision: string
  reviewHash: string
  highRisk: boolean
  files: AgentReviewFile[]
}

// 范围按差异文本的行号计数，从 1 开始；未加载部分不冒充完整差异。
export type AgentFileDiff =
  | { status: 'unloaded' }
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'loaded'; text: string; startLine: number; endLine: number; totalLines: number }

export interface AgentReviewIdentity {
  reviewId: string
  revision: string
  reviewHash: string
}

export interface AgentReviewFileEvent extends AgentReviewIdentity {
  path: string
  startLine: number
}

export interface AgentReviewDecisionEvent extends AgentReviewIdentity {
  decision: 'APPROVE' | 'REJECT'
}
