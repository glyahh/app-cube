/**
 * 项目知识接口客户端（知识状态 / 图谱 / 文档 / 问答）。
 *
 * 与 appRuntimeApi.ts 同样的手写 fetch 风格。
 */

/** 知识状态（后端 KnowledgeStatusVO） */
export interface KnowledgeStatus {
  appId?: number
  /** 工作区当前内容版本 */
  currentVersion?: string | null
  /** 已有知识对应的快照版本 */
  knowledgeVersion?: string | null
  /** 知识是否落后于当前代码 */
  outdated?: boolean
  hasKnowledge?: boolean
  docCount?: number
  nodeCount?: number
  edgeCount?: number
  taskId?: number | null
  taskStatus?: string | null
  progress?: number | null
  phase?: string | null
  errorMessage?: string | null
  retryable?: boolean
  taskUpdatedAt?: string | null
}

/** 图谱节点 */
export interface GraphNode {
  key: string
  name: string
  kind?: string | null
  summary?: string | null
  /** CODE_EVIDENCE（源码里读出来的）/ AI_INFERENCE（模型推断） */
  evidence?: string | null
  sourceRef?: string | null
}

/** 图谱关系 */
export interface GraphEdge {
  from: string
  to: string
  relation?: string | null
  evidence?: string | null
  sourceRef?: string | null
}

export interface KnowledgeGraph {
  appId?: number
  snapshotVersion?: string | null
  currentVersion?: string | null
  outdated?: boolean
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export interface KnowledgeDoc {
  id: number
  title?: string | null
  content?: string | null
  snapshotVersion?: string | null
}

export interface KnowledgeQaMessage {
  id: number
  role?: 'user' | 'assistant'
  content?: string | null
  snapshotVersion?: string | null
  /** 回答依据的资料是否已过期 */
  stale?: boolean
  citationsJson?: string | null
  createTime?: string | null
}

export interface KnowledgeTask {
  id: number
  status?: string | null
  progress?: number | null
  phase?: string | null
  errorMessage?: string | null
  snapshotVersion?: string | null
}

interface BaseOpts {
  appId: string
  apiBaseUrl?: string
}

function baseUrlOf(apiBaseUrl?: string): string {
  return (apiBaseUrl ?? '/api').replace(/\/$/, '')
}

async function readEnvelope<T>(res: Response, action: string): Promise<T> {
  if (!res.ok) {
    throw new Error(`${action}失败 (HTTP ${res.status})`)
  }
  const body: { code: number; data?: T; message?: string } = await res.json()
  if (body.code !== 0 && body.code !== 20000) {
    throw new Error(body.message || `${action}失败`)
  }
  return body.data as T
}

function base(opts: BaseOpts): string {
  return `${baseUrlOf(opts.apiBaseUrl)}/app/${encodeURIComponent(opts.appId)}/knowledge`
}

/** 知识状态：版本对齐情况 + 最近任务进度 */
export async function loadKnowledgeStatus(opts: BaseOpts): Promise<KnowledgeStatus> {
  const res = await fetch(`${base(opts)}/status`, { method: 'GET', credentials: 'include' })
  return readEnvelope<KnowledgeStatus>(res, '读取知识状态')
}

/** 知识图谱 */
export async function loadKnowledgeGraph(opts: BaseOpts): Promise<KnowledgeGraph> {
  const res = await fetch(`${base(opts)}/graph`, { method: 'GET', credentials: 'include' })
  const data = await readEnvelope<KnowledgeGraph>(res, '读取知识图谱')
  return data ?? { nodes: [], edges: [] }
}

/** 知识文档 */
export async function loadKnowledgeDocs(opts: BaseOpts): Promise<KnowledgeDoc[]> {
  const res = await fetch(`${base(opts)}/docs`, { method: 'GET', credentials: 'include' })
  const data = await readEnvelope<KnowledgeDoc[]>(res, '读取知识文档')
  return data ?? []
}

/** 提交知识生成；同一快照已有任务时后端直接返回它 */
export async function submitKnowledgeTask(opts: BaseOpts): Promise<KnowledgeTask> {
  const res = await fetch(`${base(opts)}/tasks`, { method: 'POST', credentials: 'include' })
  return readEnvelope<KnowledgeTask>(res, '提交知识生成')
}

/** 取消任务 */
export async function cancelKnowledgeTask(
  opts: BaseOpts & { taskId: number },
): Promise<boolean> {
  const res = await fetch(`${base(opts)}/tasks/${encodeURIComponent(String(opts.taskId))}/cancel`, {
    method: 'POST',
    credentials: 'include',
  })
  return readEnvelope<boolean>(res, '取消任务')
}

/** 重试任务（失败/取消/中断可重试） */
export async function retryKnowledgeTask(
  opts: BaseOpts & { taskId: number },
): Promise<KnowledgeTask> {
  const res = await fetch(`${base(opts)}/tasks/${encodeURIComponent(String(opts.taskId))}/retry`, {
    method: 'POST',
    credentials: 'include',
  })
  return readEnvelope<KnowledgeTask>(res, '重试任务')
}

/** 只读问答 */
export async function askKnowledge(
  opts: BaseOpts & { question: string },
): Promise<KnowledgeQaMessage> {
  const res = await fetch(`${base(opts)}/qa`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'Content-Type': 'application/json; charset=UTF-8' },
    body: JSON.stringify({ question: opts.question }),
  })
  return readEnvelope<KnowledgeQaMessage>(res, '提问')
}

/** 问答历史 */
export async function loadKnowledgeQaHistory(opts: BaseOpts): Promise<KnowledgeQaMessage[]> {
  const res = await fetch(`${base(opts)}/qa/history`, { method: 'GET', credentials: 'include' })
  const data = await readEnvelope<KnowledgeQaMessage[]>(res, '读取问答历史')
  return data ?? []
}

/** 清空问答历史 */
export async function clearKnowledgeQa(opts: BaseOpts): Promise<boolean> {
  const res = await fetch(`${base(opts)}/qa/clear`, { method: 'POST', credentials: 'include' })
  return readEnvelope<boolean>(res, '清空历史')
}
