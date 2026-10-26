/** 把 Agent 的结构化事件映射成 UI 动作。旧文本协议只留给历史和旧链路。 */

/** 框架工具名 → 前端卡片认识的中文动作。认不出的走兜底文案。 */
const TOOL_LABELS: Record<string, string> = {
  write_file: '写入文件',
  edit_file: '修改文件',
  delete: '删除文件',
  delete_file: '删除文件',
  read_file: '读取文件',
  ls: '读取目录结构',
  glob: '查找文件',
  grep: '搜索内容',
  execute: '执行命令',
  git_snapshot: '查看版本状态',
  record_finding: '记录结论',
}

/** 从工具参数里取一个能看的参数值。不同工具的参数名不一样。 */
function truncate(value: string): string {
  const text = value.trim()
  return text.length > 80 ? `${text.slice(0, 80)}…` : text
}

const PRIMARY_ARG_KEYS = [
  'file_path',
  'filePath',
  'relativeFilePath',
  'path',
  'command',
  'pattern',
  'query',
  'claim',
]

function primaryArgFromObject(payload: Record<string, unknown>): string {
  const candidates = PRIMARY_ARG_KEYS
  for (const key of candidates) {
    const value = payload[key]
    if (typeof value === 'string' && value.trim()) {
      return truncate(value)
    }
  }
  return ''
}

function primaryArgFromPreview(value: unknown): string {
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    return primaryArgFromObject(value as Record<string, unknown>)
  }
  if (typeof value !== 'string' || !value.trim()) return ''

  const text = value.trim()
  try {
    const parsed = JSON.parse(text)
    if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
      const arg = primaryArgFromObject(parsed as Record<string, unknown>)
      if (arg) return arg
    }
  } catch {
    // inputPreview 可能被截断；继续从 JSON 前缀提取关键参数。
  }

  for (const key of PRIMARY_ARG_KEYS) {
    const escaped = key.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    const matched = text.match(new RegExp(`["']?${escaped}["']?\\s*:\\s*["']([^"']+)`))
    if (matched?.[1]) return truncate(matched[1])
  }

  return text.startsWith('{') || text.startsWith('[') ? '' : truncate(text)
}

function primaryArg(payload: Record<string, unknown>): string {
  return (
    primaryArgFromObject(payload) ||
    primaryArgFromPreview(payload.input) ||
    primaryArgFromPreview(payload.inputPreview)
  )
}

export type AgentUiAction =
  | { kind: 'text'; text: string }
  | {
      kind: 'tool_started'
      callId: string
      toolName: string
      label: string
      target: string
    }
  | {
      kind: 'tool_finished'
      callId: string
      toolName: string
      label: string
      status: string
      exitCode?: number
      outputPreview: string
    }
  | { kind: 'error'; text: string; retryable?: boolean }
  | { kind: 'paused'; text: string }
  | { kind: 'done' }
  | { kind: 'model_route_fallback' }
  | { kind: 'thinking_started'; thinkingId: string; summary: string; details: string; status: string }
  | { kind: 'thinking_delta'; thinkingId: string; summary: string; details: string; status: string }
  | { kind: 'thinking_completed'; thinkingId: string; summary: string; details: string; status: string }
  | { kind: 'ignore' }

/** 映射一帧 Agent 事件。未知事件一律 ignore —— 新事件不该让前端报错。 */
export function mapAgentEvent(name: string, payload: Record<string, unknown>): AgentUiAction {
  switch (name) {
    case 'thinking.started':
    case 'thinking.delta':
    case 'thinking.completed': {
      const thinkingId = String(payload.thinkingId ?? '')
      const summary = String(payload.summary ?? '')
      const details = String(payload.details ?? '')
      const status = String(payload.status ?? (name.endsWith('completed') ? 'completed' : 'running'))
      const kind = name === 'thinking.started' ? 'thinking_started' : name === 'thinking.delta' ? 'thinking_delta' : 'thinking_completed'
      return { kind, thinkingId, summary, details, status } as AgentUiAction
    }
    case 'text.delta': {
      const content = typeof payload.content === 'string' ? payload.content : ''
      return content ? { kind: 'text', text: content } : { kind: 'ignore' }
    }

    case 'text.message': {
      // 终稿是权威全文。但文本通道是增量的，无法「替换」——
      // 正常情况下 delta 已经把内容吐完了，所以这里不重复追加。
      return { kind: 'ignore' }
    }

    case 'tool.started': {
      const toolName = String(payload.toolName ?? '')
      return {
        kind: 'tool_started',
        callId: String(payload.callId ?? ''),
        toolName,
        label: TOOL_LABELS[toolName] || (typeof payload.label === 'string' ? payload.label : '正在处理'),
        target: primaryArg(payload),
      }
    }

    case 'tool.finished': {
      const toolName = String(payload.toolName ?? '')
      return {
        kind: 'tool_finished',
        callId: String(payload.callId ?? ''),
        toolName,
        label: TOOL_LABELS[toolName] || (typeof payload.label === 'string' ? payload.label : '正在处理'),
        status: String(payload.status ?? 'succeeded'),
        ...(typeof payload.exitCode === 'number' ? { exitCode: payload.exitCode } : {}),
        outputPreview: typeof payload.outputPreview === 'string' ? payload.outputPreview : '',
      }
    }

    case 'business-error': {
      const text = String(payload.message ?? '生成过程中出现错误')
      return { kind: 'error', text, retryable: payload.retryable === true }
    }

    case 'error': {
      const text = String(payload.message ?? '生成过程中出现错误')
      // 后端把「重试也没用」的错误标成 retryable=false；不传就按可重试处理。
      return { kind: 'error', text, retryable: payload.retryable !== false }
    }

    case 'run.completed':
      // 终态不在这里收尾 —— 由随后的 done 事件统一处理，避免重复刷新。
      // 完整正文已展示时不重复追加取消摘要；旧生产者仍可用终态摘要收尾。
      if (payload.status === 'cancelled') {
        if (payload.summaryDelivered === true) return { kind: 'ignore' }
        return { kind: 'paused', text: String(payload.summary || '本轮已停止') }
      }
      return { kind: 'ignore' }

    case 'model.route.fallback':
      return { kind: 'model_route_fallback' }

    case 'done':
      return { kind: 'done' }

    default:
      // heartbeat / status / todo.delta / artifact.* 等
      return { kind: 'ignore' }
  }
}
