/**
 * 把 Agent 的结构化事件映射成前端已有的 UI 动作。
 *
 * 为什么转成文本而不是新建一条渲染路径：现有工具卡片是靠解析流里的
 * `[工具调用] 写入文件 <path>` 这类标签渲染的（见 assistantToolStreamProtocol）。
 * 复用它可以少写一整套 UI 状态机，代价是多一次字符串拼装。
 *
 * 抽成纯函数是为了能测 —— 这层映射错了，前端会静默不显示或显示错。
 */

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
function primaryArg(payload: Record<string, unknown>): string {
  const candidates = ['file_path', 'path', 'command', 'pattern', 'claim']
  for (const key of candidates) {
    const value = payload[key]
    if (typeof value === 'string' && value.trim()) {
      return value.trim().length > 80 ? `${value.trim().slice(0, 80)}…` : value.trim()
    }
  }
  // 参数在 input 里（不同版本的载荷形状不同）
  const input = payload.input
  if (typeof input === 'string' && input.trim()) {
    const text = input.trim()
    return text.length > 80 ? `${text.slice(0, 80)}…` : text
  }
  return ''
}

export type AgentUiAction =
  | { kind: 'text'; text: string }
  | { kind: 'error'; text: string }
  | { kind: 'done' }
  | { kind: 'ignore' }

/** 映射一帧 Agent 事件。未知事件一律 ignore —— 新事件不该让前端报错。 */
export function mapAgentEvent(name: string, payload: Record<string, unknown>): AgentUiAction {
  switch (name) {
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
      const label = TOOL_LABELS[toolName] ?? toolName ?? '调用工具'
      const arg = primaryArg(payload)
      // 标签行会把冒号后的整段都捕获进卡片标题，带上参数更有信息量
      return { kind: 'text', text: `\n\n[选择工具] ${label}${arg ? ` ${arg}` : ''}\n` }
    }

    case 'tool.finished': {
      const toolName = String(payload.toolName ?? '')
      const label = TOOL_LABELS[toolName] ?? toolName
      const arg = primaryArg(payload)
      const ok = String(payload.status ?? 'succeeded') === 'succeeded'
      const mark = ok ? '' : '（失败）'
      return { kind: 'text', text: `[工具调用] ${label}${mark}${arg ? ` ${arg}` : ''}\n` }
    }

    case 'business-error': {
      const text = String(payload.message ?? '生成过程中出现错误')
      return { kind: 'error', text }
    }

    case 'error': {
      const text = String(payload.message ?? '生成过程中出现错误')
      return { kind: 'error', text }
    }

    case 'run.completed':
      // 终态不在这里收尾 —— 由随后的 done 事件统一处理，避免重复刷新
      return { kind: 'ignore' }

    case 'done':
      return { kind: 'done' }

    default:
      // heartbeat / status / todo.delta / artifact.* 等
      return { kind: 'ignore' }
  }
}
