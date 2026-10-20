import {
  GENERATION_FAILED_MESSAGE,
  GENERATION_INTERRUPTED_MARKER,
} from '@/utils/chatGenerationStatus'

/** 从 SSE 片段中解析出的工作流步骤（与后端 CodeGenWorkflow 文案一致） */
export type WorkflowStepStatus = 'success' | 'failed' | 'pending'
export type WorkflowStepRow = { step: number; label: string; status?: WorkflowStepStatus }
export type WorkflowStageKey = 'initializing' | 'image_collecting' | 'code_generating' | 'code_checking' | 'ready'
export type WorkflowHistoryOutcome = 'success' | 'failed' | 'unknown'

const WORKFLOW_STEP_LINE_RE = /^\[workflow\]\s*第\s*(\d+)\s*步完成[：:]\s*(.+)\s*$/
const WORKFLOW_STEP_GLOBAL_RE = /\[workflow\]\s*第\s*(\d+)\s*步完成[：:]\s*([^[]+)/g
const WORKFLOW_DONE_LINE_RE = /^\[workflow\]\s*(代码生成完成|工作流结束，生成完成)\s*[。.]?\s*$/
const WORKFLOW_NOTICE_MERMAID_ERROR_RE = /^\[workflow_notice\]\s*mermaid_error\s*$/
const WORKFLOW_STAGE_STATUS_LINE_RE = /^\[workflow_stage_status\]\s*(.+)\s*$/m
const WORKFLOW_STAGE_STATUS_ITEM_RE = /(initializing|image_collecting|code_generating|code_checking|ready)\s*=\s*(success|failed|pending)/gi
const WORKFLOW_STAGE_ORDER: WorkflowStageKey[] = [
  'initializing',
  'image_collecting',
  'code_generating',
  'code_checking',
  'ready',
]
const WORKFLOW_STAGE_BASE_LABEL: Record<WorkflowStageKey, string> = {
  initializing: '初始化',
  image_collecting: '图片收集',
  code_generating: '代码生成',
  code_checking: '代码检查',
  ready: '就绪',
}

function getWorkflowStageLabel(stage: WorkflowStageKey, done: boolean): string {
  if (done) {
    if (stage === 'ready') return '就绪！'
    return `${WORKFLOW_STAGE_BASE_LABEL[stage]}已完成！`
  }
  return `${WORKFLOW_STAGE_BASE_LABEL[stage]}中...`
}

function getWorkflowStageLabelByStatus(stage: WorkflowStageKey, status: WorkflowStepStatus): string {
  if (status === 'failed') return `${WORKFLOW_STAGE_BASE_LABEL[stage]}出现问题`
  if (status === 'pending') return `${WORKFLOW_STAGE_BASE_LABEL[stage]}中...`
  if (stage === 'ready') return '就绪！'
  return `${WORKFLOW_STAGE_BASE_LABEL[stage]}已完成！`
}

function parseWorkflowStageStatusFromContent(rawContent: string): Partial<Record<WorkflowStageKey, WorkflowStepStatus>> | null {
  const text = (rawContent ?? '').trim()
  if (!text) return null
  const markerMatch = text.match(WORKFLOW_STAGE_STATUS_LINE_RE)
  if (!markerMatch) return null
  const markerBody = markerMatch[1] ?? ''
  const stageStatusMap: Partial<Record<WorkflowStageKey, WorkflowStepStatus>> = {}
  for (const item of markerBody.matchAll(WORKFLOW_STAGE_STATUS_ITEM_RE)) {
    const stage = item[1] as WorkflowStageKey
    const status = item[2] as WorkflowStepStatus
    stageStatusMap[stage] = status
  }
  return Object.keys(stageStatusMap).length > 0 ? stageStatusMap : null
}

function buildWorkflowRowsFromStageStatus(stageStatusMap: Partial<Record<WorkflowStageKey, WorkflowStepStatus>>): WorkflowStepRow[] {
  return WORKFLOW_STAGE_ORDER.map((stage, index) => {
    const status = stageStatusMap[stage] ?? 'success'
    return {
      step: index + 1,
      label: getWorkflowStageLabelByStatus(stage, status),
      status,
    }
  })
}

function classifyWorkflowStage(rawLabel: string): WorkflowStageKey | null {
  const t = (rawLabel ?? '').trim()
  if (!t) return null
  if (/就绪|完成|结束/.test(t)) return 'ready'
  if (/图片|收集|图像|插画|logo|架构图/i.test(t)) return 'image_collecting'
  if (/代码质量检查|质量检查|质检|检查/.test(t)) return 'code_checking'
  if (/智能路由|路由/.test(t)) return 'code_generating'
  if (/代码生成|项目构建|生成代码|生成/.test(t)) return 'code_generating'
  if (/初始化|提示词增强|开始准备/.test(t)) return 'initializing'
  return null
}

/** 与后端 UserFacingOutputSanitizer 对齐：用户可见正文中的内部技术栈/部署术语 */
const TOOL_PROTOCOL_LINE_RE = /^\[(?:选择工具|工具调用|workflow(?:_stage_status|_notice)?)\]/i
const STACK_JARGON_LINE_RES: RegExp[] = [
  /vue\s*3/i,
  /\bvite\b/i,
  /vue\s*router/i,
  /hash\s*模式/i,
  /hash\s*路由/i,
  /子路径部署/i,
  /部署配置/i,
  /可构建/i,
  /可运行/i,
  /npm\s+(?:install|run|build)/i,
  /主要技术栈/i,
  /技术栈/i,
]
// Agent 内部工作日志（英文计划/沙箱说明）不属于用户答案，禁止泄漏到聊天正文。
const INTERNAL_AGENT_LOG_LINE_RE = /^(?:i['’]?ll\s|the workspace\b|let me\s|now i['’]?ll\s|no (?:python|node)\b|all (?:the )?\w+ (?:is|are)\b|structure is\s)/i

function isStackJargonLine(trimmed: string): boolean {
  if (!trimmed) return false
  if (TOOL_PROTOCOL_LINE_RE.test(trimmed)) return false
  return STACK_JARGON_LINE_RES.some((re) => re.test(trimmed))
}

const WORKFLOW_HISTORY_SUCCESS_RE = /(代码生成完成|工作流结束|构建成功|生成完成|ready)/i
const WORKFLOW_HISTORY_FAILED_RE = /(失败|异常|error|中断|出现问题|超时)/i
const LEGACY_AI_ERROR_LINE_RE = /^AI回复失败[:：]/
const WORKFLOW_STAGE_FAILED_RE: Record<WorkflowStageKey, RegExp> = {
  initializing: /(初始化|提示词增强|开始准备)/i,
  image_collecting: /(图片|收集|图像|插画|logo|架构图)/i,
  code_generating: /(代码生成|项目构建|智能路由|生成代码|生成)/i,
  code_checking: /(代码质量检查|质量检查|质检|检查)/i,
  ready: /(就绪|完成|结束)/i,
}

/**
 * 解析历史消息的工作流终态（success/failed/unknown）。
 * @param rawContent 历史消息全文
 * @return 历史消息终态
 */
export function resolveWorkflowHistoryOutcomeFromContent(rawContent: string): WorkflowHistoryOutcome {
  const text = (rawContent ?? '').trim()
  if (!text) return 'unknown'
  const stageStatusMap = parseWorkflowStageStatusFromContent(text)
  if (stageStatusMap) {
    const hasFailed = WORKFLOW_STAGE_ORDER.some((stage) => stageStatusMap[stage] === 'failed')
    return hasFailed ? 'failed' : 'success'
  }
  if (WORKFLOW_HISTORY_FAILED_RE.test(text)) return 'failed'
  if (WORKFLOW_HISTORY_SUCCESS_RE.test(text)) return 'success'
  return 'unknown'
}

/**
 * 在失败终态下推断失败阶段，用于裁剪历史卡片并标记“出现问题”。
 * @param rows 当前可解析到的工作流步骤
 * @param rawContent 历史消息全文
 * @return 失败阶段，无法推断时返回代码生成阶段
 */
function resolveFailedStage(rows: WorkflowStepRow[] | undefined, rawContent: string): WorkflowStageKey {
  const text = rawContent ?? ''
  for (const stage of WORKFLOW_STAGE_ORDER.slice().reverse()) {
    if (WORKFLOW_STAGE_FAILED_RE[stage].test(text)) {
      return stage
    }
  }
  const existing = rows ?? []
  for (let i = existing.length - 1; i >= 0; i--) {
    const stage = classifyWorkflowStage(existing[i]?.label ?? '')
    if (stage) {
      return stage
    }
  }
  return 'code_generating'
}

function hasOnlyInitializingPlaceholder(rows: WorkflowStepRow[] | undefined): boolean {
  if (!rows || rows.length !== 1) return false
  return classifyWorkflowStage(rows[0]?.label ?? '') === 'initializing'
}

function hasNonInitializingStage(rows: WorkflowStepRow[]): boolean {
  return rows.some((row) => {
    const stage = classifyWorkflowStage(row.label)
    return stage != null && stage !== 'initializing'
  })
}

export function resolveWorkflowStepsFromMessageContent(
  currentSteps: WorkflowStepRow[] | undefined,
  rawContent: string,
): WorkflowStepRow[] {
  const parsed = parseWorkflowStepsFromText(rawContent)
  if (!currentSteps || currentSteps.length === 0) return parsed
  if (hasOnlyInitializingPlaceholder(currentSteps) && hasNonInitializingStage(parsed)) {
    return parsed
  }
  return currentSteps
}

export function normalizeWorkflowStepsForUi(
  rows: WorkflowStepRow[] | undefined,
  opts?: { historyMode?: boolean; streaming?: boolean; historyOutcome?: WorkflowHistoryOutcome; rawContent?: string },
): WorkflowStepRow[] {
  const isHistoryMode = opts?.historyMode === true
  const rawContent = opts?.rawContent ?? ''
  const stageStatusMap = isHistoryMode ? parseWorkflowStageStatusFromContent(rawContent) : null
  if (stageStatusMap) {
    return buildWorkflowRowsFromStageStatus(stageStatusMap)
  }
  if (!rows || rows.length === 0) return []
  const stageSet = new Set<WorkflowStageKey>()
  // 只要存在工作流卡片，就固定在最前面展示“初始化”
  stageSet.add('initializing')
  for (const row of rows) {
    const stage = classifyWorkflowStage(row.label)
    if (stage) {
      stageSet.add(stage)
    }
  }
  const isStreaming = opts?.streaming === true
  const historyOutcome = opts?.historyOutcome ?? 'unknown'

  if (isHistoryMode && historyOutcome === 'success') {
    return WORKFLOW_STAGE_ORDER.map((stage, index) => ({
      step: index + 1,
      label: getWorkflowStageLabel(stage, true),
    }))
  }

  if (isHistoryMode && historyOutcome === 'failed') {
    const failedStage = resolveFailedStage(rows, rawContent)
    const failedIndex = WORKFLOW_STAGE_ORDER.indexOf(failedStage)
    if (failedIndex < 0) return []
    const displayStages = WORKFLOW_STAGE_ORDER.slice(0, failedIndex + 1)
    return displayStages.map((stage, index) => ({
      step: index + 1,
      label: stage === failedStage
        ? `${WORKFLOW_STAGE_BASE_LABEL[stage]}出现问题`
        : getWorkflowStageLabel(stage, true),
    }))
  }

  const orderedStages = WORKFLOW_STAGE_ORDER.filter((s) => stageSet.has(s))
  const displayStages = [...orderedStages]

  // 仅在流式阶段前移一节：后端到达 X 时，前端展示到 X+1
  if (isStreaming && !isHistoryMode && displayStages.length > 0) {
    const lastStage = displayStages[displayStages.length - 1]
    const lastIdx = lastStage ? WORKFLOW_STAGE_ORDER.indexOf(lastStage) : -1
    if (lastIdx >= 0 && lastIdx < WORKFLOW_STAGE_ORDER.length - 1) {
      const nextStage = WORKFLOW_STAGE_ORDER[lastIdx + 1]
      if (nextStage && !stageSet.has(nextStage)) {
        displayStages.push(nextStage)
      }
    }
  }
  const latestIdx = displayStages.length - 1

  return displayStages.map((stage, index) => {
    const done = isHistoryMode || (!isStreaming || index < latestIdx)
    return {
      step: index + 1,
      label: getWorkflowStageLabel(stage, done),
    }
  })
}

/** 非流式全文：提取步骤并去重（按 step 保留最后一条 label） */
export function parseWorkflowStepsFromText(raw: string): WorkflowStepRow[] {
  if (!raw?.trim()) return []
  const byStep = new Map<number, string>()
  for (const line of raw.split(/\r?\n/)) {
    const t = line.trim()
    const m = t.match(WORKFLOW_STEP_LINE_RE)
    if (m) {
      const step = Number(m[1])
      if (Number.isFinite(step)) {
        byStep.set(step, (m[2] ?? '').trim())
      }
      continue
    }
    if (WORKFLOW_DONE_LINE_RE.test(t)) {
      const nextStep = byStep.size > 0 ? Math.max(...byStep.keys()) + 1 : 1
      byStep.set(nextStep, '代码生成完成')
      continue
    }
    // 兼容历史脏数据：多个 [workflow] 粘在同一行
    for (const item of extractWorkflowStepsFromText(t)) {
      byStep.set(item.step, item.label)
    }
  }
  return [...byStep.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([step, label]) => ({ step, label }))
}

function extractWorkflowStepsFromText(text: string): WorkflowStepRow[] {
  if (!text) return []
  const out: WorkflowStepRow[] = []
  for (const m of text.matchAll(WORKFLOW_STEP_GLOBAL_RE)) {
    const step = Number(m[1] ?? '')
    if (!Number.isFinite(step)) continue
    const label = (m[2] ?? '').trim()
    if (!label) continue
    out.push({ step, label })
  }
  return out
}

const INTERNAL_DIR_LINE_RE = /^(生成目录|构建目录)\s*[:：].+$/
const SHORT_NOISE_ENGLISH_WORD_RE = /^[a-z]{1,14}$/i
const PROTOCOL_FRAGMENT_RE = /^(data:|event:|id:|retry:|workflow_notice|workflow)\b/i
const TRAILING_NOISE_TOKEN_RE = /\b(background|color|padding|margin|border|font|display|width|height)\s*$/i

export type SseLineAccumulator = { carry: string }
export type WorkflowSseFilterResult = {
  uiText: string
  newSteps: WorkflowStepRow[]
  mermaidErrorNotice: boolean
}

function maybeAppendDoneStep(source: string, steps: WorkflowStepRow[]) {
  if (!WORKFLOW_DONE_LINE_RE.test(source.trim())) return
  const maxStep = steps.length > 0 ? Math.max(...steps.map((s) => s.step)) : 0
  steps.push({ step: maxStep + 1, label: '代码生成完成' })
}

function normalizeUiText(text: string): string {
  if (!text) return ''
  return text
    .replace(/\r\n/g, '\n')
    .replace(/\n{3,}/g, '\n\n')
}

function isLikelyReadableCarryText(text: string): boolean {
  const t = (text ?? '').trim()
  if (!t) return false
  if (INTERNAL_DIR_LINE_RE.test(t)) return false
  if (WORKFLOW_STEP_LINE_RE.test(t)) return false
  if (WORKFLOW_DONE_LINE_RE.test(t)) return false
  if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(t)) return false
  if (PROTOCOL_FRAGMENT_RE.test(t)) return false
  if (SHORT_NOISE_ENGLISH_WORD_RE.test(t)) return false

  // 存在中日韩字符，通常是用户可读正文
  if (/[\u4e00-\u9fa5]/.test(t)) return true

  // 英文至少两个词，或有正常句子标点，才允许尾包回灌
  const words = t.split(/\s+/).filter(Boolean)
  if (words.length >= 2) return true
  if (/[.!?;,:，。！？；：]/.test(t)) return true
  return false
}

function stripInlineNoiseFragments(line: string): string {
  if (!line) return ''
  let out = line
    .replace(WORKFLOW_STEP_GLOBAL_RE, '')
    .replace(WORKFLOW_NOTICE_MERMAID_ERROR_RE, '')
    .replace(/\[workflow\][^\n\r]*/g, '')
    .replace(/\b(?:data|event|id|retry)\s*:\s*[^\s]+/gi, '')
    .replace(/\s{2,}/g, ' ')
    .trim()

  if (TRAILING_NOISE_TOKEN_RE.test(out)) {
    out = out.replace(TRAILING_NOISE_TOKEN_RE, '').trim()
  }
  return out
}

/**
 * 将 SSE chunk 与上行残留拼接，按行过滤噪声；未完成行留在 carry。
 * 返回供 `processAssistantChunkIntoUiState` 使用的正文片段（可能为空字符串）。
 */
export function filterAssistantSseChunkForUi(
  acc: SseLineAccumulator,
  chunk: string,
): WorkflowSseFilterResult {
  const s = acc.carry + (chunk ?? '')
  const newSteps: WorkflowStepRow[] = []
  let mermaidErrorNotice = false
  if (!s) {
    return { uiText: '', newSteps, mermaidErrorNotice }
  }

  const endsWithBreak = /\r?\n$/.test(s)
  const allParts = s.split(/\r?\n/)

  // 单行且无换行结尾：整段就是一条 [workflow] 时立即消费（避免步骤卡片延迟到下一个包）
  if (!endsWithBreak && allParts.length === 1) {
    const singleLine = allParts[0] ?? ''
    if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(singleLine.trim())) {
      acc.carry = ''
      return { uiText: '', newSteps, mermaidErrorNotice: true }
    }
    maybeAppendDoneStep(singleLine, newSteps)
    if (newSteps.length > 0 && singleLine.replace(WORKFLOW_DONE_LINE_RE, '').trim().length === 0) {
      acc.carry = ''
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
    const immediate = extractWorkflowStepsFromText(singleLine)
    if (immediate.length > 0 && singleLine.replace(WORKFLOW_STEP_GLOBAL_RE, '').trim().length === 0) {
      newSteps.push(...immediate)
      acc.carry = ''
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
  }

  const completeLines = endsWithBreak ? allParts : allParts.slice(0, -1)
  acc.carry = endsWithBreak ? '' : (allParts[allParts.length - 1] ?? '')

  const kept: string[] = []
  for (const line of completeLines) {
    const cleanedLine = line.replace(WORKFLOW_STEP_GLOBAL_RE, (_match, step, label) => {
      const s = Number(step)
      const l = (label ?? '').trim()
      if (Number.isFinite(s) && l) newSteps.push({ step: s, label: l })
      return ''
    })
    const trimmed = cleanedLine.trim()
    if (trimmed) {
      if (WORKFLOW_DONE_LINE_RE.test(trimmed)) {
        maybeAppendDoneStep(trimmed, newSteps)
        continue
      }
      if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(trimmed)) {
        mermaidErrorNotice = true
        continue
      }
      if (INTERNAL_DIR_LINE_RE.test(trimmed)) continue
    }
    kept.push(cleanedLine)
  }

  const filteredKept = kept.filter((line) => {
    const t = line.trim()
    if (!t) return true
    if (isStackJargonLine(t)) return false
    if (t.length <= 2 && !/[A-Za-z0-9\u4e00-\u9fa5]/.test(t)) return false
    return true
  })

  const uiText = normalizeUiText(filteredKept.join('\n') + (endsWithBreak && completeLines.length > 0 ? '\n' : ''))
  return { uiText, newSteps, mermaidErrorNotice }
}

/** 流结束后：把 carry 中剩余半行也过滤一遍（若无换行结尾） */
export function flushAssistantSseCarry(acc: SseLineAccumulator): WorkflowSseFilterResult {
  const tail = acc.carry
  acc.carry = ''
  if (!tail) return { uiText: '', newSteps: [], mermaidErrorNotice: false }
  const trimmed = tail.trim()
  const newSteps: WorkflowStepRow[] = []
  const mermaidErrorNotice = false
  if (trimmed) {
    if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(trimmed)) {
      return { uiText: '', newSteps, mermaidErrorNotice: true }
    }
    maybeAppendDoneStep(trimmed, newSteps)
    if (newSteps.length > 0 && trimmed.replace(WORKFLOW_DONE_LINE_RE, '').trim().length === 0) {
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
    const immediate = extractWorkflowStepsFromText(trimmed)
    if (immediate.length > 0 && trimmed.replace(WORKFLOW_STEP_GLOBAL_RE, '').trim().length === 0) {
      newSteps.push(...immediate)
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
    if (INTERNAL_DIR_LINE_RE.test(trimmed)) {
      return { uiText: '', newSteps, mermaidErrorNotice }
    }
  }
  if (!isLikelyReadableCarryText(tail)) {
    return { uiText: '', newSteps, mermaidErrorNotice }
  }
  return { uiText: normalizeUiText(tail), newSteps, mermaidErrorNotice }
}

export function resetSseLineAccumulator(acc: SseLineAccumulator) {
  acc.carry = ''
}

/** 历史回放/全文解析：去掉不应展示给用户的行 */
export function stripAssistantNoiseLines(text: string): string {
  if (!text) return ''
  const out: string[] = []

  // Fence-aware: fenced code blocks must preserve whitespace exactly.
  // We only strip workflow/protocol noise outside fences.
  let inFence = false

  for (const line of text.split(/\r?\n/)) {
    const fenceCandidate = line.replace(/^ {0,3}/, '')
    const isFenceLine = fenceCandidate.startsWith('```')
    if (isFenceLine) {
      inFence = !inFence
      out.push(line)
      continue
    }

    if (inFence) {
      out.push(line)
      continue
    }

    const t = line.trim()
    if (!t) {
      out.push(line)
      continue
    }
    if (WORKFLOW_STAGE_STATUS_LINE_RE.test(t)) continue
    if (WORKFLOW_STEP_LINE_RE.test(t)) continue
    if (WORKFLOW_DONE_LINE_RE.test(t)) continue
    if (WORKFLOW_NOTICE_MERMAID_ERROR_RE.test(t)) continue
    if (t === GENERATION_FAILED_MESSAGE || t === GENERATION_INTERRUPTED_MARKER) continue
    if (LEGACY_AI_ERROR_LINE_RE.test(t)) continue
    if (INTERNAL_DIR_LINE_RE.test(t)) continue
    if (isStackJargonLine(t)) continue
    if (INTERNAL_AGENT_LOG_LINE_RE.test(t)) continue
    const stripped = stripInlineNoiseFragments(line)
    if (!stripped) continue
    if (SHORT_NOISE_ENGLISH_WORD_RE.test(stripped)) continue
    if (PROTOCOL_FRAGMENT_RE.test(stripped)) continue
    out.push(stripped)
  }

  return out.join('\n')
}

export function mergeWorkflowSteps(
  existing: WorkflowStepRow[] | undefined,
  delta: WorkflowStepRow[],
): WorkflowStepRow[] {
  const map = new Map<number, string>()
  for (const r of existing ?? []) {
    map.set(r.step, r.label)
  }
  for (const r of delta) {
    map.set(r.step, r.label)
  }
  return [...map.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([step, label]) => ({ step, label }))
}
