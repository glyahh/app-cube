import type { AgentUiAction } from './agentEventMapping'

export type ThinkingSegment = {
  kind: 'thinking'
  thinkingId: string
  summary: string
  details: string[]
  done: boolean
  segmentId?: string
}
export type ThinkingAction = Extract<AgentUiAction, { kind: 'thinking_started' | 'thinking_delta' | 'thinking_completed' }>

/** 历史 JSON 与实时事件使用同一份阶段结构；无记录时不补造。 */
export function parseThinkingSegments(value: unknown): ThinkingSegment[] {
  if (typeof value === 'string') {
    try { value = JSON.parse(value) } catch { return [] }
  }
  if (!Array.isArray(value)) return []
  return value.flatMap(item => {
    if (!item || typeof item.thinkingId !== 'string' || !item.thinkingId ||
      typeof item.summary !== 'string' || !item.summary.trim()) return []
    return [{ kind: 'thinking' as const, thinkingId: item.thinkingId, summary: item.summary,
      details: (Array.isArray(item.details) ? item.details : [item.details])
        .filter((detail: unknown): detail is string => typeof detail === 'string' && !!detail.trim()),
      done: ['completed', 'failed', 'cancelled'].includes(item.status),
    }]
  })
}

/** 更新同一阶段；结束帧中的空详情不擦除已经收到的内容。 */
export function mergeThinkingAction(segments: ThinkingSegment[], action: ThinkingAction): ThinkingSegment | undefined {
  if (!action.thinkingId) return
  let segment = segments.find(item => item.thinkingId === action.thinkingId)
  if (!segment) {
    if (!action.summary.trim()) return
    segment = { kind: 'thinking', thinkingId: action.thinkingId, summary: action.summary, details: [], done: false }
    segments.push(segment)
  }
  if (action.summary.trim()) segment.summary = action.summary
  if (action.details && !segment.details.includes(action.details)) segment.details.push(action.details)
  segment.done ||= action.kind === 'thinking_completed'
  return segment
}
