<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import {
  Button as AButton,
  Modal as AModal,
  Checkbox as ACheckbox,
  Input as AInput,
  Select as ASelect,
  Alert as AAlert,
  Tag as ATag,
} from 'ant-design-vue'
import { UserLoginStore } from '@/stores/UserLogin'
import {
  activeApproval,
  activeScan,
  agentDirectories,
  attemptAllowed,
  cancelApproval,
  cancelInstallJob,
  cancelScan,
  confirmPlan,
  createPlan,
  createScan,
  currentInstallJob,
  currentPlan,
  decideApproval,
  decideInstall,
  executionAllowed,
  installJobByRequest,
  installJobTerminal,
  loadInstallJob,
  stopRun,
  loadApproval,
  loadConfiguration,
  loadInspection,
  loadPlan,
  loadScan,
  retryRun,
  saveConfiguration,
  selectableCandidates,
  startRun,
  StartupError,
  currentPreparation,
  loadExecutionLog,
  type InspectionRecord,
  type ManualService,
  type StartupApproval,
  type StartupConfiguration,
  type StartupInstallJob,
  type StartupPlan,
  type StartupPreparation,
  type StartupScan,
  type StartupExecutionLog,
} from '@/features/project-import/startup'
const props = defineProps<{ appId: string }>()
const user = UserLoginStore()
const ownerId = computed(() => String(user.userLogin?.id ?? ''))
const open = ref(false),
  loading = ref(false),
  working = ref(false),
  error = ref(''),
  notice = ref(''),
  details = ref<string>()
const configuration = ref<StartupConfiguration>(),
  scope = ref('.'),
  scan = ref<StartupScan>(),
  record = ref<InspectionRecord>(),
  selected = ref<string[]>([])
const followingScan = ref(false),
  followingApproval = ref(false)
const approval = ref<StartupApproval>(),
  agentDirectory = ref<string>(),
  plan = ref<StartupPlan>(),
  acknowledged = ref(false),
  preparation = ref<StartupPreparation>(),
  installPrompt = ref(false)
const executionLog = ref<StartupExecutionLog>(),
  logBusy = ref(false)
const installJob = ref<StartupInstallJob>(),
  followingInstall = ref(false),
  installNotice = ref(''),
  installUnknown = ref(false)
const pendingInstall = ref<{
    requestId: string
    planId: string
    planRevision: string
    approved: boolean
  }>(),
  installNotReceived = ref(false)
const manual = ref(false),
  manualName = ref(''),
  manualDirectory = ref('.'),
  manualCommand = ref(''),
  manualInstall = ref(''),
  manualBuild = ref(''),
  manualPort = ref(''),
  editing = ref<string | null>(null),
  manualError = ref('')
const scanLabels: Record<string, string> = {
  QUEUED: '排队中',
  SCANNING: '识别中',
  CANCELLING: '取消中',
  CANCELLED: '已取消',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  STALE: '已过期',
  STOP_FAILED: '停止未确认',
  RECOVERY_REQUIRED: '待恢复',
}
const scopeLabels: Record<string, string> = {
  IDENTIFIED: '已识别',
  UNIDENTIFIED: '未识别',
  INCOMPLETE: '不完整',
  FAILED: '失败',
  AMBIGUOUS: '有歧义',
}
// STATIC 指静态规则分析，不是静态网页入口。
const sourceLabels: Record<string, string> = {
  STATIC: '规则识别',
  DECLARED: '工程声明',
  MANUAL: '手动',
  AGENT: 'Agent 推断',
}
const reasonLabels: Record<string, string> = {
  MULTI_SCRIPT_RUNNER: '并行多个脚本',
  WORKSPACE_RUNNER: '工作区批量运行',
  BACKGROUND_PROCESS: '后台进程',
  DIRECTORY_CHANGE: '切换目录',
  COMPOSE: 'Compose 编排',
  MAVEN_REACTOR: 'Maven 多模块',
  EXPANSION_LIMIT: '展开超过上限',
  REFERENCES_UNSELECTED: '引用未选服务',
}
const approvalLabels: Record<string, string> = {
  APPROVED: '等待执行',
  RUNNING: '识别中',
  CANCELLING: '取消中',
  RECOVERY_REQUIRED: '待恢复',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
  STALE: '已失效',
  REJECTED: '已拒绝',
}
const planLabels: Record<string, string> = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  STALE: '已失效',
  SUPERSEDED: '已替代',
}
const staleLabels: Record<string, string> = {
  INSPECTION_STALE: '识别结果已过期',
  SELECTION_UNAVAILABLE: '所选服务已不存在',
  PLAN_CHANGED: '计划内容已变化',
  IMPORT_TASK_CHANGED: '导入内容已变化',
  PERMISSION_CHANGED: '权限已变化',
}
const context = () => ({ appId: props.appId, ownerId: ownerId.value })
const candidates = computed(() => selectableCandidates(scan.value, record.value))
const unknownDirectories = computed(() => agentDirectories(record.value))
const diagnostics = computed(() =>
  record.value?.status === 'SUCCEEDED'
    ? (record.value.inspection?.result.scopes ?? []).filter(
        (row) => row.status !== 'IDENTIFIED' || row.phase === 'AGENT',
      )
    : [],
)
// 轮询中断（网络错误）后不锁死界面，可重新识别或重新打开读取真实状态。
const scanning = computed(
  () => followingScan.value && !!scan.value && activeScan.includes(scan.value.status),
)
const approving = computed(
  () =>
    followingApproval.value && !!approval.value && activeApproval.includes(approval.value.state),
)
const busy = computed(() => loading.value || working.value || scanning.value || approving.value)
// 安装未到服务端终态就不算完成；期间禁止重复安装和运行。
const installing = computed(
  () => installUnknown.value || (!!installJob.value && !installJobTerminal(installJob.value.state)),
)
const installStage = computed(() => {
  const stages = installJob.value?.stages ?? []
  for (let i = stages.length - 1; i >= 0; i--) {
    const stage = stages[i]
    if (stage && stage.status !== 'NOT_STARTED') return stage.stage
  }
  return undefined
})
const directories = computed(() =>
  [
    ...new Set([
      '.',
      scope.value,
      ...(record.value?.inspection?.result.scopes ?? []).map((row) => row.directory),
      ...candidates.value.map((row) => row.directory),
      ...(configuration.value?.services ?? []).map((row) => row.directory),
    ]),
  ].map((value) => ({ value, label: value === '.' ? '整个工程' : value })),
)
// 已确认后可以发起安装或运行。服务端拒绝时按钮仍在，但不会显示已启动。
const attempt = computed(() => attemptAllowed(plan.value) && !busy.value && !installing.value)
const runnable = computed(() =>
  executionAllowed(plan.value, preparation.value?.runPermitted === true),
)
const aggregates = computed(() => plan.value?.plan.aggregates ?? [])
function serviceName(id: string) {
  const key = id.replace(/^service:/, '')
  return plan.value?.plan.available.find((row) => row.id === key)?.name ?? key
}
const canConfirm = computed(
  () =>
    plan.value?.state === 'DRAFT' &&
    (!aggregates.value.length || acknowledged.value) &&
    !busy.value,
)
const storageKey = computed(() => `project-import-startup-approval:${ownerId.value}:${props.appId}`)
const installStorageKey = computed(
  () => 'project-import-install-request:' + ownerId.value + ':' + props.appId,
)
let generation = 0
const timers = new Set<ReturnType<typeof setTimeout>>()
// 1. 每次切换应用/账号或卸载都换代，迟到响应不写入当前界面。
function current() {
  const value = generation
  return () => value === generation
}
function delay(ms: number) {
  return new Promise<void>((resolve) => {
    const timer = setTimeout(() => {
      timers.delete(timer)
      resolve()
    }, ms)
    timers.add(timer)
  })
}
function reset() {
  generation++
  for (const timer of timers) clearTimeout(timer)
  timers.clear()
  followingScan.value = followingApproval.value = false
  configuration.value =
    scan.value =
    record.value =
    approval.value =
    plan.value =
    preparation.value =
      undefined
  selected.value = []
  executionLog.value = undefined
  error.value = notice.value = ''
  acknowledged.value = false
  installPrompt.value = false
  scope.value = '.'
  loading.value = working.value = logBusy.value = false
  installJob.value = undefined
  followingInstall.value = false
  installNotice.value = ''
  installUnknown.value = false
  pendingInstall.value = undefined
  installNotReceived.value = false
}
function fail(cause: unknown) {
  if ((cause as Error)?.name !== 'AbortError')
    error.value = cause instanceof Error ? cause.message : '操作失败，请重试。'
}
async function guarded(action: (alive: () => boolean) => Promise<void>) {
  if (working.value) return
  const alive = current()
  working.value = true
  error.value = ''
  notice.value = ''
  try {
    await action(alive)
  } catch (cause) {
    if (alive()) fail(cause)
  } finally {
    if (alive()) working.value = false
  }
}
async function openPanel() {
  open.value = true
  if (loading.value) return
  const alive = current()
  loading.value = true
  error.value = ''
  try {
    const pending = sessionStorage.getItem(installStorageKey.value)
    if (pending) {
      installUnknown.value = true
      let requestId = pending
      try {
        const saved = JSON.parse(pending)
        if (typeof saved.requestId === 'string' && typeof saved.approved === 'boolean') {
          pendingInstall.value = saved
          requestId = saved.requestId
        }
      } catch {
        /* 兼容已保存的旧请求身份，只查询、不猜测原安装决定。 */
      }
      try {
        const restored = await installJobByRequest(context(), requestId)
        if (!alive()) return
        installJob.value = restored
        installUnknown.value = false
      } catch {
        if (alive()) installNotice.value = '受理状态尚未确认，请刷新后查看。'
      }
    }
    configuration.value = await loadConfiguration(context())
    if (!alive()) return
    scope.value = configuration.value.scope || '.'
    const latest = await currentPlan(context())
    if (!alive()) return
    plan.value = latest ?? undefined
    if (latest && ['CONFIRMED', 'STALE'].includes(latest.state)) {
      try {
        preparation.value = (await currentPreparation(context(), latest)) ?? undefined
      } catch (cause) {
        if (cause instanceof StartupError && cause.code === 40400) preparation.value = undefined
        else throw cause
      }
      // 页面刷新后恢复该计划的安装任务；只读任务登记，不重新发起安装。
      try {
        const restored = await currentInstallJob(context(), latest)
        if (
          alive() &&
          restored &&
          (!pendingInstall.value || restored.requestId === pendingInstall.value.requestId)
        ) {
          installJob.value = restored
          installUnknown.value = false
        }
      } catch (cause) {
        if (alive() && (cause as Error).name !== 'AbortError') {
          installNotice.value = '安装状态暂时无法读取，可重新打开面板重试。'
          installUnknown.value = true
        }
      }
    }
    // 2. 有效计划所依据的识别重新读取；识别失效时不恢复候选。
    if (latest && ['DRAFT', 'CONFIRMED'].includes(latest.state)) {
      const [restoredScan, restored] = await Promise.all([
        loadScan(context(), latest.scanId),
        loadInspection(context(), latest.scanId),
      ])
      if (!alive()) return
      scan.value = restoredScan
      record.value = restored
      selected.value = latest.plan.services.map((row) => row.id)
    }
    const stored = sessionStorage.getItem(storageKey.value)
    if (stored) {
      try {
        approval.value = await loadApproval(context(), stored)
        if (alive())
          followApproval(alive).catch((cause) => {
            if (alive()) fail(cause)
          })
      } catch (cause) {
        if (cause instanceof StartupError && cause.code === 40400)
          sessionStorage.removeItem(storageKey.value)
        else throw cause
      }
    }
  } catch (cause) {
    if (alive()) fail(cause)
  } finally {
    if (alive()) loading.value = false
  }
  if (alive() && installJob.value && !installUnknown.value)
    void followInstall(alive).catch((cause) => {
      if (alive()) fail(cause)
    })
}
/** 受理未知先只读找回；明确未受理后由用户按原请求身份提交。 */
function recoverInstall(resubmit = false) {
  return guarded(async (alive) => {
    const saved = pendingInstall.value
    if (!saved) return
    let job: StartupInstallJob
    try {
      job = await installJobByRequest(context(), saved.requestId)
    } catch (cause) {
      if (!(cause instanceof StartupError) || cause.code !== 40400) throw cause
      if (!resubmit) {
        if (alive()) {
          installNotReceived.value = true
          installNotice.value = '未查到任务，可按原请求重新提交。'
        }
        return
      }
      const value = plan.value
      if (!value || value.planId !== saved.planId || value.planRevision !== saved.planRevision)
        throw new Error('原计划已变化，不能重新提交。')
      job = await decideInstall(context(), value, saved.approved, saved.requestId)
    }
    if (!alive()) return
    installJob.value = job
    installUnknown.value = false
    installNotReceived.value = false
    working.value = false
    await followInstall(alive)
  })
}
function scanMessage(value: StartupScan) {
  if (value.status === 'CANCELLED') notice.value = '已取消识别，未产生候选。'
  else if (value.status === 'FAILED')
    error.value = `识别失败${value.error ? `：${value.error}` : ''}。`
  else if (value.status === 'STALE') error.value = '识别结果已过期，请重新识别。'
  else if (['STOP_FAILED', 'RECOVERY_REQUIRED'].includes(value.status))
    error.value = '识别停止或清理未确认，暂不能使用结果。'
}
async function followScan(alive: () => boolean) {
  // 3. 只轮询真实状态；停止未确认不当成已取消，也不给出候选。
  followingScan.value = true
  try {
    while (
      alive() &&
      scan.value &&
      ['QUEUED', 'SCANNING', 'CANCELLING'].includes(scan.value.status)
    ) {
      await delay(800)
      if (!alive()) return
      scan.value = await loadScan(context(), scan.value.scanId)
    }
  } finally {
    if (alive()) followingScan.value = false
  }
  if (!alive() || !scan.value) return
  if (scan.value.status === 'SUCCEEDED') {
    record.value = await loadInspection(context(), scan.value.scanId)
    if (alive() && record.value.status !== 'SUCCEEDED') error.value = '识别结果已过期，请重新识别。'
  } else scanMessage(scan.value)
}
function inspect() {
  return guarded(async (alive) => {
    if (!configuration.value) configuration.value = await loadConfiguration(context())
    if (!alive()) return
    record.value = undefined
    selected.value = []
    plan.value = undefined
    acknowledged.value = false
    scan.value = await createScan(context(), configuration.value, scope.value, crypto.randomUUID())
    working.value = false
    await followScan(alive)
  })
}
async function stopScan() {
  const value = scan.value
  if (!value) return
  const alive = current()
  try {
    scan.value = await cancelScan(context(), value)
  } catch (cause) {
    if (alive()) fail(cause)
  }
}
async function followApproval(alive: () => boolean) {
  followingApproval.value = true
  try {
    while (alive() && approval.value && activeApproval.includes(approval.value.state)) {
      await delay(1500)
      if (!alive()) return
      approval.value = await loadApproval(context(), approval.value.approvalId)
    }
  } finally {
    if (alive()) followingApproval.value = false
  }
  const value = approval.value
  if (!alive() || !value) return
  // 4. 只有经后端复核发布的补充才通过重新读取识别出现；失败、取消、过期都不产生候选。
  if (value.state === 'SUCCEEDED' && scan.value?.scanId === value.scanId) {
    record.value = await loadInspection(context(), value.scanId)
    if (alive()) notice.value = 'Agent 识别完成，补充结果需确认后才能纳入计划。'
  } else if (value.state === 'STALE')
    error.value =
      value.error === 'APPROVAL_EXPIRED'
        ? 'Agent 识别批准已过期，未产生候选。'
        : 'Agent 识别结果已失效，未产生候选。'
  else if (value.state === 'FAILED') error.value = 'Agent 识别未成功，未产生候选。'
  else if (value.state === 'CANCELLED') notice.value = '已取消 Agent 识别，未产生候选。'
}
function decide(approved: boolean) {
  const directory = agentDirectory.value,
    scanId = scan.value?.scanId
  agentDirectory.value = undefined
  if (!directory || !scanId) return
  return guarded(async (alive) => {
    approval.value = await decideApproval(
      context(),
      scanId,
      [directory],
      approved,
      crypto.randomUUID(),
    )
    if (!alive()) return
    if (!approved) {
      notice.value = `已拒绝 Agent 识别 ${directory}，未产生候选。`
      return
    }
    sessionStorage.setItem(storageKey.value, approval.value.approvalId)
    working.value = false
    await followApproval(alive)
  })
}
async function stopApproval() {
  const value = approval.value
  if (!value) return
  const alive = current()
  try {
    approval.value = await cancelApproval(context(), value)
  } catch (cause) {
    if (alive()) fail(cause)
  }
}
function editService(row?: ManualService) {
  editing.value = row?.id ?? null
  manualName.value = row?.name ?? ''
  manualDirectory.value = row?.directory ?? scope.value
  manualCommand.value = row?.command ?? ''
  manualInstall.value = row?.install ?? ''
  manualBuild.value = row?.build ?? ''
  manualPort.value = row?.port == null ? '' : String(row.port)
  manualError.value = ''
  manual.value = true
}
function saveManual() {
  manualError.value = ''
  if (!manualName.value.trim() || !manualCommand.value.trim() || !manualDirectory.value.trim()) {
    manualError.value = '填写名称、目录和启动命令。'
    return
  }
  const rawPort = manualPort.value.trim()
  let port: number | null = null
  if (rawPort) {
    if (!/^[1-9]\d{0,4}$/.test(rawPort) || Number(rawPort) > 65535) {
      manualError.value = '端口须是 1 到 65535 的整数。不填表示不提供访问地址。'
      return
    }
    port = Number(rawPort)
  }
  const value = configuration.value
  if (!value) return
  const row: ManualService = {
    id: editing.value,
    name: manualName.value.trim(),
    directory: manualDirectory.value.trim(),
    command: manualCommand.value.trim(),
    install: manualInstall.value.trim() || null,
    build: manualBuild.value.trim() || null,
    port,
  }
  const services = editing.value
    ? value.services.map((item) => (item.id === editing.value ? row : item))
    : [...value.services, row]
  return guarded(async (alive) => {
    const saved = await saveConfiguration(context(), value, services)
    if (!alive()) return
    // 5. 配置版本变化后旧识别和计划都不能继续使用，需重新识别；保存不代表可运行。
    configuration.value = saved
    manual.value = false
    scan.value = record.value = undefined
    selected.value = []
    if (plan.value) {
      const next = await loadPlan(context(), plan.value.planId)
      if (alive()) applyPlan(next)
    }
    if (alive()) notice.value = '已保存手动配置。请重新识别后选择服务。'
  })
}
function applyPlan(next: StartupPlan) {
  plan.value = next
  // 7. 计划因识别、导入内容或权限失效时，旧识别候选一并收起，必须重新识别。
  if (
    next.state === 'STALE' &&
    next.scanId === scan.value?.scanId &&
    ['INSPECTION_STALE', 'IMPORT_TASK_CHANGED', 'PERMISSION_CHANGED'].includes(
      next.staleReason ?? '',
    )
  ) {
    scan.value = record.value = undefined
    selected.value = []
  }
}
function toggle(id: string, checked: boolean) {
  selected.value = checked ? [...selected.value, id] : selected.value.filter((item) => item !== id)
}
function makePlan() {
  const scanId = scan.value?.scanId
  if (!scanId || !selected.value.length) return
  return guarded(async (alive) => {
    const value = await createPlan(context(), scanId, [...selected.value])
    if (alive()) {
      plan.value = value
      acknowledged.value = false
    }
  })
}
function refreshPlan() {
  const value = plan.value
  if (value)
    return guarded(async (alive) => {
      const next = await loadPlan(context(), value.planId)
      if (alive()) applyPlan(next)
    })
}
function confirm() {
  const value = plan.value
  if (!value) return
  return guarded(async (alive) => {
    try {
      const next = await confirmPlan(context(), value, acknowledged.value)
      if (alive()) {
        plan.value = next
        notice.value = '计划已确认。安装只执行已写明的步骤；依赖未核对或执行被拒绝时不会启动服务。'
      }
    } catch (cause) {
      // 6. 版本冲突时重读计划，展示失效原因和差异，不自动改用新计划。
      if (cause instanceof StartupError && cause.code === 40900 && alive()) {
        const next = await loadPlan(context(), value.planId)
        if (alive()) applyPlan(next)
      }
      throw cause
    }
  })
}
const preparationLabels: Record<string, string> = {
  REJECTED: '已拒绝安装',
  NOT_REQUIRED: '无需安装',
  BLOCKED: '依赖未核对',
  REFUSED: '执行被拒绝',
  FAILED: '失败',
  SUCCEEDED: '安装完成',
  INSTALL_DECLINED: '已拒绝安装',
  WAITING_INSTALL: '等待安装确认',
  PARTIAL: '部分失败',
  NONE_READY: '没有服务启动',
  READY: '已就绪',
  STOP_FAILED: '停止失败',
  STOPPED: '已停止',
  NOT_READY: '进程已退出，尚未就绪',
}
function showPreparation(value: StartupPreparation) {
  preparation.value = value
  if (value.servicesStarted)
    notice.value = '部分步骤已执行。失败项可以单独重试，已就绪的服务不会重新启动。'
  else if (value.state === 'WAITING_INSTALL' || value.state === 'REJECTED') {
    installPrompt.value = true
    notice.value = '依赖尚未确认，未启动服务。'
  } else notice.value = preparationLabels[value.state] ?? value.state
  if (value.reason)
    error.value =
      value.state === 'SUCCEEDED' || value.state === 'READY' || value.state === 'NOT_REQUIRED'
        ? ''
        : `${preparationLabels[value.state] ?? value.state}：${preparationLabels[value.reason] ?? value.reason}`
}
function askInstall() {
  if (plan.value) installPrompt.value = true
}
const installLabels: Record<string, string> = {
  PENDING: '已受理',
  PREPARING: '准备中',
  INSTALLING: '安装中',
  VERIFYING: '核对中',
  SUCCEEDED: '完成',
  FAILED: '失败',
  REJECTED: '已拒绝',
  CANCELLING: '正在取消',
  CANCELLED: '已取消',
  STOP_FAILED: '停止未确认',
  RECOVERY_REQUIRED: '待恢复',
}
const installStageLabels: Record<string, string> = {
  PLAN_VALIDATE: '核对计划',
  ADMISSION: '准入检查',
  COPY_PROJECT: '复制工程',
  PREPARE_WORKSPACE: '准备工作区',
  PREPARE_VOLUME: '准备磁盘卷',
  CREATE_CONTAINER: '创建容器',
  START_CONTAINER: '启动容器',
  NPM_INSTALL: '安装前端依赖',
  PIP_INSTALL: '安装 Python 依赖',
  BUILD_PREPARE: '准备构建',
  VERIFY: '核对结果',
  CLEANUP: '清理',
}
/** 提交安装只受理；POST 响应丢失时按同一 requestId 找回任务，不重复提交。 */
function chooseInstall(approved: boolean) {
  installPrompt.value = false
  const value = plan.value
  if (!value) return
  return guarded(async (alive) => {
    const requestId = crypto.randomUUID()
    pendingInstall.value = {
      requestId,
      planId: value.planId,
      planRevision: value.planRevision,
      approved,
    }
    sessionStorage.setItem(installStorageKey.value, JSON.stringify(pendingInstall.value))
    installUnknown.value = true
    installNotReceived.value = false
    let job: StartupInstallJob
    try {
      job = await decideInstall(context(), value, approved, requestId)
    } catch (cause) {
      if (cause instanceof StartupError) {
        try {
          job = await installJobByRequest(context(), requestId)
        } catch {
          if (alive()) installNotice.value = '受理状态尚未确认，请刷新后查看。'
          throw new Error('安装受理状态未知，请刷新查看。')
        }
      } else throw cause
    }
    if (!alive()) return
    installJob.value = job
    installNotice.value = ''
    installUnknown.value = false
    working.value = false
    await followInstall(alive)
  })
}
async function followInstall(alive: () => boolean) {
  followingInstall.value = true
  try {
    // 只按真实任务状态推进；读不到状态时保持未知，不显示安装失败也不重新发起安装。
    while (alive() && installJob.value && !installJobTerminal(installJob.value.state)) {
      await delay(2000)
      if (!alive() || !installJob.value) return
      try {
        installJob.value = await loadInstallJob(context(), installJob.value.jobId)
        installNotice.value = ''
      } catch (cause) {
        if ((cause as Error).name === 'AbortError') throw cause
        installNotice.value = '状态暂时无法读取，正在重试。'
      }
    }
  } finally {
    if (alive()) followingInstall.value = false
  }
  if (!alive() || !installJob.value) return
  installMessage(installJob.value)
}
function installMessage(job: StartupInstallJob) {
  if (installJobTerminal(job.state)) {
    installUnknown.value = false
    pendingInstall.value = undefined
    sessionStorage.removeItem(installStorageKey.value)
  }
  if (job.state === 'SUCCEEDED') {
    notice.value = '安装完成。'
    return
  }
  if (job.state === 'REJECTED') {
    notice.value = '已拒绝安装，未执行任何步骤。'
    return
  }
  if (job.state === 'CANCELLED') {
    notice.value = '安装已取消。'
    return
  }
  if (job.state === 'STOP_FAILED' || job.state === 'RECOVERY_REQUIRED') {
    error.value = '安装停止或资源清理未确认，现场已保留，请稍后重试。'
    return
  }
  if (job.state === 'FAILED')
    error.value = `安装失败${job.failureStage ? `（${installStageLabels[job.failureStage] ?? job.failureStage}）` : ''}${job.failureReason ? `：${job.failureReason}` : ''}。`
}
/** 取消只登记意图；真正停止由后台确认，未确认前不宣称已取消。 */
function cancelInstall() {
  const job = installJob.value
  if (!job || installJobTerminal(job.state)) return
  return guarded(async (alive) => {
    try {
      installJob.value = await cancelInstallJob(context(), job)
    } catch (cause) {
      if (alive()) fail(cause)
    }
  })
}
function launch() {
  const value = plan.value
  if (!value) return
  return guarded(async (alive) => {
    const next = await startRun(context(), value)
    if (alive()) showPreparation(next)
  })
}
function halt() {
  const value = plan.value
  if (!value) return
  return guarded(async (alive) => {
    const next = await stopRun(context(), value)
    if (alive()) showPreparation(next)
  })
}
function retry() {
  const value = plan.value
  if (!value) return
  return guarded(async (alive) => {
    const next = await retryRun(context(), value)
    if (alive()) showPreparation(next)
  })
}
async function showLog(executionId: string) {
  const value = plan.value
  if (!value || logBusy.value) return
  const alive = current()
  logBusy.value = true
  error.value = ''
  try {
    executionLog.value = await loadExecutionLog(context(), value, executionId)
  } catch (cause) {
    if (alive()) fail(cause)
  } finally {
    if (alive()) logBusy.value = false
  }
}
watch(
  () => [props.appId, ownerId.value],
  () => {
    reset()
    if (open.value) void openPanel()
  },
)
onBeforeUnmount(() => {
  generation++
  for (const timer of timers) clearTimeout(timer)
  timers.clear()
})
</script>
<template>
  <div class="startup-actions">
    <a-button :disabled="!attempt" data-testid="startup-install" @click="askInstall"
      >安装依赖</a-button
    >
    <a-button type="primary" data-testid="startup-open" @click="openPanel">运行</a-button>
  </div>
  <a-modal
    v-model:open="open"
    title="运行服务"
    :width="760"
    :footer="null"
    :mask-closable="!busy"
    :keyboard="!busy"
  >
    <div class="startup-content" data-testid="startup-panel">
      <div class="scope">
        <label for="startup-directory">运行目录</label
        ><a-select
          id="startup-directory"
          v-model:value="scope"
          aria-label="运行目录"
          :disabled="busy"
          :options="directories"
        /><a-button
          :disabled="busy || !configuration"
          data-testid="startup-scan"
          @click="inspect"
          >{{ record ? '重新识别' : '识别' }}</a-button
        >
      </div>
      <a-alert v-if="error" type="error" show-icon :message="error" data-testid="startup-error" />
      <a-alert v-if="notice" type="info" :message="notice" data-testid="startup-notice" />
      <div v-if="installUnknown" data-testid="startup-install-unknown">
        <span>{{ installNotice || '安装受理状态未知。' }}</span
        ><a-button
          v-if="pendingInstall"
          :disabled="busy"
          data-testid="startup-install-recover"
          @click="recoverInstall(false)"
          >查询安装</a-button
        ><a-button
          v-if="pendingInstall && installNotReceived"
          :disabled="busy"
          data-testid="startup-install-resubmit"
          @click="recoverInstall(true)"
          >重新提交原请求</a-button
        >
      </div>
      <div v-if="loading" role="status">正在读取…</div>
      <div v-if="scan" class="status-line" data-testid="startup-scan-status">
        <span>识别 · {{ scanLabels[scan.status] ?? scan.status }}</span
        ><a-button v-if="scanning && scan.status !== 'CANCELLING'" size="small" @click="stopScan"
          >取消识别</a-button
        >
      </div>
      <div v-for="row in candidates" :key="row.id" class="service" data-testid="startup-candidate">
        <div class="service-main">
          <a-checkbox
            :checked="selected.includes(row.id)"
            :disabled="busy"
            @change="
              (event: { target: { checked: boolean } }) => toggle(row.id, event.target.checked)
            "
            >{{ row.name }}</a-checkbox
          ><a-tag>{{ sourceLabels[row.source] ?? '规则识别' }}</a-tag>
        </div>
        <div class="command">
          <span>{{ row.directory }}</span
          ><code>{{ row.command || '静态入口，无启动命令' }}</code>
        </div>
        <div class="service-options">
          <a-button
            type="link"
            size="small"
            @click="details = details === row.id ? undefined : row.id"
            >详情</a-button
          >
        </div>
        <dl v-if="details === row.id" class="evidence">
          <dt>依据</dt>
          <dd>{{ row.evidence }}</dd>
          <dt v-if="row.build">构建</dt>
          <dd v-if="row.build">
            <code>{{ row.build }}</code>
          </dd>
        </dl>
      </div>
      <div
        v-for="row in diagnostics"
        :key="`${row.phase}:${row.directory}:${row.diagnostic}`"
        class="unknown"
        data-testid="startup-scope"
      >
        <span
          >{{ row.directory }} · {{ scopeLabels[row.status] ?? row.status
          }}<a-tag v-if="row.phase === 'AGENT'" class="phase">Agent</a-tag
          ><small v-if="row.diagnostic">{{ row.diagnostic }}</small></span
        >
        <div v-if="unknownDirectories.includes(row.directory) && row.phase !== 'AGENT'">
          <a-button :disabled="busy" @click="agentDirectory = row.directory">Agent 识别</a-button
          ><a-button :disabled="busy" @click="editService()">手动配置</a-button>
        </div>
      </div>
      <div v-if="approval" class="status-line" data-testid="startup-approval">
        <span
          >Agent 识别 {{ approval.directories.join('、') }} ·
          {{ approvalLabels[approval.state] ?? approval.state }}</span
        ><a-button
          v-if="approving && approval.state !== 'CANCELLING'"
          size="small"
          @click="stopApproval"
          >取消识别</a-button
        >
      </div>
      <section v-if="plan" class="plan" data-testid="startup-plan">
        <div class="service-main">
          <strong>运行计划</strong
          ><a-tag
            :color="
              plan.state === 'CONFIRMED' ? 'green' : plan.state === 'DRAFT' ? undefined : 'red'
            "
            data-testid="startup-plan-state"
            >{{ planLabels[plan.state] ?? plan.state }}</a-tag
          >
        </div>
        <a-alert
          v-if="plan.staleReason"
          type="warning"
          show-icon
          :message="`计划已失效：${staleLabels[plan.staleReason] ?? plan.staleReason}。原确认不再有效，请重新识别并生成计划。`"
        />
        <div v-for="row in plan.plan.services" :key="row.id" class="command">
          <span>{{ row.name }} · {{ row.directory }}</span
          ><code>{{ row.command || '静态入口' }}</code>
          <code v-for="line in row.expansion.slice(1)" :key="line" class="muted">{{ line }}</code>
          <span
            >就绪：{{ row.readiness.method
            }}{{ row.unknowns.length ? ` · 未知：${row.unknowns.join('、')}` : '' }}</span
          >
        </div>
        <dl class="evidence">
          <template v-for="row in plan.plan.dependencies" :key="row.id"
            ><dt>依赖</dt>
            <dd>
              {{ row.directory }} · {{ row.ecosystem }} · 未检查（{{ row.status }}）
            </dd></template
          >
          <template v-for="row in plan.plan.builds" :key="row.id"
            ><dt>构建</dt>
            <dd>
              <code>{{ row.command }}</code> · {{ row.directory
              }}{{ row.services.length > 1 ? ' · 共享' : '' }}
            </dd></template
          >
        </dl>
        <div v-if="aggregates.length" class="aggregate" data-testid="startup-aggregate">
          <a-alert
            type="warning"
            show-icon
            message="启动脚本可能带起多个服务，确认前请核对展开范围。"
          />
          <span v-for="row in aggregates" :key="row.id"
            >{{ serviceName(row.id) }}：{{
              row.reasons.map((item) => reasonLabels[item] ?? item).join('、')
            }}{{
              row.unselected.length
                ? ` · 将带上未选服务 ${row.unselected.map(serviceName).join('、')}`
                : ''
            }}</span
          >
          <a-checkbox
            v-if="plan.state === 'DRAFT'"
            :checked="acknowledged"
            @change="
              (event: { target: { checked: boolean } }) => (acknowledged = event.target.checked)
            "
            >我已核对展开范围</a-checkbox
          >
        </div>
        <div v-if="plan.diff.length" class="diff" data-testid="startup-diff">
          <strong>与上次确认的差异</strong
          ><code v-for="row in plan.diff" :key="row.path"
            >{{ row.path }}：{{ row.before ?? '无' }} → {{ row.after ?? '无' }}</code
          >
        </div>
        <div v-if="installJob" class="status-line" data-testid="startup-install-job">
          <span
            >安装 · {{ installLabels[installJob.state] ?? installJob.state
            }}<template v-if="installing && installStage">
              · {{ installStageLabels[installStage] ?? installStage }}</template
            ></span
          ><a-button
            v-if="installing && !installJob.cancelRequested"
            size="small"
            data-testid="startup-install-cancel"
            @click="cancelInstall"
            >取消安装</a-button
          >
        </div>
        <a-alert
          v-if="installNotice"
          type="warning"
          show-icon
          :message="installNotice"
          data-testid="startup-install-notice"
        />
        <dl
          v-if="installJob && installJob.stages.length"
          class="evidence"
          data-testid="startup-install-stages"
        >
          <template v-for="(row, index) in installJob.stages" :key="index"
            ><dt>{{ installStageLabels[row.stage] ?? row.stage }}</dt>
            <dd>
              {{ row.status }}{{ row.durationMs !== null ? ` · ${row.durationMs}ms` : ''
              }}{{ row.exitCode !== null ? ` · exit ${row.exitCode}` : ''
              }}{{ row.reason ? ` · ${row.reason}` : '' }}
            </dd></template
          >
        </dl>
        <div v-if="preparation" class="services" data-testid="startup-services">
          <div v-for="row in preparation.services" :key="row.serviceId" class="command">
            <span>{{ row.serviceId }} · {{ preparationLabels[row.state] ?? row.state }}</span
            ><code v-if="row.address">{{ row.address }}</code
            ><span v-else-if="row.state === 'READY'">未声明端口，不提供访问地址</span
            ><span v-if="row.reason">{{ preparationLabels[row.reason] ?? row.reason }}</span
            ><a-button
              v-if="row.executionId"
              type="link"
              size="small"
              :loading="logBusy"
              @click="showLog(row.executionId)"
              >查看日志</a-button
            >
          </div>
        </div>
        <div class="footer">
          <a-button :disabled="busy" @click="refreshPlan">刷新计划</a-button
          ><a-button
            v-if="plan.state === 'DRAFT'"
            type="primary"
            :disabled="!canConfirm"
            data-testid="startup-confirm"
            @click="confirm"
            >确认计划</a-button
          ><a-button
            v-if="preparation?.servicesStarted"
            :disabled="busy"
            data-testid="startup-stop"
            @click="halt"
            >停止</a-button
          ><a-button
            v-if="
              preparation &&
              ['PARTIAL', 'FAILED', 'NONE_READY'].includes(preparation.state) &&
              preparation.reason !== 'STOP_FAILED'
            "
            :disabled="busy"
            data-testid="startup-retry"
            @click="retry"
            >重试失败项</a-button
          >
        </div>
      </section>
      <div class="footer">
        <a-button :disabled="busy || !configuration" @click="editService()">添加服务</a-button
        ><a-button
          :disabled="busy || !selected.length || !candidates.length"
          data-testid="startup-plan-create"
          @click="makePlan"
          >生成运行计划</a-button
        ><a-button type="primary" :disabled="!attempt" data-testid="startup-run" @click="launch"
          >运行所选</a-button
        >
      </div>
      <span class="hint">{{
        runnable
          ? '所选服务已就绪。'
          : '安装只执行计划中写明的步骤，不会因此启动。依赖未核对、拒绝安装或执行被拒绝时，服务不会启动。'
      }}</span>
    </div>
  </a-modal>
  <a-modal
    :open="!!executionLog"
    title="执行日志"
    :footer="null"
    @cancel="executionLog = undefined"
  >
    <pre class="execution-log" data-testid="startup-execution-log">{{ executionLog?.content }}</pre>
  </a-modal>
  <a-modal
    :open="installPrompt"
    :z-index="1100"
    title="是否安装依赖？"
    @cancel="installPrompt = false"
  >
    <p>只执行计划里已经写明的安装步骤，不会启动服务。目录里已有文件不代表依赖就绪。</p>
    <template #footer
      ><a-button data-testid="startup-install-reject" @click="chooseInstall(false)"
        >暂不安装</a-button
      ><a-button type="primary" data-testid="startup-install-approve" @click="chooseInstall(true)"
        >安装依赖</a-button
      ></template
    >
  </a-modal>
  <a-modal
    :open="!!agentDirectory"
    :z-index="1100"
    title="使用 Agent 识别？"
    @cancel="agentDirectory = undefined"
  >
    <p>读取 {{ agentDirectory }} 的源码与说明，发送至当前模型；不读取密钥、不安装或运行。</p>
    <template #footer
      ><a-button data-testid="startup-agent-reject" @click="decide(false)">暂不使用</a-button
      ><a-button type="primary" data-testid="startup-agent-approve" @click="decide(true)"
        >识别</a-button
      ></template
    >
  </a-modal>
  <a-modal
    v-model:open="manual"
    :z-index="1100"
    title="添加服务"
    ok-text="保存"
    cancel-text="取消"
    :confirm-loading="working"
    @ok="saveManual"
  >
    <div class="startup-content">
      <a-alert v-if="manualError" type="error" :message="manualError" show-icon /><label
        >名称<a-input v-model:value="manualName" :maxlength="80" /></label
      ><label>运行目录<a-input v-model:value="manualDirectory" :maxlength="512" /></label
      ><label>启动命令<a-input v-model:value="manualCommand" :maxlength="1024" /></label
      ><label>安装命令<a-input v-model:value="manualInstall" :maxlength="1024" /></label
      ><label>构建命令<a-input v-model:value="manualBuild" :maxlength="1024" /></label
      ><label
        >容器端口<a-input
          v-model:value="manualPort"
          :maxlength="5"
          placeholder="不填则不提供访问地址"
      /></label>
    </div>
  </a-modal>
</template>
<style scoped>
.startup-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.startup-content {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 8px 0;
  min-width: 0;
}
.scope,
.service-main,
.service-options,
.footer,
.unknown,
.status-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.scope :deep(.ant-select) {
  flex: 1;
  min-width: 150px;
}
.service,
.plan {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  background: var(--bg-soft);
  border: 1px solid var(--border-color);
  border-radius: 12px;
  min-width: 0;
}
.command,
.startup-content > label,
.aggregate,
.diff {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}
code {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-size: 13px;
}
.execution-log {
  max-height: 60vh;
  overflow: auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--bg-soft);
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
}
.command span,
.evidence,
.hint,
.muted,
.unknown small {
  color: var(--text-muted);
  font-size: 13px;
}
.evidence {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 8px 16px;
  margin: 0;
}
.evidence dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.unknown {
  padding: 16px 0;
  border-bottom: 1px solid var(--border-color);
}
.unknown > div {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.unknown span {
  overflow-wrap: anywhere;
}
.unknown small {
  margin-left: 8px;
}
.phase {
  margin-left: 8px;
}
.footer {
  padding-top: 4px;
}
@media (max-width: 560px) {
  .service,
  .plan {
    padding: 16px;
  }
  .scope {
    align-items: stretch;
  }
  .scope label {
    width: 100%;
  }
}
</style>
