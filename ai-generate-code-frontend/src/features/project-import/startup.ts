import myAxios from '@/request'

/** 启动识别与冻结计划的正式接口适配；只读识别、批准和计划确认，不提供安装或运行入口。 */
export interface StartupContext { appId: string; ownerId: string; signal?: AbortSignal }
export interface StartupConfiguration { appId: string; importTaskId: string; configRevision: string; contextEpoch: string; scope: string
  services: ManualService[] }
export interface ManualService { id: string | null; name: string; directory: string; command: string; install: string | null; build: string | null; port: number | null }
export interface StartupScan { scanId: string; requestId: string; scope: string; status: string; revision: string; error: string | null; cancelRequested: boolean; cleanup: string }
export interface StartupCandidate { id: string; name: string; directory: string; command: string | null; build: string | null; source: string; evidence: string }
export interface StartupScope { directory: string; phase: string; status: string; diagnostic: string | null }
export interface StartupInspection { scanId: string; importTaskId: string; contentHash: string; scope: string; configRevision: string; contextEpoch: string
  result: { candidates: StartupCandidate[]; scopes: StartupScope[] } }
export interface InspectionRecord { scanId: string; status: string; diagnostic: string | null; inspection: StartupInspection | null }
export interface StartupApproval { approvalId: string; requestId: string; scanId: string; directories: string[]; approved: boolean; state: string
  revision: string; expiresAt: string | null; resultRecorded: boolean; error: string | null; cancelRequested: boolean; cleanup: string | null }
export interface PlanService { id: string; name: string; directory: string; source: string; evidence: string; command: string | null; expansion: string[]
  lifecycle: string[]; build: string | null; dependencies: string[]; readiness: { method: string; detail: string | null }; unknowns: string[] }
export interface PlanContent { contentHash: string; services: PlanService[]
  dependencies: Array<{ id: string; directory: string; ecosystem: string; manager: string | null; declarations: string[]; installs: string[]; status: string; lifecycle: string[]; unknowns: string[]; services: string[] }>
  builds: Array<{ id: string; directory: string; command: string; services: string[] }>; shared: string[]
  aggregates: Array<{ id: string; reasons: string[]; unselected: string[] }>; available: Array<{ id: string; name: string; directory: string; selected: boolean }> }
export interface StartupPlan { planId: string; scanId: string; planRevision: string; state: string; revision: string; aggregateAcknowledged: boolean
  confirmedAt: string | null; executable: boolean; plan: PlanContent; staleReason: string | null; diff: Array<{ path: string; before: string | null; after: string | null }>
  diffBase: string | null }

export const activeScan = ['QUEUED', 'SCANNING', 'CANCELLING', 'STOP_FAILED', 'RECOVERY_REQUIRED']
export const activeApproval = ['APPROVED', 'RUNNING', 'CANCELLING', 'RECOVERY_REQUIRED']
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** 带业务码的错误；409 用于提示重新读取，不自动重试写操作。 */
export class StartupError extends Error {
  constructor(message: string, readonly code: number) { super(message); this.name = 'StartupError' }
}

function guard(context: StartupContext) {
  if (context.signal?.aborted) throw new DOMException('请求已取消', 'AbortError')
  if (!/^[1-9]\d*$/.test(context.appId) || !/^[1-9]\d*$/.test(context.ownerId)) throw new StartupError('请先登录。', 40100)
}
async function call<T>(context: StartupContext, path: string, method = 'GET', data?: unknown, timeout = 30_000): Promise<T> {
  guard(context)
  // 1. 写操作沿用导入模块的会话 CSRF；同源 Origin 由浏览器携带。
  const csrf = method === 'GET' ? undefined : await call<string>(context, '/csrf-token')
  guard(context)
  let body: { code: number; message?: string; data: T }
  try {
    const url = path === '/csrf-token' ? '/project/import/csrf' : `/project/import/apps/${context.appId}/startup${path}`
    body = (await myAxios.request<{ code: number; message?: string; data: T }>({ url, method, data, signal: context.signal, timeout,
      headers: csrf ? { 'X-Project-Import-CSRF': csrf } : {} })).data
  } catch (cause) {
    guard(context)
    const response = (cause as { response?: { data?: { code?: number; message?: string } } }).response?.data
    throw new StartupError(response?.message || '请求失败，请重试。', response?.code ?? 50000)
  }
  guard(context)
  if (body?.code !== 20000) throw new StartupError(body?.message || '操作失败，请重试。', body?.code ?? 50000)
  return body.data
}
const owner = (context: StartupContext) => ({ ownerId: context.ownerId })
const revision = (value: string) => {
  if (!/^(0|[1-9]\d*)$/.test(String(value)) || !Number.isSafeInteger(Number(value))) throw new StartupError('版本无效，请重新读取。', 40000)
  return Number(value)
}
const id = (value: string) => { if (!uuid.test(value)) throw new StartupError('记录无效，请重新读取。', 40000); return value }

/** 只有确认不是导入应用时返回 false。超时、冲突和服务器错误必须抛出，不能退回旧运行按钮。 */
export async function importedApp(context: StartupContext) {
  try {
    await call(context, '/configuration')
    return true
  } catch (cause) {
    if ((cause as Error).name === 'AbortError') throw cause
    // 404 表示没有导入资格；40100 是未登录或编号无效，原入口保持不变。
    if (cause instanceof StartupError && (cause.code === 40400 || cause.code === 40100)) return false
    throw cause
  }
}
export const loadConfiguration = async (context: StartupContext) => call<StartupConfiguration>(context, '/configuration')
export async function saveConfiguration(context: StartupContext, current: StartupConfiguration, services: ManualService[]) {
  return call<StartupConfiguration>(context, '/configuration', 'PUT', { ...owner(context), configRevision: revision(current.configRevision),
    contextEpoch: revision(current.contextEpoch), scope: current.scope, services })
}
export async function createScan(context: StartupContext, current: StartupConfiguration, scope: string, requestId: string) {
  return call<StartupScan>(context, '/scans', 'POST', { ...owner(context), requestId: id(requestId), configRevision: revision(current.configRevision),
    contextEpoch: revision(current.contextEpoch), scope })
}
export const loadScan = async (context: StartupContext, scanId: string) => call<StartupScan>(context, `/scans/${id(scanId)}`)
export const cancelScan = async (context: StartupContext, scan: StartupScan) =>
  call<StartupScan>(context, `/scans/${id(scan.scanId)}/cancel`, 'POST', { ...owner(context), revision: revision(scan.revision) })
export const loadInspection = async (context: StartupContext, scanId: string) => call<InspectionRecord>(context, `/inspections/${id(scanId)}`)
export async function decideApproval(context: StartupContext, scanId: string, directories: string[], approved: boolean, requestId: string) {
  return call<StartupApproval>(context, '/approvals', 'POST', { ...owner(context), requestId: id(requestId), scanId: id(scanId), directories, approved })
}
export const loadApproval = async (context: StartupContext, approvalId: string) => call<StartupApproval>(context, `/approvals/${id(approvalId)}`)
export const cancelApproval = async (context: StartupContext, approval: StartupApproval) =>
  call<StartupApproval>(context, `/approvals/${id(approval.approvalId)}/cancel`, 'POST', { ...owner(context), revision: revision(approval.revision) })
export const createPlan = async (context: StartupContext, scanId: string, serviceIds: string[]) =>
  call<StartupPlan>(context, '/plans', 'POST', { ...owner(context), scanId: id(scanId), serviceIds })
export const currentPlan = async (context: StartupContext) => call<StartupPlan | null>(context, '/plans/current')
export const loadPlan = async (context: StartupContext, planId: string) => call<StartupPlan>(context, `/plans/${id(planId)}`)
export const confirmPlan = async (context: StartupContext, plan: StartupPlan, aggregateAcknowledged: boolean) => {
  if (!/^[0-9a-f]{64}$/.test(plan.planRevision)) throw new StartupError('计划版本无效，请重新生成。', 40000)
  return call<StartupPlan>(context, `/plans/${id(plan.planId)}/confirm`, 'POST', { ...owner(context), planRevision: plan.planRevision, aggregateAcknowledged })
}
export interface StartupPreparation { preparationId: string; planId: string; planRevision: string; kind: string; decision: string; state: string
  reason: string | null; runPermitted: boolean; servicesStarted: boolean
  dependencies: Array<{ id: string; directory: string; status: string; reason: string }>
  services: Array<{ serviceId: string; phase: string; state: string; reason: string | null; executionId: string | null; address: string | null }> }
export interface StartupExecution { id: string; ownerId: string; appId: string; planId: string; planRevision: string; contentHash: string; directory: string
  slot: string; network: string; state: string; reason: string | null; exitCode: number | null; outputBytes: number; cleanup: string; revision: number
  createdAt: string; updatedAt: string; finishedAt: string | null; containerId: string | null }
export interface StartupExecutionLog { executionId: string; state: string; content: string; truncated: boolean }
/** 安装任务阶段摘要；脱敏，只含固定类型、状态和耗时。 */
export interface StartupInstallStage { stage: string; commandType: string; status: string; reason: string | null
  exitCode: number | null; startTime: string | null; endTime: string | null; durationMs: number | null; executionId: string | null }
export interface StartupPreparationSummary { preparationId: string; kind: string; decision: string; state: string; reason: string | null }
/** 安装任务：成败只由服务端终态决定，HTTP 200、计时结束或容器启动都不算成功。 */
export interface StartupInstallJob { jobId: string; requestId: string; appId: string; planId: string; planRevision: string
  state: string; revision: string; cancelRequested: boolean; failureStage: string | null; failureReason: string | null
  startedAt: string | null; finishedAt: string | null; executionIds: string[]; stages: StartupInstallStage[]
  preparationId: string | null; cleanup: string; preparation: StartupPreparationSummary | null }

/** 只有这些状态算安装完成或失败。 */
export const installJobTerminal = (state: string) => ['SUCCEEDED', 'FAILED', 'REJECTED', 'CANCELLED'].includes(state)
/** 提交安装只受理，返回任务身份；安装结果靠轮询任务得到。requestId 由调用方生成并复用。 */
export async function decideInstall(context: StartupContext, plan: StartupPlan, approved: boolean, requestId: string) {
  return call<StartupInstallJob>(context, '/preparations/install', 'POST',
    { ...owner(context), requestId: id(requestId), planId: id(plan.planId), planRevision: plan.planRevision, approved }, 120_000)
}
export const loadInstallJob = async (context: StartupContext, jobId: string) =>
  call<StartupInstallJob>(context, `/install-jobs/${id(jobId)}`)
export const currentInstallJob = async (context: StartupContext, plan: StartupPlan) =>
  call<StartupInstallJob | null>(context, `/install-jobs/current?planId=${encodeURIComponent(id(plan.planId))}`)
export const installJobByRequest = async (context: StartupContext, requestId: string) =>
  call<StartupInstallJob>(context, `/install-jobs/by-request/${id(requestId)}`)
export const cancelInstallJob = async (context: StartupContext, job: StartupInstallJob) =>
  call<StartupInstallJob>(context, `/install-jobs/${id(job.jobId)}/cancel`, 'POST', { ...owner(context), revision: revision(job.revision) })
export async function startRun(context: StartupContext, plan: StartupPlan) {
  return call<StartupPreparation>(context, '/preparations/run', 'POST', { ...owner(context), planId: id(plan.planId), planRevision: plan.planRevision }, 120_000)
}
export async function stopRun(context: StartupContext, plan: StartupPlan) {
  return call<StartupPreparation>(context, '/preparations/stop', 'POST', { ...owner(context), planId: id(plan.planId), planRevision: plan.planRevision }, 120_000)
}
export async function retryRun(context: StartupContext, plan: StartupPlan) {
  return call<StartupPreparation>(context, '/preparations/retry', 'POST', { ...owner(context), planId: id(plan.planId), planRevision: plan.planRevision }, 120_000)
}
export const currentPreparation = async (context: StartupContext, plan: StartupPlan) =>
  call<StartupPreparation | null>(context, `/preparations/current?planId=${encodeURIComponent(id(plan.planId))}`)
export const loadExecution = async (context: StartupContext, plan: StartupPlan, executionId: string) =>
  call<StartupExecution>(context, `/executions/${id(executionId)}?planId=${encodeURIComponent(id(plan.planId))}`)
export const loadExecutionLog = async (context: StartupContext, plan: StartupPlan, executionId: string, maxBytes = 262144) =>
  call<StartupExecutionLog>(context, `/executions/${id(executionId)}/log?planId=${encodeURIComponent(id(plan.planId))}&maxBytes=${maxBytes}`)
export const stopExecution = async (context: StartupContext, plan: StartupPlan, executionId: string) =>
  call<StartupExecution>(context, `/executions/${id(executionId)}/stop`, 'POST', { ...owner(context), planId: id(plan.planId) })
export const reconcilePreparations = async (context: StartupContext, plan: StartupPlan) =>
  call<{ rows: number; containers: number; files: number; unresolved: string[] }>(context, '/preparations/reconcile', 'POST', { ...owner(context), planId: id(plan.planId) })

/** 可选服务只来自当前有效识别；失败、取消、过期或 STALE 的识别不给出任何候选。 */
export function selectableCandidates(scan: StartupScan | undefined, record: InspectionRecord | undefined) {
  if (scan?.status !== 'SUCCEEDED' || record?.status !== 'SUCCEEDED' || !record.inspection || record.scanId !== scan.scanId) return []
  return record.inspection.result.candidates
}
/** 仅原规则阶段未识别、且没有 Agent 阶段结果的目录可申请 Agent 识别；失败/不完整/歧义不转 Agent。 */
export function agentDirectories(record: InspectionRecord | undefined) {
  const scopes = record?.status === 'SUCCEEDED' ? record.inspection?.result.scopes ?? [] : []
  const settled = new Set(scopes.filter(scope => scope.phase === 'AGENT').map(scope => scope.directory))
  return [...new Set(scopes.filter(scope => scope.phase !== 'AGENT' && scope.status === 'UNIDENTIFIED' && !settled.has(scope.directory)).map(scope => scope.directory))]
}
/** 已确认计划可以发起安装或运行请求。真正启动仍由服务端准入决定，未核对依赖和内存上限无法保证时不会启动。 */
export function attemptAllowed(plan: StartupPlan | undefined) {
  return !!plan && plan.state === 'CONFIRMED'
}
/** 安装/运行是否已经获准执行：必须已确认、服务端声明可执行且隔离授权已接入。当前执行结果未就绪时为 false。 */
export function executionAllowed(plan: StartupPlan | undefined, isolationReady = false) {
  return !!plan && plan.state === 'CONFIRMED' && plan.executable === true && isolationReady
}
