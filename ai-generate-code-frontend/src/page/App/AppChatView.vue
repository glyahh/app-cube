<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, computed, nextTick, watch, defineAsyncComponent } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  message,
  Button as AButton,
  Input as AInput,
  Spin as ASpin,
  Empty as AEmpty,
  Modal as AModal,
  Tooltip as ATooltip,
  Alert as AAlert,
  Textarea as ATextarea,
  Checkbox as ACheckbox,
  CheckboxGroup as ACheckboxGroup,
} from 'ant-design-vue'
import type { AppVO, ChatHistory } from '@/api'
import { appGetVoUsingGet, appApplyUsingPost } from '@/api/appController'
import {
  chatHistoryAppAppIdUsingGet,
  chatHistoryOpenApiExportAppIdUsingGet,
  chatHistoryRoundCountAppIdUsingGet,
} from '@/api/chatHistoryController'
import { UserLoginStore } from '@/stores/UserLogin'
import { useExecutionPreference } from '@/stores/executionPreference'
import { useAppearanceStore } from '@/stores/appearance'
import {
  parseMarkdownWithCode,
  findLineAlignedClosingFenceInBuffer,
  normalizeFenceCodeContent,
} from '@/utils/markdownParser'
import type { WorkflowHistoryOutcome, WorkflowStepRow } from '@/utils/workflowChatFilters'
import {
  filterAssistantSseChunkForUi,
  flushAssistantSseCarry,
  mergeWorkflowSteps,
  normalizeWorkflowStepsForUi,
  resetSseLineAccumulator,
  resolveWorkflowHistoryOutcomeFromContent,
  resolveWorkflowStepsFromMessageContent,
  stripAssistantNoiseLines,
} from '@/utils/workflowChatFilters'
import {
  extractToolModifyFileNewContentBlocksFromText,
  extractToolWriteFileBlocksFromText,
} from '@/utils/toolOutputAdapters/toolOutputBlockParsers'
import { loadProjectFilesEchoFromDisk, filterLockFiles } from '@/utils/projectFilesEcho'
import {
  loadAppWorkspace,
  updateDashboardFeatures,
  type AppWorkspaceInfo,
} from '@/utils/appWorkspace'
import {
  buildRuntimePreviewUrl,
  dismissIncident,
  loadOpenIncidents,
  loadRuntimeStatus,
  prepareIncidentRepair,
  sendRuntimeHeartbeat,
  startRuntime,
  stopRuntime,
  type RuntimeIncident,
  type RuntimeStatus,
} from '@/utils/appRuntimeApi'
import {
  calculateSplitRatioWithOffset,
  clampRatio,
  computeGridColumns,
  isChatHidden,
  isPreviewHidden,
  isSplitterVisible,
  isLeftRestoreTriggerVisible,
  isRightRestoreTriggerVisible,
  SPLITTER_TRACK,
} from '@/utils/splitPane'
import CodeBlock from '@/components/CodeBlock.vue'
import ProjectFilesModal from '@/components/app/ProjectFilesModal.vue'
import RuntimeMetricsPanel from '@/components/app/RuntimeMetricsPanel.vue'
import RuntimeLogsPanel from '@/components/app/RuntimeLogsPanel.vue'
import KnowledgePanel from '@/components/app/KnowledgePanel.vue'
import '@/components/app/workbench.css'
import ToolExitHint from '@/components/ToolExitHint.vue'
import ToolRequestHint from '@/components/ToolRequestHint.vue'
import ToolExecCardHeader from '@/components/ToolExecCardHeader.vue'
import CollapsibleCodeBlock from '@/components/CollapsibleCodeBlock.vue'
import {
  getToolExecHeaderTargetPath,
  stripToolCallPrefix,
} from '@/utils/toolExecDisplay'
import {
  isToolWaitStatusTool,
  resolveToolWaitStatus,
  generateTier2Threshold,
  type ToolWaitStatusView,
} from '@/utils/toolWaitStatus'
import { CodeGenTypeEnum } from '@/utils/CodeGenTypeEnum'
import AppLoopInjectBar from '@/components/loop/AppLoopInjectBar.vue'
import { loopGetVoById } from '@/api/longIdApi'
import {
  attachVisualWebsiteEditor,
  formatSelectedElementForPrompt,
  type VisualSelectedElementInfo,
} from '@/utils/visualWebsiteEditor'
import { buildDeployUrlFromKey } from '@/utils/deployUrl'
import {
  injectGenerationStatusSegments,
  type GenerationStatusSegmentInput,
} from '@/utils/chatGenerationStatus'
import {
  getVisualEditCardTitle,
  getVisualEditFieldRows,
  splitUserMessageForDisplay,
  type ParsedVisualEditSelection,
} from '@/utils/visualEditMessageParser'
import { createStreamRenderScheduler } from '@/utils/streamRenderScheduler'
import { IncrementalMarkdownParser } from '@/utils/incrementalMarkdownParser'
import { openAgentStream } from '@/utils/agentStreamTransport'
import { mapAgentEvent, type AgentUiAction } from '@/utils/agentEventMapping'
import '@/assets/stream-layout.css'
import { scrollElementToEnd } from '@/utils/scrollElement'
import { renderSafeMarkdown } from '@/utils/safeMarkdown'
import { importedApp } from '@/features/project-import/startup'
import ImportedAgentControls from '@/features/project-import/agent-permissions/ImportedAgentControls.vue'
import ExecutionProcess from '@/components/ExecutionProcess.vue'
import ExecutionTerminal from '@/components/ExecutionTerminal.vue'
import { cleanExecutionText } from '@/utils/executionDisplay'
import ThinkingStage from '@/components/ThinkingStage.vue'
import { parseThinkingSegments, mergeThinkingAction, type ThinkingSegment } from '@/utils/thinkingSegments'

const props = defineProps<{ importedProject?: boolean }>()

// 与全局请求保持一致：默认走当前页面 origin 下的 `/api`
const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? '/api'
const PREVIEW_BASE_URL =
  import.meta.env.VITE_PREVIEW_BASE_URL ?? `${API_BASE_URL.replace(/\/$/, '')}/static`

const route = useRoute()
const router = useRouter()
const userLoginStore = UserLoginStore()
const appearanceStore = useAppearanceStore()
/** 保留原始索引，工具状态查询仍对应原来的事件序列。 */
function displaySegments(m: ChatMessage) {
  const active = isStreamActiveForMessage(m)
  const clean = (text: string) => cleanExecutionText(text, active)
  return getMessageUiSegments(m).map((source, idx) => {
    let segment = source
    if (source.kind === 'thinking') {
      segment = { ...source, summary: clean(source.summary), details: source.details.map(clean).filter(text => text.trim()) }
    } else if (source.kind === 'markdown') {
      segment = { ...source, content: clean(source.content) }
    } else if (source.kind === 'tool_request') {
      const isExec = source.agentToolName === 'execute'
      segment = { ...source, toolName: clean(source.toolName), targetPath: cleanExecutionText(source.targetPath ?? '', active, isExec) }
    } else if (source.kind === 'tool_executed_simple_tool') {
      const isExec = source.agentToolName === 'execute'
      segment = { ...source, toolTitle: clean(source.toolTitle), filePath: cleanExecutionText(source.filePath, active, isExec), message: cleanExecutionText(source.message, active, isExec) }
    } else if (source.kind === 'tool_executed_write_file') {
      segment = { ...source, filePath: clean(source.filePath), content: clean(source.content) }
    } else if (source.kind === 'tool_executed_modify_file') {
      segment = { ...source, filePath: clean(source.filePath), beforeContent: clean(source.beforeContent), afterContent: clean(source.afterContent) }
    } else if (source.kind === 'generation_status') {
      segment = { ...source, message: clean(source.message) }
    }
    return { segment, idx }
  }).filter(({ segment }) => segment.kind === 'thinking'
    ? !!(segment.summary.trim() || segment.details.length)
    : segment.kind !== 'markdown' || !!segment.content.trim())
}

function isExecutionSegment(segment: UiSegment): boolean {
  return segment.kind === 'thinking' || segment.kind === 'tool_request' || isToolExecutedUiSegmentKind(segment.kind)
}

function executionSegments(m: ChatMessage) {
  return displaySegments(m).filter(({ segment }) => isExecutionSegment(segment))
}

function answerSegments(m: ChatMessage) {
  return displaySegments(m).filter(({ segment }) => !isExecutionSegment(segment))
}

function terminalStatus(segment: UiToolRequestSegment | UiToolExecutedSimpleToolSegment, m: ChatMessage): string {
  if (segment.kind === 'tool_request') return isStreamActiveForMessage(m) ? '执行中' : '已结束（未收到结果）'
  if (segment.status === 'cancelled') return '已取消'
  return segment.status === 'succeeded' ? '成功' : '失败'
}
const genMode = ref<'legacy' | 'workflow'>('legacy')

/** 将普通文本片段统一按 Markdown 渲染，避免仅代码围栏生效。 */
function renderMarkdown(text: string): string {
  return renderSafeMarkdown(text)
}

// 保持 id 为字符串，避免雪花算法生成的 Long ID 精度丢失
const appId = computed(() => {
  const idStr = String(route.params.id ?? '')
  return idStr ? idStr : null
})

function syncGenMode() {
  const queryMode = String(route.query.genMode ?? '').toLowerCase()
  if (queryMode === 'workflow') {
    genMode.value = 'workflow'
  } else if (queryMode === 'legacy') {
    genMode.value = 'legacy'
  } else if (appId.value) {
    const cached = window.sessionStorage.getItem(`genMode:${appId.value}`)
    if (cached === 'workflow' || cached === 'legacy') {
      genMode.value = cached
    }
  }
  if (appId.value) {
    window.sessionStorage.setItem(`genMode:${appId.value}`, genMode.value)
  }
}

const appInfo = ref<AppVO | null>(null)
/** 是否 workflow beta 应用（仅 is_beta=1 时展示首轮工作流进度卡片） */
const appIsBeta = ref(0)
const loadingApp = ref(false)

function syncAppIsBeta(isBeta?: number | null) {
  appIsBeta.value = isBeta === 1 ? 1 : 0
}

function isAppWorkflowBeta(): boolean {
  return appIsBeta.value === 1
}

// 是否为该应用创建者 / 管理员，用于控制是否允许继续对话生成
const isOwner = computed(() => {
  if (!appInfo.value || !userLoginStore.userLogin?.id) return false
  const loginId = String(userLoginStore.userLogin.id)
  const appUserId = appInfo.value.userId != null ? String(appInfo.value.userId) : ''
  return !!appUserId && appUserId === loginId
})

const isAdmin = computed(() => userLoginStore.userLogin?.userRole === 'admin')

// 普通用户访问他人应用（例如首页“精选应用”）时，只能查看预览，不能继续对话生成
const isReadOnly = computed(() => !isOwner.value && !isAdmin.value)

// 1. 导入工程改用启动识别与冻结计划入口；确认不是导入应用才显示原运行按钮。
const StartupRunPreview = defineAsyncComponent(() => import('@/preview/project-import/StartupRunPreview.vue'))
const importedWorkspace = ref<'pending' | 'imported' | 'plain'>('pending')
const importedView = computed(() => props.importedProject === true || importedWorkspace.value === 'imported')
const importedAgentBlocked = ref(true)
// 普通应用的模型偏好保存期间才阻塞；读取失败不阻塞，执行侧回落全局配置。
const projectAgentBlocked = ref(false)
// 有权限卡片或候选审阅时隐藏输入框，处理完再恢复。
const agentWaiting = ref(false)
const importLookupBlocked = computed(() => props.importedProject !== true && importedWorkspace.value === 'pending')
watch(() => [appId.value, userLoginStore.userLogin?.id] as const, async ([id, owner], _, onCleanup) => {
  importedWorkspace.value = 'pending'
  if (props.importedProject) return
  if (!id || !owner) {
    importedWorkspace.value = 'plain'
    return
  }
  // 2. 只读查询导入资格。取消的结果丢掉；查失败时留在导入启动入口，不退回旧运行按钮。
  const controller = new AbortController()
  onCleanup(() => controller.abort())
  try {
    const imported = await importedApp({ appId: id, ownerId: String(owner), signal: controller.signal })
    if (!controller.signal.aborted) importedWorkspace.value = imported ? 'imported' : 'plain'
  } catch (cause) {
    if (controller.signal.aborted || (cause as Error).name === 'AbortError') return
    if (!controller.signal.aborted) {
      importedWorkspace.value = 'imported'
      message.warning(cause instanceof Error && cause.message ? cause.message : '暂时无法确认导入状态，已停用旧的运行入口')
    }
  }
}, { immediate: true })

type ChatMessage = {
  id: number
  role: 'user' | 'assistant'
  content: string
  thinkingSegments?: unknown
  createTime?: string
  /** 会话消息流式开始时间（历史回放无此字段） */
  streamStartedAt?: number
  /** 首个可见内容出现时间（用于“共思考X秒”） */
  firstVisibleAt?: number
  /** 流式完成时间（用于“共消耗Y秒”） */
  streamFinishedAt?: number
  uiState?: AssistantUiState
  /** 预解析 UI 片段缓存（主要用于历史回放，避免模板重复重解析） */
  cachedUiSegments?: UiSegment[]
  /** 预解析工作流步骤缓存（主要用于历史回放） */
  cachedWorkflowSteps?: WorkflowStepRow[]
  /** 工作流 SSE：步骤卡片（流式中累加；历史从 content 回溯解析） */
  workflowSteps?: WorkflowStepRow[]
  /** 本条消息是否收到 Mermaid 失败提示标记 */
  mermaidErrorNotice?: boolean
  /** workflow 流式开始时间戳（用于长耗时提示） */
  workflowStreamStartedAt?: number
  /** 后端是否正在执行对话记忆压缩（回忆分析中） */
  isMemoryCompressing?: boolean
  /**
   * 用于将 SSE 流与具体 assistant 气泡绑定。
   * 多并发流时，禁止再用“最后一条 assistant 消息”作为流式落点。
   */
  streamId?: string
}

type ActiveStreamMeta = {
  assistantMessageId: number
  sseCarry: { carry: string }
  mode: 'legacy' | 'workflow'
}

type UiMarkdownSegment = { kind: 'markdown'; content: string; segmentId?: string }
type UiThinkingSegment = ThinkingSegment
type UiToolRequestSegment = {
  kind: 'tool_request'
  rawLabel: string
  toolName: string
  callId?: string
  agentToolName?: string
  targetPath?: string
  /** pending 胶囊开始计时（流式 tool_request 创建时写入） */
  createdAt?: number
  segmentId?: string
  /** 随机生成的 tier2 阈值（毫秒），取代全局常量，使多卡片切换时刻错开 */
  tier2ThresholdMs?: number
}
type UiToolExecutedWriteFileSegment = {
  kind: 'tool_executed_write_file'
  filePath: string
  language: string
  content: string
  done: boolean
  segmentId?: string
}

type UiToolExecutedModifyFileSegment = {
  kind: 'tool_executed_modify_file'
  filePath: string
  language: string
  beforeContent: string
  afterContent: string
  beforeDone: boolean
  afterDone: boolean
  done: boolean
  segmentId?: string
}

type UiToolExecutedSimpleToolSegment = {
  kind: 'tool_executed_simple_tool'
  toolTitle: string
  callId?: string
  agentToolName?: string
  status?: string
  filePath: string
  message: string
  done: boolean
  segmentId?: string
}

type UiGenerationStatusSegment = GenerationStatusSegmentInput

type UiVisualEditSelectionSegment = {
  kind: 'visual_edit_selection'
  segmentId?: string
} & ParsedVisualEditSelection

type UiSegment =
  | UiMarkdownSegment
  | UiThinkingSegment
  | UiToolRequestSegment
  | UiToolExecutedWriteFileSegment
  | UiToolExecutedModifyFileSegment
  | UiToolExecutedSimpleToolSegment
  | UiGenerationStatusSegment
  | UiVisualEditSelectionSegment

type ToolExecStage =
  | 'markdown'
  | 'tool_exec_wait_fence'
  | 'tool_exec_stream'
  | 'tool_exec_modify_wait_before_fence'
  | 'tool_exec_modify_stream_before'
  | 'tool_exec_modify_wait_after_fence'
  | 'tool_exec_modify_stream_after'
type PendingToolRequest = {
  toolName: string
  createdAt: number
}

type AssistantUiState = {
  segments: UiSegment[]
  buffer: string
  stage: ToolExecStage
  pendingFilePath?: string
  pendingLanguage?: string
  pendingModifyPart?: 'before' | 'after'
  /** 工具代码块起始围栏的反引号个数（与闭合行对齐规则一致） */
  pendingFenceLen?: number
  activeToolIndex?: number
  /** 折叠重复的“选择工具”提示，避免流式阶段刷屏 */
  lastToolRequestName?: string
  /** @deprecated 流式已统一用 segments；仅 drain/clear 时清零，避免遗留状态 */
  pendingToolRequest?: PendingToolRequest | null
  /** 递增序号，为每个新 segment 生成稳定的 segmentId */
  segmentSeq: number
}

/** 已加载的历史消息（来自接口） */
const loadedHistoryRecords = ref<ChatHistory[]>([])
const loadingHistory = ref(false)
const loadingMoreHistory = ref(false)
const hasMoreHistory = ref(true)
const inputMessage = ref('')
const sending = ref(false)
// 首次生成时用于提示“分析代码 ing~”，避免用户干等
const analyzingHintCloser = ref<null | (() => void)>(null)

// 仅用于把用户在首页选择的“代码类型”追加到发送给 AI 的 prompt 末尾（不回显到前端对话气泡）
const codeTypePromptChoiceOnce = ref<string | null>(null)
const selectedLoopId = ref<string | number>(0)
const selectedLoopCompiledPrompt = ref<string>('')
const selectedLoopName = ref<string>('')
function onLoopSelect(loopId: string | number) {
  selectedLoopId.value = loopId
  // 获取 Loop 详情以获得 compiledPrompt（Snowflake ID 用字符串传递避免精度丢失）
  if (loopId) {
    const idStr = String(loopId)
    loopGetVoById(idStr).then(res => {
        if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
          selectedLoopCompiledPrompt.value = res.data.data.compiledPrompt || ''
          selectedLoopName.value = res.data.data.loopName || ''
        }
      }).catch(() => {
        selectedLoopCompiledPrompt.value = ''
        selectedLoopName.value = ''
      })
  } else {
    selectedLoopCompiledPrompt.value = ''
    selectedLoopName.value = ''
  }
}

/**
 * 多并发 SSE：每次 sendMessage 会创建一个独立的 EventSource。
 * 用 streamId 作为 key，保证后发请求不会 close 掉先发请求。
 */
const eventSourceMap = new Map<string, EventSource>()
/**
 * Agent 传输句柄（WebSocket 优先 / SSE 降级）。
 * 与 eventSourceMap 并存：开关打开时走这个，关闭时走老的 EventSource。
 */
const agentStreamMap = new Map<string, import('@/utils/agentStreamTransport').AgentStream>()
const activeStreamOrder = ref<string[]>([])
const activeStreamMeta = ref<Record<string, ActiveStreamMeta>>({})
const streamRenderScheduler = createStreamRenderScheduler({
  onChunk: (streamId, chunk) => commitAssistantChunkToStream(streamId, chunk),
})
let scrollFrameHandle: number | null = null
let chatBottomObserver: IntersectionObserver | null = null
const WORKFLOW_SLOW_HINT_DELAY_MS = 120_000
const TOOL_REQUEST_STATUS_DELAY_MS = 5_000
const workflowNowTs = ref(Date.now())
let workflowSlowHintTicker: number | null = null

const streaming = computed(() => Object.keys(activeStreamMeta.value).length > 0)
let nextMessageId = 1

const hasGenerated = ref(false)

/**
 * 服务端对实际产物的描述（真实目录名、可预览的静态入口）。
 *
 * 为 null 表示还没拿到或接口失败，此时预览退回按 codeGenType 推断的旧行为。
 */
const workspace = ref<AppWorkspaceInfo | null>(null)
/** 当前会话新产生的消息（不含历史） */
const sessionMessages = ref<ChatMessage[]>([])
/** 历史消息（由 loadedHistoryRecords 预处理而来，避免渲染期重复 map+parse） */
const historyMessages = ref<ChatMessage[]>([])

type MessageRenderCache = {
  workflowVersion?: string
  workflowSteps?: WorkflowStepRow[]
  uiVersion?: string
  uiSegments?: UiSegment[]
  renderableVersion?: string
  renderable?: boolean
  incrementalMarkdownParsers?: Map<string, IncrementalMarkdownParser>
}
const messageRenderCache = new WeakMap<ChatMessage, MessageRenderCache>()

function getCachedMarkdownSegments(m: ChatMessage, content: string, parserKey = 'default') {
  const cache = messageRenderCache.get(m) ?? {}
  const parser = cache.incrementalMarkdownParsers?.get(parserKey) ?? new IncrementalMarkdownParser()
  const parsed = parser.update(content)
  const parsers = cache.incrementalMarkdownParsers ?? new Map<string, IncrementalMarkdownParser>()
  parsers.set(parserKey, parser)
  messageRenderCache.set(m, { ...cache, incrementalMarkdownParsers: parsers })
  return parsed
}

type GeneratedFileItem = {
  path: string
  language?: string
  content: string
  updatedAt: number
}

/** 从流式工具输出中提取到的”生成文件”列表（用于 Vue 项目文件回显） */
const generatedFiles = ref<GeneratedFileItem[]>([])
const generatedFileMap = ref<Record<string, GeneratedFileItem>>({})
const toolSelectHintShown = ref(false)
const loadingEcho = ref(false)
let pendingEchoRefresh = false

const filesModalOpen = ref(false)
const activeFilePath = ref<string>('')
const activeFile = computed(() => {
  const p = activeFilePath.value
  if (!p) return null
  return generatedFileMap.value[p] ?? null
})
const lastPreviewProbing = ref<{
  active: boolean
  attempts: number
  maxAttempts: number
  timer: number | null
}>({
  active: false,
  attempts: 0,
  maxAttempts: 10,
  timer: null,
})
const refreshAppCtaVisible = ref(false)
const refreshAppCtaHint = ref('生成完成后建议刷新一次，以避免浏览器缓存 / 资源加载时序导致预览仍显示旧内容。')
const refreshingApp = ref(false)

const isVueProject = computed(() => {
  if (!appInfo.value) return false
  const codeGenType = normalizeCodeGenType(appInfo.value.codeGenType)
  return codeGenType === CodeGenTypeEnum.VUE_PROJECT
})

function dedupHistoryRecords(records: ChatHistory[]): ChatHistory[] {
  const byId = new Map<number, ChatHistory>()
  const fallbackKeys = new Set<string>()
  const deduped: ChatHistory[] = []

  for (const item of records) {
    const id = item.id
    if (typeof id === 'number' && Number.isFinite(id) && id > 0) {
      byId.set(id, item)
      continue
    }
    const fallbackKey = `${item.createTime ?? ''}|${item.messageType ?? ''}|${item.message ?? ''}`
    if (fallbackKeys.has(fallbackKey)) continue
    fallbackKeys.add(fallbackKey)
    deduped.push(item)
  }

  deduped.push(...byId.values())
  deduped.sort((a, b) => {
    const ta = a.createTime ?? ''
    const tb = b.createTime ?? ''
    if (ta !== tb) return ta.localeCompare(tb)
    const ia = typeof a.id === 'number' ? a.id : 0
    const ib = typeof b.id === 'number' ? b.id : 0
    return ia - ib
  })
  return deduped
}

/** 将 ChatHistory 转为 ChatMessage */
function historyToMessage(r: ChatHistory): ChatMessage {
  const role = (r.messageType?.toLowerCase() === 'user' ? 'user' : 'assistant') as 'user' | 'assistant'
  const msg: ChatMessage = {
    id: r.id ?? 0,
    role,
    content: r.message ?? '',
    createTime: r.createTime,
    thinkingSegments: (r as ChatHistory & { thinkingSegments?: unknown }).thinkingSegments,
  }
  precomputeMessageRenderCaches(msg)
  return msg
}

function stableSerializeWorkflowRows(rows: WorkflowStepRow[] | undefined): string {
  if (!rows || rows.length === 0) return '[]'
  return rows.map((row) => `${row.step}:${row.label}`).join('|')
}

function buildWorkflowRenderVersion(
  m: ChatMessage,
  opts?: { historyMode?: boolean; streaming?: boolean; historyOutcome?: WorkflowHistoryOutcome },
): string {
  const rawVersion = stableSerializeWorkflowRows(m.workflowSteps)
  return `${rawVersion}|h=${opts?.historyMode === true ? 1 : 0}|s=${opts?.streaming === true ? 1 : 0}|o=${opts?.historyOutcome ?? 'unknown'}`
}

function buildUiRenderVersion(m: ChatMessage): string {
  if (m.uiState) {
    // 流式消息：segments.length + buffer.length 在 flushSafeMarkdown 追加到已有
    // markdown segment 时可能都不变，导致缓存版本 key 不变、getMessageUiSegments
    // 返回旧 segments（画面卡在早期代码位置）。纳入 content.length 确保内容增长
    // 时缓存一定失效。
    return `stream:${m.content.length}:${m.uiState.segments.length}:${m.uiState.buffer.length}:${m.uiState.stage}:${JSON.stringify(m.uiState.segments.filter(s => s.kind === 'thinking'))}`
  }
  return `hist:${m.content ?? ''}:${JSON.stringify(m.thinkingSegments)}`
}

function precomputeMessageRenderCaches(m: ChatMessage) {
  if (m.role !== 'assistant' || m.uiState) return
  const cache = messageRenderCache.get(m) ?? {}
  const historyOutcome = m.createTime
    ? resolveWorkflowHistoryOutcomeFromContent(m.content ?? '')
    : 'unknown'

  const rawWorkflow = resolveWorkflowStepsFromMessageContent(m.workflowSteps, m.content ?? '')
  const workflowVersion = buildWorkflowRenderVersion(m, {
    historyMode: !!m.createTime,
    streaming: false,
    historyOutcome,
  })
  const normalizedWorkflow = normalizeWorkflowStepsForUi(rawWorkflow, {
    historyMode: !!m.createTime,
    streaming: false,
    historyOutcome,
    rawContent: m.content ?? '',
  })
  cache.workflowVersion = workflowVersion
  cache.workflowSteps = normalizedWorkflow
  m.cachedWorkflowSteps = normalizedWorkflow

  const uiVersion = buildUiRenderVersion(m)
  const uiSegments = computeMessageUiSegments(m)
  cache.uiVersion = uiVersion
  cache.uiSegments = uiSegments
  m.cachedUiSegments = uiSegments

  cache.renderableVersion = `${workflowVersion}|${uiVersion}`
  cache.renderable = normalizedWorkflow.length > 0 || uiSegments.some((seg) => {
    if (seg.kind !== 'markdown') return true
    return (seg.content ?? '').trim().length > 0
  })
  messageRenderCache.set(m, cache)
}

/** 用于展示的完整消息列表（历史 + 当前会话），按 createTime 升序，无 createTime 的当前会话消息始终排在最下面 */
const displayMessages = computed(() => {
  const combined = [...historyMessages.value, ...sessionMessages.value]
  return combined.sort((a, b) => {
    const ta = a.createTime || ''
    const tb = b.createTime || ''
    if (ta && tb) return ta.localeCompare(tb)
    // 只有一方有 createTime 时：有时间的（历史）排前面，无时间的（当前会话）排后面
    if (ta) return -1
    if (tb) return 1
    return 0
  })
})

/** 是否应展示网站：仅在确有可打开的静态入口时展示，避免指向不存在的文件导致白屏 */
const shouldShowWebsite = computed(() => {
  // 运行中一定有可访问的入口（容器端口），不受静态入口判定影响
  if (runtimeRunning.value && runtimeStatus.value?.gatewayPort) return true
  // 服务端描述就绪时以它为准：没有静态入口就不挂 iframe
  if (workspace.value) return !!workspace.value.staticEntry
  // 描述尚未返回或接口失败时退回原判断
  // Vue 项目：以静态入口探测为准 + 显示托底刷新按钮，避免仅依赖 hasGeneratedCode 导致刷新后误判“未生成”
  if (isVueProject.value) return hasGenerated.value || !!appInfo.value?.hasGeneratedCode
  return !!appInfo.value?.hasGeneratedCode || hasGenerated.value
})
const iframeKey = ref(0)

/** 「项目文件」窗口实例：用来在 AI 改码前处理未保存草稿 */
const projectFilesModalRef = ref<InstanceType<typeof ProjectFilesModal> | null>(null)

/* ========== 聊天 / 预览分栏 ========== */

/** 左侧占比。拖拽过程中只改这个本地 ref，松手才写入偏好 */
const splitRatio = ref(appearanceStore.splitRatio)

/** 放大某一侧后另一侧隐藏；none 表示按比例分栏 */
const focusedSide = ref<'none' | 'chat' | 'preview'>('none')

const mainContentRef = ref<HTMLElement | null>(null)
const draggingSplitter = ref(false)

/**
 * 窄屏下布局由 media query 接管，而行内样式优先级更高会盖掉它 ——
 * 所以窄屏时干脆不下发 grid-template-columns。
 */
const isNarrowScreen = ref(false)
const narrowScreenQuery = window.matchMedia('(max-width: 992px)')
function syncNarrowScreen() {
  isNarrowScreen.value = narrowScreenQuery.matches
  // 窄屏下重置为 none，避免从宽屏单栏切到窄屏后丢失一侧面板且无入口恢复
  if (isNarrowScreen.value && focusedSide.value !== 'none') {
    focusedSide.value = 'none'
  }
}
syncNarrowScreen()
narrowScreenQuery.addEventListener('change', syncNarrowScreen)

const splitLayout = computed(() => ({
  narrow: isNarrowScreen.value,
  previewExpanded: appearanceStore.previewExpanded,
  focusedSide: focusedSide.value,
  ratio: splitRatio.value,
}))

const splitterVisible = computed(() => isSplitterVisible(splitLayout.value))
const gridColumns = computed(() => computeGridColumns(splitLayout.value))
const chatHidden = computed(() => isChatHidden(focusedSide.value, isNarrowScreen.value))
const previewHidden = computed(() =>
  isPreviewHidden(
    appearanceStore.previewExpanded,
    focusedSide.value,
    isNarrowScreen.value,
  ),
)
const leftRestoreVisible = computed(() =>
  isLeftRestoreTriggerVisible(
    isNarrowScreen.value,
    focusedSide.value,
    appearanceStore.previewExpanded,
  ),
)
const rightRestoreVisible = computed(() =>
  isRightRestoreTriggerVisible(
    isNarrowScreen.value,
    appearanceStore.previewExpanded,
    focusedSide.value,
  ),
)

function toggleFocusSide(side: 'chat' | 'preview') {
  focusedSide.value = focusedSide.value === side ? 'none' : side
}

function restoreSplitView() {
  focusedSide.value = 'none'
  appearanceStore.previewExpanded = true
}

/** 处于单侧放大时，按 Escape 键可快速退回双栏并存布局 */
function handleSplitPaneKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && !document.fullscreenElement && focusedSide.value !== 'none') {
    restoreSplitView()
  }
}

let cachedContainerRect: { left: number; width: number } | null = null
let grabOffset = 0
let splitterRafId: number | null = null
let pendingClientX = 0

function updateRatioFromClientX(clientX: number) {
  if (!cachedContainerRect) return
  splitRatio.value = calculateSplitRatioWithOffset(
    clientX,
    cachedContainerRect,
    grabOffset,
    SPLITTER_TRACK,
  )
}

function onSplitterMove(event: MouseEvent) {
  if (!draggingSplitter.value) return
  pendingClientX = event.clientX
  if (splitterRafId === null) {
    splitterRafId = requestAnimationFrame(() => {
      splitterRafId = null
      if (draggingSplitter.value) {
        updateRatioFromClientX(pendingClientX)
      }
    })
  }
}

function stopSplitterDrag() {
  window.removeEventListener('mousemove', onSplitterMove)
  window.removeEventListener('mouseup', stopSplitterDrag)
  if (splitterRafId !== null) {
    cancelAnimationFrame(splitterRafId)
    splitterRafId = null
  }
  if (typeof document !== 'undefined') {
    document.body.classList.remove('is-splitter-resizing')
  }
  if (!draggingSplitter.value) return
  if (cachedContainerRect) {
    updateRatioFromClientX(pendingClientX)
  }
  draggingSplitter.value = false
  cachedContainerRect = null
  // 松手才落盘：拖拽过程中每帧写 localStorage 会明显卡顿
  appearanceStore.splitRatio = splitRatio.value
}

function startSplitterDrag(event: MouseEvent) {
  if (event.button !== 0) return
  const el = mainContentRef.value
  if (!el) return
  const rect = el.getBoundingClientRect()
  if (rect.width <= 0) return

  cachedContainerRect = { left: rect.left, width: rect.width }
  const availableWidth = rect.width - SPLITTER_TRACK
  const currentCenterX = rect.left + splitRatio.value * availableWidth + SPLITTER_TRACK / 2
  // 计算抓取偏离中心的相对量并限制在轨道范围内，避免拖拽启动时跳变
  const rawOffset = event.clientX - currentCenterX
  const halfTrack = SPLITTER_TRACK / 2
  grabOffset = Math.max(-halfTrack, Math.min(halfTrack, rawOffset))

  draggingSplitter.value = true
  focusedSide.value = 'none'
  pendingClientX = event.clientX

  // 关键：鼠标按下瞬间立即更新位置，让点击与微移瞬间获得即时跟手反馈
  updateRatioFromClientX(event.clientX)

  if (typeof document !== 'undefined') {
    document.body.classList.add('is-splitter-resizing')
  }
  window.addEventListener('mousemove', onSplitterMove, { passive: true })
  window.addEventListener('mouseup', stopSplitterDrag)
  event.preventDefault()
}

/** 双击分割线快速恢复 50:50 等分 */
function handleSplitterDblClick() {
  splitRatio.value = 0.5
  appearanceStore.splitRatio = 0.5
  focusedSide.value = 'none'
}


const chatMessagesRef = ref<HTMLElement | null>(null)
const chatBottomRef = ref<HTMLElement | null>(null)
const shouldAutoScroll = ref(appearanceStore.smoothScroll)
const showScrollToBottom = computed(() => !shouldAutoScroll.value && displayMessages.value.length > 0)

const previewIframeRef = ref<HTMLIFrameElement | null>(null)
const workflowCollapsedMap = ref<Record<string, boolean>>({})
const visualEditMode = ref(false)
const selectedElement = ref<VisualSelectedElementInfo | null>(null)
const selectedElementDisplay = computed(() => {
  if (!selectedElement.value) return ''
  const info = selectedElement.value
  return (
    info.cssSelector ||
    info.xpath ||
    [info.tagName, info.id ? `#${info.id}` : '', info.className ? `.${String(info.className).split(/\s+/)[0]}` : '']
      .filter(Boolean)
      .join('')
  )
})

const selectedElementTextPreview = computed(() => {
  const info = selectedElement.value
  if (!info) return ''
  const t = String(info.text ?? '').replace(/\s+/g, ' ').trim()
  if (!t) return ''
  return t.length > 120 ? `${t.slice(0, 120)}…` : t
})

let visualEditorDetach: null | (() => void) = null
let visualEditAutoRefreshTimer: number | null = null

/**
 * 防御性清理可视化编辑残留层。
 * 证据：若父页透明 shield 偶发残留，会拦截 iframe 的滚轮/点击，表现为“非编辑模式无法滚动，编辑模式反而可滚动”。
 */
function cleanupVisualEditorArtifacts() {
  try {
    const staleLayers = document.querySelectorAll(
      '[data-glyahh-visual-editor-parent-shield="true"], [data-glyahh-visual-editor-parent="true"]',
    )
    staleLayers.forEach((el) => {
      try {
        el.remove()
      } catch {
        // ignore
      }
    })
  } catch {
    // ignore
  }
  try {
    const iframe = previewIframeRef.value
    if (iframe) {
      iframe.style.pointerEvents = 'auto'
    }
  } catch {
    // ignore
  }
}

function clearSelectedElement() {
  selectedElement.value = null
}

function exitVisualEditMode() {
  visualEditMode.value = false
  clearSelectedElement()
  if (visualEditAutoRefreshTimer != null) {
    window.clearTimeout(visualEditAutoRefreshTimer)
    visualEditAutoRefreshTimer = null
  }
  if (visualEditorDetach) {
    try {
      visualEditorDetach()
    } catch {
      // ignore
    }
    visualEditorDetach = null
  }
  cleanupVisualEditorArtifacts()
}

function tryAttachVisualEditor() {
  const iframe = previewIframeRef.value
  if (!iframe) return
  if (!visualEditMode.value) return

  // 先清理旧的，再挂新的（iframe 刷新/重载时需要重新 attach）
  if (visualEditorDetach) {
    try {
      visualEditorDetach()
    } catch {
      // ignore
    }
    visualEditorDetach = null
  }

  try {
    const res = attachVisualWebsiteEditor({
      iframeEl: iframe,
      onSelected: (info) => {
        selectedElement.value = info
      },
      onNavigateClear: () => {
        selectedElement.value = null
      },
      onError: (err) => {
        // 注入失败时给出明确提示（便于定位：跨域 / CSP / 预览未就绪）
        message.warning(err?.message || '可视化编辑注入失败，请刷新预览后重试')
      },
    })
    visualEditorDetach = res.detach
  } catch (e) {
    message.warning('预览尚未就绪，暂时无法进入编辑模式')
  }
}

function toggleVisualEditMode() {
  if (isReadOnly.value) {
    message.warning('当前应用处于只读状态，无法进入编辑模式')
    return
  }
  // 如果预览入口尚未就绪，依然允许切换编辑态，并立刻触发一次与“刷新应用”相同的探测请求，
  // 等 iframe 重载后 handlePreviewIframeLoad 会重新注入。
  if (!shouldShowWebsite.value && isVueProject.value) {
    void handleManualRefreshApp()
  }
  visualEditMode.value = !visualEditMode.value
  if (!visualEditMode.value) {
    exitVisualEditMode()
    return
  }

  message.info('已进入编辑模式：悬浮高亮元素，点击选中并锁定边框')

  // 立即探测一次，确保会发出与“刷新应用”一致的 ?probe= 请求（便于确认链路）。
  void probePreviewReady().then((ready) => {
    if (ready) hasGenerated.value = true
  })

  // 经验问题：部分项目（尤其 Vue）进入编辑模式时立即注入可能打到旧文档/尚未就绪的 iframe。
  // 因此模拟“先点编辑模式，约 0.5s 后再触发一次刷新”的人工操作，提高生效率。
  if (visualEditAutoRefreshTimer != null) {
    window.clearTimeout(visualEditAutoRefreshTimer)
  }
  visualEditAutoRefreshTimer = window.setTimeout(() => {
    visualEditAutoRefreshTimer = null
    void handleManualRefreshApp()
  })

  nextTick(() => {
    tryAttachVisualEditor()
  })
}

function handlePreviewIframeLoad() {
  // 预览刷新后：若仍在编辑模式，需要重新注入监听
  if (visualEditMode.value) {
    nextTick(() => {
      tryAttachVisualEditor()
    })
  } else {
    // 非编辑模式下确保不存在残留覆盖层，避免滚轮被拦截。
    cleanupVisualEditorArtifacts()
  }
}

const applyFeaturedModalVisible = ref(false)
const applyAppProprietyReason = ref('')

const canApplyFeatured = computed(
  () => isOwner.value && !isReadOnly.value && (!appInfo.value?.priority || appInfo.value.priority < 99),
)

const exportingChat = ref(false)
const downloadingProject = ref(false)

/** 当前应用对话轮数（用户一问 + AI 一答为一轮），仅创建者/管理员展示 */
let titlePollTimer: ReturnType<typeof setTimeout> | undefined
let titlePollVersion = 0
function pollFirstProjectTitle() {
  if ((roundCount.value ?? 0) > 0 || executionPreference.autoNameProject === false) return
  const id = appId.value
  if (!id) return
  const original = appInfo.value?.appName
  const version = ++titlePollVersion
  let tries = 0
  const poll = async () => {
    try {
      const res = await appGetVoUsingGet({ params: { id } })
      if (version !== titlePollVersion || appId.value !== id) return
      const name = res.data?.data?.appName
      if (name && name !== original && appInfo.value) { appInfo.value.appName = name; return }
    } catch { /* 命名失败不影响生成。 */ }
    if (version === titlePollVersion && ++tries < 24) titlePollTimer = setTimeout(poll, 3000)
  }
  clearTimeout(titlePollTimer)
  titlePollTimer = setTimeout(poll, 3000)
}
onBeforeUnmount(() => { titlePollVersion++; clearTimeout(titlePollTimer) })

const roundCount = ref<number | null>(null)
const loadingRoundCount = ref(false)

async function fetchRoundCount() {
  const id = appId.value
  if (!id || !(isOwner.value || isAdmin.value)) return
  loadingRoundCount.value = true
  try {
    const res = await chatHistoryRoundCountAppIdUsingGet({
      params: { appId: id as unknown as number },
    })
    const code = res?.data?.code
    const data = res?.data?.data
    if (code === 0 || code === 20000) {
      roundCount.value = typeof data === 'number' ? data : null
    } else {
      roundCount.value = null
    }
  } catch {
    roundCount.value = null
  } finally {
    loadingRoundCount.value = false
  }
}

/** 将服务端返回的对话列表转为 Markdown 文本并触发下载 */
function buildMarkdownFromHistory(records: { messageType?: string; message?: string; createTime?: string }[]): string {
  const lines: string[] = [
    `# 对话导出 - ${appInfo.value?.appName ?? '应用'}`,
    '',
    `导出时间：${new Date().toLocaleString('zh-CN')}`,
    `共 ${records.length} 条消息`,
    '',
    '---',
    '',
  ]
  for (const r of records) {
    const role = r.messageType?.toLowerCase() === 'user' ? '用户' : '应用助手'
    const time = r.createTime ? ` (${r.createTime})` : ''
    lines.push(`## ${role}${time}`)
    lines.push('')
    lines.push(r.message ?? '')
    lines.push('')
    lines.push('')
  }
  return lines.join('\n')
}

async function handleExportChatToLocal() {
  if (!appId.value) {
    message.error('应用 ID 异常，无法导出')
    return
  }
  exportingChat.value = true
  const hide = message.loading('正在导出对话…', 0)
  try {
    const res = await chatHistoryOpenApiExportAppIdUsingGet({
      params: { appId: appId.value as unknown as number },
    })
    hide()
    const code = res?.data?.code
    const data = res?.data?.data
    if ((code === 0 || code === 20000) && Array.isArray(data)) {
      if (data.length === 0) {
        message.info('当前没有可导出的对话记录')
        return
      }
      const md = buildMarkdownFromHistory(data)
      const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `对话导出-${appInfo.value?.appName ?? appId.value}-${Date.now()}.md`
      a.click()
      URL.revokeObjectURL(url)
      message.success('对话已导出到本地')
    } else {
      message.error(res?.data?.message ?? '导出失败')
    }
  } catch (e: unknown) {
    hide()
    const err = e as { response?: { data?: { message?: string } } }
    message.error(err?.response?.data?.message ?? '导出失败，请稍后再试')
  } finally {
    exportingChat.value = false
  }
}

function getFileNameFromContentDisposition(
  headerValue: string | null,
  fallbackName: string,
): string {
  if (!headerValue) return fallbackName

  // 优先解析 filename*（RFC 5987，UTF-8 编码）
  const filenameStarMatch = headerValue.match(/filename\*\s*=\s*UTF-8''([^;]+)/i)
  if (filenameStarMatch && filenameStarMatch[1]) {
    try {
      const encoded = filenameStarMatch[1].trim().replace(/^"(.*)"$/, '$1')
      return decodeURIComponent(encoded)
    } catch {
      // ignore and try other strategies
    }
  }

  // 兼容 filename="xxx"
  const filenameMatch = headerValue.match(/filename\s*=\s*"([^"]+)"/i)
  if (filenameMatch && filenameMatch[1]) {
    return filenameMatch[1].trim()
  }

  // 兼容 filename=xxx（无引号）
  const filenameNoQuoteMatch = headerValue.match(/filename\s*=\s*([^;]+)/i)
  if (filenameNoQuoteMatch && filenameNoQuoteMatch[1]) {
    return filenameNoQuoteMatch[1].trim()
  }

  return fallbackName
}

async function handleDownloadProjectFiles() {
  if (!appId.value) {
    message.error('应用 ID 异常，无法下载项目文件')
    return
  }

  // 若还未生成代码，优先提示用户先完成一次生成
  if (!appInfo.value?.hasGeneratedCode && !hasGenerated.value) {
    message.warning('当前应用还没有可下载的项目代码，请先完成一次生成')
    return
  }

  const displayName = appInfo.value?.appName || String(appId.value)
  const fallbackFileName = `项目代码-${displayName}.zip`

  downloadingProject.value = true
  const hide = message.loading('正在打包并下载项目文件…', 0)
  try {
    const apiBase = API_BASE_URL.replace(/\/$/, '')
    const url = `${apiBase}/app/download/${encodeURIComponent(String(appId.value))}`

    const res = await fetch(url, {
      method: 'GET',
      credentials: 'include',
    })

    if (!res.ok) {
      // 尝试读取服务端返回的错误信息
      let errorText: string | null = null
      try {
        errorText = await res.text()
      } catch {
        // ignore
      }
      message.error(errorText || '下载项目文件失败，请稍后重试')
      return
    }

    const blob = await res.blob()
    const disposition =
      res.headers.get('Content-Disposition') ?? res.headers.get('content-disposition')
    const fileName = getFileNameFromContentDisposition(disposition, fallbackFileName)

    const objectUrl = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = objectUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(objectUrl)

    message.success('项目文件下载成功')
  } catch (e: unknown) {
    const err = e as { response?: { data?: { message?: string } } }
    message.error(err?.response?.data?.message ?? '下载项目文件失败，请稍后重试')
  } finally {
    hide()
    downloadingProject.value = false
  }
}

function openApplyFeatured() {
  if (!canApplyFeatured.value) return
  applyFeaturedModalVisible.value = true
}

function closeApplyFeatured() {
  applyFeaturedModalVisible.value = false
}

async function handleApplyFeaturedConfirm() {
  if (!appId.value) {
    message.error('应用 ID 异常，无法提交申请')
    return
  }
  const reason = applyAppProprietyReason.value.trim() || '我想成为精选应用'
  const hide = message.loading('正在提交精选申请...', 0)
  try {
    const res = await appApplyUsingPost({
      body: {
        // 注意：这里必须保持 appId 为字符串，不能转为 Number，否则会丢失精度
        appId: appId.value as any,
        operate: 1,
        appPropriety: 99,
        applyReason: reason,
      },
    })
    hide()
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data === true) {
      message.success('申请已提交，请等待审核')
      applyFeaturedModalVisible.value = false
    } else {
      message.error(res.data.message || '申请提交失败')
    }
  } catch (e: any) {
    hide()
    message.error(e?.response?.data?.message || '申请提交失败，请稍后再试')
  }
}

/**
 * 拉取服务端工作区描述。
 *
 * 失败时保持 workspace 为 null，预览退回旧行为 —— 描述接口出问题不该让预览彻底不可用。
 */
async function refreshWorkspace() {
  if (!appId.value) return
  try {
    workspace.value = await loadAppWorkspace({ appId: appId.value, apiBaseUrl: API_BASE_URL })
  } catch {
    workspace.value = null
  }
}

/* ========== 看板功能开关 ========== */

const DASHBOARD_FEATURE_OPTIONS = [
  { value: 'metrics', label: '运行监控', description: '查看 CPU、内存与平台入口流量的真实采样。' },
  { value: 'logs', label: '运行日志', description: '实时查看进程输出，筛选并下载诊断日志。' },
  { value: 'knowledge', label: '项目知识', description: '探索源码图谱、阅读项目文档，进行只读问答。' },
] as const

const dashboardModalOpen = ref(false)
const dashboardDraft = ref<string[]>([])
const savingDashboard = ref(false)

function openDashboardSettings() {
  dashboardDraft.value = [...(workspace.value?.dashboardFeatures ?? [])]
  dashboardModalOpen.value = true
}

async function saveDashboardSettings() {
  if (!appId.value) return
  savingDashboard.value = true
  try {
    // 接口回的就是更新后的整份描述，直接替换，省一次读取
    workspace.value = await updateDashboardFeatures({
      appId: appId.value,
      features: dashboardDraft.value,
      apiBaseUrl: API_BASE_URL,
    })
    dashboardModalOpen.value = false
    message.success('看板设置已保存')
  } catch (e) {
    message.error((e as Error).message || '保存看板设置失败')
  } finally {
    savingDashboard.value = false
  }
}

/* ========== 运行状态 ========== */

const fullscreenBody = ref<HTMLElement | null>(null)
const previewFullscreen = ref(false)
const fullscreenHint = ref(false)
let fullscreenHintTimer: ReturnType<typeof setTimeout> | undefined
function syncFullscreen() {
  previewFullscreen.value = document.fullscreenElement === fullscreenBody.value
  if (!previewFullscreen.value) fullscreenHint.value = false
}
async function enterPreviewFullscreen() {
  try {
    await fullscreenBody.value?.requestFullscreen()
    fullscreenHint.value = true
    clearTimeout(fullscreenHintTimer)
    fullscreenHintTimer = setTimeout(() => { fullscreenHint.value = false }, 4500)
  } catch { message.error('无法进入全屏') }
}
function exitPreviewFullscreen() {
  if (document.fullscreenElement) void document.exitFullscreen()
}
onMounted(() => document.addEventListener('fullscreenchange', syncFullscreen))
onBeforeUnmount(() => {
  clearTimeout(fullscreenHintTimer)
  document.removeEventListener('fullscreenchange', syncFullscreen)
  if (document.fullscreenElement === fullscreenBody.value) exitPreviewFullscreen()
})

const runtimeStatus = ref<RuntimeStatus | null>(null)
const startingRuntime = ref(false)
const stoppingRuntime = ref(false)
/** 右侧面板当前页签 */
const runtimePanelTab = ref<'preview' | 'metrics' | 'logs' | 'knowledge'>('preview')

const runtimeRunning = computed(() => runtimeStatus.value?.running === true)
/** 运行版本落后：改了代码但没重新运行 */
const runtimeStale = computed(() => runtimeStatus.value?.stale === true)
const runtimeActive = computed(() => {
  const status = runtimeStatus.value?.status
  return status === 'STARTING' || status === 'RUNNING' || runtimeRunning.value
})

const dashboardFeatureSet = computed(() => new Set(workspace.value?.dashboardFeatures ?? []))
const showMetricsPanel = computed(() => dashboardFeatureSet.value.has('metrics'))
const showLogsPanel = computed(() => dashboardFeatureSet.value.has('logs'))

/**
 * 右侧页签：预览必在（有前端就该能看），监控 / 日志 / 知识按用户勾选的看板功能出现。
 * 一个都不用勾时只剩预览，此时不显示页签栏。
 */
const runtimeTabs = computed(() => {
  const tabs: Array<{ key: 'preview' | 'metrics' | 'logs' | 'knowledge'; label: string }> = [
    { key: 'preview', label: '预览' },
  ]
  if (showMetricsPanel.value) tabs.push({ key: 'metrics', label: '监控' })
  if (showLogsPanel.value) tabs.push({ key: 'logs', label: '日志' })
  if (dashboardFeatureSet.value.has('knowledge')) tabs.push({ key: 'knowledge', label: '知识' })
  return tabs
})

async function refreshRuntimeStatus() {
  if (!appId.value) return
  try {
    runtimeStatus.value = await loadRuntimeStatus({ appId: appId.value, apiBaseUrl: API_BASE_URL })
  } catch {
    // 状态拿不到就当没在运行，不阻断页面其他部分
    runtimeStatus.value = null
  }
  void refreshIncidents()
}

/* ========== 运行异常提示 ========== */
const executionPreference = useExecutionPreference()
const openIncidents = ref<RuntimeIncident[]>([])
const incidentModalOpen = ref(false)
const repairingIncident = ref(false)

/**
 * 按用户的两个全局开关过滤。
 *
 * 开关语义（Plan 明确）：启动异常默认开、运行异常默认关 ——
 * 运行期的偶发错误太吵，默认不打扰；但记录照留，日志里查得到。
 */
const visibleIncidents = computed(() =>
  openIncidents.value.filter((item) =>
    item.kind === 'STARTUP_FAILURE'
      ? executionPreference.alertOnStartupFailure
      : executionPreference.alertOnRuntimeFailure,
  ),
)

const incidentTitle = computed(() =>
  visibleIncidents.value.some((item) => item.kind === 'STARTUP_FAILURE')
    ? '应用启动失败'
    : '应用运行异常',
)

const incidentDetail = computed(() => visibleIncidents.value[0]?.detail ?? '')

async function refreshIncidents() {
  if (!appId.value) return
  try {
    openIncidents.value = await loadOpenIncidents({ appId: appId.value, apiBaseUrl: API_BASE_URL })
  } catch {
    openIncidents.value = []
  }
  // 只在确有该提示的问题时才弹；用户关掉后状态变 DISMISSED，下次拉取不会再进来
  incidentModalOpen.value = visibleIncidents.value.length > 0
}

async function handleDismissIncident() {
  if (!appId.value) return
  const list = visibleIncidents.value
  incidentModalOpen.value = false
  openIncidents.value = []
  // 关掉弹窗只是不再打扰，服务端的记录保留
  await Promise.all(
    list.map((item) =>
      dismissIncident({ appId: appId.value!, incidentId: item.id, apiBaseUrl: API_BASE_URL }).catch(() => false),
    ),
  )
}

async function handleRepairIncident() {
  const target = visibleIncidents.value[0]
  if (!target || !appId.value || repairingIncident.value) return
  repairingIncident.value = true
  try {
    const prompt = await prepareIncidentRepair({
      appId: appId.value,
      incidentId: target.id,
      apiBaseUrl: API_BASE_URL,
    })
    incidentModalOpen.value = false
    openIncidents.value = []
    message.info('已把问题交给 AI 诊断，修复过程会出现在下面的对话里')
    // 走用户发消息那条路：修复在原对话里可见，也自然受项目写锁串行保护
    await sendMessage(prompt)
  } catch (e) {
    message.error((e as Error).message || '发起修复失败')
  } finally {
    repairingIncident.value = false
  }
}

/**
 * 知识图谱里点「打开源码」：打开项目文件窗口并定位到那个文件。
 *
 * sourceRef 形如 {@code src/main.js:42}，行号不是文件路径的一部分，要去掉。
 */
async function handleOpenSourceFromGraph(sourceRef: string) {
  if (!sourceRef) return
  const path = sourceRef.replace(/:\d+(-\d+)?$/, '').trim()
  if (!path) return

  filesModalOpen.value = true
  await nextTick()
  const opened = await projectFilesModalRef.value?.openFileByPath(path)
  if (opened === false) {
    // 有未保存草稿时不会静默覆盖，由用户自己决定
    message.info('已打开项目文件窗口，请先处理未保存的修改')
  }
}

async function handleStartRuntime() {
  // 导入工程只能经冻结计划与后续隔离授权运行，不走旧运行入口。
  if (!appId.value || startingRuntime.value || importedView.value || importLookupBlocked.value) return
  startingRuntime.value = true
  try {
    runtimeStatus.value = await startRuntime({ appId: appId.value, apiBaseUrl: API_BASE_URL })
    message.success('应用已启动')
    // 启动后切到预览，用户能立刻看到结果
    runtimePanelTab.value = 'preview'
    // 运行版本落后时明确提示：改过的代码不会自动生效
    if (runtimeStatus.value?.stale) {
      message.warning('当前运行的仍是上次启动时的代码，改动要重新运行才生效')
    }
  } catch (e) {
    message.error((e as Error).message || '启动失败')
    await refreshRuntimeStatus()
  } finally {
    startingRuntime.value = false
  }
}

async function handleStopRuntime() {
  if (!appId.value || stoppingRuntime.value) return
  stoppingRuntime.value = true
  try {
    await stopRuntime({ appId: appId.value, apiBaseUrl: API_BASE_URL })
    message.success('应用已停止')
  } catch (e) {
    message.error((e as Error).message || '停止失败')
  } finally {
    stoppingRuntime.value = false
    await refreshRuntimeStatus()
  }
}

/**
 * 心跳定时器。
 *
 * 页面在查看该应用时定期上报，服务端据此判断「还有人在看」——
 * 最后一个页面离开后保留一段时间再自动停止。刷新、多标签都不会误停。
 */
let runtimeHeartbeatTimer: number | null = null

function startRuntimeHeartbeat() {
  stopRuntimeHeartbeat()
  runtimeHeartbeatTimer = window.setInterval(() => {
    if (appId.value && runtimeRunning.value) {
      void sendRuntimeHeartbeat({ appId: appId.value, apiBaseUrl: API_BASE_URL })
    }
  }, 30000)
}

function stopRuntimeHeartbeat() {
  if (runtimeHeartbeatTimer != null) {
    window.clearInterval(runtimeHeartbeatTimer)
    runtimeHeartbeatTimer = null
  }
}

const previewUrl = computed(() => {
  if (!appInfo.value || !appId.value) return ''

  // 运行中：指向容器映射出来的宿主端口。
  // 这个地址与平台页面不同源，生成的应用因此拿不到平台会话凭据 —— 预览用隔离来源。
  if (runtimeRunning.value && runtimeStatus.value?.gatewayPort) {
    return `${buildRuntimePreviewUrl(runtimeStatus.value.gatewayPort)}?v=${iframeKey.value}`
  }

  const base = PREVIEW_BASE_URL.replace(/\/$/, '')
  // 使用版本号避免浏览器缓存旧内容；同时配合 iframeKey 触发刷新

  // 描述就绪时，目录名与入口都以实际产物为准
  if (workspace.value) {
    const entry = workspace.value.staticEntry
    if (!entry) return ''
    return `${base}/${workspace.value.workspaceDirName}/${entry}?v=${iframeKey.value}`
  }

  // 描述不可用（尚未返回或接口失败）时不再根据旧类型猜测入口
  return ''
})

const isAppDeployed = computed(() => {
  const key = appInfo.value?.deployKey
  return key !== undefined && key !== null && String(key).trim() !== ''
})

const deployedAccessUrl = computed(() => {
  if (!isAppDeployed.value || !appInfo.value?.deployKey) return ''
  return buildDeployUrlFromKey(appInfo.value.deployKey, API_BASE_URL)
})

function normalizeCodeGenType(raw: unknown): string {
  const rawType = String(raw ?? '').trim()
  const lower = rawType.toLowerCase()
  // 后端可能返回枚举名（HTML / MULTI_FILE / VUE_PROJECT），但静态目录使用 value（html / multi_file / vue_project）
  if (lower === CodeGenTypeEnum.HTML) return CodeGenTypeEnum.HTML
  if (lower === CodeGenTypeEnum.MULTI_FILE) return CodeGenTypeEnum.MULTI_FILE
  if (lower === CodeGenTypeEnum.VUE_PROJECT) return CodeGenTypeEnum.VUE_PROJECT
  const upper = rawType.toUpperCase()
  if (upper === 'HTML') return CodeGenTypeEnum.HTML
  if (upper === 'MULTI_FILE') return CodeGenTypeEnum.MULTI_FILE
  if (upper === 'VUE_PROJECT' || upper === 'VUE') return CodeGenTypeEnum.VUE_PROJECT
  return CodeGenTypeEnum.MULTI_FILE
}

function getPreviewEntryPath(codeGenType: string): string {
  if (codeGenType === CodeGenTypeEnum.VUE_PROJECT) return 'dist/index.html'
  return ''
}

function stopPreviewProbing() {
  if (lastPreviewProbing.value.timer != null) {
    window.clearTimeout(lastPreviewProbing.value.timer)
  }
  lastPreviewProbing.value.active = false
  lastPreviewProbing.value.timer = null
  lastPreviewProbing.value.attempts = 0
}

async function probePreviewReady(): Promise<boolean> {
  if (!appInfo.value || !appId.value) return false
  const base = PREVIEW_BASE_URL.replace(/\/$/, '')

  let url: string
  if (workspace.value) {
    // 描述就绪：只探测真实存在的入口，没有入口就不算 ready
    const entry = workspace.value.staticEntry
    if (!entry) return false
    url = `${base}/${workspace.value.workspaceDirName}/${entry}?probe=${Date.now()}`
  } else {
    const codeGenType = normalizeCodeGenType(appInfo.value.codeGenType)
    const entry = getPreviewEntryPath(codeGenType)
    const entryPart = entry ? `/${entry}` : '/index.html'
    url = `${base}/${codeGenType}_${appId.value}${entryPart}?probe=${Date.now()}`
  }
  try {
    const res = await fetch(url, { method: 'GET', credentials: 'include', cache: 'no-store' as RequestCache })
    if (!res.ok) return false
    const ct = (res.headers.get('content-type') ?? '').toLowerCase()
    // 静态入口一般为 html；若是其他类型也视为可访问（某些构建产物可能返回 application/octet-stream）
    if (ct.includes('text/html')) return true
    // 内容长度不可靠（可能 gzip / chunked），这里尽量少读：只要能读取到非空就算 ready
    const text = await res.text()
    return !!text && text.trim().length > 0
  } catch {
    return false
  }
}

function showRefreshAppCta(reason?: string) {
  refreshAppCtaVisible.value = true
  if (reason && reason.trim()) {
    refreshAppCtaHint.value = reason.trim()
  }
}

async function handleManualRefreshApp() {
  if (!appInfo.value || !appId.value) return
  refreshingApp.value = true
  try {
    // 强制刷新 iframe（key 变化会重新挂载）
    iframeKey.value += 1
    // 刷新后立即探测一次，若已 ready 则认为可预览（即使后端 hasGeneratedCode 未同步）
    const ready = await probePreviewReady()
    if (ready) {
      hasGenerated.value = true
    } else {
      // 未 ready 也保留按钮，避免用户无从操作
      showRefreshAppCta('当前预览入口暂未就绪（可能仍在写入/构建）。建议稍等片刻后再次点击“刷新应用”。')
    }
  } finally {
    refreshingApp.value = false
  }
}

async function refreshPreviewWithRetry() {
  stopPreviewProbing()
  lastPreviewProbing.value.active = true
  lastPreviewProbing.value.attempts = 0
  refreshAppCtaVisible.value = false

  const loop = async () => {
    if (!lastPreviewProbing.value.active) return
    lastPreviewProbing.value.attempts += 1
    const ready = await probePreviewReady()
    if (ready) {
      hasGenerated.value = true
      iframeKey.value += 1
      stopPreviewProbing()
      // 生成完成后仍提供托底刷新按钮（避免缓存/资源时序导致的“看不到”）
      if (isVueProject.value) {
        showRefreshAppCta(
          'Vue 项目构建产物可能受浏览器缓存或资源加载时序影响。若预览仍异常，点一次“刷新应用”可强制重载 iframe。',
        )
      }
      return
    }
    if (lastPreviewProbing.value.attempts >= lastPreviewProbing.value.maxAttempts) {
      // 最后兜底刷新一次（哪怕还没探测到），避免用户一直看旧内容
      iframeKey.value += 1
      stopPreviewProbing()
      if (isVueProject.value) {
        showRefreshAppCta(
          '已完成多次探测但入口仍可能未就绪/被缓存。你可以点击“刷新应用”强制重载预览（必要时多点几次）。',
        )
      }
      return
    }
    const delay = Math.min(2500, 500 + lastPreviewProbing.value.attempts * 250)
    lastPreviewProbing.value.timer = window.setTimeout(() => {
      void loop()
    }, delay)
  }

  void loop()
}

function appendUserMessage(text: string) {
  sessionMessages.value.push({
    id: nextMessageId++,
    role: 'user',
    content: text,
  })
  nextTick(() => {
    if (shouldAutoScroll.value) {
      scrollToBottom()
    }
  })
}

function createAssistantUiState(): AssistantUiState {
  return {
    segments: [],
    buffer: '',
    stage: 'markdown',
    lastToolRequestName: undefined,
    pendingToolRequest: null,
    segmentSeq: 0,
  }
}

function getOrCreateLastMarkdownSegment(state: AssistantUiState): UiMarkdownSegment {
  const last = state.segments[state.segments.length - 1]
  if (last && last.kind === 'markdown') return last
  const seg: UiMarkdownSegment = { kind: 'markdown', content: '' }
  state.segments.push(seg)
  return seg
}

function appendMarkdown(state: AssistantUiState, text: string) {
  if (!text) return
  const seg = getOrCreateLastMarkdownSegment(state)
  seg.content += text
}

function ensureToolExecutedSegment(
  state: AssistantUiState,
  filePath: string,
  language: string,
): UiToolExecutedWriteFileSegment {
  const seg: UiToolExecutedWriteFileSegment = {
    kind: 'tool_executed_write_file',
    filePath,
    language: language || 'text',
    content: '',
    done: false,
  }
  state.activeToolIndex = state.segments.length
  state.segments.push(seg)
  return seg
}

function getActiveToolExecutedSegment(state: AssistantUiState): UiToolExecutedWriteFileSegment | null {
  const idx = state.activeToolIndex
  if (idx == null) return null
  const seg = state.segments[idx]
  if (seg && seg.kind === 'tool_executed_write_file') return seg
  return null
}

function ensureToolExecutedModifyFileSegment(
  state: AssistantUiState,
  filePath: string,
  language: string,
): UiToolExecutedModifyFileSegment {
  const seg: UiToolExecutedModifyFileSegment = {
    kind: 'tool_executed_modify_file',
    filePath,
    language: language || 'text',
    beforeContent: '',
    afterContent: '',
    beforeDone: false,
    afterDone: false,
    done: false,
  }
  state.activeToolIndex = state.segments.length
  state.segments.push(seg)
  return seg
}

function getActiveToolExecutedModifyFileSegment(
  state: AssistantUiState,
): UiToolExecutedModifyFileSegment | null {
  const idx = state.activeToolIndex
  if (idx == null) return null
  const seg = state.segments[idx]
  if (seg && seg.kind === 'tool_executed_modify_file') return seg
  return null
}

function trimTrailingNewlinesInLastMarkdown(state: AssistantUiState, maxTrim = 2) {
  if (maxTrim <= 0) return
  const last = state.segments[state.segments.length - 1]
  if (!last || last.kind !== 'markdown') return
  let s = last.content ?? ''
  let trimmed = 0
  while (trimmed < maxTrim) {
    if (s.endsWith('\r\n')) {
      s = s.slice(0, -2)
      trimmed += 1
      continue
    }
    if (s.endsWith('\n')) {
      s = s.slice(0, -1)
      trimmed += 1
      continue
    }
    break
  }
  last.content = s
}

function consumeLeadingNewlines(text: string, maxConsume = 2): string {
  let s = text ?? ''
  let consumed = 0
  while (consumed < maxConsume) {
    if (s.startsWith('\r\n')) {
      s = s.slice(2)
      consumed += 1
      continue
    }
    if (s.startsWith('\n')) {
      s = s.slice(1)
      consumed += 1
      continue
    }
    break
  }
  return s
}

function inferLanguageFromFilePath(p: string): string {
  const lower = (p || '').toLowerCase()
  if (lower.endsWith('.vue')) return 'vue'
  if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript'
  if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript'
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'css'
  if (lower.endsWith('.html')) return 'html'
  return 'text'
}

function flushSafeMarkdown(state: AssistantUiState, keepTail = 80) {
  if (!state.buffer) return
  if (state.buffer.length <= keepTail) return

  // 代码围栏感知：当 buffer 包含 ``` 围栏标记时，确保不会把围栏开口留在 buffer
  // 中导致前端 markdown 渲染器长时间看不到代码块内容（Issue #1 流式空白）。
  // 策略：检查 keepTail 保留部分是否包含未闭合的围栏；若是，则缩小 keepTail
  // 使得围栏内容可以被 flush 到渲染器（渲染器容忍未闭合围栏，会以纯文本展示）。
  let effectiveKeepTail = keepTail
  const tailPart = state.buffer.slice(state.buffer.length - keepTail)
  const fenceOpenCount = (tailPart.match(/```\w*/g) || []).length
  const fenceCloseCount = (tailPart.match(/```(?:\s|$)/g) || []).length
  // 如果 keepTail 部分有围栏开口但没有对应闭合，说明代码块内容被阻塞
  if (fenceOpenCount > fenceCloseCount) {
    // 缩小 keepTail 到围栏开口之前，让代码内容流出
    const lastFenceOpen = tailPart.lastIndexOf('```')
    if (lastFenceOpen >= 0) {
      effectiveKeepTail = keepTail - lastFenceOpen
      if (effectiveKeepTail < 20) effectiveKeepTail = 20
    }
  }

  const flushLen = state.buffer.length - effectiveKeepTail
  if (flushLen <= 0) return
  const part = state.buffer.slice(0, flushLen)
  state.buffer = state.buffer.slice(flushLen)
  appendMarkdown(state, part)
}

/**
 * 流式结束（DONE / 业务错误 / 用户停止）时把残留 buffer 落盘到对应 active 段，
 * 让 m.uiState 与 buildUiSegmentsFromFullText 历史回放保持一致。
 *
 * 工具流式阶段必须把尾巴落到 active tool 段；其余情况合并进 markdown segment。
 */
function drainBufferToSegments(state: AssistantUiState) {
  if (!state.buffer) return
  if (state.stage === 'tool_exec_stream') {
    const tool = getActiveToolExecutedSegment(state)
    if (tool) {
      tool.content = normalizeFenceCodeContent(tool.content + state.buffer)
    } else appendMarkdown(state, state.buffer)
  } else if (state.stage === 'tool_exec_modify_stream_before') {
    const seg = getActiveToolExecutedModifyFileSegment(state)
    if (seg) {
      seg.beforeContent = normalizeFenceCodeContent(seg.beforeContent + state.buffer)
    } else appendMarkdown(state, state.buffer)
  } else if (state.stage === 'tool_exec_modify_stream_after') {
    const seg = getActiveToolExecutedModifyFileSegment(state)
    if (seg) {
      seg.afterContent = normalizeFenceCodeContent(seg.afterContent + state.buffer)
    } else appendMarkdown(state, state.buffer)
  } else {
    appendMarkdown(state, state.buffer)
  }
  state.buffer = ''
  state.pendingToolRequest = null
}

function processAssistantChunkIntoUiState(state: AssistantUiState, chunk: string) {
  state.buffer += chunk ?? ''

  const TOOL_REQUEST_ANY_RE = /\[选择工具\]\s*([^\r\n]+)/

  const TOOL_EXEC_HEADER_RE_WRITE_FILE = /\[工具调用\]\s*写入文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
  const TOOL_EXEC_HEADER_RE_MODIFY_FILE = /\[工具调用\]\s*修改文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
  const TOOL_EXEC_HEADER_RE_DELETE_FILE = /\[工具调用\]\s*删除文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
  const TOOL_EXEC_HEADER_RE_READ_FILE = /\[工具调用\]\s*读取文件\s+([^\r\n]+)\s*(?:\r?\n|$)/
  const TOOL_EXEC_HEADER_RE_READ_DIR = /\[工具调用\]\s*读取目录结构\s+([^\r\n]+)\s*(?:\r?\n|$)/
  const clearPendingToolRequest = () => {
    state.pendingToolRequest = null
  }

  const loopGuardMax = 2000
  let loopGuard = 0

  while (state.buffer && loopGuard++ < loopGuardMax) {
    if (state.stage === 'markdown') {
      const mReqAny = TOOL_REQUEST_ANY_RE.exec(state.buffer)
      const idxRequestAny = mReqAny ? (mReqAny.index ?? -1) : -1

      const mExecWrite = state.buffer.match(TOOL_EXEC_HEADER_RE_WRITE_FILE)
      const idxExecWrite = mExecWrite ? (mExecWrite.index ?? -1) : -1
      const mExecModify = state.buffer.match(TOOL_EXEC_HEADER_RE_MODIFY_FILE)
      const idxExecModify = mExecModify ? (mExecModify.index ?? -1) : -1
      const mExecDelete = state.buffer.match(TOOL_EXEC_HEADER_RE_DELETE_FILE)
      const idxExecDelete = mExecDelete ? (mExecDelete.index ?? -1) : -1
      const mExecRead = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_FILE)
      const idxExecRead = mExecRead ? (mExecRead.index ?? -1) : -1
      const mExecDir = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_DIR)
      const idxExecDir = mExecDir ? (mExecDir.index ?? -1) : -1

      // 没有任何工具标记：尽量流式把 buffer 大部分吐到 markdown
      if (
        idxRequestAny < 0 &&
        idxExecWrite < 0 &&
        idxExecModify < 0 &&
        idxExecDelete < 0 &&
        idxExecRead < 0 &&
        idxExecDir < 0
      ) {
        flushSafeMarkdown(state, 80)
        break
      }

      // 找到最靠前的事件
      const candidates: Array<{
        idx: number
        type:
          | 'tool_request_any'
          | 'tool_exec_write'
          | 'tool_exec_modify'
          | 'tool_exec_delete'
          | 'tool_exec_read'
          | 'tool_exec_dir'
        toolName?: string
      }> = []

      if (idxRequestAny >= 0) {
        const toolName = (mReqAny?.[1] ?? '').trim()
        candidates.push({ idx: idxRequestAny, type: 'tool_request_any', toolName })
      }
      if (idxExecWrite >= 0) {
        candidates.push({ idx: idxExecWrite, type: 'tool_exec_write' })
      }
      if (idxExecModify >= 0) {
        candidates.push({ idx: idxExecModify, type: 'tool_exec_modify' })
      }
      if (idxExecDelete >= 0) {
        candidates.push({ idx: idxExecDelete, type: 'tool_exec_delete' })
      }
      if (idxExecRead >= 0) {
        candidates.push({ idx: idxExecRead, type: 'tool_exec_read' })
      }
      if (idxExecDir >= 0) {
        candidates.push({ idx: idxExecDir, type: 'tool_exec_dir' })
      }

      candidates.sort((a, b) => a.idx - b.idx)
      const next = candidates[0]
      if (!next) {
        flushSafeMarkdown(state, 80)
        break
      }

      // 先把事件前面的普通文本作为 markdown 吐出
      const before = state.buffer.slice(0, next.idx)
      if (before) appendMarkdown(state, before)
      state.buffer = state.buffer.slice(next.idx)

      if (next.type === 'tool_request_any') {
        trimTrailingNewlinesInLastMarkdown(state, 2)

        const mReqNow = TOOL_REQUEST_ANY_RE.exec(state.buffer)
        if (!mReqNow) {
          flushSafeMarkdown(state, 40)
          break
        }

        const toolName = (mReqNow[1] ?? next.toolName ?? '').trim() || '未知工具'

        // 去重：同轮相同工具名只保留第一次出现，避免模型重复调用产生多个相同卡片
        if (state.lastToolRequestName === toolName) {
          // 消费掉匹配文本但不创建新 segment
          const matchLen2 = mReqNow[0].length
          state.buffer = state.buffer.slice((mReqNow.index ?? 0) + matchLen2)
          state.buffer = consumeLeadingNewlines(state.buffer, 2)
          continue
        }

        const matchStart = mReqNow.index ?? 0
        const matchLen = mReqNow[0].length
        const afterMatch = state.buffer.slice(matchStart + matchLen)

        state.lastToolRequestName = toolName
        toolSelectHintShown.value = true

        const rawLabel = mReqNow[0] ?? `[选择工具] ${toolName}`
        state.segments.push({
          kind: 'tool_request',
          rawLabel,
          toolName,
          createdAt: Date.now(),
          tier2ThresholdMs: generateTier2Threshold(toolName),
        })

        if (afterMatch.startsWith('\r\n')) {
          state.buffer = state.buffer.slice(matchStart + matchLen + 2)
          state.buffer = consumeLeadingNewlines(state.buffer, 2)
        } else if (afterMatch.startsWith('\n')) {
          state.buffer = state.buffer.slice(matchStart + matchLen + 1)
          state.buffer = consumeLeadingNewlines(state.buffer, 2)
        } else if (matchStart + matchLen >= state.buffer.length) {
          break
        } else {
          state.buffer = state.buffer.slice(matchStart + matchLen)
          state.buffer = consumeLeadingNewlines(state.buffer, 2)
        }
        continue
      }

      if (next.type === 'tool_exec_write') {
        // TOOL_EXEC(writeFile)：消费 header 行，并进入等待 fence
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_WRITE_FILE)
        if (!m) {
          // header 可能被 chunk 截断
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        clearPendingToolRequest()
        state.pendingFilePath = filePath
        // 进入真实工具执行后，重置“选择工具”去重状态，允许下一轮提示正常出现。
        state.lastToolRequestName = undefined
        state.stage = 'tool_exec_wait_fence'
        continue
      }

      if (next.type === 'tool_exec_modify') {
        // TOOL_EXEC(modifyFile)：创建卡片并进入解析“替换前/替换后”围栏
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_MODIFY_FILE)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        clearPendingToolRequest()
        // 与 writeFile 一致：执行开始后清空去重状态。
        state.lastToolRequestName = undefined

        const lang = inferLanguageFromFilePath(filePath)
        ensureToolExecutedModifyFileSegment(state, filePath, lang)
        state.pendingFilePath = filePath
        state.pendingLanguage = lang
        state.pendingModifyPart = 'before'
        state.stage = 'tool_exec_modify_wait_before_fence'
        continue
      }

      if (next.type === 'tool_exec_delete') {
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_DELETE_FILE)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        clearPendingToolRequest()
        state.lastToolRequestName = undefined

        removeGeneratedFile(filePath)

        state.segments.push({
          kind: 'tool_executed_simple_tool',
          toolTitle: '[工具调用] 删除文件',
          filePath,
          message: `删除文件：${filePath}`,
          done: true,
        })
        continue
      }

      if (next.type === 'tool_exec_read') {
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_FILE)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        clearPendingToolRequest()
        state.lastToolRequestName = undefined

        state.segments.push({
          kind: 'tool_executed_simple_tool',
          toolTitle: '[工具调用] 读取文件',
          filePath,
          message: `读取文件：${filePath}`,
          done: true,
        })
        continue
      }

      if (next.type === 'tool_exec_dir') {
        const m = state.buffer.match(TOOL_EXEC_HEADER_RE_READ_DIR)
        if (!m) {
          flushSafeMarkdown(state, 120)
          break
        }
        const filePath = (m[1] ?? '').trim()
        const headerLen = m[0].length
        state.buffer = state.buffer.slice(headerLen)
        clearPendingToolRequest()
        state.lastToolRequestName = undefined

        state.segments.push({
          kind: 'tool_executed_simple_tool',
          toolTitle: '[工具调用] 读取目录结构',
          filePath,
          message: `目录结构：${filePath}`,
          done: true,
        })
        continue
      }
    }

    if (state.stage === 'tool_exec_wait_fence') {
      // 后端 FileWriteTool 的工具执行结果格式为：
      // [工具调用] 写入文件 xxx\n
      // 文件内容:\n
      // ```lang\n
      // ...
      // 因为 streaming chunk 可能被截断，所以这里允许 header 后的：
      // 1) 空行/换行
      // 2) 可选“文件内容:”行
      // 3) 围栏前最多 0-3 空格缩进
      state.buffer = consumeLeadingNewlines(state.buffer, 3)
      state.buffer = state.buffer.replace(/^\s*文件内容:\s*\r?\n/, '')
      state.buffer = consumeLeadingNewlines(state.buffer, 2)

      // 期望：``` 或更多反引号 + 可选语言/信息 + 换行（与 markdown 围栏一致）
      const fenceRe = /^ {0,3}(`{3,})([^\r\n]*)\r?\n/
      const mFence = state.buffer.match(fenceRe)
      if (!mFence) {
        // 证据：这里如果把等待中的片段误吐到 markdown，会打断「文件内容 + 围栏」序列，
        // 导致后续即使收齐围栏也无法进入代码流式卡片（用户只看到工具气泡）。
        // 修复：等待围栏阶段只缓存，不向 markdown 外吐。
        break
      }
      const fenceRunLen = (mFence[1] ?? '```').length
      const info = (mFence[2] ?? '').trim()
      const lang = info.split(/\s+/)[0] || 'text'
      state.pendingLanguage = lang
      state.pendingFenceLen = fenceRunLen
      state.buffer = state.buffer.slice(mFence[0].length)
      ensureToolExecutedSegment(state, state.pendingFilePath ?? '', lang)
      state.stage = 'tool_exec_stream'
      continue
    }

    if (state.stage === 'tool_exec_stream') {
      // tool_exec_stream：流式追加到卡片内部代码区，直到遇到 closing fence
      const toolSeg = getActiveToolExecutedSegment(state)
      if (!toolSeg) {
        // 防御：状态异常则退回 markdown
        state.stage = 'markdown'
        continue
      }

      const minRun = state.pendingFenceLen ?? 3
      const closeRange = findLineAlignedClosingFenceInBuffer(state.buffer, minRun)

      if (closeRange) {
        const codePart = state.buffer.slice(0, closeRange.codeEnd)
        toolSeg.content = normalizeFenceCodeContent(toolSeg.content + codePart)
        toolSeg.done = true
        if (toolSeg.filePath) {
          recordGeneratedFile(toolSeg.filePath, toolSeg.language, toolSeg.content)
        }
        state.buffer = state.buffer.slice(closeRange.consumeEnd)

        state.pendingFilePath = undefined
        state.pendingLanguage = undefined
        state.pendingFenceLen = undefined
        state.activeToolIndex = undefined
        state.stage = 'markdown'
        continue
      }

      // 没有结束 fence：尽量把 buffer 大部分作为代码流式追加（保留少量 tail 防止 fence 被截断）
      if (state.buffer.length > 120) {
        const flushLen = state.buffer.length - 40
        toolSeg.content += state.buffer.slice(0, flushLen)
        state.buffer = state.buffer.slice(flushLen)
        break
      }
      break
    }

    if (state.stage === 'tool_exec_modify_wait_before_fence') {
      // 期望 token 序列：
      // 替换前:\n
      // ```(\n|lang...\n)
      state.buffer = consumeLeadingNewlines(state.buffer, 3)
      state.buffer = state.buffer.replace(/^\s*替换前:\s*\r?\n/, '')
      state.buffer = consumeLeadingNewlines(state.buffer, 2)

      const fenceRe = /^ {0,3}(`{3,})([^\r\n]*)\r?\n/
      const mFence = state.buffer.match(fenceRe)
      if (!mFence) {
        // 同上：等待 `替换前` 围栏时仅缓存，避免破坏后续代码块解析。
        break
      }

      const fenceRunLen = (mFence[1] ?? '```').length
      const info = (mFence[2] ?? '').trim()
      const langFromFence = info.split(/\s+/)[0] || ''

      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (seg && langFromFence) {
        seg.language = langFromFence
      }

      state.pendingFenceLen = fenceRunLen
      state.buffer = state.buffer.slice(mFence[0].length)
      state.pendingModifyPart = 'before'
      state.stage = 'tool_exec_modify_stream_before'
      continue
    }

    if (state.stage === 'tool_exec_modify_stream_before') {
      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (!seg) {
        state.stage = 'markdown'
        continue
      }

      const minRun = state.pendingFenceLen ?? 3
      const closeRange = findLineAlignedClosingFenceInBuffer(state.buffer, minRun)
      if (closeRange) {
        const codePart = state.buffer.slice(0, closeRange.codeEnd)
        seg.beforeContent = normalizeFenceCodeContent(seg.beforeContent + codePart)
        seg.beforeDone = true
        state.buffer = state.buffer.slice(closeRange.consumeEnd)
        state.stage = 'tool_exec_modify_wait_after_fence'
        continue
      }

      if (state.buffer.length > 120) {
        const flushLen = state.buffer.length - 40
        seg.beforeContent += state.buffer.slice(0, flushLen)
        state.buffer = state.buffer.slice(flushLen)
        break
      }
      break
    }

    if (state.stage === 'tool_exec_modify_wait_after_fence') {
      state.buffer = consumeLeadingNewlines(state.buffer, 3)
      state.buffer = state.buffer.replace(/^\s*替换后:\s*\r?\n/, '')
      state.buffer = consumeLeadingNewlines(state.buffer, 2)

      const fenceRe = /^ {0,3}(`{3,})([^\r\n]*)\r?\n/
      const mFence = state.buffer.match(fenceRe)
      if (!mFence) {
        // 同上：等待 `替换后` 围栏时仅缓存，避免“只显示工具调用，不显示代码块”。
        break
      }

      const fenceRunLen = (mFence[1] ?? '```').length
      const info = (mFence[2] ?? '').trim()
      const langFromFence = info.split(/\s+/)[0] || ''

      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (seg && langFromFence) {
        seg.language = langFromFence
      }

      state.pendingFenceLen = fenceRunLen
      state.buffer = state.buffer.slice(mFence[0].length)
      state.pendingModifyPart = 'after'
      state.stage = 'tool_exec_modify_stream_after'
      continue
    }

    if (state.stage === 'tool_exec_modify_stream_after') {
      const seg = getActiveToolExecutedModifyFileSegment(state)
      if (!seg) {
        state.stage = 'markdown'
        continue
      }

      const minRun = state.pendingFenceLen ?? 3
      const closeRange = findLineAlignedClosingFenceInBuffer(state.buffer, minRun)
      if (closeRange) {
        const codePart = state.buffer.slice(0, closeRange.codeEnd)
        seg.afterContent = normalizeFenceCodeContent(seg.afterContent + codePart)
        seg.afterDone = true
        seg.done = true

        if (seg.filePath) {
          recordGeneratedFile(seg.filePath, seg.language, seg.afterContent)
        }

        state.buffer = state.buffer.slice(closeRange.consumeEnd)
        state.pendingFilePath = undefined
        state.pendingLanguage = undefined
        state.pendingFenceLen = undefined
        state.pendingModifyPart = undefined
        state.activeToolIndex = undefined
        state.stage = 'markdown'
        continue
      }

      if (state.buffer.length > 120) {
        const flushLen = state.buffer.length - 40
        seg.afterContent += state.buffer.slice(0, flushLen)
        state.buffer = state.buffer.slice(flushLen)
        break
      }
      break
    }

    break
  }
}

function appendAssistantMessageChunk(chunk: string) {
  const last = sessionMessages.value[sessionMessages.value.length - 1]
  if (!last || last.role !== 'assistant') {
    sessionMessages.value.push({
      id: nextMessageId++,
      role: 'assistant',
      content: chunk,
      uiState: createAssistantUiState(),
    })
    const created = sessionMessages.value[sessionMessages.value.length - 1]
    if (created?.uiState) {
      processAssistantChunkIntoUiState(created.uiState, chunk)
    }
    nextTick(() => {
      if (shouldAutoScroll.value) scrollToBottom()
    })
    return
  }
  last.content += chunk
  if (!last.uiState) last.uiState = createAssistantUiState()
  processAssistantChunkIntoUiState(last.uiState, chunk)
  nextTick(() => {
    if (shouldAutoScroll.value) scrollToBottom()
  })
}

function showGeneratedFilesModal() {
  filesModalOpen.value = true
  const list = generatedFiles.value
  const last = list.length > 0 ? (list[list.length - 1] as GeneratedFileItem | undefined) : undefined
  if (last) activeFilePath.value = last.path
}

function parseToolWriteFileBlock(chunk: string) {
  const list = extractToolWriteFileBlocksFromText(chunk ?? '')
  const first = list[0]
  if (!first) return null
  return { filePath: first.filePath, lang: first.lang ?? '', content: first.content }
}

function extractToolWriteFileBlocks(text: string): Array<{ filePath: string; lang?: string; content: string }> {
  return extractToolWriteFileBlocksFromText(text ?? '')
}

function recordGeneratedFile(filePath: string, language: string | undefined, content: string) {
  // 锁文件不进入回显列表（磁盘保留，构建正常使用）
  if (isLockFilePath(filePath)) return
  const now = Date.now()
  const existing = generatedFileMap.value[filePath]
  const normalizedContent = normalizeFenceCodeContent(content)
  const item: GeneratedFileItem = {
    path: filePath,
    language: language || existing?.language,
    content: normalizedContent,
    updatedAt: now,
  }
  generatedFileMap.value = {
    ...generatedFileMap.value,
    [filePath]: item,
  }
  if (!existing) {
    generatedFiles.value = [...generatedFiles.value, item]
  } else {
    generatedFiles.value = generatedFiles.value.map((it) => (it.path === filePath ? item : it))
  }
}

const LOCK_FILE_PATTERNS = ['package-lock.json', 'yarn.lock', 'pnpm-lock.yaml']

function isLockFilePath(filePath: string): boolean {
  const normalized = filePath.replace(/\\/g, '/')
  const baseName = normalized.split('/').pop() ?? ''
  return LOCK_FILE_PATTERNS.includes(baseName)
}

function formatFileTime(ts: number | string): string {
  const d = new Date(Number(ts) || 0)
  if (isNaN(d.getTime())) return ''
  return d.toLocaleTimeString('zh-CN')
}

function removeGeneratedFile(filePath: string) {
  if (!filePath) return
  const existing = generatedFileMap.value[filePath]
  if (!existing) return

  const nextMap = { ...generatedFileMap.value }
  delete nextMap[filePath]
  generatedFileMap.value = nextMap

  generatedFiles.value = generatedFiles.value.filter((it) => it.path !== filePath)
}

function getFileNameColorClass(path: string): string {
  const lower = path.toLowerCase()
  if (lower.endsWith('.vue')) return 'file-path--vue'
  if (lower.endsWith('.ts') || lower.endsWith('.tsx') || lower.endsWith('.js') || lower.endsWith('.jsx'))
    return 'file-path--script'
  if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'file-path--style'
  if (lower.endsWith('.html')) return 'file-path--html'
  if (
    lower.endsWith('package.json') ||
    lower.endsWith('vite.config.js') ||
    lower.endsWith('tsconfig.json') ||
    lower.endsWith('eslint.config.js') ||
    lower.endsWith('postcss.config.js')
  ) {
    return 'file-path--config'
  }
  if (/\.(png|jpg|jpeg|gif|svg|webp)$/.test(lower)) return 'file-path--asset'
  return 'file-path--default'
}

function isCoreFile(path: string): boolean {
  const lower = path.toLowerCase()
  if (lower.includes('/src/main.') || lower.includes('\\src\\main.')) return true
  if (lower.endsWith('/src/app.vue') || lower.endsWith('\\src\\app.vue')) return true
  if (lower.endsWith('/src/router/index.js') || lower.endsWith('\\src\\router\\index.js')) return true
  if (lower.endsWith('/src/router/index.ts') || lower.endsWith('\\src\\router\\index.ts')) return true
  if (lower.includes('/src/pages/') || lower.includes('\\src\\pages\\')) return true
  if (lower.endsWith('/src/styles/global.css') || lower.endsWith('\\src\\styles\\global.css')) return true
  if (lower.endsWith('/index.html') && !lower.includes('node_modules')) return true
  if (lower.endsWith('/vite.config.js') || lower.endsWith('\\vite.config.js')) return true
  if (lower.endsWith('/package.json') || lower.endsWith('\\package.json')) return true
  return false
}

function maybeInjectToolSelectHint(chunk: string) {
  // 仅在后端单独返回 "[选择工具] 写入文件" 这句话时，标记已经展示过提示；
  // 不再自动插入额外文本，避免与真正的 "[工具调用] 写入文件 xxx + 代码块" 混在一起。
  if (toolSelectHintShown.value) return
  const text = (chunk || '').trim()
  if (text === '[选择工具] 写入文件') {
    toolSelectHintShown.value = true
  }
}

function applyGenerationStatusToSegments(segments: UiSegment[], rawText: string): UiSegment[] {
  return injectGenerationStatusSegments(segments, rawText) as UiSegment[]
}

function buildUiSegmentsFromFullText(fullText: string): UiSegment[] {
  const state = createAssistantUiState()
  processAssistantChunkIntoUiState(state, fullText ?? '')
  // 最后把残留 buffer 全部作为 markdown 吐出
  if (state.buffer) {
    appendMarkdown(state, state.buffer)
    state.buffer = ''
  }
  // 若异常情况下仍在 tool_exec_stream，则也视为未完成（允许历史里展示“未闭合”的工具卡片）
  const segments = state.segments.filter((s) => {
    if (s.kind !== 'markdown') return true
    return !!s.content && s.content.trim().length > 0
  })
  return applyGenerationStatusToSegments(segments, fullText ?? '')
}

function isFirstSessionAssistantMessage(m: ChatMessage): boolean {
  if (m.role !== 'assistant' || !!m.createTime) return false
  const firstAssistant = sessionMessages.value.find((item) => item.role === 'assistant' && !item.createTime)
  return firstAssistant?.id === m.id
}

/** 应用生命周期内最早的一条助手消息（首轮创建应用时的 AI 回复） */
function isFirstAppAssistantMessage(m: ChatMessage): boolean {
  if (m.role !== 'assistant') return false
  const firstAssistant = displayMessages.value.find((item) => item.role === 'assistant')
  if (!firstAssistant) return false
  return firstAssistant.id === m.id && firstAssistant.createTime === m.createTime
}

/** 当前是否已有历史/会话中的助手回复（用于判断是否为首轮生成） */
function hasExistingAssistantMessages(): boolean {
  return displayMessages.value.some((item) => item.role === 'assistant')
}

/** 是否展示工作流进度卡片：仅 is_beta=1 的工作流应用 + 全应用首条助手消息 */
function shouldShowWorkflowCard(m: ChatMessage): boolean {
  if (m.role !== 'assistant') return false
  if (!isAppWorkflowBeta()) return false
  if (!isFirstAppAssistantMessage(m)) return false
  return computeWorkflowStepsForMessage(m).length > 0
}

function normalizeFirstRoundMarkdownText(text: string): string {
  if (!text) return ''
  let normalized = text.replace(/\r\n/g, '\n')
  normalized = normalized.replace(/^(?:[ \t]*\n)+/, '')
  normalized = normalized.replace(/(?:\n[ \t]*)+$/, '')
  // 保持段落感：最多保留 2 个连续换行（即 1 个空行）。
  // 含围栏代码块时不改中间内容，避免误伤代码格式。
  if (!normalized.includes('```')) {
    normalized = normalized.replace(/\n{3,}/g, '\n\n')
  }
  return normalized
}

function normalizeFirstRoundUiSegments(m: ChatMessage, segments: UiSegment[]): UiSegment[] {
  if (!isFirstSessionAssistantMessage(m)) return segments
  return segments
    .map((segment) => {
      if (segment.kind !== 'markdown') return segment
      return {
        kind: 'markdown' as const,
        content: normalizeFirstRoundMarkdownText(segment.content),
      }
    })
    .filter((segment) => segment.kind !== 'markdown' || segment.content.trim().length > 0)
}

/** 仅附加在发给模型的 prompt 末尾，不应在用户气泡中展示 */
const VISUAL_EDIT_MODEL_ONLY_HINT =
  '请根据以上元素信息，对该元素及其相关区域进行定向修改/优化,注意只能修改用户选中的区域,其他区域不可动,并不要再下面的对话中提到这句话'

function stripVisualEditModelOnlyHint(text: string): string {
  const t = text ?? ''
  if (!t.includes(VISUAL_EDIT_MODEL_ONLY_HINT)) return t
  const withBreak = `\n\n${VISUAL_EDIT_MODEL_ONLY_HINT}`
  if (t.endsWith(withBreak)) {
    return t.slice(0, -withBreak.length).trimEnd()
  }
  if (t.endsWith(VISUAL_EDIT_MODEL_ONLY_HINT)) {
    return t.slice(0, -VISUAL_EDIT_MODEL_ONLY_HINT.length).replace(/\n+$/, '').trimEnd()
  }
  return t
}

function computeWorkflowStepsForMessage(m: ChatMessage): WorkflowStepRow[] {
  if (m.role !== 'assistant') return []
  const historyMode = isHistoryMessage(m)
  const streaming = isStreamActiveForMessage(m)
  const historyOutcome = historyMode
    ? resolveWorkflowHistoryOutcomeFromContent(m.content ?? '')
    : 'unknown'
  const version = buildWorkflowRenderVersion(m, { historyMode, streaming, historyOutcome })
  const cache = messageRenderCache.get(m)
  if (cache?.workflowVersion === version && cache.workflowSteps) {
    return cache.workflowSteps
  }
  const raw = resolveWorkflowStepsFromMessageContent(m.workflowSteps, m.content ?? '')
  const normalized = normalizeWorkflowStepsForUi(raw, {
    historyMode,
    streaming,
    historyOutcome,
    rawContent: m.content ?? '',
  })
  messageRenderCache.set(m, {
    ...cache,
    workflowVersion: version,
    workflowSteps: normalized,
  })
  if (historyMode) {
    m.cachedWorkflowSteps = normalized
  }
  return normalized
}

function mergeWorkflowStepsIntoMessage(m: ChatMessage, steps: WorkflowStepRow[]) {
  m.workflowSteps = mergeWorkflowSteps(m.workflowSteps, steps)
  nextTick(() => {
    const list = chatMessagesRef.value?.querySelector<HTMLElement>(
      `[data-workflow-message-id="${m.id}"]`,
    )
    scrollElementToEnd(list)
  })
}

function getWorkflowStepsForMessage(m: ChatMessage): WorkflowStepRow[] {
  if (!shouldShowWorkflowCard(m)) return []
  return computeWorkflowStepsForMessage(m)
}

function getWorkflowLatestStepForMessage(m: ChatMessage): WorkflowStepRow | null {
  const rows = getWorkflowStepsForMessage(m)
  if (!rows.length) return null
  return rows[rows.length - 1] ?? null
}

function getWorkflowStepLabelClass(m: ChatMessage, row: WorkflowStepRow): string {
  if (!isHistoryMessage(m)) return ''
  if (row.status === 'success') return 'workflow-step-label--success'
  if (row.status === 'failed') return 'workflow-step-label--failed'
  if (row.status === 'pending') return 'workflow-step-label--pending'
  return ''
}

function isCodeGeneratingLabel(label: string): boolean {
  const text = (label ?? '').trim()
  if (!text) return false
  return /代码生成中(\.\.\.|…)?$/.test(text) || /代码生成/.test(text)
}

function shouldShowWorkflowSlowHint(m: ChatMessage): boolean {
  if (!isStreamActiveForMessage(m)) return false
  if (!m.workflowStreamStartedAt) return false
  const latest = getWorkflowLatestStepForMessage(m)
  if (!latest || !isCodeGeneratingLabel(latest.label)) return false
  return workflowNowTs.value - m.workflowStreamStartedAt >= WORKFLOW_SLOW_HINT_DELAY_MS
}

function workflowMessageKey(m: ChatMessage): string {
  return `${m.createTime ? 'h' : 's'}:${m.id}:${m.createTime ?? ''}`
}

function isHistoryMessage(m: ChatMessage): boolean {
  return !!m.createTime && !isStreamActiveForMessage(m)
}

function isWorkflowCollapsed(m: ChatMessage): boolean {
  if (!isHistoryMessage(m)) return false
  const key = workflowMessageKey(m)
  return workflowCollapsedMap.value[key] ?? true
}

function toggleWorkflowCollapsed(m: ChatMessage) {
  const key = workflowMessageKey(m)
  const current = workflowCollapsedMap.value[key] ?? true
  workflowCollapsedMap.value = { ...workflowCollapsedMap.value, [key]: !current }
}

function hasMermaidNoticeForMessage(m: ChatMessage): boolean {
  if (m.mermaidErrorNotice) return true
  return (m.content ?? '').includes('[workflow_notice] mermaid_error')
}

function buildUserMessageUiSegments(content: string): UiSegment[] {
  const cleaned = stripVisualEditModelOnlyHint(content ?? '')
  const { userText, visualEdit } = splitUserMessageForDisplay(cleaned)
  const segments: UiSegment[] = []
  if (userText.trim()) {
    segments.push({ kind: 'markdown', content: userText })
  }
  if (visualEdit) {
    segments.push({ kind: 'visual_edit_selection', ...visualEdit })
  }
  return segments.length > 0 ? segments : [{ kind: 'markdown', content: '' }]
}

function getUserMarkdownSegments(m: ChatMessage): UiMarkdownSegment[] {
  return getMessageUiSegments(m).filter((s): s is UiMarkdownSegment => s.kind === 'markdown')
}

function getUserVisualEditSegment(m: ChatMessage): UiVisualEditSelectionSegment | null {
  const seg = getMessageUiSegments(m).find((s) => s.kind === 'visual_edit_selection')
  return seg && seg.kind === 'visual_edit_selection' ? seg : null
}

const visualEditTextExpandedKeys = ref<Set<string>>(new Set())

function visualEditExpandKey(m: ChatMessage): string {
  return `${m.createTime ?? 'session'}-${m.id}`
}

function isVisualEditTextExpanded(m: ChatMessage): boolean {
  return visualEditTextExpandedKeys.value.has(visualEditExpandKey(m))
}

function toggleVisualEditTextExpanded(m: ChatMessage) {
  const key = visualEditExpandKey(m)
  const next = new Set(visualEditTextExpandedKeys.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  visualEditTextExpandedKeys.value = next
}

function shouldTruncateVisualEditText(value: string): boolean {
  return value.length > 120 || value.split('\n').length > 3
}

function getVisualEditTextPreview(value: string, expanded: boolean): string {
  if (!shouldTruncateVisualEditText(value) || expanded) return value
  const lines = value.split('\n')
  if (lines.length > 3) {
    return `${lines.slice(0, 3).join('\n')}…`
  }
  return `${value.slice(0, 120)}…`
}

function getMessageUiSegments(m: ChatMessage): UiSegment[] {
  if (m.role === 'user') {
    return buildUserMessageUiSegments(m.content ?? '')
  }
  if (m.role !== 'assistant') {
    return [{ kind: 'markdown', content: m.content ?? '' }]
  }
  // 先查缓存（含流式消息），避免模板每次 render 都重新 slice/合并/归一化
  const version = buildUiRenderVersion(m)
  const cache = messageRenderCache.get(m)
  if (cache?.uiVersion === version && cache.uiSegments) {
    return normalizeFirstRoundUiSegments(m, cache.uiSegments)
  }
  const segments = computeMessageUiSegments(m)
  messageRenderCache.set(m, {
    ...cache,
    uiVersion: version,
    uiSegments: segments,
  })
  if (isHistoryMessage(m)) {
    m.cachedUiSegments = segments
  }
  return normalizeFirstRoundUiSegments(m, segments)
}

/** 为每个 segment 生成稳定 segmentId，用作 :key，避免 idx 导致 remount */
function stampSegmentIds(segments: UiSegment[]): UiSegment[] {
  let seq = 0
  return segments.map((seg) => {
    if (seg.segmentId) return seg
    const id =
      seg.kind === 'tool_request'
        ? `tool-req-${seq}`
        : seg.kind === 'markdown'
          ? `md-${seq}`
          : `tool-exec-${seq}`
    seq++
    return { ...seg, segmentId: id }
  })
}

function computeMessageUiSegments(m: ChatMessage): UiSegment[] {
  const persistedThinking = parseThinkingSegments(m.thinkingSegments)
  if (m.uiState) {
    // 流式 buffer 必须与最后一个 markdown segment 合并展示，否则
    // `flushSafeMarkdown` 在字符位置切分时会把开围栏 ```html``` 留在 segment[0]、
    // 把代码尾巴留在 buffer 这个独立 segment 中，导致 `parseMarkdownWithCode`
    // 对 buffer 那一段看不到开围栏，把代码当成普通文本渲染（白底溢出）。
    const segs = m.uiState.segments.slice()
    const buf = m.uiState.buffer ?? ''
    if (buf) {
      // 工具流式阶段：buffer 是正在持续 flush 进 active tool 段的代码尾巴，
      // 不能再把它在 tool 卡片之外作为 markdown 展示，否则会形成卡片外的小尾巴泄漏
      const stage = m.uiState.stage
      const inToolStreamStage =
        stage === 'tool_exec_stream' ||
        stage === 'tool_exec_modify_stream_before' ||
        stage === 'tool_exec_modify_stream_after'

      if (!inToolStreamStage) {
        const last = segs[segs.length - 1]
        if (last && last.kind === 'markdown') {
          // 浅拷贝替换，避免污染 m.uiState.segments 内的对象引用
          segs[segs.length - 1] = { kind: 'markdown', content: last.content + buf }
        } else if (buf.trim()) {
          segs.push({ kind: 'markdown', content: buf })
        }
      }
    }
    return stampSegmentIds(normalizeFirstRoundUiSegments(m, [...persistedThinking, ...applyGenerationStatusToSegments(segs, m.content ?? '')]))
  }
  return stampSegmentIds([...persistedThinking, ...buildUiSegmentsFromFullText(stripAssistantNoiseLines(m.content ?? ''))])
}

function assistantHasNonWorkflowRenderableOutput(m: ChatMessage): boolean {
  if (m.role !== 'assistant') return true
  const segs = getMessageUiSegments(m)
  for (const s of segs) {
    if (s.kind === 'generation_status') return true
    if (s.kind !== 'markdown') return true
    if ((s.content ?? '').trim().length > 0) return true
  }
  return false
}

function assistantHasRenderableOutput(m: ChatMessage): boolean {
  if (m.role !== 'assistant') return true
  const workflowVersion = buildWorkflowRenderVersion(m, {
    historyMode: isHistoryMessage(m),
    streaming: isStreamActiveForMessage(m),
  })
  const uiVersion = buildUiRenderVersion(m)
  const combinedVersion = `${workflowVersion}|${uiVersion}`
  const cache = messageRenderCache.get(m)
  if (cache?.renderableVersion === combinedVersion && typeof cache.renderable === 'boolean') {
    return cache.renderable
  }
  if (getWorkflowStepsForMessage(m).length > 0) {
    messageRenderCache.set(m, {
      ...cache,
      renderableVersion: combinedVersion,
      renderable: true,
    })
    return true
  }
  const hasUi = assistantHasNonWorkflowRenderableOutput(m)
  messageRenderCache.set(m, {
    ...cache,
    renderableVersion: combinedVersion,
    renderable: hasUi,
  })
  return hasUi
}

function getThinkingElapsedSec(m: ChatMessage): number | null {
  if (m.role !== 'assistant' || m.createTime || !m.streamStartedAt) return null
  const streamActive = isStreamActiveForMessage(m)
  const endTs = m.firstVisibleAt
    ? m.firstVisibleAt
    : streamActive
      ? workflowNowTs.value
      : (m.streamFinishedAt ?? workflowNowTs.value)
  return Math.max(0, Math.floor((endTs - m.streamStartedAt) / 1000))
}

function getTotalElapsedSec(m: ChatMessage): number | null {
  if (m.role !== 'assistant' || m.createTime || !m.streamStartedAt || !m.streamFinishedAt) return null
  return Math.max(0, Math.floor((m.streamFinishedAt - m.streamStartedAt) / 1000))
}

function getThinkingStatusText(m: ChatMessage): string {
  // 后端正在压缩对话记忆时，显示"回忆分析中..."而非"已思考X秒"
  if (m.isMemoryCompressing) {
    return '回忆分析中...'
  }
  const thinkingSec = getThinkingElapsedSec(m)
  if (thinkingSec == null) return ''
  const baseText = `${m.firstVisibleAt ? '共思考' : '已思考'}${thinkingSec}秒`
  const totalSec = getTotalElapsedSec(m)
  if (totalSec == null) return baseText
  return `${baseText} | 共消耗${totalSec}秒`
}

function markFirstVisibleAt(msg: ChatMessage, now = Date.now()) {
  if (msg.firstVisibleAt || msg.role !== 'assistant' || !!msg.createTime) return
  // 工作流进度卡片不算首屏可见；与 legacy 链路一致，仅真实 AI 输出到达时冻结“共思考”
  if (assistantHasNonWorkflowRenderableOutput(msg)) {
    msg.firstVisibleAt = now
  }
}

function markStreamFinishedAt(msg: ChatMessage, now = Date.now()) {
  if (msg.role !== 'assistant' || !!msg.createTime) return
  if (!msg.streamFinishedAt) {
    msg.streamFinishedAt = now
  }
}

function rebuildGeneratedFilesFromHistory() {
  generatedFiles.value = []
  generatedFileMap.value = {}
  toolSelectHintShown.value = false

  const inferLanguageFromPath = (p: string): string => {
    const lower = (p || '').toLowerCase()
    if (lower.endsWith('.vue')) return 'vue'
    if (lower.endsWith('.ts') || lower.endsWith('.tsx')) return 'typescript'
    if (lower.endsWith('.js') || lower.endsWith('.jsx')) return 'javascript'
    if (lower.endsWith('.css') || lower.endsWith('.scss') || lower.endsWith('.less')) return 'css'
    if (lower.endsWith('.html')) return 'html'
    return 'text'
  }

  for (const r of loadedHistoryRecords.value) {
    const msg = r.message ?? ''
    if (msg.includes('[选择工具] 写入文件') || msg.includes('[选择工具] 修改文件')) {
      toolSelectHintShown.value = true
    }
    const blocks = extractToolWriteFileBlocks(msg)
    for (const b of blocks) {
      recordGeneratedFile(b.filePath, b.lang, b.content)
    }

    // 兼容后端使用 modifyFile 做局部替换：在历史回放时也更新“生成文件”回显
    const modifyBlocks = extractToolModifyFileNewContentBlocksFromText(msg)
    for (const b of modifyBlocks) {
      recordGeneratedFile(b.filePath, inferLanguageFromPath(b.filePath), b.content)
    }
  }
}

function getCodeGenTypeLabel(): string {
  if (!appInfo.value) return ''
  const t = normalizeCodeGenType(appInfo.value.codeGenType)
  if (t === CodeGenTypeEnum.HTML) return 'HTML 项目'
  if (t === CodeGenTypeEnum.MULTI_FILE) return '多文件项目'
  if (t === CodeGenTypeEnum.VUE_PROJECT) return 'Vue 项目'
  return '项目'
}

async function refreshProjectFilesEchoFromDisk() {
  if (!appId.value || !appInfo.value) return

  // 一并刷新工作区描述与运行状态：预览入口与运行按钮都依赖它们
  void refreshWorkspace()
  void refreshRuntimeStatus()

  // 所有生成类型统一走磁盘项目快照，不再按类型区分回显来源
  if (!hasGenerated.value && !appInfo.value?.hasGeneratedCode) {
    rebuildGeneratedFilesFromHistory()
    return
  }

  if (loadingEcho.value) {
    pendingEchoRefresh = true
    return
  }

  loadingEcho.value = true
  pendingEchoRefresh = false
  try {
    const items = await loadProjectFilesEchoFromDisk({
      appId: appId.value,
      apiBaseUrl: API_BASE_URL,
    })
    if (items.length > 0) {
      const nextMap: Record<string, GeneratedFileItem> = {}
      for (const item of items) {
        nextMap[item.path] = item
      }
      generatedFileMap.value = nextMap
      generatedFiles.value = items
    }
  } catch {
    rebuildGeneratedFilesFromHistory()
  } finally {
    loadingEcho.value = false
    if (pendingEchoRefresh) {
      pendingEchoRefresh = false
      void refreshProjectFilesEchoFromDisk()
    }
  }
}

async function copyToClipboard(text: string) {
  try {
    await window.navigator.clipboard.writeText(text)
    message.success('已复制到剪贴板')
  } catch {
    message.error('复制失败，请检查浏览器权限')
  }
}

function handleCopyDeployUrl() {
  const url = deployedAccessUrl.value
  if (!url) {
    message.warning('当前应用尚未部署')
    return
  }
  void copyToClipboard(url)
}

function handleVisitDeployUrl() {
  const url = deployedAccessUrl.value
  if (!url) return
  if (!/^https?:\/\//i.test(url)) {
    message.error('部署地址格式异常，请复制地址后手动检查')
    return
  }
  window.open(url, '_blank', 'noopener,noreferrer')
}

/**
 * 走 Agent 传输发起一轮生成。
 *
 * 传输本身（WebSocket 优先 / 3 秒未连上降级 SSE）与事件到 UI 的映射都在
 * utils 里，这里只做胶水：把 Action 落到消息状态上。
 */
function startAgentStream(streamId: string, prompt: string) {
  const apiBase = API_BASE_URL.replace(/\/$/, '')
  const agentStream = openAgentStream(
    apiBase,
    { appId: String(appId.value), message: prompt },
    {
      onTransport: (kind) => {
        // 打到控制台便于排障：一眼看出这次实际走的是哪条
        console.info(`[agent-stream] 传输=${kind}`)
      },
      onEvent: (name, payload) => {
        if (name === 'import.review.requested') {
          stopStream(streamId)
          return
        }
        const action = mapAgentEvent(name, payload)
        if (action.kind === 'text') {
          maybeInjectToolSelectHint(action.text)
          appendAssistantChunkToStream(streamId, action.text)
          return
        }
        if (action.kind === 'tool_started' || action.kind === 'tool_finished') {
          // 工具事件是结构化协议。先提交更早到达的正文，再直接更新 UI segment。
          streamRenderScheduler.flush(streamId)
          commitAgentToolActionToStream(streamId, action)
          return
        }
        if (action.kind === 'thinking_started' || action.kind === 'thinking_delta' || action.kind === 'thinking_completed') {
          commitThinkingActionToStream(streamId, action)
          return
        }
        if (action.kind === 'error') {
          // 不可重试的错误要说清楚，否则用户会一直点重试。
          // 限流其实过一会儿可以，所以措辞用「立即重试无效」而不是「重试无效」。
          const hint = action.retryable === false ? '（立即重试无效，请按提示处理或稍后再试）' : ''
          appendAssistantChunkToStream(streamId, `\n\n❌ ${action.text}${hint}`)
          message.error(`${action.text}${hint}`)
          stopStream(streamId)
          return
        }
        if (action.kind === 'paused') {
          // 等待审批超时：中性提示，不按错误处理。
          appendAssistantChunkToStream(streamId, `\n\n${action.text}`)
          stopStream(streamId)
          return
        }
        if (action.kind === 'done') {
          finishAgentStream(streamId)
        }
      },
      onError: (error) => {
        console.error('[agent-stream] 连接异常', error)
        // 断线不自动重连（重连会把这一轮重跑一遍，产生重复副作用）
        appendAssistantChunkToStream(streamId, '\n\n❌ 连接中断，本轮未完成')
        message.error('生成过程中出现错误，已结束本次对话')
        stopStream(streamId)
      },
    },
  )
  agentStreamMap.set(streamId, agentStream)
}

/** 一轮正常结束后的收尾。与老链路的 done 处理保持一致。 */
function finishAgentStream(streamId: string) {
  stopStream(streamId)
  hasGenerated.value = true
  void refreshProjectFilesEchoFromDisk()
  void refreshPreviewWithRetry()
  void fetchRoundCount()
}

function stopStream(streamId?: string) {
  // stop a single stream
  if (streamId) {
    // 关闭连接前先把该流对应消息的残留 buffer 排空到 active 段，
    // 保证 [DONE]/business-error/用户停止 三种结束路径都落盘一致。
    const meta = activeStreamMeta.value[streamId]
    if (meta) {
      streamRenderScheduler.flush(streamId)
      const msg = sessionMessages.value.find((x) => x.id === meta.assistantMessageId)
      if (msg?.uiState && meta.sseCarry) {
        const { uiText, newSteps, mermaidErrorNotice } = flushAssistantSseCarry(meta.sseCarry)
        if (newSteps.length && isFirstAppAssistantMessage(msg)) {
          mergeWorkflowStepsIntoMessage(msg, newSteps)
        }
        if (mermaidErrorNotice) {
          msg.mermaidErrorNotice = true
        }
        if (uiText) {
          processAssistantChunkIntoUiState(msg.uiState, uiText)
        }
        resetSseLineAccumulator(meta.sseCarry)
      }
      if (msg?.uiState) drainBufferToSegments(msg.uiState)
      if (msg) {
        markFirstVisibleAt(msg)
        markStreamFinishedAt(msg)
      }
    }
    const es = eventSourceMap.get(streamId)
    if (es) {
      try {
        es.close()
      } catch {
        // ignore
      }
      eventSourceMap.delete(streamId)
    }
    // Agent 传输（WebSocket 优先 / SSE 降级）走独立句柄
    const agentStream = agentStreamMap.get(streamId)
    if (agentStream) {
      try {
        agentStream.close()
      } catch {
        // ignore
      }
      agentStreamMap.delete(streamId)
    }
    if (activeStreamMeta.value[streamId]) {
      const nextMeta = { ...activeStreamMeta.value }
      delete nextMeta[streamId]
      activeStreamMeta.value = nextMeta
    }
    if (activeStreamOrder.value.length > 0) {
      activeStreamOrder.value = activeStreamOrder.value.filter((id) => id !== streamId)
    }
  } else {
    // stop all streams：对每条 active 流都先 drain buffer 再关闭 EventSource
    for (const sid of Object.keys(activeStreamMeta.value)) {
      const meta = activeStreamMeta.value[sid]
      if (meta) {
        streamRenderScheduler.flush(sid)
        const msg = sessionMessages.value.find((x) => x.id === meta.assistantMessageId)
        if (msg?.uiState && meta.sseCarry) {
          const { uiText, newSteps, mermaidErrorNotice } = flushAssistantSseCarry(meta.sseCarry)
          if (newSteps.length && isFirstAppAssistantMessage(msg)) {
            mergeWorkflowStepsIntoMessage(msg, newSteps)
          }
          if (mermaidErrorNotice) {
            msg.mermaidErrorNotice = true
          }
          if (uiText) {
            processAssistantChunkIntoUiState(msg.uiState, uiText)
          }
          resetSseLineAccumulator(meta.sseCarry)
        }
        if (msg?.uiState) drainBufferToSegments(msg.uiState)
        if (msg) {
          markFirstVisibleAt(msg)
          markStreamFinishedAt(msg)
        }
      }
    }
    for (const [, es] of eventSourceMap.entries()) {
      try {
        es.close()
      } catch {
        // ignore
      }
    }
    eventSourceMap.clear()
    for (const [, agentStream] of agentStreamMap.entries()) {
      try {
        agentStream.close()
      } catch {
        // ignore
      }
    }
    agentStreamMap.clear()
    activeStreamOrder.value = []
    activeStreamMeta.value = {}
  }
  // 结束流式时关闭“分析代码 ing~”提示
  if (analyzingHintCloser.value) {
    try {
      analyzingHintCloser.value()
    } catch {
      // ignore
    }
    analyzingHintCloser.value = null
  }
}

function startWorkflowSlowHintTicker() {
  if (workflowSlowHintTicker != null) {
    return
  }
  workflowSlowHintTicker = window.setInterval(() => {
    workflowNowTs.value = Date.now()
  }, 1000)
}

function stopWorkflowSlowHintTicker() {
  if (workflowSlowHintTicker == null) {
    return
  }
  window.clearInterval(workflowSlowHintTicker)
  workflowSlowHintTicker = null
}

function createStreamId(): string {
  // 足够区分同一毫秒内的多次发送；无需引入依赖
  return `${Date.now()}_${Math.random().toString(16).slice(2)}`
}

function isStreamActiveForMessage(m: ChatMessage): boolean {
  const sid = m.streamId
  if (!sid) return false
  return !!activeStreamMeta.value[sid]
}

function isToolExecutedUiSegmentKind(kind: UiSegment['kind'] | undefined): boolean {
  return (
    kind === 'tool_executed_write_file' ||
    kind === 'tool_executed_modify_file' ||
    kind === 'tool_executed_simple_tool'
  )
}

function isToolRequestPending(m: ChatMessage, idx: number): boolean {
  if (!isStreamActiveForMessage(m)) return false
  const segs = getMessageUiSegments(m)
  for (let i = idx + 1; i < segs.length; i++) {
    const kind = segs[i]?.kind
    if (kind === 'tool_request' || isToolExecutedUiSegmentKind(kind)) return false
  }
  return true
}

function getToolRequestStatusView(m: ChatMessage, idx: number): ToolWaitStatusView | null {
  const segs = getMessageUiSegments(m)
  const seg = segs[idx]
  if (!seg || seg.kind !== 'tool_request' || !seg.createdAt) return null
  return resolveToolWaitStatus(
    seg.toolName,
    workflowNowTs.value - seg.createdAt,
    seg.tier2ThresholdMs,
  )
}

function shouldShowToolRequestStatus(m: ChatMessage, idx: number): boolean {
  if (!isToolRequestPending(m, idx)) return false
  const segs = getMessageUiSegments(m)
  const seg = segs[idx]
  if (!seg || seg.kind !== 'tool_request' || !seg.createdAt) return false

  const elapsed = workflowNowTs.value - seg.createdAt
  if (isToolWaitStatusTool(seg.toolName)) {
    return getToolRequestStatusView(m, idx)?.show ?? false
  }
  return elapsed >= TOOL_REQUEST_STATUS_DELAY_MS
}

function getToolRequestShimmerText(m: ChatMessage, idx: number): string {
  const segs = getMessageUiSegments(m)
  const seg = segs[idx]
  if (!seg || seg.kind !== 'tool_request') return '工具执行中'
  return '工具执行中'
}

function appendAssistantChunkToStream(streamId: string, chunk: string) {
  streamRenderScheduler.enqueue(streamId, chunk)
}

function commitThinkingActionToStream(streamId: string, action: Extract<AgentUiAction, { kind: 'thinking_started' | 'thinking_delta' | 'thinking_completed' }>) {
  const meta = activeStreamMeta.value[streamId]
  const msg = meta && sessionMessages.value.find((item) => item.id === meta.assistantMessageId)
  if (!msg || msg.role !== 'assistant') return
  if (!msg.uiState) msg.uiState = createAssistantUiState()
  const state = msg.uiState
  const thinking = state.segments.filter((s): s is UiThinkingSegment => s.kind === 'thinking')
  const segment = mergeThinkingAction(thinking, action)
  if (segment && !state.segments.includes(segment)) state.segments.push(segment)
  scheduleChatAutoScroll()
}

function commitAgentToolActionToStream(
  streamId: string,
  action: Extract<AgentUiAction, { kind: 'tool_started' | 'tool_finished' }>,
) {
  const meta = activeStreamMeta.value[streamId]
  if (!meta) return
  const msg = sessionMessages.value.find((item) => item.id === meta.assistantMessageId)
  if (!msg || msg.role !== 'assistant') return

  if (!msg.uiState) msg.uiState = createAssistantUiState()
  const state = msg.uiState
  drainBufferToSegments(state)
  state.stage = 'markdown'
  state.lastToolRequestName = undefined
  state.pendingFilePath = undefined
  state.pendingLanguage = undefined
  state.pendingFenceLen = undefined
  state.pendingModifyPart = undefined
  state.activeToolIndex = undefined

  if (action.kind === 'tool_started') {
    toolSelectHintShown.value = true
    state.segments.push({
      kind: 'tool_request',
      segmentId: action.callId ? `agent-tool-${action.callId}` : undefined,
      rawLabel: `[选择工具] ${action.label}`,
      toolName: action.label,
      callId: action.callId,
      agentToolName: action.toolName,
      targetPath: action.target,
      createdAt: Date.now(),
      tier2ThresholdMs: generateTier2Threshold(action.label),
    })
  } else {
    const request = [...state.segments]
      .reverse()
      .find(
        (segment): segment is UiToolRequestSegment =>
          segment.kind === 'tool_request' &&
          (action.callId
            ? segment.callId === action.callId
            : segment.agentToolName === action.toolName),
      )
    const label = request?.toolName || action.label
    const failed = action.status !== 'succeeded'
    const completed: UiToolExecutedSimpleToolSegment = {
      kind: 'tool_executed_simple_tool',
      segmentId: request?.segmentId ?? (action.callId ? `agent-tool-${action.callId}` : undefined),
      callId: action.callId,
      agentToolName: action.toolName,
      status: action.status,
      toolTitle: `[工具调用] ${label}${failed ? '（失败）' : ''}`,
      filePath: request?.targetPath || '',
      message: action.outputPreview,
      done: true,
    }
    // 原位更新，执行中的终端不因完成事件丢失展开状态或改变顺序。
    if (request) state.segments.splice(state.segments.indexOf(request), 1, completed)
    else state.segments.push(completed)
  }

  // 工具原位替换不改变段数，主动失效旧缓存才能立即显示完成状态。
  messageRenderCache.delete(msg)
  markFirstVisibleAt(msg)
  scheduleChatAutoScroll()
}

function commitAssistantChunkToStream(streamId: string, chunk: string) {
  const meta = activeStreamMeta.value[streamId]
  if (!meta) return
  const msg = sessionMessages.value.find((x) => x.id === meta.assistantMessageId)
  if (!msg || msg.role !== 'assistant') return

  const parsedFile = parseToolWriteFileBlock(chunk)
  if (parsedFile) {
    recordGeneratedFile(parsedFile.filePath, parsedFile.lang, parsedFile.content)
  }

  let uiText = chunk
  if (meta.mode === 'workflow') {
    if (!meta.sseCarry) {
      meta.sseCarry = { carry: '' }
    }
    const filtered = filterAssistantSseChunkForUi(meta.sseCarry, chunk)
    uiText = filtered.uiText
    if (filtered.newSteps.length && isFirstAppAssistantMessage(msg)) {
      mergeWorkflowStepsIntoMessage(msg, filtered.newSteps)
    }
    if (filtered.mermaidErrorNotice) {
      msg.mermaidErrorNotice = true
    }
  }

  msg.content += chunk
  if (!msg.uiState) msg.uiState = createAssistantUiState()
  if (uiText) {
    processAssistantChunkIntoUiState(msg.uiState, uiText)
  }
  markFirstVisibleAt(msg)
  scheduleChatAutoScroll()
}

function scheduleChatAutoScroll() {
  if (!shouldAutoScroll.value || scrollFrameHandle != null) return
  const callback = () => {
    scrollFrameHandle = null
    nextTick(() => {
      observeChatBottom()
      if (shouldAutoScroll.value) scrollToBottom()
    })
  }
  if (typeof window !== 'undefined' && typeof window.requestAnimationFrame === 'function') {
    scrollFrameHandle = window.requestAnimationFrame(callback)
  } else {
    scrollFrameHandle = window.setTimeout(callback, 16)
  }
}

async function sendMessage(text?: string) {
  if (importedView.value && importedAgentBlocked.value) {
    message.warning('请先处理当前任务或读取会话设置。')
    return
  }
  if (isReadOnly.value) {
    // 只读视图下静默忽略发送，不弹出任何提示
    return
  }
  const content = (text ?? inputMessage.value).trim()
  if (!content) {
    message.warning('请输入要生成的应用描述')
    return
  }
  if (!appId.value || !appId.value.trim()) {
    message.error('应用 ID 异常')
    return
  }

  // AI 即将写入项目文件：有未保存草稿时先让用户处理，不能静默覆盖。
  // 打开项目文件窗口并显示里面的「保存 / 放弃 / 取消」确认条，
  // 处理完由用户重新发送 —— 发送动作在这里中断。
  if (projectFilesModalRef.value?.hasUnsavedChanges()) {
    projectFilesModalRef.value.promptUnsavedChoice()
    message.warning('请先处理项目文件里的未保存修改，再发送消息')
    return
  }

  if (!userLoginStore.userLogin?.id) {
    message.warning('请先登录')
    router.push({
      path: '/user/login',
      query: { redirect: route.fullPath },
    })
    return
  }

  const selectedSnapshot = selectedElement.value
  // 不再在前端拼接“代码类型提示词”；让后端按 appInfo.codeGenType 决定输出格式
  // （仍然清空一次性变量，避免未来误复用产生重复约束）
  codeTypePromptChoiceOnce.value = null

  const promptBase =
    selectedSnapshot && visualEditMode.value
      ? `${content}\n\n${formatSelectedElementForPrompt(selectedSnapshot)}\n\n${VISUAL_EDIT_MODEL_ONLY_HINT}`
      : content

  const prompt = promptBase

  // 用户在可视化编辑模式下选择了元素时：
  // - 发给 AI 的 prompt 会附带完整元素信息（如上）
  // - 同时对话区里用户这条消息也展示一份元素信息，避免“只在记忆里存在，用户看不到选择了什么”
  const visibleUserContent =
    selectedSnapshot && visualEditMode.value
      ? `${content}\n\n${formatSelectedElementForPrompt(selectedSnapshot)}`
      : content

  // 发送后：清空选中并退出编辑模式（保持其余流程不变）
  if (visualEditMode.value) {
    exitVisualEditMode()
  }

  // 对话区展示用户输入 + 选中元素信息；模型专用引导句仅在 prompt 中，展示时由 getMessageUiSegments 剥离
  appendUserMessage(visibleUserContent)
  const streamId = createStreamId()
  const assistantMessageId = nextMessageId++
  const enableWorkflowCard = isAppWorkflowBeta() && !hasExistingAssistantMessages()
  sessionMessages.value.push({
    id: assistantMessageId,
    role: 'assistant',
    content: '',
    streamStartedAt: Date.now(),
    uiState: createAssistantUiState(),
    streamId,
    workflowStreamStartedAt: enableWorkflowCard ? Date.now() : undefined,
    // 仅首轮工作流生成展示进度卡片：先放入“初始化”占位，避免首条 [workflow] SSE 到达前空白。
    workflowSteps: enableWorkflowCard ? [{ step: 1, label: '初始化' }] : [],
  })
  activeStreamMeta.value = {
    ...activeStreamMeta.value,
    [streamId]: {
      assistantMessageId,
      sseCarry: { carry: '' },
      mode: isAppWorkflowBeta() ? 'workflow' : 'legacy',
    },
  }
  activeStreamOrder.value = [...activeStreamOrder.value, streamId]
  const createdAssistant = sessionMessages.value.find((x) => x.id === assistantMessageId)
  if (createdAssistant) {
    markFirstVisibleAt(createdAssistant)
  }

  inputMessage.value = ''
  sending.value = true
  pollFirstProjectTitle()

   // 首次生成时给出“分析代码 ing~”提示，告诉用户正在分析并决定生成格式
  if (!hasGenerated.value && !appInfo.value?.hasGeneratedCode && !analyzingHintCloser.value) {
    try {
      analyzingHintCloser.value = message.loading('分析代码ing~  请不要退出/刷新界面', 0)
    } catch {
      analyzingHintCloser.value = null
    }
  }

  try {
    // Agent 传输：WebSocket 优先，不可用则降级 SSE。
    startAgentStream(streamId, prompt)
  } catch {
    stopStream(streamId)
    message.error('无法建立对话连接，请稍后重试')
  } finally {
    sending.value = false
  }
}

function handleManualSend() {
  if (isReadOnly.value) return
  void sendMessage()
}

function handleStop() {
  if (isReadOnly.value || !streaming.value) return
  const lastId = activeStreamOrder.value.length > 0 ? activeStreamOrder.value[activeStreamOrder.value.length - 1] : null
  if (!lastId) return
  stopStream(lastId)
}

function scrollToBottom(options: { smooth?: boolean } = {}) {
  const container = chatMessagesRef.value
  if (!container) return
  container.scrollTo({
    top: container.scrollHeight,
    behavior: options.smooth ? 'smooth' : 'auto',
  })
}

function handleScrollToBottomClick() {
  shouldAutoScroll.value = true
  scrollToBottom({ smooth: true })
}

function observeChatBottom() {
  const root = chatMessagesRef.value
  const target = chatBottomRef.value
  if (!root || !target || typeof IntersectionObserver === 'undefined') return
  if (chatBottomObserver) return
  chatBottomObserver = new IntersectionObserver(
    ([entry]) => {
      shouldAutoScroll.value = entry?.isIntersecting === true
    },
    { root, rootMargin: '0px 0px 40px 0px', threshold: 0 },
  )
  chatBottomObserver.observe(target)
}

/** 加载对话历史（游标分页） */
async function loadChatHistory(lastCreateTime?: string) {
  if (!appId.value || !appId.value.trim()) return
  const isLoadMore = !!lastCreateTime
  if (isLoadMore) {
    loadingMoreHistory.value = true
  } else {
    loadingHistory.value = true
  }
  try {
    const res = await chatHistoryAppAppIdUsingGet({
      params: {
        // 这里保持 appId 为字符串，避免长整型精度丢失，只在类型层面断言为 number
        appId: appId.value as unknown as number,
        size: 10,
        ...(lastCreateTime ? { lastCreateTime } : {}),
      },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      const page = res.data.data
      syncAppIsBeta(page?.isBeta)
      const records = page?.records ?? []
      if (isLoadMore) {
        loadedHistoryRecords.value = dedupHistoryRecords([...records, ...loadedHistoryRecords.value])
      } else {
        loadedHistoryRecords.value = dedupHistoryRecords(records)
      }
      historyMessages.value = loadedHistoryRecords.value.map(historyToMessage)
      // 历史回放需要重建工具输出（否则”查看项目回显/架构”按钮会丢失）
      void refreshProjectFilesEchoFromDisk()
      const totalRow = page?.totalRow ?? 0
      const loaded = loadedHistoryRecords.value.length
      hasMoreHistory.value = loaded < totalRow
    }
  } catch {
    if (!isLoadMore) {
      message.error('加载对话历史失败')
    } else {
      message.error('加载更多历史失败')
    }
  } finally {
    loadingHistory.value = false
    loadingMoreHistory.value = false
  }
}

function loadMoreHistory() {
  const records = loadedHistoryRecords.value
  if (records.length === 0 || !hasMoreHistory.value || loadingMoreHistory.value) return
  const oldest = records.reduce((a, b) => {
    const ta = a.createTime ?? ''
    const tb = b.createTime ?? ''
    return ta && tb && ta < tb ? a : b
  })
  if (oldest.createTime) {
    void loadChatHistory(oldest.createTime)
  }
}

async function loadAppInfo() {
  if (!appId.value || !appId.value.trim()) {
    message.error('应用 ID 异常')
    router.replace('/')
    return
  }
  loadingApp.value = true
  try {
    const res = await appGetVoUsingGet({
      params: { id: appId.value },
    })
    if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
      appInfo.value = res.data.data
      syncAppIsBeta(appInfo.value.isBeta)
      if (appInfo.value.hasGeneratedCode) {
        hasGenerated.value = true
      }
      generatedFiles.value = []
      generatedFileMap.value = {}
      toolSelectHintShown.value = false
      filesModalOpen.value = false
      activeFilePath.value = ''
      await loadChatHistory()
      // loadChatHistory 内已走磁盘全量回显；这里再兜底一次
      void refreshProjectFilesEchoFromDisk()
      void fetchRoundCount()
      // 从首页进入 Vue 项目页面：先探测一次静态入口，并显示托底“刷新应用”按钮（不强依赖 hasGeneratedCode 字段）
      if (isVueProject.value) {
        showRefreshAppCta(
          '已为你加载 Vue 项目。由于浏览器缓存/资源加载顺序，预览可能不会立刻更新；需要时点击“刷新应用”强制重载。',
        )
        // 触发一次入口探测：若 ready 则确保 shouldShowWebsite 生效（即使 hasGeneratedCode 未正确同步）
        const ready = await probePreviewReady()
        if (ready) {
          hasGenerated.value = true
        }
      }

      // 检查是否从首页跳转过来（需要自动提交）
      const autoSend = route.query.autoSend === 'true'
      const hasInitPrompt = appInfo.value.initPrompt && appInfo.value.initPrompt.trim()

      if (hasInitPrompt) {
        if (autoSend) {
          // 从首页创建的应用：自动提交初始提示词
          // 兼容读取 codeTypeChoice 参数（当前前端不再拼接提示词，约束由后端按 appInfo.codeGenType 控制）
          const codeTypeChoice =
            typeof route.query.codeTypeChoice === 'string' && route.query.codeTypeChoice.trim()
              ? route.query.codeTypeChoice.trim()
              : null
          codeTypePromptChoiceOnce.value = codeTypeChoice

          // 读取从首页传递的 loopId 参数（字符串传递，Snowflake ID 不可转 Number）
          const autoLoopId = route.query.loopId
          if (autoLoopId && String(autoLoopId).trim()) {
            selectedLoopId.value = String(autoLoopId).trim()
          }

          // 先获取 Loop 详情以填充 compiledPrompt（用于调试日志），再发送消息
          const loopDetailPromise = (autoLoopId && String(autoLoopId).trim())
            ? loopGetVoById(String(autoLoopId).trim()).then(res => {
                  if ((res.data.code === 0 || res.data.code === 20000) && res.data.data) {
                    selectedLoopCompiledPrompt.value = res.data.data.compiledPrompt || ''
                    selectedLoopName.value = res.data.data.loopName || ''
                  }
                }).catch(() => {})
            : Promise.resolve()

          // 清除 query 参数，避免刷新页面时重复自动发送
          router.replace({
            name: 'app-chat',
            params: { id: appId.value },
          })
          // 等待 Loop 详情加载完成后发送消息（prompt log 需要 compiledPrompt）
          await loopDetailPromise
          await sendMessage(appInfo.value.initPrompt)
        } else {
          // 其他情况：只预填到输入框，等待用户确认提交
          inputMessage.value = appInfo.value.initPrompt ?? ''
        }
      }
    } else {
      message.error(res.data.message || '获取应用信息失败')
    }
  } catch {
    message.error('获取应用信息失败，请稍后重试')
  } finally {
    loadingApp.value = false
  }
}

function goEdit() {
  if (!appId.value) return
  router.push({ name: 'app-edit', params: { id: appId.value } })
}

onMounted(() => {
  syncGenMode()
  void loadAppInfo()
  startWorkflowSlowHintTicker()
  // 页面在看着这个应用期间持续上报心跳，服务端据此决定何时空闲停止
  startRuntimeHeartbeat()
  // 首次进入页面先做一次清理，避免历史会话遗留覆盖层影响当前预览滚动。
  cleanupVisualEditorArtifacts()
  observeChatBottom()
  window.addEventListener('keydown', handleSplitPaneKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleSplitPaneKeydown)
  stopWorkflowSlowHintTicker()
  stopPreviewProbing()
  // 分栏相关的监听要成对摘掉，否则组件卸载后拖拽仍会改到已失效的状态
  stopSplitterDrag()
  narrowScreenQuery.removeEventListener('change', syncNarrowScreen)
  // 心跳停了，服务端会在保留时长后自动停止运行
  stopRuntimeHeartbeat()
  exitVisualEditMode()
  // 页面卸载时关闭所有 SSE，避免后台连接泄露
  stopStream()
  streamRenderScheduler.dispose()
  if (scrollFrameHandle != null) {
    if (typeof window !== 'undefined' && typeof window.cancelAnimationFrame === 'function') {
      window.cancelAnimationFrame(scrollFrameHandle)
    } else {
      window.clearTimeout(scrollFrameHandle)
    }
    scrollFrameHandle = null
  }
  chatBottomObserver?.disconnect()
  chatBottomObserver = null
})

defineExpose({
  workspace,
  previewUrl,
  runtimeStatus,
  runtimeActive,
  runtimeRunning,
})
</script>

<template>
  <main class="app-chat-page">
    <ProjectFilesModal
      ref="projectFilesModalRef"
      v-model:open="filesModalOpen"
      :app-id="appId ?? ''"
      :api-base-url="API_BASE_URL"
      :read-only="isReadOnly"
      :generating="streaming"
    />

    <a-modal
      v-model:open="dashboardModalOpen"
      title="看板设置"
      wrap-class-name="cosmic-settings"
      :confirm-loading="savingDashboard"
      ok-text="保存"
      cancel-text="取消"
      @ok="saveDashboardSettings"
    >
      <p class="dashboard-settings-hint">
        开启后可在右侧工作区查看对应内容。默认全部关闭，随时可以改。
      </p>
      <ACheckboxGroup v-model:value="dashboardDraft">
        <ACheckbox v-for="opt in DASHBOARD_FEATURE_OPTIONS" :key="opt.value" :value="opt.value">
          {{ opt.label }}
          <span class="feature-caption">{{ opt.description }}</span>
        </ACheckbox>
      </ACheckboxGroup>
      <template #footer>
        <button type="button" class="wb-button" :disabled="savingDashboard" @click="dashboardModalOpen = false">取消</button>
        <button type="button" class="wb-button is-primary" :disabled="savingDashboard" @click="saveDashboardSettings">{{ savingDashboard ? '保存中…' : '保存设置' }}</button>
      </template>
    </a-modal>

    <!-- 运行异常提示：关闭只是不再打扰，记录仍保留在服务端 -->
    <a-modal
      v-model:open="incidentModalOpen"
      :title="incidentTitle"
      :mask-closable="false"
      ok-text="让 AI 修复"
      cancel-text="稍后处理"
      :confirm-loading="repairingIncident"
      @ok="handleRepairIncident"
      @cancel="handleDismissIncident"
    >
      <p class="incident-summary">
        {{ visibleIncidents[0]?.summary || '应用运行出现问题' }}
      </p>
      <details v-if="incidentDetail" class="incident-detail">
        <summary>查看诊断信息（已脱敏）</summary>
        <pre class="incident-detail-body">{{ incidentDetail }}</pre>
      </details>
      <p class="incident-hint">
        选择「让 AI 修复」后，问题会作为一条消息发到当前对话里，由 AI 分析并修改代码；
        修好后不会自动重启应用，需要你再次点击运行来验证。
      </p>
    </a-modal>

    <a-modal v-model:open="applyFeaturedModalVisible" title="申请成为精选应用" :confirm-loading="false" ok-text="申请"
      cancel-text="取消" @ok="handleApplyFeaturedConfirm" @cancel="closeApplyFeatured">
      <p style="margin-bottom: 8px">你可以简单说明想成为精选应用的理由（选填）：</p>
      <a-textarea v-model:value="applyAppProprietyReason" :rows="3" placeholder="例如：该应用体验较好、功能完善，希望被更多用户看到" />
    </a-modal>

    <div class="top-bar">
      <div class="app-title-wrap">
        <div class="app-avatar">
          <span>{{ appInfo?.appName?.[0] ?? 'A' }}</span>
        </div>
        <div class="app-meta">
          <div class="app-name">
            {{ appInfo?.appName || '应用生成中' }}
          </div>
          <div v-if="!importedView" class="app-subtitle">
            一句话，生成一个 Web 应用
          </div>
        </div>
        <div v-if="(isOwner || isAdmin) && (roundCount !== null || loadingRoundCount)" class="round-count-wrap">
          <span class="round-count-line" aria-hidden="true" />
          <span class="round-count-text">
            <template v-if="loadingRoundCount">…</template>
            <template v-else>共 {{ roundCount }} 轮对话</template>
          </span>
        </div>
      </div>
      <div class="top-actions">
        <a-button
          v-if="!isReadOnly"
          class="ghost-btn"
          :loading="exportingChat"
          @click="handleExportChatToLocal"
        >
          导出对话
        </a-button>
        <a-button
          v-if="!isReadOnly"
          class="ghost-btn"
          :loading="downloadingProject"
          @click="handleDownloadProjectFiles"
        >
          下载项目
        </a-button>
        <a-button
          v-if="!isReadOnly"
          class="ghost-btn"
          @click="goEdit"
        >
          编辑应用
        </a-button>
        <slot name="runtime-actions"><template v-if="importedView"><StartupRunPreview v-if="!isReadOnly && appId" :app-id="appId" /></template><a-button
          v-else-if="importLookupBlocked && !isReadOnly"
          class="ghost-btn"
          loading
        >正在确认</a-button><ATooltip
          v-else
          :title="runtimeRunning
            ? (runtimeStale ? '当前运行的仍是上次启动时的代码，重新运行才会生效' : '停止常驻进程')
            : '在受控沙箱中启动常驻进程'"
        >
          <a-button
            v-if="!isReadOnly"
            class="ghost-btn"
            :type="runtimeRunning ? 'default' : 'primary'"
            :loading="startingRuntime || stoppingRuntime"
            @click="runtimeRunning ? handleStopRuntime() : handleStartRuntime()"
          >
            {{ runtimeRunning ? '运行中' : '运行' }}
          </a-button>
        </ATooltip></slot>
        <a-button
          v-if="!isReadOnly && generatedFiles.length > 0"
          class="ghost-btn"
          @click="showGeneratedFilesModal"
        >
          项目文件
        </a-button>
        <a-button v-if="!isReadOnly" class="ghost-btn" @click="openDashboardSettings">
          看板设置
        </a-button>
        <a-button v-if="canApplyFeatured" class="ghost-btn" type="primary" @click="openApplyFeatured">
          我要成为精选
        </a-button>
      </div>
    </div>

    <section
      ref="mainContentRef"
      class="main-content"
      :class="{
        'main-content--single': !appearanceStore.previewExpanded,
        'is-splitter-dragging': draggingSplitter,
      }"
      :style="gridColumns ? { gridTemplateColumns: gridColumns } : undefined"
    >
      <!-- 左边缘恢复双栏小箭头：只显示预览区时展示在屏幕左侧 -->
      <Transition name="edge-restore-left">
        <ATooltip
          v-if="leftRestoreVisible"
          title="展开聊天区（恢复双栏，或按 Esc）"
          placement="right"
        >
          <button
            type="button"
            class="edge-restore-trigger edge-restore-trigger--left"
            aria-label="展开聊天区（恢复双栏，或按 Esc）"
            @click="restoreSplitView"
          >
            <span class="edge-restore-arrow" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="9 18 15 12 9 6" />
              </svg>
            </span>
          </button>
        </ATooltip>
      </Transition>

      <div v-show="!chatHidden" class="chat-panel">
        <div
          v-if="generatedFiles.length > 0"
          class="stream-file-slot"
        >
          <div class="generated-files-bar">
            <div class="generated-files-left">
              <span class="generated-files-dot" aria-hidden="true" />
              <span class="generated-files-text">已回显 {{ generatedFiles.length }} 个文件</span>
            </div>
            <a-button size="small" class="ghost-btn" @click="showGeneratedFilesModal">查看文件</a-button>
          </div>
        </div>
        <div ref="chatMessagesRef" class="chat-messages">
          <ASpin v-if="loadingApp" />
          <template v-else>
            <div v-if="displayMessages.length === 0" class="chat-empty">
              <AEmpty :description="importedView ? '还没有对话' : '还没有对话，先从左侧输入提示词开始吧'" />
            </div>
            <template v-else>
              <div v-if="hasMoreHistory && !loadingHistory" class="load-more-wrap">
                <a-button type="link" size="small" :loading="loadingMoreHistory" @click="loadMoreHistory">
                  加载更多历史消息
                </a-button>
              </div>
              <div v-for="(m, midx) in displayMessages" :key="m.createTime ? `h-${m.id}` : `s-${m.id}-${midx}`"
                :class="['chat-bubble', m.role === 'user' ? 'bubble-user' : 'bubble-ai']">
                <div class="bubble-inner">
                  <div
                    class="bubble-role"
                    :class="{ 'bubble-role--shimmer': m.role === 'assistant' && isStreamActiveForMessage(m) }"
                  >
                    {{ m.role === 'user' ? '我' : '应用助手' }}
                  </div>
                  <template v-if="m.role === 'user'">
                    <div
                      v-if="getUserMarkdownSegments(m).some((s) => s.content.trim())"
                      class="bubble-content"
                    >
                      <template v-for="(segment, idx) in getUserMarkdownSegments(m)" :key="`u-md-${idx}`">
                        <template
                          v-for="(mdSeg, mdIdx) in getCachedMarkdownSegments(m, segment.content, `user-${idx}`)"
                          :key="`u-md-${idx}-${mdIdx}`"
                        >
                          <CodeBlock
                            v-if="mdSeg.type === 'code'"
                            :code="mdSeg.content"
                            :language="mdSeg.language"
                            :file-label="mdSeg.fileLabel"
                            :is-streaming="false"
                          />
                          <span v-else class="text-segment">{{ mdSeg.content }}</span>
                        </template>
                      </template>
                    </div>
                    <div
                      v-if="getUserVisualEditSegment(m)"
                      class="visual-edit-selection-card"
                      role="region"
                      aria-label="编辑模式"
                    >
                      <div class="visual-edit-selection-card__glow" aria-hidden="true" />
                      <div class="visual-edit-selection-card__inner">
                        <div class="visual-edit-selection-card__header">
                          <div class="visual-edit-selection-card__identity">
                            <span class="visual-edit-selection-badge">
                              <svg
                                class="visual-edit-badge-icon"
                                viewBox="0 0 16 16"
                                width="12"
                                height="12"
                                fill="none"
                                aria-hidden="true"
                              >
                                <path
                                  d="M8 2.5v11M2.5 8h11"
                                  stroke="currentColor"
                                  stroke-width="1.4"
                                  stroke-linecap="round"
                                />
                                <circle cx="8" cy="8" r="5.25" stroke="currentColor" stroke-width="1.2" />
                              </svg>
                              编辑模式
                            </span>
                          </div>
                          <a-button
                            size="small"
                            class="ghost-btn visual-edit-copy-btn"
                            @click="
                              copyToClipboard(
                                getUserVisualEditSegment(m)?.cssSelector ||
                                  getUserVisualEditSegment(m)?.locator ||
                                  '',
                              )
                            "
                          >
                            复制选择器
                          </a-button>
                        </div>
                        <div class="visual-edit-selector-chip">
                          <span class="visual-edit-selector-chip__label">locator</span>
                          <code class="visual-edit-selector-chip__value">{{
                            getVisualEditCardTitle(getUserVisualEditSegment(m)!)
                          }}</code>
                        </div>
                        <div
                          v-if="getVisualEditFieldRows(getUserVisualEditSegment(m)!).length > 0"
                          class="visual-edit-selection-card__fields"
                        >
                          <div
                            v-for="row in getVisualEditFieldRows(getUserVisualEditSegment(m)!)"
                            :key="row.label"
                            class="visual-edit-field-row"
                          >
                            <span class="visual-edit-field-label">{{ row.label }}</span>
                            <span
                              class="visual-edit-field-value"
                              :class="{ 'visual-edit-field-value--text': row.label === 'text' }"
                            >
                              <template v-if="row.label === 'text' && shouldTruncateVisualEditText(row.value)">
                                {{ getVisualEditTextPreview(row.value, isVisualEditTextExpanded(m)) }}
                                <button
                                  type="button"
                                  class="visual-edit-expand-btn"
                                  @click="toggleVisualEditTextExpanded(m)"
                                >
                                  {{ isVisualEditTextExpanded(m) ? '收起' : '展开' }}
                                </button>
                              </template>
                              <template v-else>{{ row.value }}</template>
                            </span>
                          </div>
                        </div>
                      </div>
                    </div>
                  </template>
                  <div v-else class="bubble-content">
                    <template
                      v-if="m.role === 'assistant' && isStreamActiveForMessage(m) && !assistantHasRenderableOutput(m)">
                      <div class="typing-indicator" role="status" aria-live="polite" aria-label="应用助手正在思考">
                        <span class="typing-dot" aria-hidden="true" />
                        <span class="typing-dot" aria-hidden="true" />
                        <span class="typing-dot" aria-hidden="true" />
                        <span class="typing-text">{{ getThinkingStatusText(m) }}</span>
                      </div>
                    </template>
                    <template v-else>
                      <div
                        v-if="m.role === 'assistant' && getThinkingStatusText(m) && !getMessageUiSegments(m).some(s => s.kind === 'thinking')"
                        class="thinking-summary"
                      >
                        {{ getThinkingStatusText(m) }}
                      </div>
                      <div
                        v-if="shouldShowWorkflowCard(m)"
                        class="workflow-steps-shell"
                        :class="{
                          'workflow-steps-shell--collapsed': isWorkflowCollapsed(m),
                          'stream-workflow-viewport': !isHistoryMessage(m),
                        }"
                      >
                        <button
                          v-if="isHistoryMessage(m)"
                          type="button"
                          class="workflow-toggle-btn"
                          @click="toggleWorkflowCollapsed(m)"
                          :title="isWorkflowCollapsed(m) ? '展开工作流' : '收起工作流'"
                        >
                          {{ isWorkflowCollapsed(m) ? '>' : '<' }}
                        </button>
                        <div class="workflow-steps-card">
                          <div class="workflow-steps-title-row">
                            <div class="workflow-steps-title">工作流进度</div>
                            <div v-if="hasMermaidNoticeForMessage(m)" class="workflow-mermaid-notice">
                              存在 Mermaid 构造失败（不影响生成）
                            </div>
                          </div>
                          <div class="workflow-steps-list" :data-workflow-message-id="m.id">
                          <div
                            v-for="row in getWorkflowStepsForMessage(m)"
                            :key="row.step"
                            class="workflow-step-card"
                            :class="{
                              'workflow-step-card--latest':
                                row.step === getWorkflowStepsForMessage(m)[getWorkflowStepsForMessage(m).length - 1]?.step &&
                                isStreamActiveForMessage(m),
                            }"
                          >
                            <span class="workflow-step-badge">{{ row.step }}</span>
                            <span class="workflow-step-label" :class="getWorkflowStepLabelClass(m, row)">{{ row.label }}</span>
                            <span
                              class="workflow-step-slow-hint"
                              :class="{
                                'workflow-step-slow-hint--visible':
                                  row.step === getWorkflowLatestStepForMessage(m)?.step && shouldShowWorkflowSlowHint(m),
                              }"
                            >
                              Taking longer than expected
                            </span>
                          </div>
                          </div>
                        </div>
                      </div>
                      <ExecutionProcess v-if="executionSegments(m).length" :active="isStreamActiveForMessage(m)">
                      <template v-for="{ segment, idx } in executionSegments(m)" :key="segment.segmentId ?? 'seg-' + idx">
                      <ThinkingStage v-if="segment.kind === 'thinking'" :stage="segment" />
                      <ExecutionTerminal
                        v-else-if="(segment.kind === 'tool_request' || segment.kind === 'tool_executed_simple_tool') && segment.agentToolName === 'execute'"
                        :command="cleanExecutionText(segment.kind === 'tool_request' ? segment.targetPath ?? '' : segment.filePath, false, true)"
                        :output="cleanExecutionText(segment.kind === 'tool_executed_simple_tool' ? segment.message : '', false, true)"
                        :status="terminalStatus(segment, m)"
                      />
                      <!-- TOOL_REQUEST：结束工具 → ToolExitHint；其它 → list-row + 延迟光影 -->
                      <ToolExitHint
                        v-else-if="segment.kind === 'tool_request' && segment.toolName === '结束工具调用'"
                        :pending="isToolRequestPending(m, idx)"
                      />
                      <ToolRequestHint
                        v-else-if="segment.kind === 'tool_request'"
                        :tool-name="segment.toolName"
                        :pending="isToolRequestPending(m, idx)"
                        :show-status="shouldShowToolRequestStatus(m, idx)"
                        :status-text="getToolRequestStatusView(m, idx)?.text ?? getToolRequestShimmerText(m, idx)"
                        :status-icon-src="getToolRequestStatusView(m, idx)?.iconSrc"
                        :status-icon-motion="getToolRequestStatusView(m, idx)?.motion ?? 'none'"
                      />

                      <!-- TOOL_EXECUTED：写入文件卡片（可折叠+可复制） -->
                      <div v-else-if="segment.kind === 'tool_executed_write_file'" class="tool-exec-card">
                        <CollapsibleCodeBlock
                          :code="segment.content"
                          :language="segment.language"
                          :default-collapsed="appearanceStore.toolCardCollapsed"
                          :file-path="segment.filePath"
                          :is-streaming="isStreamActiveForMessage(m) && !segment.done"
                        />
                      </div>

                      <!-- TOOL_EXECUTED：修改文件卡片（可折叠+可复制） -->
                      <div v-else-if="segment.kind === 'tool_executed_modify_file'" class="tool-exec-card">
                        <CollapsibleCodeBlock
                          :before-code="segment.beforeContent"
                          :after-code="segment.afterContent"
                          :default-collapsed="appearanceStore.toolCardCollapsed"
                          :language="segment.language"
                          :file-path="segment.filePath"
                          :is-streaming="isStreamActiveForMessage(m)"
                          :before-done="segment.beforeDone"
                          :after-done="segment.afterDone"
                        />
                      </div>

                      <!-- TOOL_EXECUTED：其它工具简单输出卡片 -->
                      <div v-else-if="segment.kind === 'tool_executed_simple_tool'" class="tool-exec-card tool-exec-card--compact">
                        <ToolExecCardHeader
                          :action-label="stripToolCallPrefix(segment.toolTitle)"
                          :target-path="getToolExecHeaderTargetPath(segment.toolTitle, segment.filePath)"
                        />
                      </div>

                      </template>
                      </ExecutionProcess>
                      <template v-for="{ segment, idx } in answerSegments(m)" :key="segment.segmentId ?? 'answer-' + idx">
                      <!-- 生成失败 / 用户中断 -->
                      <div
                        v-if="segment.kind === 'generation_status'"
                        class="gen-status-card"
                        role="alert"
                        aria-live="polite"
                      >
                        <div class="gen-status-card__icon" aria-hidden="true">
                          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
                            <circle cx="12" cy="12" r="10" />
                            <path d="M12 8v5" stroke-linecap="round" />
                            <circle cx="12" cy="16" r="0.5" fill="currentColor" stroke="none" />
                          </svg>
                        </div>
                        <div class="gen-status-card__body-wrap">
                          <div class="gen-status-card__title">
                            {{ segment.variant === 'failed' ? '生成失败' : '生成已中断' }}
                          </div>
                          <div class="gen-status-card__message">{{ segment.message }}</div>
                        </div>
                      </div>

                      <!-- 普通 markdown：仍保持流式解析与代码块渲染（仅助手消息） -->
                      <template v-else-if="segment.kind === 'markdown'">
                        <template v-for="(mdSeg, mdIdx) in getCachedMarkdownSegments(m, segment.content, `assistant-${idx}`)" :key="`md-${idx}-${mdIdx}`">
                          <CodeBlock
                            v-if="mdSeg.type === 'code'"
                            :code="mdSeg.content"
                            :language="mdSeg.language"
                            :file-label="mdSeg.fileLabel"
                            :is-streaming="isStreamActiveForMessage(m)"
                          />
                          <!-- 证据：隐藏流式 markdown 会导致“生成计划/生成完毕”等说明文本在生成时不可见。
                               修复：流式阶段也展示文本片段，保证与历史回显一致。 -->
                          <div v-else class="text-segment" v-html="renderMarkdown(mdSeg.content)" />
                        </template>
                      </template>
                      </template>
                    </template>
                  </div>
                </div>
              </div>
            </template>
          </template>
          <div ref="chatBottomRef" class="chat-bottom-sentinel" aria-hidden="true" />
        </div>

        <AppLoopInjectBar v-if="appId" :appId="appId" @select="onLoopSelect" />

        <div :class="['chat-input-bar', { 'chat-input-bar-readonly': isReadOnly }]">
          <div v-if="selectedElement" class="selected-element-alert">
            <AAlert
              type="info"
              show-icon
              closable
              :message="`已选中元素：${selectedElementDisplay}`"
              @close="clearSelectedElement"
            >
              <template #description>
                <div style="white-space: pre-wrap;">
                  {{
                    selectedElementTextPreview
                      ? `内容预览：${selectedElementTextPreview}\n\n发送消息时会把该元素信息附加到提示词中；你也可以手动移除选中。`
                      : '发送消息时会把该元素信息附加到提示词中；你也可以手动移除选中。'
                  }}
                </div>
              </template>
            </AAlert>
          </div>
          <AInput.TextArea
            v-if="!agentWaiting"
            v-model:value="inputMessage"
            :placeholder="importedView ? '描述你想调整的内容…' : '描述你想生成的网页，例如：一个用于展示我技术博客的个人站点……'"
            :rows="3"
            :disabled="isReadOnly || streaming"
            :class="['chat-input', { 'chat-input-readonly': isReadOnly || streaming }]"
            @press-enter.prevent="handleManualSend"
          />
          <div class="chat-input-actions">
            <ImportedAgentControls v-if="appId && !isReadOnly" :key="`${importedView}-${appId}`" :app-id="appId"
              :streaming="streaming"
              @blocked="importedView ? (importedAgentBlocked = $event) : (projectAgentBlocked = $event)"
              @waiting="agentWaiting = $event"
              @merged="refreshProjectFilesEchoFromDisk(); refreshPreviewWithRetry()" />
            <div class="chat-input-buttons">
              <button v-if="showScrollToBottom" type="button" class="scroll-bottom-btn"
                @click="handleScrollToBottomClick">
                <span class="scroll-bottom-icon">↓</span>
              </button>
              <div v-show="streaming" class="vibing-status-pill" :class="{ 'vibing-status-pill--active': streaming }">
                <span class="vibing-tag">vibing<span class="vibing-tilde">&nbsp;</span></span>
                <div class="vibing-dots">
                  <span class="vibing-dot dot-emerald" :class="{ 'anim-vibe-1': streaming }" title="工作区上下文" />
                  <span class="vibing-dot dot-sky" :class="{ 'anim-vibe-2': streaming }" title="代码索引与读取" />
                  <span class="vibing-dot dot-purple" :class="{ 'anim-vibe-3': streaming }" title="单文件补丁修改" />
                  <span class="vibing-dot dot-rose" :class="{ 'anim-vibe-4': streaming }" title="落盘差异校验" />
                </div>
              </div>
              <AButton
                v-if="!streaming"
                class="ghost-btn"
                :type="visualEditMode ? 'primary' : 'default'"
                @click="toggleVisualEditMode"
              >
                {{ visualEditMode ? '退出编辑' : '编辑模式' }}
              </AButton>
              <AButton v-if="streaming" danger ghost :disabled="isReadOnly" @click="handleStop">
                停止生成
              </AButton>
              <AButton v-else type="primary" :loading="sending" :disabled="isReadOnly || (importedView && importedAgentBlocked)"
                :class="{ 'send-btn-readonly': isReadOnly }" @click="handleManualSend">
                发送
              </AButton>
            </div>
          </div>
        </div>
      </div>

      <div
        v-if="splitterVisible"
        class="main-splitter"
        :class="{ 'is-dragging': draggingSplitter }"
        role="separator"
        aria-orientation="vertical"
        aria-label="调整聊天与预览的宽度"
        @mousedown="startSplitterDrag"
        @dblclick="handleSplitterDblClick"
      >
        <button
          type="button"
          class="splitter-btn"
          title="放大预览区"
          aria-label="放大预览区"
          @mousedown.stop
          @click="toggleFocusSide('preview')"
        >▶</button>
        <button
          type="button"
          class="splitter-btn"
          title="恢复等分"
          aria-label="恢复等分"
          @mousedown.stop
          @click="focusedSide = 'none'"
        >⛶</button>
        <button
          type="button"
          class="splitter-btn"
          title="放大聊天区"
          aria-label="放大聊天区"
          @mousedown.stop
          @click="toggleFocusSide('chat')"
        >◀</button>
      </div>

      <div
        v-if="appearanceStore.previewExpanded"
        v-show="!previewHidden"
        class="preview-panel cosmic-workbench"
      >
        <div class="preview-header">
        <!-- 页签按「实际能力 + 用户勾选的看板功能」决定，纯后端且全不选时右侧留空 -->
        <div v-if="runtimeTabs.length > 1" class="preview-tabs">
          <button
            v-for="tab in runtimeTabs"
            :key="tab.key"
            type="button"
            class="preview-tab"
            :class="{ 'is-active': runtimePanelTab === tab.key }"
            :aria-pressed="runtimePanelTab === tab.key"
            @click="runtimePanelTab = tab.key"
          >
            {{ tab.label }}
          </button>
        </div>

          <div class="preview-header-right">
            <button type="button" class="wb-button" @click="enterPreviewFullscreen">全屏</button>
            <button type="button" class="wb-button" :aria-label="focusedSide === 'preview' ? '恢复双栏' : '放大工作台'" @click="toggleFocusSide('preview')">{{ focusedSide === 'preview' ? '恢复双栏' : '⛶ 放大' }}</button>
            <div v-if="isAppDeployed" class="deploy-url-pill">
              <span class="deploy-url-label">部署的访问网址</span>
              <ATooltip :title="deployedAccessUrl">
                <AInput
                  readonly
                  size="small"
                  class="deploy-url-input"
                  :value="deployedAccessUrl"
                />
              </ATooltip>
              <a-button size="small" class="ghost-btn" @click="handleCopyDeployUrl">
                复制
              </a-button>
              <a-button size="small" class="ghost-btn" type="primary" @click="handleVisitDeployUrl">
                访问
              </a-button>
            </div>

            <ATooltip
              v-if="shouldShowWebsite"
              :title="refreshAppCtaVisible ? refreshAppCtaHint : '强制重新加载预览 iframe，避免浏览器缓存导致仍显示旧页面'"
            >
              <a-button
                size="small"
                class="ghost-btn preview-refresh-btn"
                :loading="refreshingApp"
                @click="handleManualRefreshApp"
              >
                刷新应用
              </a-button>
            </ATooltip>
          </div>
        </div>

        <div ref="fullscreenBody" class="preview-body" :class="{ 'is-fullscreen': previewFullscreen }">
          <div v-if="previewFullscreen" class="fullscreen-exit-zone">
            <button type="button" class="wb-button" aria-label="退出全屏" @click="exitPreviewFullscreen">返回</button>
          </div>
          <div v-if="previewFullscreen && fullscreenHint" class="fullscreen-hint" role="status">按 Esc 或移到左上角返回</div>
          <!-- 监控 -->
          <RuntimeMetricsPanel
            v-if="runtimePanelTab === 'metrics' && showMetricsPanel"
            :app-id="appId ?? ''"
            :api-base-url="API_BASE_URL"
            :running="runtimeRunning"
          />

          <!-- 日志 -->
          <RuntimeLogsPanel
            v-else-if="runtimePanelTab === 'logs' && showLogsPanel"
            :app-id="appId ?? ''"
            :api-base-url="API_BASE_URL"
            :running="runtimeRunning"
          />

          <!-- 知识：文档 / 图谱 / 只读问答 -->
          <KnowledgePanel
            v-else-if="runtimePanelTab === 'knowledge' && dashboardFeatureSet.has('knowledge')"
            :app-id="appId ?? ''"
            :api-base-url="API_BASE_URL"
            @open-source="handleOpenSourceFromGraph"
          />

          <!-- 预览 -->
          <template v-else>
            <div v-if="!shouldShowWebsite" class="preview-empty">
              <AEmpty :description="runtimeRunning ? '应用已启动，等待端口就绪' : importedView ? '项目尚未运行' : 'AI 未生成内容，请先完成至少一轮对话'" />
            </div>
            <iframe
              v-else
              ref="previewIframeRef"
              :key="iframeKey"
              class="preview-iframe"
              :src="previewUrl"
              @load="handlePreviewIframeLoad"
            />
          </template>
        </div>
      </div>

      <!-- 右边缘恢复双栏小箭头：只显示聊天区时展示在屏幕右侧 -->
      <Transition name="edge-restore-right">
        <ATooltip
          v-if="rightRestoreVisible"
          title="展开预览区（恢复双栏，或按 Esc）"
          placement="left"
        >
          <button
            type="button"
            class="edge-restore-trigger edge-restore-trigger--right"
            aria-label="展开预览区（恢复双栏，或按 Esc）"
            @click="restoreSplitView"
          >
            <span class="edge-restore-arrow" aria-hidden="true">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round">
                <polyline points="15 18 9 12 15 6" />
              </svg>
            </span>
          </button>
        </ATooltip>
      </Transition>
    </section>
  </main>
</template>

<style scoped>
.app-chat-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-radius: 16px;
  background: var(--bg-card, rgba(255, 255, 255, 0.9));
  box-shadow: 0 4px 16px rgba(15, 23, 42, 0.08);
}

.app-title-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
}

.app-avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(135deg, #34d399, #22c55e);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 600;
}

.app-meta {
  display: flex;
  flex-direction: column;
}

.app-name {
  font-size: 18px;
  font-weight: 600;
}

.app-subtitle {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

/* 应用标题右侧：纤细竖线 + 对话轮数，仅创建者/管理员可见 */
.round-count-wrap {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 8px;
  min-height: 24px;
}

.round-count-line {
  width: 1px;
  height: 20px;
  background: linear-gradient(to bottom, transparent, rgba(15, 23, 42, 0.12), transparent);
  flex-shrink: 0;
}

.round-count-text {
  font-size: 12px;
  color: var(--text-muted, #64748b);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}

.top-actions {
  display: flex;
  gap: 8px;
}

.ghost-btn {
  border-radius: 999px;
}

.main-content {
  height: auto;
  flex: 1;
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1.1fr) minmax(0, 1.1fr);
  /* 间隔由中间那 16px 的分隔线列提供；再用 gap 会叠成两倍 */
  gap: 0;
  flex: 1;
  min-height: 0;
  align-items: stretch;
  overflow: hidden;
}

/* 全局拖拽保护：无论光标滑到屏幕何处，保持 col-resize 与禁止文字选中 */
body.is-splitter-resizing,
body.is-splitter-resizing * {
  cursor: col-resize !important;
  user-select: none !important;
}

/* 拖拽中整块禁止选中文字，且 iframe 必须禁用 pointer-events，否则鼠标滑入 iframe 会丢失 mousemove/mouseup 导致拖拽假死 */
.main-content.is-splitter-dragging {
  user-select: none;
}

.main-content.is-splitter-dragging iframe,
.main-content.is-splitter-dragging .preview-iframe {
  pointer-events: none !important;
}

.main-splitter {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  cursor: col-resize;
  user-select: none;
  touch-action: none;
}

/* 隐形扩展可抓取热区，左右向外扩展，方便鼠标快速捕获与点击 */
.main-splitter::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -4px;
  right: -4px;
  z-index: 0;
}

.main-splitter::before {
  content: '';
  position: absolute;
  top: 8px;
  bottom: 8px;
  left: 50%;
  width: 2px;
  margin-left: -1px;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.12);
  transition: background 160ms ease, width 160ms ease, margin-left 160ms ease, opacity 160ms ease;
}

.main-splitter:hover::before,
.main-splitter.is-dragging::before {
  width: 4px;
  margin-left: -2px;
  background: var(--color-primary, #1677ff);
  opacity: 0.9;
}

.splitter-btn {
  position: relative;
  z-index: 1;
  width: 22px;
  height: 22px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  font-size: 10px;
  line-height: 1;
  color: #64748b;
  background: var(--bg-card, #fff);
  border: 1px solid rgba(15, 23, 42, 0.12);
  border-radius: 6px;
  cursor: pointer;
  transition: color 160ms ease, border-color 160ms ease;
}

.splitter-btn:hover {
  color: var(--color-primary, #1677ff);
  border-color: var(--color-primary, #1677ff);
}

/* 单侧展开时贴在屏幕边缘的恢复双栏小箭头按钮 */
.edge-restore-trigger {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 100;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 60px;
  padding: 0;
  background: var(--bg-card, #ffffff);
  border: 1px solid rgba(15, 23, 42, 0.12);
  color: #64748b;
  cursor: pointer;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.08);
  transition: width 180ms ease, background 180ms ease, color 180ms ease, border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
  user-select: none;
}

.edge-restore-trigger:focus-visible {
  outline: 2px solid var(--color-primary, #1677ff);
  outline-offset: 2px;
}

.edge-restore-trigger--left {
  left: 0;
  border-left: none;
  border-radius: 0 8px 8px 0;
}

.edge-restore-trigger--right {
  right: 0;
  border-right: none;
  border-radius: 8px 0 0 8px;
}

.edge-restore-trigger:hover {
  width: 30px;
  color: var(--color-primary, #1677ff);
  border-color: var(--color-primary, #1677ff);
  background: var(--bg-card, #ffffff);
  box-shadow: 0 4px 14px rgba(22, 119, 255, 0.2);
}

.edge-restore-trigger--left:hover {
  transform: translateY(-50%) translateX(2px);
}

.edge-restore-trigger--right:hover {
  transform: translateY(-50%) translateX(-2px);
}

.edge-restore-arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  line-height: 1;
}

/* 边缘小箭头淡入淡出与微位移动效 */
.edge-restore-left-enter-active,
.edge-restore-left-leave-active,
.edge-restore-right-enter-active,
.edge-restore-right-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.edge-restore-left-enter-from,
.edge-restore-left-leave-to {
  opacity: 0;
  transform: translateY(-50%) translateX(-12px);
}

.edge-restore-right-enter-from,
.edge-restore-right-leave-to {
  opacity: 0;
  transform: translateY(-50%) translateX(12px);
}

[data-theme="dark"] .edge-restore-trigger {
  background: var(--bg-card, #1e293b);
  border-color: rgba(148, 163, 184, 0.2);
  color: #94a3b8;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.35);
}

[data-theme="dark"] .edge-restore-trigger:hover {
  color: var(--color-primary, #38bdf8);
  border-color: var(--color-primary, #38bdf8);
  box-shadow: 0 4px 14px rgba(56, 189, 248, 0.25);
}

.dashboard-settings-hint {
  margin: 0 0 12px;
  color: #64748b;
  font-size: 12px;
  line-height: 1.6;
}

/* 运行状态标记：运行中 / 运行版本落后 */
.runtime-status-pill {
  display: inline-flex;
  align-items: center;
  height: 24px;
  padding: 0 10px;
  border-radius: 999px;
  font-size: 12px;
  color: #0f766e;
  background: #f0fdfa;
  border: 1px solid #99f6e4;
}

.runtime-status-pill.is-stale {
  color: #b45309;
  background: #fffbeb;
  border-color: #fde68a;
}

/* 运行异常弹窗 */
.incident-summary {
  margin: 0 0 10px;
  font-size: 13px;
  color: #cf1322;
  word-break: break-all;
}

.incident-detail summary {
  cursor: pointer;
  font-size: 12px;
  color: #64748b;
  margin-bottom: 6px;
}

.incident-detail-body {
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 8px 10px;
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 6px;
  font-family: Consolas, 'Courier New', monospace;
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}

.incident-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #8c8c8c;
  line-height: 1.6;
}

.preview-tabs {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 2px 8px;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 8px;
}

.preview-tab {
  height: 28px;
  padding: 0 12px;
  border: 1px solid transparent;
  border-radius: 6px;
  background: transparent;
  color: #64748b;
  font-size: 13px;
  cursor: pointer;
  transition: color 160ms ease, background 160ms ease;
}

.preview-tab:hover {
  color: var(--color-primary, #1677ff);
  background: #f5f8ff;
}

.preview-tab.is-active {
  color: var(--color-primary, #1677ff);
  background: #eff6ff;
  border-color: #bfdbfe;
  font-weight: 600;
}

.chat-panel,
.preview-panel {
  position: relative;
  background: var(--bg-card, rgba(255, 255, 255, 0.94));
  border-radius: 18px;
  padding: 16px;
  display: flex;
  flex-direction: column;
  flex: 1;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

.generated-files-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  margin-bottom: 10px;
  border-radius: 14px;
  background: radial-gradient(circle at 20% 0%, rgba(34, 197, 94, 0.18), rgba(14, 165, 233, 0.12) 55%, rgba(15, 23, 42, 0.02));
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.05);
}

.generated-files-left {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.generated-files-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: linear-gradient(135deg, #22c55e, #0ea5e9);
  box-shadow: 0 0 0 4px rgba(34, 197, 94, 0.12);
  flex: 0 0 auto;
}

.generated-files-text {
  font-size: 12px;
  color: var(--text-base, #0f172a);
  opacity: 0.86;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.files-modal {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 12px;
  min-height: 640px;
}

.files-list {
  border-radius: 14px;
  background: var(--bg-soft, rgba(249, 250, 251, 1));
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.files-list-title {
  font-size: 12px;
  color: var(--text-base, #334155);
  font-weight: 600;
  letter-spacing: 0.02em;
}

.file-path-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.files-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.file-item {
  width: 100%;
  text-align: left;
  border: none;
  border-radius: 12px;
  padding: 10px 10px;
  cursor: pointer;
  background: var(--bg-card, rgba(255, 255, 255, 0.85));
  box-shadow: inset 0 0 0 1px rgba(15, 23, 42, 0.06);
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition: transform 0.15s ease, box-shadow 0.15s ease, background 0.15s ease;
}

.file-item:hover {
  transform: translateY(-1px);
  background: var(--bg-card, rgba(255, 255, 255, 1));
  box-shadow:
    0 8px 18px rgba(15, 23, 42, 0.08),
    inset 0 0 0 1px rgba(15, 23, 42, 0.06);
}

.file-item.active {
  box-shadow:
    0 10px 22px rgba(15, 23, 42, 0.1),
    inset 0 0 0 1px rgba(14, 165, 233, 0.45);
}

.file-path {
  font-size: 12px;
  color: var(--text-base, #0f172a);
  word-break: break-all;
}

.file-path--vue {
  color: #2563eb;
  font-weight: 600;
}

.file-path--script {
  color: #16a34a;
  font-weight: 500;
}

.file-path--style {
  color: #db2777;
}

.file-path--html {
  color: #ea580c;
}

.file-path--config {
  color: #7c3aed;
}

.file-path--asset {
  color: #0ea5e9;
}

.file-path--default {
  color: var(--text-base, #0f172a);
}

.file-core-badge {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  color: #065f46;
  background: linear-gradient(135deg, #bbf7d0, #6ee7b7);
  box-shadow: 0 0 0 1px rgba(22, 163, 74, 0.35);
}

.file-meta {
  font-size: 11px;
  color: var(--text-muted, #64748b);
  font-variant-numeric: tabular-nums;
}

.files-viewer {
  border-radius: 14px;
  background: var(--bg-soft, rgba(249, 250, 251, 1));
  padding: 10px;
  min-height: 0;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.files-viewer-empty {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
}

.files-viewer-inner {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.files-viewer-code {
  flex: 1;
  min-height: 0;
  display: flex;
}

.files-viewer-code :deep(.code-block-container) {
  margin: 0;
  width: 100%;
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.files-viewer-code :deep(.code-block-container .code-block) {
  flex: 1 1 auto;
  height: 0;
  min-height: 0;
  max-height: none !important;
}

.files-viewer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.files-viewer-path {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-base, #0f172a);
  word-break: break-all;
}

.files-viewer-actions {
  flex: 0 0 auto;
}

.chat-messages {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  padding-right: 4px;
  min-height: 0;
  scrollbar-width: thin;
  scrollbar-color: rgba(0, 0, 0, 0.15) transparent;
}

.chat-bottom-sentinel {
  block-size: 1px;
  inline-size: 100%;
  overflow-anchor: auto;
}

.chat-messages::-webkit-scrollbar {
  width: 6px;
}

.chat-messages::-webkit-scrollbar-track {
  background: transparent;
}

.chat-messages::-webkit-scrollbar-thumb {
  background: rgba(0, 0, 0, 0.12);
  border-radius: 3px;
}

.chat-messages::-webkit-scrollbar-thumb:hover {
  background: rgba(0, 0, 0, 0.2);
}

.load-more-wrap {
  text-align: center;
  padding: 8px 0;
  margin-bottom: 8px;
}

.chat-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-empty {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.chat-bubble {
  display: flex;
  margin-bottom: 10px;
}



.bubble-ai .bubble-inner {
  max-width: 100%;
  width: 100%;
}

.bubble-inner {
  max-width: 80%;
  border-radius: 18px;
  padding: 8px 12px;
  background: var(--bg-soft, #f3f4f6);
}

.bubble-user .bubble-inner {
  background: #2563eb;
  color: white;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.visual-edit-selection-card {
  position: relative;
  margin-top: 4px;
  border-radius: 14px;
  overflow: hidden;
  isolation: isolate;
  color: var(--text-base, #0f172a);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.65) inset,
    0 10px 28px rgba(15, 23, 42, 0.14),
    0 0 0 1px rgba(255, 255, 255, 0.22);
  transition: box-shadow 220ms ease, transform 220ms ease;
}

.visual-edit-selection-card:hover {
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.75) inset,
    0 14px 32px rgba(15, 23, 42, 0.16),
    0 0 0 1px rgba(255, 255, 255, 0.28);
}

@media (prefers-reduced-motion: reduce) {
  .visual-edit-selection-card {
    transition: none;
  }
}

.visual-edit-selection-card__glow {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 120% 80% at 0% -20%, rgba(191, 219, 254, 0.55), transparent 52%),
    radial-gradient(ellipse 90% 70% at 100% 110%, rgba(199, 210, 254, 0.35), transparent 48%),
    linear-gradient(145deg, rgba(255, 255, 255, 0.98) 0%, rgba(248, 250, 252, 0.94) 48%, rgba(241, 245, 249, 0.92) 100%);
  pointer-events: none;
}

.visual-edit-selection-card__inner {
  position: relative;
  padding: 11px 12px 12px;
  border-radius: inherit;
  border: 1px solid rgba(255, 255, 255, 0.55);
  background-image:
    linear-gradient(rgba(37, 99, 235, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(37, 99, 235, 0.04) 1px, transparent 1px);
  background-size: 18px 18px;
  background-position: -1px -1px;
}

.visual-edit-selection-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.visual-edit-selection-card__identity {
  display: flex;
  align-items: center;
  min-width: 0;
}

.visual-edit-selection-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 9px 3px 7px;
  border-radius: 999px;
  background: linear-gradient(135deg, #1e40af 0%, #2563eb 48%, #4338ca 100%);
  color: #f8fafc;
  font-size: 11px;
  letter-spacing: 0.06em;
  font-weight: 600;
  box-shadow: 0 2px 8px rgba(30, 64, 175, 0.28);
}

.visual-edit-badge-icon {
  flex-shrink: 0;
  opacity: 0.92;
}

.visual-edit-copy-btn {
  flex-shrink: 0;
  color: var(--color-primary, #1e3a8a) !important;
  border-color: rgba(99, 102, 241, 0.28) !important;
  background: var(--bg-card, rgba(255, 255, 255, 0.78)) !important;
  backdrop-filter: blur(6px);
  cursor: pointer;
  transition: background-color 180ms ease, border-color 180ms ease, color 180ms ease;
}

.visual-edit-copy-btn:hover {
  color: #1d4ed8 !important;
  border-color: rgba(37, 99, 235, 0.45) !important;
  background: var(--bg-card, rgba(255, 255, 255, 0.95)) !important;
}

.visual-edit-selector-chip {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(148, 163, 184, 0.28);
  background: var(--bg-card, rgba(255, 255, 255, 0.72));
  backdrop-filter: blur(4px);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.visual-edit-selector-chip__label {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: #6366f1;
}

.visual-edit-selector-chip__value {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--text-base, #0f172a);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', monospace;
  word-break: break-all;
}

.visual-edit-selection-card__fields {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px dashed rgba(148, 163, 184, 0.35);
  background: var(--bg-card, rgba(255, 255, 255, 0.5));
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.visual-edit-field-row {
  display: grid;
  grid-template-columns: 64px minmax(0, 1fr);
  gap: 10px;
  align-items: start;
  font-size: 11.5px;
  line-height: 1.55;
}

.visual-edit-field-row + .visual-edit-field-row {
  padding-top: 7px;
  border-top: 1px solid rgba(148, 163, 184, 0.2);
}

.visual-edit-field-label {
  color: var(--text-secondary, #475569);
  font-weight: 600;
  font-size: 10px;
  letter-spacing: 0.04em;
  text-transform: lowercase;
}

.visual-edit-field-value {
  color: var(--text-base, #1e293b);
  word-break: break-word;
  white-space: pre-wrap;
}

.visual-edit-field-value--text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.visual-edit-expand-btn {
  border: none;
  padding: 2px 0 0;
  background: transparent;
  color: #4f46e5;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  transition: color 160ms ease;
}

.visual-edit-expand-btn:hover {
  color: #2563eb;
  text-decoration: underline;
}

.bubble-role {
  font-size: 11px;
  opacity: 0.7;
  margin-bottom: 4px;
}

/* Codex 风格流光文字波动动效 (顺着文字从左到右滑过) */
@keyframes codex-shimmer {
  0% {
    background-position: 200% 0;
  }
  100% {
    background-position: -200% 0;
  }
}

.bubble-role--shimmer {
  opacity: 1 !important;
  background: linear-gradient(
    90deg,
    var(--text-base, #1f1f1f) 0%,
    var(--text-base, #1f1f1f) 20%,
    #1677ff 45%,
    #06b6d4 55%,
    #1677ff 65%,
    var(--text-base, #1f1f1f) 80%,
    var(--text-base, #1f1f1f) 100%
  );
  background-size: 250% 100%;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  animation: codex-shimmer 2.2s cubic-bezier(0.4, 0, 0.2, 1) infinite;
  display: inline-block;
  font-weight: 600;
  letter-spacing: 0.2px;
}

.bubble-content {
  white-space: normal;
  word-break: break-word;
  font-size: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.text-segment {
  display: block;
  line-height: 1.55;
}

.text-segment :deep(p) { margin: 0 0 8px; }
.text-segment :deep(li) { margin: 4px 0; }
.text-segment :deep(ul), .text-segment :deep(ol) { margin: 8px 0; padding-left: 24px; }
.text-segment :deep(h1), .text-segment :deep(h2), .text-segment :deep(h3) { margin: 12px 0 8px; }
.text-segment :deep(pre) { white-space: pre; overflow-x: auto; }
.tool-exec-card :deep(.tool-call-badge) { padding: 2px 4px; background: transparent; color: var(--text-secondary, #64748b); font-size: 10px; }
.tool-exec-card :deep(.tool-exec-action-label) { font-size: 12px; font-weight: 400; }


.workflow-steps-shell {
  align-self: stretch;
  display: flex;
  align-items: stretch;
  gap: 8px;
  margin-bottom: 4px;
}

.workflow-toggle-btn {
  border: 1px solid rgba(14, 165, 233, 0.35);
  border-radius: 10px;
  width: 24px;
  min-width: 24px;
  background: var(--color-primary-bg, #f0f9ff);
  color: #0c4a6e;
  font-weight: 700;
  cursor: pointer;
}

.workflow-steps-card {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid rgba(14, 165, 233, 0.35);
  background:
    radial-gradient(circle at 0 0, rgba(224, 242, 254, 0.9), transparent 55%),
    var(--bg-soft, #f8fafc);
}

.workflow-steps-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
}

.workflow-steps-title {
  font-size: 11px;
  font-weight: 700;
  color: #0c4a6e;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.workflow-mermaid-notice {
  font-size: 11px;
  color: #9a3412;
  background: #ffedd5;
  border: 1px solid #fdba74;
  border-radius: 999px;
  padding: 1px 8px;
}

.workflow-steps-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.workflow-step-card {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
  color: var(--text-base, #334155);
  line-height: 1.45;
  border: 1px solid rgba(148, 163, 184, 0.3);
  background: var(--bg-card, rgba(255, 255, 255, 0.75));
  border-radius: 8px;
  padding: 7px 8px;
}

.workflow-step-card--latest .workflow-step-label {
  font-weight: 600;
  color: var(--text-base, #0f172a);
}

.workflow-step-card--latest .workflow-step-badge {
  background: linear-gradient(135deg, #0ea5e9, #2563eb);
  color: #f8fafc;
  animation: workflowPulse 1.2s ease-in-out infinite;
}

.workflow-steps-shell--collapsed .workflow-steps-card {
  max-height: 54px;
  overflow: hidden;
}

.workflow-step-badge {
  flex: 0 0 auto;
  min-width: 22px;
  height: 22px;
  border-radius: 6px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  background: var(--bg-mute, #e2e8f0);
  color: var(--text-secondary, #475569);
}

.workflow-step-label {
  flex: 1;
  min-width: 0;
}

.workflow-step-label--success {
  color: #16a34a;
}

.workflow-step-label--failed {
  color: #dc2626;
}

.workflow-step-label--pending {
  color: #94a3b8;
}

.workflow-step-slow-hint {
  flex: 0 0 auto;
  margin-left: auto;
  font-size: 11px;
  line-height: 1.2;
  color: #9a3412;
  border: 1px solid rgba(251, 146, 60, 0.5);
  background: linear-gradient(135deg, rgba(254, 215, 170, 0.45), rgba(255, 237, 213, 0.85));
  border-radius: 999px;
  padding: 2px 8px;
  white-space: nowrap;
  opacity: 0;
  visibility: hidden;
  transition: opacity 0.2s ease;
}

.workflow-step-slow-hint--visible {
  opacity: 1;
  visibility: visible;
}

@keyframes workflowPulse {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(14, 165, 233, 0.35);
  }
  50% {
    box-shadow: 0 0 0 4px rgba(14, 165, 233, 0);
  }
}

.typing-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(59, 130, 246, 0.18);
  background:
    radial-gradient(circle at 12% 20%, rgba(59, 130, 246, 0.14), transparent 55%),
    radial-gradient(circle at 90% 30%, rgba(45, 212, 191, 0.12), transparent 52%),
    rgba(15, 23, 42, 0.04);
  box-shadow:
    0 10px 24px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.5);
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: rgba(37, 99, 235, 0.65);
  box-shadow: 0 0 10px rgba(59, 130, 246, 0.26);
  animation: typingDot 1.4s infinite ease-in-out;
}

.typing-dot:nth-child(2) {
  animation-delay: 0.15s;
}

.typing-dot:nth-child(3) {
  animation-delay: 0.3s;
}

.typing-text {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--text-secondary, rgba(15, 23, 42, 0.7));
}

.thinking-summary {
  display: inline-flex;
  align-items: center;
  margin-bottom: 8px;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(30, 64, 175, 0.25);
  background:
    linear-gradient(120deg, rgba(219, 234, 254, 0.72), rgba(199, 210, 254, 0.54)),
    rgba(255, 255, 255, 0.88);
  color: rgba(30, 58, 138, 0.95);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

@keyframes typingDot {

  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.35;
  }

  30% {
    transform: translateY(-3px);
    opacity: 0.8;
  }
}

@media (prefers-reduced-motion: reduce) {
  .typing-dot {
    animation: none;
    opacity: 0.6;
  }
}

/* 代码块在对话气泡中的样式：占满宽度，内嵌淡色滚动条 */
.bubble-content :deep(.code-block-container) {
  max-width: 100%;
  width: 100%;
  margin: 0;
  border-radius: 6px;
  flex: 1;
}

.bubble-content :deep(.code-block-container .code-block) {
  max-height: none;
  flex: 1;
}

.bubble-user .bubble-content :deep(.code-block-container) {
  background: rgba(0, 0, 0, 0.2);
  border-color: rgba(255, 255, 255, 0.2);
}

.bubble-user .bubble-content :deep(.code-block-header) {
  background: rgba(0, 0, 0, 0.3);
  border-bottom-color: rgba(255, 255, 255, 0.1);
}

.bubble-user .bubble-content :deep(.code-block) {
  background: rgba(0, 0, 0, 0.2);
  color: rgba(255, 255, 255, 0.9);
}

.chat-input-bar {
  margin-top: 8px;
  box-sizing: border-box;
  border: 1px solid #d4d4d4;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 2px 6px #00000004;
  /* 底部比上侧更紧，压缩操作栏下方的空白。 */
  padding: 12px 12px 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chat-input-bar:focus-within {
  border-color: #a3a3a3;
}

.selected-element-alert :deep(.ant-alert) {
  border-radius: 14px;
  border: 1px solid rgba(14, 165, 233, 0.25);
  background:
    radial-gradient(circle at 0 0, rgba(14, 165, 233, 0.18), transparent 55%),
    rgba(255, 255, 255, 0.75);
  box-shadow:
    inset 0 0 0 1px rgba(15, 23, 42, 0.03),
    0 10px 22px rgba(15, 23, 42, 0.06);
}

.selected-element-alert :deep(.ant-alert-message) {
  font-weight: 600;
}

.selected-element-alert :deep(.ant-alert-description) {
  color: rgba(15, 23, 42, 0.72);
}

.chat-input-bar-readonly {
  opacity: 0.7;
}

/* 输入区与容器融合：去边框、透明底，焦点态交给外框。 */
.chat-input :deep(.ant-input) {
  border: 0;
  border-radius: 0;
  padding: 0 4px;
  box-shadow: none;
  background: transparent;
  resize: vertical;
  min-height: 52px;
}

.chat-input :deep(.ant-input:focus-visible) {
  outline: 2px solid #2563eb;
  outline-offset: 4px;
  border-radius: 2px;
}

.chat-input-readonly :deep(.ant-input) {
  cursor: not-allowed;
  background-color: var(--bg-soft, #f3f4f6);
}

.chat-input-actions {
  display: flex;
  flex-wrap: nowrap;
  /* 卡片展开时行会变高；按钮必须贴底，居中会浮在差异区中间挡住内容。 */
  align-items: flex-end;
  gap: 8px;
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

/* 控件组占满左侧，按钮组靠右；与设计稿的 .send-action { margin-left: auto } 一致。 */
.chat-input-actions > .agent-controls {
  flex: 1;
  min-width: 0;
}

.chat-input-buttons {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-shrink: 0;
  margin-left: auto;
}

/* vibing~ 高级微胶囊状态条 */
.vibing-status-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 3px 9px 3px 8px;
  border-radius: 20px;
  background: rgba(245, 247, 250, 0.85);
  border: 1px solid rgba(226, 232, 240, 0.8);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  font-family: 'JetBrains Mono', 'Fira Code', ui-monospace, SFMono-Regular, Menlo, monospace;
  user-select: none;
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}

.vibing-status-pill:hover {
  background: rgba(240, 243, 248, 0.95);
  border-color: rgba(203, 213, 225, 0.9);
}

.vibing-status-pill--active {
  background: linear-gradient(135deg, rgba(240, 245, 255, 0.9) 0%, rgba(245, 243, 255, 0.9) 100%);
  border-color: rgba(199, 210, 254, 0.7);
  box-shadow: 0 1px 6px rgba(99, 102, 241, 0.08);
}

.vibing-tag {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.2px;
  line-height: 1;
  background: linear-gradient(135deg, #4f46e5 0%, #1677ff 50%, #06b6d4 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  display: inline-flex;
  align-items: center;
}

.vibing-status-pill--active .vibing-tag {
  background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 45%, #ec4899 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.vibing-tilde {
  display: inline-block;
  margin-left: 0.5px;
  transform-origin: center;
}

.vibing-status-pill--active .vibing-tilde {
  animation: vibe-tilde-wave 1.6s ease-in-out infinite;
}

@keyframes vibe-tilde-wave {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  50% { transform: translateY(-1px) rotate(8deg); }
}

.vibing-dots {
  display: inline-flex;
  align-items: center;
  gap: 3.5px;
}

.vibing-dot {
  display: inline-block;
  width: 6.5px;
  height: 6.5px;
  border-radius: 2px;
  transition: all 0.22s ease;
  opacity: 0.85;
}

.dot-emerald { background: linear-gradient(135deg, #10b981, #059669); }
.dot-sky     { background: linear-gradient(135deg, #0ea5e9, #0284c7); }
.dot-purple  { background: linear-gradient(135deg, #8b5cf6, #6d28d9); }
.dot-rose    { background: linear-gradient(135deg, #f43f5e, #e11d48); }

@keyframes vibe-dot-pulse-1 {
  0%, 100% { transform: scale(0.9); opacity: 0.45; }
  50% { transform: scale(1.2); opacity: 1; filter: drop-shadow(0 0 4px rgba(16, 185, 129, 0.5)); }
}
@keyframes vibe-dot-pulse-2 {
  0%, 100% { transform: scale(0.9); opacity: 0.45; }
  50% { transform: scale(1.2); opacity: 1; filter: drop-shadow(0 0 4px rgba(14, 165, 233, 0.5)); }
}
@keyframes vibe-dot-pulse-3 {
  0%, 100% { transform: scale(0.9); opacity: 0.45; }
  50% { transform: scale(1.2); opacity: 1; filter: drop-shadow(0 0 4px rgba(139, 92, 246, 0.5)); }
}
@keyframes vibe-dot-pulse-4 {
  0%, 100% { transform: scale(0.9); opacity: 0.45; }
  50% { transform: scale(1.2); opacity: 1; filter: drop-shadow(0 0 4px rgba(244, 63, 94, 0.5)); }
}

.anim-vibe-1 { animation: vibe-dot-pulse-1 1.2s cubic-bezier(0.4, 0, 0.2, 1) infinite; }
.anim-vibe-2 { animation: vibe-dot-pulse-2 1.2s cubic-bezier(0.4, 0, 0.2, 1) infinite 0.25s; }
.anim-vibe-3 { animation: vibe-dot-pulse-3 1.2s cubic-bezier(0.4, 0, 0.2, 1) infinite 0.5s; }
.anim-vibe-4 { animation: vibe-dot-pulse-4 1.2s cubic-bezier(0.4, 0, 0.2, 1) infinite 0.75s; }

.send-btn-readonly {
  cursor: not-allowed;
}

.preview-header {
  margin-bottom: 8px;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.preview-header-left {
  min-width: 0;
}

.preview-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  padding-top: 2px;
}

.deploy-url-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-radius: 999px;
  background:
    radial-gradient(circle at 0 0, rgba(34, 197, 94, 0.18), transparent 55%),
    rgba(255, 255, 255, 0.85);
  box-shadow:
    inset 0 0 0 1px rgba(13, 148, 136, 0.32),
    0 4px 10px rgba(15, 23, 42, 0.06);
}

.deploy-url-label {
  font-size: 11px;
  color: var(--text-base, #0f172a);
  opacity: 0.72;
  white-space: nowrap;
}

.deploy-url-input {
  width: 220px;
  max-width: 28vw;
}

.deploy-url-input :deep(.ant-input) {
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  padding: 2px 8px;
  border-radius: 999px;
}

.preview-title {
  font-size: 15px;
  font-weight: 600;
}

.preview-subtitle {
  font-size: 12px;
  color: var(--text-secondary, #6b7280);
}

.preview-refresh-btn {
  border-radius: 999px;
  box-shadow:
    0 10px 22px rgba(15, 23, 42, 0.1),
    inset 0 0 0 1px rgba(14, 165, 233, 0.35);
}

.preview-body {
  flex: 1;
  border-radius: 12px;
  background: var(--bg-soft, #f3f4f6);
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}

.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: var(--bg-base, white);
}

.scroll-bottom-btn {
  width: 30px;
  height: 30px;
  border-radius: 8px;
  border: none;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: radial-gradient(circle at 30% 0%, #bbf7d0 0, rgba(34, 197, 94, 0.85) 40%, #0ea5e9 100%);
  box-shadow:
    0 10px 25px rgba(15, 23, 42, 0.25),
    0 0 0 1px rgba(15, 23, 42, 0.04);
  color: #f9fafb;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  transition:
    transform 0.18s ease-out,
    box-shadow 0.18s ease-out,
    opacity 0.18s ease-out,
    background 0.25s ease-out;
  opacity: 0.98;
}

.scroll-bottom-btn:hover {
  transform: translateY(-2px) scale(1.03);
  box-shadow:
    0 14px 30px rgba(15, 23, 42, 0.3),
    0 0 0 1px rgba(15, 23, 42, 0.06);
}

.scroll-bottom-btn:active {
  transform: translateY(0) scale(0.98);
  box-shadow:
    0 6px 15px rgba(15, 23, 42, 0.25),
    0 0 0 1px rgba(15, 23, 42, 0.06);
}

.scroll-bottom-icon {
  font-size: 18px;
  line-height: 1;
  transform: translateY(1px);
  text-shadow: 0 1px 2px rgba(15, 23, 42, 0.35);
}

.gen-status-card {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin: 6px 0 8px;
  padding: 12px 14px;
  border: 1px solid #fecaca;
  border-left: 4px solid #dc2626;
  border-radius: 8px;
  background: #fef2f2;
  color: #7f1d1d;
}

.gen-status-card__icon {
  flex-shrink: 0;
  margin-top: 1px;
  color: #dc2626;
}

.gen-status-card__title {
  font-size: 13px;
  font-weight: 600;
  color: #991b1b;
  line-height: 1.4;
}

.gen-status-card__message {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #7f1d1d;
}

.tool-call-card {
  align-self: stretch;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 14px;
  border: 1px solid rgba(148, 163, 184, 0.8);
  background:
    radial-gradient(circle at 0 0, rgba(226, 232, 240, 0.9), rgba(148, 163, 184, 0.2) 55%),
    var(--bg-soft, #f9fafb);
  box-shadow:
    0 8px 18px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(15, 23, 42, 0.02);
}

.tool-exec-card {
  align-self: stretch;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(148, 163, 184, 0.8);
  background:
    radial-gradient(circle at 0 0, rgba(226, 232, 240, 0.9), rgba(148, 163, 184, 0.2) 55%),
    var(--bg-soft, #f9fafb);
  box-shadow:
    0 8px 18px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(15, 23, 42, 0.02);
}

.tool-call-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-base, #111827);
}

.bubble-content :deep(.code-block-container) {
  max-width: 100%;
  width: 100%;
  margin: 2px 0 0;
  border-radius: 6px;
  flex: 1;
}

.main-content--single {
  grid-template-columns: minmax(0, 1fr);
}

.main-content--single .preview-panel {
  display: none;
}

@media (max-width: 992px) {
  .main-content {
    grid-template-columns: minmax(0, 1fr);
    /* 窄屏是上下堆叠，恢复面板间距 */
    gap: 16px;
    flex: 1;
    min-height: 0;
    overflow: hidden;
  }

  .deploy-url-input {
    width: 160px;
    max-width: 40vw;
  }
}
/* ========== 深色模式覆盖 ========== */
[data-theme="dark"] .workflow-steps-card {
  background:
    radial-gradient(circle at 0 0, rgba(99, 102, 241, 0.12), transparent 55%),
    var(--bg-soft, #16213e);
  border-color: rgba(148, 163, 184, 0.1);
}

[data-theme="dark"] .workflow-mermaid-notice {
  background: rgba(180, 83, 9, 0.2);
  border-color: rgba(180, 83, 9, 0.25);
  color: var(--text-secondary, #a0a0a0);
}

[data-theme="dark"] .workflow-step-slow-hint {
  background: linear-gradient(135deg, rgba(180, 83, 9, 0.15), rgba(120, 53, 15, 0.2));
  color: var(--text-secondary, #a0a0a0);
}

[data-theme="dark"] .thinking-summary {
  background:
    linear-gradient(120deg, rgba(99, 102, 241, 0.12), rgba(129, 140, 248, 0.08)),
    var(--bg-card, #1e1e36);
}

[data-theme="dark"] .gen-status-card {
  background: rgba(239, 68, 68, 0.1);
  border-color: rgba(239, 68, 68, 0.2);
  color: var(--text-secondary, #a0a0a0);
}

[data-theme="dark"] .tool-call-card,
[data-theme="dark"] .tool-exec-card {
  background:
    radial-gradient(circle at 0 0, rgba(99, 102, 241, 0.08), transparent 55%),
    var(--bg-soft, #16213e);
  border-color: rgba(148, 163, 184, 0.08);
}

[data-theme="dark"] .visual-edit-selection-card__glow {
  background:
    radial-gradient(circle at 40% 20%, rgba(99, 102, 241, 0.15), transparent 45%),
    radial-gradient(circle at 80% 60%, rgba(56, 189, 248, 0.08), transparent 35%),
    linear-gradient(145deg, rgba(30, 30, 54, 0.98) 0%, rgba(37, 37, 66, 0.94) 48%, rgba(30, 30, 54, 0.92) 100%);
}

[data-theme="dark"] .deploy-url-pill {
  background:
    radial-gradient(circle at 0 0, rgba(34, 197, 94, 0.15), transparent 55%),
    var(--bg-card, #1e1e36) !important;
  border-color: rgba(34, 197, 94, 0.3) !important;
  color: var(--text-base, #e0e0e0) !important;
}

[data-theme="dark"] .selected-element-alert :deep(.ant-alert) {
  background:
    radial-gradient(circle at 0 0, rgba(14, 165, 233, 0.15), transparent 55%),
    var(--bg-card, #1e1e36) !important;
  border-color: rgba(14, 165, 233, 0.25) !important;
  color: var(--text-base, #e0e0e0) !important;
}

[data-theme="dark"] .workflow-step-card {
  border-color: rgba(148, 163, 184, 0.08);
}

[data-theme="dark"] .file-core-badge {
  background: linear-gradient(135deg, rgba(52, 211, 153, 0.3), rgba(16, 185, 129, 0.25));
  color: #6ee7b7;
}

[data-theme="dark"] .visual-edit-copy-btn {
  border-color: rgba(148, 163, 184, 0.15) !important;
}

[data-theme="dark"] .scroll-bottom-btn {
  background:
    radial-gradient(circle at 30% 0%, rgba(52, 211, 153, 0.3), rgba(34, 197, 94, 0.2) 40%, rgba(14, 165, 233, 0.2) 100%);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.3);
}
.tool-exec-card.tool-exec-card--compact { padding: 4px 6px; border: 0; border-radius: 4px; background: transparent; box-shadow: none; }

/* 工具栏放在内容框上方，颜色复用用户主题。 */
.preview-panel.cosmic-workbench { padding:0; background:transparent; border:0; border-radius:0; box-shadow:none; overflow:hidden; }
.cosmic-workbench .preview-header { padding:0 0 8px; margin:0; min-height:40px; gap:8px; flex-wrap:wrap; }
.cosmic-workbench .preview-header-right { margin-left:auto; flex-wrap:wrap; }
.cosmic-workbench .preview-tabs { padding:0; margin:0; gap:5px; border:0; flex-shrink:0; }
.cosmic-workbench .preview-tab { color:var(--wb-muted); border:1px solid transparent; border-radius:8px; padding:6px 13px; background:none; font-size:12px; }
.cosmic-workbench .preview-tab.is-active { color:var(--wb-text); background:var(--wb-surface); border-color:var(--wb-line); }
.cosmic-workbench .preview-body { border:1px solid var(--wb-line); border-radius:14px; background:var(--wb-bg); min-height:0; padding:0; overflow:hidden; flex-direction:column; align-items:stretch; justify-content:flex-start; }
.cosmic-workbench .preview-body > :deep(section) { width:100%; flex:1; min-width:0; }
.cosmic-workbench .preview-refresh-btn, .cosmic-workbench .deploy-url-pill { box-shadow:none; border:1px solid var(--wb-line); border-radius:8px; background:var(--wb-surface); color:var(--wb-text); }
.cosmic-workbench .preview-empty { background:var(--wb-bg); }
.cosmic-workbench .preview-empty :deep(.ant-empty-description), .cosmic-workbench .deploy-url-label { color:var(--wb-muted); }
@media (max-width: 1100px) {
  .top-bar { flex-wrap:wrap; gap:12px; }
  .top-actions { flex-wrap:wrap; }
  .app-title-wrap { min-width:0; }
  .round-count-text { white-space:nowrap; }
}
@media (max-width: 992px) {
  .main-content { display:flex; flex-direction:column; }
  .chat-panel, .preview-panel.cosmic-workbench { flex:1; height:auto; min-height:0; }
  .top-bar { padding:8px; }
  .top-actions { gap:4px; }
}
.preview-body:fullscreen { width:100vw; height:100dvh; border:0; border-radius:0; position:relative; background:var(--wb-bg); }
.fullscreen-exit-zone { position:absolute; top:0; left:0; width:110px; height:66px; z-index:50; display:flex; align-items:center; padding:12px; }
.fullscreen-exit-zone button { opacity:0; pointer-events:none; background:var(--wb-surface); }
.fullscreen-exit-zone:hover button, .fullscreen-exit-zone:focus-within button { opacity:1; pointer-events:auto; }
.fullscreen-hint { position:absolute; top:16px; left:50%; transform:translateX(-50%); z-index:51; padding:9px 16px; border:1px solid var(--wb-line); border-radius:10px; background:var(--wb-surface); color:var(--wb-text); white-space:nowrap; }
</style>
